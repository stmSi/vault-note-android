package com.vaultnote.app

import android.content.Context

fun Context.appContainer(): AppContainer {
    val application = applicationContext as? VaultNoteApplication
        ?: error("VaultNoteApplication is not configured")
    return application.container
}
