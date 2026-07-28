use std::{path::Path, sync::Arc};

use axum::{
    Json, Router,
    body::Body,
    extract::{DefaultBodyLimit, Path as AxumPath, Query, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, put},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use futures_util::StreamExt;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::{
    fs::{self, OpenOptions},
    io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt},
    sync::Mutex,
};
use tokio_util::io::ReaderStream;

use crate::{
    config::{
        MAX_ATTACHMENT_ENVELOPE_BYTES, MAX_CHANGE_PAGE_SIZE, MAX_ITEM_ENVELOPE_BYTES,
        PROTOCOL_VERSION, RelayConfig, is_lower_hex_sha256, is_valid_id, sha256_hex,
    },
    error::{ApiError, RelayError},
    model::{
        AttachmentReceipt, ChangePage, DeleteMutationRequest, ItemMutationRequest,
        KeyCheckEnvelope, RelayDiscovery, RelayInformation, RelayKeyDerivation, RelayLimits,
        RelayTlsIdentity, StoredMutation,
    },
    storage::{
        AttachmentCommit, AttachmentRecord, KeyCheckInitialization, Storage, StoredOperation,
    },
};

pub const PROTOCOL_HEADER: &str = "x-vaultnote-protocol";
pub const OPERATION_HEADER: &str = "x-vaultnote-operation-id";
pub const CIPHERTEXT_SHA256_HEADER: &str = "x-vaultnote-ciphertext-sha256";
const MAX_JSON_BODY_BYTES: usize = (MAX_ITEM_ENVELOPE_BYTES * 4 / 3) + 64 * 1024;
const MAX_KEY_CHECK_ENVELOPE_BYTES: usize = 4 * 1024;

#[derive(Clone)]
pub struct AppState {
    config: Arc<RelayConfig>,
    storage: Arc<Storage>,
    authentication_token_sha256: [u8; 32],
    attachment_commit_lock: Arc<Mutex<()>>,
}

impl AppState {
    pub fn new(config: RelayConfig, storage: Storage) -> Result<Self, RelayError> {
        let authentication_token_sha256 = decode_sha256_hex(&config.authentication_token_sha256)
            .ok_or(RelayError::InvalidConfiguration)?;
        Ok(Self {
            config: Arc::new(config),
            storage: Arc::new(storage),
            authentication_token_sha256,
            attachment_commit_lock: Arc::new(Mutex::new(())),
        })
    }

    fn authorize(&self, headers: &HeaderMap) -> Result<(), ApiError> {
        let protocol = headers
            .get(PROTOCOL_HEADER)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<u32>().ok());
        if protocol != Some(PROTOCOL_VERSION) {
            return Err(ApiError::protocol_mismatch());
        }
        let token = headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .filter(|value| value.starts_with("vns_") && value.len() <= 128)
            .ok_or_else(ApiError::unauthorized)?;
        let digest: [u8; 32] = Sha256::digest(token.as_bytes()).into();
        if digest.ct_eq(&self.authentication_token_sha256).unwrap_u8() != 1 {
            return Err(ApiError::unauthorized());
        }
        Ok(())
    }
}

pub fn router(state: AppState) -> Router {
    let json_routes = Router::new()
        .route("/v1/relay", get(relay_information))
        .route("/v1/key-check", get(get_key_check).put(put_key_check))
        .route("/v1/items/{item_id}", put(upsert_item).delete(delete_item))
        .route("/v1/changes", get(pull_changes))
        .layer(DefaultBodyLimit::max(MAX_JSON_BODY_BYTES));
    let attachment_routes = Router::new()
        .route(
            "/v1/attachments/{attachment_id}",
            put(upload_attachment)
                .get(download_attachment)
                .head(inspect_attachment)
                .delete(delete_attachment),
        )
        .layer(DefaultBodyLimit::disable());
    Router::new()
        .route("/health", get(health))
        .merge(json_routes)
        .merge(attachment_routes)
        .with_state(state)
}

async fn health() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "ok",
            "protocolVersion": PROTOCOL_VERSION,
        })),
    )
}

async fn relay_information(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<RelayInformation>, ApiError> {
    state.authorize(&headers)?;
    Ok(Json(RelayInformation {
        protocol_version: PROTOCOL_VERSION,
        minimum_client_protocol_version: PROTOCOL_VERSION,
        vault_id: state.config.vault_id.clone(),
        tls_identity: RelayTlsIdentity {
            dns_name: state.config.tls.dns_name.clone(),
            certificate_sha256: state.config.tls.certificate_sha256.clone(),
        },
        discovery: RelayDiscovery {
            service_type: crate::discovery::SERVICE_TYPE.to_owned(),
        },
        key_derivation: RelayKeyDerivation {
            algorithm: state.config.key_derivation.algorithm.clone(),
            iterations: state.config.key_derivation.iterations,
            salt: state.config.key_derivation.salt.clone(),
            key_bits: state.config.key_derivation.key_bits,
        },
        limits: RelayLimits {
            maximum_item_envelope_bytes: MAX_ITEM_ENVELOPE_BYTES,
            maximum_attachment_envelope_bytes: MAX_ATTACHMENT_ENVELOPE_BYTES,
            maximum_change_page_size: MAX_CHANGE_PAGE_SIZE,
        },
    }))
}

async fn get_key_check(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    let storage = Arc::clone(&state.storage);
    let envelope = blocking(move || storage.get_key_check()).await?;
    match envelope {
        Some(value) => Ok(Json(value).into_response()),
        None => Err(ApiError::not_found()),
    }
}

async fn put_key_check(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(envelope): Json<KeyCheckEnvelope>,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_envelope(
        &envelope.encrypted_key_check,
        &envelope.ciphertext_sha256,
        MAX_KEY_CHECK_ENVELOPE_BYTES,
    )?;
    let storage = Arc::clone(&state.storage);
    let result = blocking(move || storage.initialize_key_check(&envelope)).await?;
    match result {
        KeyCheckInitialization::Created => Ok(StatusCode::CREATED.into_response()),
        KeyCheckInitialization::AlreadyIdentical => Ok(StatusCode::NO_CONTENT.into_response()),
        KeyCheckInitialization::AlreadyDifferent => {
            Err(ApiError::conflict("key_check_already_initialized"))
        }
    }
}

async fn upsert_item(
    State(state): State<AppState>,
    AxumPath(item_id): AxumPath<String>,
    headers: HeaderMap,
    Json(request): Json<ItemMutationRequest>,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&item_id)?;
    let operation_id = operation_id(&headers)?;
    validate_version_token(request.expected_version_token.as_deref())?;
    validate_envelope(
        &request.encrypted_payload,
        &request.ciphertext_sha256,
        MAX_ITEM_ENVELOPE_BYTES,
    )?;
    let request_hash = hash_parts(&[
        b"UPSERT_ITEM",
        item_id.as_bytes(),
        request
            .expected_version_token
            .as_deref()
            .unwrap_or("")
            .as_bytes(),
        request.ciphertext_sha256.as_bytes(),
    ]);
    let storage = Arc::clone(&state.storage);
    let result = blocking(move || {
        storage.upsert_item(
            &operation_id,
            &item_id,
            &request_hash,
            request.expected_version_token.as_deref(),
            &request.encrypted_payload,
            &request.ciphertext_sha256,
        )
    })
    .await?;
    mutation_response(result)
}

async fn delete_item(
    State(state): State<AppState>,
    AxumPath(item_id): AxumPath<String>,
    headers: HeaderMap,
    Json(request): Json<DeleteMutationRequest>,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&item_id)?;
    let operation_id = operation_id(&headers)?;
    validate_version_token(request.expected_version_token.as_deref())?;
    let request_hash = hash_parts(&[
        b"DELETE_ITEM",
        item_id.as_bytes(),
        request
            .expected_version_token
            .as_deref()
            .unwrap_or("")
            .as_bytes(),
    ]);
    let storage = Arc::clone(&state.storage);
    let result = blocking(move || {
        storage.delete_item(
            &operation_id,
            &item_id,
            &request_hash,
            request.expected_version_token.as_deref(),
        )
    })
    .await?;
    mutation_response(result)
}

#[derive(Deserialize)]
struct ChangeQuery {
    cursor: Option<String>,
    limit: Option<u32>,
}

async fn pull_changes(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<ChangeQuery>,
) -> Result<Json<ChangePage>, ApiError> {
    state.authorize(&headers)?;
    let after_revision = query
        .cursor
        .as_deref()
        .unwrap_or("0")
        .parse::<i64>()
        .ok()
        .filter(|value| *value >= 0)
        .ok_or_else(ApiError::invalid_request)?;
    let limit = query.limit.unwrap_or(100).clamp(1, MAX_CHANGE_PAGE_SIZE);
    let storage = Arc::clone(&state.storage);
    let page = blocking(move || storage.pull_changes(after_revision, limit)).await?;
    Ok(Json(page))
}

async fn upload_attachment(
    State(state): State<AppState>,
    AxumPath(attachment_id): AxumPath<String>,
    headers: HeaderMap,
    body: Body,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&attachment_id)?;
    let operation_id = operation_id(&headers)?;
    let expected_sha256 = header_value(&headers, CIPHERTEXT_SHA256_HEADER)?;
    if !is_lower_hex_sha256(&expected_sha256) {
        return Err(ApiError::invalid_request());
    }
    let expected_size = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|size| *size > 0 && *size <= MAX_ATTACHMENT_ENVELOPE_BYTES)
        .ok_or_else(ApiError::payload_too_large)?;
    let request_hash = hash_parts(&[
        b"UPLOAD_ATTACHMENT",
        attachment_id.as_bytes(),
        expected_sha256.as_bytes(),
        expected_size.to_string().as_bytes(),
    ]);
    let storage = Arc::clone(&state.storage);
    let lookup_operation = operation_id.clone();
    let lookup_attachment = attachment_id.clone();
    let lookup_hash = request_hash.clone();
    if let Some(existing) = blocking(move || {
        storage.existing_attachment_upload(&lookup_operation, &lookup_attachment, &lookup_hash)
    })
    .await?
    {
        return stored_attachment_response(existing);
    }

    let pending_path = state.storage.new_pending_attachment_path();
    let streamed = stream_attachment(body, &pending_path, expected_size).await;
    let (actual_size, actual_sha256) = match streamed {
        Ok(value) => value,
        Err(error) => {
            let _ = fs::remove_file(&pending_path).await;
            return Err(error);
        }
    };
    if actual_size != expected_size || actual_sha256 != expected_sha256 {
        let _ = fs::remove_file(&pending_path).await;
        return Err(ApiError::corrupted_upload());
    }

    let _commit_guard = state.attachment_commit_lock.lock().await;
    let storage = Arc::clone(&state.storage);
    let pending_for_commit = pending_path.clone();
    let result = blocking(move || {
        storage.commit_attachment_upload(
            &operation_id,
            &attachment_id,
            &request_hash,
            &actual_sha256,
            actual_size,
            &pending_for_commit,
        )
    })
    .await;
    let _ = fs::remove_file(&pending_path).await;
    match result? {
        AttachmentCommit::Complete(receipt) => {
            Ok((StatusCode::CREATED, Json(receipt)).into_response())
        }
        AttachmentCommit::IdempotencyMismatch => Err(ApiError::conflict("idempotency_key_reused")),
        AttachmentCommit::ImmutableConflict => Err(ApiError::conflict("attachment_id_conflict")),
    }
}

async fn inspect_attachment(
    State(state): State<AppState>,
    AxumPath(attachment_id): AxumPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&attachment_id)?;
    let record = attachment_record(&state, attachment_id).await?;
    Ok(attachment_headers(
        &record,
        StatusCode::OK,
        Body::empty(),
        None,
    ))
}

async fn download_attachment(
    State(state): State<AppState>,
    AxumPath(attachment_id): AxumPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&attachment_id)?;
    let record = attachment_record(&state, attachment_id).await?;
    let path = state.storage.attachment_file(&record);
    let mut file = fs::File::open(path)
        .await
        .map_err(|_| ApiError::unavailable())?;
    let metadata = file.metadata().await.map_err(|_| ApiError::unavailable())?;
    if metadata.len() != record.ciphertext_size {
        return Err(ApiError::unavailable());
    }
    let requested_range = parse_byte_range(headers.get(header::RANGE), record.ciphertext_size)?;
    let (status, body) = match requested_range {
        None => (StatusCode::OK, Body::from_stream(ReaderStream::new(file))),
        Some((start, end)) => {
            file.seek(std::io::SeekFrom::Start(start))
                .await
                .map_err(|_| ApiError::unavailable())?;
            let length = end - start + 1;
            (
                StatusCode::PARTIAL_CONTENT,
                Body::from_stream(ReaderStream::new(file.take(length))),
            )
        }
    };
    Ok(attachment_headers(&record, status, body, requested_range))
}

async fn delete_attachment(
    State(state): State<AppState>,
    AxumPath(attachment_id): AxumPath<String>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    state.authorize(&headers)?;
    validate_identifier(&attachment_id)?;
    let operation_id = operation_id(&headers)?;
    let request_hash = hash_parts(&[b"DELETE_ATTACHMENT", attachment_id.as_bytes()]);
    let storage = Arc::clone(&state.storage);
    let result =
        blocking(move || storage.delete_attachment(&operation_id, &attachment_id, &request_hash))
            .await?;
    match result {
        StoredOperation::Complete(response) => Ok(Json(response).into_response()),
        StoredOperation::IdempotencyMismatch => Err(ApiError::conflict("idempotency_key_reused")),
    }
}

async fn attachment_record(
    state: &AppState,
    attachment_id: String,
) -> Result<AttachmentRecord, ApiError> {
    let storage = Arc::clone(&state.storage);
    blocking(move || storage.attachment_record(&attachment_id))
        .await?
        .ok_or_else(ApiError::not_found)
}

fn attachment_headers(
    record: &AttachmentRecord,
    status: StatusCode,
    body: Body,
    range: Option<(u64, u64)>,
) -> Response {
    let mut response = Response::new(body);
    *response.status_mut() = status;
    let headers = response.headers_mut();
    headers.insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    headers.insert(
        header::CONTENT_LENGTH,
        HeaderValue::from_str(
            &range
                .map(|(start, end)| end - start + 1)
                .unwrap_or(record.ciphertext_size)
                .to_string(),
        )
        .expect("validated attachment size"),
    );
    headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("bytes"));
    if let Some((start, end)) = range {
        headers.insert(
            header::CONTENT_RANGE,
            HeaderValue::from_str(&format!("bytes {start}-{end}/{}", record.ciphertext_size,))
                .expect("validated content range"),
        );
    }
    headers.insert(
        CIPHERTEXT_SHA256_HEADER,
        HeaderValue::from_str(&record.ciphertext_sha256).expect("validated attachment checksum"),
    );
    headers.insert(
        header::ETAG,
        HeaderValue::from_str(&format!("\"{}\"", record.ciphertext_sha256))
            .expect("validated attachment checksum"),
    );
    response
}

fn parse_byte_range(
    value: Option<&HeaderValue>,
    total_size: u64,
) -> Result<Option<(u64, u64)>, ApiError> {
    let Some(raw) = value else {
        return Ok(None);
    };
    let range = raw
        .to_str()
        .ok()
        .and_then(|value| value.strip_prefix("bytes="))
        .filter(|value| !value.contains(','))
        .ok_or_else(ApiError::range_not_satisfiable)?;
    let (start, end) = range
        .split_once('-')
        .ok_or_else(ApiError::range_not_satisfiable)?;
    if start.is_empty() {
        let suffix = end
            .parse::<u64>()
            .ok()
            .filter(|value| *value > 0)
            .ok_or_else(ApiError::range_not_satisfiable)?;
        let length = suffix.min(total_size);
        return Ok(Some((total_size - length, total_size - 1)));
    }
    let start = start
        .parse::<u64>()
        .ok()
        .filter(|value| *value < total_size)
        .ok_or_else(ApiError::range_not_satisfiable)?;
    let end = if end.is_empty() {
        total_size - 1
    } else {
        end.parse::<u64>()
            .ok()
            .map(|value| value.min(total_size - 1))
            .filter(|value| *value >= start)
            .ok_or_else(ApiError::range_not_satisfiable)?
    };
    Ok(Some((start, end)))
}

async fn stream_attachment(
    body: Body,
    destination: &Path,
    expected_size: u64,
) -> Result<(u64, String), ApiError> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    set_private_file_creation_mode(&mut options);
    let mut file = options
        .open(destination)
        .await
        .map_err(|_| ApiError::unavailable())?;
    let mut stream = body.into_data_stream();
    let mut digest = Sha256::new();
    let mut total = 0_u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| ApiError::corrupted_upload())?;
        total = total
            .checked_add(u64::try_from(chunk.len()).map_err(|_| ApiError::payload_too_large())?)
            .ok_or_else(ApiError::payload_too_large)?;
        if total > expected_size || total > MAX_ATTACHMENT_ENVELOPE_BYTES {
            return Err(ApiError::payload_too_large());
        }
        digest.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|_| ApiError::unavailable())?;
    }
    file.flush().await.map_err(|_| ApiError::unavailable())?;
    file.sync_all().await.map_err(|_| ApiError::unavailable())?;
    let checksum = digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    Ok((total, checksum))
}

fn mutation_response(result: StoredOperation<StoredMutation>) -> Result<Response, ApiError> {
    match result {
        StoredOperation::IdempotencyMismatch => Err(ApiError::conflict("idempotency_key_reused")),
        StoredOperation::Complete(stored) => {
            let status =
                StatusCode::from_u16(stored.status_code).map_err(|_| ApiError::internal())?;
            Ok((status, Json(stored.response)).into_response())
        }
    }
}

fn stored_attachment_response(
    stored: StoredOperation<AttachmentReceipt>,
) -> Result<Response, ApiError> {
    match stored {
        StoredOperation::Complete(receipt) => Ok(Json(receipt).into_response()),
        StoredOperation::IdempotencyMismatch => Err(ApiError::conflict("idempotency_key_reused")),
    }
}

fn operation_id(headers: &HeaderMap) -> Result<String, ApiError> {
    let value = header_value(headers, OPERATION_HEADER)?;
    validate_identifier(&value)?;
    Ok(value)
}

fn header_value(headers: &HeaderMap, name: &'static str) -> Result<String, ApiError> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(str::to_owned)
        .ok_or_else(ApiError::invalid_request)
}

fn validate_identifier(value: &str) -> Result<(), ApiError> {
    if is_valid_id(value) {
        Ok(())
    } else {
        Err(ApiError::invalid_request())
    }
}

fn validate_version_token(value: Option<&str>) -> Result<(), ApiError> {
    if value.is_none_or(is_valid_id) {
        Ok(())
    } else {
        Err(ApiError::invalid_request())
    }
}

fn validate_envelope(
    encoded: &str,
    expected_sha256: &str,
    maximum_bytes: usize,
) -> Result<(), ApiError> {
    if !is_lower_hex_sha256(expected_sha256) || encoded.is_empty() {
        return Err(ApiError::invalid_request());
    }
    let decoded = STANDARD
        .decode(encoded)
        .map_err(|_| ApiError::invalid_request())?;
    if decoded.is_empty() || decoded.len() > maximum_bytes {
        return Err(ApiError::payload_too_large());
    }
    if sha256_hex(&decoded) != expected_sha256 {
        return Err(ApiError::corrupted_upload());
    }
    Ok(())
}

fn hash_parts(parts: &[&[u8]]) -> String {
    let mut digest = Sha256::new();
    for part in parts {
        digest.update((part.len() as u64).to_be_bytes());
        digest.update(part);
    }
    digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn decode_sha256_hex(value: &str) -> Option<[u8; 32]> {
    if !is_lower_hex_sha256(value) {
        return None;
    }
    let mut output = [0_u8; 32];
    for (index, byte) in output.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16).ok()?;
    }
    Some(output)
}

async fn blocking<T, F>(operation: F) -> Result<T, ApiError>
where
    T: Send + 'static,
    F: FnOnce() -> Result<T, RelayError> + Send + 'static,
{
    tokio::task::spawn_blocking(operation)
        .await
        .map_err(|_| ApiError::internal())?
        .map_err(|error| match error {
            RelayError::Io(_) | RelayError::Database(_) => ApiError::unavailable(),
            _ => ApiError::internal(),
        })
}

#[cfg(unix)]
fn set_private_file_creation_mode(options: &mut OpenOptions) {
    options.mode(0o600);
}

#[cfg(not(unix))]
fn set_private_file_creation_mode(_: &mut OpenOptions) {}
