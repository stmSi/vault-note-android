package com.vaultnote.feature.editor

import android.text.SpannedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodePointLengthFilterTest {
    @Test
    fun `filter truncates at a code point boundary`() {
        val source = SpannedString("😀😀😀")
        val result = CodePointLengthFilter(2).filter(
            source = source,
            start = 0,
            end = source.length,
            destination = SpannedString(""),
            destinationStart = 0,
            destinationEnd = 0,
        )

        assertEquals("😀😀", result.toString())
    }

    @Test
    fun `filter accepts replacement that remains within the limit`() {
        val source = SpannedString("😀")
        val destination = SpannedString("abcd")
        val result = CodePointLengthFilter(4).filter(
            source = source,
            start = 0,
            end = source.length,
            destination = destination,
            destinationStart = 1,
            destinationEnd = 3,
        )

        assertNull(result)
    }

    @Test
    fun `large ASCII input uses the within-limit path`() {
        val destination = SpannedString("a".repeat(99_999))
        val source = SpannedString("b")

        val result = CodePointLengthFilter(100_000).filter(
            source = source,
            start = 0,
            end = source.length,
            destination = destination,
            destinationStart = destination.length,
            destinationEnd = destination.length,
        )

        assertNull(result)
    }
}
