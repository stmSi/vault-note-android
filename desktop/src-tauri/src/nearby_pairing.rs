use std::{
    collections::HashMap,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use aes_gcm::{
    Aes256Gcm, Nonce,
    aead::{Aead, KeyInit, Payload},
};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::{Engine as _, engine::general_purpose::URL_SAFE_NO_PAD};
use hkdf::Hkdf;
use p256::{PublicKey, SecretKey, ecdh::diffie_hellman, elliptic_curve::sec1::ToEncodedPoint};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::Mutex;
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

use crate::error::AppError;

const PAIRING_VERSION: u32 = 1;
const RELAY_PROTOCOL_VERSION: &str = "3";
const PROTOCOL_HEADER: &str = "x-vaultnote-protocol";
const MAX_PAIRING_BODY_BYTES: usize = 4 * 1024;
const MAX_SESSIONS: usize = 16;
const SESSION_LIFETIME_MILLIS: i64 = 120_000;
const NONCE_BYTES: usize = 12;
const KEY_BYTES: usize = 32;
const CONTEXT_PREFIX: &[u8] = b"VaultNote Nearby Pairing v1";
const KEY_INFO: &[u8] = b"VaultNote nearby pairing encryption key v1";
const CODE_INFO: &[u8] = b"VaultNote nearby pairing verification code v1";

#[derive(Debug, Clone)]
pub struct PairingRelayIdentity {
    pub vault_id: String,
    pub certificate_sha256: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PendingNearbyPairing {
    pub request_id: String,
    pub device_name: String,
    pub verification_code: String,
    pub expires_at_epoch_millis: i64,
}

pub struct NearbyPairingSecret {
    pub authentication_token: Zeroizing<String>,
    pub master_key: Zeroizing<[u8; KEY_BYTES]>,
}

#[derive(Clone, Default)]
pub struct NearbyPairingBroker {
    sessions: Arc<Mutex<HashMap<String, PairingSession>>>,
}

struct PairingSession {
    summary: PendingNearbyPairing,
    context_sha256: [u8; KEY_BYTES],
    encryption_key: Option<Zeroizing<[u8; KEY_BYTES]>>,
    state: PairingState,
}

enum PairingState {
    Pending,
    Approved {
        nonce: String,
        encrypted_payload: String,
    },
    Rejected,
}

#[derive(Clone)]
struct PairingHttpState {
    broker: NearbyPairingBroker,
    identity: PairingRelayIdentity,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct StartPairingRequest {
    version: u32,
    device_name: String,
    client_public_key: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct StartPairingResponse {
    version: u32,
    request_id: String,
    server_public_key: String,
    expires_at_epoch_millis: i64,
    vault_id: String,
    certificate_sha256: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct PollPairingResponse {
    version: u32,
    status: &'static str,
    nonce: Option<String>,
    encrypted_payload: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ApprovedPairingPayload<'a> {
    version: u32,
    vault_id: &'a str,
    certificate_sha256: &'a str,
    authentication_token: &'a str,
    master_key: &'a str,
}

enum PollResult {
    Pending,
    Approved {
        nonce: String,
        encrypted_payload: String,
    },
    Rejected,
    Missing,
}

impl NearbyPairingBroker {
    pub fn router(&self, identity: PairingRelayIdentity) -> Router {
        Router::new()
            .route("/v1/nearby-pairing/requests", post(start_pairing))
            .route(
                "/v1/nearby-pairing/requests/{request_id}",
                get(poll_pairing).delete(cancel_pairing),
            )
            .layer(DefaultBodyLimit::max(MAX_PAIRING_BODY_BYTES))
            .with_state(PairingHttpState {
                broker: self.clone(),
                identity,
            })
    }

    pub async fn pending(&self) -> Result<Vec<PendingNearbyPairing>, AppError> {
        let now = now_epoch_millis()?;
        let mut sessions = self.sessions.lock().await;
        prune_expired(&mut sessions, now);
        let mut pending = sessions
            .values()
            .filter(|session| matches!(session.state, PairingState::Pending))
            .map(|session| session.summary.clone())
            .collect::<Vec<_>>();
        pending.sort_by_key(|request| request.expires_at_epoch_millis);
        Ok(pending)
    }

    pub async fn approve(
        &self,
        request_id: &str,
        identity: &PairingRelayIdentity,
        secret: &NearbyPairingSecret,
    ) -> Result<(), AppError> {
        let now = now_epoch_millis()?;
        let mut sessions = self.sessions.lock().await;
        prune_expired(&mut sessions, now);
        let session = sessions
            .get_mut(request_id)
            .ok_or_else(|| invalid_request_id("pairing request is unavailable"))?;
        if !matches!(session.state, PairingState::Pending) {
            return Err(invalid_request_id("pairing request is no longer pending"));
        }
        let encryption_key = session
            .encryption_key
            .take()
            .ok_or(AppError::Cryptography)?;
        let master_key = Zeroizing::new(URL_SAFE_NO_PAD.encode(secret.master_key.as_slice()));
        let payload = ApprovedPairingPayload {
            version: PAIRING_VERSION,
            vault_id: &identity.vault_id,
            certificate_sha256: &identity.certificate_sha256,
            authentication_token: secret.authentication_token.as_str(),
            master_key: master_key.as_str(),
        };
        let mut plaintext =
            Zeroizing::new(serde_json::to_vec(&payload).map_err(|_| AppError::Cryptography)?);
        let mut nonce = [0_u8; NONCE_BYTES];
        OsRng.fill_bytes(&mut nonce);
        let cipher = Aes256Gcm::new_from_slice(encryption_key.as_slice())
            .map_err(|_| AppError::Cryptography)?;
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: plaintext.as_slice(),
                    aad: &session.context_sha256,
                },
            )
            .map_err(|_| AppError::Cryptography)?;
        plaintext.zeroize();
        session.state = PairingState::Approved {
            nonce: URL_SAFE_NO_PAD.encode(nonce),
            encrypted_payload: URL_SAFE_NO_PAD.encode(ciphertext),
        };
        Ok(())
    }

    pub async fn reject(&self, request_id: &str) -> Result<(), AppError> {
        let now = now_epoch_millis()?;
        let mut sessions = self.sessions.lock().await;
        prune_expired(&mut sessions, now);
        let session = sessions
            .get_mut(request_id)
            .ok_or_else(|| invalid_request_id("pairing request is unavailable"))?;
        session.encryption_key.take();
        session.state = PairingState::Rejected;
        Ok(())
    }

    pub async fn clear(&self) {
        self.sessions.lock().await.clear();
    }

    async fn start(
        &self,
        identity: &PairingRelayIdentity,
        request: StartPairingRequest,
    ) -> Result<StartPairingResponse, ()> {
        if request.version != PAIRING_VERSION {
            return Err(());
        }
        let device_name = sanitize_device_name(&request.device_name);
        let client_key_bytes = URL_SAFE_NO_PAD
            .decode(request.client_public_key.as_bytes())
            .map_err(|_| ())?;
        if client_key_bytes.len() != 65 {
            return Err(());
        }
        let client_public = PublicKey::from_sec1_bytes(&client_key_bytes).map_err(|_| ())?;
        let server_secret = SecretKey::random(&mut OsRng);
        let server_public = server_secret.public_key();
        let server_public_bytes = server_public.to_encoded_point(false);
        let request_id = Uuid::new_v4().hyphenated().to_string();
        let expires_at_epoch_millis = now_epoch_millis()
            .map_err(|_| ())?
            .checked_add(SESSION_LIFETIME_MILLIS)
            .ok_or(())?;
        let context = pairing_context(
            &request_id,
            &client_key_bytes,
            server_public_bytes.as_bytes(),
            &identity.vault_id,
            &identity.certificate_sha256,
        );
        let context_sha256: [u8; KEY_BYTES] = Sha256::digest(&context).into();
        let shared = diffie_hellman(server_secret.to_nonzero_scalar(), client_public.as_affine());
        let (encryption_key, verification_code) =
            derive_pairing_values(shared.raw_secret_bytes().as_slice(), &context_sha256)
                .map_err(|_| ())?;
        let summary = PendingNearbyPairing {
            request_id: request_id.clone(),
            device_name,
            verification_code,
            expires_at_epoch_millis,
        };
        let mut sessions = self.sessions.lock().await;
        prune_expired(&mut sessions, now_epoch_millis().map_err(|_| ())?);
        if sessions.len() >= MAX_SESSIONS
            && let Some(oldest) = sessions
                .values()
                .min_by_key(|session| session.summary.expires_at_epoch_millis)
                .map(|session| session.summary.request_id.clone())
        {
            sessions.remove(&oldest);
        }
        sessions.insert(
            request_id.clone(),
            PairingSession {
                summary,
                context_sha256,
                encryption_key: Some(Zeroizing::new(encryption_key)),
                state: PairingState::Pending,
            },
        );
        Ok(StartPairingResponse {
            version: PAIRING_VERSION,
            request_id,
            server_public_key: URL_SAFE_NO_PAD.encode(server_public_bytes.as_bytes()),
            expires_at_epoch_millis,
            vault_id: identity.vault_id.clone(),
            certificate_sha256: identity.certificate_sha256.clone(),
        })
    }

    async fn poll(&self, request_id: &str) -> Result<PollResult, AppError> {
        let now = now_epoch_millis()?;
        let mut sessions = self.sessions.lock().await;
        prune_expired(&mut sessions, now);
        Ok(match sessions.get(request_id) {
            Some(session) => match &session.state {
                PairingState::Pending => PollResult::Pending,
                PairingState::Approved {
                    nonce,
                    encrypted_payload,
                } => PollResult::Approved {
                    nonce: nonce.clone(),
                    encrypted_payload: encrypted_payload.clone(),
                },
                PairingState::Rejected => PollResult::Rejected,
            },
            None => PollResult::Missing,
        })
    }
}

async fn start_pairing(
    State(state): State<PairingHttpState>,
    headers: HeaderMap,
    Json(request): Json<StartPairingRequest>,
) -> Response {
    if !valid_protocol(&headers) {
        return error_response(StatusCode::UPGRADE_REQUIRED, "protocol_mismatch");
    }
    match state.broker.start(&state.identity, request).await {
        Ok(response) => (StatusCode::CREATED, Json(response)).into_response(),
        Err(()) => error_response(StatusCode::BAD_REQUEST, "invalid_pairing_request"),
    }
}

async fn poll_pairing(
    State(state): State<PairingHttpState>,
    Path(request_id): Path<String>,
    headers: HeaderMap,
) -> Response {
    if !valid_protocol(&headers) {
        return error_response(StatusCode::UPGRADE_REQUIRED, "protocol_mismatch");
    }
    match state.broker.poll(&request_id).await {
        Ok(PollResult::Pending) => Json(PollPairingResponse {
            version: PAIRING_VERSION,
            status: "PENDING",
            nonce: None,
            encrypted_payload: None,
        })
        .into_response(),
        Ok(PollResult::Approved {
            nonce,
            encrypted_payload,
        }) => Json(PollPairingResponse {
            version: PAIRING_VERSION,
            status: "APPROVED",
            nonce: Some(nonce),
            encrypted_payload: Some(encrypted_payload),
        })
        .into_response(),
        Ok(PollResult::Rejected) => Json(PollPairingResponse {
            version: PAIRING_VERSION,
            status: "REJECTED",
            nonce: None,
            encrypted_payload: None,
        })
        .into_response(),
        Ok(PollResult::Missing) => error_response(StatusCode::GONE, "pairing_request_unavailable"),
        Err(_) => error_response(StatusCode::INTERNAL_SERVER_ERROR, "pairing_unavailable"),
    }
}

async fn cancel_pairing(
    State(state): State<PairingHttpState>,
    Path(request_id): Path<String>,
    headers: HeaderMap,
) -> Response {
    if !valid_protocol(&headers) {
        return error_response(StatusCode::UPGRADE_REQUIRED, "protocol_mismatch");
    }
    match state.broker.reject(&request_id).await {
        Ok(()) => StatusCode::NO_CONTENT.into_response(),
        Err(_) => error_response(StatusCode::GONE, "pairing_request_unavailable"),
    }
}

fn valid_protocol(headers: &HeaderMap) -> bool {
    headers
        .get(PROTOCOL_HEADER)
        .and_then(|value| value.to_str().ok())
        == Some(RELAY_PROTOCOL_VERSION)
}

fn sanitize_device_name(value: &str) -> String {
    let cleaned = value
        .trim()
        .chars()
        .filter(|character| !character.is_control())
        .take(64)
        .collect::<String>();
    if cleaned.is_empty() {
        "Android device".to_owned()
    } else {
        cleaned
    }
}

fn pairing_context(
    request_id: &str,
    client_public_key: &[u8],
    server_public_key: &[u8],
    vault_id: &str,
    certificate_sha256: &str,
) -> Vec<u8> {
    let mut context = Vec::with_capacity(256);
    append_context_part(&mut context, CONTEXT_PREFIX);
    append_context_part(&mut context, request_id.as_bytes());
    append_context_part(&mut context, client_public_key);
    append_context_part(&mut context, server_public_key);
    append_context_part(&mut context, vault_id.as_bytes());
    append_context_part(&mut context, certificate_sha256.as_bytes());
    context
}

fn append_context_part(context: &mut Vec<u8>, value: &[u8]) {
    context.extend_from_slice(&(value.len() as u32).to_be_bytes());
    context.extend_from_slice(value);
}

fn derive_pairing_values(
    shared_secret: &[u8],
    context_sha256: &[u8; KEY_BYTES],
) -> Result<([u8; KEY_BYTES], String), AppError> {
    let hkdf = Hkdf::<Sha256>::new(Some(context_sha256), shared_secret);
    let mut encryption_key = [0_u8; KEY_BYTES];
    hkdf.expand(KEY_INFO, &mut encryption_key)
        .map_err(|_| AppError::Cryptography)?;
    let mut code_bytes = [0_u8; 4];
    hkdf.expand(CODE_INFO, &mut code_bytes)
        .map_err(|_| AppError::Cryptography)?;
    let code = u32::from_be_bytes(code_bytes) % 1_000_000;
    Ok((
        encryption_key,
        format!("{:03} {:03}", code / 1_000, code % 1_000),
    ))
}

fn prune_expired(sessions: &mut HashMap<String, PairingSession>, now: i64) {
    sessions.retain(|_, session| session.summary.expires_at_epoch_millis > now);
}

fn now_epoch_millis() -> Result<i64, AppError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| AppError::InvalidState)?;
    i64::try_from(duration.as_millis()).map_err(|_| AppError::InvalidState)
}

fn invalid_request_id(reason: &str) -> AppError {
    AppError::InvalidInput {
        field: "pairing_request",
        reason: reason.to_owned(),
    }
}

fn error_response(status: StatusCode, code: &'static str) -> Response {
    (
        status,
        Json(serde_json::json!({
            "error": {
                "code": code,
                "retryable": false
            }
        })),
    )
        .into_response()
}

#[cfg(test)]
mod tests {
    use axum::{body::Body, http::Request};
    use tower::ServiceExt;

    use super::*;

    #[tokio::test]
    async fn approved_pairing_encrypts_secrets_for_requesting_device() {
        let broker = NearbyPairingBroker::default();
        let identity = PairingRelayIdentity {
            vault_id: "vault-test".to_owned(),
            certificate_sha256: "a".repeat(64),
        };
        let client_secret = SecretKey::random(&mut OsRng);
        let client_public = client_secret.public_key().to_encoded_point(false);
        let response = broker
            .start(
                &identity,
                StartPairingRequest {
                    version: PAIRING_VERSION,
                    device_name: "  Galaxy Test\u{0000} ".to_owned(),
                    client_public_key: URL_SAFE_NO_PAD.encode(client_public.as_bytes()),
                },
            )
            .await
            .expect("pairing should start");
        let pending = broker.pending().await.expect("pending request should load");
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].device_name, "Galaxy Test");

        let server_public_bytes = URL_SAFE_NO_PAD
            .decode(response.server_public_key)
            .expect("server key should decode");
        let server_public =
            PublicKey::from_sec1_bytes(&server_public_bytes).expect("server key should parse");
        let context = pairing_context(
            &response.request_id,
            client_public.as_bytes(),
            &server_public_bytes,
            &identity.vault_id,
            &identity.certificate_sha256,
        );
        let context_sha256: [u8; KEY_BYTES] = Sha256::digest(&context).into();
        let shared = diffie_hellman(client_secret.to_nonzero_scalar(), server_public.as_affine());
        let (key, code) =
            derive_pairing_values(shared.raw_secret_bytes().as_slice(), &context_sha256)
                .expect("values should derive");
        assert_eq!(pending[0].verification_code, code);

        let secret = NearbyPairingSecret {
            authentication_token: Zeroizing::new("vns_test-token".to_owned()),
            master_key: Zeroizing::new([7_u8; KEY_BYTES]),
        };
        broker
            .approve(&response.request_id, &identity, &secret)
            .await
            .expect("request should approve");
        let PollResult::Approved {
            nonce,
            encrypted_payload,
        } = broker
            .poll(&response.request_id)
            .await
            .expect("request should poll")
        else {
            panic!("approved payload should be returned");
        };
        let nonce = URL_SAFE_NO_PAD.decode(nonce).expect("nonce should decode");
        let encrypted = URL_SAFE_NO_PAD
            .decode(encrypted_payload)
            .expect("payload should decode");
        let cipher = Aes256Gcm::new_from_slice(&key).expect("key should be valid");
        let mut plaintext = cipher
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &encrypted,
                    aad: &context_sha256,
                },
            )
            .expect("payload should authenticate");
        let payload: serde_json::Value =
            serde_json::from_slice(&plaintext).expect("payload should parse");
        assert_eq!(payload["authenticationToken"], "vns_test-token");
        assert_eq!(
            payload["masterKey"],
            URL_SAFE_NO_PAD.encode([7_u8; KEY_BYTES])
        );
        plaintext.zeroize();
    }

    #[test]
    fn android_interop_vector_is_stable() {
        let client_secret =
            SecretKey::from_slice(&[1_u8; KEY_BYTES]).expect("client scalar should be valid");
        let server_secret =
            SecretKey::from_slice(&[2_u8; KEY_BYTES]).expect("server scalar should be valid");
        let client_public = client_secret.public_key().to_encoded_point(false);
        let server_public = server_secret.public_key().to_encoded_point(false);
        let context = pairing_context(
            "123e4567-e89b-12d3-a456-426614174000",
            client_public.as_bytes(),
            server_public.as_bytes(),
            "vault-interop",
            &"a".repeat(64),
        );
        let context_sha256: [u8; KEY_BYTES] = Sha256::digest(&context).into();
        let shared = diffie_hellman(
            client_secret.to_nonzero_scalar(),
            server_secret.public_key().as_affine(),
        );
        let (key, code) =
            derive_pairing_values(shared.raw_secret_bytes().as_slice(), &context_sha256)
                .expect("values should derive");
        assert_eq!(
            URL_SAFE_NO_PAD.encode(client_public.as_bytes()),
            "BG_wO5SSQc4drdQ1GeaWDgqFtBppoFwygQOqK84VlMoWPE91OlW_AdxT9sCwx-7ni0DG_30lqW4igrmJzvccFEo"
        );
        assert_eq!(
            URL_SAFE_NO_PAD.encode(server_public.as_bytes()),
            "BFUPRxAD89-Xw99QaseX9nIfsaH7e49vg9IkSYplyI4kE2CT1wEuUJpzcVy9CwCjzA_0tcAbP_oZarH7MnA2uOY"
        );
        assert_eq!(
            URL_SAFE_NO_PAD.encode(context_sha256),
            "PkDJYg-oJhQucJIBws3CGlQ2ttervOeR3Gg2fjNII4E"
        );
        assert_eq!(
            URL_SAFE_NO_PAD.encode(key),
            "NeSJ393outL1rB3FM_GdmCllZ8s3mKPO4pbO-8JjygY"
        );
        assert_eq!(code, "733 504");
    }

    #[tokio::test]
    async fn http_endpoint_requires_protocol_and_creates_pending_request() {
        let broker = NearbyPairingBroker::default();
        let identity = PairingRelayIdentity {
            vault_id: "vault-http-test".to_owned(),
            certificate_sha256: "b".repeat(64),
        };
        let client_secret = SecretKey::random(&mut OsRng);
        let client_public = client_secret.public_key().to_encoded_point(false);
        let body = serde_json::json!({
            "version": PAIRING_VERSION,
            "deviceName": "Galaxy HTTP Test",
            "clientPublicKey": URL_SAFE_NO_PAD.encode(client_public.as_bytes()),
        })
        .to_string();
        let router = broker.router(identity);
        let missing_protocol = router
            .clone()
            .oneshot(
                Request::post("/v1/nearby-pairing/requests")
                    .header("content-type", "application/json")
                    .body(Body::from(body.clone()))
                    .expect("request should build"),
            )
            .await
            .expect("router should respond");
        assert_eq!(missing_protocol.status(), StatusCode::UPGRADE_REQUIRED);

        let created = router
            .oneshot(
                Request::post("/v1/nearby-pairing/requests")
                    .header("content-type", "application/json")
                    .header(PROTOCOL_HEADER, RELAY_PROTOCOL_VERSION)
                    .body(Body::from(body))
                    .expect("request should build"),
            )
            .await
            .expect("router should respond");
        assert_eq!(created.status(), StatusCode::CREATED);
        let pending = broker.pending().await.expect("pending request should load");
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].device_name, "Galaxy HTTP Test");
    }
}
