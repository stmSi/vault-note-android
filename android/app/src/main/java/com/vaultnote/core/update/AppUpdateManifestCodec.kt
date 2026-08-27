package com.vaultnote.core.update

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class GitHubReleaseDescriptor(
    val tagName: String,
    val releasePageUrl: String,
    val manifestUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

internal data class PublishedUpdateManifest(
    val schemaVersion: Long,
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val packageName: String,
    val channel: AppUpdateChannel,
    val certificateSha256: String,
    val apkName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
)

internal object AppUpdateManifestCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun decodeGitHubRelease(bytes: ByteArray): GitHubReleaseDescriptor {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val tagName = root.requiredString("tag_name", MAX_TAG_LENGTH)
        val releasePageUrl = root.requiredString("html_url", MAX_URL_LENGTH)
        val assets = root.getValue("assets").jsonArray
        val byName = assets.associate { element ->
            val asset = element.jsonObject
            asset.requiredString("name", MAX_ASSET_NAME_LENGTH) to asset
        }
        val manifest = requireNotNull(byName[UPDATE_MANIFEST_NAME])
        val apk = requireNotNull(byName[UPDATE_APK_NAME])
        val apkSize = apk.requiredLong("size")
        require(apkSize in 1..MAX_APK_BYTES)
        val manifestUrl = manifest.requiredString("browser_download_url", MAX_URL_LENGTH)
        val apkUrl = apk.requiredString("browser_download_url", MAX_URL_LENGTH)
        requireSafeReleaseUrl(releasePageUrl)
        requireSafeAssetUrl(manifestUrl, tagName, UPDATE_MANIFEST_NAME)
        requireSafeAssetUrl(apkUrl, tagName, UPDATE_APK_NAME)
        return GitHubReleaseDescriptor(tagName, releasePageUrl, manifestUrl, apkUrl, apkSize)
    }

    fun decodeManifest(bytes: ByteArray): PublishedUpdateManifest {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        require(
            root.keys == setOf(
                "schemaVersion",
                "tagName",
                "versionName",
                "versionCode",
                "packageName",
                "channel",
                "certificateSha256",
                "asset",
            ),
        )
        val asset = root.getValue("asset").jsonObject
        require(asset.keys == setOf("name", "sizeBytes", "sha256"))
        val channel = AppUpdateChannel.entries.firstOrNull {
            it.wireValue == root.requiredString("channel", 16)
        } ?: error("Unsupported update channel")
        return PublishedUpdateManifest(
            schemaVersion = root.requiredLong("schemaVersion"),
            tagName = root.requiredString("tagName", MAX_TAG_LENGTH),
            versionName = root.requiredString("versionName", MAX_VERSION_LENGTH),
            versionCode = root.requiredLong("versionCode"),
            packageName = root.requiredString("packageName", MAX_PACKAGE_LENGTH),
            channel = channel,
            certificateSha256 = root.requiredDigest("certificateSha256"),
            apkName = asset.requiredString("name", MAX_ASSET_NAME_LENGTH),
            apkSizeBytes = asset.requiredLong("sizeBytes"),
            apkSha256 = asset.requiredDigest("sha256"),
        )
    }

    private fun JsonObject.requiredString(name: String, maximumLength: Int): String {
        val value = (getValue(name) as? JsonPrimitive)?.contentOrNull
            ?: error("$name must be a string")
        require(value.isNotBlank() && value.length <= maximumLength)
        return value
    }

    private fun JsonObject.requiredLong(name: String): Long =
        getValue(name).jsonPrimitive.longOrNull ?: error("$name must be an integer")

    private fun JsonObject.requiredDigest(name: String): String =
        requiredString(name, SHA256_LENGTH).lowercase().also {
            require(SHA256.matches(it))
        }

    private fun requireSafeReleaseUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme == HTTPS && uri.host == GITHUB_HOST)
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.startsWith(RELEASE_PATH_PREFIX))
    }

    private fun requireSafeAssetUrl(value: String, tagName: String, assetName: String) {
        val uri = URI(value)
        require(uri.scheme == HTTPS && uri.host == GITHUB_HOST)
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path == "$RELEASE_PATH_PREFIX/download/$tagName/$assetName")
    }

    const val UPDATE_MANIFEST_NAME = "VaultNote-Android-update.json"
    const val UPDATE_APK_NAME = "VaultNote-Android.apk"
    const val MAX_APK_BYTES = 200L * 1024L * 1024L
    private const val HTTPS = "https"
    private const val GITHUB_HOST = "github.com"
    private const val RELEASE_PATH_PREFIX = "/stmSi/vault-note-android/releases"
    private const val MAX_URL_LENGTH = 2_048
    private const val MAX_TAG_LENGTH = 64
    private const val MAX_VERSION_LENGTH = 64
    private const val MAX_PACKAGE_LENGTH = 200
    private const val MAX_ASSET_NAME_LENGTH = 128
    private const val SHA256_LENGTH = 64
    private val SHA256 = Regex("^[a-f0-9]{64}$")
}

internal object AppUpdateValidator {
    fun validate(
        release: GitHubReleaseDescriptor,
        manifest: PublishedUpdateManifest,
        installed: InstalledAppIdentity,
    ): AppUpdateCheckResult {
        require(manifest.schemaVersion == 1L)
        require(manifest.tagName == release.tagName)
        require(manifest.versionName.matches(Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")))
        require(manifest.versionCode in 1..2_100_000_000L)
        require(manifest.apkName == AppUpdateManifestCodec.UPDATE_APK_NAME)
        require(manifest.apkSizeBytes == release.apkSizeBytes)
        require(manifest.apkSizeBytes in 1..AppUpdateManifestCodec.MAX_APK_BYTES)
        if (manifest.packageName != installed.packageName) {
            return AppUpdateCheckResult.Incompatible(AppUpdateIncompatibility.PACKAGE)
        }
        if (manifest.channel != installed.channel) {
            return AppUpdateCheckResult.Incompatible(AppUpdateIncompatibility.CHANNEL)
        }
        if (manifest.certificateSha256 !in installed.certificateSha256) {
            return AppUpdateCheckResult.Incompatible(AppUpdateIncompatibility.SIGNING_CERTIFICATE)
        }
        if (manifest.versionCode <= installed.versionCode) return AppUpdateCheckResult.UpToDate
        return AppUpdateCheckResult.Available(
            AppUpdateRelease(
                tagName = manifest.tagName,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                packageName = manifest.packageName,
                channel = manifest.channel,
                certificateSha256 = manifest.certificateSha256,
                releasePageUrl = release.releasePageUrl,
                apkUrl = release.apkUrl,
                apkName = manifest.apkName,
                apkSizeBytes = manifest.apkSizeBytes,
                apkSha256 = manifest.apkSha256,
            ),
        )
    }
}
