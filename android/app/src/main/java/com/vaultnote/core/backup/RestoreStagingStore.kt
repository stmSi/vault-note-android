package com.vaultnote.core.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.vaultnote.core.common.IdGenerator
import com.vaultnote.core.common.RepositoryResult
import com.vaultnote.core.common.VaultConstraints
import com.vaultnote.core.common.model.AttachmentUploadStatus
import com.vaultnote.core.common.model.ItemSyncStatus
import com.vaultnote.core.common.model.OcrState
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.database.dao.AttachmentDao
import com.vaultnote.core.database.dao.TagDao
import com.vaultnote.core.database.dao.VaultItemDao
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION
import com.vaultnote.core.files.FilenameSanitizer
import com.vaultnote.core.files.MAX_ATTACHMENT_BYTES
import java.io.Closeable
import java.io.File
import java.text.Normalizer
import java.util.Locale

internal data class RestoreAttachmentPlan(
    val originalId: String,
    val finalId: String,
    val entryPath: String,
    val filename: String,
    val mimeType: String,
    val fileSize: Long,
    val sha256: String,
    val pendingFile: String?,
    val destinationFile: String?,
    val relativePath: String?,
)

internal data class RestoreMappingStats(val copiedItems: Long)

/** Private, bounded-memory SQLite staging area populated only after backup authentication. */
internal class RestoreStagingStore private constructor(
    val directory: File,
    private val sqlite: SQLiteDatabase,
) : BackupDatabaseSink, Closeable {
    private var expectedItems = -1L
    private var expectedAttachments = -1L
    private var actualItems = 0L
    private var actualAttachments = 0L
    private var finished = false
    private var closed = false

    override fun begin(expectedItems: Long, expectedAttachments: Long) {
        check(this.expectedItems < 0L)
        require(expectedItems in 0..BackupFormat.MAX_ITEM_COUNT)
        require(expectedAttachments in 0..BackupFormat.MAX_ATTACHMENT_COUNT)
        this.expectedItems = expectedItems
        this.expectedAttachments = expectedAttachments
        sqlite.beginTransaction()
    }

    override fun acceptItem(item: VaultItemEntity) {
        check(!finished)
        validateItem(item)
        sqlite.insertOrThrow(
            ITEMS,
            null,
            ContentValues().apply {
                put("original_id", item.id)
                put("type", item.type.name)
                put("color", item.color.name)
                put("title", item.title)
                put("body", item.body)
                put("ocr_text", item.ocrText)
                put("is_pinned", item.isPinned)
                put("is_favorite", item.isFavorite)
                put("is_archived", item.isArchived)
                put("sort_position", item.sortPosition)
                put("created_at", item.createdAt)
                put("updated_at", item.updatedAt)
                put("local_revision", item.localRevision)
                putNullable("deleted_at", item.deletedAt)
                putNullable("conflict_origin_id", item.conflictOriginId)
            },
        )
        actualItems += 1L
        require(actualItems <= expectedItems)
    }

    override fun acceptTag(tag: TagEntity) {
        check(!finished)
        validateTag(tag)
        sqlite.insertOrThrow(
            TAGS,
            null,
            ContentValues().apply {
                put("original_id", tag.id)
                put("name", tag.name)
                put("normalized_name", tag.normalizedName)
                put("created_at", tag.createdAt)
            },
        )
    }

    override fun acceptItemTag(reference: ItemTagCrossRef) {
        check(!finished)
        requireSafeId(reference.itemId)
        requireSafeId(reference.tagId)
        sqlite.insertOrThrow(
            ITEM_TAGS,
            null,
            ContentValues().apply {
                put("item_id", reference.itemId)
                put("tag_id", reference.tagId)
            },
        )
    }

    override fun acceptAttachment(attachment: AttachmentEntity, entryPath: String) {
        check(!finished)
        validateAttachment(attachment, entryPath)
        sqlite.insertOrThrow(
            ATTACHMENTS,
            null,
            ContentValues().apply {
                put("original_id", attachment.id)
                put("parent_item_id", attachment.parentItemId)
                put("filename", attachment.originalFilename)
                put("mime_type", attachment.mimeType)
                put("file_size", attachment.fileSize)
                putNullable("image_width", attachment.imageWidth)
                putNullable("image_height", attachment.imageHeight)
                putNullable("pdf_page_count", attachment.pdfPageCount)
                put("sha256", attachment.sha256Checksum)
                put("created_at", attachment.createdAt)
                put("ocr_state", attachment.ocrState.name)
                put("ocr_text", attachment.extractedOcrText)
                putNullable("ocr_source_checksum", attachment.ocrSourceChecksum)
                putNullable("ocr_failure_code", attachment.ocrFailureCode)
                putNullable("ocr_updated_at", attachment.ocrUpdatedAt)
                put("content_entry", entryPath)
            },
        )
        actualAttachments += 1L
        require(actualAttachments <= expectedAttachments)
    }

    override fun finish() {
        check(!finished)
        try {
            require(actualItems == expectedItems)
            require(actualAttachments == expectedAttachments)
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                require(!cursor.moveToFirst())
            }
            sqlite.setTransactionSuccessful()
            finished = true
        } finally {
            sqlite.endTransaction()
        }
    }

    suspend fun planMappings(
        itemDao: VaultItemDao,
        tagDao: TagDao,
        attachmentDao: AttachmentDao,
        idGenerator: IdGenerator,
    ): RestoreMappingStats {
        check(finished)
        var copiedItems = 0L
        query("SELECT original_id FROM $ITEMS ORDER BY original_id").use { cursor ->
            while (cursor.moveToNext()) {
                val originalId = cursor.getString(0)
                val collision = itemDao.getById(originalId) != null
                val finalId = if (collision) {
                    copiedItems += 1L
                    uniqueItemId(itemDao, idGenerator)
                } else {
                    originalId
                }
                updateFinalId(ITEMS, originalId, finalId)
            }
        }
        query("SELECT original_id, normalized_name FROM $TAGS ORDER BY original_id").use { cursor ->
            while (cursor.moveToNext()) {
                val originalId = cursor.getString(0)
                val normalized = cursor.getString(1)
                val existing = tagDao.getByNormalizedNames(listOf(normalized)).singleOrNull()
                val finalId = when {
                    existing != null -> existing.id.also(::markLiveTagId)
                    tagIdInUse(originalId) || tagDao.getById(originalId) != null ->
                        uniqueTagId(tagDao, idGenerator)
                    else -> originalId
                }
                updateFinalId(TAGS, originalId, finalId)
            }
        }
        query("SELECT original_id FROM $ATTACHMENTS ORDER BY original_id").use { cursor ->
            while (cursor.moveToNext()) {
                val originalId = cursor.getString(0)
                val finalId = if (attachmentDao.getById(originalId) == null) {
                    originalId
                } else {
                    uniqueAttachmentId(attachmentDao, idGenerator)
                }
                updateFinalId(ATTACHMENTS, originalId, finalId)
            }
        }
        return RestoreMappingStats(copiedItems)
    }

    fun attachmentPlansPage(afterOriginalId: String, limit: Int): List<RestoreAttachmentPlan> {
        check(finished)
        return query(
            """
            SELECT original_id, final_id, content_entry, filename, mime_type, file_size, sha256,
                   pending_file, destination_file, local_path
            FROM $ATTACHMENTS
            WHERE original_id > ?
            ORDER BY original_id
            LIMIT ?
            """.trimIndent(),
            arrayOf(afterOriginalId, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        RestoreAttachmentPlan(
                            originalId = cursor.requiredString(0),
                            finalId = cursor.requiredString(1),
                            entryPath = cursor.requiredString(2),
                            filename = cursor.requiredString(3),
                            mimeType = cursor.requiredString(4),
                            fileSize = cursor.getLong(5),
                            sha256 = cursor.requiredString(6),
                            pendingFile = cursor.optionalString(7),
                            destinationFile = cursor.optionalString(8),
                            relativePath = cursor.optionalString(9),
                        ),
                    )
                }
            }
        }
    }

    fun setAttachmentLocalPath(originalId: String, relativePath: String) {
        requireSafeId(originalId)
        require(relativePath.isNotBlank())
        val updated = sqlite.update(
            ATTACHMENTS,
            ContentValues().apply { put("local_path", relativePath) },
            "original_id = ?",
            arrayOf(originalId),
        )
        check(updated == 1)
    }

    fun setAttachmentStage(
        originalId: String,
        pendingFile: File,
        destinationFile: File,
        relativePath: String,
    ) {
        requireSafeId(originalId)
        val updated = sqlite.update(
            ATTACHMENTS,
            ContentValues().apply {
                put("pending_file", pendingFile.absolutePath)
                put("destination_file", destinationFile.absolutePath)
                put("local_path", relativePath)
            },
            "original_id = ?",
            arrayOf(originalId),
        )
        check(updated == 1)
    }

    fun addArchiveEntry(entry: BackupEntryChecksum) {
        sqlite.insertOrThrow(
            ARCHIVE_ENTRIES,
            null,
            ContentValues().apply {
                put("path", entry.path)
                put("ciphertext_size", entry.ciphertextSize)
                put("ciphertext_sha256", entry.ciphertextSha256)
            },
        )
    }

    fun archiveEntry(path: String): BackupEntryChecksum? = query(
        """
        SELECT path, ciphertext_size, ciphertext_sha256
        FROM $ARCHIVE_ENTRIES WHERE path = ? LIMIT 1
        """.trimIndent(),
        arrayOf(path),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else BackupEntryChecksum(
            path = cursor.requiredString(0),
            ciphertextSize = cursor.getLong(1),
            ciphertextSha256 = cursor.requiredString(2),
        )
    }

    fun archiveEntryCount(): Long = query("SELECT COUNT(*) FROM $ARCHIVE_ENTRIES").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    fun readItemsPage(
        afterOriginalId: String,
        limit: Int,
    ): List<Pair<String, VaultItemEntity>> = query(
        """
        SELECT original_id, final_id, type, color, title, body, ocr_text,
               is_pinned, is_favorite, is_archived, sort_position, created_at, updated_at,
               local_revision, deleted_at, conflict_origin_id
        FROM $ITEMS
        WHERE original_id > ?
        ORDER BY original_id
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterOriginalId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val mappedConflict = cursor.optionalString(15)?.let(::mappedItemId)
                add(
                    cursor.requiredString(0) to VaultItemEntity(
                        id = cursor.requiredString(1),
                        type = enumValueOf<VaultItemType>(cursor.requiredString(2)),
                        color = enumValueOf<VaultItemColor>(cursor.requiredString(3)),
                        title = cursor.requiredString(4),
                        body = cursor.requiredString(5),
                        ocrText = cursor.requiredString(6),
                        isPinned = cursor.getInt(7) != 0,
                        isFavorite = cursor.getInt(8) != 0,
                        isArchived = cursor.getInt(9) != 0,
                        sortPosition = cursor.getLong(10),
                        createdAt = cursor.getLong(11),
                        updatedAt = cursor.getLong(12),
                        localRevision = cursor.getLong(13).coerceAtLeast(1L),
                        remoteRevision = null,
                        lastSyncedRevision = null,
                        serverVersionToken = null,
                        syncStatus = ItemSyncStatus.PENDING,
                        deletedAt = cursor.optionalLong(14),
                        conflictOriginId = mappedConflict,
                    ),
                )
            }
        }
    }

    fun readNewTagsPage(afterOriginalId: String, limit: Int): List<Pair<String, TagEntity>> = query(
        """
        SELECT original_id, final_id, name, normalized_name, created_at
        FROM $TAGS
        WHERE final_id NOT IN (SELECT id FROM live_tag_ids)
          AND original_id > ?
        ORDER BY original_id
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterOriginalId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    cursor.requiredString(0) to TagEntity(
                        id = cursor.requiredString(1),
                        name = cursor.requiredString(2),
                        normalizedName = cursor.requiredString(3),
                        createdAt = cursor.getLong(4),
                    ),
                )
            }
        }
    }

    fun markLiveTagId(id: String) {
        sqlite.insertWithOnConflict(
            LIVE_TAG_IDS,
            null,
            ContentValues().apply { put("id", id) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun readItemTagsPage(
        afterItemId: String,
        afterTagId: String,
        limit: Int,
    ): List<Triple<String, String, ItemTagCrossRef>> = query(
        """
        SELECT refs.item_id, refs.tag_id, items.final_id, tags.final_id
        FROM $ITEM_TAGS refs
        JOIN $ITEMS items ON items.original_id = refs.item_id
        JOIN $TAGS tags ON tags.original_id = refs.tag_id
        WHERE refs.item_id > ? OR (refs.item_id = ? AND refs.tag_id > ?)
        ORDER BY refs.item_id, refs.tag_id
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterItemId, afterItemId, afterTagId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    Triple(
                        cursor.requiredString(0),
                        cursor.requiredString(1),
                        ItemTagCrossRef(cursor.requiredString(2), cursor.requiredString(3)),
                    ),
                )
            }
        }
    }

    fun readAttachmentsPage(
        afterOriginalId: String,
        limit: Int,
    ): List<Pair<String, AttachmentEntity>> = query(
        """
        SELECT attachments.original_id, attachments.final_id, items.final_id,
               attachments.filename, attachments.mime_type, attachments.file_size,
               attachments.image_width, attachments.image_height,
               attachments.pdf_page_count, attachments.sha256, attachments.local_path,
               attachments.created_at, attachments.ocr_state, attachments.ocr_text,
               attachments.ocr_source_checksum, attachments.ocr_failure_code,
               attachments.ocr_updated_at
        FROM $ATTACHMENTS attachments
        JOIN $ITEMS items ON items.original_id = attachments.parent_item_id
        WHERE attachments.original_id > ?
        ORDER BY attachments.original_id
        LIMIT ?
        """.trimIndent(),
        arrayOf(afterOriginalId, limit.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    cursor.requiredString(0) to AttachmentEntity(
                        id = cursor.requiredString(1),
                        parentItemId = cursor.requiredString(2),
                        originalFilename = cursor.requiredString(3),
                        mimeType = cursor.requiredString(4),
                        fileSize = cursor.getLong(5),
                        imageWidth = cursor.optionalInt(6),
                        imageHeight = cursor.optionalInt(7),
                        pdfPageCount = cursor.optionalInt(8),
                        sha256Checksum = cursor.requiredString(9),
                        localEncryptedPath = cursor.requiredString(10),
                        remotePath = null,
                        thumbnailPath = null,
                        encryptionFormatVersion = CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                        uploadStatus = AttachmentUploadStatus.PENDING,
                        createdAt = cursor.getLong(11),
                        ocrState = enumValueOf<OcrState>(cursor.requiredString(12)).let { state ->
                            if (state == OcrState.PROCESSING) OcrState.PENDING else state
                        },
                        extractedOcrText = cursor.requiredString(13),
                        ocrSourceChecksum = cursor.optionalString(14),
                        ocrFailureCode = cursor.optionalString(15),
                        ocrUpdatedAt = cursor.optionalLong(16),
                    ),
                )
            }
        }
    }

    fun counts(): Pair<Long, Long> = actualItems to actualAttachments

    fun isReadyForCommit(): Boolean = finished && !closed

    override fun close() {
        if (!closed) {
            sqlite.close()
            closed = true
        }
    }

    fun delete() {
        close()
        directory.deleteRecursively()
    }

    private suspend fun uniqueItemId(dao: VaultItemDao, ids: IdGenerator): String =
        uniqueId(ids) { candidate -> dao.getById(candidate) != null || finalIdInUse(ITEMS, candidate) }

    private suspend fun uniqueAttachmentId(dao: AttachmentDao, ids: IdGenerator): String =
        uniqueId(ids) { candidate ->
            dao.getById(candidate) != null || finalIdInUse(ATTACHMENTS, candidate)
        }

    private suspend fun uniqueTagId(dao: TagDao, ids: IdGenerator): String =
        uniqueId(ids) { candidate -> dao.getById(candidate) != null || finalIdInUse(TAGS, candidate) }

    private suspend fun uniqueId(
        ids: IdGenerator,
        isUsed: suspend (String) -> Boolean,
    ): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = ids.newId()
            requireSafeId(candidate)
            if (!isUsed(candidate)) return candidate
        }
        throw IllegalStateException("Unable to generate a collision-free restore ID")
    }

    private fun tagIdInUse(id: String): Boolean = finalIdInUse(TAGS, id)

    private fun finalIdInUse(table: String, id: String): Boolean = query(
        "SELECT 1 FROM $table WHERE final_id = ? LIMIT 1",
        arrayOf(id),
    ).use(Cursor::moveToFirst)

    private fun updateFinalId(table: String, originalId: String, finalId: String) {
        val updated = sqlite.update(
            table,
            ContentValues().apply { put("final_id", finalId) },
            "original_id = ?",
            arrayOf(originalId),
        )
        check(updated == 1)
    }

    private fun mappedItemId(originalId: String): String = query(
        "SELECT final_id FROM $ITEMS WHERE original_id = ? LIMIT 1",
        arrayOf(originalId),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.requiredString(0) else originalId }

    private fun query(sql: String, args: Array<String>? = null): Cursor = sqlite.rawQuery(sql, args)

    private fun validateItem(item: VaultItemEntity) {
        requireSafeId(item.id)
        item.conflictOriginId?.let(::requireSafeId)
        requireBoundedText(item.title, VaultConstraints.MAX_NOTE_TITLE_CHARACTERS)
        requireBoundedText(item.body, VaultConstraints.MAX_NOTE_BODY_CHARACTERS)
        requireBoundedText(item.ocrText, MAX_OCR_CHARACTERS)
        require(
            item.createdAt >= 0L && item.updatedAt >= 0L &&
                item.localRevision in 1 until Long.MAX_VALUE
        )
        require(item.deletedAt == null || item.deletedAt >= 0L)
    }

    private fun validateTag(tag: TagEntity) {
        requireSafeId(tag.id)
        requireBoundedText(tag.name, MAX_TAG_CHARACTERS)
        requireBoundedText(tag.normalizedName, MAX_TAG_CHARACTERS)
        require(tag.name.isNotBlank() && tag.normalizedName.isNotBlank() && tag.createdAt >= 0L)
        val normalized = collapseWhitespace(
            Normalizer.normalize(tag.name, Normalizer.Form.NFKC).trim(),
        ).lowercase(Locale.ROOT)
        require(normalized == tag.normalizedName)
    }

    private fun validateAttachment(attachment: AttachmentEntity, entryPath: String) {
        requireSafeId(attachment.id)
        requireSafeId(attachment.parentItemId)
        require(ATTACHMENT_ENTRY_PATTERN.matches(entryPath))
        require(attachment.fileSize in 0..MAX_ATTACHMENT_BYTES)
        require(SHA256_PATTERN.matches(attachment.sha256Checksum))
        require(attachment.createdAt >= 0L)
        require(attachment.imageWidth == null || attachment.imageWidth > 0)
        require(attachment.imageHeight == null || attachment.imageHeight > 0)
        require(attachment.pdfPageCount == null || attachment.pdfPageCount > 0)
        requireBoundedText(attachment.extractedOcrText, MAX_OCR_CHARACTERS)
        require(attachment.ocrFailureCode == null || attachment.ocrFailureCode.length <= 128)
        require(
            attachment.ocrSourceChecksum == null ||
                SHA256_PATTERN.matches(attachment.ocrSourceChecksum)
        )
        val sanitized = FilenameSanitizer.sanitize(attachment.originalFilename)
        require(sanitized is RepositoryResult.Success && sanitized.value == attachment.originalFilename)
        require(attachment.mimeType.length in 3..256 && MIME_PATTERN.matches(attachment.mimeType))
    }

    private fun requireSafeId(value: String) {
        require(SAFE_ID_PATTERN.matches(value))
    }

    private fun requireBoundedText(value: String, maximumCodePoints: Int) {
        require(value.codePointCount(0, value.length) <= maximumCodePoints)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    require(index + 1 < value.length && Character.isLowSurrogate(value[index + 1]))
                    index += 2
                }
                Character.isLowSurrogate(character) -> throw IllegalArgumentException()
                else -> index += 1
            }
        }
    }

    private fun collapseWhitespace(value: String): String = buildString(value.length) {
        var previousWhitespace = false
        value.forEach { character ->
            if (character.isWhitespace()) {
                if (!previousWhitespace) append(' ')
                previousWhitespace = true
            } else {
                append(character)
                previousWhitespace = false
            }
        }
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.requiredString(index: Int): String = requireNotNull(getString(index))
    private fun Cursor.optionalString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.optionalLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.optionalInt(index: Int): Int? = if (isNull(index)) null else getInt(index)

    companion object {
        fun create(directory: File): RestoreStagingStore {
            require(!directory.exists() || directory.listFiles().isNullOrEmpty())
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IllegalStateException("Unable to create restore staging directory")
            }
            val sqlite = SQLiteDatabase.openOrCreateDatabase(File(directory, DATABASE_FILE), null)
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            createSchema(sqlite)
            return RestoreStagingStore(directory, sqlite)
        }

        fun open(directory: File): RestoreStagingStore {
            val database = File(directory, DATABASE_FILE)
            require(database.isFile)
            val sqlite = SQLiteDatabase.openDatabase(
                database.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            return RestoreStagingStore(directory, sqlite).apply {
                expectedItems = query("SELECT COUNT(*) FROM $ITEMS").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                expectedAttachments = query("SELECT COUNT(*) FROM $ATTACHMENTS").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                actualItems = expectedItems
                actualAttachments = expectedAttachments
                finished = true
            }
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $ITEMS (
                    original_id TEXT PRIMARY KEY NOT NULL,
                    final_id TEXT UNIQUE,
                    type TEXT NOT NULL,
                    color TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    ocr_text TEXT NOT NULL,
                    is_pinned INTEGER NOT NULL,
                    is_favorite INTEGER NOT NULL,
                    is_archived INTEGER NOT NULL,
                    sort_position INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    local_revision INTEGER NOT NULL,
                    deleted_at INTEGER,
                    conflict_origin_id TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $TAGS (
                    original_id TEXT PRIMARY KEY NOT NULL,
                    final_id TEXT,
                    name TEXT NOT NULL,
                    normalized_name TEXT UNIQUE NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE TABLE $LIVE_TAG_IDS (id TEXT PRIMARY KEY NOT NULL)")
            db.execSQL(
                """
                CREATE TABLE $ARCHIVE_ENTRIES (
                    path TEXT PRIMARY KEY NOT NULL,
                    ciphertext_size INTEGER NOT NULL,
                    ciphertext_sha256 TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $ITEM_TAGS (
                    item_id TEXT NOT NULL REFERENCES $ITEMS(original_id),
                    tag_id TEXT NOT NULL REFERENCES $TAGS(original_id),
                    PRIMARY KEY(item_id, tag_id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $ATTACHMENTS (
                    original_id TEXT PRIMARY KEY NOT NULL,
                    final_id TEXT UNIQUE,
                    parent_item_id TEXT NOT NULL REFERENCES $ITEMS(original_id),
                    filename TEXT NOT NULL,
                    mime_type TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    image_width INTEGER,
                    image_height INTEGER,
                    pdf_page_count INTEGER,
                    sha256 TEXT NOT NULL,
                    local_path TEXT,
                    pending_file TEXT,
                    destination_file TEXT,
                    created_at INTEGER NOT NULL,
                    ocr_state TEXT NOT NULL,
                    ocr_text TEXT NOT NULL,
                    ocr_source_checksum TEXT,
                    ocr_failure_code TEXT,
                    ocr_updated_at INTEGER,
                    content_entry TEXT UNIQUE NOT NULL
                )
                """.trimIndent(),
            )
        }

        private const val DATABASE_FILE = "restore-staging.db"
        private const val ITEMS = "items"
        private const val TAGS = "tags"
        private const val LIVE_TAG_IDS = "live_tag_ids"
        private const val ARCHIVE_ENTRIES = "archive_entries"
        private const val ITEM_TAGS = "item_tags"
        private const val ATTACHMENTS = "attachments"
        private const val MAX_TAG_CHARACTERS = 64
        private const val MAX_OCR_CHARACTERS = 200_000
        private const val MAX_ID_GENERATION_ATTEMPTS = 16
        private val SAFE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        private val ATTACHMENT_ENTRY_PATTERN = Regex("attachments/[0-9]{8}\\.bin")
        private val MIME_PATTERN = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
    }
}
