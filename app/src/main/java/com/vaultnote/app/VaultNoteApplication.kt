package com.vaultnote.app

import android.app.Application

class VaultNoteApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAppContainer(this)
    }
}
