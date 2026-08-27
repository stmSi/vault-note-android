package com.vaultnote.feature.settings

import com.vaultnote.core.update.AppUpdateChannel
import com.vaultnote.core.update.AppUpdateCheckResult
import com.vaultnote.core.update.AppUpdateDownloadResult
import com.vaultnote.core.update.AppUpdateFailure
import com.vaultnote.core.update.AppUpdateRelease
import com.vaultnote.core.update.AppUpdateRepository
import com.vaultnote.core.update.AppUpdateScheduleResult
import com.vaultnote.core.update.AppUpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rejected background scheduling does not leave automatic checks enabled`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeUpdateRepository()
        val viewModel = AppUpdateViewModel(
            repository,
            FakeUpdateScheduler(AppUpdateScheduleResult.REJECTED),
        )

        viewModel.setAutomaticChecksEnabled(true)

        assertFalse(repository.automaticChecksEnabled())
        assertFalse(viewModel.state.value.automaticChecksEnabled)
        assertNull(viewModel.state.value.availableUpdate)
        assertEquals(
            AppUpdateUiStatus.Failed(
                AppUpdateFailure.BACKGROUND_SCHEDULING,
                retryable = true,
            ),
            viewModel.state.value.status,
        )
    }

    @Test
    fun `manual check exposes a compatible release for installation`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = FakeUpdateRepository(
            checkResult = AppUpdateCheckResult.Available(RELEASE),
        )
        val viewModel = AppUpdateViewModel(
            repository,
            FakeUpdateScheduler(AppUpdateScheduleResult.SCHEDULED),
        )

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(RELEASE, viewModel.state.value.availableUpdate)
        assertEquals(AppUpdateUiStatus.Available("0.0.4"), viewModel.state.value.status)
    }

    private class FakeUpdateRepository(
        private var automatic: Boolean = false,
        private val checkResult: AppUpdateCheckResult = AppUpdateCheckResult.UpToDate,
    ) : AppUpdateRepository {
        override fun automaticChecksEnabled(): Boolean = automatic

        override fun setAutomaticChecksEnabled(enabled: Boolean) {
            automatic = enabled
        }

        override fun cachedAvailableUpdate(): AppUpdateRelease? = null

        override fun shouldNotify(release: AppUpdateRelease): Boolean = true

        override fun markNotified(release: AppUpdateRelease) = Unit

        override suspend fun checkForUpdate(): AppUpdateCheckResult = checkResult

        override suspend fun downloadUpdate(
            release: AppUpdateRelease,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): AppUpdateDownloadResult = AppUpdateDownloadResult.Failed(
            AppUpdateFailure.INVALID_APK,
            retryable = false,
        )
    }

    private class FakeUpdateScheduler(
        private val result: AppUpdateScheduleResult,
    ) : AppUpdateScheduler {
        override fun setAutomaticChecksEnabled(enabled: Boolean): AppUpdateScheduleResult = result
    }

    private companion object {
        val RELEASE = AppUpdateRelease(
            tagName = "v0.0.4",
            versionName = "0.0.4",
            versionCode = 100_004,
            packageName = "com.vaultnote",
            channel = AppUpdateChannel.PRODUCTION,
            certificateSha256 = "a".repeat(64),
            releasePageUrl = "https://github.com/stmSi/vault-note-android/releases/tag/v0.0.4",
            apkUrl = "https://github.com/stmSi/vault-note-android/releases/download/v0.0.4/" +
                "VaultNote-Android.apk",
            apkName = "VaultNote-Android.apk",
            apkSizeBytes = 50_000_000,
            apkSha256 = "b".repeat(64),
        )
    }
}
