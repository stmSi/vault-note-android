package com.vaultnote.core.files

import com.vaultnote.core.common.AppError
import com.vaultnote.core.common.RepositoryResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSanitizerTest {
    @Test
    fun `normalizes unicode removes bidi controls and replaces reserved characters`() {
        val result = FilenameSanitizer.sanitize("Cafe\u0301\u202E?.pdf")

        assertEquals("Café_.pdf", result.successValue())
    }

    @Test
    fun `rejects traversal separators and malformed unicode`() {
        val malformed = charArrayOf(0xD800.toChar()).concatToString() + ".txt"

        listOf("../private.pdf", "..\\private.pdf", malformed).forEach { unsafeName ->
            val result = FilenameSanitizer.sanitize(unsafeName)
            assertTrue(result is RepositoryResult.Failure)
            assertTrue((result as RepositoryResult.Failure).error is AppError.InvalidInput)
        }
    }

    @Test
    fun `limits code points while retaining extension and avoids reserved device names`() {
        val longResult = FilenameSanitizer.sanitize("界".repeat(240) + ".json").successValue()

        assertEquals(180, longResult.codePointCount(0, longResult.length))
        assertTrue(longResult.endsWith(".json"))
        assertEquals("_CON.txt", FilenameSanitizer.sanitize("CON.txt").successValue())
    }

    private fun RepositoryResult<String>.successValue(): String = when (this) {
        is RepositoryResult.Success -> value
        is RepositoryResult.Failure -> throw AssertionError("Expected success")
    }
}
