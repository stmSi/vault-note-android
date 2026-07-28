package com.vaultnote.core.sync.lan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.DefaultDispatcherProvider
import com.vaultnote.core.common.DispatcherProvider
import com.vaultnote.core.common.RepositoryResult
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RelayConnectionSecrets(
    val hostAddress: String,
    val port: Int,
    val dnsName: String,
    val vaultId: String,
    val certificateSha256: String,
    val authenticationToken: String,
    val masterKey: ByteArray,
) {
    fun clearKey() {
        masterKey.fill(0)
    }
}

data class RelayConnectionSummary(
    val hostAddress: String,
    val port: Int,
    val vaultId: String,
    val certificateSha256: String,
)

sealed interface RelayConnectionState {
    data object Loading : RelayConnectionState
    data object NotConfigured : RelayConnectionState
    data class Configured(val summary: RelayConnectionSummary) : RelayConnectionState
    data object Corrupted : RelayConnectionState
}

interface SyncCredentialStore {
    val state: StateFlow<RelayConnectionState>

    suspend fun load(): RepositoryResult<RelayConnectionSecrets?>

    suspend fun save(connection: RelayConnectionSecrets): RepositoryResult<Unit>

    suspend fun updateEndpoint(hostAddress: String, port: Int): RepositoryResult<Unit>

    suspend fun clear(): RepositoryResult<Unit>
}

/**
 * Stores the relay token and derived sync key only as AES-GCM ciphertext protected by a
 * non-exportable Android Keystore key. The sync password itself is never persisted.
 */
class AndroidSyncCredentialStore(
    context: Context,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) : SyncCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<RelayConnectionState>(RelayConnectionState.Loading)
    override val state: StateFlow<RelayConnectionState> = mutableState.asStateFlow()

    override suspend fun load(): RepositoryResult<RelayConnectionSecrets?> = mutex.withLock {
        withContext(dispatchers.io) {
            val encoded = preferences.getString(CREDENTIALS_KEY, null)
            if (encoded == null) {
                mutableState.value = RelayConnectionState.NotConfigured
                return@withContext RepositoryResult.Success(null)
            }
            try {
                val envelope = Base64.decode(encoded, Base64.NO_WRAP)
                val connection = decodeConnection(decrypt(envelope))
                mutableState.value = RelayConnectionState.Configured(connection.toSummary())
                RepositoryResult.Success(connection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                mutableState.value = RelayConnectionState.Corrupted
                RepositoryResult.Failure(AppError.DecryptionFailure())
            } catch (_: GeneralSecurityException) {
                mutableState.value = RelayConnectionState.Corrupted
                RepositoryResult.Failure(AppError.DecryptionFailure())
            } catch (_: SecurityException) {
                mutableState.value = RelayConnectionState.Corrupted
                RepositoryResult.Failure(AppError.DecryptionFailure())
            }
        }
    }

    override suspend fun save(
        connection: RelayConnectionSecrets,
    ): RepositoryResult<Unit> = mutex.withLock {
        withContext(dispatchers.io) {
            if (!connection.isValid()) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("sync_connection", "invalid"),
                )
            }
            try {
                val plaintext = encodeConnection(connection)
                val encrypted = try {
                    encrypt(plaintext)
                } finally {
                    plaintext.fill(0)
                }
                val committed = preferences.edit()
                    .putString(CREDENTIALS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
                encrypted.fill(0)
                if (!committed) {
                    return@withContext RepositoryResult.Failure(AppError.EncryptionFailure())
                }
                mutableState.value = RelayConnectionState.Configured(connection.toSummary())
                RepositoryResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GeneralSecurityException) {
                RepositoryResult.Failure(AppError.EncryptionFailure())
            } catch (_: SecurityException) {
                RepositoryResult.Failure(AppError.EncryptionFailure())
            }
        }
    }

    override suspend fun updateEndpoint(
        hostAddress: String,
        port: Int,
    ): RepositoryResult<Unit> = mutex.withLock {
        withContext(dispatchers.io) {
            if (!isValidHost(hostAddress) || port !in 1..65_535) {
                return@withContext RepositoryResult.Failure(
                    AppError.InvalidInput("sync_endpoint", "invalid"),
                )
            }
            val encoded = preferences.getString(CREDENTIALS_KEY, null)
                ?: return@withContext RepositoryResult.Failure(AppError.AuthenticationExpired)
            var current: RelayConnectionSecrets? = null
            try {
                current = decodeConnection(decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
                val updated = current.copy(hostAddress = hostAddress, port = port)
                val plaintext = encodeConnection(updated)
                val encrypted = try {
                    encrypt(plaintext)
                } finally {
                    plaintext.fill(0)
                }
                val committed = preferences.edit()
                    .putString(CREDENTIALS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .commit()
                encrypted.fill(0)
                if (!committed) {
                    return@withContext RepositoryResult.Failure(AppError.EncryptionFailure())
                }
                mutableState.value = RelayConnectionState.Configured(updated.toSummary())
                RepositoryResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                mutableState.value = RelayConnectionState.Corrupted
                RepositoryResult.Failure(AppError.DecryptionFailure())
            } catch (_: GeneralSecurityException) {
                mutableState.value = RelayConnectionState.Corrupted
                RepositoryResult.Failure(AppError.DecryptionFailure())
            } finally {
                current?.clearKey()
            }
        }
    }

    override suspend fun clear(): RepositoryResult<Unit> = mutex.withLock {
        withContext(dispatchers.io) {
            try {
                if (!preferences.edit().remove(CREDENTIALS_KEY).commit()) {
                    return@withContext RepositoryResult.Failure(AppError.EncryptionFailure())
                }
                val keyStore = loadKeyStore()
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
                mutableState.value = RelayConnectionState.NotConfigured
                RepositoryResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: GeneralSecurityException) {
                RepositoryResult.Failure(AppError.EncryptionFailure())
            } catch (_: SecurityException) {
                RepositoryResult.Failure(AppError.EncryptionFailure())
            }
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(CREDENTIAL_AAD)
        val nonce = cipher.iv
        require(nonce.size == GCM_NONCE_BYTES)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(2 + nonce.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(nonce.size.toByte())
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        if (envelope.size < 2 + GCM_NONCE_BYTES + GCM_TAG_BYTES) {
            throw GeneralSecurityException("Truncated credentials")
        }
        val buffer = ByteBuffer.wrap(envelope)
        if (buffer.get() != FORMAT_VERSION) throw GeneralSecurityException("Unsupported credentials")
        val nonceLength = buffer.get().toInt() and 0xff
        if (nonceLength != GCM_NONCE_BYTES || buffer.remaining() <= nonceLength + GCM_TAG_BYTES) {
            throw GeneralSecurityException("Invalid credentials")
        }
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(CREDENTIAL_AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey =
        getExistingKeyOrNull() ?: throw GeneralSecurityException("Missing credentials key")

    private fun getExistingKeyOrNull(): SecretKey? =
        (loadKeyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun encodeConnection(connection: RelayConnectionSecrets): ByteArray = buildJsonObject {
        put("version", JSON_VERSION)
        put("hostAddress", connection.hostAddress)
        put("port", connection.port)
        put("dnsName", connection.dnsName)
        put("vaultId", connection.vaultId)
        put("certificateSha256", connection.certificateSha256)
        put("authenticationToken", connection.authenticationToken)
        put("masterKey", Base64.encodeToString(connection.masterKey, Base64.NO_WRAP))
    }.toString().toByteArray(StandardCharsets.UTF_8)

    private fun decodeConnection(bytes: ByteArray): RelayConnectionSecrets {
        if (bytes.size !in 1..MAX_JSON_BYTES) throw GeneralSecurityException("Invalid credentials")
        val json = try {
            Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
                ?: throw GeneralSecurityException("Invalid credentials")
        } finally {
            bytes.fill(0)
        }
        val requiredKeys = setOf(
            "version",
            "hostAddress",
            "port",
            "dnsName",
            "vaultId",
            "certificateSha256",
            "authenticationToken",
            "masterKey",
        )
        if (json.keys != requiredKeys || json.requiredInt("version") != JSON_VERSION) {
            throw GeneralSecurityException("Invalid credentials")
        }
        val connection = RelayConnectionSecrets(
            hostAddress = json.requiredString("hostAddress"),
            port = json.requiredInt("port"),
            dnsName = json.requiredString("dnsName"),
            vaultId = json.requiredString("vaultId"),
            certificateSha256 = json.requiredString("certificateSha256"),
            authenticationToken = json.requiredString("authenticationToken"),
            masterKey = Base64.decode(json.requiredString("masterKey"), Base64.NO_WRAP),
        )
        if (!connection.isValid()) {
            connection.clearKey()
            throw GeneralSecurityException("Invalid credentials")
        }
        return connection
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.content
            ?.takeIf { it.length <= MAX_FIELD_CHARACTERS }
            ?: throw GeneralSecurityException("Invalid credentials")

    private fun JsonObject.requiredInt(key: String): Int =
        try {
            get(key)?.jsonPrimitive?.int ?: throw GeneralSecurityException("Invalid credentials")
        } catch (_: NumberFormatException) {
            throw GeneralSecurityException("Invalid credentials")
        }

    private fun RelayConnectionSecrets.isValid(): Boolean =
        isValidHost(hostAddress) &&
            port in 1..65_535 &&
            dnsName.length in 1..253 &&
            dnsName.endsWith(".local") &&
            SAFE_DNS.matches(dnsName) &&
            vaultId.length in 1..128 &&
            SAFE_ID.matches(vaultId) &&
            SHA256.matches(certificateSha256) &&
            authenticationToken.startsWith("vns_") &&
            authenticationToken.length in 16..128 &&
            masterKey.size == MASTER_KEY_BYTES

    private fun RelayConnectionSecrets.toSummary(): RelayConnectionSummary =
        RelayConnectionSummary(hostAddress, port, vaultId, certificateSha256)

    private fun isValidHost(value: String): Boolean =
        value.length in 1..255 &&
            value.none(Char::isWhitespace) &&
            value.none { it == '/' || it == '\\' || it == '\u0000' } &&
            SAFE_HOST.matches(value)

    private companion object {
        const val PREFERENCES_NAME = "vaultnote_sync_connection"
        const val CREDENTIALS_KEY = "credentials_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaultnote.sync.credentials.aes.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_BITS = 256
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val MASTER_KEY_BYTES = 32
        const val JSON_VERSION = 1
        const val MAX_JSON_BYTES = 16 * 1024
        const val MAX_FIELD_CHARACTERS = 512
        val FORMAT_VERSION: Byte = 1
        val CREDENTIAL_AAD = "VaultNote Sync Credentials v1".toByteArray(StandardCharsets.UTF_8)
        val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        val SAFE_DNS = Regex("[A-Za-z0-9.-]+")
        val SAFE_HOST = Regex("[A-Za-z0-9._:%-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
