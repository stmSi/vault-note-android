package com.vaultnote.core.theme

import android.content.Context
import androidx.core.content.edit

class VaultThemePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun selectedTheme(): VaultTheme =
        VaultThemes.fromStoredId(preferences.getString(THEME_KEY, null))

    fun select(theme: VaultTheme) {
        preferences.edit { putString(THEME_KEY, theme.storedId) }
    }

    private companion object {
        const val PREFERENCES_NAME = "vaultnote_appearance"
        const val THEME_KEY = "theme"
    }
}
