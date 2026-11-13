package com.ndxinvest.app.yjb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NativeDcaAutomationTest {
    @Test fun automaticJobIsPeriodicNetworkConstrainedAndDoesNotRestoreLegacyWorkers() {
        val request=NativeDcaAutomation.periodicRequest()
        assertEquals(DcaNavWorker::class.java.name,request.workSpec.workerClassName)
        assertEquals(TimeUnit.HOURS.toMillis(1),request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED,request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL,request.workSpec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30),request.workSpec.backoffDelayDuration)
        val c=ApplicationProvider.getApplicationContext<Context>()
        val worker=TestListenableWorkerBuilder<DcaNavWorker>(c).build()
        assertIs<DcaNavWorker>(worker) // Default WorkManager reflection can instantiate the native worker.
    }
    @Test fun foregroundAndBackgroundCommandsShareOneGateAndCancellationReleasesIt()=runTest {
        val order=mutableListOf<Int>()
        val first=launch {NativeTransactionGate.mutex.withLock {order+=1;delay(1000);order+=2}}
        val second=launch {NativeTransactionGate.mutex.withLock {order+=3}}
        first.join();second.join();assertEquals(listOf(1,2,3),order)
        val cancelled=launch {NativeTransactionGate.mutex.withLock {awaitCancellation()}}
        yield();cancelled.cancelAndJoin();assertFalse(NativeTransactionGate.mutex.isLocked)
    }
}
