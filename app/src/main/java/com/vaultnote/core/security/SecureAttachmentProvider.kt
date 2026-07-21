package com.vaultnote.core.security

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import com.vaultnote.app.appContainer
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.encryption.EncryptedFilePurpose
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Exposes authenticated attachment plaintext through short-lived content grants.
 *
 * External viewers often require a seekable descriptor and may open it more than once. Content is
 * therefore authenticated and streamed into a private cache file, opened read-only, then unlinked
 * immediately. The returned Linux file descriptor remains seekable without leaving a named
 * plaintext file behind.
 */
class SecureAttachmentProvider : ContentProvider() {
    private lateinit var authority: String
    private lateinit var matcher: UriMatcher
    internal var contentSourceOverride: SecureAttachmentContentSource? = null
    internal var availableSpaceBytes: (File) -> Long = { directory ->
        StatFs(directory.path).availableBytes
    }

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
        if (mode != READ_MODE) throw FileNotFoundException("Read-only content")
        val request = parse(uri)
        val metadata = metadata(request)
            ?: throw FileNotFoundException("Content unavailable")
        val cacheDirectory = prepareCacheDirectory()
        ensureCapacity(cacheDirectory, metadata.sizeBytes)
        val temporary = try {
            File.createTempFile(HANDOFF_FILE_PREFIX, HANDOFF_FILE_SUFFIX, cacheDirectory)
        } catch (_: IOException) {
            throw FileNotFoundException("Content unavailable")
        } catch (_: SecurityException) {
            throw FileNotFoundException("Content unavailable")
        }

        var descriptor: ParcelFileDescriptor? = null
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val bufferedOutput = BufferedOutputStream(fileOutput, BUFFER_SIZE_BYTES)
                val result = runBlocking {
                    contentSource().writeVerifiedContent(
                        attachmentId = request.attachmentId,
                        purpose = request.purpose,
                        externalAccessToken = request.accessToken,
                        output = bufferedOutput,
                    )
                }
                if (result is RepositoryResult.Failure) {
                    throw FileNotFoundException("Content unavailable")
                }
                bufferedOutput.flush()
                fileOutput.fd.sync()
            }
            if (metadata.sizeBytes != null && temporary.length() != metadata.sizeBytes) {
                throw FileNotFoundException("Content unavailable")
            }
            descriptor = ParcelFileDescriptor.open(
                temporary,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            if (!temporary.delete()) {
                descriptor.close()
                descriptor = null
                throw FileNotFoundException("Content unavailable")
            }
            return descriptor
        } catch (failure: FileNotFoundException) {
            throw failure
        } catch (_: Exception) {
            throw FileNotFoundException("Content unavailable")
        } finally {
            if (descriptor == null) temporary.delete()
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        val metadata = metadata(parse(uri))
            ?: throw FileNotFoundException("Content unavailable")
        return AssetFileDescriptor(
            openFile(uri, mode),
            0L,
            metadata.sizeBytes ?: AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getType(uri: Uri): String? = runCatching {
        metadata(parse(uri))?.mimeType
    }.getOrNull()

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val request = parse(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val supported = requested.filter { column ->
            column == OpenableColumns.DISPLAY_NAME || column == OpenableColumns.SIZE
        }
        val cursor = MatrixCursor(supported.toTypedArray(), 1)
        val metadata = metadata(request) ?: return cursor
        cursor.addRow(
            supported.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> metadata.displayName
                    OpenableColumns.SIZE -> metadata.sizeBytes
                    else -> null
                }
            },
        )
        return cursor
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

    private fun metadata(request: Request): SecureAttachmentMetadata? = when (
        val result = runBlocking {
            contentSource().getMetadata(
                attachmentId = request.attachmentId,
                purpose = request.purpose,
                externalAccessToken = request.accessToken,
            )
        }
    ) {
        is RepositoryResult.Success -> result.value
        is RepositoryResult.Failure -> null
    }

    private fun contentSource(): SecureAttachmentContentSource =
        contentSourceOverride
            ?: requireNotNull(context).appContainer().secureAttachmentContentSource

    private fun parse(uri: Uri): Request {
        if (uri.authority != authority) throw FileNotFoundException("Unknown content")
        val id = uri.lastPathSegment
            ?.takeIf(SecureAttachmentUriFactory.SAFE_ID::matches)
            ?: throw FileNotFoundException("Unknown content")
        val purpose = when (matcher.match(uri)) {
            MATCH_ATTACHMENT -> EncryptedFilePurpose.ATTACHMENT
            MATCH_THUMBNAIL -> EncryptedFilePurpose.THUMBNAIL
            else -> throw FileNotFoundException("Unknown content")
        }
        return Request(
            attachmentId = id,
            purpose = purpose,
            accessToken = uri.getQueryParameter(
                SecureAttachmentUriFactory.ACCESS_TOKEN_PARAMETER,
            ),
        )
    }

    private fun prepareCacheDirectory(): File {
        val directory = File(requireNotNull(context).cacheDir, HANDOFF_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw FileNotFoundException("Content unavailable")
        }
        val cutoff = System.currentTimeMillis() - ABANDONED_FILE_AGE_MILLIS
        directory.listFiles()?.forEach { candidate ->
            if (candidate.isFile && candidate.lastModified() <= cutoff) candidate.delete()
        }
        return directory
    }

    private fun ensureCapacity(directory: File, expectedSize: Long?) {
        if (expectedSize == null) return
        if (expectedSize < 0L) throw FileNotFoundException("Content unavailable")
        val available = try {
            availableSpaceBytes(directory)
        } catch (_: IllegalArgumentException) {
            throw FileNotFoundException("Content unavailable")
        }
        if (
            available < MINIMUM_FREE_SPACE_RESERVE_BYTES ||
            expectedSize > available - MINIMUM_FREE_SPACE_RESERVE_BYTES
        ) {
            throw FileNotFoundException("Content unavailable")
        }
    }

    private data class Request(
        val attachmentId: String,
        val purpose: EncryptedFilePurpose,
        val accessToken: String?,
    )

    private companion object {
        const val MATCH_ATTACHMENT = 1
        const val MATCH_THUMBNAIL = 2
        const val READ_MODE = "r"
        const val BUFFER_SIZE_BYTES = 64 * 1024
        const val HANDOFF_DIRECTORY = "secure-handoff"
        const val HANDOFF_FILE_PREFIX = ".handoff-"
        const val HANDOFF_FILE_SUFFIX = ".tmp"
        const val ABANDONED_FILE_AGE_MILLIS = 10 * 60 * 1_000L
        const val MINIMUM_FREE_SPACE_RESERVE_BYTES = 16L * 1024L * 1024L
    }
}
