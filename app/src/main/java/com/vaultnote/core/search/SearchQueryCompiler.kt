package com.vaultnote.core.search

sealed interface SearchQueryCompilation {
    data object Empty : SearchQueryCompilation
    data object TooLong : SearchQueryCompilation
    data class Valid(val query: CompiledSearchQuery) : SearchQueryCompilation
}

class CompiledSearchQuery internal constructor(
    val matchExpression: String,
    val displayTerms: List<String>,
)

/** Converts untrusted text into bounded quoted FTS prefix terms without accepting operators. */
object SearchQueryCompiler {
    fun compile(input: String): SearchQueryCompilation {
        if (input.isBlank()) return SearchQueryCompilation.Empty
        if (input.codePointCount(0, input.length) > MAX_QUERY_CODE_POINTS) {
            return SearchQueryCompilation.TooLong
        }
        val terms = TOKEN_PATTERN.findAll(input)
            .map(MatchResult::value)
            .map(::truncateCodePoints)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .take(MAX_TERMS)
            .toList()
        if (terms.isEmpty()) return SearchQueryCompilation.Empty
        // FTS4 on Android uses adjacent quoted terms as implicit AND. The explicit AND keyword
        // is not enabled consistently and can be interpreted as a literal token.
        val expression = terms.joinToString(separator = " ") { term ->
            "\"$term\"*"
        }
        return SearchQueryCompilation.Valid(CompiledSearchQuery(expression, terms))
    }

    private fun truncateCodePoints(value: String): String {
        val count = value.codePointCount(0, value.length)
        if (count <= MAX_TERM_CODE_POINTS) return value
        return value.substring(0, value.offsetByCodePoints(0, MAX_TERM_CODE_POINTS))
    }

    const val MAX_QUERY_CODE_POINTS = 200
    const val MAX_TERMS = 8
    const val MAX_TERM_CODE_POINTS = 64
    // unicode61 indexes letters and numbers as tokens but treats filename punctuation,
    // including underscores, as separators. Matching that behavior keeps full filenames usable.
    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
}
