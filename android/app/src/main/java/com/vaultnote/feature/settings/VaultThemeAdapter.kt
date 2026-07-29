package com.vaultnote.feature.settings

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.vaultnote.R
import com.vaultnote.core.theme.VaultTheme

internal class VaultThemeAdapter(
    context: Context,
    private val themes: List<VaultTheme>,
) : ArrayAdapter<VaultTheme>(context, R.layout.item_theme_option, themes) {
    private val inflater = LayoutInflater.from(context)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        bind(position, convertView, parent)

    private fun bind(position: Int, recycled: View?, parent: ViewGroup): View {
        val view = recycled ?: inflater.inflate(R.layout.item_theme_option, parent, false)
        val theme = getItem(position) ?: return view
        view.findViewById<TextView>(R.id.theme_name).setText(theme.labelResource)
        theme.applyBackground(view.findViewById(R.id.theme_swatch), cornerRadiusDp = 9f)
        return view
    }
}
