package com.vaultnote.core.update

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.edit
import com.vaultnote.core.common.DispatcherProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Checks only VaultNote's public GitHub release feed and installs nothing by itself.
 *
 * APK bytes are streamed into private cache, bounded by signed-build metadata, SHA-256 verified,
 * parsed by PackageManager, and matched to this installation's package and signing certificate
 * before a content URI may be handed to Android's package installer.
 */
class GitHubAppUpdateRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
    client: OkHttpClient? = null,
) : AppUpdateRepository {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val verifier = AndroidInstalledAppVerifier(applicationContext)
    private val client = client ?: OkHttpClient.Builder()
        .connectTimeout(15L, TimeUnit.SECONDS)
        .readTimeout(45L, TimeUnit.SECONDS)
        .callTimeout(90L, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val updatesRoot = File(applicationContext.cacheDir, UPDATE_CACHE_DIRECTORY)

    override fun automaticChecksEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTOMATIC_CHECKS, false)

    override fun setAutomaticChecksEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_AUTOMATIC_CHECKS, enabled) }
    }

    override fun cachedAvailableUpdate(): AppUpdateRelease? = runCatching {
        val versionCode = preferences.getLong(KEY_VERSION_CODE, 0L)
        if (versionCode <= verifier.identity.versionCode) return null
        AppUpdateRelease(
            tagName = requireCachedString(KEY_TAG_NAME, MAX_TAG_LENGTH),
            versionName = requireCachedString(KEY_VERSION_NAME, MAX_VERSION_LENGTH),
            versionCode = versionCode,
            packageName = requireCachedString(KEY_PACKAGE_NAME, MAX_PACKAGE_LENGTH),
            channel = AppUpdateChannel.entries.first {
                it.wireValue == requireCachedString(KEY_CHANNEL, 16)
            },
            certificateSha256 = requireCachedDigest(KEY_CERTIFICATE_SHA256),
            releasePageUrl = requireCachedUrl(KEY_RELEASE_PAGE_URL),
            apkUrl = requireCachedUrl(KEY_APK_URL),
            apkName = requireCachedString(KEY_APK_NAME, MAX_ASSET_NAME_LENGTH),
            apkSizeBytes = preferences.getLong(KEY_APK_SIZE, 0L),
            apkSha256 = requireCachedDigest(KEY_APK_SHA256),
        ).also(::requireCompatibleCachedRelease)
    }.getOrNull()

    override fun shouldNotify(release: AppUpdateRelease): Boolean =
        preferences.getLong(KEY_NOTIFIED_VERSION_CODE, 0L) < release.versionCode

    override fun markNotified(release: AppUpdateRelease) {
        preferences.edit { putLong(KEY_NOTIFIED_VERSION_CODE, release.versionCode) }
    }

    override suspend fun checkForUpdate(): AppUpdateCheckResult = withContext(dispatchers.io) {
        try {
            val release = AppUpdateManifestCodec.decodeGitHubRelease(
                executeBounded(
                    request = Request.Builder()
                        .url(LATEST_RELEASE_API)
                        .header("Accept", GITHUB_ACCEPT)
                        .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
                        .header("User-Agent", USER_AGENT)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build(),
                    maximumBytes = MAX_RELEASE_RESPONSE_BYTES,
                    responseKind = ResponseKind.GITHUB_API,
                ),
            )
            val manifest = AppUpdateManifestCodec.decodeManifest(
                executeBounded(
                    request = Request.Builder()
                        .url(release.manifestUrl)
                        .header("Accept", "application/json")
                        .header("User-Agent", USER_AGENT)
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build(),
                    maximumBytes = MAX_MANIFEST_BYTES,
                    responseKind = ResponseKind.RELEASE_ASSET,
                ),
            )
            when (val result = AppUpdateValidator.validate(release, manifest, verifier.identity)) {
                is AppUpdateCheckResult.Available -> {
                    cache(result.release)
                    result
                }
                AppUpdateCheckResult.UpToDate -> {
                    clearCachedRelease()
                    result
                }
                is AppUpdateCheckResult.Incompatible -> {
                    clearCachedRelease()
                    result
                }
                is AppUpdateCheckResult.Failed -> result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpFailureException) {
            AppUpdateCheckResult.Failed(AppUpdateFailure.SERVER, retryable = true)
        } catch (_: UnknownHostException) {
            AppUpdateCheckResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: SocketTimeoutException) {
            AppUpdateCheckResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: IOException) {
            AppUpdateCheckResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: Exception) {
            AppUpdateCheckResult.Failed(AppUpdateFailure.INVALID_METADATA, retryable = false)
        }
    }

    override suspend fun downloadUpdate(
        release: AppUpdateRelease,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): AppUpdateDownloadResult = withContext(dispatchers.io) {
        try {
            requireCompatibleCachedRelease(release)
            val directory = File(updatesRoot, release.versionCode.toString())
            val target = File(directory, AppUpdateManifestCodec.UPDATE_APK_NAME)
            if (target.isFile && verifyFile(target, release) == ApkVerificationResult.VALID) {
                onProgress(release.apkSizeBytes, release.apkSizeBytes)
                return@withContext AppUpdateDownloadResult.Ready(release, target)
            }
            if (!hasEnoughDownloadSpace(release.apkSizeBytes + STORAGE_RESERVE_BYTES)) {
                return@withContext AppUpdateDownloadResult.Failed(
                    AppUpdateFailure.INSUFFICIENT_STORAGE,
                    retryable = false,
                )
            }
            directory.mkdirs()
            require(directory.isDirectory)
            val temporary = File(directory, "${AppUpdateManifestCodec.UPDATE_APK_NAME}.part")
            temporary.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("Accept", "application/vnd.android.package-archive")
                .header("User-Agent", USER_AGENT)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            execute(request).use { response ->
                requireReleaseAssetResponse(response)
                val declaredLength = response.body.contentLength()
                if (declaredLength >= 0L && declaredLength != release.apkSizeBytes) {
                    throw InvalidDownloadException(AppUpdateFailure.INVALID_METADATA)
                }
                var total = 0L
                response.body.byteStream().use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > release.apkSizeBytes) {
                                throw InvalidDownloadException(AppUpdateFailure.INVALID_METADATA)
                            }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            onProgress(total, release.apkSizeBytes)
                        }
                        output.fd.sync()
                    }
                }
                if (total != release.apkSizeBytes) {
                    throw InvalidDownloadException(AppUpdateFailure.INVALID_METADATA)
                }
            }
            val actualDigest = digest.digest().toHex()
            if (actualDigest != release.apkSha256) {
                throw InvalidDownloadException(AppUpdateFailure.CHECKSUM_MISMATCH)
            }
            when (verifier.verifyArchive(temporary, release)) {
                ApkVerificationResult.VALID -> Unit
                ApkVerificationResult.INVALID_CERTIFICATE ->
                    throw InvalidDownloadException(AppUpdateFailure.CHECKSUM_MISMATCH)
                else -> throw InvalidDownloadException(AppUpdateFailure.INVALID_APK)
            }
            moveAtomically(temporary, target)
            updatesRoot.listFiles()
                ?.filter { it != directory }
                ?.forEach(File::deleteRecursively)
            AppUpdateDownloadResult.Ready(release, target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: InvalidDownloadException) {
            AppUpdateDownloadResult.Failed(failure.failure, retryable = false)
        } catch (_: HttpFailureException) {
            AppUpdateDownloadResult.Failed(AppUpdateFailure.SERVER, retryable = true)
        } catch (_: UnknownHostException) {
            AppUpdateDownloadResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: SocketTimeoutException) {
            AppUpdateDownloadResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: IOException) {
            AppUpdateDownloadResult.Failed(AppUpdateFailure.NETWORK, retryable = true)
        } catch (_: Exception) {
            AppUpdateDownloadResult.Failed(AppUpdateFailure.INVALID_APK, retryable = false)
        }
    }

    private suspend fun executeBounded(
        request: Request,
        maximumBytes: Int,
        responseKind: ResponseKind,
    ): ByteArray = execute(request).use { response ->
        when (responseKind) {
            ResponseKind.GITHUB_API -> requireGitHubApiResponse(response)
            ResponseKind.RELEASE_ASSET -> requireReleaseAssetResponse(response)
        }
        val declaredLength = response.body.contentLength()
        require(declaredLength <= maximumBytes || declaredLength < 0L)
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_RESPONSE_CAPACITY))
        response.body.byteStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maximumBytes)
                output.write(buffer, 0, read)
            }
        }
        output.toByteArray()
    }

    private suspend fun execute(request: Request): Response {
        val call = client.newCall(request)
        val completion = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        return try {
            call.execute().also {
                if (!it.isSuccessful) {
                    it.close()
                    throw HttpFailureException(it.code)
                }
            }
        } finally {
            completion?.dispose()
        }
    }

    private fun requireGitHubApiResponse(response: Response) {
        val url = response.request.url
        require(url.isHttps && url.host == GITHUB_API_HOST && url.encodedPath == LATEST_RELEASE_PATH)
    }

    private fun requireReleaseAssetResponse(response: Response) {
        val url = response.request.url
        require(url.isHttps && isAllowedReleaseAssetHost(url.host))
    }

    private fun isAllowedReleaseAssetHost(host: String): Boolean =
        host == GITHUB_HOST ||
            host == RELEASE_ASSET_HOST ||
            host == OBJECTS_HOST ||
            host.endsWith(GITHUB_USER_CONTENT_SUFFIX)

    private fun verifyFile(file: File, release: AppUpdateRelease): ApkVerificationResult {
        if (file.length() != release.apkSizeBytes || sha256(file) != release.apkSha256) {
            return ApkVerificationResult.INVALID_APK
        }
        return verifier.verifyArchive(file, release)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasEnoughDownloadSpace(requiredBytes: Long): Boolean = runCatching {
        val storage = applicationContext.getSystemService(StorageManager::class.java)
        val storageUuid = storage.getUuidForPath(applicationContext.cacheDir)
        storage.getAllocatableBytes(storageUuid) >= requiredBytes
    }.getOrDefault(false)

    private fun cache(release: AppUpdateRelease) {
        preferences.edit {
            putString(KEY_TAG_NAME, release.tagName)
            putString(KEY_VERSION_NAME, release.versionName)
            putLong(KEY_VERSION_CODE, release.versionCode)
            putString(KEY_PACKAGE_NAME, release.packageName)
            putString(KEY_CHANNEL, release.channel.wireValue)
            putString(KEY_CERTIFICATE_SHA256, release.certificateSha256)
            putString(KEY_RELEASE_PAGE_URL, release.releasePageUrl)
            putString(KEY_APK_URL, release.apkUrl)
            putString(KEY_APK_NAME, release.apkName)
            putLong(KEY_APK_SIZE, release.apkSizeBytes)
            putString(KEY_APK_SHA256, release.apkSha256)
        }
    }

    private fun clearCachedRelease() {
        preferences.edit {
            remove(KEY_TAG_NAME)
            remove(KEY_VERSION_NAME)
            remove(KEY_VERSION_CODE)
            remove(KEY_PACKAGE_NAME)
            remove(KEY_CHANNEL)
            remove(KEY_CERTIFICATE_SHA256)
            remove(KEY_RELEASE_PAGE_URL)
            remove(KEY_APK_URL)
            remove(KEY_APK_NAME)
            remove(KEY_APK_SIZE)
            remove(KEY_APK_SHA256)
        }
    }

    private fun requireCompatibleCachedRelease(release: AppUpdateRelease) {
        require(release.packageName == verifier.identity.packageName)
        require(release.channel == verifier.identity.channel)
        require(release.certificateSha256 in verifier.identity.certificateSha256)
        require(release.versionCode > verifier.identity.versionCode)
        require(release.apkName == AppUpdateManifestCodec.UPDATE_APK_NAME)
        require(release.apkSizeBytes in 1..AppUpdateManifestCodec.MAX_APK_BYTES)
        require(SHA256.matches(release.apkSha256))
        requireSafeCachedUrl(release.releasePageUrl, "/stmSi/vault-note-android/releases/")
        requireSafeCachedUrl(release.apkUrl, "/stmSi/vault-note-android/releases/download/")
    }

    private fun requireCachedString(key: String, maximumLength: Int): String =
        requireNotNull(preferences.getString(key, null)).also {
            require(it.isNotBlank() && it.length <= maximumLength)
        }

    private fun requireCachedDigest(key: String): String =
        requireCachedString(key, 64).lowercase().also { require(SHA256.matches(it)) }

    private fun requireCachedUrl(key: String): String = requireCachedString(key, MAX_URL_LENGTH)

    private fun requireSafeCachedUrl(value: String, pathPrefix: String) {
        val url = value.toHttpUrlOrNull() ?: error("Invalid cached URL")
        require(url.isHttps && url.host == GITHUB_HOST && url.encodedPath.startsWith(pathPrefix))
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte)
    }

    private enum class ResponseKind { GITHUB_API, RELEASE_ASSET }
    private class HttpFailureException(val statusCode: Int) : IOException()
    private class InvalidDownloadException(val failure: AppUpdateFailure) : IOException()

    private companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/stmSi/vault-note-android/releases/latest"
        const val LATEST_RELEASE_PATH = "/repos/stmSi/vault-note-android/releases/latest"
        const val GITHUB_ACCEPT = "application/vnd.github+json"
        const val GITHUB_API_VERSION = "2026-03-10"
        const val USER_AGENT = "VaultNote-Android-Update-Checker"
        const val GITHUB_API_HOST = "api.github.com"
        const val GITHUB_HOST = "github.com"
        const val RELEASE_ASSET_HOST = "release-assets.githubusercontent.com"
        const val OBJECTS_HOST = "objects.githubusercontent.com"
        const val GITHUB_USER_CONTENT_SUFFIX = ".githubusercontent.com"
        const val PREFERENCES_NAME = "vaultnote_app_updates"
        const val UPDATE_CACHE_DIRECTORY = "updates"
        const val MAX_RELEASE_RESPONSE_BYTES = 256 * 1024
        const val MAX_MANIFEST_BYTES = 32 * 1024
        const val DEFAULT_RESPONSE_CAPACITY = 8 * 1024
        const val BUFFER_SIZE = 32 * 1024
        const val STORAGE_RESERVE_BYTES = 16L * 1024L * 1024L
        const val MAX_URL_LENGTH = 2_048
        const val MAX_TAG_LENGTH = 64
        const val MAX_VERSION_LENGTH = 64
        const val MAX_PACKAGE_LENGTH = 200
        const val MAX_ASSET_NAME_LENGTH = 128
        const val KEY_AUTOMATIC_CHECKS = "automatic_checks"
        const val KEY_NOTIFIED_VERSION_CODE = "notified_version_code"
        const val KEY_TAG_NAME = "available_tag"
        const val KEY_VERSION_NAME = "available_version_name"
        const val KEY_VERSION_CODE = "available_version_code"
        const val KEY_PACKAGE_NAME = "available_package"
        const val KEY_CHANNEL = "available_channel"
        const val KEY_CERTIFICATE_SHA256 = "available_certificate_sha256"
        const val KEY_RELEASE_PAGE_URL = "available_release_page_url"
        const val KEY_APK_URL = "available_apk_url"
        const val KEY_APK_NAME = "available_apk_name"
        const val KEY_APK_SIZE = "available_apk_size"
        const val KEY_APK_SHA256 = "available_apk_sha256"
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
