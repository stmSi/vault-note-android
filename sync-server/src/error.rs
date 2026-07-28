use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum RelayError {
    #[error("relay is already initialized")]
    AlreadyInitialized,
    #[error("relay configuration is missing")]
    NotInitialized,
    #[error("relay configuration is invalid")]
    InvalidConfiguration,
    #[error("relay storage path is invalid")]
    InvalidStoragePath,
    #[error("relay I/O failed")]
    Io(#[from] std::io::Error),
    #[error("relay database failed")]
    Database(#[from] rusqlite::Error),
    #[error("relay serialization failed")]
    Serialization(#[from] serde_json::Error),
    #[error("relay certificate generation failed")]
    Certificate(#[from] rcgen::Error),
    #[error("LAN discovery failed")]
    Discovery(#[from] mdns_sd::Error),
    #[error("system clock is unavailable")]
    Clock,
}

#[derive(Debug, Clone)]
pub struct ApiError {
    status: StatusCode,
    code: &'static str,
    retryable: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorBody {
    code: &'static str,
    retryable: bool,
}

impl ApiError {
    pub const fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, "authentication_required", false)
    }

    pub const fn protocol_mismatch() -> Self {
        Self::new(StatusCode::UPGRADE_REQUIRED, "unsupported_protocol", false)
    }

    pub const fn invalid_request() -> Self {
        Self::new(StatusCode::BAD_REQUEST, "invalid_request", false)
    }

    pub const fn not_found() -> Self {
        Self::new(StatusCode::NOT_FOUND, "not_found", false)
    }

    pub const fn conflict(code: &'static str) -> Self {
        Self::new(StatusCode::CONFLICT, code, false)
    }

    pub const fn payload_too_large() -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, "payload_too_large", false)
    }

    pub const fn corrupted_upload() -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, "corrupted_upload", false)
    }

    pub const fn range_not_satisfiable() -> Self {
        Self::new(
            StatusCode::RANGE_NOT_SATISFIABLE,
            "range_not_satisfiable",
            false,
        )
    }

    pub const fn unavailable() -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, "server_unavailable", true)
    }

    pub const fn internal() -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "internal_error", true)
    }

    pub const fn new(status: StatusCode, code: &'static str, retryable: bool) -> Self {
        Self {
            status,
            code,
            retryable,
        }
    }

    pub const fn status(&self) -> StatusCode {
        self.status
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(ErrorBody {
                code: self.code,
                retryable: self.retryable,
            }),
        )
            .into_response()
    }
}

impl From<rusqlite::Error> for ApiError {
    fn from(_: rusqlite::Error) -> Self {
        Self::unavailable()
    }
}

impl From<std::io::Error> for ApiError {
    fn from(_: std::io::Error) -> Self {
        Self::unavailable()
    }
}

impl From<serde_json::Error> for ApiError {
    fn from(_: serde_json::Error) -> Self {
        Self::internal()
    }
}
