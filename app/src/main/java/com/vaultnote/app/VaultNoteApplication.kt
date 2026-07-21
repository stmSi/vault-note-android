package com.vaultnote.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.vaultnote.BuildConfig

class VaultNoteApplication : Application(), Configuration.Provider {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DefaultAppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.INFO else Log.ERROR)
            .build()
}
