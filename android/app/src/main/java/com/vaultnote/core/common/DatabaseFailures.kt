package com.vaultnote.core.common

import android.database.sqlite.SQLiteDatabaseLockedException

/** Returns true only for local database failures where an immediate retry may succeed. */
fun Throwable.isRetryableLocalDatabaseFailure(): Boolean {
    var current: Throwable? = this
    repeat(MAX_CAUSE_DEPTH) {
        if (current is SQLiteDatabaseLockedException) return true
        current = current?.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8
