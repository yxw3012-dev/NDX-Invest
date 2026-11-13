package com.ndxinvest.app.frontendv2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetiredAutomationTest {
    @After fun closeTestWorkDatabase() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test fun upgradeCancelsOnlyTheThreeRetiredUniqueJobs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setWorkerFactory(RetiredAutomation.Factory()).build())
        val manager = WorkManager.getInstance(context)
        val names = RetiredAutomation.uniqueNames + "unrelated-future-task"
        for (name in names) {
            val request = PeriodicWorkRequest.Builder(FutureTestWorker::class.java, 1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS).build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request).result.get()
        }
        RetiredAutomation.cancelScheduledWork(manager)
        RetiredAutomation.uniqueNames.forEach { name ->
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(name).get().single().state)
        }
        assertEquals(WorkInfo.State.ENQUEUED,
            manager.getWorkInfosForUniqueWork("unrelated-future-task").get().single().state)
        assertFalse(context.getDatabasePath("ndx-invest.db").exists())
    }

    @Test fun persistedLegacyClassNamesAreHandledWithoutExecutingOldWorkerCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parameters = TestListenableWorkerBuilder<FutureTestWorker>(context).build().parameters
        val factory = RetiredAutomation.Factory()
        RetiredAutomation.workerClassNames.forEach { name ->
            val worker = factory.createWorker(context, name, parameters) as Worker
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }
        assertNull(factory.createWorker(context, FutureTestWorker::class.java.name, parameters))
        assertFalse(context.getDatabasePath("ndx-invest.db").exists())
    }

    class FutureTestWorker(context: Context, val parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }
}
