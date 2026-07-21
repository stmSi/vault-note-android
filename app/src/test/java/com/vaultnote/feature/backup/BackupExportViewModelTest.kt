package com.vaultnote.feature.backup

import android.net.Uri
import com.vaultnote.core.backup.BackupRepository
import com.vaultnote.core.backup.BackupSummary
import com.vaultnote.core.backup.PreparedBackupExport
import com.vaultnote.core.backup.PreparedBackupRestore
import com.vaultnote.core.backup.RestoreSummary
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.security.LockPolicy
import com.vaultnote.core.security.VaultLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupExportViewModelTest {
    @get:Rule
    val mainDispatcherRule = BackupMainDispatcherRule()

    @Test
    fun `selected destination waits for unlock before exporting`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeBackupRepository()
            val lockManager = VaultLockManager().apply {
                applyPolicy(LockPolicy(true, 0L, true))
                unlock()
            }
            val viewModel = BackupExportViewModel(repository, lockManager)
            val chooseDestination = async { viewModel.events.first() }

            viewModel.requestExport(PASSWORD.toCharArray(), PASSWORD.toCharArray())
            runCurrent()
            assertEquals(BackupExportEvent.ChooseDestination, chooseDestination.await())

            lockManager.lockNow()
            viewModel.completeDestination(DESTINATION)
            runCurrent()

            assertTrue(viewModel.state.value.isExporting)
            assertEquals(0, repository.exportCalls)

            lockManager.unlock()
            runCurrent()

            assertEquals(1, repository.exportCalls)
            assertFalse(viewModel.state.value.isExporting)
        }

    private class FakeBackupRepository : BackupRepository {
        var exportCalls: Int = 0

        override fun prepareExport(password: CharArray): RepositoryResult<PreparedBackupExport> {
            val retainedPassword = password.copyOf()
            password.fill('\u0000')
            return RepositoryResult.Success(PreparedBackupExport(retainedPassword))
        }

        override suspend fun export(
            prepared: PreparedBackupExport,
            destination: Uri,
        ): RepositoryResult<BackupSummary> {
            exportCalls += 1
            prepared.clear()
            return RepositoryResult.Success(BackupSummary(1L, 0L, 1L))
        }

        override fun cancelExport(prepared: PreparedBackupExport) {
            prepared.clear()
        }

        override suspend fun prepareRestore(
            source: Uri,
            password: CharArray,
        ): RepositoryResult<PreparedBackupRestore> {
            password.fill('\u0000')
            return RepositoryResult.Failure(
                AppError.InvalidInput("backup", "Restore is not used by this test"),
            )
        }

        override suspend fun commitRestore(
            prepared: PreparedBackupRestore,
        ): RepositoryResult<RestoreSummary> = RepositoryResult.Failure(
            AppError.InvalidInput("backup", "Restore is not used by this test"),
        )

        override fun cancelRestore(prepared: PreparedBackupRestore) {
            prepared.stagingDirectory.deleteRecursively()
        }

        override suspend fun discardDestination(destination: Uri) = Unit
    }

    private companion object {
        const val PASSWORD = "correct horse battery staple"
        val DESTINATION: Uri = Uri.parse("content://vaultnote.test/backup.vnb")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupMainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
