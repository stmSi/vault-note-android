package com.vaultnote.feature.importing

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.files.MAX_ATTACHMENTS_PER_IMPORT
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class ImportSourceKind {
    EXTERNAL,
    CAMERA_CAPTURE,
}

data class ImportSource(
    val uri: Uri,
    val kind: ImportSourceKind,
    val temporaryFile: File? = null,
    val captureId: String? = null,
)

data class IncomingImport(
    val sharedText: String?,
    val sources: List<ImportSource>,
)

internal sealed interface IncomingImportParseResult {
    data object NotAnImport : IncomingImportParseResult
    data object Empty : IncomingImportParseResult
    data object TooManyFiles : IncomingImportParseResult
    data object UnsupportedUri : IncomingImportParseResult
    data object TextTooLarge : IncomingImportParseResult
    data class Accepted(val incomingImport: IncomingImport) : IncomingImportParseResult
}

/**
 * Keeps grants and potentially sensitive shared text in memory instead of saved instance state.
 * The activity owns this coordinator, so a pending import cannot outlive the task that received it.
 */
internal class IncomingImportCoordinator : ViewModel() {
    private val nextToken = AtomicLong(1L)
    private val pending = LinkedHashMap<Long, IncomingImport>()
    private var deferred: IncomingImport? = null

    @Synchronized
    fun offer(incomingImport: IncomingImport): Long {
        val token = nextToken.getAndIncrement()
        pending[token] = incomingImport
        return token
    }

    @Synchronized
    fun take(token: Long): IncomingImport? = pending.remove(token)

    @Synchronized
    fun deferUntilUnlock(incomingImport: IncomingImport) {
        deferred = incomingImport
    }

    @Synchronized
    fun takeDeferred(): IncomingImport? = deferred.also { deferred = null }

    @Synchronized
    fun discardAll(): List<IncomingImport> {
        val discarded = buildList {
            addAll(pending.values)
            deferred?.let(::add)
        }
        pending.clear()
        deferred = null
        return discarded
    }
}

internal object IncomingImportParser {
    fun parse(intent: Intent): IncomingImportParseResult {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            return IncomingImportParseResult.NotAnImport
        }

        return try {
            parsePayload(intent)
        } catch (_: BadParcelableException) {
            IncomingImportParseResult.UnsupportedUri
        } catch (_: ClassCastException) {
            IncomingImportParseResult.UnsupportedUri
        } catch (_: IllegalArgumentException) {
            IncomingImportParseResult.UnsupportedUri
        }
    }

    private fun parsePayload(intent: Intent): IncomingImportParseResult {
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.takeIf(String::isNotBlank)
        if (
            sharedText != null &&
            sharedText.codePointCount(0, sharedText.length) > VaultConstraints.MAX_NOTE_BODY_CHARACTERS
        ) {
            return IncomingImportParseResult.TextTooLarge
        }

        val clipData = intent.clipData
        if (clipData != null && clipData.itemCount > MAX_ATTACHMENTS_PER_IMPORT) {
            return IncomingImportParseResult.TooManyFiles
        }
        val uris = ArrayList<Uri>(MAX_ATTACHMENTS_PER_IMPORT)
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }

        if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(uris::add)
        } else {
            val streams = IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )
            if (streams != null && streams.size > MAX_ATTACHMENTS_PER_IMPORT) {
                return IncomingImportParseResult.TooManyFiles
            }
            streams?.let(uris::addAll)
        }
        if (uris.size > MAX_ATTACHMENTS_PER_IMPORT) return IncomingImportParseResult.TooManyFiles
        val distinctUris = uris.distinctBy(Uri::toString)

        if (distinctUris.any { it.scheme != "content" }) return IncomingImportParseResult.UnsupportedUri
        if (sharedText == null && distinctUris.isEmpty()) return IncomingImportParseResult.Empty

        return IncomingImportParseResult.Accepted(
            IncomingImport(
                sharedText = sharedText,
                sources = distinctUris.map { uri ->
                    ImportSource(uri = uri, kind = ImportSourceKind.EXTERNAL)
                },
            ),
        )
    }
}
