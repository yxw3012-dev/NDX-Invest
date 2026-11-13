package com.ndxinvest.app.yjb

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.*

/** Device Keystore / file restore only. Entire gateway is synthetic: no real YJB request. */
@RunWith(AndroidJUnit4::class)
class DcaAutomaticJournalDeviceTest {
    @Test fun officialNavReservationsSurviveNativeStorageRestartAndNeverDuplicate()=runBlocking {
        NativeTransactionGate.mutex.withLock {
            val target=InstrumentationRegistry.getInstrumentation().targetContext
            val userBuys=AndroidBuyStorage(target).read();val userSells=AndroidSellStorage(target).read();val userPlans=AndroidDcaStorage(target).read()
            val directory=File(target.noBackupFilesDir,"phase5-isolated-storage-test").apply {mkdirs()}
            val context=object:ContextWrapper(target){override fun getNoBackupFilesDir()=directory}
            val alias="ndx_phase5_isolated_test_key";val key=AndroidKeystoreCredentialKeyProvider(alias)
            var time=Instant.parse("2026-10-03T01:00:00Z");var posts=0
            var position=BuyBaseline(BigDecimal("100"),BigDecimal("2"))
            val navs=listOf("2026-09-30","2026-10-08","2026-10-09").map {OfficialBuyNav(LocalDate.parse(it),BigDecimal("2.123456789"))}
            val gateway=object:BuyGateway {
                override suspend fun current(fund:BuyFund)=position
                override suspend fun officialHistory(fund:BuyFund)=navs
                override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal?=error("No other NAV path")
                override fun writeAvailable()=true
                override fun normalizeTarget(target:BuyBaseline)=target
                override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
                override suspend fun postFinal(fund:BuyFund,target:BuyBaseline){posts++;position=target} // in memory only
            }
            try {
                val buyStore=AndroidBuyStorage(context,key);val planStore=AndroidDcaStorage(context,key)
                val buys=SingleBuyRepository(buyStore,gateway,{error("No calendar")},now={time})
                val plans=DcaRepository(planStore,buyStore,buys,now={time})
                plans.create(BuyFund("isolated-account","17","040046","synthetic"),DcaConfig(BigDecimal("5"),BigDecimal.ZERO,DcaFrequency.DAILY,1,1),true,"885e825b-6aa0-4a30-9786-c65a2c41141e")
                time=Instant.parse("2026-10-12T01:00:00Z")
                assertEquals(2,DcaNavEngine(planStore,buyStore,plans,buys,gateway).run(setOf("isolated-account")).synced)
                val restoredBuys=AndroidBuyStorage(context,key);val restoredPlans=AndroidDcaStorage(context,key)
                val secondBuys=SingleBuyRepository(restoredBuys,gateway,{error("No calendar")},now={time})
                secondBuys.restore()
                val secondPlans=DcaRepository(restoredPlans,restoredBuys,secondBuys,now={time})
                assertEquals(0,DcaNavEngine(restoredPlans,restoredBuys,secondPlans,secondBuys,gateway).run(setOf("isolated-account")).synced)
                assertEquals(2,posts);assertEquals(2,restoredBuys.read().size)
                assertTrue(restoredBuys.read().all {it.status==BuyStatus.SYNCED&&it.createdAt==time})
                assertEquals(setOf("2026-10-08","2026-10-09"),restoredPlans.read().occurrences.map {it.officialNav!!.date.toString()}.toSet())
                assertEquals(userBuys,AndroidBuyStorage(target).read());assertEquals(userSells,AndroidSellStorage(target).read());assertEquals(userPlans,AndroidDcaStorage(target).read())
            }finally {
                for(name in listOf("ndx-dca-plans-v1.enc","ndx-buy-transactions-v1.enc"))android.util.AtomicFile(File(directory,name)).delete()
                directory.delete();java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
            }
        }
    }
}
