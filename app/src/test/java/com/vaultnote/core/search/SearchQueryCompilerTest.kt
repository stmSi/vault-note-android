package com.vaultnote.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryCompilerTest {
    @Test
    fun `a single letter creates a prefix query for incremental search`() {
        val compiled = SearchQueryCompiler.compile("p") as SearchQueryCompilation.Valid

        assertEquals("\"p\"*", compiled.query.matchExpression)
    }

    @Test
    fun `untrusted operators are converted to quoted prefix terms`() {
        val compiled = SearchQueryCompiler.compile("paper OR secret* -tag")

        assertTrue(compiled is SearchQueryCompilation.Valid)
        val valid = compiled as SearchQueryCompilation.Valid
        assertEquals("\"paper\"* \"OR\"* \"secret\"* \"tag\"*", valid.query.matchExpression)
        assertEquals(listOf("paper", "OR", "secret", "tag"), valid.query.displayTerms)
    }

    @Test
    fun `oversized input is rejected before querying SQLite`() {
        assertTrue(
            SearchQueryCompiler.compile("a".repeat(SearchQueryCompiler.MAX_QUERY_CODE_POINTS + 1))
                is SearchQueryCompilation.TooLong,
        )
    }

    @Test
    fun `punctuation-only input does not create an FTS expression`() {
        assertTrue(SearchQueryCompiler.compile("\"*()-") is SearchQueryCompilation.Empty)
    }

    @Test
    fun `filename separators compile to the same tokens as unicode61`() {
        val compiled = SearchQueryCompiler.compile("Samsung_scan_2026.pdf")
            as SearchQueryCompilation.Valid

        assertEquals(
            "\"Samsung\"* \"scan\"* \"2026\"* \"pdf\"*",
            compiled.query.matchExpression,
        )
    }
}
