package com.vaultnote.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestCodecTest {
    @Test
    fun `valid release metadata produces a compatible update`() {
        val release = AppUpdateManifestCodec.decodeGitHubRelease(RELEASE_JSON.encodeToByteArray())
        val manifest = AppUpdateManifestCodec.decodeManifest(MANIFEST_JSON.encodeToByteArray())

        val result = AppUpdateValidator.validate(
            release,
            manifest,
            InstalledAppIdentity(
                packageName = "com.vaultnote",
                channel = AppUpdateChannel.PRODUCTION,
                versionCode = 100_003,
                certificateSha256 = setOf(CERTIFICATE),
            ),
        )

        assertTrue(result is AppUpdateCheckResult.Available)
        val available = (result as AppUpdateCheckResult.Available).release
        assertEquals(100_004L, available.versionCode)
        assertEquals(APK_DIGEST, available.apkSha256)
        assertEquals(54_000_000L, available.apkSizeBytes)
    }

    @Test
    fun `version code prevents tag-only downgrade decisions`() {
        val result = AppUpdateValidator.validate(
            AppUpdateManifestCodec.decodeGitHubRelease(RELEASE_JSON.encodeToByteArray()),
            AppUpdateManifestCodec.decodeManifest(MANIFEST_JSON.encodeToByteArray()),
            InstalledAppIdentity(
                packageName = "com.vaultnote",
                channel = AppUpdateChannel.PRODUCTION,
                versionCode = 100_004,
                certificateSha256 = setOf(CERTIFICATE),
            ),
        )

        assertEquals(AppUpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `different signer is rejected before download`() {
        val result = AppUpdateValidator.validate(
            AppUpdateManifestCodec.decodeGitHubRelease(RELEASE_JSON.encodeToByteArray()),
            AppUpdateManifestCodec.decodeManifest(MANIFEST_JSON.encodeToByteArray()),
            InstalledAppIdentity(
                packageName = "com.vaultnote",
                channel = AppUpdateChannel.PRODUCTION,
                versionCode = 32,
                certificateSha256 = setOf("c".repeat(64)),
            ),
        )

        assertEquals(
            AppUpdateCheckResult.Incompatible(AppUpdateIncompatibility.SIGNING_CERTIFICATE),
            result,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `release asset outside repository is rejected`() {
        AppUpdateManifestCodec.decodeGitHubRelease(
            RELEASE_JSON.replace(
                "https://github.com/stmSi/vault-note-android/releases/download/v0.0.4/" +
                    "VaultNote-Android.apk",
                "https://example.com/VaultNote-Android.apk",
            ).encodeToByteArray(),
        )
    }

    private companion object {
        val CERTIFICATE = "a".repeat(64)
        val APK_DIGEST = "b".repeat(64)
        val RELEASE_JSON = """
            {
              "tag_name": "v0.0.4",
              "html_url": "https://github.com/stmSi/vault-note-android/releases/tag/v0.0.4",
              "assets": [
                {
                  "name": "VaultNote-Android-update.json",
                  "browser_download_url": "https://github.com/stmSi/vault-note-android/releases/download/v0.0.4/VaultNote-Android-update.json",
                  "size": 500
                },
                {
                  "name": "VaultNote-Android.apk",
                  "browser_download_url": "https://github.com/stmSi/vault-note-android/releases/download/v0.0.4/VaultNote-Android.apk",
                  "size": 54000000
                }
              ]
            }
        """.trimIndent()
        val MANIFEST_JSON = """
            {
              "schemaVersion": 1,
              "tagName": "v0.0.4",
              "versionName": "0.0.4",
              "versionCode": 100004,
              "packageName": "com.vaultnote",
              "channel": "production",
              "certificateSha256": "$CERTIFICATE",
              "asset": {
                "name": "VaultNote-Android.apk",
                "sizeBytes": 54000000,
                "sha256": "$APK_DIGEST"
              }
            }
        """.trimIndent()
    }
}
