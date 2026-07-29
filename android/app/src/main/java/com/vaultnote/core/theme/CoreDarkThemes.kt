package com.vaultnote.core.theme

import com.vaultnote.R

internal val coreDarkThemes = listOf(
    VaultTheme("dark", R.style.Theme_VaultNote_Dark, R.string.theme_dark, colors(0x111318, 0x151C2A, 0x1A1723)),
    VaultTheme("obsidian", R.style.Theme_VaultNote_Obsidian, R.string.theme_obsidian, colors(0x0D0F12, 0x171A1F, 0x111419)),
    VaultTheme("graphite", R.style.Theme_VaultNote_Graphite, R.string.theme_graphite, colors(0x181A1D, 0x23262A, 0x1A1E21)),
    VaultTheme("aurora", R.style.Theme_VaultNote_Aurora, R.string.theme_aurora, colors(0x111923, 0x17313A, 0x232543)),
    VaultTheme("nebula", R.style.Theme_VaultNote_Nebula, R.string.theme_nebula, colors(0x14121F, 0x282044, 0x17273A)),
    VaultTheme("amethyst", R.style.Theme_VaultNote_Amethyst, R.string.theme_amethyst, colors(0x18121F, 0x30213C, 0x21172B)),
    VaultTheme("emerald", R.style.Theme_VaultNote_Emerald, R.string.theme_emerald, colors(0x0D1916, 0x15352A, 0x102521)),
    VaultTheme("forest", R.style.Theme_VaultNote_Forest, R.string.theme_forest, colors(0x101810, 0x22321C, 0x16231A)),
    VaultTheme("cobalt", R.style.Theme_VaultNote_Cobalt, R.string.theme_cobalt, colors(0x0E1726, 0x142D52, 0x151E35)),
    VaultTheme("indigo", R.style.Theme_VaultNote_Indigo, R.string.theme_indigo, colors(0x121426, 0x242953, 0x191B36)),
    VaultTheme("crimson", R.style.Theme_VaultNote_Crimson, R.string.theme_crimson, colors(0x1D1115, 0x3A1D27, 0x25141B)),
    VaultTheme("ember", R.style.Theme_VaultNote_Ember, R.string.theme_ember, colors(0x1C1410, 0x3B2416, 0x261812)),
    VaultTheme("mocha", R.style.Theme_VaultNote_Mocha, R.string.theme_mocha, colors(0x1A1513, 0x30231E, 0x231B18)),
    VaultTheme("dusk", R.style.Theme_VaultNote_Dusk, R.string.theme_dusk, colors(0x171521, 0x30283D, 0x1D2432)),
    VaultTheme("cyber", R.style.Theme_VaultNote_Cyber, R.string.theme_cyber, colors(0x07191C, 0x0B3035, 0x10232D)),
)

internal fun colors(start: Int, middle: Int, end: Int): IntArray =
    intArrayOf(
        0xFF000000.toInt() or start,
        0xFF000000.toInt() or middle,
        0xFF000000.toInt() or end,
    )
