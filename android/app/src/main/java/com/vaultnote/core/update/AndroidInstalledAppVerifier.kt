package com.vaultnote.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.vaultnote.BuildConfig
import java.io.File
import java.security.MessageDigest

internal enum class ApkVerificationResult {
    VALID,
    INVALID_PACKAGE,
    INVALID_VERSION,
    INVALID_CERTIFICATE,
    INVALID_APK,
}

internal class AndroidInstalledAppVerifier(context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    val identity: InstalledAppIdentity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val info = installedPackageInfo()
        InstalledAppIdentity(
            packageName = packageName,
            channel = if (BuildConfig.DEBUG) AppUpdateChannel.DEBUG else AppUpdateChannel.PRODUCTION,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            certificateSha256 = certificateDigests(info),
        ).also { require(it.certificateSha256.isNotEmpty()) }
    }

    fun verifyArchive(apk: File, release: AppUpdateRelease): ApkVerificationResult {
        val info = archivePackageInfo(apk) ?: return ApkVerificationResult.INVALID_APK
        if (info.packageName != identity.packageName || info.packageName != release.packageName) {
            return ApkVerificationResult.INVALID_PACKAGE
        }
        if (
            PackageInfoCompat.getLongVersionCode(info) != release.versionCode ||
            release.versionCode <= identity.versionCode
        ) {
            return ApkVerificationResult.INVALID_VERSION
        }
        val archiveCertificates = certificateDigests(info)
        if (
            release.certificateSha256 !in archiveCertificates ||
            archiveCertificates.intersect(identity.certificateSha256).isEmpty()
        ) {
            return ApkVerificationResult.INVALID_CERTIFICATE
        }
        return ApkVerificationResult.VALID
    }

    private fun installedPackageInfo(): PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, signingFlag())
    }

    private fun archivePackageInfo(apk: File): PackageInfo? = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlag())
    }

    private fun signingFlag(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
