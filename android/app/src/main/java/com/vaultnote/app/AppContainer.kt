package com.vaultnote.app

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.SystemClock
import com.vaultnote.core.common.UuidIdGenerator
import com.vaultnote.core.backup.AndroidBackupRepository
import com.vaultnote.core.backup.BackupAttachmentReader
import com.vaultnote.core.backup.BackupRepository
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.encryption.AesGcmEncryptionService
import com.vaultnote.core.encryption.AndroidKeystoreKeyProvider
import com.vaultnote.core.encryption.EncryptionService
import com.vaultnote.core.files.AndroidAttachmentFileManager
import com.vaultnote.core.files.AttachmentFileManager
import com.vaultnote.core.files.RestoredAttachmentStore
import com.vaultnote.core.repository.AttachmentRepository
import com.vaultnote.core.repository.RoomVaultRepository
import com.vaultnote.core.repository.RoomAttachmentRepository
import com.vaultnote.core.repository.VaultRepository
import com.vaultnote.core.sync.RoomSyncRepository
import com.vaultnote.core.sync.SyncRepository
import com.vaultnote.core.sync.SyncScheduler
import com.vaultnote.core.sync.WorkManagerSyncScheduler
import com.vaultnote.core.sync.lan.AndroidLanRelayDiscovery
import com.vaultnote.core.sync.lan.AndroidSyncCredentialStore
import com.vaultnote.core.sync.lan.DefaultLanSyncConnectionRepository
import com.vaultnote.core.sync.lan.LanSyncConnectionRepository
import com.vaultnote.core.sync.lan.RelayHttpBackend
import com.vaultnote.core.sync.lan.SyncCredentialStore
import com.vaultnote.core.sync.lan.SyncEnvelopeCrypto
import com.vaultnote.core.update.AppUpdateRepository
import com.vaultnote.core.update.AppUpdateScheduler
import com.vaultnote.core.update.GitHubAppUpdateRepository
import com.vaultnote.core.update.WorkManagerAppUpdateScheduler
import com.vaultnote.feature.viewer.AndroidFileViewer
import com.vaultnote.feature.viewer.AndroidAttachmentExporter
import com.vaultnote.feature.viewer.AttachmentExporter
import com.vaultnote.feature.viewer.FileViewer
import com.vaultnote.core.security.ExternalAttachmentGrantRegistry
import com.vaultnote.core.security.LockPolicyRepository
import com.vaultnote.core.security.RoomLockPolicyRepository
import com.vaultnote.core.security.RoomSecureAttachmentContentSource
import com.vaultnote.core.security.SecureAttachmentContentSource
import com.vaultnote.core.security.SecureAttachmentUriFactory
import com.vaultnote.core.security.VaultLockManager
import com.vaultnote.core.search.RoomSearchRepository
import com.vaultnote.core.search.SearchRepository
import com.vaultnote.core.ocr.AndroidOcrPlaintextStore
import com.vaultnote.core.ocr.MlKitOcrProcessor
import com.vaultnote.core.ocr.OcrRepository
import com.vaultnote.core.ocr.RoomOcrRepository
import com.vaultnote.core.reminder.AndroidReminderScheduler
import com.vaultnote.core.reminder.ReminderScheduler

interface AppContainer {
    val databaseForReminders: VaultDatabase
    val vaultRepository: VaultRepository
    val attachmentRepository: AttachmentRepository
    val attachmentFileManager: AttachmentFileManager
    val imageLoader: ImageLoader
    val fileViewer: FileViewer
    val attachmentExporter: AttachmentExporter
    val backupRepository: BackupRepository
    val lockPolicyRepository: LockPolicyRepository
    val lockManager: VaultLockManager
    val secureAttachmentContentSource: SecureAttachmentContentSource
    val searchRepository: SearchRepository
    val ocrRepository: OcrRepository
    val syncRepository: SyncRepository
    val syncScheduler: SyncScheduler
    val lanSyncConnectionRepository: LanSyncConnectionRepository
    val reminderScheduler: ReminderScheduler
    val appUpdateRepository: AppUpdateRepository
    val appUpdateScheduler: AppUpdateScheduler
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val database: VaultDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultDatabase.create(applicationContext)
    }
    override val databaseForReminders: VaultDatabase
        get() = database
    override val reminderScheduler: ReminderScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidReminderScheduler(applicationContext, database)
    }
    override val syncScheduler: SyncScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WorkManagerSyncScheduler(applicationContext)
    }
    override val appUpdateScheduler: AppUpdateScheduler by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        WorkManagerAppUpdateScheduler(applicationContext)
    }
    override val appUpdateRepository: AppUpdateRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        GitHubAppUpdateRepository(
            context = applicationContext,
            dispatchers = DefaultDispatcherProvider,
        )
    }
    private val syncCredentialStore: SyncCredentialStore by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidSyncCredentialStore(applicationContext)
    }
    private val lanRelayDiscovery by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidLanRelayDiscovery(applicationContext)
    }
    private val syncEnvelopeCrypto by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SyncEnvelopeCrypto(DefaultDispatcherProvider)
    }
    private val encryptionService: EncryptionService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AesGcmEncryptionService(
            keyProvider = AndroidKeystoreKeyProvider(),
            dispatchers = DefaultDispatcherProvider,
        )
    }
    private val relayBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RelayHttpBackend(
            context = applicationContext,
            credentialStore = syncCredentialStore,
            discovery = lanRelayDiscovery,
            envelopeCrypto = syncEnvelopeCrypto,
            deviceEncryption = encryptionService,
            dispatchers = DefaultDispatcherProvider,
        )
    }
    private val secureUris by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureAttachmentUriFactory(applicationContext)
    }
    private val externalGrants by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ExternalAttachmentGrantRegistry()
    }

    override val lockManager: VaultLockManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultLockManager()
    }

    override val attachmentFileManager: AttachmentFileManager by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidAttachmentFileManager(
            context = applicationContext,
            dispatchers = DefaultDispatcherProvider,
            encryptionService = encryptionService,
        )
    }

    override val vaultRepository: VaultRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomVaultRepository(
            database = database,
            syncScheduler = syncScheduler,
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
            idGenerator = UuidIdGenerator,
            reminderScheduler = reminderScheduler,
        )
    }

    override val attachmentRepository: AttachmentRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomAttachmentRepository(
            database = database,
            fileManager = attachmentFileManager,
            syncScheduler = syncScheduler,
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
            idGenerator = UuidIdGenerator,
            secureUris = secureUris,
        )
    }

    override val imageLoader: ImageLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ImageLoader.Builder(applicationContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(applicationContext, percent = 0.08)
                    .build()
            }
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .build()
    }

    override val fileViewer: FileViewer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidFileViewer(secureUris, externalGrants)
    }

    override val attachmentExporter: AttachmentExporter by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidAttachmentExporter(
            contentResolver = applicationContext.contentResolver,
            contentSource = secureAttachmentContentSource,
            externalGrants = externalGrants,
            dispatchers = DefaultDispatcherProvider,
        )
    }

    override val backupRepository: BackupRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidBackupRepository(
            context = applicationContext,
            database = database,
            attachmentReader = BackupAttachmentReader { attachmentId, relativePath, output ->
                attachmentFileManager.decryptStored(
                    attachmentId = attachmentId,
                    purpose = com.vaultnote.core.encryption.EncryptedFilePurpose.ATTACHMENT,
                    relativePath = relativePath,
                    output = output,
                )
            },
            restoredAttachmentStore = RestoredAttachmentStore(
                context = applicationContext,
                encryptionService = encryptionService,
                dispatchers = DefaultDispatcherProvider,
            ),
            lockManager = lockManager,
            syncScheduler = syncScheduler,
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
            idGenerator = UuidIdGenerator,
            reminderScheduler = reminderScheduler,
        )
    }

    override val lockPolicyRepository: LockPolicyRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomLockPolicyRepository(
            settings = database.appSettingDao(),
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
        )
    }

    override val secureAttachmentContentSource: SecureAttachmentContentSource by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        RoomSecureAttachmentContentSource(
            database = database,
            fileManager = attachmentFileManager,
            lockManager = lockManager,
            externalGrants = externalGrants,
            dispatchers = DefaultDispatcherProvider,
        )
    }

    override val searchRepository: SearchRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSearchRepository(
            searchDao = database.searchDao(),
            dispatchers = DefaultDispatcherProvider,
        )
    }

    override val ocrRepository: OcrRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomOcrRepository(
            database = database,
            plaintextStore = AndroidOcrPlaintextStore(
                context = applicationContext,
                fileManager = attachmentFileManager,
                dispatchers = DefaultDispatcherProvider,
                isContentAccessAllowed = lockManager::isContentAccessAllowed,
            ),
            processor = MlKitOcrProcessor(applicationContext),
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
        )
    }

    override val syncRepository: SyncRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RoomSyncRepository(
            database = database,
            syncApi = relayBackend,
            authProvider = relayBackend,
            remoteFileStore = relayBackend,
            fileManager = attachmentFileManager,
            syncScheduler = syncScheduler,
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
            idGenerator = UuidIdGenerator,
            artifactStore = relayBackend,
        )
    }

    override val lanSyncConnectionRepository: LanSyncConnectionRepository by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        DefaultLanSyncConnectionRepository(
            credentialStore = syncCredentialStore,
            discovery = lanRelayDiscovery,
            backend = relayBackend,
            envelopeCrypto = syncEnvelopeCrypto,
            syncRepository = syncRepository,
        )
    }
}
