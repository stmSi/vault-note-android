package com.vaultnote.core.sync.lan

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyPairingCryptoTest {
    private val crypto = NearbyPairingCrypto()

    @Test
    fun derivesRustInteropVectorAndAuthenticatesApprovedPayload() {
        val clientPublicBytes = decode(CLIENT_PUBLIC)
        val initiator = NearbyPairingInitiator(
            keyPair = fixedClientKeyPair(clientPublicBytes),
            clientPublicKey = clientPublicBytes,
        )
        val candidate = LanRelayCandidate(
            serviceName = "VaultNote Desktop",
            hostAddress = "192.168.1.7",
            port = 8787,
            vaultId = VAULT_ID,
            certificateSha256 = CERTIFICATE,
        )
        val session = crypto.completeHandshake(
            initiator = initiator,
            candidate = candidate,
            requestId = REQUEST_ID,
            serverPublicKeyBase64Url = SERVER_PUBLIC,
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000,
            responseVaultId = VAULT_ID,
            responseCertificateSha256 = CERTIFICATE,
        )
        assertEquals("733 504", session.verificationCode)
        assertEquals(EXPECTED_CONTEXT, encode(session.contextSha256))
        assertEquals(EXPECTED_KEY, encode(session.encryptionKey))

        val nonce = ByteArray(12) { 9 }
        val expectedMasterKey = ByteArray(32) { 7 }
        val plaintext = JSONObject()
            .put("version", 1)
            .put("vaultId", VAULT_ID)
            .put("certificateSha256", CERTIFICATE)
            .put("authenticationToken", "vns_interop-token_123456789")
            .put("masterKey", encode(expectedMasterKey))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(session.encryptionKey, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(session.contextSha256)
        val encrypted = cipher.doFinal(plaintext)
        plaintext.fill(0)

        val payload = crypto.decryptApprovedPayload(
            session,
            encode(nonce),
            encode(encrypted),
        )
        assertEquals("vns_interop-token_123456789", payload.authenticationToken)
        assertArrayEquals(expectedMasterKey, payload.masterKey)
        payload.clear()
        session.clear()
        expectedMasterKey.fill(0)
        nonce.fill(0)
        encrypted.fill(0)
    }

    private fun fixedClientKeyPair(publicBytes: ByteArray): KeyPair {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec("secp256r1"))
        val curve = parameters.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        val privateKey = factory.generatePrivate(
            ECPrivateKeySpec(BigInteger(1, ByteArray(32) { 1 }), curve),
        )
        val publicKey = factory.generatePublic(
            ECPublicKeySpec(
                ECPoint(
                    BigInteger(1, publicBytes.copyOfRange(1, 33)),
                    BigInteger(1, publicBytes.copyOfRange(33, 65)),
                ),
                curve,
            ),
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun encode(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    companion object {
        private const val REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val VAULT_ID = "vault-interop"
        private val CERTIFICATE = "a".repeat(64)
        private const val CLIENT_PUBLIC =
            "BG_wO5SSQc4drdQ1GeaWDgqFtBppoFwygQOqK84VlMoWPE91OlW_AdxT9sCwx-7ni0DG_30lqW4igrmJzvccFEo"
        private const val SERVER_PUBLIC =
            "BFUPRxAD89-Xw99QaseX9nIfsaH7e49vg9IkSYplyI4kE2CT1wEuUJpzcVy9CwCjzA_0tcAbP_oZarH7MnA2uOY"
        private const val EXPECTED_CONTEXT =
            "PkDJYg-oJhQucJIBws3CGlQ2ttervOeR3Gg2fjNII4E"
        private const val EXPECTED_KEY =
            "NeSJ393outL1rB3FM_GdmCllZ8s3mKPO4pbO-8JjygY"
    }
}
