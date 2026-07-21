package com.vaultnote.core.sync

import com.vaultnote.core.common.DispatcherProvider
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Deterministic replaceable backend used until a production service is configured.
 * It retains no attachment bytes and never writes a second plaintext note database to disk.
 */
class InMemoryFakeSyncBackend(
    private val dispatchers: DispatcherProvider,
) : SyncApi, AuthProvider, RemoteFileStore {
    private data class StoredFile(
        val attachmentId: String,
        val plaintextSha256: String,
        val encryptedSha256: String,
        val byteCount: Long,
    )

    private val mutex = Mutex()
    private val items = linkedMapOf<String, RemoteItemVersion>()
    private val files = linkedMapOf<String, StoredFile>()
    private val idempotentMutations = BoundedLinkedHashMap<String, RemoteMutationResult>(
        MAX_IDEMPOTENCY_RECORDS,
    )
    private val idempotentFiles = BoundedLinkedHashMap<String, RemoteFileResult>(
        MAX_IDEMPOTENCY_RECORDS,
    )
    private val changes = mutableListOf<RemoteChange>()
    private var nextServerRevision = 1L
    @Volatile private var authenticationState = AuthenticationState.AUTHENTICATED

    override suspend fun authenticationState(): AuthenticationState = authenticationState

    fun setAuthenticationState(state: AuthenticationState) {
        authenticationState = state
    }

    override suspend fun upsertItem(
        operationId: String,
        item: RemoteItemMetadata,
        expectedVersionToken: String?,
    ): RemoteMutationResult = mutex.withLock {
        idempotentMutations[operationId]?.let { return@withLock it }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            return@withLock RemoteMutationResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED)
        }
        val current = items[item.id]
        if (current != null && current.versionToken != expectedVersionToken) {
            return@withLock RemoteMutationResult.Conflict(current)
        }
        val revision = allocateRevision()
        val version = RemoteItemVersion(item, revision, token(revision))
        items[item.id] = version
        changes += RemoteChange.Upsert(version)
        trimChangeHistory()
        RemoteMutationResult.Applied(revision, version.versionToken).also {
            idempotentMutations[operationId] = it
        }
    }

    override suspend fun deleteItem(
        operationId: String,
        itemId: String,
        expectedVersionToken: String?,
    ): RemoteMutationResult = mutex.withLock {
        idempotentMutations[operationId]?.let { return@withLock it }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            return@withLock RemoteMutationResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED)
        }
        val current = items[itemId]
        if (current == null) {
            return@withLock RemoteMutationResult.Applied(0L, "deleted:0").also {
                idempotentMutations[operationId] = it
            }
        }
        if (current?.versionToken != expectedVersionToken) {
            return@withLock RemoteMutationResult.Conflict(current)
        }
        val revision = allocateRevision()
        val versionToken = token(revision)
        items.remove(itemId)
        changes += RemoteChange.Delete(itemId, revision, versionToken)
        trimChangeHistory()
        RemoteMutationResult.Applied(revision, versionToken).also {
            idempotentMutations[operationId] = it
        }
    }

    override suspend fun pullChanges(cursor: String?, limit: Int): RemotePullResult = mutex.withLock {
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            return@withLock RemotePullResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED)
        }
        val afterRevision = cursor?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val boundedLimit = limit.coerceIn(1, 200)
        val page = changes.asSequence()
            .filter { it.serverRevision > afterRevision }
            .sortedBy(RemoteChange::serverRevision)
            .take(boundedLimit)
            .toList()
        val nextCursor = page.lastOrNull()?.serverRevision?.toString() ?: cursor
        val hasMore = changes.any { it.serverRevision > (nextCursor?.toLongOrNull() ?: 0L) }
        RemotePullResult.Success(RemoteChangePage(page, nextCursor, hasMore))
    }

    override suspend fun uploadEncrypted(
        operationId: String,
        attachmentId: String,
        plaintextSha256: String,
        source: File,
    ): RemoteFileResult {
        mutex.withLock { idempotentFiles[operationId] }?.let { return it }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            return RemoteFileResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED)
        }
        val inspected = inspectEncryptedFile(source)
            ?: return RemoteFileResult.Failure(RemoteErrorCode.CORRUPTED_UPLOAD)
        val remotePath = "attachments/$attachmentId.bin"
        return mutex.withLock {
            idempotentFiles[operationId]?.let { return@withLock it }
            files[remotePath] = StoredFile(
                attachmentId = attachmentId,
                plaintextSha256 = plaintextSha256,
                encryptedSha256 = inspected.first,
                byteCount = inspected.second,
            )
            RemoteFileResult.Uploaded(remotePath).also { idempotentFiles[operationId] = it }
        }
    }

    override suspend fun verifyUpload(
        remotePath: String,
        plaintextSha256: String,
    ): Boolean = mutex.withLock {
        files[remotePath]?.let { stored ->
            stored.plaintextSha256 == plaintextSha256 &&
                stored.byteCount > 0L &&
                stored.encryptedSha256.length == SHA256_HEX_LENGTH
        } == true
    }

    override suspend fun delete(
        operationId: String,
        attachmentId: String,
    ): RemoteFileResult = mutex.withLock {
        idempotentFiles[operationId]?.let { return@withLock it }
        if (authenticationState != AuthenticationState.AUTHENTICATED) {
            return@withLock RemoteFileResult.Failure(RemoteErrorCode.AUTHENTICATION_EXPIRED)
        }
        files.entries.removeAll { it.value.attachmentId == attachmentId }
        RemoteFileResult.Deleted.also { idempotentFiles[operationId] = it }
    }

    private suspend fun inspectEncryptedFile(source: File): Pair<String, Long>? =
        withContext(dispatchers.io) {
            if (!source.isFile) return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileInputStream(source).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            digest.digest().joinToString(separator = "") { "%02x".format(it) } to total
        }

    private fun allocateRevision(): Long = nextServerRevision++

    private fun token(revision: Long): String = "fake-v$revision"

    private fun trimChangeHistory() {
        val excess = changes.size - MAX_CHANGE_RECORDS
        if (excess > 0) changes.subList(0, excess).clear()
    }

    private class BoundedLinkedHashMap<K, V>(
        private val maximumEntries: Int,
    ) : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maximumEntries
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        const val MAX_IDEMPOTENCY_RECORDS = 2_048
        const val MAX_CHANGE_RECORDS = 4_096
    }
}
