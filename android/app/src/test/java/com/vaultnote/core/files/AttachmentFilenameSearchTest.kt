package com.vaultnote.core.files

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentFilenameSearchTest {
    @Test
    fun `builds contiguous and ordered character patterns`() {
        val patterns = requireNotNull(AttachmentFilenameSearch.compile(" BKK "))

        assertEquals("%BKK%", patterns.contiguous)
        assertEquals("%B%K%K%", patterns.subsequence)
    }

    @Test
    fun `escapes sqlite wildcard characters as literal filename text`() {
        val patterns = requireNotNull(AttachmentFilenameSearch.compile("100%_done"))

        assertEquals("%100\\%\\_done%", patterns.contiguous)
        assertEquals("%1%0%0%\\%%\\_%d%o%n%e%", patterns.subsequence)
    }

    @Test
    fun `bounds query size by unicode code points`() {
        val value = "界".repeat(250)

        assertEquals(
            AttachmentFilenameSearch.MAX_QUERY_CODE_POINTS,
            AttachmentFilenameSearch.boundInput(value).codePointCount(0, 200),
        )
    }

    @Test
    fun `normalizes unicode filename queries`() {
        val patterns = requireNotNull(AttachmentFilenameSearch.compile("Cafe\u0301"))

        assertEquals("%Café%", patterns.contiguous)
    }
}
