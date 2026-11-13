package com.ndxinvest.app.frontendv2

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Upgrade compatibility only. It neither reads nor writes the user's business database. */
internal object RetiredAutomation {
    val uniqueNames = listOf(
        "ndx-market-refresh-v1", "fund-nav-refresh-v1", "recurring-plan-generation-v1",
    )
    val workerClassNames = setOf(
        "com.ndxinvest.automation.android.NdxMarketCoroutineWorker",
        "com.ndxinvest.automation.android.FundNavCoroutineWorker",
        "com.ndxinvest.automation.android.RecurringPlanCoroutineWorker",
    )

    fun cancelScheduledWork(manager: WorkManager) {
        uniqueNames.forEach { manager.cancelUniqueWork(it) }
    }

    class Factory : WorkerFactory() {
        override fun createWorker(context: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? =
            if (workerClassName in workerClassNames) RetiredWorker(context, parameters) else null
    }

    private class RetiredWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }
}
