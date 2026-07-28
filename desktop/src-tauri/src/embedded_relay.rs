use std::{
    fs::{File, OpenOptions},
    net::{Ipv4Addr, SocketAddr, TcpListener},
    path::{Path, PathBuf},
    sync::{Arc, Mutex as StdMutex},
    time::Duration,
};

use axum_server::{Handle, tls_rustls::RustlsConfig};
use fs2::FileExt;
use serde::Serialize;
use tokio::{sync::Mutex, task::JoinHandle};
use vaultnote_sync_server::{
    AppState as RelayAppState, DiscoveryAdvertisement, DiscoveryGuard, RelayConfig, Storage,
    config as relay_config, initialize_relay, load_config, rotate_authentication_token,
    router as relay_router,
};
use zeroize::Zeroizing;

use crate::error::AppError;
use crate::nearby_pairing::{
    NearbyPairingBroker, NearbyPairingSecret, PairingRelayIdentity, PendingNearbyPairing,
};

const RELAY_DIRECTORY: &str = "embedded-relay";
const PROCESS_LOCK_FILENAME: &str = "host.lock";
const PREFERRED_PORT: u16 = 8787;
const START_TIMEOUT: Duration = Duration::from_secs(5);
const STOP_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct EmbeddedRelayStatus {
    pub enabled: bool,
    pub running: bool,
    pub port: Option<u16>,
    pub vault_id: Option<String>,
    pub certificate_sha256: Option<String>,
}

pub struct EmbeddedRelayStart {
    pub status: EmbeddedRelayStatus,
    pub authentication_token: Option<Zeroizing<String>>,
}

#[derive(Clone)]
pub struct EmbeddedRelayHost {
    inner: Arc<EmbeddedRelayInner>,
}

struct EmbeddedRelayInner {
    directory: PathBuf,
    advertise: bool,
    operation: Mutex<()>,
    running: StdMutex<Option<RunningRelay>>,
    pairing: NearbyPairingBroker,
}

struct RunningRelay {
    config: RelayConfig,
    port: u16,
    handle: Handle<SocketAddr>,
    task: JoinHandle<()>,
    _discovery: Option<DiscoveryGuard>,
    _process_lock: File,
}

impl EmbeddedRelayHost {
    pub fn new(app_data_directory: &Path) -> Result<Self, AppError> {
        Self::with_advertisement(app_data_directory, true)
    }

    fn with_advertisement(app_data_directory: &Path, advertise: bool) -> Result<Self, AppError> {
        if !app_data_directory.is_absolute() {
            return Err(AppError::EmbeddedRelayUnavailable);
        }
        Ok(Self {
            inner: Arc::new(EmbeddedRelayInner {
                directory: app_data_directory.join(RELAY_DIRECTORY),
                advertise,
                operation: Mutex::new(()),
                running: StdMutex::new(None),
                pairing: NearbyPairingBroker::default(),
            }),
        })
    }

    pub fn is_enabled(&self) -> bool {
        self.inner
            .directory
            .join(relay_config::CONFIG_FILENAME)
            .is_file()
    }

    pub async fn start_if_enabled(&self) -> Result<EmbeddedRelayStatus, AppError> {
        let _operation = self.inner.operation.lock().await;
        if !self.is_enabled() {
            return Ok(EmbeddedRelayStatus::disabled());
        }
        self.start_locked(false).await.map(|started| started.status)
    }

    pub async fn enable(&self) -> Result<EmbeddedRelayStart, AppError> {
        let _operation = self.inner.operation.lock().await;
        self.start_locked(true).await
    }

    pub async fn rotate_access(&self) -> Result<EmbeddedRelayStart, AppError> {
        let _operation = self.inner.operation.lock().await;
        if !self.is_enabled() {
            return Err(AppError::SyncNotConfigured);
        }
        self.stop_locked().await?;
        let directory = self.inner.directory.clone();
        let token = tokio::task::spawn_blocking(move || rotate_authentication_token(&directory))
            .await
            .map_err(|_| AppError::EmbeddedRelayUnavailable)?
            .map_err(|_| AppError::EmbeddedRelayUnavailable)?;
        let mut started = self.start_locked(false).await?;
        started.authentication_token = Some(token);
        Ok(started)
    }

    pub async fn pending_pairings(&self) -> Result<Vec<PendingNearbyPairing>, AppError> {
        self.inner.pairing.pending().await
    }

    pub async fn approve_pairing(
        &self,
        request_id: &str,
        secret: &NearbyPairingSecret,
    ) -> Result<(), AppError> {
        let identity = self.running_identity()?;
        self.inner
            .pairing
            .approve(request_id, &identity, secret)
            .await
    }

    pub async fn reject_pairing(&self, request_id: &str) -> Result<(), AppError> {
        self.inner.pairing.reject(request_id).await
    }

    async fn start_locked(&self, initialize: bool) -> Result<EmbeddedRelayStart, AppError> {
        {
            let mut running = self.inner.running.lock().map_err(|_| AppError::StateLock)?;
            if running
                .as_ref()
                .is_some_and(|value| !value.task.is_finished())
            {
                let value = running.as_ref().ok_or(AppError::StateLock)?;
                return Ok(EmbeddedRelayStart {
                    status: EmbeddedRelayStatus::running(&value.config, value.port),
                    authentication_token: None,
                });
            }
            if let Some(finished) = running.take() {
                finished.task.abort();
            }
        }

        let directory = self.inner.directory.clone();
        let preparation = tokio::task::spawn_blocking(move || {
            let (config, authentication_token) =
                if directory.join(relay_config::CONFIG_FILENAME).is_file() {
                    (load_config(&directory)?, None)
                } else if initialize {
                    let initialized = initialize_relay(&directory)?;
                    (initialized.config, Some(initialized.authentication_token))
                } else {
                    return Err(vaultnote_sync_server::RelayError::NotInitialized);
                };
            relay_config::verify_tls_identity(&directory, &config)?;
            let process_lock = acquire_process_lock(&directory)?;
            let storage = Storage::open(&directory, &config)?;
            let state = RelayAppState::new(config.clone(), storage)?;
            Ok::<_, vaultnote_sync_server::RelayError>((
                config,
                authentication_token,
                state,
                process_lock,
            ))
        })
        .await
        .map_err(|_| AppError::EmbeddedRelayUnavailable)?
        .map_err(|_| AppError::EmbeddedRelayUnavailable)?;
        let (config, authentication_token, relay_state, process_lock) = preparation;

        let tls = RustlsConfig::from_pem_file(
            relay_config::tls_certificate_path(&self.inner.directory),
            relay_config::tls_private_key_path(&self.inner.directory),
        )
        .await
        .map_err(|_| AppError::EmbeddedRelayUnavailable)?;
        let listener = bind_listener()?;
        let port = listener
            .local_addr()
            .map_err(|_| AppError::EmbeddedRelayUnavailable)?
            .port();
        let discovery = if self.inner.advertise {
            Some(
                DiscoveryAdvertisement::from_config(&config, port)
                    .start()
                    .map_err(|_| AppError::EmbeddedRelayUnavailable)?,
            )
        } else {
            None
        };
        let handle: Handle<SocketAddr> = Handle::new();
        let pairing_identity = PairingRelayIdentity {
            vault_id: config.vault_id.clone(),
            certificate_sha256: config.tls.certificate_sha256.clone(),
        };
        let router = relay_router(relay_state).merge(self.inner.pairing.router(pairing_identity));
        let server = axum_server::from_tcp_rustls(listener, tls)
            .map_err(|_| AppError::EmbeddedRelayUnavailable)?
            .handle(handle.clone())
            .serve(router.into_make_service());
        let task = tokio::spawn(async move {
            let _ = server.await;
        });
        let listening = tokio::time::timeout(START_TIMEOUT, handle.listening())
            .await
            .map_err(|_| AppError::EmbeddedRelayUnavailable)?
            .ok_or(AppError::EmbeddedRelayUnavailable)?;
        if listening.port() != port || task.is_finished() {
            handle.graceful_shutdown(Some(Duration::ZERO));
            task.abort();
            return Err(AppError::EmbeddedRelayUnavailable);
        }
        let status = EmbeddedRelayStatus::running(&config, port);
        *self.inner.running.lock().map_err(|_| AppError::StateLock)? = Some(RunningRelay {
            config,
            port,
            handle,
            task,
            _discovery: discovery,
            _process_lock: process_lock,
        });
        Ok(EmbeddedRelayStart {
            status,
            authentication_token,
        })
    }

    async fn stop_locked(&self) -> Result<(), AppError> {
        self.inner.pairing.clear().await;
        let running = self
            .inner
            .running
            .lock()
            .map_err(|_| AppError::StateLock)?
            .take();
        let Some(mut running) = running else {
            return Ok(());
        };
        running.handle.graceful_shutdown(Some(STOP_TIMEOUT));
        if tokio::time::timeout(STOP_TIMEOUT, &mut running.task)
            .await
            .is_err()
        {
            running.task.abort();
        }
        Ok(())
    }

    fn running_identity(&self) -> Result<PairingRelayIdentity, AppError> {
        let running = self.inner.running.lock().map_err(|_| AppError::StateLock)?;
        let relay = running
            .as_ref()
            .filter(|value| !value.task.is_finished())
            .ok_or(AppError::EmbeddedRelayUnavailable)?;
        Ok(PairingRelayIdentity {
            vault_id: relay.config.vault_id.clone(),
            certificate_sha256: relay.config.tls.certificate_sha256.clone(),
        })
    }

    #[cfg(test)]
    pub(crate) fn for_tests(app_data_directory: &Path) -> Result<Self, AppError> {
        Self::with_advertisement(app_data_directory, false)
    }

    #[cfg(test)]
    async fn stop(&self) -> Result<(), AppError> {
        let _operation = self.inner.operation.lock().await;
        self.stop_locked().await
    }
}

impl EmbeddedRelayStatus {
    fn disabled() -> Self {
        Self {
            enabled: false,
            running: false,
            port: None,
            vault_id: None,
            certificate_sha256: None,
        }
    }

    fn running(config: &RelayConfig, port: u16) -> Self {
        Self {
            enabled: true,
            running: true,
            port: Some(port),
            vault_id: Some(config.vault_id.clone()),
            certificate_sha256: Some(config.tls.certificate_sha256.clone()),
        }
    }
}

impl Drop for EmbeddedRelayInner {
    fn drop(&mut self) {
        if let Ok(running) = self.running.get_mut()
            && let Some(running) = running.take()
        {
            running.handle.graceful_shutdown(Some(Duration::ZERO));
            running.task.abort();
        }
    }
}

fn bind_listener() -> Result<TcpListener, AppError> {
    let preferred = TcpListener::bind((Ipv4Addr::UNSPECIFIED, PREFERRED_PORT));
    let listener = preferred
        .or_else(|_| TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)))
        .map_err(|_| AppError::EmbeddedRelayUnavailable)?;
    listener
        .set_nonblocking(true)
        .map_err(|_| AppError::EmbeddedRelayUnavailable)?;
    Ok(listener)
}

fn acquire_process_lock(directory: &Path) -> Result<File, vaultnote_sync_server::RelayError> {
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let file = options.open(directory.join(PROCESS_LOCK_FILENAME))?;
    file.try_lock_exclusive()?;
    Ok(file)
}

#[cfg(test)]
mod tests {
    use std::{fs, sync::Arc};

    use tempfile::tempdir;

    use super::*;
    use crate::{
        crypto::AttachmentCrypto,
        database::Database,
        relay_client::{ProvisionalRelayAccess, RelayClient},
        repository::{SqliteVaultRepository, VaultRepository},
        sync_credentials::SyncCredentialStore,
        sync_engine::{LanSyncService, PairRelayParameters},
        sync_store::SyncStore,
    };

    #[tokio::test]
    async fn embedded_host_initializes_restarts_and_serves_the_real_protocol() {
        let directory = tempdir().expect("temporary directory should exist");
        let host =
            EmbeddedRelayHost::for_tests(directory.path()).expect("embedded host should configure");
        assert_eq!(
            host.start_if_enabled()
                .await
                .expect("disabled status should load"),
            EmbeddedRelayStatus::disabled()
        );

        let started = host.enable().await.expect("embedded host should start");
        let token = started
            .authentication_token
            .expect("new relay should return its one-time token");
        let status = started.status;
        assert!(status.enabled);
        assert!(status.running);
        let client = RelayClient::new(ProvisionalRelayAccess {
            host_address: "127.0.0.1",
            port: status.port.expect("running port should exist"),
            certificate_sha256: status
                .certificate_sha256
                .as_deref()
                .expect("fingerprint should exist"),
            authentication_token: &token,
        })
        .expect("pinned client should configure");
        assert_eq!(
            client
                .relay_information()
                .await
                .expect("embedded protocol should respond")
                .vault_id,
            status.vault_id.as_deref().expect("vault ID should exist")
        );

        let contender =
            EmbeddedRelayHost::for_tests(directory.path()).expect("second host should configure");
        assert!(matches!(
            contender.start_if_enabled().await,
            Err(AppError::EmbeddedRelayUnavailable)
        ));
        host.stop().await.expect("embedded host should stop");
        let restarted = contender
            .start_if_enabled()
            .await
            .expect("configured host should restart");
        assert!(restarted.running);
        let restarted_client = RelayClient::new(ProvisionalRelayAccess {
            host_address: "127.0.0.1",
            port: restarted.port.expect("restarted port should exist"),
            certificate_sha256: restarted
                .certificate_sha256
                .as_deref()
                .expect("restarted fingerprint should exist"),
            authentication_token: &token,
        })
        .expect("restarted pinned client should configure");
        restarted_client
            .relay_information()
            .await
            .expect("existing token should survive restart");
    }

    #[tokio::test]
    async fn desktop_self_pairs_and_pushes_through_its_embedded_host() {
        let directory = tempdir().expect("temporary directory should exist");
        let host =
            EmbeddedRelayHost::for_tests(directory.path()).expect("embedded host should configure");
        let started = host.enable().await.expect("embedded host should start");
        let token = started
            .authentication_token
            .expect("new relay should return its one-time token");
        let status = started.status;

        let client_root = directory.path().join("desktop-client");
        fs::create_dir_all(&client_root).expect("client directory should exist");
        let database =
            Database::open_unencrypted(&client_root.join("vaultnote.db")).expect("database opens");
        let repository = SqliteVaultRepository::new(database.clone());
        repository
            .create_note("Hosted locally", "No separate relay process", 1_000)
            .expect("note should be queued");
        let attachments =
            AttachmentCrypto::new_unencrypted(&client_root).expect("attachments should configure");
        let credentials = Arc::new(
            SyncCredentialStore::new(&client_root, None).expect("credentials should configure"),
        );
        let sync = LanSyncService::new(
            SyncStore::new(database),
            credentials,
            attachments,
            &client_root,
        )
        .expect("sync should configure");
        sync.pair(PairRelayParameters {
            host_address: "127.0.0.1".to_owned(),
            port: status.port.expect("running port should exist"),
            certificate_sha256: status.certificate_sha256.expect("fingerprint should exist"),
            authentication_token: token.to_string(),
            sync_password: "shared phone sync password".to_owned(),
            expected_vault_id: status.vault_id,
            fingerprint_confirmed: true,
        })
        .await
        .expect("desktop should self-pair");
        let report = sync.run_once().await.expect("self-hosted sync should run");
        assert_eq!(report.uploaded_items, 1);
    }
}
