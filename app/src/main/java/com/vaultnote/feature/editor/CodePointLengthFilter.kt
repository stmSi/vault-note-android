package com.vaultnote.feature.editor

import android.text.InputFilter
import android.text.Spanned

/** Limits editable text by Unicode code points without splitting surrogate pairs. */
internal class CodePointLengthFilter(
    private val maximumCodePoints: Int,
) : InputFilter {
    init {
        require(maximumCodePoints >= 0) { "Maximum code points must not be negative" }
    }

    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        destination: Spanned,
        destinationStart: Int,
        destinationEnd: Int,
    ): CharSequence? {
        val retainedUtf16Units = destination.length - (destinationEnd - destinationStart)
        val incomingUtf16Units = end - start
        if (retainedUtf16Units + incomingUtf16Units <= maximumCodePoints) return null

        val retainedDestinationCodePoints =
            Character.codePointCount(destination, 0, destination.length) -
                Character.codePointCount(destination, destinationStart, destinationEnd)
        val available = maximumCodePoints - retainedDestinationCodePoints
        if (available <= 0) return ""

        val sourceCodePoints = Character.codePointCount(source, start, end)
        if (sourceCodePoints <= available) return null

        var index = start
        repeat(available) {
            val first = source[index]
            index += if (
                Character.isHighSurrogate(first) &&
                index + 1 < end &&
                Character.isLowSurrogate(source[index + 1])
            ) {
                2
            } else {
                1
            }
        }
        return source.subSequence(start, index)
    }
}
