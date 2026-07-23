package com.vaultnote.core.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vaultnote.core.common.Clock
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.database.VaultDatabase
import com.vaultnote.core.database.entity.AppSettingEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLockPolicyRepositoryTest {
    private lateinit var database: VaultDatabase
    private lateinit var repository: LockPolicyRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        repository = RoomLockPolicyRepository(database.appSettingDao(), TestDispatchers, Clock { 42L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `missing policy uses documented default`() = runBlocking {
        assertEquals(LockPolicy.DEFAULT, repository.observe().first().successValue())
    }

    @Test
    fun `malformed persisted policy fails closed`() = runBlocking {
        database.appSettingDao().insert(
            AppSettingEntity("security.lock_policy", "malformed", 1L),
        )

        assertEquals(LockPolicy.FAIL_CLOSED, repository.observe().first().successValue())
    }

    @Test
    fun `saved policy round trips through typed storage`() = runBlocking {
        val policy = LockPolicy(true, 60_000L, false)

        repository.save(policy).successValue()

        assertEquals(policy, repository.observe().first().successValue())
    }

    private fun <T> RepositoryResult<T>.successValue(): T =
        (this as RepositoryResult.Success<T>).value

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }
}
