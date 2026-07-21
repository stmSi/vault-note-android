package com.vaultnote.core.common

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.vaultnote.R
import com.vaultnote.core.common.model.VaultItemColor

data class ItemColorStyle(
    @param:ColorRes val surfaceColor: Int,
    @param:ColorRes val titleColor: Int,
    @param:StringRes val label: Int,
)

fun VaultItemColor.toStyle(): ItemColorStyle = when (this) {
    VaultItemColor.DEFAULT -> ItemColorStyle(
        R.color.item_default_surface,
        R.color.item_default_title,
        R.string.item_color_default,
    )
    VaultItemColor.RED -> ItemColorStyle(
        R.color.item_red_surface,
        R.color.item_red_title,
        R.string.item_color_red,
    )
    VaultItemColor.ORANGE -> ItemColorStyle(
        R.color.item_orange_surface,
        R.color.item_orange_title,
        R.string.item_color_orange,
    )
    VaultItemColor.YELLOW -> ItemColorStyle(
        R.color.item_yellow_surface,
        R.color.item_yellow_title,
        R.string.item_color_yellow,
    )
    VaultItemColor.GREEN -> ItemColorStyle(
        R.color.item_green_surface,
        R.color.item_green_title,
        R.string.item_color_green,
    )
    VaultItemColor.BLUE -> ItemColorStyle(
        R.color.item_blue_surface,
        R.color.item_blue_title,
        R.string.item_color_blue,
    )
    VaultItemColor.PURPLE -> ItemColorStyle(
        R.color.item_purple_surface,
        R.color.item_purple_title,
        R.string.item_color_purple,
    )
}
