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

    fun markTypedSubsequences(source: String, terms: List<String>): String {
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
            val token = match.value
            val ranges = candidates.firstNotNullOfOrNull { term ->
                matchingSubsequenceRanges(token, term)
            }
            if (ranges == null) {
                output.append(token)
            } else {
                appendMarkedRanges(output, token, ranges)
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

    private fun matchingSubsequenceRanges(source: String, foldedTerm: String): List<IntRange>? {
        val target = foldedTerm.codePoints().toArray()
        if (target.isEmpty()) return null
        val matched = ArrayList<IntRange>(target.size)
        var targetIndex = 0
        var sourceStart = 0
        var inspectedCodePoints = 0
        while (
            sourceStart < source.length &&
            targetIndex < target.size &&
            inspectedCodePoints < MAX_INSPECTED_CODE_POINTS
        ) {
            val sourceEnd = source.offsetByCodePoints(sourceStart, 1)
            val foldedSourceCodePoints = fold(source.substring(sourceStart, sourceEnd))
                .codePoints()
                .toArray()
            for (codePoint in foldedSourceCodePoints) {
                if (targetIndex < target.size && codePoint == target[targetIndex]) {
                    matched += sourceStart until sourceEnd
                    targetIndex += 1
                    break
                }
            }
            sourceStart = sourceEnd
            inspectedCodePoints += 1
        }
        return matched.takeIf { targetIndex == target.size }
    }

    private fun appendMarkedRanges(
        output: StringBuilder,
        source: String,
        ranges: List<IntRange>,
    ) {
        var cursor = 0
        mergeAdjacent(ranges).forEach { range ->
            output.append(source, cursor, range.first)
            output.append(RoomSearchRepository.HIGHLIGHT_START)
            output.append(source, range.first, range.last + 1)
            output.append(RoomSearchRepository.HIGHLIGHT_END)
            cursor = range.last + 1
        }
        output.append(source, cursor, source.length)
    }

    private fun mergeAdjacent(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val merged = ArrayList<IntRange>(ranges.size)
        var current = ranges.first()
        for (next in ranges.drop(1)) {
            if (current.last + 1 == next.first) {
                current = current.first..next.last
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
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
