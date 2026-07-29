package com.vaultnote.core.theme

object VaultThemes {
    val selectable: List<VaultTheme> = coreDarkThemes + extendedDarkThemes
    val default: VaultTheme = selectable.first()

    fun fromStoredId(value: String?): VaultTheme =
        selectable.firstOrNull { it.storedId == value } ?: default
}
