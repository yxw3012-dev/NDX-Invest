package com.ndxinvest.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.ndxinvest.app.frontendv2.RetiredAutomation
import com.ndxinvest.app.yjb.NativeDcaAutomation

class NdxInvestApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(RetiredAutomation.Factory()).build()

    override fun onCreate() {
        super.onCreate()
        com.ndxinvest.app.notifications.NdxReminders.createChannels(this)
        RetiredAutomation.cancelScheduledWork(WorkManager.getInstance(this))
        NativeDcaAutomation.schedule(this)
    }
}
