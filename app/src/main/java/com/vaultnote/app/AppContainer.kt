package com.vaultnote.app

import android.content.Context
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.SystemClock
import com.vaultnote.core.common.UuidIdGenerator
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.files.AndroidAttachmentFileManager
import com.vaultnote.core.files.AttachmentFileManager
import com.vaultnote.core.repository.AttachmentRepository
import com.vaultnote.core.repository.RoomVaultRepository
import com.vaultnote.core.repository.RoomAttachmentRepository
import com.vaultnote.core.repository.VaultRepository
import com.vaultnote.core.sync.CoalescingFakeSyncScheduler
import com.vaultnote.feature.viewer.AndroidFileViewer
import com.vaultnote.feature.viewer.FileViewer

interface AppContainer {
    val vaultRepository: VaultRepository
    val attachmentRepository: AttachmentRepository
    val attachmentFileManager: AttachmentFileManager
    val imageLoader: ImageLoader
    val fileViewer: FileViewer
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val database: VaultDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultDatabase.create(applicationContext)
    }
    private val syncScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoalescingFakeSyncScheduler()
    }

    override val attachmentFileManager: AttachmentFileManager by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidAttachmentFileManager(
            context = applicationContext,
            dispatchers = DefaultDispatcherProvider,
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
        AndroidFileViewer()
    }
}
