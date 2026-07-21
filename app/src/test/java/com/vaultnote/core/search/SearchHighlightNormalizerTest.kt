package com.vaultnote.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHighlightNormalizerTest {
    @Test
    fun `prefix query highlights only characters entered by the user`() {
        assertEquals(
            "${start}Pa${end}per receipt",
            SearchHighlightNormalizer.retainTypedPrefixes(
                "${start}Paper${end} receipt",
                listOf("pa"),
            ),
        )
    }

    @Test
    fun `each marked filename token uses its matching query prefix`() {
        assertEquals(
            "${start}Sam${end}sung_scan_${start}202${end}6.pdf",
            SearchHighlightNormalizer.retainTypedPrefixes(
                "${start}Samsung${end}_scan_${start}2026${end}.pdf",
                listOf("sam", "202"),
            ),
        )
    }

    @Test
    fun `accent insensitive match retains the original displayed characters`() {
        assertEquals(
            "${start}Café${end}ine",
            SearchHighlightNormalizer.retainTypedPrefixes(
                "${start}Caféine${end}",
                listOf("cafe"),
            ),
        )
    }

    private companion object {
        const val start = RoomSearchRepository.HIGHLIGHT_START
        const val end = RoomSearchRepository.HIGHLIGHT_END
    }
}
