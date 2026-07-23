use std::{
    path::PathBuf,
    sync::{Arc, RwLock},
};

use crate::{
    crypto::AttachmentCrypto,
    database::{self, Database},
    error::AppError,
    models::{AuthStatus, VaultEncryptionMode},
    repository::{SqliteVaultRepository, VaultRepository},
    services::AppState,
    storage,
    vault_key::{MasterKey, PlaintextVaultStore, VaultKeyStore},
};

pub struct RuntimeState {
    database_path: PathBuf,
    app_data_directory: PathBuf,
    key_store: VaultKeyStore,
    plaintext_store: PlaintextVaultStore,
    services: RwLock<Option<AppState>>,
}

impl RuntimeState {
    pub fn new(database_path: PathBuf) -> Result<Self, AppError> {
        let app_data_directory = database_path
            .parent()
            .ok_or_else(|| std::io::Error::other("application data directory unavailable"))?
            .to_owned();
        let state = Self {
            key_store: VaultKeyStore::new(&app_data_directory),
            plaintext_store: PlaintextVaultStore::new(&app_data_directory),
            database_path,
            app_data_directory,
            services: RwLock::new(None),
        };
        if state.configured_mode()? == VaultEncryptionMode::Unencrypted {
            *state.services.write().map_err(|_| AppError::StateLock)? =
                Some(state.open_unencrypted_services()?);
        }
        Ok(state)
    }

    pub fn status(&self) -> Result<AuthStatus, AppError> {
        let services = self.services.read().map_err(|_| AppError::StateLock)?;
        let encryption_mode = self.configured_mode()?;
        Ok(AuthStatus {
            setup_required: encryption_mode == VaultEncryptionMode::Unconfigured,
            unlocked: services.is_some(),
            encryption_mode,
        })
    }

    pub fn initialize(&self, password: &str) -> Result<AuthStatus, AppError> {
        let mut services = self.services.write().map_err(|_| AppError::StateLock)?;
        if services.is_some() || self.configured_mode()? != VaultEncryptionMode::Unconfigured {
            return Err(AppError::InvalidState);
        }
        if !database::can_initialize_vault(&self.database_path)? {
            return Err(AppError::VaultConfiguration);
        }
        let master_key = self.key_store.initialize(password)?;
        *services = Some(self.open_services(master_key)?);
        drop(services);
        self.status()
    }

    pub fn initialize_unencrypted(&self) -> Result<AuthStatus, AppError> {
        let mut services = self.services.write().map_err(|_| AppError::StateLock)?;
        if services.is_some() || self.configured_mode()? != VaultEncryptionMode::Unconfigured {
            return Err(AppError::InvalidState);
        }
        if !database::can_initialize_vault(&self.database_path)? {
            return Err(AppError::VaultConfiguration);
        }
        self.plaintext_store.initialize()?;
        *services = Some(self.open_unencrypted_services()?);
        drop(services);
        self.status()
    }

    pub fn unlock(&self, password: &str) -> Result<AuthStatus, AppError> {
        let mut services = self.services.write().map_err(|_| AppError::StateLock)?;
        if services.is_some() {
            drop(services);
            return self.status();
        }
        if self.configured_mode()? != VaultEncryptionMode::Password {
            return Err(AppError::VaultNotInitialized);
        }
        let master_key = self.key_store.unlock(password)?;
        *services = Some(self.open_services(master_key)?);
        drop(services);
        self.status()
    }

    pub fn lock(&self) -> Result<AuthStatus, AppError> {
        if self.configured_mode()? != VaultEncryptionMode::Password {
            return Err(AppError::InvalidState);
        }
        let mut services = self.services.write().map_err(|_| AppError::StateLock)?;
        services.take();
        drop(services);
        self.status()
    }

    pub fn with_services<T>(
        &self,
        operation: impl FnOnce(&AppState) -> Result<T, AppError>,
    ) -> Result<T, AppError> {
        let services = self.services.read().map_err(|_| AppError::StateLock)?;
        operation(services.as_ref().ok_or(AppError::AuthenticationRequired)?)
    }

    fn open_services(&self, master_key: MasterKey) -> Result<AppState, AppError> {
        let master_key = Arc::new(master_key);
        let database = Database::open(&self.database_path, master_key.as_bytes())?;
        storage::harden_database_file(&self.database_path)?;
        let attachment_crypto =
            AttachmentCrypto::new(Arc::clone(&master_key), &self.app_data_directory)?;
        let repository: Arc<dyn VaultRepository> = Arc::new(SqliteVaultRepository::new(database));
        Ok(AppState::new(repository, attachment_crypto))
    }

    fn open_unencrypted_services(&self) -> Result<AppState, AppError> {
        let database = Database::open_unencrypted(&self.database_path)?;
        storage::harden_database_file(&self.database_path)?;
        let attachment_storage = AttachmentCrypto::new_unencrypted(&self.app_data_directory)?;
        let repository: Arc<dyn VaultRepository> = Arc::new(SqliteVaultRepository::new(database));
        Ok(AppState::new(repository, attachment_storage))
    }

    fn configured_mode(&self) -> Result<VaultEncryptionMode, AppError> {
        let password = self.key_store.is_configured();
        let unencrypted = self.plaintext_store.is_configured();
        match (password, unencrypted) {
            (false, false) => Ok(VaultEncryptionMode::Unconfigured),
            (true, false) => Ok(VaultEncryptionMode::Password),
            (false, true) => {
                self.plaintext_store.validate()?;
                Ok(VaultEncryptionMode::Unencrypted)
            }
            (true, true) => Err(AppError::VaultConfiguration),
        }
    }

    #[cfg(test)]
    fn for_tests(database_path: PathBuf) -> Result<Self, AppError> {
        let app_data_directory = database_path
            .parent()
            .ok_or_else(|| std::io::Error::other("application data directory unavailable"))?
            .to_owned();
        let state = Self {
            key_store: VaultKeyStore::with_test_parameters(&app_data_directory),
            plaintext_store: PlaintextVaultStore::new(&app_data_directory),
            database_path,
            app_data_directory,
            services: RwLock::new(None),
        };
        if state.configured_mode()? == VaultEncryptionMode::Unencrypted {
            *state.services.write().map_err(|_| AppError::StateLock)? =
                Some(state.open_unencrypted_services()?);
        }
        Ok(state)
    }
}

#[cfg(test)]
mod tests {
    use std::{fs::File, io::Read};

    use tempfile::tempdir;

    use super::*;

    #[test]
    fn setup_lock_and_unlock_control_database_lifetime() {
        let directory = tempdir().expect("temporary directory should exist");
        let database_path = directory.path().join("vaultnote.db");
        let runtime = RuntimeState::for_tests(database_path.clone())
            .expect("runtime state should initialize");

        let initial = runtime.status().expect("status should be available");
        assert!(initial.setup_required);
        assert!(!initial.unlocked);
        assert_eq!(initial.encryption_mode, VaultEncryptionMode::Unconfigured);

        let initialized = runtime
            .initialize("correct horse battery staple")
            .expect("vault should initialize");
        assert!(!initialized.setup_required);
        assert!(initialized.unlocked);
        assert_eq!(initialized.encryption_mode, VaultEncryptionMode::Password);
        let mut header = [0_u8; 16];
        File::open(&database_path)
            .and_then(|mut file| file.read_exact(&mut header))
            .expect("database header should be readable");
        assert_ne!(&header, b"SQLite format 3\0");

        assert!(!runtime.lock().expect("vault should lock").unlocked);
        assert!(matches!(
            runtime.unlock("incorrect password"),
            Err(AppError::InvalidCredentials)
        ));
        assert!(
            runtime
                .unlock("correct horse battery staple")
                .expect("correct password should unlock")
                .unlocked
        );
    }

    #[test]
    fn unencrypted_vault_opens_without_password_and_cannot_lock() {
        let directory = tempdir().expect("temporary directory should exist");
        let database_path = directory.path().join("vaultnote.db");
        let runtime = RuntimeState::for_tests(database_path.clone())
            .expect("runtime state should initialize");
        let status = runtime
            .initialize_unencrypted()
            .expect("unencrypted vault should initialize");
        assert!(status.unlocked);
        assert_eq!(status.encryption_mode, VaultEncryptionMode::Unencrypted);
        assert!(matches!(runtime.lock(), Err(AppError::InvalidState)));
        drop(runtime);

        let reopened = RuntimeState::for_tests(database_path)
            .expect("unencrypted vault should reopen automatically");
        let status = reopened.status().expect("status should be available");
        assert!(status.unlocked);
        assert_eq!(status.encryption_mode, VaultEncryptionMode::Unencrypted);
    }
}
