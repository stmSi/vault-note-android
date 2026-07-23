package com.vaultnote.feature.backup

import android.text.InputFilter
import android.text.Spanned
import com.google.android.material.textfield.TextInputEditText

internal fun TextInputEditText.configureBackupPasswordInput() {
    filters = filters + BackupPasswordCodePointFilter(MAX_BACKUP_PASSWORD_CODE_POINTS)
}

internal fun TextInputEditText.consumePasswordChars(): CharArray {
    val editable = text ?: return CharArray(0)
    return CharArray(editable.length) { index -> editable[index] }.also { editable.clear() }
}

private class BackupPasswordCodePointFilter(
    private val maximumCodePoints: Int,
) : InputFilter {
    override fun filter(
        source: CharSequence,
        start: Int,
        end: Int,
        destination: Spanned,
        destinationStart: Int,
        destinationEnd: Int,
    ): CharSequence? {
        val retained = Character.codePointCount(destination, 0, destination.length) -
            Character.codePointCount(destination, destinationStart, destinationEnd)
        val available = maximumCodePoints - retained
        if (available <= 0) return ""
        if (Character.codePointCount(source, start, end) <= available) return null

        var index = start
        repeat(available) {
            val character = source[index]
            index += if (
                Character.isHighSurrogate(character) &&
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

private const val MAX_BACKUP_PASSWORD_CODE_POINTS = 128
