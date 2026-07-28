package com.vaultnote.core.sync.lan

import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.sync.RemoteErrorCode
import com.vaultnote.core.sync.SyncRepository
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.StateFlow

data class RelayPairingInput(
    val hostAddress: String,
    val port: Int,
    val vaultId: String?,
    val certificateSha256: String,
    val authenticationToken: String,
    val syncPassword: CharArray,
)

data class NearbyPairingChallenge(
    val verificationCode: String,
    val expiresAtEpochMillis: Long,
)

sealed interface RelayPairingResult {
    data class Paired(val summary: RelayConnectionSummary) : RelayPairingResult
    data object WrongPassword : RelayPairingResult
    data object AuthenticationFailed : RelayPairingResult
    data object CertificateMismatch : RelayPairingResult
    data object PermissionDenied : RelayPairingResult
    data object RelayUnavailable : RelayPairingResult
    data object InvalidConfiguration : RelayPairingResult
    data object LocalStorageFailure : RelayPairingResult
    data object ManualRequired : RelayPairingResult
    data object ApprovalRejected : RelayPairingResult
    data object ApprovalExpired : RelayPairingResult
}

interface LanSyncConnectionRepository {
    val state: StateFlow<RelayConnectionState>

    suspend fun refresh(): RelayConnectionState

    suspend fun discover(): LanDiscoveryResult

    suspend fun pair(input: RelayPairingInput): RelayPairingResult

    suspend fun pairNearby(
        candidate: LanRelayCandidate,
        deviceName: String,
        onChallenge: (NearbyPairingChallenge) -> Unit,
    ): RelayPairingResult

    suspend fun disconnect(): Boolean
}

class DefaultLanSyncConnectionRepository(
    private val credentialStore: SyncCredentialStore,
    private val discovery: LanRelayDiscovery,
    private val backend: RelayHttpBackend,
    private val envelopeCrypto: SyncEnvelopeCrypto,
    private val syncRepository: SyncRepository,
) : LanSyncConnectionRepository {
    override val state: StateFlow<RelayConnectionState> = credentialStore.state

    override suspend fun refresh(): RelayConnectionState {
        when (val loaded = credentialStore.load()) {
            is RepositoryResult.Success -> loaded.value?.clearKey()
            is RepositoryResult.Failure -> Unit
        }
        return state.value
    }

    override suspend fun discover(): LanDiscoveryResult {
        val configured = (state.value as? RelayConnectionState.Configured)?.summary
        return discovery.discover(
            expectedVaultId = configured?.vaultId,
            expectedCertificateSha256 = configured?.certificateSha256,
        )
    }

    override suspend fun pair(input: RelayPairingInput): RelayPairingResult {
        val password = input.syncPassword
        var masterKey: ByteArray? = null
        try {
            val fingerprint = input.certificateSha256.lowercase().replace(":", "").trim()
            val access = ProvisionalRelayAccess(
                hostAddress = input.hostAddress.trim(),
                port = input.port,
                certificateSha256 = fingerprint,
                authenticationToken = input.authenticationToken.trim(),
                expectedVaultId = input.vaultId?.trim()?.takeIf(String::isNotEmpty),
            )
            val information = when (val probe = backend.probe(access)) {
                is RelayProbeResult.Success -> probe.information
                is RelayProbeResult.Failure -> return probe.code.toPairingFailure()
            }
            if (
                information.kdfAlgorithm != "PBKDF2-HMAC-SHA256" ||
                information.kdfIterations != SyncEnvelopeCrypto.REQUIRED_PBKDF2_ITERATIONS ||
                information.kdfKeyBits != 256 ||
                information.certificateSha256 != fingerprint
            ) {
                return RelayPairingResult.InvalidConfiguration
            }
            masterKey = when (
                val derived = envelopeCrypto.deriveMasterKey(
                    password,
                    information.kdfSalt,
                    information.kdfIterations,
                )
            ) {
                is RepositoryResult.Success -> derived.value
                is RepositoryResult.Failure -> return RelayPairingResult.InvalidConfiguration
            }
            when (val keyCheck = backend.getKeyCheck(access)) {
                is RelayKeyCheckResult.Present -> {
                    val decrypted = when (
                        val result = envelopeCrypto.decryptBytes(
                            masterKey,
                            information.vaultId,
                            SyncEnvelopeCrypto.KEY_CHECK_OBJECT_ID,
                            SyncEnvelopePurpose.KEY_CHECK,
                            keyCheck.encryptedEnvelope,
                        )
                    ) {
                        is RepositoryResult.Success -> result.value
                        is RepositoryResult.Failure -> {
                            keyCheck.encryptedEnvelope.fill(0)
                            return RelayPairingResult.WrongPassword
                        }
                    }
                    keyCheck.encryptedEnvelope.fill(0)
                    val matches = MessageDigest.isEqual(
                        decrypted,
                        SyncEnvelopeCrypto.KEY_CHECK_PLAINTEXT,
                    )
                    decrypted.fill(0)
                    if (!matches) return RelayPairingResult.WrongPassword
                }
                RelayKeyCheckResult.Missing -> {
                    val encrypted = when (
                        val result = envelopeCrypto.encryptBytes(
                            masterKey,
                            information.vaultId,
                            SyncEnvelopeCrypto.KEY_CHECK_OBJECT_ID,
                            SyncEnvelopePurpose.KEY_CHECK,
                            SyncEnvelopeCrypto.KEY_CHECK_PLAINTEXT,
                        )
                    ) {
                        is RepositoryResult.Success -> result.value
                        is RepositoryResult.Failure ->
                            return RelayPairingResult.LocalStorageFailure
                    }
                    val failure = try {
                        backend.putKeyCheck(access, encrypted)
                    } finally {
                        encrypted.fill(0)
                    }
                    if (failure != null) return failure.toPairingFailure()
                }
                is RelayKeyCheckResult.Failure -> return keyCheck.code.toPairingFailure()
            }

            return saveVerifiedPairing(access, information, masterKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            masterKey?.fill(0)
            password.fill('\u0000')
        }
    }

    override suspend fun pairNearby(
        candidate: LanRelayCandidate,
        deviceName: String,
        onChallenge: (NearbyPairingChallenge) -> Unit,
    ): RelayPairingResult {
        val session = when (val started = backend.beginNearbyPairing(candidate, deviceName)) {
            is NearbyPairingStartResult.Started -> started.session
            NearbyPairingStartResult.Unsupported -> return RelayPairingResult.ManualRequired
            is NearbyPairingStartResult.Failure -> return started.code.toPairingFailure()
        }
        var payload: NearbyPairingPayload? = null
        try {
            onChallenge(
                NearbyPairingChallenge(
                    session.verificationCode,
                    session.expiresAtEpochMillis,
                ),
            )
            payload = when (val completed = backend.awaitNearbyPairing(session)) {
                is NearbyPairingCompletionResult.Approved -> completed.payload
                NearbyPairingCompletionResult.Rejected ->
                    return RelayPairingResult.ApprovalRejected
                NearbyPairingCompletionResult.Expired ->
                    return RelayPairingResult.ApprovalExpired
                is NearbyPairingCompletionResult.Failure ->
                    return completed.code.toPairingFailure()
            }
            val access = ProvisionalRelayAccess(
                hostAddress = session.hostAddress,
                port = session.port,
                certificateSha256 = session.certificateSha256,
                authenticationToken = payload.authenticationToken,
                expectedVaultId = session.vaultId,
            )
            val information = when (val probe = backend.probe(access)) {
                is RelayProbeResult.Success -> probe.information
                is RelayProbeResult.Failure -> return probe.code.toPairingFailure()
            }
            if (
                information.kdfAlgorithm != "PBKDF2-HMAC-SHA256" ||
                information.kdfIterations != SyncEnvelopeCrypto.REQUIRED_PBKDF2_ITERATIONS ||
                information.kdfKeyBits != 256 ||
                information.vaultId != session.vaultId ||
                information.certificateSha256 != session.certificateSha256
            ) {
                return RelayPairingResult.InvalidConfiguration
            }
            when (val keyCheck = backend.getKeyCheck(access)) {
                is RelayKeyCheckResult.Present -> {
                    val decrypted = when (
                        val result = envelopeCrypto.decryptBytes(
                            payload.masterKey,
                            information.vaultId,
                            SyncEnvelopeCrypto.KEY_CHECK_OBJECT_ID,
                            SyncEnvelopePurpose.KEY_CHECK,
                            keyCheck.encryptedEnvelope,
                        )
                    ) {
                        is RepositoryResult.Success -> result.value
                        is RepositoryResult.Failure -> {
                            keyCheck.encryptedEnvelope.fill(0)
                            return RelayPairingResult.InvalidConfiguration
                        }
                    }
                    keyCheck.encryptedEnvelope.fill(0)
                    val matches = MessageDigest.isEqual(
                        decrypted,
                        SyncEnvelopeCrypto.KEY_CHECK_PLAINTEXT,
                    )
                    decrypted.fill(0)
                    if (!matches) return RelayPairingResult.InvalidConfiguration
                }
                RelayKeyCheckResult.Missing -> return RelayPairingResult.InvalidConfiguration
                is RelayKeyCheckResult.Failure -> return keyCheck.code.toPairingFailure()
            }
            return saveVerifiedPairing(access, information, payload.masterKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            payload?.clear()
            session.clear()
        }
    }

    override suspend fun disconnect(): Boolean =
        credentialStore.clear() is RepositoryResult.Success

    private suspend fun saveVerifiedPairing(
        access: ProvisionalRelayAccess,
        information: RelayInformationWire,
        masterKey: ByteArray,
    ): RelayPairingResult {
        val previous = when (val loaded = credentialStore.load()) {
            is RepositoryResult.Success -> loaded.value
            is RepositoryResult.Failure -> null
        }
        val sameRemote = previous?.vaultId == information.vaultId
        previous?.clearKey()
        val connection = RelayConnectionSecrets(
            hostAddress = access.hostAddress,
            port = access.port,
            dnsName = information.dnsName,
            vaultId = information.vaultId,
            certificateSha256 = information.certificateSha256,
            authenticationToken = access.authenticationToken,
            masterKey = masterKey,
        )
        if (credentialStore.save(connection) is RepositoryResult.Failure) {
            return RelayPairingResult.LocalStorageFailure
        }
        val prepared = if (sameRemote) {
            syncRepository.resumeAfterAuthentication()
        } else {
            syncRepository.prepareForNewRemote()
        }
        if (prepared is RepositoryResult.Failure) {
            credentialStore.clear()
            return RelayPairingResult.LocalStorageFailure
        }
        return RelayPairingResult.Paired(
            RelayConnectionSummary(
                connection.hostAddress,
                connection.port,
                connection.vaultId,
                connection.certificateSha256,
            ),
        )
    }

    private fun RemoteErrorCode.toPairingFailure(): RelayPairingResult = when (this) {
        RemoteErrorCode.AUTHENTICATION_EXPIRED -> RelayPairingResult.AuthenticationFailed
        RemoteErrorCode.NETWORK_UNAVAILABLE,
        RemoteErrorCode.SERVER_UNAVAILABLE,
        -> RelayPairingResult.RelayUnavailable
        RemoteErrorCode.UNSUPPORTED_PROTOCOL -> RelayPairingResult.InvalidConfiguration
        RemoteErrorCode.INVALID_REQUEST -> RelayPairingResult.CertificateMismatch
        RemoteErrorCode.NOT_FOUND,
        RemoteErrorCode.QUOTA_EXCEEDED,
        RemoteErrorCode.CORRUPTED_UPLOAD,
        -> RelayPairingResult.InvalidConfiguration
    }
}
