package com.vaultnote.core.common

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.vaultnote.R
import com.vaultnote.core.common.model.VaultItemColor

data class ItemColorStyle(
    @param:ColorRes val surfaceColor: Int,
    @param:ColorRes val titleColor: Int,
    @param:StringRes val label: Int,
    val followsTheme: Boolean = false,
) {
    fun resolveSurface(context: Context): Int =
        if (followsTheme) themeSurface(context) else ContextCompat.getColor(context, surfaceColor)

    fun resolveTitle(context: Context): Int {
        if (followsTheme) {
            return MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurface,
                0,
            )
        }
        return ContextCompat.getColor(context, titleColor)
    }

    private fun themeSurface(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0)
}

fun VaultItemColor.toStyle(): ItemColorStyle = when (this) {
    VaultItemColor.DEFAULT -> ItemColorStyle(
        R.color.item_default_surface,
        R.color.item_default_title,
        R.string.item_color_default,
        followsTheme = true,
    )
    VaultItemColor.RED -> ItemColorStyle(
        R.color.item_red_surface_dark,
        R.color.item_red_title_dark,
        R.string.item_color_red,
    )
    VaultItemColor.ORANGE -> ItemColorStyle(
        R.color.item_orange_surface_dark,
        R.color.item_orange_title_dark,
        R.string.item_color_orange,
    )
    VaultItemColor.YELLOW -> ItemColorStyle(
        R.color.item_yellow_surface_dark,
        R.color.item_yellow_title_dark,
        R.string.item_color_yellow,
    )
    VaultItemColor.GREEN -> ItemColorStyle(
        R.color.item_green_surface_dark,
        R.color.item_green_title_dark,
        R.string.item_color_green,
    )
    VaultItemColor.BLUE -> ItemColorStyle(
        R.color.item_blue_surface_dark,
        R.color.item_blue_title_dark,
        R.string.item_color_blue,
    )
    VaultItemColor.PURPLE -> ItemColorStyle(
        R.color.item_purple_surface_dark,
        R.color.item_purple_title_dark,
        R.string.item_color_purple,
    )
}
