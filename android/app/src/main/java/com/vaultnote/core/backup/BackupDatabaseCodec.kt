package com.vaultnote.core.backup

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.vaultnote.core.database.dao.BackupDao
import com.vaultnote.core.database.entity.AttachmentEntity
import com.vaultnote.core.database.entity.ItemTagCrossRef
import com.vaultnote.core.database.entity.TagEntity
import com.vaultnote.core.database.entity.VaultItemEntity
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.Writer
import java.nio.charset.StandardCharsets

internal data class BackupAttachmentSource(
    val entryPath: String,
    val attachmentId: String,
    val localRelativePath: String,
    val plaintextSize: Long,
    val plaintextSha256: String,
)

internal data class BackupSnapshotStats(
    val itemCount: Long,
    val attachmentCount: Long,
    val totalAttachmentBytes: Long,
)

internal interface BackupDatabaseSink {
    fun begin(expectedItems: Long, expectedAttachments: Long)
    fun acceptItem(item: VaultItemEntity)
    fun acceptTag(tag: TagEntity)
    fun acceptItemTag(reference: ItemTagCrossRef)
    fun acceptAttachment(attachment: AttachmentEntity, entryPath: String)
    fun finish()
}

internal class BackupDatabaseCodec(
    private val backupDao: BackupDao,
) {
    suspend fun write(
        destination: OutputStream,
        attachmentSources: Writer,
    ): BackupSnapshotStats {
        val itemCount = backupDao.countItems()
        val attachmentCount = backupDao.countAttachments()
        val attachmentBytes = backupDao.totalAttachmentBytes()
        require(itemCount in 0..BackupFormat.MAX_ITEM_COUNT)
        require(attachmentCount in 0..BackupFormat.MAX_ATTACHMENT_COUNT)
        require(attachmentBytes >= 0L)

        val json = JsonWriter(OutputStreamWriter(destination, StandardCharsets.UTF_8))
        val sources = BackupAttachmentSourcesCodec.newWriter(attachmentSources)
        json.beginObject()
        json.name("schemaVersion").value(BackupFormat.DATABASE_SCHEMA_VERSION.toLong())
        json.name("itemCount").value(itemCount)
        json.name("attachmentCount").value(attachmentCount)
        writeItems(json)
        writeTags(json)
        writeItemTags(json)
        writeAttachments(json, sources)
        json.endObject()
        json.flush()
        BackupAttachmentSourcesCodec.finish(sources)
        return BackupSnapshotStats(itemCount, attachmentCount, attachmentBytes)
    }

    fun read(source: InputStream, sink: BackupDatabaseSink) {
        JsonReader(InputStreamReader(source, StandardCharsets.UTF_8)).use { json ->
            json.isLenient = false
            json.beginObject()
            requireName(json, "schemaVersion")
            val schemaVersion = json.nextInt()
            require(
                schemaVersion in
                    BackupFormat.MIN_DATABASE_SCHEMA_VERSION..BackupFormat.DATABASE_SCHEMA_VERSION,
            )
            requireName(json, "itemCount")
            val expectedItems = json.nextLong()
            require(expectedItems in 0..BackupFormat.MAX_ITEM_COUNT)
            requireName(json, "attachmentCount")
            val expectedAttachments = json.nextLong()
            require(expectedAttachments in 0..BackupFormat.MAX_ATTACHMENT_COUNT)
            sink.begin(expectedItems, expectedAttachments)
            readItems(json, sink, schemaVersion)
            readTags(json, sink)
            readItemTags(json, sink)
            readAttachments(json, sink)
            require(!json.hasNext())
            json.endObject()
            require(json.peek() == JsonToken.END_DOCUMENT)
            sink.finish()
        }
    }

    private suspend fun writeItems(json: JsonWriter) {
        json.name("items").beginArray()
        var after = ""
        while (true) {
            val page = backupDao.getItemsPage(after, BackupFormat.PAGE_SIZE)
            for (item in page) writeItem(json, item)
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().id
        }
        json.endArray()
    }

    private suspend fun writeTags(json: JsonWriter) {
        json.name("tags").beginArray()
        var after = ""
        while (true) {
            val page = backupDao.getTagsPage(after, BackupFormat.PAGE_SIZE)
            for (tag in page) {
                json.beginObject()
                json.name("id").value(tag.id)
                json.name("name").value(tag.name)
                json.name("normalizedName").value(tag.normalizedName)
                json.name("createdAt").value(tag.createdAt)
                json.endObject()
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().id
        }
        json.endArray()
    }

    private suspend fun writeItemTags(json: JsonWriter) {
        json.name("itemTags").beginArray()
        var afterItem = ""
        var afterTag = ""
        while (true) {
            val page = backupDao.getItemTagsPage(
                afterItemId = afterItem,
                afterTagId = afterTag,
                limit = BackupFormat.PAGE_SIZE,
            )
            for (reference in page) {
                json.beginObject()
                json.name("itemId").value(reference.itemId)
                json.name("tagId").value(reference.tagId)
                json.endObject()
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            afterItem = page.last().itemId
            afterTag = page.last().tagId
        }
        json.endArray()
    }

    private suspend fun writeAttachments(
        json: JsonWriter,
        sources: JsonWriter,
    ) {
        json.name("attachments").beginArray()
        var after = ""
        var index = 0L
        while (true) {
            val page = backupDao.getAttachmentsPage(after, BackupFormat.PAGE_SIZE)
            for (attachment in page) {
                index += 1L
                val entryPath = BackupFormat.attachmentPath(index)
                writeAttachment(json, attachment, entryPath)
                BackupAttachmentSourcesCodec.write(
                    sources,
                    BackupAttachmentSource(
                        entryPath = entryPath,
                        attachmentId = attachment.id,
                        localRelativePath = attachment.localEncryptedPath,
                        plaintextSize = attachment.fileSize,
                        plaintextSha256 = attachment.sha256Checksum,
                    ),
                )
            }
            if (page.size < BackupFormat.PAGE_SIZE) break
            after = page.last().id
        }
        json.endArray()
    }

    private fun writeItem(json: JsonWriter, item: VaultItemEntity) {
        json.beginObject()
        json.name("id").value(item.id)
        json.name("type").value(item.type.name)
        json.name("color").value(item.color.name)
        json.name("title").value(item.title)
        json.name("body").value(item.body)
        json.name("ocrText").value(item.ocrText)
        json.name("pinned").value(item.isPinned)
        json.name("favorite").value(item.isFavorite)
        json.name("archived").value(item.isArchived)
        json.name("sortPosition").value(item.sortPosition)
        json.name("createdAt").value(item.createdAt)
        json.name("updatedAt").value(item.updatedAt)
        json.name("localRevision").value(item.localRevision)
        writeNullableLong(json, "deletedAt", item.deletedAt)
        writeNullableString(json, "conflictOriginId", item.conflictOriginId)
        json.endObject()
    }

    private fun writeAttachment(
        json: JsonWriter,
        attachment: AttachmentEntity,
        entryPath: String,
    ) {
        json.beginObject()
        json.name("id").value(attachment.id)
        json.name("parentItemId").value(attachment.parentItemId)
        json.name("filename").value(attachment.originalFilename)
        json.name("mimeType").value(attachment.mimeType)
        json.name("fileSize").value(attachment.fileSize)
        writeNullableLong(json, "imageWidth", attachment.imageWidth?.toLong())
        writeNullableLong(json, "imageHeight", attachment.imageHeight?.toLong())
        writeNullableLong(json, "pdfPageCount", attachment.pdfPageCount?.toLong())
        json.name("sha256").value(attachment.sha256Checksum)
        json.name("createdAt").value(attachment.createdAt)
        json.name("ocrState").value(attachment.ocrState.name)
        json.name("ocrText").value(attachment.extractedOcrText)
        writeNullableString(json, "ocrSourceChecksum", attachment.ocrSourceChecksum)
        writeNullableString(json, "ocrFailureCode", attachment.ocrFailureCode)
        writeNullableLong(json, "ocrUpdatedAt", attachment.ocrUpdatedAt)
        json.name("contentEntry").value(entryPath)
        json.endObject()
    }

    private fun readItems(
        json: JsonReader,
        sink: BackupDatabaseSink,
        schemaVersion: Int,
    ) {
        requireName(json, "items")
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            requireName(json, "id")
            val id = json.nextString()
            requireName(json, "type")
            val type = enumValueOf<com.vaultnote.core.common.model.VaultItemType>(json.nextString())
            requireName(json, "color")
            val color = enumValueOf<com.vaultnote.core.common.model.VaultItemColor>(json.nextString())
            requireName(json, "title")
            val title = json.nextString()
            requireName(json, "body")
            val body = json.nextString()
            requireName(json, "ocrText")
            val ocrText = json.nextString()
            requireName(json, "pinned")
            val pinned = json.nextBoolean()
            requireName(json, "favorite")
            val favorite = json.nextBoolean()
            requireName(json, "archived")
            val archived = json.nextBoolean()
            val sortPosition = if (schemaVersion >= 2) {
                requireName(json, "sortPosition")
                json.nextLong()
            } else {
                null
            }
            requireName(json, "createdAt")
            val createdAt = json.nextLong()
            requireName(json, "updatedAt")
            val updatedAt = json.nextLong()
            requireName(json, "localRevision")
            val localRevision = json.nextLong()
            requireName(json, "deletedAt")
            val deletedAt = nextNullableLong(json)
            requireName(json, "conflictOriginId")
            val conflictOriginId = nextNullableString(json)
            require(!json.hasNext())
            json.endObject()
            sink.acceptItem(
                VaultItemEntity(
                    id = id,
                    type = type,
                    color = color,
                    title = title,
                    body = body,
                    ocrText = ocrText,
                    isPinned = pinned,
                    isFavorite = favorite,
                    isArchived = archived,
                    sortPosition = sortPosition ?: legacySortPosition(updatedAt),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    localRevision = localRevision,
                    remoteRevision = null,
                    lastSyncedRevision = null,
                    serverVersionToken = null,
                    syncStatus = com.vaultnote.core.common.model.ItemSyncStatus.PENDING,
                    deletedAt = deletedAt,
                    conflictOriginId = conflictOriginId,
                ),
            )
        }
        json.endArray()
    }

    private fun legacySortPosition(updatedAt: Long): Long =
        Long.MAX_VALUE - updatedAt.coerceAtLeast(0L)

    private fun readTags(json: JsonReader, sink: BackupDatabaseSink) {
        requireName(json, "tags")
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            requireName(json, "id")
            val id = json.nextString()
            requireName(json, "name")
            val name = json.nextString()
            requireName(json, "normalizedName")
            val normalizedName = json.nextString()
            requireName(json, "createdAt")
            val createdAt = json.nextLong()
            require(!json.hasNext())
            json.endObject()
            sink.acceptTag(TagEntity(id, name, normalizedName, createdAt))
        }
        json.endArray()
    }

    private fun readItemTags(json: JsonReader, sink: BackupDatabaseSink) {
        requireName(json, "itemTags")
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            requireName(json, "itemId")
            val itemId = json.nextString()
            requireName(json, "tagId")
            val tagId = json.nextString()
            require(!json.hasNext())
            json.endObject()
            sink.acceptItemTag(ItemTagCrossRef(itemId, tagId))
        }
        json.endArray()
    }

    private fun readAttachments(json: JsonReader, sink: BackupDatabaseSink) {
        requireName(json, "attachments")
        json.beginArray()
        while (json.hasNext()) {
            json.beginObject()
            requireName(json, "id")
            val id = json.nextString()
            requireName(json, "parentItemId")
            val parentItemId = json.nextString()
            requireName(json, "filename")
            val filename = json.nextString()
            requireName(json, "mimeType")
            val mimeType = json.nextString()
            requireName(json, "fileSize")
            val fileSize = json.nextLong()
            requireName(json, "imageWidth")
            val imageWidth = nextNullableInt(json)
            requireName(json, "imageHeight")
            val imageHeight = nextNullableInt(json)
            requireName(json, "pdfPageCount")
            val pdfPageCount = nextNullableInt(json)
            requireName(json, "sha256")
            val sha256 = json.nextString()
            requireName(json, "createdAt")
            val createdAt = json.nextLong()
            requireName(json, "ocrState")
            val ocrState = enumValueOf<com.vaultnote.core.common.model.OcrState>(json.nextString())
            requireName(json, "ocrText")
            val ocrText = json.nextString()
            requireName(json, "ocrSourceChecksum")
            val ocrSourceChecksum = nextNullableString(json)
            requireName(json, "ocrFailureCode")
            val ocrFailureCode = nextNullableString(json)
            requireName(json, "ocrUpdatedAt")
            val ocrUpdatedAt = nextNullableLong(json)
            requireName(json, "contentEntry")
            val contentEntry = json.nextString()
            require(!json.hasNext())
            json.endObject()
            sink.acceptAttachment(
                AttachmentEntity(
                    id = id,
                    parentItemId = parentItemId,
                    originalFilename = filename,
                    mimeType = mimeType,
                    fileSize = fileSize,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    pdfPageCount = pdfPageCount,
                    sha256Checksum = sha256,
                    localEncryptedPath = "",
                    remotePath = null,
                    thumbnailPath = null,
                    encryptionFormatVersion = com.vaultnote.core.encryption.CURRENT_ATTACHMENT_ENCRYPTION_FORMAT_VERSION,
                    uploadStatus = com.vaultnote.core.common.model.AttachmentUploadStatus.PENDING,
                    createdAt = createdAt,
                    ocrState = ocrState,
                    extractedOcrText = ocrText,
                    ocrSourceChecksum = ocrSourceChecksum,
                    ocrFailureCode = ocrFailureCode,
                    ocrUpdatedAt = ocrUpdatedAt,
                ),
                contentEntry,
            )
        }
        json.endArray()
    }

    private fun writeNullableString(json: JsonWriter, name: String, value: String?) {
        json.name(name)
        if (value == null) json.nullValue() else json.value(value)
    }

    private fun writeNullableLong(json: JsonWriter, name: String, value: Long?) {
        json.name(name)
        if (value == null) json.nullValue() else json.value(value)
    }

    private fun requireName(json: JsonReader, expected: String) {
        require(json.hasNext() && json.nextName() == expected)
    }

    private fun nextNullableString(json: JsonReader): String? =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            null
        } else {
            json.nextString()
        }

    private fun nextNullableLong(json: JsonReader): Long? =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            null
        } else {
            json.nextLong()
        }

    private fun nextNullableInt(json: JsonReader): Int? = nextNullableLong(json)?.let { value ->
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        value.toInt()
    }
}

internal object BackupAttachmentSourcesCodec {
    fun newWriter(destination: Writer): JsonWriter = JsonWriter(destination).apply {
        beginArray()
    }

    fun write(writer: JsonWriter, source: BackupAttachmentSource) {
        writer.beginObject()
        writer.name("entryPath").value(source.entryPath)
        writer.name("attachmentId").value(source.attachmentId)
        writer.name("localRelativePath").value(source.localRelativePath)
        writer.name("plaintextSize").value(source.plaintextSize)
        writer.name("plaintextSha256").value(source.plaintextSha256)
        writer.endObject()
    }

    fun finish(writer: JsonWriter) {
        writer.endArray()
        writer.flush()
    }

    fun openReader(source: Reader): BackupAttachmentSourcesReader =
        BackupAttachmentSourcesReader(JsonReader(source))
}

internal class BackupAttachmentSourcesReader(
    private val json: JsonReader,
) : AutoCloseable {
    private var finished = false

    init {
        json.isLenient = false
        json.beginArray()
    }

    fun next(): BackupAttachmentSource? {
        check(!finished)
        if (!json.hasNext()) {
            json.endArray()
            require(json.peek() == JsonToken.END_DOCUMENT)
            finished = true
            return null
        }
        json.beginObject()
        require(json.nextName() == "entryPath")
        val entryPath = json.nextString()
        require(json.nextName() == "attachmentId")
        val attachmentId = json.nextString()
        require(json.nextName() == "localRelativePath")
        val localPath = json.nextString()
        require(json.nextName() == "plaintextSize")
        val size = json.nextLong()
        require(json.nextName() == "plaintextSha256")
        val checksum = json.nextString()
        require(!json.hasNext())
        json.endObject()
        return BackupAttachmentSource(
            entryPath,
            attachmentId,
            localPath,
            size,
            checksum,
        )
    }

    override fun close() {
        json.close()
    }
}
