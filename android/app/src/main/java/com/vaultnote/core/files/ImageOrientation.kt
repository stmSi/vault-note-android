package com.vaultnote.core.files

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream

internal fun readExifOrientation(file: File): Int = try {
    ExifInterface(file.path).vaultOrientation()
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
} catch (_: RuntimeException) {
    ExifInterface.ORIENTATION_NORMAL
}

internal fun readExifOrientation(input: InputStream): Int = try {
    ExifInterface(input).vaultOrientation()
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
} catch (_: RuntimeException) {
    ExifInterface.ORIENTATION_NORMAL
}

internal fun orientedDimensions(
    width: Int,
    height: Int,
    orientation: Int,
): Pair<Int, Int> = if (
    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
    orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
    orientation == ExifInterface.ORIENTATION_TRANSVERSE ||
    orientation == ExifInterface.ORIENTATION_ROTATE_270
) {
    height to width
} else {
    width to height
}

internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
}

private fun ExifInterface.vaultOrientation(): Int = getAttributeInt(
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.ORIENTATION_NORMAL,
)
