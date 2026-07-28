package com.vaultnote.core.sync.lan

import com.vaultnote.core.common.model.DatedEntryType
import com.vaultnote.core.common.model.RecurrenceUnit
import com.vaultnote.core.common.model.VaultItemColor
import com.vaultnote.core.common.model.VaultItemType
import com.vaultnote.core.sync.RemoteAttachmentReference
import com.vaultnote.core.sync.RemoteDatedEntry
import com.vaultnote.core.sync.RemoteItemMetadata
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class RelayInformationWire(
    val protocolVersion: Int,
    val minimumClientProtocolVersion: Int,
    val vaultId: String,
    val dnsName: String,
    val certificateSha256: String,
    val kdfAlgorithm: String,
    val kdfIterations: Int,
    val kdfSalt: String,
    val kdfKeyBits: Int,
)

data class RemoteItemWire(
    val itemId: String,
    val serverRevision: Long,
    val versionToken: String,
    val deleted: Boolean,
    val encryptedPayload: String?,
    val ciphertextSha256: String?,
)

data class MutationWire(
    val outcome: String,
    val serverRevision: Long?,
    val versionToken: String?,
    val remote: RemoteItemWire?,
)

data class ChangePageWire(
    val changes: List<RemoteItemWire>,
    val nextCursor: String?,
    val hasMore: Boolean,
)

data class AttachmentReceiptWire(
    val attachmentId: String,
    val ciphertextSha256: String,
    val ciphertextSize: Long,
    val remotePath: String,
)

internal object RelayWireCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun relayInformation(text: String): RelayInformationWire {
        val root = objectFrom(text, MAX_CONTROL_JSON)
        val tls = root.objectValue("tlsIdentity")
        val kdf = root.objectValue("keyDerivation")
        return RelayInformationWire(
            protocolVersion = root.intValue("protocolVersion"),
            minimumClientProtocolVersion = root.intValue("minimumClientProtocolVersion"),
            vaultId = root.safeId("vaultId"),
            dnsName = tls.string("dnsName", 253),
            certificateSha256 = tls.sha256("certificateSha256"),
            kdfAlgorithm = kdf.string("algorithm", 64),
            kdfIterations = kdf.intValue("iterations"),
            kdfSalt = kdf.string("salt", 128),
            kdfKeyBits = kdf.intValue("keyBits"),
        )
    }

    fun keyCheck(text: String): Pair<String, String> {
        val root = objectFrom(text, MAX_CONTROL_JSON)
        return root.string("encryptedKeyCheck", MAX_ENCODED_ITEM) to
            root.sha256("ciphertextSha256")
    }

    fun keyCheckRequest(encrypted: String, checksum: String): String = buildJsonObject {
        put("encryptedKeyCheck", encrypted)
        put("ciphertextSha256", checksum)
    }.toString()

    fun itemMutationRequest(
        expectedVersionToken: String?,
        encrypted: String,
        checksum: String,
    ): String = buildJsonObject {
        nullableString("expectedVersionToken", expectedVersionToken)
        put("encryptedPayload", encrypted)
        put("ciphertextSha256", checksum)
    }.toString()

    fun deleteMutationRequest(expectedVersionToken: String?): String = buildJsonObject {
        nullableString("expectedVersionToken", expectedVersionToken)
    }.toString()

    fun mutation(text: String): MutationWire {
        val root = objectFrom(text, MAX_ITEM_RESPONSE_JSON)
        return MutationWire(
            outcome = root.string("outcome", 16),
            serverRevision = root.optionalLong("serverRevision"),
            versionToken = root.optionalString("versionToken", 128),
            remote = root["remote"].takeUnless { it == null || it is JsonNull }?.jsonObject?.remoteItem(),
        )
    }

    fun changePage(text: String): ChangePageWire {
        val root = objectFrom(text, MAX_CHANGE_PAGE_JSON)
        val changes = root.arrayValue("changes").map { it.jsonObject.remoteItem() }
        if (changes.size > MAX_CHANGE_PAGE_ITEMS) throw IllegalArgumentException("Too many changes")
        return ChangePageWire(
            changes = changes,
            nextCursor = root.optionalString("nextCursor", 128)?.takeIf {
                it.isNotEmpty() && it.all(Char::isDigit)
            } ?: if (root["nextCursor"] == null || root["nextCursor"] is JsonNull) {
                null
            } else {
                throw IllegalArgumentException("Invalid cursor")
            },
            hasMore = root.booleanValue("hasMore"),
        )
    }

    fun attachmentReceipt(text: String): AttachmentReceiptWire {
        val root = objectFrom(text, MAX_CONTROL_JSON)
        return AttachmentReceiptWire(
            attachmentId = root.safeId("attachmentId"),
            ciphertextSha256 = root.sha256("ciphertextSha256"),
            ciphertextSize = root.longValue("ciphertextSize").takeIf { it > 0L }
                ?: throw IllegalArgumentException("Invalid attachment size"),
            remotePath = root.remotePath("remotePath"),
        )
    }

    fun encodeMetadata(metadata: RemoteItemMetadata): ByteArray = buildJsonObject {
        put("schemaVersion", ITEM_SCHEMA_VERSION)
        put("id", metadata.id)
        put("type", metadata.type.name)
        put("title", metadata.title)
        put("body", metadata.body)
        put("ocrText", metadata.ocrText)
        put("color", metadata.color.name)
        put("isPinned", metadata.isPinned)
        put("isFavorite", metadata.isFavorite)
        put("isArchived", metadata.isArchived)
        put("sortPosition", metadata.sortPosition)
        put("createdAt", metadata.createdAtEpochMillis)
        put("updatedAt", metadata.updatedAtEpochMillis)
        put("clientRevision", metadata.clientRevision)
        nullableString("bodyDocumentJson", metadata.bodyDocumentJson)
        put("tags", JsonArray(metadata.tags.map(::JsonPrimitive)))
        put("attachments", buildJsonArray {
            metadata.attachments.forEach { attachment ->
                add(buildJsonObject {
                    put("id", attachment.id)
                    put("remotePath", attachment.remotePath)
                    put("originalFilename", attachment.originalFilename)
                    put("mimeType", attachment.mimeType)
                    put("fileSizeBytes", attachment.fileSizeBytes)
                    put("plaintextSha256", attachment.plaintextSha256)
                    put("encryptionFormatVersion", attachment.encryptionFormatVersion)
                    nullableInt("imageWidth", attachment.imageWidth)
                    nullableInt("imageHeight", attachment.imageHeight)
                    nullableInt("pdfPageCount", attachment.pdfPageCount)
                    put("createdAt", attachment.createdAtEpochMillis)
                })
            }
        })
        put("datedEntries", buildJsonArray {
            metadata.datedEntries.forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id)
                    put("type", entry.type.name)
                    put("label", entry.label)
                    put("occurrenceAt", entry.occurrenceAtEpochMillis)
                    put("isAllDay", entry.isAllDay)
                    put("timeZoneId", entry.timeZoneId)
                    nullableString("recurrenceUnit", entry.recurrenceUnit?.name)
                    nullableInt("recurrenceInterval", entry.recurrenceInterval)
                    nullableLong("completedAt", entry.completedAtEpochMillis)
                    put("createdAt", entry.createdAtEpochMillis)
                    put("updatedAt", entry.updatedAtEpochMillis)
                    put(
                        "alertLeadTimesMinutes",
                        JsonArray(entry.alertLeadTimesMinutes.map(::JsonPrimitive)),
                    )
                })
            }
        })
    }.toString().encodeToByteArray()

    fun decodeMetadata(bytes: ByteArray, expectedItemId: String): RemoteItemMetadata {
        if (bytes.size !in 1..MAX_DECRYPTED_ITEM) throw IllegalArgumentException("Invalid item")
        val root = objectFrom(bytes.decodeToString(), MAX_DECRYPTED_ITEM)
        if (root.intValue("schemaVersion") != ITEM_SCHEMA_VERSION) {
            throw IllegalArgumentException("Unsupported item schema")
        }
        val id = root.safeId("id")
        if (id != expectedItemId) throw IllegalArgumentException("Item identity mismatch")
        val tags = root.arrayValue("tags").map { it.jsonPrimitive.content }.also {
            if (it.size > 64 || it.any { tag -> tag.length > 256 }) {
                throw IllegalArgumentException("Invalid tags")
            }
        }
        val attachments = root.arrayValue("attachments").map { element ->
            val attachment = element.jsonObject
            RemoteAttachmentReference(
                id = attachment.safeId("id"),
                remotePath = attachment.remotePath("remotePath"),
                mimeType = attachment.string("mimeType", 256),
                fileSizeBytes = attachment.longValue("fileSizeBytes")
                    .takeIf { it in 0..MAX_PLAINTEXT_ATTACHMENT_BYTES }
                    ?: throw IllegalArgumentException("Invalid attachment size"),
                plaintextSha256 = attachment.sha256("plaintextSha256"),
                encryptionFormatVersion = attachment.intValue("encryptionFormatVersion"),
                originalFilename = attachment.string("originalFilename", 512),
                imageWidth = attachment.optionalInt("imageWidth")?.takeIf {
                    it in 1..MAX_MEDIA_DIMENSION
                } ?: attachment.requireNull("imageWidth"),
                imageHeight = attachment.optionalInt("imageHeight")?.takeIf {
                    it in 1..MAX_MEDIA_DIMENSION
                } ?: attachment.requireNull("imageHeight"),
                pdfPageCount = attachment.optionalInt("pdfPageCount")?.takeIf {
                    it in 1..MAX_PDF_PAGES
                } ?: attachment.requireNull("pdfPageCount"),
                createdAtEpochMillis = attachment.longValue("createdAt"),
            )
        }.also {
            if (it.size > 512 || it.distinctBy(RemoteAttachmentReference::id).size != it.size) {
                throw IllegalArgumentException("Invalid attachments")
            }
        }
        val datedEntries = root.arrayValue("datedEntries").map { element ->
            val entry = element.jsonObject
            RemoteDatedEntry(
                id = entry.safeId("id"),
                type = enumValue<DatedEntryType>(entry.string("type", 32)),
                label = entry.string("label", 1_024),
                occurrenceAtEpochMillis = entry.longValue("occurrenceAt"),
                isAllDay = entry.booleanValue("isAllDay"),
                timeZoneId = entry.string("timeZoneId", 128),
                recurrenceUnit = entry.optionalString("recurrenceUnit", 32)
                    ?.let { enumValue<RecurrenceUnit>(it) },
                recurrenceInterval = entry.optionalInt("recurrenceInterval"),
                completedAtEpochMillis = entry.optionalLong("completedAt"),
                createdAtEpochMillis = entry.longValue("createdAt"),
                updatedAtEpochMillis = entry.longValue("updatedAt"),
                alertLeadTimesMinutes = entry.arrayValue("alertLeadTimesMinutes")
                    .map { it.jsonPrimitive.long },
            )
        }.also {
            if (it.size > 512) throw IllegalArgumentException("Invalid dated entries")
        }
        return RemoteItemMetadata(
            id = id,
            type = enumValue(root.string("type", 32)),
            title = root.string("title", MAX_TEXT_CHARS),
            body = root.string("body", MAX_TEXT_CHARS),
            ocrText = root.string("ocrText", MAX_TEXT_CHARS),
            color = enumValue(root.string("color", 32)),
            isPinned = root.booleanValue("isPinned"),
            isFavorite = root.booleanValue("isFavorite"),
            isArchived = root.booleanValue("isArchived"),
            sortPosition = root.longValue("sortPosition"),
            createdAtEpochMillis = root.longValue("createdAt"),
            updatedAtEpochMillis = root.longValue("updatedAt"),
            clientRevision = root.longValue("clientRevision").coerceAtLeast(1L),
            tags = tags,
            attachments = attachments,
            bodyDocumentJson = root.optionalString("bodyDocumentJson", MAX_TEXT_CHARS),
            datedEntries = datedEntries,
        )
    }

    private fun JsonObject.remoteItem(): RemoteItemWire = RemoteItemWire(
        itemId = safeId("itemId"),
        serverRevision = longValue("serverRevision").takeIf { it > 0L }
            ?: throw IllegalArgumentException("Invalid revision"),
        versionToken = safeId("versionToken"),
        deleted = booleanValue("deleted"),
        encryptedPayload = optionalString("encryptedPayload", MAX_ENCODED_ITEM),
        ciphertextSha256 = optionalString("ciphertextSha256", 64)?.also {
            if (!SHA256.matches(it)) throw IllegalArgumentException("Invalid checksum")
        },
    ).also {
        if (it.deleted != (it.encryptedPayload == null && it.ciphertextSha256 == null)) {
            throw IllegalArgumentException("Invalid tombstone")
        }
    }

    private fun objectFrom(text: String, maximumChars: Int): JsonObject {
        if (text.length !in 1..maximumChars) throw IllegalArgumentException("Invalid JSON size")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(key: String, maximum: Int): String =
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= maximum }
            ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.optionalString(key: String, maximum: Int): String? =
        get(key).takeUnless { it == null || it is JsonNull }
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.length <= maximum }
            ?: if (get(key) == null || get(key) is JsonNull) null
            else throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.intValue(key: String): Int = get(key)?.jsonPrimitive?.int
        ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.optionalInt(key: String): Int? =
        get(key).takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.int

    private fun JsonObject.longValue(key: String): Long = get(key)?.jsonPrimitive?.long
        ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.optionalLong(key: String): Long? =
        get(key).takeUnless { it == null || it is JsonNull }?.jsonPrimitive?.long

    private fun JsonObject.requireNull(key: String): Nothing? {
        if (get(key) == null || get(key) is JsonNull) return null
        throw IllegalArgumentException("Invalid $key")
    }

    private fun JsonObject.booleanValue(key: String): Boolean = get(key)?.jsonPrimitive?.boolean
        ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.objectValue(key: String): JsonObject =
        get(key)?.jsonObject ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.arrayValue(key: String): JsonArray =
        get(key)?.jsonArray ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.safeId(key: String): String =
        string(key, 128).takeIf { SAFE_ID.matches(it) }
            ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.sha256(key: String): String =
        string(key, 64).lowercase(Locale.ROOT).takeIf { SHA256.matches(it) }
            ?: throw IllegalArgumentException("Invalid $key")

    private fun JsonObject.remotePath(key: String): String =
        string(key, 256).takeIf {
            it.startsWith("/v1/attachments/") &&
                SAFE_ID.matches(it.substringAfterLast('/'))
        } ?: throw IllegalArgumentException("Invalid remote path")

    private fun kotlinx.serialization.json.JsonObjectBuilder.nullableString(
        key: String,
        value: String?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.nullableInt(
        key: String,
        value: Int?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.nullableLong(
        key: String,
        value: Long?,
    ) {
        put(key, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Invalid enum")

    private const val ITEM_SCHEMA_VERSION = 3
    private const val MAX_CONTROL_JSON = 64 * 1024
    private const val MAX_ITEM_RESPONSE_JSON = 3 * 1024 * 1024
    private const val MAX_CHANGE_PAGE_JSON = 64 * 1024 * 1024
    private const val MAX_CHANGE_PAGE_ITEMS = 200
    private const val MAX_DECRYPTED_ITEM = 2 * 1024 * 1024
    private const val MAX_ENCODED_ITEM = 3 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 1_500_000
    private const val MAX_MEDIA_DIMENSION = 100_000
    private const val MAX_PDF_PAGES = 1_000_000
    private const val MAX_PLAINTEXT_ATTACHMENT_BYTES = 100L * 1024L * 1024L
    private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
