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
    fun `grant is bound to attachment and can be consumed once`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val token = registry.issue("attachment_1")

        assertTrue(registry.consume("attachment_1", token))
        assertFalse(registry.consume("attachment_1", token))
    }

    @Test
    fun `wrong attachment consumes and invalidates the grant`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })
        val token = registry.issue("attachment_1")

        assertFalse(registry.consume("attachment_2", token))
        assertFalse(registry.consume("attachment_1", token))
    }

    @Test
    fun `expired grant and malformed tokens are rejected`() {
        var elapsed = 1_000L
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { elapsed })
        val token = registry.issue("attachment_1")
        elapsed += 60_001L

        assertFalse(registry.consume("attachment_1", token))
        assertFalse(registry.consume("attachment_1", "short"))
        assertFalse(registry.consume("attachment_1", null))
    }

    @Test
    fun `issued tokens are distinct`() {
        val registry = ExternalAttachmentGrantRegistry(ElapsedRealtimeProvider { 1_000L })

        assertNotEquals(registry.issue("attachment_1"), registry.issue("attachment_1"))
    }
}
