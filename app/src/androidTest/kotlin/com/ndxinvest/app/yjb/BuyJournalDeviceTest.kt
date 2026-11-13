package com.ndxinvest.app.yjb

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*
import java.io.File

/** Real Keystore + AtomicFile, isolated synthetic journal. NO YJB writes or user history edits. */
@RunWith(AndroidJUnit4::class)
class BuyJournalDeviceTest {
    @Test fun actualAndroidAtomicReplacementRestoresLatestState()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(target.noBackupFilesDir,"phase2-isolated-storage-test").apply{mkdirs()}
        val context=object:ContextWrapper(target){override fun getNoBackupFilesDir()=directory}
        val key=AndroidKeystoreCredentialKeyProvider("ndx_phase2_isolated_test_key")
        fun storage()=AndroidBuyStorage(context,key)
        val date=LocalDate.parse("2026-09-28");val now=Instant.parse("2026-10-03T00:00:00Z")
        val waiting=BuyTransaction("synthetic-only",BuyInput(BuyFund("1","17","270042","synthetic-only"),BigDecimal("1000.123456789"),BigDecimal("0.0015"),date,CutoffType.BEFORE_1500),BuyBaseline(BigDecimal("100"),BigDecimal("1.2")),date,createdAt=now,updatedAt=now,calendarSource="synthetic-only")
        val file=File(directory,"ndx-buy-transactions-v1.enc")
        try {
            storage().write(listOf(waiting));assertEquals(waiting,storage().read().single())
            val ready=BuyCalculation.confirm(waiting,date,BigDecimal("2.123456789"),now)
            storage().write(listOf(ready));assertEquals(ready,storage().read().single())
            val marker=ready.copy(status=BuyStatus.SYNCING,postMayHaveBeenSent=true,submittedTarget=ready.target)
            storage().write(listOf(marker));assertEquals(marker,storage().read().single())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-only"))
        }finally {
            android.util.AtomicFile(file).delete();directory.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("ndx_phase2_isolated_test_key")}
        }
    }
}
