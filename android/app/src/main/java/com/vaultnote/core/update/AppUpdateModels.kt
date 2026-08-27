package com.vaultnote.core.update

import java.io.File

enum class AppUpdateChannel(val wireValue: String) {
    DEBUG("debug"),
    PRODUCTION("production"),
}

data class InstalledAppIdentity(
    val packageName: String,
    val channel: AppUpdateChannel,
    val versionCode: Long,
    val certificateSha256: Set<String>,
)

data class AppUpdateRelease(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val channel: AppUpdateChannel,
    val certificateSha256: String,
    val releasePageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
)

enum class AppUpdateIncompatibility {
    PACKAGE,
    CHANNEL,
    SIGNING_CERTIFICATE,
}

enum class AppUpdateFailure {
    NETWORK,
    SERVER,
    BACKGROUND_SCHEDULING,
    INVALID_METADATA,
    INSUFFICIENT_STORAGE,
    CHECKSUM_MISMATCH,
    INVALID_APK,
}

sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult
    data class Available(val release: AppUpdateRelease) : AppUpdateCheckResult
    data class Incompatible(val reason: AppUpdateIncompatibility) : AppUpdateCheckResult
    data class Failed(val failure: AppUpdateFailure, val retryable: Boolean) : AppUpdateCheckResult
}

sealed interface AppUpdateDownloadResult {
    data class Ready(val release: AppUpdateRelease, val apk: File) : AppUpdateDownloadResult
    data class Failed(val failure: AppUpdateFailure, val retryable: Boolean) :
        AppUpdateDownloadResult
}

interface AppUpdateRepository {
    fun automaticChecksEnabled(): Boolean
    fun setAutomaticChecksEnabled(enabled: Boolean)
    fun cachedAvailableUpdate(): AppUpdateRelease?
    fun shouldNotify(release: AppUpdateRelease): Boolean
    fun markNotified(release: AppUpdateRelease)
    suspend fun checkForUpdate(): AppUpdateCheckResult
    suspend fun downloadUpdate(
        release: AppUpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): AppUpdateDownloadResult
}

enum class AppUpdateScheduleResult { SCHEDULED, REJECTED }

interface AppUpdateScheduler {
    fun setAutomaticChecksEnabled(enabled: Boolean): AppUpdateScheduleResult
}
