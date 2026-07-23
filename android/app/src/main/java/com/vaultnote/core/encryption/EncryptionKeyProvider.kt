package com.vaultnote.core.encryption

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

interface EncryptionKeyProvider {
    val currentKeyVersion: Int

    /** Returns a key for decryption, or null when that version is permanently unavailable. */
    fun getKey(keyVersion: Int): SecretKey?

    /** Returns the current key, creating it inside its protected provider when needed. */
    fun getOrCreateCurrentKey(): SecretKey
}

/**
 * Stores non-exportable AES keys in Android Keystore. Aliases contain only a format namespace and
 * integer version; key bytes are never written to preferences, Room, files, logs, or backups.
 */
class AndroidKeystoreKeyProvider : EncryptionKeyProvider {
    override val currentKeyVersion: Int = CURRENT_KEY_VERSION

    @Synchronized
    override fun getKey(keyVersion: Int): SecretKey? {
        if (keyVersion !in 1..MAX_SUPPORTED_KEY_VERSION) return null
        val store = loadStore()
        return (store.getEntry(aliasFor(keyVersion), null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    @Synchronized
    override fun getOrCreateCurrentKey(): SecretKey {
        getKey(currentKeyVersion)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                aliasFor(currentKeyVersion),
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

    private fun loadStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aliasFor(version: Int): String = "$KEY_ALIAS_PREFIX$version"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "vaultnote.attachment.aes.v"
        const val CURRENT_KEY_VERSION = 1
        const val MAX_SUPPORTED_KEY_VERSION = 1_000_000
        const val AES_KEY_BITS = 256
    }
}
