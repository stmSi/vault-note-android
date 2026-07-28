use std::{
    collections::HashMap,
    fs::{self, File, OpenOptions},
    io::{self, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use serde::Serialize;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use tokio::sync::Mutex;
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

use crate::{
    crypto::AttachmentCrypto,
    error::AppError,
    lan_discovery::{self, DiscoveredRelay},
    nearby_pairing::NearbyPairingSecret,
    relay_client::{KeyCheckResult, ProvisionalRelayAccess, RelayClient},
    services::now_epoch_millis,
    sync_credentials::{
        CredentialProtection, RelayPublicConfig, RelaySecrets, SyncCredentialStore,
    },
    sync_crypto::{self, EnvelopePurpose, SyncMasterKey},
    sync_store::{
        ClaimedOperation, PreparedAttachment, PreparedRemoteChange, SyncAttachmentSource,
        SyncStore, desktop_supported,
    },
    sync_wire::{
        self, KeyCheckEnvelope, MutationOutcome, MutationResponse, RelayInformation, RemoteItem,
    },
};

const MAX_OUTGOING_OPERATIONS: usize = 100;
const MAX_PULL_PAGES: usize = 20;
const DISCOVERY_TIMEOUT: Duration = Duration::from_millis(1_500);

#[derive(Debug, Clone)]
pub struct PairRelayParameters {
    pub host_address: String,
    pub port: u16,
    pub certificate_sha256: String,
    pub authentication_token: String,
    pub sync_password: String,
    pub expected_vault_id: Option<String>,
    pub fingerprint_confirmed: bool,
}

impl Drop for PairRelayParameters {
    fn drop(&mut self) {
        self.authentication_token.zeroize();
        self.sync_password.zeroize();
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncConnectionStatus {
    pub configured: bool,
    pub unlocked: bool,
    pub requires_password: bool,
    pub protection: Option<CredentialProtection>,
    pub host_address: Option<String>,
    pub port: Option<u16>,
    pub vault_id: Option<String>,
    pub certificate_sha256: Option<String>,
    pub pending_count: i64,
    pub running_count: i64,
    pub retry_count: i64,
    pub failed_count: i64,
    pub server_revision: Option<i64>,
    pub last_attempt_at_epoch_millis: Option<i64>,
    pub last_success_at_epoch_millis: Option<i64>,
}

#[derive(Debug, Clone, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SyncRunReport {
    pub uploaded_items: usize,
    pub uploaded_attachments: usize,
    pub pulled_changes: usize,
    pub conflict_copies: usize,
    pub deferred_items: usize,
    pub deleted_attachments: usize,
}

#[derive(Clone)]
pub struct LanSyncService {
    inner: Arc<LanSyncInner>,
}

struct LanSyncInner {
    store: SyncStore,
    credentials: Arc<SyncCredentialStore>,
    attachment_crypto: AttachmentCrypto,
    transfer_root: PathBuf,
    run_lock: Mutex<()>,
}

impl LanSyncService {
    pub fn new(
        store: SyncStore,
        credentials: Arc<SyncCredentialStore>,
        attachment_crypto: AttachmentCrypto,
        app_data_directory: &Path,
    ) -> Result<Self, AppError> {
        let transfer_root = app_data_directory.join("sync-transfers");
        fs::create_dir_all(&transfer_root)?;
        harden_directory(&transfer_root)?;
        Ok(Self {
            inner: Arc::new(LanSyncInner {
                store,
                credentials,
                attachment_crypto,
                transfer_root,
                run_lock: Mutex::new(()),
            }),
        })
    }

    pub fn status(&self) -> Result<SyncConnectionStatus, AppError> {
        let public = self.inner.credentials.public_config()?;
        let unlocked = self.inner.credentials.active().is_ok();
        let counts = self.inner.store.queue_counts()?;
        let state = self.inner.store.state()?;
        Ok(SyncConnectionStatus {
            configured: public.is_some(),
            unlocked,
            requires_password: public.as_ref().is_some_and(|value| {
                value.protection == CredentialProtection::SyncPassword && !unlocked
            }),
            protection: public.as_ref().map(|value| value.protection),
            host_address: public.as_ref().map(|value| value.host_address.clone()),
            port: public.as_ref().map(|value| value.port),
            vault_id: public.as_ref().map(|value| value.vault_id.clone()),
            certificate_sha256: public
                .as_ref()
                .map(|value| value.certificate_sha256.clone()),
            pending_count: counts.pending,
            running_count: counts.running,
            retry_count: counts.retrying,
            failed_count: counts.failed,
            server_revision: state.server_revision,
            last_attempt_at_epoch_millis: state.last_attempt_at,
            last_success_at_epoch_millis: state.last_success_at,
        })
    }

    pub fn unlock_sync(&self, password: &str) -> Result<SyncConnectionStatus, AppError> {
        self.inner.credentials.unlock_with_sync_password(password)?;
        self.status()
    }

    pub fn disconnect(&self) -> Result<SyncConnectionStatus, AppError> {
        self.inner.store.disconnect()?;
        self.inner.credentials.disconnect()?;
        self.status()
    }

    pub fn authentication_token_for(
        &self,
        vault_id: &str,
        certificate_sha256: &str,
    ) -> Result<Zeroizing<String>, AppError> {
        let secrets = self.inner.credentials.active()?;
        if secrets.public.vault_id != vault_id
            || secrets.public.certificate_sha256 != certificate_sha256
        {
            return Err(AppError::RelayIdentity);
        }
        Ok(Zeroizing::new(secrets.authentication_token.to_string()))
    }

    pub fn nearby_pairing_secret_for(
        &self,
        vault_id: &str,
        certificate_sha256: &str,
    ) -> Result<NearbyPairingSecret, AppError> {
        let secrets = self.inner.credentials.active()?;
        if secrets.public.vault_id != vault_id
            || secrets.public.certificate_sha256 != certificate_sha256
        {
            return Err(AppError::RelayIdentity);
        }
        Ok(NearbyPairingSecret {
            authentication_token: Zeroizing::new(secrets.authentication_token.to_string()),
            master_key: Zeroizing::new(*secrets.master_key.as_bytes()),
        })
    }

    pub async fn discover(&self) -> Result<Vec<DiscoveredRelay>, AppError> {
        tokio::task::spawn_blocking(|| lan_discovery::discover_relays(Duration::from_secs(3)))
            .await
            .map_err(|_| AppError::NetworkUnavailable)?
    }

    pub async fn pair(
        &self,
        parameters: PairRelayParameters,
    ) -> Result<SyncConnectionStatus, AppError> {
        if !parameters.fingerprint_confirmed {
            return Err(AppError::InvalidInput {
                field: "relay_fingerprint",
                reason: "the relay fingerprint must be confirmed".to_owned(),
            });
        }
        let client = RelayClient::new(ProvisionalRelayAccess {
            host_address: &parameters.host_address,
            port: parameters.port,
            certificate_sha256: &parameters.certificate_sha256,
            authentication_token: &parameters.authentication_token,
        })?;
        let information = client.relay_information().await?;
        validate_information(
            &information,
            &parameters.certificate_sha256,
            parameters.expected_vault_id.as_deref(),
        )?;
        let master_key = SyncMasterKey::derive(
            &parameters.sync_password,
            &information.key_derivation.salt,
            information.key_derivation.iterations,
        )?;
        verify_or_initialize_key_check(&client, &master_key, &information.vault_id).await?;

        let previous = self.inner.credentials.public_config()?;
        let public = RelayPublicConfig {
            host_address: parameters.host_address.clone(),
            port: parameters.port,
            dns_name: information.tls_identity.dns_name,
            vault_id: information.vault_id,
            certificate_sha256: information.tls_identity.certificate_sha256,
            kdf_algorithm: information.key_derivation.algorithm,
            kdf_iterations: information.key_derivation.iterations,
            kdf_salt: information.key_derivation.salt,
            kdf_key_bits: information.key_derivation.key_bits,
            protection: self.inner.credentials.preferred_protection(),
        };
        let same_remote = previous.as_ref().is_some_and(|value| {
            value.vault_id == public.vault_id
                && value.certificate_sha256 == public.certificate_sha256
        });
        let now = now_epoch_millis()?;
        if same_remote {
            self.inner.store.resume_failed_operations(now)?;
        } else {
            self.inner.store.prepare_new_remote(now)?;
            remove_transfer_directory(&self.inner.transfer_root)?;
        }
        self.inner.credentials.save_pairing(
            public,
            &parameters.authentication_token,
            master_key,
        )?;
        self.status()
    }

    pub async fn run_once(&self) -> Result<SyncRunReport, AppError> {
        let _guard = self.inner.run_lock.lock().await;
        let secrets = self.inner.credentials.active()?;
        let client = self.connected_client(&secrets).await?;
        let now = now_epoch_millis()?;
        self.inner.store.mark_attempt(now)?;
        let mut report = SyncRunReport::default();
        self.pull_pages(&client, &secrets, &mut report).await?;
        self.push_operations(&client, &secrets, &mut report).await?;
        self.pull_pages(&client, &secrets, &mut report).await?;
        self.delete_attachment_tombstones(&client, &mut report)
            .await?;
        Ok(report)
    }

    async fn connected_client(&self, secrets: &Arc<RelaySecrets>) -> Result<RelayClient, AppError> {
        let configured = RelayClient::new(ProvisionalRelayAccess {
            host_address: &secrets.public.host_address,
            port: secrets.public.port,
            certificate_sha256: &secrets.public.certificate_sha256,
            authentication_token: &secrets.authentication_token,
        })?;
        match configured.relay_information().await {
            Ok(information) => {
                validate_paired_information(&information, &secrets.public)?;
                return Ok(configured);
            }
            Err(AppError::RelayAuthentication) => {
                return Err(AppError::RelayAuthentication);
            }
            Err(AppError::UnsupportedProtocol) => return Err(AppError::UnsupportedProtocol),
            Err(_) => {}
        }

        let vault_id = secrets.public.vault_id.clone();
        let fingerprint = secrets.public.certificate_sha256.clone();
        let discovered = tokio::task::spawn_blocking(move || {
            lan_discovery::discover_matching(&vault_id, &fingerprint, DISCOVERY_TIMEOUT)
        })
        .await
        .map_err(|_| AppError::NetworkUnavailable)??;
        let relay = discovered.ok_or(AppError::NetworkUnavailable)?;
        let relocated = RelayClient::new(ProvisionalRelayAccess {
            host_address: &relay.host_address,
            port: relay.port,
            certificate_sha256: &secrets.public.certificate_sha256,
            authentication_token: &secrets.authentication_token,
        })?;
        let information = relocated.relay_information().await?;
        validate_paired_information(&information, &secrets.public)?;
        self.inner
            .credentials
            .update_endpoint(&relay.host_address, relay.port)?;
        Ok(relocated)
    }

    async fn push_operations(
        &self,
        client: &RelayClient,
        secrets: &Arc<RelaySecrets>,
        report: &mut SyncRunReport,
    ) -> Result<(), AppError> {
        for _ in 0..MAX_OUTGOING_OPERATIONS {
            let now = now_epoch_millis()?;
            let Some(operation) = self.inner.store.claim_next(now)? else {
                break;
            };
            let result = self
                .push_operation(client, secrets, &operation, report)
                .await;
            if let Err(error) = result {
                let permanent = permanent_sync_error(&error);
                let code = sync_error_code(&error);
                self.inner.store.fail_operation(
                    &operation,
                    now_epoch_millis()?,
                    code,
                    permanent,
                )?;
                return Err(error);
            }
        }
        Ok(())
    }

    async fn push_operation(
        &self,
        client: &RelayClient,
        secrets: &Arc<RelaySecrets>,
        operation: &ClaimedOperation,
        report: &mut SyncRunReport,
    ) -> Result<(), AppError> {
        let outgoing = self.inner.store.outgoing_item(&operation.item_id)?;
        if operation.operation_type == "DELETE_ITEM" {
            if !outgoing.deleted {
                return Err(AppError::SyncChangedLocally);
            }
            let response = client
                .delete_item(
                    &operation.operation_id,
                    &operation.item_id,
                    outgoing.expected_version_token.as_deref(),
                )
                .await?;
            return self.finish_mutation(operation, response, report);
        }
        if operation.operation_type != "UPSERT_ITEM" || outgoing.deleted {
            return Err(AppError::CorruptedSync);
        }
        for attachment in &outgoing.attachments {
            let expected_path = format!("/v1/attachments/{}", attachment.id);
            if attachment.remote_path.as_deref() == Some(expected_path.as_str())
                && attachment.upload_status == "UPLOADED"
            {
                continue;
            }
            self.upload_attachment(client, secrets, attachment).await?;
            report.uploaded_attachments = report.uploaded_attachments.saturating_add(1);
        }
        let outgoing = self.inner.store.outgoing_item(&operation.item_id)?;
        if !outgoing.metadata.validate(&operation.item_id)
            || outgoing
                .metadata
                .attachments
                .iter()
                .any(|value| value.remote_path != format!("/v1/attachments/{}", value.id))
        {
            return Err(AppError::CorruptedSync);
        }
        let plaintext = Zeroizing::new(
            serde_json::to_vec(&outgoing.metadata).map_err(|_| AppError::CorruptedSync)?,
        );
        let envelope = stable_item_envelope(
            &self.inner.transfer_root,
            secrets,
            &operation.operation_id,
            &operation.item_id,
            &plaintext,
        )?;
        let checksum = sync_crypto::sha256_bytes(&envelope);
        let encoded = Zeroizing::new(STANDARD.encode(&envelope));
        let response = client
            .upsert_item(
                &operation.operation_id,
                &operation.item_id,
                outgoing.expected_version_token.as_deref(),
                &encoded,
                &checksum,
            )
            .await?;
        self.finish_mutation(operation, response, report)?;
        remove_item_artifact(
            &self.inner.transfer_root,
            &secrets.public.vault_id,
            &operation.operation_id,
        )?;
        Ok(())
    }

    fn finish_mutation(
        &self,
        operation: &ClaimedOperation,
        response: MutationResponse,
        report: &mut SyncRunReport,
    ) -> Result<(), AppError> {
        match response.outcome {
            MutationOutcome::Applied => {
                let server_revision = response.server_revision.ok_or(AppError::CorruptedSync)?;
                let version_token = response.version_token.ok_or(AppError::CorruptedSync)?;
                self.inner.store.complete_operation(
                    operation,
                    server_revision,
                    &version_token,
                    now_epoch_millis()?,
                )?;
                report.uploaded_items = report.uploaded_items.saturating_add(1);
                Ok(())
            }
            MutationOutcome::Conflict => {
                let remote = response.remote.ok_or(AppError::CorruptedSync)?;
                self.inner.store.record_mutation_conflict(
                    operation,
                    remote.server_revision,
                    &remote.version_token,
                    now_epoch_millis()?,
                )?;
                Ok(())
            }
        }
    }

    async fn upload_attachment(
        &self,
        client: &RelayClient,
        secrets: &Arc<RelaySecrets>,
        attachment: &SyncAttachmentSource,
    ) -> Result<(), AppError> {
        self.inner.store.mark_attachment_uploading(&attachment.id)?;
        let result = async {
            let cache = attachment_artifact(
                &self.inner.transfer_root,
                &secrets.public.vault_id,
                &attachment.id,
            );
            let crypto = self.inner.attachment_crypto.clone();
            let source = attachment.clone();
            let secrets_for_crypto = Arc::clone(secrets);
            let cache_for_crypto = cache.clone();
            let (ciphertext_sha256, ciphertext_size) = tokio::task::spawn_blocking(move || {
                prepare_attachment_envelope(
                    &crypto,
                    &secrets_for_crypto,
                    &source,
                    &cache_for_crypto,
                )
            })
            .await
            .map_err(|_| AppError::NetworkUnavailable)??;
            let operation_id = format!("upload_attachment_{}", attachment.id);
            let receipt = client
                .upload_attachment(&operation_id, &attachment.id, &cache, &ciphertext_sha256)
                .await?;
            client
                .verify_attachment(&attachment.id, &ciphertext_sha256, ciphertext_size)
                .await?;
            self.inner
                .store
                .mark_attachment_uploaded(&attachment.id, &receipt.remote_path)?;
            remove_file_if_present(&cache)?;
            Ok(())
        }
        .await;
        if result.is_err() {
            self.inner.store.mark_attachment_retryable(&attachment.id)?;
        }
        result
    }

    async fn pull_pages(
        &self,
        client: &RelayClient,
        secrets: &Arc<RelaySecrets>,
        report: &mut SyncRunReport,
    ) -> Result<(), AppError> {
        for _ in 0..MAX_PULL_PAGES {
            let cursor = self.inner.store.cursor()?;
            let page = client
                .pull_changes(cursor.as_deref(), sync_wire::MAX_CHANGE_PAGE_ITEMS)
                .await?;
            let mut latest_by_item = HashMap::new();
            for remote in page.changes {
                latest_by_item.insert(remote.item_id.clone(), remote);
            }
            let mut latest = latest_by_item.into_values().collect::<Vec<_>>();
            latest.sort_by_key(|value| value.server_revision);
            let mut prepared = Vec::with_capacity(latest.len());
            let mut new_local_files = Vec::new();
            for remote in latest {
                match self
                    .prepare_remote_change(client, secrets, remote, &mut new_local_files)
                    .await
                {
                    Ok(change) => prepared.push(change),
                    Err(error) => {
                        cleanup_local_files(&self.inner.attachment_crypto, &new_local_files);
                        return Err(error);
                    }
                }
            }
            let applied = self.inner.store.apply_remote_page(
                &prepared,
                page.next_cursor.as_deref(),
                now_epoch_millis()?,
            );
            let obsolete = match applied {
                Ok(value) => value,
                Err(error) => {
                    cleanup_local_files(&self.inner.attachment_crypto, &new_local_files);
                    return Err(error);
                }
            };
            for value in obsolete {
                let _ = self
                    .inner
                    .attachment_crypto
                    .remove(&value.encrypted_relative_path, &value.id);
            }
            report.pulled_changes = report.pulled_changes.saturating_add(prepared.len());
            report.conflict_copies = report.conflict_copies.saturating_add(
                prepared
                    .iter()
                    .filter(|value| {
                        matches!(
                            value,
                            PreparedRemoteChange::Upsert {
                                conflict_copy: true,
                                ..
                            }
                        )
                    })
                    .count(),
            );
            report.deferred_items = report.deferred_items.saturating_add(
                prepared
                    .iter()
                    .filter(|value| matches!(value, PreparedRemoteChange::Deferred { .. }))
                    .count(),
            );
            if !page.has_more {
                return Ok(());
            }
        }
        Err(AppError::NetworkUnavailable)
    }

    async fn prepare_remote_change(
        &self,
        client: &RelayClient,
        secrets: &Arc<RelaySecrets>,
        remote: RemoteItem,
        new_local_files: &mut Vec<(String, String)>,
    ) -> Result<PreparedRemoteChange, AppError> {
        let expectation = self.inner.store.remote_expectation(&remote.item_id)?;
        if expectation.server_version_token.as_deref() == Some(remote.version_token.as_str()) {
            return Ok(PreparedRemoteChange::Ignored { remote });
        }
        if remote.deleted {
            return Ok(PreparedRemoteChange::Delete {
                remote,
                expectation,
            });
        }
        let encoded = remote
            .encrypted_payload
            .as_deref()
            .ok_or(AppError::CorruptedSync)?;
        let envelope = Zeroizing::new(
            STANDARD
                .decode(encoded)
                .map_err(|_| AppError::CorruptedSync)?,
        );
        if sync_crypto::sha256_bytes(&envelope)
            != remote
                .ciphertext_sha256
                .as_deref()
                .ok_or(AppError::CorruptedSync)?
        {
            return Err(AppError::CorruptedSync);
        }
        let plaintext = sync_crypto::decrypt_bytes(
            &secrets.master_key,
            &secrets.public.vault_id,
            &remote.item_id,
            EnvelopePurpose::Item,
            &envelope,
        )
        .map_err(|_| AppError::CorruptedSync)?;
        let metadata: sync_wire::ItemMetadata =
            serde_json::from_slice(&plaintext).map_err(|_| AppError::CorruptedSync)?;
        if metadata.schema_version != sync_wire::ITEM_SCHEMA_VERSION
            || !metadata.validate(&remote.item_id)
            || !desktop_supported(&metadata)
        {
            return Ok(PreparedRemoteChange::Deferred { remote });
        }
        let conflict_copy = expectation.local_revision.is_some() && expectation.has_local_changes;
        let local_item_id = if conflict_copy {
            stable_conflict_id("item", &remote.item_id, &remote.version_token)
        } else {
            remote.item_id.clone()
        };
        let mut attachments = Vec::with_capacity(metadata.attachments.len());
        for attachment in &metadata.attachments {
            let existing = expectation
                .attachments
                .iter()
                .find(|value| value.id == attachment.id);
            if !conflict_copy && let Some(existing) = existing {
                if existing.sha256 != attachment.plaintext_sha256 {
                    return Err(AppError::CorruptedSync);
                }
                if self
                    .inner
                    .attachment_crypto
                    .contains(&existing.encrypted_relative_path, &existing.id)
                {
                    attachments.push(PreparedAttachment {
                        local_id: existing.id.clone(),
                        encrypted_relative_path: existing.encrypted_relative_path.clone(),
                        reused: true,
                        remote: attachment.clone(),
                    });
                    continue;
                }
            }
            let local_id = if conflict_copy {
                stable_conflict_id("attachment", &attachment.id, &remote.version_token)
            } else {
                attachment.id.clone()
            };
            self.inner.attachment_crypto.remove_orphan(&local_id)?;
            let cached = incoming_attachment_artifact(
                &self.inner.transfer_root,
                &secrets.public.vault_id,
                &attachment.id,
                &remote.version_token,
            );
            client.download_attachment(&attachment.id, &cached).await?;
            let crypto = self.inner.attachment_crypto.clone();
            let secrets_for_crypto = Arc::clone(secrets);
            let cached_for_crypto = cached.clone();
            let remote_attachment = attachment.clone();
            let local_id_for_crypto = local_id.clone();
            let encrypted = tokio::task::spawn_blocking(move || {
                crypto.import_remote_from_producer(
                    &local_id_for_crypto,
                    &remote_attachment.original_filename,
                    &remote_attachment.mime_type,
                    u64::try_from(remote_attachment.file_size_bytes)
                        .map_err(|_| AppError::CorruptedSync)?,
                    &remote_attachment.plaintext_sha256,
                    |destination| {
                        sync_crypto::decrypt_file_verified_to(
                            &secrets_for_crypto.master_key,
                            &secrets_for_crypto.public.vault_id,
                            &remote_attachment.id,
                            &cached_for_crypto,
                            u64::try_from(remote_attachment.file_size_bytes)
                                .map_err(|_| AppError::CorruptedSync)?,
                            destination,
                        )
                        .map(|_| ())
                        .map_err(|_| AppError::CorruptedSync)
                    },
                )
            })
            .await
            .map_err(|_| AppError::NetworkUnavailable)??;
            remove_file_if_present(&cached)?;
            new_local_files.push((local_id.clone(), encrypted.relative_path.clone()));
            attachments.push(PreparedAttachment {
                local_id,
                encrypted_relative_path: encrypted.relative_path,
                reused: false,
                remote: attachment.clone(),
            });
        }
        Ok(PreparedRemoteChange::Upsert {
            remote,
            expectation,
            metadata: Box::new(metadata),
            local_item_id,
            conflict_copy,
            attachments,
        })
    }

    async fn delete_attachment_tombstones(
        &self,
        client: &RelayClient,
        report: &mut SyncRunReport,
    ) -> Result<(), AppError> {
        for _ in 0..MAX_OUTGOING_OPERATIONS {
            let now = now_epoch_millis()?;
            let Some(tombstone) = self.inner.store.next_attachment_tombstone(now)? else {
                break;
            };
            match client
                .delete_attachment(&tombstone.operation_id, &tombstone.attachment_id)
                .await
            {
                Ok(()) => {
                    self.inner
                        .store
                        .complete_attachment_tombstone(&tombstone.attachment_id)?;
                    report.deleted_attachments = report.deleted_attachments.saturating_add(1);
                }
                Err(error) => {
                    self.inner.store.fail_attachment_tombstone(
                        &tombstone.attachment_id,
                        tombstone.attempt_count,
                        now,
                        sync_error_code(&error),
                        permanent_sync_error(&error),
                    )?;
                    return Err(error);
                }
            }
        }
        Ok(())
    }
}

async fn verify_or_initialize_key_check(
    client: &RelayClient,
    master_key: &SyncMasterKey,
    vault_id: &str,
) -> Result<(), AppError> {
    match client.key_check().await? {
        KeyCheckResult::Present(key_check) => verify_key_check(master_key, vault_id, &key_check),
        KeyCheckResult::Missing => {
            let envelope = Zeroizing::new(sync_crypto::encrypt_bytes(
                master_key,
                vault_id,
                sync_crypto::KEY_CHECK_OBJECT_ID,
                EnvelopePurpose::KeyCheck,
                sync_crypto::KEY_CHECK_PLAINTEXT,
            )?);
            let key_check = KeyCheckEnvelope {
                encrypted_key_check: STANDARD.encode(&envelope),
                ciphertext_sha256: sync_crypto::sha256_bytes(&envelope),
            };
            match client.initialize_key_check(&key_check).await {
                Ok(()) => Ok(()),
                Err(_) => match client.key_check().await? {
                    KeyCheckResult::Present(current) => {
                        verify_key_check(master_key, vault_id, &current)
                    }
                    KeyCheckResult::Missing => Err(AppError::NetworkUnavailable),
                },
            }
        }
    }
}

fn verify_key_check(
    master_key: &SyncMasterKey,
    vault_id: &str,
    key_check: &KeyCheckEnvelope,
) -> Result<(), AppError> {
    let envelope = Zeroizing::new(
        STANDARD
            .decode(&key_check.encrypted_key_check)
            .map_err(|_| AppError::CorruptedSync)?,
    );
    if sync_crypto::sha256_bytes(&envelope) != key_check.ciphertext_sha256 {
        return Err(AppError::CorruptedSync);
    }
    let plaintext = sync_crypto::decrypt_bytes(
        master_key,
        vault_id,
        sync_crypto::KEY_CHECK_OBJECT_ID,
        EnvelopePurpose::KeyCheck,
        &envelope,
    )
    .map_err(|_| AppError::InvalidCredentials)?;
    if !bool::from(plaintext.as_slice().ct_eq(sync_crypto::KEY_CHECK_PLAINTEXT)) {
        return Err(AppError::InvalidCredentials);
    }
    Ok(())
}

fn validate_information(
    information: &RelayInformation,
    confirmed_fingerprint: &str,
    expected_vault_id: Option<&str>,
) -> Result<(), AppError> {
    if information.protocol_version != sync_wire::PROTOCOL_VERSION
        || information.minimum_client_protocol_version > sync_wire::PROTOCOL_VERSION
    {
        return Err(AppError::UnsupportedProtocol);
    }
    if information.tls_identity.certificate_sha256 != confirmed_fingerprint
        || expected_vault_id.is_some_and(|value| value != information.vault_id)
        || !sync_wire::valid_id(&information.vault_id)
        || information.tls_identity.dns_name.is_empty()
        || information.discovery.service_type != lan_discovery::SERVICE_TYPE
        || information.key_derivation.algorithm != "PBKDF2-HMAC-SHA256"
        || information.key_derivation.iterations != 600_000
        || information.key_derivation.key_bits != 256
        || information.limits.maximum_item_envelope_bytes < sync_wire::MAX_ITEM_PLAINTEXT_BYTES
        || information.limits.maximum_attachment_envelope_bytes
            < sync_wire::MAX_ATTACHMENT_PLAINTEXT_BYTES
        || information.limits.maximum_change_page_size < sync_wire::MAX_CHANGE_PAGE_ITEMS as u32
    {
        return Err(AppError::RelayIdentity);
    }
    Ok(())
}

fn validate_paired_information(
    information: &RelayInformation,
    public: &RelayPublicConfig,
) -> Result<(), AppError> {
    validate_information(
        information,
        &public.certificate_sha256,
        Some(&public.vault_id),
    )?;
    if information.key_derivation.algorithm != public.kdf_algorithm
        || information.key_derivation.iterations != public.kdf_iterations
        || information.key_derivation.salt != public.kdf_salt
        || information.key_derivation.key_bits != public.kdf_key_bits
    {
        return Err(AppError::RelayIdentity);
    }
    Ok(())
}

fn prepare_attachment_envelope(
    crypto: &AttachmentCrypto,
    secrets: &RelaySecrets,
    attachment: &SyncAttachmentSource,
    cache: &Path,
) -> Result<(String, u64), AppError> {
    if cache.is_file() {
        let valid = (|| {
            let info = sync_crypto::inspect_file(cache)?;
            if info.purpose != EnvelopePurpose::Attachment
                || info.plaintext_length
                    != u64::try_from(attachment.file_size).map_err(|_| AppError::CorruptedSync)?
            {
                return Ok(false);
            }
            let mut digest = DigestWriter::default();
            sync_crypto::decrypt_file_verified_to(
                &secrets.master_key,
                &secrets.public.vault_id,
                &attachment.id,
                cache,
                info.plaintext_length,
                &mut digest,
            )?;
            Ok::<bool, AppError>(digest.finish() == attachment.sha256)
        })()
        .unwrap_or(false);
        if !valid {
            remove_file_if_present(cache)?;
        }
    }
    if !cache.is_file() {
        sync_crypto::encrypt_file_from_producer(
            &secrets.master_key,
            &secrets.public.vault_id,
            &attachment.id,
            u64::try_from(attachment.file_size).map_err(|_| AppError::CorruptedSync)?,
            cache,
            |destination| {
                crypto.write_plaintext_to(
                    &attachment.encrypted_relative_path,
                    &attachment.id,
                    destination,
                )
            },
        )?;
    }
    Ok((sync_crypto::sha256_file(cache)?, cache.metadata()?.len()))
}

fn stable_item_envelope(
    transfer_root: &Path,
    secrets: &RelaySecrets,
    operation_id: &str,
    item_id: &str,
    plaintext: &[u8],
) -> Result<Zeroizing<Vec<u8>>, AppError> {
    let path = item_artifact(transfer_root, &secrets.public.vault_id, operation_id);
    if path.is_file() {
        let cached = Zeroizing::new(fs::read(&path)?);
        let decrypted = sync_crypto::decrypt_bytes(
            &secrets.master_key,
            &secrets.public.vault_id,
            item_id,
            EnvelopePurpose::Item,
            &cached,
        );
        if decrypted
            .as_ref()
            .is_ok_and(|value| bool::from(value.as_slice().ct_eq(plaintext)))
        {
            return Ok(cached);
        }
        remove_file_if_present(&path)?;
    }
    let envelope = Zeroizing::new(sync_crypto::encrypt_bytes(
        &secrets.master_key,
        &secrets.public.vault_id,
        item_id,
        EnvelopePurpose::Item,
        plaintext,
    )?);
    write_private_atomic(&path, &envelope)?;
    Ok(envelope)
}

fn item_artifact(root: &Path, vault_id: &str, operation_id: &str) -> PathBuf {
    root.join("outgoing-items")
        .join(vault_id)
        .join(format!("{operation_id}.bin"))
}

fn attachment_artifact(root: &Path, vault_id: &str, attachment_id: &str) -> PathBuf {
    root.join("outgoing-attachments")
        .join(vault_id)
        .join(format!("{attachment_id}.bin"))
}

fn incoming_attachment_artifact(
    root: &Path,
    vault_id: &str,
    attachment_id: &str,
    version_token: &str,
) -> PathBuf {
    let token_hash = sync_crypto::sha256_bytes(version_token.as_bytes());
    root.join("incoming")
        .join(vault_id)
        .join(format!("{attachment_id}-{}.part", &token_hash[..16]))
}

fn remove_item_artifact(root: &Path, vault_id: &str, operation_id: &str) -> Result<(), AppError> {
    remove_file_if_present(&item_artifact(root, vault_id, operation_id))
}

fn stable_conflict_id(kind: &str, object_id: &str, version_token: &str) -> String {
    let material = format!("{kind}\0{object_id}\0{version_token}");
    let digest = sync_crypto::sha256_bytes(material.as_bytes());
    format!("conflict_{}", &digest[..32])
}

fn cleanup_local_files(crypto: &AttachmentCrypto, files: &[(String, String)]) {
    for (id, path) in files {
        let _ = crypto.remove(path, id);
    }
}

fn remove_file_if_present(path: &Path) -> Result<(), AppError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(AppError::Storage(error)),
    }
}

fn remove_transfer_directory(root: &Path) -> Result<(), AppError> {
    match fs::remove_dir_all(root) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(error) => return Err(AppError::Storage(error)),
    }
    fs::create_dir_all(root)?;
    harden_directory(root)
}

fn write_private_atomic(path: &Path, contents: &[u8]) -> Result<(), AppError> {
    let parent = path.parent().ok_or_else(|| {
        AppError::Storage(io::Error::other("sync transfer directory unavailable"))
    })?;
    fs::create_dir_all(parent)?;
    harden_directory(parent)?;
    let temporary = parent.join(format!(".sync-{}.tmp", Uuid::new_v4().hyphenated()));
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temporary)?;
        file.write_all(contents)?;
        file.flush()?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        harden_file(path)?;
        sync_directory(parent)
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

#[cfg(unix)]
fn harden_directory(path: &Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_directory(_path: &Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(unix)]
fn harden_file(path: &Path) -> Result<(), AppError> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
    Ok(())
}

#[cfg(not(unix))]
fn harden_file(_path: &Path) -> Result<(), AppError> {
    Ok(())
}

#[cfg(unix)]
fn sync_directory(path: &Path) -> Result<(), AppError> {
    File::open(path)?.sync_all()?;
    Ok(())
}

#[cfg(not(unix))]
fn sync_directory(_path: &Path) -> Result<(), AppError> {
    Ok(())
}

fn permanent_sync_error(error: &AppError) -> bool {
    matches!(
        error,
        AppError::RelayAuthentication
            | AppError::RelayIdentity
            | AppError::UnsupportedProtocol
            | AppError::CorruptedSync
            | AppError::FileTooLarge
            | AppError::UnsupportedFile
            | AppError::InvalidInput { .. }
    )
}

fn sync_error_code(error: &AppError) -> &'static str {
    match error {
        AppError::RelayAuthentication => "AUTHENTICATION_EXPIRED",
        AppError::RelayIdentity => "RELAY_IDENTITY",
        AppError::UnsupportedProtocol => "UNSUPPORTED_PROTOCOL",
        AppError::CorruptedSync | AppError::Cryptography => "CORRUPTED_SYNC",
        AppError::FileTooLarge => "FILE_TOO_LARGE",
        AppError::UnsupportedFile => "UNSUPPORTED_FILE",
        AppError::SyncChangedLocally => "LOCAL_CHANGED",
        AppError::Storage(_) => "STORAGE_UNAVAILABLE",
        _ => "NETWORK_UNAVAILABLE",
    }
}

#[derive(Default)]
struct DigestWriter(Sha256);

impl DigestWriter {
    fn finish(self) -> String {
        let bytes = self.0.finalize();
        let mut output = String::with_capacity(64);
        for byte in bytes {
            use std::fmt::Write as _;
            write!(output, "{byte:02x}").expect("writing to a string cannot fail");
        }
        output
    }
}

impl Write for DigestWriter {
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.0.update(buffer);
        Ok(buffer.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod integration_tests {
    use std::{
        net::{SocketAddr, TcpListener},
        path::Path,
        sync::Arc,
    };

    use axum_server::{Handle, tls_rustls::RustlsConfig};
    use tempfile::tempdir;
    use vaultnote_sync_server::{
        AppState as RelayAppState, RelayConfig, Storage, config as relay_config, initialize_relay,
        router as relay_router,
    };

    use super::*;
    use crate::{
        database::Database,
        models::{AttachmentRecord, VaultAttachment, VaultSection},
        repository::{SqliteVaultRepository, VaultRepository},
        sync_credentials::SyncCredentialStore,
    };

    struct ServerTask(tokio::task::JoinHandle<()>);

    impl Drop for ServerTask {
        fn drop(&mut self) {
            self.0.abort();
        }
    }

    fn local_client(root: &Path) -> (Arc<SqliteVaultRepository>, AttachmentCrypto, LanSyncService) {
        fs::create_dir_all(root).expect("client directory should exist");
        let database =
            Database::open_unencrypted(&root.join("vaultnote.db")).expect("database should open");
        let repository = Arc::new(SqliteVaultRepository::new(database.clone()));
        let attachments =
            AttachmentCrypto::new_unencrypted(root).expect("attachment storage should open");
        let credentials =
            Arc::new(SyncCredentialStore::new(root, None).expect("credential storage should open"));
        let sync = LanSyncService::new(
            SyncStore::new(database),
            credentials,
            attachments.clone(),
            root,
        )
        .expect("sync service should initialize");
        (repository, attachments, sync)
    }

    async fn pair_client(
        sync: &LanSyncService,
        address: SocketAddr,
        config: &RelayConfig,
        token: &str,
    ) {
        sync.pair(PairRelayParameters {
            host_address: "127.0.0.1".to_owned(),
            port: address.port(),
            certificate_sha256: config.tls.certificate_sha256.clone(),
            authentication_token: token.to_owned(),
            sync_password: "shared sync password".to_owned(),
            expected_vault_id: Some(config.vault_id.clone()),
            fingerprint_confirmed: true,
        })
        .await
        .expect("client should pair with relay");
    }

    #[tokio::test]
    async fn two_clients_progressively_sync_note_and_attachment_through_real_tls_relay() {
        let root = tempdir().expect("temporary root should exist");
        let relay_root = root.path().join("relay");
        let initialized = initialize_relay(&relay_root).expect("relay should initialize");
        let token = initialized.authentication_token.to_string();
        let config = initialized.config;
        let storage = Storage::open(&relay_root, &config).expect("relay storage should open");
        let relay_state =
            RelayAppState::new(config.clone(), storage).expect("relay state should initialize");
        let tls = RustlsConfig::from_pem_file(
            relay_config::tls_certificate_path(&relay_root),
            relay_config::tls_private_key_path(&relay_root),
        )
        .await
        .expect("relay TLS should load");
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("loopback listener should bind");
        listener
            .set_nonblocking(true)
            .expect("loopback listener should be nonblocking");
        let address = listener
            .local_addr()
            .expect("listener address should exist");
        let handle: Handle<SocketAddr> = Handle::new();
        let server = axum_server::from_tcp_rustls(listener, tls)
            .expect("TLS server should configure")
            .handle(handle.clone())
            .serve(relay_router(relay_state).into_make_service());
        let _server_task = ServerTask(tokio::spawn(async move {
            server.await.expect("relay should serve");
        }));
        handle
            .listening()
            .await
            .expect("relay should start listening");

        let (first_repository, first_files, first_sync) = local_client(&root.path().join("first"));
        let item_id = first_repository
            .create_note("Progressive sync", "Created on desktop one", 1_000)
            .expect("note should be created");
        let attachment_id = Uuid::new_v4().hyphenated().to_string();
        let attachment_plaintext = b"cross-client attachment";
        let stored = first_files
            .encrypt_restored(
                attachment_plaintext,
                "proof.pdf",
                "application/pdf",
                &attachment_id,
            )
            .expect("attachment should be stored");
        first_repository
            .add_attachment(
                &AttachmentRecord {
                    attachment: VaultAttachment {
                        id: attachment_id,
                        parent_item_id: item_id.clone(),
                        display_name: stored.display_name,
                        mime_type: stored.mime_type,
                        file_size: stored.plaintext_size,
                        sha256: stored.sha256,
                        created_at_epoch_millis: 1_001,
                    },
                    encrypted_relative_path: stored.relative_path,
                },
                1_001,
            )
            .expect("attachment metadata should commit");
        pair_client(&first_sync, address, &config, &token).await;
        let first_report = first_sync.run_once().await.expect("first sync should run");
        assert_eq!(first_report.uploaded_items, 1);
        assert_eq!(first_report.uploaded_attachments, 1);

        let (second_repository, second_files, second_sync) =
            local_client(&root.path().join("second"));
        pair_client(&second_sync, address, &config, &token).await;
        let second_report = second_sync
            .run_once()
            .await
            .expect("second sync should run");
        assert_eq!(second_report.pulled_changes, 1);
        let received = second_repository
            .list_items(VaultSection::Active, 100)
            .expect("received notes should list");
        assert_eq!(received.len(), 1);
        assert_eq!(received[0].id, item_id);
        assert_eq!(received[0].title, "Progressive sync");
        let attachment = second_repository
            .attachment_record(
                &second_repository
                    .list_attachments(&item_id)
                    .expect("received attachments should list")[0]
                    .id,
            )
            .expect("received attachment should load");
        assert_eq!(
            second_files
                .decrypt_bytes(
                    &attachment.encrypted_relative_path,
                    &attachment.attachment.id,
                )
                .expect("received attachment should authenticate")
                .as_slice(),
            attachment_plaintext
        );
    }
}
