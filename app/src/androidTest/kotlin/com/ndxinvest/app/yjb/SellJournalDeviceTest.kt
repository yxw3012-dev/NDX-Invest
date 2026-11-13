package com.ndxinvest.app.yjb

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Real Keystore test in an isolated directory. No API, transaction creation or user-journal writes. */
@RunWith(AndroidJUnit4::class)
class SellJournalDeviceTest {
    @Test fun encryptedSellStatesRestoreWithoutChangingUserHistory()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val userBuy=AndroidBuyStorage(target).read()
        val userSell=AndroidSellStorage(target).read()
        val directory=File(target.noBackupFilesDir,"phase3-isolated-storage-test").apply {mkdirs()}
        val context=object:ContextWrapper(target){override fun getNoBackupFilesDir()=directory}
        val alias="ndx_phase3_isolated_test_key"
        val key=AndroidKeystoreCredentialKeyProvider(alias)
        fun storage()=AndroidSellStorage(context,key)
        val date=LocalDate.parse("2026-09-28");val now=Instant.parse("2026-10-03T00:00:00Z")
        val baseline=BuyBaseline(BigDecimal("10.12"),BigDecimal("4.49412345"))
        val waiting=SellTransaction("synthetic-only",SellInput(BuyFund("1","17","160213","synthetic-only"),baseline.shares,BigDecimal("0.0015"),date,CutoffType.BEFORE_1500),baseline,date,createdAt=now,updatedAt=now,calendarSource="synthetic-only")
        val file=File(directory,"ndx-sell-transactions-v1.enc")
        try {
            storage().write(listOf(waiting));assertEquals(waiting,storage().read().single())
            val ready=SellCalculation.confirm(waiting,OfficialBuyNav(date,BigDecimal("4.5700")),now)
            assertEquals(0,ready.target!!.shares.signum())
            assertEquals(baseline.unitCost,ready.target!!.unitCost)
            storage().write(listOf(ready));assertEquals(ready,storage().read().single())
            val marker=ready.copy(status=SellStatus.SYNCING,postMayHaveBeenSent=true,submittedTarget=ready.target)
            storage().write(listOf(marker));assertEquals(marker,storage().read().single())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-only"))
            assertEquals(userBuy,AndroidBuyStorage(target).read())
            assertEquals(userSell,AndroidSellStorage(target).read())
        }finally {
            android.util.AtomicFile(file).delete();directory.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        }
    }
}
