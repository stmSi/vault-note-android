package com.vaultnote.core.sync.lan

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal data class NearbyPairingInitiator(
    val keyPair: KeyPair,
    val clientPublicKey: ByteArray,
)

internal data class NearbyPairingSession(
    val hostAddress: String,
    val port: Int,
    val vaultId: String,
    val certificateSha256: String,
    val requestId: String,
    val verificationCode: String,
    val expiresAtEpochMillis: Long,
    val encryptionKey: ByteArray,
    val contextSha256: ByteArray,
) {
    fun clear() {
        encryptionKey.fill(0)
        contextSha256.fill(0)
    }
}

internal data class NearbyPairingPayload(
    val authenticationToken: String,
    val masterKey: ByteArray,
) {
    fun clear() {
        masterKey.fill(0)
    }
}

/**
 * Ephemeral P-256 ECDH pairing for nearby devices.
 *
 * A transcript-bound HKDF derives an AES-256-GCM key and the six-digit safety code displayed
 * independently on Android and Desktop. The relay credential and sync key are accepted only
 * after authenticated decryption and identity validation.
 */
internal class NearbyPairingCrypto {
    fun createInitiator(): NearbyPairingInitiator {
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE_NAME))
        val keyPair = generator.generateKeyPair()
        return NearbyPairingInitiator(
            keyPair = keyPair,
            clientPublicKey = encodePublicKey(keyPair.public as ECPublicKey),
        )
    }

    fun completeHandshake(
        initiator: NearbyPairingInitiator,
        candidate: LanRelayCandidate,
        requestId: String,
        serverPublicKeyBase64Url: String,
        expiresAtEpochMillis: Long,
        responseVaultId: String,
        responseCertificateSha256: String,
    ): NearbyPairingSession {
        require(SAFE_ID.matches(requestId))
        require(expiresAtEpochMillis > System.currentTimeMillis())
        require(responseVaultId == candidate.vaultId)
        require(responseCertificateSha256 == candidate.certificateSha256)
        val serverPublicBytes = decodeBase64Url(serverPublicKeyBase64Url)
        require(serverPublicBytes.size == PUBLIC_KEY_BYTES)
        val serverPublic = decodePublicKey(serverPublicBytes)
        val agreement = KeyAgreement.getInstance(ECDH_ALGORITHM)
        agreement.init(initiator.keyPair.private)
        agreement.doPhase(serverPublic, true)
        val rawSecret = agreement.generateSecret()
        val sharedSecret = normalizeSecret(rawSecret)
        rawSecret.fill(0)
        val context = pairingContext(
            requestId,
            initiator.clientPublicKey,
            serverPublicBytes,
            responseVaultId,
            responseCertificateSha256,
        )
        serverPublicBytes.fill(0)
        val contextSha256 = MessageDigest.getInstance(SHA256).digest(context)
        context.fill(0)
        val encryptionKey = hkdfSha256(sharedSecret, contextSha256, KEY_INFO, KEY_BYTES)
        val codeBytes = hkdfSha256(sharedSecret, contextSha256, CODE_INFO, CODE_BYTES)
        sharedSecret.fill(0)
        val codeNumber = ByteBuffer.wrap(codeBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .int
            .toLong()
            .and(0xffff_ffffL)
            .rem(1_000_000L)
        codeBytes.fill(0)
        return NearbyPairingSession(
            hostAddress = candidate.hostAddress,
            port = candidate.port,
            vaultId = responseVaultId,
            certificateSha256 = responseCertificateSha256,
            requestId = requestId,
            verificationCode = String.format(
                java.util.Locale.ROOT,
                "%03d %03d",
                codeNumber / 1_000,
                codeNumber % 1_000,
            ),
            expiresAtEpochMillis = expiresAtEpochMillis,
            encryptionKey = encryptionKey,
            contextSha256 = contextSha256,
        )
    }

    fun decryptApprovedPayload(
        session: NearbyPairingSession,
        nonceBase64Url: String,
        encryptedPayloadBase64Url: String,
    ): NearbyPairingPayload {
        val nonce = decodeBase64Url(nonceBase64Url)
        require(nonce.size == GCM_NONCE_BYTES)
        val ciphertext = decodeBase64Url(encryptedPayloadBase64Url)
        require(ciphertext.size in (GCM_TAG_BYTES + 1)..MAX_ENCRYPTED_PAYLOAD_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(session.encryptionKey, AES_ALGORITHM),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(session.contextSha256)
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
        return try {
            val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            require(json.length() == 5)
            require(json.getInt("version") == PAIRING_VERSION)
            require(json.getString("vaultId") == session.vaultId)
            require(json.getString("certificateSha256") == session.certificateSha256)
            val authenticationToken = json.getString("authenticationToken")
            require(
                authenticationToken.startsWith("vns_") &&
                    authenticationToken.length in 16..128,
            )
            val masterKey = decodeBase64Url(json.getString("masterKey"))
            require(masterKey.size == KEY_BYTES)
            NearbyPairingPayload(authenticationToken, masterKey)
        } finally {
            plaintext.fill(0)
        }
    }

    fun encodeBase64Url(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun encodePublicKey(publicKey: ECPublicKey): ByteArray {
        val output = ByteArray(PUBLIC_KEY_BYTES)
        output[0] = UNCOMPRESSED_POINT
        fixedCoordinate(publicKey.w.affineX).copyInto(output, 1)
        fixedCoordinate(publicKey.w.affineY).copyInto(output, 1 + COORDINATE_BYTES)
        return output
    }

    private fun decodePublicKey(encoded: ByteArray): java.security.PublicKey {
        require(encoded.size == PUBLIC_KEY_BYTES && encoded[0] == UNCOMPRESSED_POINT)
        val parameters = AlgorithmParameters.getInstance(EC_ALGORITHM)
        parameters.init(ECGenParameterSpec(CURVE_NAME))
        val curve = parameters.getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(
            BigInteger(1, encoded.copyOfRange(1, 1 + COORDINATE_BYTES)),
            BigInteger(1, encoded.copyOfRange(1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES)),
        )
        return KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(ECPublicKeySpec(point, curve))
    }

    private fun fixedCoordinate(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val withoutSign = if (encoded.size > COORDINATE_BYTES) {
            encoded.copyOfRange(encoded.size - COORDINATE_BYTES, encoded.size)
        } else {
            encoded
        }
        require(withoutSign.size <= COORDINATE_BYTES)
        return ByteArray(COORDINATE_BYTES).also {
            withoutSign.copyInto(it, COORDINATE_BYTES - withoutSign.size)
        }
    }

    private fun normalizeSecret(raw: ByteArray): ByteArray {
        require(raw.isNotEmpty() && raw.size <= KEY_BYTES)
        return ByteArray(KEY_BYTES).also { raw.copyInto(it, KEY_BYTES - raw.size) }
    }

    private fun pairingContext(
        requestId: String,
        clientPublicKey: ByteArray,
        serverPublicKey: ByteArray,
        vaultId: String,
        certificateSha256: String,
    ): ByteArray {
        val parts = arrayOf(
            CONTEXT_PREFIX,
            requestId.toByteArray(StandardCharsets.UTF_8),
            clientPublicKey,
            serverPublicKey,
            vaultId.toByteArray(StandardCharsets.UTF_8),
            certificateSha256.toByteArray(StandardCharsets.UTF_8),
        )
        val size = parts.sumOf { Integer.BYTES + it.size }
        val output = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        parts.forEach { part ->
            output.putInt(part.size)
            output.put(part)
        }
        return output.array()
    }

    private fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int,
    ): ByteArray {
        require(outputLength in 1..SHA256_BYTES)
        val extract = Mac.getInstance(HMAC_SHA256)
        extract.init(SecretKeySpec(salt, HMAC_SHA256))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
        return try {
            val expand = Mac.getInstance(HMAC_SHA256)
            expand.init(SecretKeySpec(pseudoRandomKey, HMAC_SHA256))
            expand.update(info)
            expand.update(1.toByte())
            expand.doFinal().copyOf(outputLength)
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    private fun decodeBase64Url(value: String): ByteArray {
        require(value.length in 2..MAX_BASE64_LENGTH)
        return try {
            Base64.getUrlDecoder().decode(value)
        } catch (failure: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid nearby pairing encoding", failure)
        }
    }

    companion object {
        const val PAIRING_VERSION = 1
        private const val EC_ALGORITHM = "EC"
        private const val ECDH_ALGORITHM = "ECDH"
        private const val CURVE_NAME = "secp256r1"
        private const val SHA256 = "SHA-256"
        private const val HMAC_SHA256 = "HmacSHA256"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val SHA256_BYTES = 32
        private const val CODE_BYTES = 4
        private const val COORDINATE_BYTES = 32
        private const val PUBLIC_KEY_BYTES = 65
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val MAX_BASE64_LENGTH = 8 * 1024
        private const val MAX_ENCRYPTED_PAYLOAD_BYTES = 8 * 1024
        private const val UNCOMPRESSED_POINT: Byte = 0x04
        private val SAFE_ID = Regex("[A-Za-z0-9-]{36}")
        private val CONTEXT_PREFIX =
            "VaultNote Nearby Pairing v1".toByteArray(StandardCharsets.UTF_8)
        private val KEY_INFO =
            "VaultNote nearby pairing encryption key v1".toByteArray(StandardCharsets.UTF_8)
        private val CODE_INFO =
            "VaultNote nearby pairing verification code v1".toByteArray(StandardCharsets.UTF_8)
    }
}
