package com.vaultnote.core.security

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import java.security.SecureRandom

class SecureAttachmentUriFactory(context: Context) {
    private val authority = "${context.applicationContext.packageName}.$AUTHORITY_SUFFIX"

    fun attachment(attachmentId: String, accessToken: String? = null): Uri =
        buildUri(ATTACHMENT_PATH, attachmentId, accessToken)

    fun thumbnail(attachmentId: String): Uri = buildUri(THUMBNAIL_PATH, attachmentId, null)

    private fun buildUri(path: String, attachmentId: String, accessToken: String?): Uri {
        require(SAFE_ID.matches(attachmentId))
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(path)
            .appendPath(attachmentId)
            .apply { accessToken?.let { appendQueryParameter(ACCESS_TOKEN_PARAMETER, it) } }
            .build()
    }

    companion object {
        const val AUTHORITY_SUFFIX = "secure-attachments"
        const val ATTACHMENT_PATH = "attachment"
        const val THUMBNAIL_PATH = "thumbnail"
        const val ACCESS_TOKEN_PARAMETER = "access"
        val SAFE_ID: Regex = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** One-use, short-lived authority for a user-requested handoff to an external viewer. */
class ExternalAttachmentGrantRegistry(
    private val elapsedRealtime: ElapsedRealtimeProvider =
        ElapsedRealtimeProvider(SystemClock::elapsedRealtime),
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized
    fun issue(attachmentId: String): String {
        require(SecureAttachmentUriFactory.SAFE_ID.matches(attachmentId))
        pruneExpired()
        while (grants.size >= MAX_GRANTS) grants.remove(grants.entries.first().key)
        val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.encodeToString(
            tokenBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        grants[token] = Grant(
            attachmentId = attachmentId,
            expiresAt = elapsedRealtime.nowMillis() + GRANT_LIFETIME_MILLIS,
        )
        return token
    }

    @Synchronized
    fun consume(attachmentId: String, token: String?): Boolean {
        if (token == null || token.length !in 20..64) return false
        pruneExpired()
        val grant = grants.remove(token) ?: return false
        return grant.attachmentId == attachmentId && grant.expiresAt >= elapsedRealtime.nowMillis()
    }

    @Synchronized
    fun clear() {
        grants.clear()
    }

    private fun pruneExpired() {
        val now = elapsedRealtime.nowMillis()
        grants.entries.removeAll { it.value.expiresAt < now }
    }

    private data class Grant(val attachmentId: String, val expiresAt: Long)

    private companion object {
        const val TOKEN_BYTES = 18
        const val MAX_GRANTS = 16
        const val GRANT_LIFETIME_MILLIS = 60_000L
    }
}
