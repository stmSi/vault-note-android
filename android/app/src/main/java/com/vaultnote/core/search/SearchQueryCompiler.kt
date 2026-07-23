package com.vaultnote.core.search

sealed interface SearchQueryCompilation {
    data object Empty : SearchQueryCompilation
    data object TooLong : SearchQueryCompilation
    data class Valid(val query: CompiledSearchQuery) : SearchQueryCompilation
}

class CompiledSearchQuery internal constructor(
    val matchExpression: String,
    val displayTerms: List<String>,
    val subsequencePattern: String?,
    val subsequenceAnchor: String?,
)

/** Converts untrusted text into bounded FTS prefix terms without accepting operators. */
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
        // TOKEN_PATTERN reduces every term to letters and numbers before the suffix is added.
        // Quoting a term here would disable FTS4 prefix matching ("paper"* is an exact token),
        // so safe terms intentionally use the unquoted paper* form. Adjacent terms are ANDed.
        val expression = terms.joinToString(separator = " ") { term ->
            "$term*"
        }
        val subsequenceTerm = terms.singleOrNull()?.takeIf { term ->
            term.codePointCount(0, term.length) >= MIN_SUBSEQUENCE_CODE_POINTS
        }
        return SearchQueryCompilation.Valid(
            CompiledSearchQuery(
                matchExpression = expression,
                displayTerms = terms,
                subsequencePattern = subsequenceTerm?.toSubsequencePattern(),
                subsequenceAnchor = subsequenceTerm?.firstCodePoint(),
            ),
        )
    }

    private fun truncateCodePoints(value: String): String {
        val count = value.codePointCount(0, value.length)
        if (count <= MAX_TERM_CODE_POINTS) return value
        return value.substring(0, value.offsetByCodePoints(0, MAX_TERM_CODE_POINTS))
    }

    private fun String.toSubsequencePattern(): String = buildString(length * 2 + 1) {
        append('%')
        var offset = 0
        while (offset < this@toSubsequencePattern.length) {
            val codePoint = this@toSubsequencePattern.codePointAt(offset)
            appendCodePoint(codePoint)
            append('%')
            offset += Character.charCount(codePoint)
        }
    }

    private fun String.firstCodePoint(): String {
        val end = offsetByCodePoints(0, 1)
        return substring(0, end)
    }

    const val MAX_QUERY_CODE_POINTS = 200
    const val MAX_TERMS = 8
    const val MAX_TERM_CODE_POINTS = 64
    private const val MIN_SUBSEQUENCE_CODE_POINTS = 2
    // unicode61 indexes letters and numbers as tokens but treats filename punctuation,
    // including underscores, as separators. Matching that behavior keeps full filenames usable.
    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
}
