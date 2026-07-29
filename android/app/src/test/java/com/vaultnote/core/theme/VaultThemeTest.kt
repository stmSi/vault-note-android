package com.vaultnote.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VaultThemeTest {
    @Test
    fun catalogContainsThirtyUniqueDarkPresetsIncludingTokyoNight() {
        val presets = VaultThemes.selectable

        assertEquals(30, presets.size)
        assertEquals(presets.size, presets.map(VaultTheme::storedId).toSet().size)
        assertEquals(presets.size, presets.map(VaultTheme::styleResource).toSet().size)
        assertEquals("tokyo_night", presets.first { it.storedId == "tokyo_night" }.storedId)
    }

    @Test
    fun storedThemeFallsBackSafely() {
        assertSame(
            VaultThemes.selectable.first { it.storedId == "aurora" },
            VaultThemes.fromStoredId("aurora"),
        )
        assertSame(VaultThemes.default, VaultThemes.fromStoredId("light"))
        assertSame(VaultThemes.default, VaultThemes.fromStoredId("system"))
        assertSame(VaultThemes.default, VaultThemes.fromStoredId("unknown-theme"))
        assertSame(VaultThemes.default, VaultThemes.fromStoredId(null))
    }

    @Test
    fun everyStoredThemeIdRoundTripsThroughCatalog() {
        val tokyoNight = VaultThemes.selectable.first { it.storedId == "tokyo_night" }

        assertSame(tokyoNight, VaultThemes.fromStoredId(tokyoNight.storedId))
        VaultThemes.selectable.forEach { theme ->
            assertSame(theme, VaultThemes.fromStoredId(theme.storedId))
        }
    }
}
