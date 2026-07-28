use std::{fmt, net::IpAddr, path::Path, sync::Arc, time::Duration};

use futures_util::StreamExt;
use reqwest::{
    Client, Method, Response, StatusCode,
    header::{self, HeaderMap, HeaderValue},
    redirect::Policy,
};
use rustls::{
    CertificateError, ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::WebPkiSupportedAlgorithms,
    pki_types::{CertificateDer, ServerName, UnixTime},
};
use serde::de::DeserializeOwned;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::io::AsyncWriteExt;
use tokio_util::io::ReaderStream;
use zeroize::Zeroizing;

use crate::{
    error::AppError,
    sync_crypto,
    sync_wire::{
        self, AttachmentReceipt, ChangePage, DeleteMutationRequest, ErrorBody, ItemMutationRequest,
        KeyCheckEnvelope, MutationResponse, RelayInformation, RemoteItem,
    },
};

const PROTOCOL_HEADER: &str = "x-vaultnote-protocol";
const OPERATION_HEADER: &str = "x-vaultnote-operation-id";
const CIPHERTEXT_SHA256_HEADER: &str = "x-vaultnote-ciphertext-sha256";
const MAX_CONTROL_RESPONSE_BYTES: usize = 64 * 1024;
const MAX_ITEM_RESPONSE_BYTES: usize = 3 * 1024 * 1024;
const MAX_CHANGE_RESPONSE_BYTES: usize = 64 * 1024 * 1024;
const MAX_ATTACHMENT_ENVELOPE_BYTES: u64 = 110 * 1024 * 1024;

pub struct ProvisionalRelayAccess<'a> {
    pub host_address: &'a str,
    pub port: u16,
    pub certificate_sha256: &'a str,
    pub authentication_token: &'a str,
}

#[derive(Debug)]
pub enum KeyCheckResult {
    Present(KeyCheckEnvelope),
    Missing,
}

pub struct RelayClient {
    client: Client,
    base_url: String,
    authentication_token: Zeroizing<String>,
}

impl RelayClient {
    pub fn new(access: ProvisionalRelayAccess<'_>) -> Result<Self, AppError> {
        if access.port == 0
            || !sync_wire::lower_hex_sha256(access.certificate_sha256)
            || !access.authentication_token.starts_with("vns_")
            || access.authentication_token.len() > 128
        {
            return Err(AppError::InvalidInput {
                field: "relay",
                reason: "invalid relay access".to_owned(),
            });
        }
        let expected_fingerprint = decode_sha256(access.certificate_sha256)?;
        let provider = rustls::crypto::aws_lc_rs::default_provider();
        let supported = provider.signature_verification_algorithms;
        let verifier = Arc::new(PinnedCertificateVerifier {
            expected_fingerprint,
            supported,
        });
        let tls = ClientConfig::builder_with_provider(Arc::new(provider))
            .with_safe_default_protocol_versions()
            .map_err(|_| AppError::RelayIdentity)?
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();
        let client = Client::builder()
            .tls_backend_preconfigured(tls)
            .redirect(Policy::none())
            .https_only(true)
            .no_proxy()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(60))
            .pool_idle_timeout(Duration::from_secs(30))
            .build()
            .map_err(|_| AppError::NetworkUnavailable)?;
        Ok(Self {
            client,
            base_url: endpoint(access.host_address, access.port)?,
            authentication_token: Zeroizing::new(access.authentication_token.to_owned()),
        })
    }

    pub async fn relay_information(&self) -> Result<RelayInformation, AppError> {
        let response = self.request(Method::GET, "/v1/relay", None)?.send().await?;
        parse_success_json(response, MAX_CONTROL_RESPONSE_BYTES).await
    }

    pub async fn key_check(&self) -> Result<KeyCheckResult, AppError> {
        let response = self
            .request(Method::GET, "/v1/key-check", None)?
            .send()
            .await?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(KeyCheckResult::Missing);
        }
        parse_success_json(response, MAX_CONTROL_RESPONSE_BYTES)
            .await
            .map(KeyCheckResult::Present)
    }

    pub async fn initialize_key_check(&self, key_check: &KeyCheckEnvelope) -> Result<(), AppError> {
        let response = self
            .request(Method::PUT, "/v1/key-check", None)?
            .json(key_check)
            .send()
            .await?;
        if matches!(
            response.status(),
            StatusCode::CREATED | StatusCode::NO_CONTENT
        ) {
            Ok(())
        } else {
            Err(map_error_response(response).await)
        }
    }

    pub async fn upsert_item(
        &self,
        operation_id: &str,
        item_id: &str,
        expected_version_token: Option<&str>,
        encrypted_payload: &str,
        ciphertext_sha256: &str,
    ) -> Result<MutationResponse, AppError> {
        validate_operation(operation_id, item_id)?;
        let request = ItemMutationRequest {
            expected_version_token,
            encrypted_payload,
            ciphertext_sha256,
        };
        let response = self
            .request(
                Method::PUT,
                &format!("/v1/items/{item_id}"),
                Some(operation_id),
            )?
            .json(&request)
            .send()
            .await?;
        parse_mutation(response).await
    }

    pub async fn delete_item(
        &self,
        operation_id: &str,
        item_id: &str,
        expected_version_token: Option<&str>,
    ) -> Result<MutationResponse, AppError> {
        validate_operation(operation_id, item_id)?;
        let request = DeleteMutationRequest {
            expected_version_token,
        };
        let response = self
            .request(
                Method::DELETE,
                &format!("/v1/items/{item_id}"),
                Some(operation_id),
            )?
            .json(&request)
            .send()
            .await?;
        parse_mutation(response).await
    }

    pub async fn pull_changes(
        &self,
        cursor: Option<&str>,
        limit: usize,
    ) -> Result<ChangePage, AppError> {
        if cursor.is_some_and(|value| {
            value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit())
        }) {
            return Err(AppError::CorruptedSync);
        }
        let mut path = format!("/v1/changes?limit={}", limit.clamp(1, 200));
        if let Some(cursor) = cursor {
            path.push_str("&cursor=");
            path.push_str(cursor);
        }
        let response = self.request(Method::GET, &path, None)?.send().await?;
        let page: ChangePage = parse_success_json(response, MAX_CHANGE_RESPONSE_BYTES).await?;
        if page.changes.len() > sync_wire::MAX_CHANGE_PAGE_ITEMS
            || page.next_cursor.as_deref().is_some_and(|value| {
                value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit())
            })
            || page.changes.iter().any(|change| !valid_remote_item(change))
        {
            return Err(AppError::CorruptedSync);
        }
        Ok(page)
    }

    pub async fn upload_attachment(
        &self,
        operation_id: &str,
        attachment_id: &str,
        source: &Path,
        ciphertext_sha256: &str,
    ) -> Result<AttachmentReceipt, AppError> {
        validate_operation(operation_id, attachment_id)?;
        if !sync_wire::lower_hex_sha256(ciphertext_sha256) {
            return Err(AppError::CorruptedSync);
        }
        let size = tokio::fs::metadata(source).await?.len();
        if !(1..=MAX_ATTACHMENT_ENVELOPE_BYTES).contains(&size) {
            return Err(AppError::FileTooLarge);
        }
        let file = tokio::fs::File::open(source).await?;
        let body = reqwest::Body::wrap_stream(ReaderStream::new(file));
        let response = self
            .request(
                Method::PUT,
                &format!("/v1/attachments/{attachment_id}"),
                Some(operation_id),
            )?
            .header(header::CONTENT_LENGTH, size)
            .header(CIPHERTEXT_SHA256_HEADER, ciphertext_sha256)
            .body(body)
            .send()
            .await?;
        let receipt: AttachmentReceipt =
            parse_success_json(response, MAX_CONTROL_RESPONSE_BYTES).await?;
        if receipt.attachment_id != attachment_id
            || receipt.ciphertext_sha256 != ciphertext_sha256
            || receipt.ciphertext_size != size
            || receipt.remote_path != format!("/v1/attachments/{attachment_id}")
        {
            return Err(AppError::CorruptedSync);
        }
        Ok(receipt)
    }

    pub async fn verify_attachment(
        &self,
        attachment_id: &str,
        ciphertext_sha256: &str,
        ciphertext_size: u64,
    ) -> Result<(), AppError> {
        if !sync_wire::valid_id(attachment_id) || !sync_wire::lower_hex_sha256(ciphertext_sha256) {
            return Err(AppError::CorruptedSync);
        }
        let response = self
            .request(
                Method::HEAD,
                &format!("/v1/attachments/{attachment_id}"),
                None,
            )?
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(map_error_response(response).await);
        }
        let actual_checksum = response
            .headers()
            .get(CIPHERTEXT_SHA256_HEADER)
            .and_then(|value| value.to_str().ok());
        let actual_size = content_length_header(&response);
        if actual_checksum != Some(ciphertext_sha256) || actual_size != Some(ciphertext_size) {
            return Err(AppError::CorruptedSync);
        }
        Ok(())
    }

    pub async fn download_attachment(
        &self,
        attachment_id: &str,
        destination: &Path,
    ) -> Result<(u64, String), AppError> {
        if !sync_wire::valid_id(attachment_id) {
            return Err(AppError::CorruptedSync);
        }
        let (expected_size, expected_checksum) = self.attachment_information(attachment_id).await?;
        if expected_size > MAX_ATTACHMENT_ENVELOPE_BYTES {
            return Err(AppError::FileTooLarge);
        }
        if let Some(parent) = destination.parent() {
            tokio::fs::create_dir_all(parent).await?;
        }
        for attempt in 0..2 {
            let existing = tokio::fs::metadata(destination)
                .await
                .map(|metadata| metadata.len())
                .unwrap_or(0);
            let offset = existing.min(expected_size);
            if existing > expected_size {
                let _ = tokio::fs::remove_file(destination).await;
                continue;
            }
            let mut request = self.request(
                Method::GET,
                &format!("/v1/attachments/{attachment_id}"),
                None,
            )?;
            if offset > 0 {
                request = request.header(header::RANGE, format!("bytes={offset}-"));
            }
            let response = request.send().await?;
            let expected_status = if offset > 0 {
                StatusCode::PARTIAL_CONTENT
            } else {
                StatusCode::OK
            };
            if response.status() != expected_status {
                if offset > 0 && response.status() == StatusCode::OK && attempt == 0 {
                    let _ = tokio::fs::remove_file(destination).await;
                    continue;
                }
                return Err(map_error_response(response).await);
            }
            let mut options = tokio::fs::OpenOptions::new();
            options.create(true).write(true);
            if offset > 0 {
                options.append(true);
            } else {
                options.truncate(true);
            }
            let mut file = options.open(destination).await?;
            let mut received = offset;
            let mut stream = response.bytes_stream();
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.map_err(|_| AppError::NetworkUnavailable)?;
                received = received
                    .checked_add(chunk.len() as u64)
                    .ok_or(AppError::FileTooLarge)?;
                if received > expected_size {
                    return Err(AppError::CorruptedSync);
                }
                file.write_all(&chunk).await?;
            }
            file.flush().await?;
            file.sync_all().await?;
            if received != expected_size {
                return Err(AppError::NetworkUnavailable);
            }
            let path = destination.to_owned();
            let actual_checksum =
                tokio::task::spawn_blocking(move || sync_crypto::sha256_file(&path))
                    .await
                    .map_err(|_| AppError::NetworkUnavailable)??;
            if actual_checksum != expected_checksum {
                let _ = tokio::fs::remove_file(destination).await;
                return Err(AppError::CorruptedSync);
            }
            return Ok((expected_size, expected_checksum));
        }
        Err(AppError::NetworkUnavailable)
    }

    pub async fn delete_attachment(
        &self,
        operation_id: &str,
        attachment_id: &str,
    ) -> Result<(), AppError> {
        validate_operation(operation_id, attachment_id)?;
        let response = self
            .request(
                Method::DELETE,
                &format!("/v1/attachments/{attachment_id}"),
                Some(operation_id),
            )?
            .send()
            .await?;
        if response.status().is_success() {
            let _: serde_json::Value =
                parse_success_json(response, MAX_CONTROL_RESPONSE_BYTES).await?;
            Ok(())
        } else {
            Err(map_error_response(response).await)
        }
    }

    async fn attachment_information(&self, attachment_id: &str) -> Result<(u64, String), AppError> {
        let response = self
            .request(
                Method::HEAD,
                &format!("/v1/attachments/{attachment_id}"),
                None,
            )?
            .send()
            .await?;
        if !response.status().is_success() {
            return Err(map_error_response(response).await);
        }
        let size = content_length_header(&response)
            .filter(|value| *value > 0)
            .ok_or(AppError::CorruptedSync)?;
        let checksum = response
            .headers()
            .get(CIPHERTEXT_SHA256_HEADER)
            .and_then(|value| value.to_str().ok())
            .filter(|value| sync_wire::lower_hex_sha256(value))
            .ok_or(AppError::CorruptedSync)?
            .to_owned();
        Ok((size, checksum))
    }

    fn request(
        &self,
        method: Method,
        path: &str,
        operation_id: Option<&str>,
    ) -> Result<reqwest::RequestBuilder, AppError> {
        if !path.starts_with("/v1/") || path.contains(['\r', '\n']) {
            return Err(AppError::InvalidState);
        }
        let mut headers = HeaderMap::new();
        headers.insert(PROTOCOL_HEADER, HeaderValue::from_static("3"));
        let authorization =
            HeaderValue::from_str(&format!("Bearer {}", self.authentication_token.as_str()))
                .map_err(|_| AppError::RelayAuthentication)?;
        headers.insert(header::AUTHORIZATION, authorization);
        if let Some(operation_id) = operation_id {
            if !sync_wire::valid_id(operation_id) {
                return Err(AppError::InvalidInput {
                    field: "operation_id",
                    reason: "invalid operation identifier".to_owned(),
                });
            }
            headers.insert(
                OPERATION_HEADER,
                HeaderValue::from_str(operation_id).map_err(|_| AppError::InvalidState)?,
            );
        }
        Ok(self
            .client
            .request(method, format!("{}{}", self.base_url, path))
            .headers(headers))
    }
}

fn content_length_header(response: &Response) -> Option<u64> {
    response
        .headers()
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse().ok())
}

impl From<reqwest::Error> for AppError {
    fn from(_: reqwest::Error) -> Self {
        AppError::NetworkUnavailable
    }
}

async fn parse_mutation(response: Response) -> Result<MutationResponse, AppError> {
    let status = response.status();
    let bytes = read_bounded(response, MAX_ITEM_RESPONSE_BYTES).await?;
    if matches!(
        status,
        StatusCode::OK | StatusCode::CREATED | StatusCode::CONFLICT
    ) && let Ok(mutation) = serde_json::from_slice::<MutationResponse>(&bytes)
    {
        let applied_valid = mutation.outcome == sync_wire::MutationOutcome::Applied
            && mutation.server_revision.is_some_and(|value| value > 0)
            && mutation
                .version_token
                .as_deref()
                .is_some_and(sync_wire::valid_id)
            && mutation.remote.is_none();
        let conflict_valid = mutation.outcome == sync_wire::MutationOutcome::Conflict
            && mutation.server_revision.is_none()
            && mutation.version_token.is_none()
            && mutation.remote.as_ref().is_none_or(valid_remote_item);
        if applied_valid || conflict_valid {
            return Ok(mutation);
        }
    }
    Err(map_error(status, &bytes))
}

async fn parse_success_json<T: DeserializeOwned>(
    response: Response,
    maximum: usize,
) -> Result<T, AppError> {
    let status = response.status();
    let bytes = read_bounded(response, maximum).await?;
    if !status.is_success() {
        return Err(map_error(status, &bytes));
    }
    serde_json::from_slice(&bytes).map_err(|_| AppError::CorruptedSync)
}

async fn map_error_response(response: Response) -> AppError {
    let status = response.status();
    let bytes = read_bounded(response, MAX_CONTROL_RESPONSE_BYTES)
        .await
        .unwrap_or_default();
    map_error(status, &bytes)
}

fn map_error(status: StatusCode, bytes: &[u8]) -> AppError {
    let code = serde_json::from_slice::<ErrorBody>(bytes)
        .ok()
        .map(|error| {
            let _ = error.retryable;
            error.code
        });
    match (status, code.as_deref()) {
        (StatusCode::UNAUTHORIZED, _) => AppError::RelayAuthentication,
        (StatusCode::UPGRADE_REQUIRED, _) | (_, Some("unsupported_protocol")) => {
            AppError::UnsupportedProtocol
        }
        (
            StatusCode::SERVICE_UNAVAILABLE | StatusCode::BAD_GATEWAY | StatusCode::GATEWAY_TIMEOUT,
            _,
        ) => AppError::NetworkUnavailable,
        (_, Some("authentication_required")) => AppError::RelayAuthentication,
        (_, Some("corrupted_upload" | "attachment_id_conflict" | "idempotency_key_reused")) => {
            AppError::CorruptedSync
        }
        _ if status.is_server_error() => AppError::NetworkUnavailable,
        _ => AppError::CorruptedSync,
    }
}

async fn read_bounded(response: Response, maximum: usize) -> Result<Vec<u8>, AppError> {
    if response
        .content_length()
        .is_some_and(|value| value > maximum as u64)
    {
        return Err(AppError::CorruptedSync);
    }
    let mut output = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|_| AppError::NetworkUnavailable)?;
        if output.len().saturating_add(chunk.len()) > maximum {
            return Err(AppError::CorruptedSync);
        }
        output.extend_from_slice(&chunk);
    }
    Ok(output)
}

fn endpoint(host_address: &str, port: u16) -> Result<String, AppError> {
    if host_address.is_empty()
        || host_address.len() > 253
        || host_address.contains(['/', '\\', '@', '#', '?'])
        || host_address.chars().any(char::is_whitespace)
    {
        return Err(AppError::InvalidInput {
            field: "relay_host",
            reason: "invalid host".to_owned(),
        });
    }
    let host = match host_address.parse::<IpAddr>() {
        Ok(IpAddr::V6(address)) => format!("[{address}]"),
        _ => host_address.to_owned(),
    };
    Ok(format!("https://{host}:{port}"))
}

fn validate_operation(operation_id: &str, object_id: &str) -> Result<(), AppError> {
    if sync_wire::valid_id(operation_id) && sync_wire::valid_id(object_id) {
        Ok(())
    } else {
        Err(AppError::InvalidInput {
            field: "operation_id",
            reason: "invalid operation".to_owned(),
        })
    }
}

fn valid_remote_item(value: &RemoteItem) -> bool {
    sync_wire::valid_id(&value.item_id)
        && value.server_revision > 0
        && sync_wire::valid_id(&value.version_token)
        && if value.deleted {
            value.encrypted_payload.is_none() && value.ciphertext_sha256.is_none()
        } else {
            value
                .encrypted_payload
                .as_ref()
                .is_some_and(|payload| !payload.is_empty())
                && value
                    .ciphertext_sha256
                    .as_deref()
                    .is_some_and(sync_wire::lower_hex_sha256)
        }
}

fn decode_sha256(value: &str) -> Result<[u8; 32], AppError> {
    if !sync_wire::lower_hex_sha256(value) {
        return Err(AppError::RelayIdentity);
    }
    let mut output = [0_u8; 32];
    for (index, byte) in output.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&value[index * 2..index * 2 + 2], 16)
            .map_err(|_| AppError::RelayIdentity)?;
    }
    Ok(output)
}

#[derive(Clone)]
struct PinnedCertificateVerifier {
    expected_fingerprint: [u8; 32],
    supported: WebPkiSupportedAlgorithms,
}

impl fmt::Debug for PinnedCertificateVerifier {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("PinnedCertificateVerifier")
            .finish_non_exhaustive()
    }
}

impl ServerCertVerifier for PinnedCertificateVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        let actual: [u8; 32] = Sha256::digest(end_entity.as_ref()).into();
        if actual.ct_eq(&self.expected_fingerprint).into() {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(TlsError::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls12_signature(message, cert, signature, &self.supported)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        rustls::crypto::verify_tls13_signature(message, cert, signature, &self.supported)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.supported.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoint_rejects_userinfo_and_paths() {
        assert_eq!(
            endpoint("192.168.1.5", 8787).expect("IPv4 should validate"),
            "https://192.168.1.5:8787"
        );
        assert_eq!(
            endpoint("::1", 8787).expect("IPv6 should validate"),
            "https://[::1]:8787"
        );
        assert!(endpoint("token@example.com", 8787).is_err());
        assert!(endpoint("example.com/path", 8787).is_err());
    }

    #[test]
    fn pinned_verifier_rejects_any_other_certificate() {
        let provider = rustls::crypto::aws_lc_rs::default_provider();
        let verifier = PinnedCertificateVerifier {
            expected_fingerprint: [7_u8; 32],
            supported: provider.signature_verification_algorithms,
        };
        let certificate = CertificateDer::from(vec![1_u8, 2, 3]);
        let result = verifier.verify_server_cert(
            &certificate,
            &[],
            &ServerName::try_from("localhost").expect("server name should parse"),
            &[],
            UnixTime::since_unix_epoch(Duration::from_secs(1)),
        );
        assert!(result.is_err());
    }
}
