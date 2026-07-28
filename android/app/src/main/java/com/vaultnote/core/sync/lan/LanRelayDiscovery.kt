package com.vaultnote.core.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class LanRelayCandidate(
    val serviceName: String,
    val hostAddress: String,
    val port: Int,
    val vaultId: String,
    val certificateSha256: String,
)

sealed interface LanDiscoveryResult {
    data class Found(val relay: LanRelayCandidate) : LanDiscoveryResult
    data object NotFound : LanDiscoveryResult
    data object PermissionDenied : LanDiscoveryResult
    data object Unavailable : LanDiscoveryResult
}

interface LanRelayDiscovery {
    suspend fun discover(
        expectedVaultId: String? = null,
        expectedCertificateSha256: String? = null,
        timeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MILLIS,
    ): LanDiscoveryResult

    companion object {
        const val SERVICE_TYPE = "_vaultnote-sync._tcp."
        const val DEFAULT_DISCOVERY_TIMEOUT_MILLIS = 6_000L
    }
}

/**
 * Resolves VaultNote relays with Android NSD. The mDNS record is used only for reachability:
 * callers must independently authenticate the token and pin the advertised certificate.
 */
class AndroidLanRelayDiscovery(context: Context) : LanRelayDiscovery {
    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val wifiManager = applicationContext.getSystemService(WifiManager::class.java)

    override suspend fun discover(
        expectedVaultId: String?,
        expectedCertificateSha256: String?,
        timeoutMillis: Long,
    ): LanDiscoveryResult {
        if (nsdManager == null || timeoutMillis !in 1_000L..30_000L) {
            return LanDiscoveryResult.Unavailable
        }
        val expectedVault = expectedVaultId?.takeIf(::isSafeId)
        val expectedFingerprint = expectedCertificateSha256?.normalizeFingerprint()
        if (
            expectedVaultId != null && expectedVault == null ||
            expectedCertificateSha256 != null && expectedFingerprint == null
        ) {
            return LanDiscoveryResult.Unavailable
        }

        val multicastLock = acquireMulticastLock()
        return try {
            withTimeoutOrNull(timeoutMillis) {
                awaitCandidate(expectedVault, expectedFingerprint)
            } ?: LanDiscoveryResult.NotFound
        } catch (_: SecurityException) {
            LanDiscoveryResult.PermissionDenied
        } catch (_: RuntimeException) {
            LanDiscoveryResult.Unavailable
        } finally {
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    private suspend fun awaitCandidate(
        expectedVaultId: String?,
        expectedFingerprint: String?,
    ): LanDiscoveryResult = suspendCancellableCoroutine { continuation ->
        val discoveryStarted = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        var serviceInfoCallback: NsdManager.ServiceInfoCallback? = null
        lateinit var listener: NsdManager.DiscoveryListener

        fun stopResolution() {
            val callback = serviceInfoCallback
            serviceInfoCallback = null
            if (Build.VERSION.SDK_INT >= 34 && callback != null) {
                try {
                    nsdManager?.unregisterServiceInfoCallback(callback)
                } catch (_: IllegalArgumentException) {
                    Unit
                }
            }
            resolving.set(false)
        }

        fun stopDiscovery() {
            stopResolution()
            if (discoveryStarted.compareAndSet(true, false)) {
                try {
                    nsdManager?.stopServiceDiscovery(listener)
                } catch (_: IllegalArgumentException) {
                    Unit
                } catch (_: IllegalStateException) {
                    Unit
                }
            }
        }

        fun complete(result: LanDiscoveryResult) {
            stopDiscovery()
            if (continuation.isActive) continuation.resume(result)
        }

        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discoveryStarted.set(true)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!resolving.compareAndSet(false, true)) return
                try {
                    val onResolved: (NsdServiceInfo) -> Unit = { resolved ->
                        val advertised = parseAdvertisement(resolved)
                        val address = preferredAddress(resolved)
                        if (
                            advertised == null ||
                            address == null ||
                            resolved.port !in 1..65_535 ||
                            expectedVaultId != null &&
                            advertised.vaultId != expectedVaultId ||
                            expectedFingerprint != null &&
                            advertised.certificateSha256 != expectedFingerprint
                        ) {
                            stopResolution()
                        } else {
                            complete(
                                LanDiscoveryResult.Found(
                                    advertised.copy(
                                        serviceName = resolved.serviceName,
                                        hostAddress = address.hostAddress.orEmpty(),
                                        port = resolved.port,
                                    ),
                                ),
                            )
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 34) {
                        val callback = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                serviceInfoCallback = null
                                resolving.set(false)
                            }

                            override fun onServiceInfoCallbackUnregistered() = Unit

                            override fun onServiceLost() {
                                stopResolution()
                            }

                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                onResolved(serviceInfo)
                            }
                        }
                        serviceInfoCallback = callback
                        nsdManager?.registerServiceInfoCallback(
                            serviceInfo,
                            applicationContext.mainExecutor,
                            callback,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        nsdManager?.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int,
                            ) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                onResolved(resolved)
                            }
                            },
                        )
                    }
                } catch (_: IllegalArgumentException) {
                    stopResolution()
                } catch (_: IllegalStateException) {
                    stopResolution()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryStarted.set(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryStarted.set(false)
                complete(LanDiscoveryResult.Unavailable)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryStarted.set(false)
            }
        }

        continuation.invokeOnCancellation { stopDiscovery() }
        try {
            nsdManager?.discoverServices(
                LanRelayDiscovery.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            ) ?: complete(LanDiscoveryResult.Unavailable)
        } catch (_: SecurityException) {
            complete(LanDiscoveryResult.PermissionDenied)
        } catch (_: IllegalArgumentException) {
            complete(LanDiscoveryResult.Unavailable)
        } catch (_: IllegalStateException) {
            complete(LanDiscoveryResult.Unavailable)
        }
    }

    private fun parseAdvertisement(serviceInfo: NsdServiceInfo): LanRelayCandidate? {
        if (!serviceInfo.serviceType.equals(LanRelayDiscovery.SERVICE_TYPE, ignoreCase = true)) {
            return null
        }
        val attributes = serviceInfo.attributes.mapValues { (_, bytes) ->
            bytes.toString(StandardCharsets.UTF_8)
        }
        if (attributes["protocol"] != PROTOCOL_VERSION.toString()) return null
        if (attributes["tls"] != "required") return null
        val vaultId = attributes["vault"]?.takeIf(::isSafeId) ?: return null
        val fingerprint = attributes["certSha256"]?.normalizeFingerprint() ?: return null
        return LanRelayCandidate(
            serviceName = serviceInfo.serviceName,
            hostAddress = "",
            port = 0,
            vaultId = vaultId,
            certificateSha256 = fingerprint,
        )
    }

    private fun preferredAddress(serviceInfo: NsdServiceInfo): InetAddress? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }
        return addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: addresses.firstOrNull { !it.isLoopbackAddress }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (_: SecurityException) {
        null
    }

    private fun String.normalizeFingerprint(): String? {
        val normalized = lowercase().replace(":", "").trim()
        return normalized.takeIf { SHA256.matches(it) }
    }

    private fun isSafeId(value: String): Boolean =
        value.length in 1..128 && SAFE_ID.matches(value)

    private companion object {
        const val PROTOCOL_VERSION = 3
        const val MULTICAST_LOCK_TAG = "VaultNote:LanRelayDiscovery"
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
