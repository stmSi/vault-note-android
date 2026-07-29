package com.vaultnote.core.theme

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.annotation.StringRes
import androidx.annotation.StyleRes

data class VaultTheme(
    val storedId: String,
    @param:StyleRes val styleResource: Int,
    @param:StringRes val labelResource: Int,
    private val gradientColors: IntArray,
) {
    fun createBackground(cornerRadiusPixels: Float = 0f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            gradientColors.clone(),
        ).apply {
            cornerRadius = cornerRadiusPixels
        }

    fun applyBackground(view: View, cornerRadiusDp: Float = 0f) {
        val radius = cornerRadiusDp * view.resources.displayMetrics.density
        view.background = createBackground(radius)
    }
}
