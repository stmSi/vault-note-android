package com.vaultnote.app

interface MainNavigator {
    fun openNoteEditor(itemId: String)

    fun navigateBack()
}
