package com.vaultnote.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaultnote.core.update.AppUpdateCheckResult
import com.vaultnote.core.update.AppUpdateDownloadResult
import com.vaultnote.core.update.AppUpdateFailure
import com.vaultnote.core.update.AppUpdateIncompatibility
import com.vaultnote.core.update.AppUpdateRelease
import com.vaultnote.core.update.AppUpdateRepository
import com.vaultnote.core.update.AppUpdateScheduleResult
import com.vaultnote.core.update.AppUpdateScheduler
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal sealed interface AppUpdateUiStatus {
    data object Idle : AppUpdateUiStatus
    data object Checking : AppUpdateUiStatus
    data object UpToDate : AppUpdateUiStatus
    data class Available(val versionName: String) : AppUpdateUiStatus
    data class Incompatible(val reason: AppUpdateIncompatibility) : AppUpdateUiStatus
    data class Downloading(val percent: Int) : AppUpdateUiStatus
    data class Failed(val failure: AppUpdateFailure, val retryable: Boolean) : AppUpdateUiStatus
}

internal data class AppUpdateSettingsState(
    val automaticChecksEnabled: Boolean,
    val availableUpdate: AppUpdateRelease?,
    val status: AppUpdateUiStatus,
)

internal sealed interface AppUpdateSettingsEvent {
    data class ApkReady(val file: File) : AppUpdateSettingsEvent
}

internal class AppUpdateViewModel(
    private val repository: AppUpdateRepository,
    private val scheduler: AppUpdateScheduler,
) : ViewModel() {
    private val cached = repository.cachedAvailableUpdate()
    private val mutableState = MutableStateFlow(
        AppUpdateSettingsState(
            automaticChecksEnabled = repository.automaticChecksEnabled(),
            availableUpdate = cached,
            status = cached?.let { AppUpdateUiStatus.Available(it.versionName) }
                ?: AppUpdateUiStatus.Idle,
        ),
    )
    private val mutableEvents = Channel<AppUpdateSettingsEvent>(Channel.BUFFERED)
    private var operation: Job? = null

    val state: StateFlow<AppUpdateSettingsState> = mutableState.asStateFlow()
    val events: Flow<AppUpdateSettingsEvent> = mutableEvents.receiveAsFlow()

    init {
        if (
            mutableState.value.automaticChecksEnabled &&
            scheduler.setAutomaticChecksEnabled(true) == AppUpdateScheduleResult.REJECTED
        ) {
            repository.setAutomaticChecksEnabled(false)
            mutableState.value = mutableState.value.copy(
                automaticChecksEnabled = false,
                status = AppUpdateUiStatus.Failed(
                    AppUpdateFailure.BACKGROUND_SCHEDULING,
                    retryable = true,
                ),
            )
        }
    }

    fun setAutomaticChecksEnabled(enabled: Boolean) {
        if (mutableState.value.automaticChecksEnabled == enabled) return
        val scheduleResult = scheduler.setAutomaticChecksEnabled(enabled)
        if (enabled && scheduleResult == AppUpdateScheduleResult.REJECTED) {
            repository.setAutomaticChecksEnabled(false)
            mutableState.value = mutableState.value.copy(
                automaticChecksEnabled = false,
                status = AppUpdateUiStatus.Failed(
                    AppUpdateFailure.BACKGROUND_SCHEDULING,
                    retryable = true,
                ),
            )
            return
        }
        repository.setAutomaticChecksEnabled(enabled)
        mutableState.value = mutableState.value.copy(automaticChecksEnabled = enabled)
        if (enabled) checkForUpdate()
    }

    fun checkForUpdate() {
        if (operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(status = AppUpdateUiStatus.Checking)
        operation = viewModelScope.launch {
            try {
                mutableState.value = when (val result = repository.checkForUpdate()) {
                    is AppUpdateCheckResult.Available -> AppUpdateSettingsState(
                        automaticChecksEnabled = mutableState.value.automaticChecksEnabled,
                        availableUpdate = result.release,
                        status = AppUpdateUiStatus.Available(result.release.versionName),
                    )
                    AppUpdateCheckResult.UpToDate -> mutableState.value.copy(
                        availableUpdate = null,
                        status = AppUpdateUiStatus.UpToDate,
                    )
                    is AppUpdateCheckResult.Incompatible -> mutableState.value.copy(
                        availableUpdate = null,
                        status = AppUpdateUiStatus.Incompatible(result.reason),
                    )
                    is AppUpdateCheckResult.Failed -> mutableState.value.copy(
                        status = AppUpdateUiStatus.Failed(result.failure, result.retryable),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    fun prepareInstall() {
        if (operation?.isActive == true) return
        val release = mutableState.value.availableUpdate ?: return
        mutableState.value = mutableState.value.copy(status = AppUpdateUiStatus.Downloading(0))
        operation = viewModelScope.launch {
            try {
                var lastPercent = -1
                when (
                    val result = repository.downloadUpdate(release) { downloaded, total ->
                        val percent = if (total <= 0L) 0 else ((downloaded * 100L) / total)
                            .coerceIn(0L, 100L)
                            .toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mutableState.value = mutableState.value.copy(
                                status = AppUpdateUiStatus.Downloading(percent),
                            )
                        }
                    }
                ) {
                    is AppUpdateDownloadResult.Ready -> {
                        mutableState.value = mutableState.value.copy(
                            status = AppUpdateUiStatus.Available(result.release.versionName),
                        )
                        mutableEvents.send(AppUpdateSettingsEvent.ApkReady(result.apk))
                    }
                    is AppUpdateDownloadResult.Failed -> {
                        mutableState.value = mutableState.value.copy(
                            status = AppUpdateUiStatus.Failed(result.failure, result.retryable),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    class Factory(
        private val repository: AppUpdateRepository,
        private val scheduler: AppUpdateScheduler,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppUpdateViewModel::class.java))
            return AppUpdateViewModel(repository, scheduler) as T
        }
    }
}
