use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("invalid input")]
    InvalidInput { field: &'static str, reason: String },
    #[error("vault item was not found")]
    NotFound,
    #[error("vault item is not editable")]
    InvalidState,
    #[error("database operation failed")]
    Database(#[from] rusqlite::Error),
    #[error("application storage is unavailable")]
    Storage(#[from] std::io::Error),
    #[error("database schema is newer than this application supports")]
    UnsupportedSchema,
    #[error("system clock is unavailable")]
    Clock,
    #[error("database lock is unavailable")]
    DatabaseLock,
    #[error("application state lock is unavailable")]
    StateLock,
    #[error("vault password configuration is missing or invalid")]
    VaultConfiguration,
    #[error("vault password has not been configured")]
    VaultNotInitialized,
    #[error("vault authentication is required")]
    AuthenticationRequired,
    #[error("vault authentication failed")]
    InvalidCredentials,
    #[error("cryptographic operation failed")]
    Cryptography,
    #[error("selected file is too large")]
    FileTooLarge,
    #[error("selected file type is unsupported")]
    UnsupportedFile,
    #[error("backup is invalid or corrupted")]
    InvalidBackup,
    #[error("backup version is unsupported")]
    UnsupportedBackup,
    #[error("backup exceeds desktop safety limits")]
    BackupTooLarge,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandError {
    pub code: &'static str,
    pub message: &'static str,
    pub retryable: bool,
}

impl From<AppError> for CommandError {
    fn from(error: AppError) -> Self {
        match error {
            AppError::InvalidInput { field, reason } => {
                let (code, message) = match field {
                    "title" => ("invalid_title", "The note title is invalid."),
                    "body" => ("invalid_body", "The note body is invalid."),
                    "query" => ("invalid_query", "The search query is invalid."),
                    "id" => ("invalid_id", "The note identifier is invalid."),
                    _ => ("invalid_input", "The request is invalid."),
                };
                let _ = reason;
                Self {
                    code,
                    message,
                    retryable: false,
                }
            }
            AppError::NotFound => Self {
                code: "not_found",
                message: "The note no longer exists.",
                retryable: false,
            },
            AppError::InvalidState => Self {
                code: "invalid_state",
                message: "This operation is not available for the note's current state.",
                retryable: false,
            },
            AppError::Database(_) | AppError::DatabaseLock | AppError::StateLock => Self {
                code: "database_unavailable",
                message: "VaultNote could not access local storage.",
                retryable: true,
            },
            AppError::Storage(_) => Self {
                code: "storage_unavailable",
                message: "VaultNote could not access its private application storage.",
                retryable: true,
            },
            AppError::UnsupportedSchema => Self {
                code: "unsupported_schema",
                message: "This vault was created by a newer VaultNote version.",
                retryable: false,
            },
            AppError::Clock => Self {
                code: "clock_unavailable",
                message: "The system clock is unavailable.",
                retryable: true,
            },
            AppError::VaultConfiguration => Self {
                code: "vault_configuration_invalid",
                message: "The vault password configuration is missing or invalid. Restore the configuration or a verified encrypted backup.",
                retryable: false,
            },
            AppError::VaultNotInitialized => Self {
                code: "vault_not_initialized",
                message: "Create a vault password before unlocking VaultNote.",
                retryable: false,
            },
            AppError::AuthenticationRequired => Self {
                code: "authentication_required",
                message: "Unlock VaultNote to continue.",
                retryable: false,
            },
            AppError::InvalidCredentials => Self {
                code: "invalid_credentials",
                message: "The password is incorrect.",
                retryable: false,
            },
            AppError::Cryptography => Self {
                code: "cryptography_failed",
                message: "VaultNote could not complete the cryptographic operation.",
                retryable: false,
            },
            AppError::FileTooLarge => Self {
                code: "file_too_large",
                message: "The selected file is larger than 100 MiB.",
                retryable: false,
            },
            AppError::UnsupportedFile => Self {
                code: "unsupported_file",
                message: "The selected file type is not supported.",
                retryable: false,
            },
            AppError::InvalidBackup => Self {
                code: "invalid_backup",
                message: "The backup is invalid, corrupted, or the password is incorrect.",
                retryable: false,
            },
            AppError::UnsupportedBackup => Self {
                code: "unsupported_backup",
                message: "This backup format is not supported by this VaultNote version.",
                retryable: false,
            },
            AppError::BackupTooLarge => Self {
                code: "backup_too_large",
                message: "The backup exceeds this desktop client's safety limit.",
                retryable: false,
            },
        }
    }
}
