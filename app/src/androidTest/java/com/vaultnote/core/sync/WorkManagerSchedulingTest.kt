package com.vaultnote.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkManagerSchedulingTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build(),
        )
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10L, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(10L, TimeUnit.SECONDS)
    }

    @Test
    fun immediateRequestsUseOneUniqueConstrainedWorkSlot() {
        val scheduler = WorkManagerSyncScheduler(context)

        assertTrue(scheduler.requestSync() !is SyncScheduleResult.Rejected)
        assertTrue(scheduler.requestSync() !is SyncScheduleResult.Rejected)

        val work = workManager.getWorkInfosForUniqueWork(
            WorkManagerSyncScheduler.IMMEDIATE_WORK_NAME,
        ).get(10L, TimeUnit.SECONDS)
        assertEquals(1, work.size)
    }
}
