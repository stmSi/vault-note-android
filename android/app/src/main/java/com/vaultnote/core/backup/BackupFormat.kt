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
    const val CURRENT_READER_VERSION = 2
    const val VERSION = 1
    const val MIN_READER_VERSION = 1
    const val PLAINTEXT_VERSION = 2
    const val PLAINTEXT_MIN_READER_VERSION = 2
    const val DATABASE_SCHEMA_VERSION = 2
    const val MIN_DATABASE_SCHEMA_VERSION = 1
    const val MANIFEST_PATH = "manifest.json"
    const val DATABASE_PATH = "database.json.enc"
    const val CHECKSUMS_PATH = "checksums.json.enc"
    const val PLAINTEXT_DATABASE_PATH = "database.json"
    const val PLAINTEXT_CHECKSUMS_PATH = "checksums.json"
    const val ATTACHMENTS_PREFIX = "attachments/"
    const val KDF_ALGORITHM = "PBKDF2-HMAC-SHA256"
    const val CIPHER_ALGORITHM = "AES-256-GCM"
    const val KDF_ITERATIONS = 600_000
    const val KEY_BITS = 256
    const val SALT_BYTES = 32
    const val ARCHIVE_ID_BYTES = 16
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_CHECKSUMS_BYTES = 32L * 1024L * 1024L
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

enum class BackupProtection {
    ENCRYPTED,
    PLAINTEXT,
}

internal data class BackupManifest(
    val archiveId: ByteArray,
    val createdAtEpochMillis: Long,
    val salt: ByteArray,
    val kdfIterations: Int,
    val checksumsCiphertextSize: Long,
    val checksumsCiphertextSha256: String,
    val formatVersion: Int = BackupFormat.VERSION,
    val minimumReaderVersion: Int = BackupFormat.MIN_READER_VERSION,
    val protection: BackupProtection = BackupProtection.ENCRYPTED,
    val checksumsPath: String = BackupFormat.CHECKSUMS_PATH,
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
    val protection: BackupProtection = BackupProtection.ENCRYPTED,
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
        require(manifest.archiveId.size == BackupFormat.ARCHIVE_ID_BYTES)
        require(manifest.createdAtEpochMillis >= 0L)
        require(manifest.checksumsCiphertextSize > 0L)
        require(SHA256_PATTERN.matches(manifest.checksumsCiphertextSha256))
        val root = when (manifest.protection) {
            BackupProtection.ENCRYPTED -> encryptedRoot(manifest)
            BackupProtection.PLAINTEXT -> plaintextRoot(manifest)
        }
        return json.encodeToString(JsonElement.serializer(), root).encodeToByteArray()
    }

    fun decode(bytes: ByteArray): RepositoryResult<BackupManifest> {
        if (bytes.isEmpty() || bytes.size > BackupFormat.MAX_MANIFEST_BYTES) return invalidManifest()
        return try {
            requireUniqueObjectKeys(bytes.decodeToString())
            val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
            if (root.requiredString("magic") != BackupFormat.MAGIC) return invalidManifest()
            val version = root.requiredInt("formatVersion")
            when (version) {
                BackupFormat.VERSION -> decodeEncrypted(root)
                BackupFormat.PLAINTEXT_VERSION -> decodePlaintext(root)
                else -> unsupportedVersion()
            }
        } catch (_: IllegalArgumentException) {
            invalidManifest()
        } catch (_: IllegalStateException) {
            invalidManifest()
        } catch (_: IOException) {
            invalidManifest()
        }
    }

    fun binding(manifest: BackupManifest): ByteArray {
        require(manifest.protection == BackupProtection.ENCRYPTED)
        val bound = buildJsonObject {
            put("magic", BackupFormat.MAGIC)
            put("formatVersion", manifest.formatVersion)
            put("minimumReaderVersion", manifest.minimumReaderVersion)
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

    private fun encryptedRoot(manifest: BackupManifest): JsonObject {
        require(manifest.formatVersion == BackupFormat.VERSION)
        require(manifest.minimumReaderVersion == BackupFormat.MIN_READER_VERSION)
        require(manifest.salt.size == BackupFormat.SALT_BYTES)
        require(manifest.kdfIterations == BackupFormat.KDF_ITERATIONS)
        require(manifest.checksumsPath == BackupFormat.CHECKSUMS_PATH)
        return buildJsonObject {
            putCommon(manifest)
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
                    put("path", manifest.checksumsPath)
                    put("ciphertextSize", manifest.checksumsCiphertextSize)
                    put("ciphertextSha256", manifest.checksumsCiphertextSha256)
                },
            )
        }
    }

    private fun plaintextRoot(manifest: BackupManifest): JsonObject {
        require(manifest.formatVersion == BackupFormat.PLAINTEXT_VERSION)
        require(manifest.minimumReaderVersion == BackupFormat.PLAINTEXT_MIN_READER_VERSION)
        require(manifest.salt.isEmpty())
        require(manifest.kdfIterations == 0)
        require(manifest.checksumsPath == BackupFormat.PLAINTEXT_CHECKSUMS_PATH)
        return buildJsonObject {
            putCommon(manifest)
            put("protection", "NONE")
            put(
                "checksums",
                buildJsonObject {
                    put("path", manifest.checksumsPath)
                    put("size", manifest.checksumsCiphertextSize)
                    put("sha256", manifest.checksumsCiphertextSha256)
                },
            )
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCommon(manifest: BackupManifest) {
        put("magic", BackupFormat.MAGIC)
        put("formatVersion", manifest.formatVersion)
        put("minimumReaderVersion", manifest.minimumReaderVersion)
        put("createdAtEpochMillis", manifest.createdAtEpochMillis)
        put("archiveId", Base64.getEncoder().encodeToString(manifest.archiveId))
    }

    private fun decodeEncrypted(root: JsonObject): RepositoryResult<BackupManifest> {
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
        if (root.requiredInt("minimumReaderVersion") > BackupFormat.VERSION) {
            return unsupportedVersion()
        }
        val kdf = root.requiredObject("kdf")
        requireExactKeys(kdf, setOf("algorithm", "iterations", "salt", "keyBits"))
        if (
            kdf.requiredString("algorithm") != BackupFormat.KDF_ALGORITHM ||
            kdf.requiredInt("iterations") != BackupFormat.KDF_ITERATIONS ||
            kdf.requiredInt("keyBits") != BackupFormat.KEY_BITS ||
            root.requiredString("cipher") != BackupFormat.CIPHER_ALGORITHM
        ) {
            return unsupportedVersion()
        }
        val checksums = root.requiredObject("checksums")
        requireExactKeys(checksums, setOf("path", "ciphertextSize", "ciphertextSha256"))
        if (checksums.requiredString("path") != BackupFormat.CHECKSUMS_PATH) {
            return invalidManifest()
        }
        return decodedManifest(
            root = root,
            protection = BackupProtection.ENCRYPTED,
            salt = kdf.requiredBase64("salt", BackupFormat.SALT_BYTES),
            kdfIterations = BackupFormat.KDF_ITERATIONS,
            checksumsPath = BackupFormat.CHECKSUMS_PATH,
            checksumsSize = checksums.requiredLong("ciphertextSize"),
            checksumsSha256 = checksums.requiredSha256("ciphertextSha256"),
        )
    }

    private fun decodePlaintext(root: JsonObject): RepositoryResult<BackupManifest> {
        requireExactKeys(
            root,
            setOf(
                "magic",
                "formatVersion",
                "minimumReaderVersion",
                "createdAtEpochMillis",
                "archiveId",
                "protection",
                "checksums",
            ),
        )
        if (
            root.requiredInt("minimumReaderVersion") !=
            BackupFormat.PLAINTEXT_MIN_READER_VERSION ||
            root.requiredString("protection") != "NONE"
        ) {
            return unsupportedVersion()
        }
        val checksums = root.requiredObject("checksums")
        requireExactKeys(checksums, setOf("path", "size", "sha256"))
        if (checksums.requiredString("path") != BackupFormat.PLAINTEXT_CHECKSUMS_PATH) {
            return invalidManifest()
        }
        return decodedManifest(
            root = root,
            protection = BackupProtection.PLAINTEXT,
            salt = byteArrayOf(),
            kdfIterations = 0,
            checksumsPath = BackupFormat.PLAINTEXT_CHECKSUMS_PATH,
            checksumsSize = checksums.requiredLong("size"),
            checksumsSha256 = checksums.requiredSha256("sha256"),
        )
    }

    private fun decodedManifest(
        root: JsonObject,
        protection: BackupProtection,
        salt: ByteArray,
        kdfIterations: Int,
        checksumsPath: String,
        checksumsSize: Long,
        checksumsSha256: String,
    ): RepositoryResult<BackupManifest> {
        val createdAt = root.requiredLong("createdAtEpochMillis")
        if (createdAt < 0L || checksumsSize !in 1..BackupFormat.MAX_CHECKSUMS_BYTES) {
            return invalidManifest()
        }
        return RepositoryResult.Success(
            BackupManifest(
                archiveId = root.requiredBase64("archiveId", BackupFormat.ARCHIVE_ID_BYTES),
                createdAtEpochMillis = createdAt,
                salt = salt,
                kdfIterations = kdfIterations,
                checksumsCiphertextSize = checksumsSize,
                checksumsCiphertextSha256 = checksumsSha256,
                formatVersion = root.requiredInt("formatVersion"),
                minimumReaderVersion = root.requiredInt("minimumReaderVersion"),
                protection = protection,
                checksumsPath = checksumsPath,
            ),
        )
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

    private fun unsupportedVersion(): RepositoryResult.Failure = RepositoryResult.Failure(
        AppError.BackupValidationFailure(AppError.BackupValidationReason.UNSUPPORTED_VERSION),
    )

    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
}

internal object PlaintextBackupChecksumsCodec {
    fun newWriter(destination: Writer): JsonWriter = JsonWriter(destination).apply {
        setIndent("")
        beginObject()
        name("formatVersion").value(BackupFormat.PLAINTEXT_VERSION.toLong())
        name("entries").beginArray()
    }

    fun writeEntry(writer: JsonWriter, entry: BackupEntryChecksum) {
        writer.beginObject()
        writer.name("path").value(entry.path)
        writer.name("size").value(entry.ciphertextSize)
        writer.name("sha256").value(entry.ciphertextSha256)
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
            require(json.nextInt() == BackupFormat.PLAINTEXT_VERSION)
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
        require(reader.nextName() == "size")
        val size = reader.nextLong()
        require(reader.nextName() == "sha256")
        val checksum = reader.nextString()
        require(!reader.hasNext())
        reader.endObject()
        require(path == BackupFormat.PLAINTEXT_DATABASE_PATH || ATTACHMENT_PATH_PATTERN.matches(path))
        require(size > 0L)
        require(SHA256_PATTERN.matches(checksum))
        return BackupEntryChecksum(path, size, checksum)
    }

    private val ATTACHMENT_PATH_PATTERN = Regex("attachments/[0-9]{8}\\.bin")
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
