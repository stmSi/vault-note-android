package com.vaultnote.core.backup

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object BackupFormat {
    const val MAGIC = "VaultNoteBackup"
    const val VERSION = 1
    const val MIN_READER_VERSION = 1
    const val DATABASE_SCHEMA_VERSION = 1
    const val MANIFEST_PATH = "manifest.json"
    const val DATABASE_PATH = "database.json.enc"
    const val CHECKSUMS_PATH = "checksums.json.enc"
    const val ATTACHMENTS_PREFIX = "attachments/"
    const val KDF_ALGORITHM = "PBKDF2-HMAC-SHA256"
    const val CIPHER_ALGORITHM = "AES-256-GCM"
    const val KDF_ITERATIONS = 600_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 32
    const val ARCHIVE_ID_BYTES = 16
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_PASSWORD_CODE_POINTS = 128
    const val MIN_PASSWORD_CODE_POINTS = 12
    const val MAX_ENTRY_COUNT = 100_003
    const val MAX_ITEM_COUNT = 1_000_000L
    const val MAX_ATTACHMENT_COUNT = 100_000L
    const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    const val PRIVATE_SPACE_RESERVE_BYTES = 32L * 1024L * 1024L
    const val PAGE_SIZE = 100

    fun attachmentPath(index: Long): String =
        "$ATTACHMENTS_PREFIX${index.toString().padStart(8, '0')}.bin"
}

internal data class BackupManifest(
    val archiveId: ByteArray,
    val createdAtEpochMillis: Long,
    val salt: ByteArray,
    val kdfIterations: Int,
    val checksumsCiphertextSize: Long,
    val checksumsCiphertextSha256: String,
)

internal data class BackupEntryChecksum(
    val path: String,
    val ciphertextSize: Long,
    val ciphertextSha256: String,
)

data class BackupSummary(
    val itemCount: Long,
    val attachmentCount: Long,
    val createdAtEpochMillis: Long,
)

data class RestoreSummary(
    val restoredItemCount: Long,
    val restoredAttachmentCount: Long,
    val copiedItemCount: Long,
)

internal object BackupManifestCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(manifest: BackupManifest): ByteArray {
        val root = buildJsonObject {
            put("magic", BackupFormat.MAGIC)
            put("formatVersion", BackupFormat.VERSION)
            put("minimumReaderVersion", BackupFormat.MIN_READER_VERSION)
            put("createdAtEpochMillis", manifest.createdAtEpochMillis)
            put("archiveId", Base64.getEncoder().encodeToString(manifest.archiveId))
            put(
                "kdf",
                buildJsonObject {
                    put("algorithm", BackupFormat.KDF_ALGORITHM)
                    put("iterations", manifest.kdfIterations)
                    put("salt", Base64.getEncoder().encodeToString(manifest.salt))
                    put("keyBits", BackupFormat.KEY_BITS)
                },
            )
            put("cipher", BackupFormat.CIPHER_ALGORITHM)
            put(
                "checksums",
                buildJsonObject {
                    put("path", BackupFormat.CHECKSUMS_PATH)
                    put("ciphertextSize", manifest.checksumsCiphertextSize)
                    put("ciphertextSha256", manifest.checksumsCiphertextSha256)
                },
            )
        }
        return json.encodeToString(JsonElement.serializer(), root).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): RepositoryResult<BackupManifest> {
        if (bytes.isEmpty() || bytes.size > BackupFormat.MAX_MANIFEST_BYTES) return invalidManifest()
        return try {
            requireUniqueObjectKeys(bytes.decodeToString())
            val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            requireExactKeys(
                root,
                setOf(
                    "magic",
                    "formatVersion",
                    "minimumReaderVersion",
                    "createdAtEpochMillis",
                    "archiveId",
                    "kdf",
                    "cipher",
                    "checksums",
                ),
            )
            if (root.requiredString("magic") != BackupFormat.MAGIC) return invalidManifest()
            val version = root.requiredInt("formatVersion")
            val minimumReader = root.requiredInt("minimumReaderVersion")
            if (
                version != BackupFormat.VERSION ||
                minimumReader > BackupFormat.VERSION
            ) {
                return RepositoryResult.Failure(
                    AppError.BackupValidationFailure(
                        AppError.BackupValidationReason.UNSUPPORTED_VERSION,
                    ),
                )
            }
            val kdf = root.requiredObject("kdf")
            requireExactKeys(kdf, setOf("algorithm", "iterations", "salt", "keyBits"))
            if (
                kdf.requiredString("algorithm") != BackupFormat.KDF_ALGORITHM ||
                kdf.requiredInt("iterations") != BackupFormat.KDF_ITERATIONS ||
                kdf.requiredInt("keyBits") != BackupFormat.KEY_BITS ||
                root.requiredString("cipher") != BackupFormat.CIPHER_ALGORITHM
            ) {
                return RepositoryResult.Failure(
                    AppError.BackupValidationFailure(
                        AppError.BackupValidationReason.UNSUPPORTED_VERSION,
                    ),
                )
            }
            val checksums = root.requiredObject("checksums")
            requireExactKeys(
                checksums,
                setOf("path", "ciphertextSize", "ciphertextSha256"),
            )
            if (checksums.requiredString("path") != BackupFormat.CHECKSUMS_PATH) {
                return invalidManifest()
            }
            val archiveId = root.requiredBase64("archiveId", BackupFormat.ARCHIVE_ID_BYTES)
            val salt = kdf.requiredBase64("salt", BackupFormat.SALT_BYTES)
            val createdAt = root.requiredLong("createdAtEpochMillis")
            val checksumSize = checksums.requiredLong("ciphertextSize")
            val checksumSha = checksums.requiredSha256("ciphertextSha256")
            if (createdAt < 0L || checksumSize <= 0L) return invalidManifest()
            RepositoryResult.Success(
                BackupManifest(
                    archiveId = archiveId,
                    createdAtEpochMillis = createdAt,
                    salt = salt,
                    kdfIterations = BackupFormat.KDF_ITERATIONS,
                    checksumsCiphertextSize = checksumSize,
                    checksumsCiphertextSha256 = checksumSha,
                ),
            )
        } catch (_: IllegalArgumentException) {
            invalidManifest()
        } catch (_: IllegalStateException) {
            invalidManifest()
        } catch (_: IOException) {
            invalidManifest()
        }
    }

    fun binding(manifest: BackupManifest): ByteArray {
        val bound = buildJsonObject {
            put("magic", BackupFormat.MAGIC)
            put("formatVersion", BackupFormat.VERSION)
            put("minimumReaderVersion", BackupFormat.MIN_READER_VERSION)
            put("createdAtEpochMillis", manifest.createdAtEpochMillis)
            put("archiveId", Base64.getEncoder().encodeToString(manifest.archiveId))
            put("kdfAlgorithm", BackupFormat.KDF_ALGORITHM)
            put("kdfIterations", manifest.kdfIterations)
            put("salt", Base64.getEncoder().encodeToString(manifest.salt))
            put("keyBits", BackupFormat.KEY_BITS)
            put("cipher", BackupFormat.CIPHER_ALGORITHM)
        }
        return json.encodeToString(JsonElement.serializer(), bound).encodeToByteArray()
    }

    private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
        require(value.keys == expected)
    }

    private fun requireUniqueObjectKeys(source: String) {
        JsonReader(StringReader(source)).use { reader ->
            reader.isLenient = false
            scanJsonValue(reader)
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
    }

    private fun scanJsonValue(reader: JsonReader) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val names = hashSetOf<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName()))
                    scanJsonValue(reader)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) scanJsonValue(reader)
                reader.endArray()
            }
            JsonToken.STRING,
            JsonToken.NUMBER,
            -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Invalid JSON token")
        }
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        requireNotNull(this[name]).jsonObject

    private fun JsonObject.requiredString(name: String): String =
        requireNotNull(this[name]).jsonPrimitive.content

    private fun JsonObject.requiredInt(name: String): Int =
        requiredString(name).toInt()

    private fun JsonObject.requiredLong(name: String): Long =
        requiredString(name).toLong()

    private fun JsonObject.requiredBase64(name: String, exactBytes: Int): ByteArray =
        Base64.getDecoder().decode(requiredString(name)).also { require(it.size == exactBytes) }

    private fun JsonObject.requiredSha256(name: String): String = requiredString(name).also {
        require(SHA256_PATTERN.matches(it))
    }

    private fun invalidManifest(): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.BackupValidationFailure(AppError.BackupValidationReason.INVALID_MANIFEST),
    )

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}

internal object BackupChecksumsCodec {
    fun newWriter(destination: Writer): JsonWriter = JsonWriter(destination).apply {
        setIndent("")
        beginObject()
        name("formatVersion").value(BackupFormat.VERSION.toLong())
        name("entries").beginArray()
    }

    fun writeEntry(writer: JsonWriter, entry: BackupEntryChecksum) {
        writer.beginObject()
        writer.name("path").value(entry.path)
        writer.name("ciphertextSize").value(entry.ciphertextSize)
        writer.name("ciphertextSha256").value(entry.ciphertextSha256)
        writer.endObject()
    }

    fun finish(writer: JsonWriter) {
        writer.endArray()
        writer.endObject()
        writer.flush()
    }

    fun read(reader: Reader, consume: (BackupEntryChecksum) -> Unit) {
        JsonReader(reader).use { json ->
            json.isLenient = false
            json.beginObject()
            require(json.nextName() == "formatVersion")
            require(json.nextInt() == BackupFormat.VERSION)
            require(json.nextName() == "entries")
            json.beginArray()
            while (json.hasNext()) consume(readEntry(json))
            json.endArray()
            require(!json.hasNext())
            json.endObject()
            require(json.peek() == JsonToken.END_DOCUMENT)
        }
    }

    private fun readEntry(reader: JsonReader): BackupEntryChecksum {
        reader.beginObject()
        require(reader.nextName() == "path")
        val path = reader.nextString()
        require(reader.nextName() == "ciphertextSize")
        val size = reader.nextLong()
        require(reader.nextName() == "ciphertextSha256")
        val checksum = reader.nextString()
        require(!reader.hasNext())
        reader.endObject()
        require(path == BackupFormat.DATABASE_PATH || isAttachmentPath(path))
        require(size > 0L)
        require(SHA256_PATTERN.matches(checksum))
        return BackupEntryChecksum(path, size, checksum)
    }

    private fun isAttachmentPath(path: String): Boolean =
        path.startsWith(BackupFormat.ATTACHMENTS_PREFIX) &&
            ATTACHMENT_PATH_PATTERN.matches(path)

    private val ATTACHMENT_PATH_PATTERN = Regex("attachments/[0-9]{8}\\.bin")
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}
