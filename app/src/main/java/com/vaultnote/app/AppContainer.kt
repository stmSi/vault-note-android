package com.vaultnote.app

import android.content.Context
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.SystemClock
import com.vaultnote.core.common.UuidIdGenerator
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.repository.RoomVaultRepository
import com.vaultnote.core.repository.VaultRepository
import com.vaultnote.core.sync.CoalescingFakeSyncScheduler

interface AppContainer {
    val vaultRepository: VaultRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val applicationContext = context.applicationContext
    private val database: VaultDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VaultDatabase.create(applicationContext)
    }
    private val syncScheduler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoalescingFakeSyncScheduler()
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
}
