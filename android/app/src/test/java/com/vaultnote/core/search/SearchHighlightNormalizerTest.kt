package com.vaultnote.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHighlightNormalizerTest {
    @Test
    fun `prefix query highlights only characters entered by the user`() {
        assertEquals(
            "${start}Pa${end}per receipt",
            SearchHighlightNormalizer.markTypedPrefixes(
                "${start}Paper${end} receipt",
                listOf("pa"),
            ),
        )
    }

    @Test
    fun `each marked filename token uses its matching query prefix`() {
        assertEquals(
            "${start}Sam${end}sung_scan_${start}202${end}6.pdf",
            SearchHighlightNormalizer.markTypedPrefixes(
                "${start}Samsung${end}_scan_${start}2026${end}.pdf",
                listOf("sam", "202"),
            ),
        )
    }

    @Test
    fun `accent insensitive match retains the original displayed characters`() {
        assertEquals(
            "${start}Café${end}ine",
            SearchHighlightNormalizer.markTypedPrefixes(
                "${start}Caféine${end}",
                listOf("cafe"),
            ),
        )
    }

    @Test
    fun `missing FTS markers do not suppress visible prefix highlighting`() {
        assertEquals(
            "Receipt for ${start}Pa${end}per.pdf",
            SearchHighlightNormalizer.markTypedPrefixes(
                "Receipt for Paper.pdf",
                listOf("pa"),
            ),
        )
    }

    @Test
    fun `ordered non-adjacent characters are highlighted in their original positions`() {
        assertEquals(
            "${start}B${end}ang${start}k${end}o${start}k${end}",
            SearchHighlightNormalizer.markTypedSubsequences("Bangkok", listOf("bkk")),
        )
    }

    @Test
    fun `subsequence highlighting remains accent insensitive`() {
        assertEquals(
            "${start}C${end}af${start}é${end}in${start}e${end}",
            SearchHighlightNormalizer.markTypedSubsequences("Caféine", listOf("cee")),
        )
    }

    private companion object {
        const val start = RoomSearchRepository.HIGHLIGHT_START
        const val end = RoomSearchRepository.HIGHLIGHT_END
    }
}
