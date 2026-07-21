package com.vaultnote.core.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

class SecureAttachmentProvider : ContentProvider() {
    private lateinit var authority: String
    private lateinit var matcher: UriMatcher
    private var executor: ExecutorService? = null

    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context).applicationContext
        authority = "${appContext.packageName}.${SecureAttachmentUriFactory.AUTHORITY_SUFFIX}"
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "${SecureAttachmentUriFactory.ATTACHMENT_PATH}/*", MATCH_ATTACHMENT)
            addURI(authority, "${SecureAttachmentUriFactory.THUMBNAIL_PATH}/*", MATCH_THUMBNAIL)
        }
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only content")
        val (attachmentId, purpose) = parse(uri)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val reader = pipe[0]
        val writer = pipe[1]
        executor().execute {
            val output = ParcelFileDescriptor.AutoCloseOutputStream(writer)
            try {
                val source = requireNotNull(context).appContainer().secureAttachmentContentSource
                val result = runBlocking {
                    source.writeVerifiedContent(
                        attachmentId = attachmentId,
                        purpose = purpose,
                        externalAccessToken = uri.getQueryParameter(
                            SecureAttachmentUriFactory.ACCESS_TOKEN_PARAMETER,
                        ),
                        output = output,
                    )
                }
                when (result) {
                    is RepositoryResult.Success -> output.close()
                    is RepositoryResult.Failure -> writer.closeWithError("Content unavailable")
                }
            } catch (_: Exception) {
                runCatching { writer.closeWithError("Content unavailable") }
            }
        }
        return reader
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        MATCH_ATTACHMENT,
        MATCH_THUMBNAIL,
        -> GENERIC_MIME_TYPE
        else -> null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        parse(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val supported = requested.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        return MatrixCursor(supported.toTypedArray(), 1).apply {
            addRow(
                supported.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> GENERIC_DISPLAY_NAME
                        OpenableColumns.SIZE -> null
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Read-only content")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only content")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read-only content")

    override fun shutdown() {
        executor?.shutdownNow()
        executor = null
        super.shutdown()
    }

    private fun parse(uri: Uri): Pair<String, EncryptedFilePurpose> {
        if (uri.authority != authority) throw FileNotFoundException("Unknown content")
        val id = uri.lastPathSegment
            ?.takeIf(SecureAttachmentUriFactory.SAFE_ID::matches)
            ?: throw FileNotFoundException("Unknown content")
        val purpose = when (matcher.match(uri)) {
            MATCH_ATTACHMENT -> EncryptedFilePurpose.ATTACHMENT
            MATCH_THUMBNAIL -> EncryptedFilePurpose.THUMBNAIL
            else -> throw FileNotFoundException("Unknown content")
        }
        return id to purpose
    }

    @Synchronized
    private fun executor(): ExecutorService = executor ?: Executors.newFixedThreadPool(
        MAX_PARALLEL_STREAMS,
    ).also { executor = it }

    private companion object {
        const val MATCH_ATTACHMENT = 1
        const val MATCH_THUMBNAIL = 2
        const val MAX_PARALLEL_STREAMS = 2
        const val GENERIC_MIME_TYPE = "application/octet-stream"
        const val GENERIC_DISPLAY_NAME = "VaultNote attachment"
    }
}
