package com.vaultnote.core.search

import java.text.Normalizer
import java.util.Locale

/** Rebuilds deterministic typed-prefix markers without relying on FTS4 marker placement. */
internal object SearchHighlightNormalizer {
    fun markTypedPrefixes(source: String, terms: List<String>): String {
        if (source.isEmpty() || terms.isEmpty()) return source
        val candidates = terms.asSequence()
            .map(::fold)
            .filter(String::isNotEmpty)
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        if (candidates.isEmpty()) return source

        val cleanSource = source
            .replace(RoomSearchRepository.HIGHLIGHT_START, "")
            .replace(RoomSearchRepository.HIGHLIGHT_END, "")
        val output = StringBuilder(cleanSource.length)
        var cursor = 0
        TOKEN_PATTERN.findAll(cleanSource).forEach { match ->
            output.append(cleanSource, cursor, match.range.first)
            val matchedToken = match.value
            val prefixEnd = candidates.firstNotNullOfOrNull { foldedTerm ->
                matchingPrefixEnd(matchedToken, foldedTerm)
            }
            if (prefixEnd == null) {
                output.append(matchedToken)
            } else {
                output.append(RoomSearchRepository.HIGHLIGHT_START)
                output.append(matchedToken, 0, prefixEnd)
                output.append(RoomSearchRepository.HIGHLIGHT_END)
                output.append(matchedToken, prefixEnd, matchedToken.length)
            }
            cursor = match.range.last + 1
        }
        output.append(cleanSource, cursor, cleanSource.length)
        return output.toString()
    }

    private fun matchingPrefixEnd(source: String, foldedTerm: String): Int? {
        var sourceEnd = 0
        var inspectedCodePoints = 0
        while (sourceEnd < source.length && inspectedCodePoints < MAX_INSPECTED_CODE_POINTS) {
            sourceEnd = source.offsetByCodePoints(sourceEnd, 1)
            inspectedCodePoints += 1
            val foldedPrefix = fold(source.substring(0, sourceEnd))
            if (foldedPrefix.length < foldedTerm.length) continue
            return sourceEnd.takeIf { foldedPrefix.startsWith(foldedTerm) }
        }
        return null
    }

    private fun fold(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .filterNot { character ->
            when (Character.getType(character)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                -> true
                else -> false
            }
        }
        .lowercase(Locale.ROOT)

    private const val MAX_INSPECTED_CODE_POINTS = SearchQueryCompiler.MAX_TERM_CODE_POINTS * 2
    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
}
