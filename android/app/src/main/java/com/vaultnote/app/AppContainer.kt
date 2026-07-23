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
import com.vaultnote.core.sync.InMemoryFakeSyncBackend
import com.vaultnote.core.sync.RoomSyncRepository
import com.vaultnote.core.sync.SyncRepository
import com.vaultnote.core.sync.SyncScheduler
import com.vaultnote.core.sync.WorkManagerSyncScheduler
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

interface AppContainer {
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
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val database: VaultDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultDatabase.create(applicationContext)
    }
    override val syncScheduler: SyncScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WorkManagerSyncScheduler(applicationContext)
    }
    private val fakeSyncBackend by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        InMemoryFakeSyncBackend(DefaultDispatcherProvider)
    }
    private val encryptionService: EncryptionService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AesGcmEncryptionService(
            keyProvider = AndroidKeystoreKeyProvider(),
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
            syncApi = fakeSyncBackend,
            authProvider = fakeSyncBackend,
            remoteFileStore = fakeSyncBackend,
            fileManager = attachmentFileManager,
            syncScheduler = syncScheduler,
            dispatchers = DefaultDispatcherProvider,
            clock = SystemClock,
            idGenerator = UuidIdGenerator,
        )
    }
}
