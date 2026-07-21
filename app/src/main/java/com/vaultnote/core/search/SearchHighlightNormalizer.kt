package com.vaultnote.core.search

import java.text.Normalizer
import java.util.Locale

/** Restricts FTS4 whole-token markers to the prefix characters entered by the user. */
internal object SearchHighlightNormalizer {
    fun retainTypedPrefixes(source: String, terms: List<String>): String {
        if (source.isEmpty() || terms.isEmpty()) return source
        val candidates = terms.asSequence()
            .map(::fold)
            .filter(String::isNotEmpty)
            .distinct()
            .sortedByDescending(String::length)
            .toList()
        if (candidates.isEmpty()) return source

        val output = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            val markerStart = source.indexOf(RoomSearchRepository.HIGHLIGHT_START, cursor)
            if (markerStart < 0) {
                output.append(source, cursor, source.length)
                break
            }
            output.append(source, cursor, markerStart)
            val contentStart = markerStart + RoomSearchRepository.HIGHLIGHT_START.length
            val markerEnd = source.indexOf(RoomSearchRepository.HIGHLIGHT_END, contentStart)
            if (markerEnd < 0) {
                output.append(source, markerStart, source.length)
                break
            }

            val matchedToken = source.substring(contentStart, markerEnd)
            val prefixEnd = candidates.firstNotNullOfOrNull { foldedTerm ->
                matchingPrefixEnd(matchedToken, foldedTerm)
            }
            if (prefixEnd == null) {
                output.append(
                    source,
                    markerStart,
                    markerEnd + RoomSearchRepository.HIGHLIGHT_END.length,
                )
            } else {
                output.append(RoomSearchRepository.HIGHLIGHT_START)
                output.append(matchedToken, 0, prefixEnd)
                output.append(RoomSearchRepository.HIGHLIGHT_END)
                output.append(matchedToken, prefixEnd, matchedToken.length)
            }
            cursor = markerEnd + RoomSearchRepository.HIGHLIGHT_END.length
        }
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
}
