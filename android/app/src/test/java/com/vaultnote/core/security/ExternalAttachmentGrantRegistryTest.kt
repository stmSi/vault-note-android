package com.vaultnote.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalAttachmentGrantRegistryTest {
    @Test
    fun `grant supports bounded repeated reads required by external viewers`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val token = registry.issue("attachment_1")

        repeat(8) { assertTrue(registry.acquireContentRead("attachment_1", token)) }
        assertFalse(registry.acquireContentRead("attachment_1", token))
    }

    @Test
    fun `wrong attachment cannot use or invalidate the grant`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val token = registry.issue("attachment_1")

        assertFalse(registry.acquireContentRead("attachment_2", token))
        assertTrue(registry.validate("attachment_1", token))
        assertTrue(registry.acquireContentRead("attachment_1", token))
    }

    @Test
    fun `expired grant and malformed tokens are rejected`() {
        var elapsed = 1_000L
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { elapsed })
        val token = registry.issue("attachment_1")
        elapsed += 300_001L

        assertFalse(registry.acquireContentRead("attachment_1", token))
        assertFalse(registry.validate("attachment_1", "short"))
        assertFalse(registry.validate("attachment_1", null))
    }

    @Test
    fun `issued tokens are distinct`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })

        assertNotEquals(registry.issue("attachment_1"), registry.issue("attachment_1"))
    }
}
