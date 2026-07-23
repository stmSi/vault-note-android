package com.vaultnote.core.common

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseFailuresTest {
    @Test
    fun `only a database lock is classified as retryable`() {
        assertTrue(
            AppError.DatabaseFailure(
                operation = "read",
                diagnosticCause = SQLiteDatabaseLockedException(),
            ).isRetryable,
        )
        assertFalse(
            AppError.DatabaseFailure(
                operation = "read",
                diagnosticCause = SQLiteDatabaseCorruptException(),
            ).isRetryable,
        )
    }

    @Test
    fun `wrapped database lock remains retryable`() {
        val wrapped = IllegalStateException("wrapper", SQLiteDatabaseLockedException())
        assertTrue(wrapped.isRetryableLocalDatabaseFailure())
    }
}
