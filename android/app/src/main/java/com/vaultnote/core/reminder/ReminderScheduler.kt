package com.vaultnote.core.reminder

interface ReminderScheduler {
    suspend fun reconcileEntry(entryId: String)
    suspend fun cancelEntry(entryId: String)
    suspend fun reconcileAll()
}

object NoOpReminderScheduler : ReminderScheduler {
    override suspend fun reconcileEntry(entryId: String) = Unit
    override suspend fun cancelEntry(entryId: String) = Unit
    override suspend fun reconcileAll() = Unit
}
