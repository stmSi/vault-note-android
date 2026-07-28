use axum::{
    Router,
    body::Body,
    http::{Method, Request, StatusCode, header},
};
use base64::{Engine as _, engine::general_purpose::STANDARD};
use http_body_util::BodyExt;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tempfile::TempDir;
use tower::ServiceExt;
use vaultnote_sync_server::{
    AppState, DiscoveryAdvertisement, RelayConfig, Storage, initialize_relay, load_config,
    rotate_authentication_token, router,
};
use vaultnote_sync_server::{
    api::{CIPHERTEXT_SHA256_HEADER, OPERATION_HEADER, PROTOCOL_HEADER},
    config::{PROTOCOL_VERSION, verify_tls_identity},
    discovery::SERVICE_TYPE,
    model::{
        AttachmentDeleteResponse, AttachmentReceipt, ChangePage, KeyCheckEnvelope, MutationOutcome,
        MutationResponse, RelayInformation,
    },
};

struct Harness {
    temporary: TempDir,
    token: String,
    config: RelayConfig,
    app: Router,
}

impl Harness {
    fn new() -> Self {
        let temporary = tempfile::tempdir().expect("temporary relay directory");
        let initialized = initialize_relay(temporary.path()).expect("relay initialization");
        let token = initialized.authentication_token.as_str().to_owned();
        let config = initialized.config;
        let storage = Storage::open(temporary.path(), &config).expect("relay storage");
        let app = router(AppState::new(config.clone(), storage).expect("relay state"));
        Self {
            temporary,
            token,
            config,
            app,
        }
    }

    fn request(&self, method: Method, uri: &str, body: Body) -> Request<Body> {
        Request::builder()
            .method(method)
            .uri(uri)
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .header(header::AUTHORIZATION, format!("Bearer {}", self.token))
            .body(body)
            .expect("valid request")
    }

    fn json_request(
        &self,
        method: Method,
        uri: &str,
        operation_id: Option<&str>,
        value: Value,
    ) -> Request<Body> {
        let bytes = serde_json::to_vec(&value).expect("JSON request");
        let mut builder = Request::builder()
            .method(method)
            .uri(uri)
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .header(header::AUTHORIZATION, format!("Bearer {}", self.token))
            .header(header::CONTENT_TYPE, "application/json");
        if let Some(operation_id) = operation_id {
            builder = builder.header(OPERATION_HEADER, operation_id);
        }
        builder.body(Body::from(bytes)).expect("valid JSON request")
    }

    fn restarted_app(&self) -> Router {
        let config = load_config(self.temporary.path()).expect("persisted relay configuration");
        let storage =
            Storage::open(self.temporary.path(), &config).expect("reopened relay storage");
        router(AppState::new(config, storage).expect("restarted relay state"))
    }
}

#[tokio::test]
async fn authenticated_relay_information_exposes_only_pairing_metadata() {
    let harness = Harness::new();

    let response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/relay", Body::empty()))
        .await
        .expect("relay response");

    assert_eq!(response.status(), StatusCode::OK);
    let information: RelayInformation = response_json(response).await;
    assert_eq!(information.protocol_version, PROTOCOL_VERSION);
    assert_eq!(information.vault_id, harness.config.vault_id);
    assert_eq!(
        information.tls_identity.certificate_sha256,
        harness.config.tls.certificate_sha256,
    );
    let serialized = serde_json::to_string(&information).expect("relay information JSON");
    assert!(!serialized.contains(&harness.token));
}

#[tokio::test]
async fn protocol_and_authentication_are_required_before_vault_access() {
    let harness = Harness::new();
    let missing_protocol = Request::builder()
        .uri("/v1/relay")
        .header(header::AUTHORIZATION, format!("Bearer {}", harness.token))
        .body(Body::empty())
        .expect("request");
    let response = harness
        .app
        .clone()
        .oneshot(missing_protocol)
        .await
        .expect("protocol response");
    assert_eq!(response.status(), StatusCode::UPGRADE_REQUIRED);

    let wrong_token = Request::builder()
        .uri("/v1/relay")
        .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        .header(header::AUTHORIZATION, "Bearer vns_invalid")
        .body(Body::empty())
        .expect("request");
    let response = harness
        .app
        .clone()
        .oneshot(wrong_token)
        .await
        .expect("authentication response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn rotating_authentication_invalidates_the_old_token_without_changing_vault_identity() {
    let harness = Harness::new();
    let original_vault_id = harness.config.vault_id.clone();
    let original_certificate = harness.config.tls.certificate_sha256.clone();
    let new_token =
        rotate_authentication_token(harness.temporary.path()).expect("authentication rotation");
    assert_ne!(new_token.as_str(), harness.token);

    let restarted = harness.restarted_app();
    let response = restarted
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/relay", Body::empty()))
        .await
        .expect("old token response");
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let new_request = Request::builder()
        .uri("/v1/relay")
        .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        .header(
            header::AUTHORIZATION,
            format!("Bearer {}", new_token.as_str()),
        )
        .body(Body::empty())
        .expect("new token request");
    let response = restarted
        .oneshot(new_request)
        .await
        .expect("new token response");
    assert_eq!(response.status(), StatusCode::OK);
    let information: RelayInformation = response_json(response).await;
    assert_eq!(information.vault_id, original_vault_id);
    assert_eq!(
        information.tls_identity.certificate_sha256,
        original_certificate,
    );

    let persisted = std::fs::read_to_string(
        harness
            .temporary
            .path()
            .join(vaultnote_sync_server::config::CONFIG_FILENAME),
    )
    .expect("persisted configuration");
    assert!(!persisted.contains(&harness.token));
    assert!(!persisted.contains(new_token.as_str()));
}

#[tokio::test]
async fn key_check_is_write_once_and_idempotent() {
    let harness = Harness::new();
    let key_check = envelope(b"VNS1 key check");
    let request = json!({
        "encryptedKeyCheck": key_check.0,
        "ciphertextSha256": key_check.1,
    });

    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(Method::PUT, "/v1/key-check", None, request.clone()))
        .await
        .expect("key-check create");
    assert_eq!(response.status(), StatusCode::CREATED);

    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(Method::PUT, "/v1/key-check", None, request))
        .await
        .expect("key-check replay");
    assert_eq!(response.status(), StatusCode::NO_CONTENT);

    let other = envelope(b"VNS1 another vault key");
    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/key-check",
            None,
            json!({
                "encryptedKeyCheck": other.0,
                "ciphertextSha256": other.1,
            }),
        ))
        .await
        .expect("key-check conflict");
    assert_eq!(response.status(), StatusCode::CONFLICT);

    let response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/key-check", Body::empty()))
        .await
        .expect("key-check read");
    let stored: KeyCheckEnvelope = response_json(response).await;
    assert_eq!(stored.encrypted_key_check, key_check.0);
}

#[tokio::test]
async fn revisions_conflicts_idempotency_tombstones_and_restart_are_durable() {
    let harness = Harness::new();
    let first = envelope(b"VNS1 encrypted note revision one");
    let first_request = item_request(None, &first);
    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/items/note-1",
            Some("operation-1"),
            first_request.clone(),
        ))
        .await
        .expect("first upsert");
    assert_eq!(response.status(), StatusCode::OK);
    let applied: MutationResponse = response_json(response).await;
    assert_eq!(applied.outcome, MutationOutcome::Applied);
    assert_eq!(applied.server_revision, Some(1));
    let first_token = applied.version_token.clone().expect("first version token");

    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/items/note-1",
            Some("operation-1"),
            first_request,
        ))
        .await
        .expect("idempotent upsert");
    let replay: MutationResponse = response_json(response).await;
    assert_eq!(replay, applied);

    let changed = envelope(b"VNS1 changed request under reused operation");
    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/items/note-1",
            Some("operation-1"),
            item_request(None, &changed),
        ))
        .await
        .expect("operation reuse response");
    assert_eq!(response.status(), StatusCode::CONFLICT);
    assert_eq!(
        response_value(response).await["code"],
        "idempotency_key_reused",
    );

    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/items/note-1",
            Some("operation-stale"),
            item_request(None, &changed),
        ))
        .await
        .expect("stale upsert");
    assert_eq!(response.status(), StatusCode::CONFLICT);
    let conflict: MutationResponse = response_json(response).await;
    assert_eq!(conflict.outcome, MutationOutcome::Conflict);
    assert_eq!(
        conflict
            .remote
            .as_ref()
            .and_then(|remote| remote.encrypted_payload.as_ref()),
        Some(&first.0),
    );

    let second = envelope(b"VNS1 encrypted note revision two");
    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::PUT,
            "/v1/items/note-1",
            Some("operation-2"),
            item_request(Some(&first_token), &second),
        ))
        .await
        .expect("second upsert");
    let second_applied: MutationResponse = response_json(response).await;
    assert_eq!(second_applied.server_revision, Some(2));
    let second_token = second_applied.version_token.expect("second version token");

    let first_page_response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/changes?cursor=0&limit=1", Body::empty()))
        .await
        .expect("first incremental page");
    let first_page: ChangePage = response_json(first_page_response).await;
    assert_eq!(first_page.changes.len(), 1);
    assert_eq!(first_page.next_cursor.as_deref(), Some("1"));
    assert!(first_page.has_more);

    let page_response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/changes?cursor=1&limit=1", Body::empty()))
        .await
        .expect("incremental page");
    let page: ChangePage = response_json(page_response).await;
    assert_eq!(page.changes.len(), 1);
    assert_eq!(page.changes[0].server_revision, 2);
    assert_eq!(page.changes[0].encrypted_payload.as_ref(), Some(&second.0));

    let response = harness
        .app
        .clone()
        .oneshot(harness.json_request(
            Method::DELETE,
            "/v1/items/note-1",
            Some("operation-delete"),
            json!({"expectedVersionToken": second_token}),
        ))
        .await
        .expect("item deletion");
    let deleted: MutationResponse = response_json(response).await;
    assert_eq!(deleted.server_revision, Some(3));

    let restarted = harness.restarted_app();
    let response = restarted
        .oneshot(harness.request(Method::GET, "/v1/changes?cursor=2&limit=100", Body::empty()))
        .await
        .expect("changes after restart");
    let page: ChangePage = response_json(response).await;
    assert_eq!(page.changes.len(), 1);
    assert!(page.changes[0].deleted);
    assert!(page.changes[0].encrypted_payload.is_none());
}

#[tokio::test]
async fn attachments_stream_verify_download_replay_conflict_and_delete() {
    let harness = Harness::new();
    let bytes = b"VNS1 encrypted attachment bytes".repeat(4096);
    let checksum = sha256(&bytes);
    let upload = attachment_upload_request(
        &harness,
        "attachment-1",
        "attachment-operation-1",
        &bytes,
        &checksum,
    );
    let response = harness
        .app
        .clone()
        .oneshot(upload)
        .await
        .expect("attachment upload");
    assert_eq!(response.status(), StatusCode::CREATED);
    let receipt: AttachmentReceipt = response_json(response).await;
    assert_eq!(receipt.ciphertext_size, bytes.len() as u64);
    assert_eq!(receipt.ciphertext_sha256, checksum);

    let response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/attachments/attachment-1", Body::empty()))
        .await
        .expect("attachment download");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()[CIPHERTEXT_SHA256_HEADER],
        receipt.ciphertext_sha256,
    );
    assert_eq!(response_bytes(response).await, bytes);

    let mut range_request =
        harness.request(Method::GET, "/v1/attachments/attachment-1", Body::empty());
    range_request
        .headers_mut()
        .insert(header::RANGE, "bytes=5-19".parse().expect("range header"));
    let response = harness
        .app
        .clone()
        .oneshot(range_request)
        .await
        .expect("partial attachment download");
    assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
    assert_eq!(
        response.headers()[header::CONTENT_RANGE],
        format!("bytes 5-19/{}", bytes.len()),
    );
    assert_eq!(response_bytes(response).await, bytes[5..=19]);

    let mut invalid_range =
        harness.request(Method::GET, "/v1/attachments/attachment-1", Body::empty());
    invalid_range.headers_mut().insert(
        header::RANGE,
        "bytes=999999-".parse().expect("range header"),
    );
    let response = harness
        .app
        .clone()
        .oneshot(invalid_range)
        .await
        .expect("invalid range response");
    assert_eq!(response.status(), StatusCode::RANGE_NOT_SATISFIABLE);

    let response = harness
        .app
        .clone()
        .oneshot(attachment_upload_request(
            &harness,
            "attachment-1",
            "attachment-operation-1",
            &[],
            &checksum,
        ))
        .await
        .expect("idempotent attachment response");
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response_json::<AttachmentReceipt>(response).await, receipt,);

    let different = b"VNS1 different ciphertext".to_vec();
    let response = harness
        .app
        .clone()
        .oneshot(attachment_upload_request(
            &harness,
            "attachment-1",
            "attachment-operation-2",
            &different,
            &sha256(&different),
        ))
        .await
        .expect("immutable attachment conflict");
    assert_eq!(response.status(), StatusCode::CONFLICT);

    let bad_checksum = "0".repeat(64);
    let response = harness
        .app
        .clone()
        .oneshot(attachment_upload_request(
            &harness,
            "attachment-2",
            "attachment-operation-bad",
            &different,
            &bad_checksum,
        ))
        .await
        .expect("corrupted attachment response");
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let pending_files = std::fs::read_dir(
        harness
            .config
            .attachments_directory(harness.temporary.path()),
    )
    .expect("attachments directory")
    .filter_map(Result::ok)
    .filter(|entry| entry.file_name().to_string_lossy().starts_with(".pending-"))
    .count();
    assert_eq!(pending_files, 0);

    let mut delete_request = harness.request(
        Method::DELETE,
        "/v1/attachments/attachment-1",
        Body::empty(),
    );
    delete_request.headers_mut().insert(
        OPERATION_HEADER,
        "attachment-delete-1".parse().expect("operation header"),
    );
    let response = harness
        .app
        .clone()
        .oneshot(delete_request)
        .await
        .expect("attachment delete");
    let deletion: AttachmentDeleteResponse = response_json(response).await;
    assert!(deletion.deleted);

    let response = harness
        .app
        .clone()
        .oneshot(harness.request(Method::GET, "/v1/attachments/attachment-1", Body::empty()))
        .await
        .expect("deleted attachment lookup");
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[test]
fn tls_identity_and_lan_advertisement_are_stable_and_secret_free() {
    let harness = Harness::new();
    verify_tls_identity(harness.temporary.path(), &harness.config).expect("TLS identity");
    let advertisement = DiscoveryAdvertisement::from_config(&harness.config, 8787);
    let info = advertisement
        .service_info()
        .expect("mDNS service information");

    assert_eq!(info.get_type(), SERVICE_TYPE);
    assert_eq!(
        info.get_property_val_str("protocol"),
        Some(PROTOCOL_VERSION.to_string().as_str()),
    );
    assert_eq!(
        info.get_property_val_str("vault"),
        Some(harness.config.vault_id.as_str()),
    );
    assert_eq!(
        info.get_property_val_str("certSha256"),
        Some(harness.config.tls.certificate_sha256.as_str()),
    );
    let debug = format!("{info:?}");
    assert!(!debug.contains(&harness.token));
    assert!(info.is_addr_auto());
}

#[cfg(unix)]
#[test]
fn relay_secrets_and_storage_directories_have_private_permissions() {
    use std::os::unix::fs::PermissionsExt;

    let harness = Harness::new();
    let mode = |path: &std::path::Path| {
        std::fs::metadata(path)
            .expect("relay file metadata")
            .permissions()
            .mode()
            & 0o777
    };
    assert_eq!(mode(harness.temporary.path()), 0o700);
    assert_eq!(
        mode(
            &harness
                .temporary
                .path()
                .join(vaultnote_sync_server::config::CONFIG_FILENAME),
        ),
        0o600,
    );
    assert_eq!(
        mode(&vaultnote_sync_server::config::tls_private_key_path(
            harness.temporary.path(),
        )),
        0o600,
    );
    assert_eq!(
        mode(
            &harness
                .config
                .attachments_directory(harness.temporary.path())
        ),
        0o700,
    );
}

#[test]
fn initialization_does_not_modify_preexisting_reserved_paths() {
    let temporary = tempfile::tempdir().expect("temporary relay directory");
    let preexisting = temporary.path().join("attachments");
    std::fs::write(&preexisting, b"operator-owned file").expect("preexisting collision");

    initialize_relay(temporary.path()).expect_err("initialization must reject file collision");

    assert_eq!(
        std::fs::read(&preexisting).expect("preexisting file retained"),
        b"operator-owned file",
    );
    assert!(
        !temporary
            .path()
            .join(vaultnote_sync_server::config::CONFIG_FILENAME)
            .exists(),
    );
    assert!(!vaultnote_sync_server::config::tls_certificate_path(temporary.path()).exists(),);
    assert!(!vaultnote_sync_server::config::tls_private_key_path(temporary.path()).exists());
}

#[tokio::test]
async fn malformed_identifiers_are_rejected_without_filesystem_access() {
    let harness = Harness::new();
    let bytes = b"ciphertext";
    let request = attachment_upload_request(
        &harness,
        "bad$id",
        "operation-invalid-id",
        bytes,
        &sha256(bytes),
    );
    let response = harness
        .app
        .clone()
        .oneshot(request)
        .await
        .expect("invalid identifier response");
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

fn item_request(expected_token: Option<&str>, envelope: &(String, String)) -> Value {
    json!({
        "expectedVersionToken": expected_token,
        "encryptedPayload": envelope.0,
        "ciphertextSha256": envelope.1,
    })
}

fn attachment_upload_request(
    harness: &Harness,
    attachment_id: &str,
    operation_id: &str,
    body: &[u8],
    declared_checksum: &str,
) -> Request<Body> {
    let declared_size = if body.is_empty() {
        // An idempotent replay is answered before its body is consumed.
        31 * 4096
    } else {
        body.len()
    };
    Request::builder()
        .method(Method::PUT)
        .uri(format!("/v1/attachments/{attachment_id}"))
        .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
        .header(header::AUTHORIZATION, format!("Bearer {}", harness.token))
        .header(OPERATION_HEADER, operation_id)
        .header(CIPHERTEXT_SHA256_HEADER, declared_checksum)
        .header(header::CONTENT_LENGTH, declared_size)
        .body(Body::from(body.to_vec()))
        .expect("attachment request")
}

fn envelope(bytes: &[u8]) -> (String, String) {
    (STANDARD.encode(bytes), sha256(bytes))
}

fn sha256(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

async fn response_json<T: DeserializeOwned>(response: axum::response::Response) -> T {
    serde_json::from_slice(&response_bytes(response).await).expect("JSON response")
}

async fn response_value(response: axum::response::Response) -> Value {
    response_json(response).await
}

async fn response_bytes(response: axum::response::Response) -> Vec<u8> {
    response
        .into_body()
        .collect()
        .await
        .expect("response body")
        .to_bytes()
        .to_vec()
}
