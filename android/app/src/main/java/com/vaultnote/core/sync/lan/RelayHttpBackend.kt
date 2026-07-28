package com.vaultnote.core.sync.lan

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import com.vaultnote.core.encryption.EncryptionContext
import com.vaultnote.core.encryption.EncryptionService
import com.vaultnote.core.sync.AuthProvider
import com.vaultnote.core.sync.AuthenticationState
import com.vaultnote.core.sync.RemoteAttachmentReference
import com.vaultnote.core.sync.RemoteChange
import com.vaultnote.core.sync.RemoteChangePage
import com.vaultnote.core.sync.RemoteDownloadResult
import com.vaultnote.core.sync.RemoteErrorCode
import com.vaultnote.core.sync.RemoteFileResult
import com.vaultnote.core.sync.RemoteFileStore
import com.vaultnote.core.sync.RemoteItemVersion
import com.vaultnote.core.sync.RemoteMutationResult
import com.vaultnote.core.sync.RemotePullResult
import com.vaultnote.core.sync.RemoteVerificationResult
import com.vaultnote.core.sync.SyncApi
import com.vaultnote.core.sync.SyncOperationArtifactStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class ProvisionalRelayAccess(
    val hostAddress: String,
    val port: Int,
    val certificateSha256: String,
    val authenticationToken: String,
    val expectedVaultId: String?,
)

sealed interface RelayProbeResult {
    data class Success(val information: RelayInformationWire) : RelayProbeResult
    data class Failure(val code: RemoteErrorCode) : RelayProbeResult
}

sealed interface RelayKeyCheckResult {
    data class Present(val encryptedEnvelope: ByteArray, val checksum: String) : RelayKeyCheckResult
    data object Missing : RelayKeyCheckResult
    data class Failure(val code: RemoteErrorCode) : RelayKeyCheckResult
}

/**
 * Protocol-3 HTTPS backend. It pins the exact relay certificate before sending credentials,
 * refuses redirects, streams files, and retries a failed stored address once after authenticated
 * vault-ID/fingerprint matching through mDNS.
 */
class RelayHttpBackend(
    context: Context,
    private val credentialStore: SyncCredentialStore,
    private val discovery: LanRelayDiscovery,
    private val envelopeCrypto: SyncEnvelopeCrypto,
    private val deviceEncryption: EncryptionService,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : SyncApi, AuthProvider, RemoteFileStore, SyncOperationArtifactStore {
    private val applicationContext = context.applicationContext
    private val transferRoot = File(applicationContext.filesDir, "sync-transfers")
    private val clients = ConcurrentHashMap<String, OkHttpClient>()
    private val transferMutex = Mutex()

    override suspend fun authenticationState(): AuthenticationState =
        when (val result = credentialStore.load()) {
            is RepositoryResult.Success -> {
                val loaded = result.value
                loaded?.clearKey()
                if (loaded == null) AuthenticationState.EXPIRED else AuthenticationState.AUTHENTICATED
            }
            is RepositoryResult.Failure -> AuthenticationState.EXPIRED
        }

    suspend fun probe(access: ProvisionalRelayAccess): RelayProbeResult =
        withContext(dispatchers.io) {
            if (!access.isValid()) {
                return@withContext RelayProbeResult.Failure(RemoteErrorCode.INVALID_REQUEST)
            }
            try {
                val response = execute(
                    access.hostAddress,
                    access.port,
                    access.certificateSha256,
                    request(
                        access.hostAddress,
                        access.port,
                        access.authenticationToken,
                        "/v1/relay",
                    ),
                )
                response.use {
                    if (!it.isSuccessful) return@withContext RelayProbeResult.Failure(mapStatus(it.code))
                    val information = RelayWireCodec.relayInformation(it.readBounded(MAX_CONTROL_BYTES))
                    if (
                        information.protocolVersion != PROTOCOL_VERSION ||
                        information.minimumClientProtocolVersion > PROTOCOL_VERSION ||
                        information.certificateSha256 != access.certificateSha256 ||
                        access.expectedVaultId != null &&
                        information.vaultId != access.expectedVaultId
                    ) {
                        RelayProbeResult.Failure(RemoteErrorCode.INVALID_REQUEST)
                    } else {
                        RelayProbeResult.Success(information)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                RelayProbeResult.Failure(mapIo(failure))
            } catch (_: IllegalArgumentException) {
                RelayProbeResult.Failure(RemoteErrorCode.INVALID_REQUEST)
            }
        }

    suspend fun getKeyCheck(access: ProvisionalRelayAccess): RelayKeyCheckResult =
        withContext(dispatchers.io) {
            try {
                val response = execute(
                    access.hostAddress,
                    access.port,
                    access.certificateSha256,
                    request(
                        access.hostAddress,
                        access.port,
                        access.authenticationToken,
                        "/v1/key-check",
                    ),
                )
                response.use {
                    when {
                        it.code == 404 -> RelayKeyCheckResult.Missing
                        !it.isSuccessful -> RelayKeyCheckResult.Failure(mapStatus(it.code))
                        else -> {
                            val (encoded, checksum) =
                                RelayWireCodec.keyCheck(it.readBounded(MAX_CONTROL_BYTES))
                            val encrypted = Base64.decode(encoded, Base64.DEFAULT)
                            if (envelopeCrypto.sha256(encrypted) != checksum) {
                                encrypted.fill(0)
                                RelayKeyCheckResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
                            } else {
                                RelayKeyCheckResult.Present(encrypted, checksum)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                RelayKeyCheckResult.Failure(mapIo(failure))
            } catch (_: IllegalArgumentException) {
                RelayKeyCheckResult.Failure(RemoteErrorCode.INVALID_REQUEST)
            }
        }

    suspend fun putKeyCheck(
        access: ProvisionalRelayAccess,
        encryptedEnvelope: ByteArray,
    ): RemoteErrorCode? = withContext(dispatchers.io) {
        try {
            val encoded = Base64.encodeToString(encryptedEnvelope, Base64.NO_WRAP)
            val body = RelayWireCodec.keyCheckRequest(
                encoded,
                envelopeCrypto.sha256(encryptedEnvelope),
            ).jsonBody()
            val response = execute(
                access.hostAddress,
                access.port,
                access.certificateSha256,
                request(
                    access.hostAddress,
                    access.port,
                    access.authenticationToken,
                    "/v1/key-check",
                    method = "PUT",
                    body = body,
                ),
            )
            response.use {
                if (it.code == 201 || it.code == 204) null else mapStatus(it.code)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            mapIo(failure)
        } catch (_: IllegalArgumentException) {
            RemoteErrorCode.INVALID_REQUEST
        }
    }

    override suspend fun upsertItem(
        operationId: String,
        item: com.vaultnote.core.sync.RemoteItemMetadata,
        expectedVersionToken: String?,
    ): RemoteMutationResult = withConnectionResult { connection ->
        if (!safeId(operationId) || !safeId(item.id)) {
            return@withConnectionResult RemoteMutationResult.Failure(
                RemoteErrorCode.INVALID_REQUEST,
            )
        }
        val encrypted = when (
            val result = stableItemEnvelope(connection, operationId, item.id, item)
        ) {
            is RepositoryResult.Success -> result.value
            is RepositoryResult.Failure ->
                return@withConnectionResult RemoteMutationResult.Failure(
                    RemoteErrorCode.CORRUPTED_UPLOAD,
                )
        }
        try {
            val body = RelayWireCodec.itemMutationRequest(
                expectedVersionToken,
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                envelopeCrypto.sha256(encrypted),
            ).jsonBody()
            val result = mutationRequest(
                connection,
                operationId,
                "/v1/items/${item.id}",
                "PUT",
                body,
            )
            result
        } finally {
            encrypted.fill(0)
        }
    }

    override suspend fun deleteItem(
        operationId: String,
        itemId: String,
        expectedVersionToken: String?,
    ): RemoteMutationResult = withConnectionResult { connection ->
        mutationRequest(
            connection,
            operationId,
            "/v1/items/$itemId",
            "DELETE",
            RelayWireCodec.deleteMutationRequest(expectedVersionToken).jsonBody(),
        )
    }

    override suspend fun pullChanges(cursor: String?, limit: Int): RemotePullResult =
        withConnectionPull { connection ->
            try {
                val path = buildString {
                    append("/v1/changes?limit=")
                    append(limit.coerceIn(1, 200))
                    if (cursor != null) {
                        append("&cursor=")
                        append(cursor)
                    }
                }
                withRelocation(connection) { endpoint ->
                    execute(
                        endpoint.hostAddress,
                        endpoint.port,
                        connection.certificateSha256,
                        request(
                            endpoint.hostAddress,
                            endpoint.port,
                            connection.authenticationToken,
                            path,
                        ),
                    ).use { response ->
                        if (!response.isSuccessful) {
                            return@withRelocation RemotePullResult.Failure(mapStatus(response.code))
                        }
                        val page = RelayWireCodec.changePage(
                            response.readBounded(MAX_CHANGE_PAGE_BYTES),
                        )
                        val changes = page.changes.map { wire ->
                            if (wire.deleted) {
                                RemoteChange.Delete(
                                    wire.itemId,
                                    wire.serverRevision,
                                    wire.versionToken,
                                )
                            } else {
                                RemoteChange.Upsert(decryptRemoteItem(connection, wire))
                            }
                        }
                        RemotePullResult.Success(
                            RemoteChangePage(changes, page.nextCursor, page.hasMore),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                RemotePullResult.Failure(mapIo(failure))
            } catch (_: IllegalArgumentException) {
                RemotePullResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
            } catch (_: GeneralSecurityException) {
                RemotePullResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
            }
        }

    override suspend fun uploadEncrypted(
        operationId: String,
        attachmentId: String,
        plaintextSha256: String,
        plaintextSize: Long,
        source: File,
    ): RemoteFileResult = withConnectionFile { connection ->
        if (
            !safeId(operationId) ||
            !safeId(attachmentId) ||
            !SHA256.matches(plaintextSha256) ||
            plaintextSize !in 0..MAX_PLAINTEXT_ATTACHMENT_BYTES ||
            !source.isFile
        ) {
            return@withConnectionFile RemoteFileResult.Failure(RemoteErrorCode.INVALID_REQUEST)
        }
        val envelope = outgoingAttachmentFile(connection.vaultId, attachmentId)
        val outgoingDirectory = envelope.parentFile
            ?: return@withConnectionFile RemoteFileResult.Failure(
                RemoteErrorCode.SERVER_UNAVAILABLE,
            )
        if (!outgoingDirectory.isDirectory && !outgoingDirectory.mkdirs()) {
            return@withConnectionFile RemoteFileResult.Failure(
                RemoteErrorCode.SERVER_UNAVAILABLE,
            )
        }
        if (envelope.exists()) {
            val verified = envelopeCrypto.decryptFileVerifiedTo(
                connection.masterKey,
                connection.vaultId,
                attachmentId,
                envelope,
                plaintextSize,
                DISCARD_OUTPUT,
            )
            if (verified is RepositoryResult.Failure) {
                return@withConnectionFile RemoteFileResult.Failure(
                    RemoteErrorCode.CORRUPTED_UPLOAD,
                )
            }
        } else {
            when (
                envelopeCrypto.encryptFileAtomically(
                    connection.masterKey,
                    connection.vaultId,
                    attachmentId,
                    plaintextSize,
                    envelope,
                ) { output ->
                    deviceEncryption.decryptVerifiedTo(
                        encryptedFile = source,
                        context = EncryptionContext(
                            attachmentId,
                            EncryptedFilePurpose.ATTACHMENT,
                        ),
                        output = output,
                    ).toUnitResult()
                }
            ) {
                is RepositoryResult.Success -> Unit
                is RepositoryResult.Failure ->
                    return@withConnectionFile RemoteFileResult.Failure(
                        RemoteErrorCode.CORRUPTED_UPLOAD,
                    )
            }
        }
        try {
            val checksum = envelopeCrypto.sha256(envelope)
            val responseResult = withRelocation(connection) { endpoint ->
                val uploadBody = envelope.asRequestBody(OCTET_STREAM)
                execute(
                    endpoint.hostAddress,
                    endpoint.port,
                    connection.certificateSha256,
                    request(
                        endpoint.hostAddress,
                        endpoint.port,
                        connection.authenticationToken,
                        "/v1/attachments/$attachmentId",
                        method = "PUT",
                        body = uploadBody,
                        operationId = operationId,
                        ciphertextSha256 = checksum,
                    ),
                ).use { response ->
                    if (!response.isSuccessful) {
                        return@withRelocation RemoteFileResult.Failure(mapStatus(response.code))
                    }
                    val receipt = RelayWireCodec.attachmentReceipt(
                        response.readBounded(MAX_CONTROL_BYTES),
                    )
                    if (
                        receipt.attachmentId != attachmentId ||
                        receipt.ciphertextSha256 != checksum ||
                        receipt.ciphertextSize != envelope.length()
                    ) {
                        RemoteFileResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
                    } else {
                        RemoteFileResult.Uploaded(receipt.remotePath)
                    }
                }
            }
            responseResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RemoteFileResult.Failure(mapIo(failure))
        } catch (_: IllegalArgumentException) {
            RemoteFileResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
        }
    }

    override suspend fun verifyUpload(
        remotePath: String,
        plaintextSha256: String,
    ): RemoteVerificationResult = withLoaded(
        RemoteVerificationResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED),
    ) { connection ->
        if (!SHA256.matches(plaintextSha256)) {
            return@withLoaded RemoteVerificationResult.Failure(
                RemoteErrorCode.INVALID_REQUEST,
            )
        }
        val attachmentId = remotePath
            .takeIf { it.startsWith(ATTACHMENT_PATH_PREFIX) }
            ?.removePrefix(ATTACHMENT_PATH_PREFIX)
            ?.takeIf(::safeId)
            ?: return@withLoaded RemoteVerificationResult.Failure(
                RemoteErrorCode.INVALID_REQUEST,
            )
        val localEnvelope = outgoingAttachmentFile(connection.vaultId, attachmentId)
        if (!localEnvelope.isFile) {
            return@withLoaded RemoteVerificationResult.Failure(
                RemoteErrorCode.NETWORK_UNAVAILABLE,
            )
        }
        try {
            val localChecksum = envelopeCrypto.sha256(localEnvelope)
            val verified = withRelocation(connection) { endpoint ->
                execute(
                    endpoint.hostAddress,
                    endpoint.port,
                    connection.certificateSha256,
                    request(
                        endpoint.hostAddress,
                        endpoint.port,
                        connection.authenticationToken,
                        remotePath,
                        method = "HEAD",
                    ),
                ).use { response ->
                    response.isSuccessful &&
                        response.header(CIPHERTEXT_SHA256_HEADER) == localChecksum &&
                        response.header("Content-Length")?.toLongOrNull() ==
                        localEnvelope.length()
                }
            }
            if (verified) {
                RemoteVerificationResult.Verified
            } else {
                RemoteVerificationResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RemoteVerificationResult.Failure(mapIo(failure))
        }
    }

    override suspend fun releaseItemOperation(operationId: String) {
        releaseArtifact(operationId, ::outgoingItemFile)
    }

    override suspend fun releaseAttachment(attachmentId: String) {
        releaseArtifact(attachmentId, ::outgoingAttachmentFile)
    }

    private suspend fun releaseArtifact(
        identifier: String,
        resolve: (String, String) -> File,
    ) {
        if (!safeId(identifier)) return
        val state = credentialStore.state.value as? RelayConnectionState.Configured ?: return
        withContext(dispatchers.io) {
            try {
                resolve(state.summary.vaultId, identifier).delete()
            } catch (_: SecurityException) {
                // Cleanup failure must not roll back sync state that Room already committed.
            }
        }
    }

    override suspend fun delete(
        operationId: String,
        attachmentId: String,
    ): RemoteFileResult = withConnectionFile { connection ->
        try {
            withRelocation(connection) { endpoint ->
                execute(
                    endpoint.hostAddress,
                    endpoint.port,
                    connection.certificateSha256,
                    request(
                        endpoint.hostAddress,
                        endpoint.port,
                        connection.authenticationToken,
                        "/v1/attachments/$attachmentId",
                        method = "DELETE",
                        operationId = operationId,
                    ),
                ).use { response ->
                    if (response.isSuccessful) {
                        RemoteFileResult.Deleted
                    } else {
                        RemoteFileResult.Failure(mapStatus(response.code))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RemoteFileResult.Failure(mapIo(failure))
        }
    }

    override suspend fun downloadDecrypted(
        attachment: RemoteAttachmentReference,
        output: OutputStream,
    ): RemoteDownloadResult = withConnectionDownload { connection ->
        val incomingDirectory = File(transferRoot, "incoming")
        if (!incomingDirectory.isDirectory && !incomingDirectory.mkdirs()) {
            return@withConnectionDownload RemoteDownloadResult.Failure(
                RemoteErrorCode.SERVER_UNAVAILABLE,
            )
        }
        try {
            val remote = withRelocation(connection) { endpoint ->
                inspectRemoteAttachment(endpoint, connection, attachment.remotePath)
            } ?: return@withConnectionDownload RemoteDownloadResult.Failure(
                RemoteErrorCode.NOT_FOUND,
            )
            val pending = File(
                incomingDirectory,
                "${attachment.id}-${remote.checksum}.part",
            )
            withRelocation(connection) { endpoint ->
                downloadRemoteAttachment(endpoint, connection, attachment.remotePath, remote, pending)
            }
            if (pending.length() != remote.size || envelopeCrypto.sha256(pending) != remote.checksum) {
                pending.delete()
                return@withConnectionDownload RemoteDownloadResult.Failure(
                    RemoteErrorCode.CORRUPTED_UPLOAD,
                )
            }
            val decrypted = envelopeCrypto.decryptFileVerifiedTo(
                connection.masterKey,
                connection.vaultId,
                attachment.id,
                pending,
                attachment.fileSizeBytes,
                output,
            )
            pending.delete()
            when (decrypted) {
                is RepositoryResult.Success -> RemoteDownloadResult.Downloaded
                is RepositoryResult.Failure ->
                    RemoteDownloadResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            RemoteDownloadResult.Failure(mapIo(failure))
        } catch (_: IllegalArgumentException) {
            RemoteDownloadResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
        }
    }

    private suspend fun mutationRequest(
        connection: RelayConnectionSecrets,
        operationId: String,
        path: String,
        method: String,
        body: RequestBody,
    ): RemoteMutationResult = try {
        withRelocation(connection) { endpoint ->
            execute(
                endpoint.hostAddress,
                endpoint.port,
                connection.certificateSha256,
                request(
                    endpoint.hostAddress,
                    endpoint.port,
                    connection.authenticationToken,
                    path,
                    method,
                    body,
                    operationId,
                ),
            ).use { response ->
                val wire = RelayWireCodec.mutation(response.readBounded(MAX_ITEM_RESPONSE_BYTES))
                when (wire.outcome) {
                    "APPLIED" -> {
                        if (!response.isSuccessful) {
                            RemoteMutationResult.Failure(mapStatus(response.code))
                        } else {
                            RemoteMutationResult.Applied(
                                requireNotNull(wire.serverRevision),
                                requireNotNull(wire.versionToken),
                            )
                        }
                    }
                    "CONFLICT" -> RemoteMutationResult.Conflict(
                        wire.remote?.takeUnless(RemoteItemWire::deleted)
                            ?.let { decryptRemoteItem(connection, it) },
                    )
                    else -> RemoteMutationResult.Failure(RemoteErrorCode.INVALID_REQUEST)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        RemoteMutationResult.Failure(mapIo(failure))
    } catch (_: IllegalArgumentException) {
        RemoteMutationResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
    } catch (_: GeneralSecurityException) {
        RemoteMutationResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
    }

    private suspend fun decryptRemoteItem(
        connection: RelayConnectionSecrets,
        wire: RemoteItemWire,
    ): RemoteItemVersion {
        val encrypted = Base64.decode(requireNotNull(wire.encryptedPayload), Base64.DEFAULT)
        try {
            if (envelopeCrypto.sha256(encrypted) != wire.ciphertextSha256) {
                throw GeneralSecurityException("Remote item checksum mismatch")
            }
            val plaintext = when (
                val decrypted = envelopeCrypto.decryptBytes(
                    connection.masterKey,
                    connection.vaultId,
                    wire.itemId,
                    SyncEnvelopePurpose.ITEM,
                    encrypted,
                )
            ) {
                is RepositoryResult.Success -> decrypted.value
                is RepositoryResult.Failure ->
                    throw GeneralSecurityException("Remote item authentication failed")
            }
            return try {
                RemoteItemVersion(
                    RelayWireCodec.decodeMetadata(plaintext, wire.itemId),
                    wire.serverRevision,
                    wire.versionToken,
                )
            } finally {
                plaintext.fill(0)
            }
        } finally {
            encrypted.fill(0)
        }
    }

    private suspend fun stableItemEnvelope(
        connection: RelayConnectionSecrets,
        operationId: String,
        itemId: String,
        item: com.vaultnote.core.sync.RemoteItemMetadata,
    ): RepositoryResult<ByteArray> = transferMutex.withLock {
        val destination = outgoingItemFile(connection.vaultId, operationId)
        if (destination.isFile) {
            if (destination.length() !in 1..MAX_ITEM_RESPONSE_BYTES.toLong()) {
                destination.delete()
                return@withLock RepositoryResult.Failure(AppError.CorruptedFile)
            }
            return@withLock try {
                val cached = destination.readBytes()
                val decrypted = envelopeCrypto.decryptBytes(
                    connection.masterKey,
                    connection.vaultId,
                    itemId,
                    SyncEnvelopePurpose.ITEM,
                    cached,
                )
                val expected = RelayWireCodec.encodeMetadata(item)
                val matches = try {
                    decrypted is RepositoryResult.Success &&
                        MessageDigest.isEqual(decrypted.value, expected)
                } finally {
                    if (decrypted is RepositoryResult.Success) decrypted.value.fill(0)
                    expected.fill(0)
                }
                if (matches) {
                    RepositoryResult.Success(cached)
                } else {
                    cached.fill(0)
                    destination.delete()
                    RepositoryResult.Failure(AppError.CorruptedFile)
                }
            } catch (_: IOException) {
                RepositoryResult.Failure(AppError.CorruptedFile)
            } catch (_: SecurityException) {
                RepositoryResult.Failure(AppError.PermissionDenied)
            }
        }
        val parent = destination.parentFile
            ?: return@withLock RepositoryResult.Failure(AppError.InsufficientStorage())
        if (!parent.isDirectory && !parent.mkdirs()) {
            return@withLock RepositoryResult.Failure(AppError.InsufficientStorage())
        }
        val plaintext = RelayWireCodec.encodeMetadata(item)
        val encrypted = try {
            when (
                val result = envelopeCrypto.encryptBytes(
                    connection.masterKey,
                    connection.vaultId,
                    itemId,
                    SyncEnvelopePurpose.ITEM,
                    plaintext,
                )
            ) {
                is RepositoryResult.Success -> result.value
                is RepositoryResult.Failure -> return@withLock result
            }
        } finally {
            plaintext.fill(0)
        }
        val temporary = File(parent, ".$operationId-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encrypted)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
            RepositoryResult.Success(encrypted)
        } catch (_: IOException) {
            encrypted.fill(0)
            RepositoryResult.Failure(AppError.InsufficientStorage())
        } catch (_: SecurityException) {
            encrypted.fill(0)
            RepositoryResult.Failure(AppError.PermissionDenied)
        } catch (_: UnsupportedOperationException) {
            encrypted.fill(0)
            RepositoryResult.Failure(AppError.InsufficientStorage())
        } finally {
            temporary.delete()
        }
    }

    private fun outgoingItemFile(vaultId: String, operationId: String): File =
        File(File(File(transferRoot, "outgoing-items"), vaultId), "$operationId.bin")

    private fun outgoingAttachmentFile(vaultId: String, attachmentId: String): File =
        File(File(File(transferRoot, "outgoing-attachments"), vaultId), "$attachmentId.bin")

    private suspend fun inspectRemoteAttachment(
        endpoint: Endpoint,
        connection: RelayConnectionSecrets,
        remotePath: String,
    ): RemoteAttachmentInfo? {
        return execute(
            endpoint.hostAddress,
            endpoint.port,
            connection.certificateSha256,
            request(
                endpoint.hostAddress,
                endpoint.port,
                connection.authenticationToken,
                remotePath,
                method = "HEAD",
            ),
        ).use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val size = response.header("Content-Length")?.toLongOrNull()
                ?.takeIf { it in 1..MAX_ATTACHMENT_ENVELOPE_BYTES }
                ?: throw IOException("Invalid remote attachment size")
            val checksum = response.header(CIPHERTEXT_SHA256_HEADER)
                ?.takeIf(SHA256::matches)
                ?: throw IOException("Invalid remote attachment checksum")
            RemoteAttachmentInfo(size, checksum)
        }
    }

    private suspend fun downloadRemoteAttachment(
        endpoint: Endpoint,
        connection: RelayConnectionSecrets,
        remotePath: String,
        remote: RemoteAttachmentInfo,
        pending: File,
    ) {
        if (pending.length() > remote.size) pending.delete()
        val existing = pending.length()
        if (existing == remote.size) return
        val builder = requestBuilder(
            endpoint.hostAddress,
            endpoint.port,
            connection.authenticationToken,
            remotePath,
        )
        if (existing > 0L) builder.header("Range", "bytes=$existing-")
        execute(
            endpoint.hostAddress,
            endpoint.port,
            connection.certificateSha256,
            builder.build(),
        ).use { response ->
            if (response.code !in listOf(200, 206)) throw HttpStatusException(response.code)
            val append = existing > 0L && response.code == 206
            FileOutputStream(pending, append).use { output ->
                val input = response.body.byteStream()
                val buffer = ByteArray(BUFFER_BYTES)
                var total = if (append) existing else 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > remote.size) throw IOException("Oversized remote attachment")
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    }

    private suspend fun <T> withRelocation(
        connection: RelayConnectionSecrets,
        block: suspend (Endpoint) -> T,
    ): T {
        val current = Endpoint(connection.hostAddress, connection.port)
        try {
            return block(current)
        } catch (failure: IOException) {
            if (mapIo(failure) != RemoteErrorCode.NETWORK_UNAVAILABLE) throw failure
            val discovered = discovery.discover(
                connection.vaultId,
                connection.certificateSha256,
            )
            val candidate = (discovered as? LanDiscoveryResult.Found)?.relay ?: throw failure
            val updated = Endpoint(candidate.hostAddress, candidate.port)
            if (updated != current) credentialStore.updateEndpoint(updated.hostAddress, updated.port)
            return block(updated)
        }
    }

    private suspend fun execute(
        host: String,
        port: Int,
        fingerprint: String,
        request: Request,
    ): Response {
        val client = clients.getOrPut(fingerprint) { pinnedClient(fingerprint) }
        val call = client.newCall(request)
        return call.executeCancellable()
    }

    private fun request(
        host: String,
        port: Int,
        token: String,
        pathAndQuery: String,
        method: String = "GET",
        body: RequestBody? = null,
        operationId: String? = null,
        ciphertextSha256: String? = null,
    ): Request = requestBuilder(host, port, token, pathAndQuery)
        .method(method, body)
        .apply {
            operationId?.let { header(OPERATION_HEADER, it) }
            ciphertextSha256?.let { header(CIPHERTEXT_SHA256_HEADER, it) }
        }
        .build()

    private fun requestBuilder(
        host: String,
        port: Int,
        token: String,
        pathAndQuery: String,
    ): Request.Builder {
        val base = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .port(port)
            .build()
        val url = base.resolve(pathAndQuery)
            ?: throw IllegalArgumentException("Invalid relay path")
        if (url.host != base.host || url.port != base.port || url.scheme != "https") {
            throw IllegalArgumentException("Relay path escaped endpoint")
        }
        return Request.Builder()
            .url(url)
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION.toString())
            .header("Authorization", "Bearer $token")
    }

    private fun pinnedClient(fingerprint: String): OkHttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        val hostnameVerifier = HostnameVerifier { _, session ->
            try {
                val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                certificate != null && certificateFingerprint(certificate) == fingerprint
            } catch (_: SSLPeerUnverifiedException) {
                false
            }
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.MINUTES)
            .build()
    }

    private suspend fun Call.executeCancellable(): Response {
        val job = currentCoroutineContext()[Job]
        val cancellation = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) cancel()
        }
        return try {
            execute()
        } finally {
            cancellation?.dispose()
        }
    }

    private fun Response.readBounded(maximumBytes: Int): String {
        val declared = body.contentLength()
        if (declared > maximumBytes) throw IOException("Oversized relay response")
        val input = body.byteStream()
        val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total = Math.addExact(total, read)
            if (total > maximumBytes) throw IOException("Oversized relay response")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private suspend fun <T> withLoaded(
        missing: T,
        block: suspend (RelayConnectionSecrets) -> T,
    ): T {
        val connection = when (val loaded = credentialStore.load()) {
            is RepositoryResult.Success -> loaded.value ?: return missing
            is RepositoryResult.Failure -> return missing
        }
        return try {
            withContext(dispatchers.io) { block(connection) }
        } finally {
            connection.clearKey()
        }
    }

    private suspend fun withConnectionResult(
        block: suspend (RelayConnectionSecrets) -> RemoteMutationResult,
    ): RemoteMutationResult = withLoaded(
        RemoteMutationResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED),
        block,
    )

    private suspend fun withConnectionPull(
        block: suspend (RelayConnectionSecrets) -> RemotePullResult,
    ): RemotePullResult = withLoaded(
        RemotePullResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED),
        block,
    )

    private suspend fun withConnectionFile(
        block: suspend (RelayConnectionSecrets) -> RemoteFileResult,
    ): RemoteFileResult = withLoaded(
        RemoteFileResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED),
        block,
    )

    private suspend fun withConnectionDownload(
        block: suspend (RelayConnectionSecrets) -> RemoteDownloadResult,
    ): RemoteDownloadResult = withLoaded(
        RemoteDownloadResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED),
        block,
    )

    private fun mapStatus(status: Int): RemoteErrorCode = when (status) {
        401 -> RemoteErrorCode.AUTHENTICATION_EXPIRED
        404 -> RemoteErrorCode.NOT_FOUND
        413 -> RemoteErrorCode.QUOTA_EXCEEDED
        422 -> RemoteErrorCode.CORRUPTED_UPLOAD
        426 -> RemoteErrorCode.UNSUPPORTED_PROTOCOL
        in 500..599 -> RemoteErrorCode.SERVER_UNAVAILABLE
        else -> RemoteErrorCode.INVALID_REQUEST
    }

    private fun mapIo(failure: IOException): RemoteErrorCode = when (failure) {
        is SSLPeerUnverifiedException,
        is SSLHandshakeException,
        -> RemoteErrorCode.INVALID_REQUEST
        is HttpStatusException -> mapStatus(failure.status)
        else -> RemoteErrorCode.NETWORK_UNAVAILABLE
    }

    private fun String.jsonBody(): RequestBody = toRequestBody(JSON_MEDIA_TYPE)

    private fun RepositoryResult<com.vaultnote.core.encryption.EncryptionEnvelopeInfo>.toUnitResult():
        RepositoryResult<Unit> = when (this) {
        is RepositoryResult.Success -> RepositoryResult.Success(Unit)
        is RepositoryResult.Failure -> this
    }

    private fun ProvisionalRelayAccess.isValid(): Boolean =
        hostAddress.length in 1..255 &&
            port in 1..65_535 &&
            SHA256.matches(certificateSha256) &&
            authenticationToken.startsWith("vns_") &&
            authenticationToken.length in 16..128 &&
            (expectedVaultId == null || safeId(expectedVaultId))

    private fun safeId(value: String): Boolean =
        value.length in 1..128 && SAFE_ID.matches(value)

    private data class Endpoint(val hostAddress: String, val port: Int)
    private data class RemoteAttachmentInfo(val size: Long, val checksum: String)
    private class HttpStatusException(val status: Int) : IOException()

    @SuppressLint("CustomX509TrustManager")
    private class FingerprintTrustManager(
        private val expectedFingerprint: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("Missing relay certificate")
            certificate.checkValidity()
            if (certificateFingerprint(certificate) != expectedFingerprint) {
                throw CertificateException("Relay certificate fingerprint mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val PROTOCOL_VERSION = 3
        private const val PROTOCOL_HEADER = "X-VaultNote-Protocol"
        private const val OPERATION_HEADER = "X-VaultNote-Operation-Id"
        private const val CIPHERTEXT_SHA256_HEADER = "X-VaultNote-Ciphertext-SHA256"
        private const val ATTACHMENT_PATH_PREFIX = "/v1/attachments/"
        private const val MAX_CONTROL_BYTES = 64 * 1024
        private const val MAX_ITEM_RESPONSE_BYTES = 3 * 1024 * 1024
        private const val MAX_CHANGE_PAGE_BYTES = 64 * 1024 * 1024
        private const val MAX_PLAINTEXT_ATTACHMENT_BYTES = 100L * 1024L * 1024L
        private const val MAX_ATTACHMENT_ENVELOPE_BYTES = 110L * 1024L * 1024L
        private const val BUFFER_BYTES = 64 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val DISCARD_OUTPUT = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
        }
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private val SHA256 = Regex("[0-9a-f]{64}")

        private fun certificateFingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }
}
