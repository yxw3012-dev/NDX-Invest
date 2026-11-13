package com.ndxinvest.app.yjb

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Real device Keystore, isolated synthetic plans/BUY. No YJB API or user journal writes. */
@RunWith(AndroidJUnit4::class)
class DcaJournalDeviceTest {
    @Test fun encryptedPlansAndInterruptedPeriodRestoreWithoutDuplicateBuy()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val userBuy=AndroidBuyStorage(target).read()
        val userSell=AndroidSellStorage(target).read()
        val userPlans=AndroidDcaStorage(target).read()
        val directory=File(target.noBackupFilesDir,"phase4-isolated-storage-test").apply {mkdirs()}
        val context=object:ContextWrapper(target){override fun getNoBackupFilesDir()=directory}
        val alias="ndx_phase4_isolated_test_key"
        val key=AndroidKeystoreCredentialKeyProvider(alias)
        var time=Instant.parse("2026-10-03T01:00:00Z")
        var gets=0
        val gateway=object:BuyGateway {
            override suspend fun current(fund:BuyFund):BuyBaseline {gets++;return BuyBaseline(BigDecimal("100"),BigDecimal("2"))}
            override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal?=error("No NAV in storage test")
            override fun writeAvailable()=false
            override fun normalizeTarget(target:BuyBaseline):BuyBaseline=error("No writer")
            override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
            override suspend fun postFinal(fund:BuyFund,target:BuyBaseline):Unit=error("No POST permitted")
        }
        val buys=AndroidBuyStorage(context,key)
        val plans=AndroidDcaStorage(context,key)
        val buyRepository=SingleBuyRepository(buys,gateway,{null},now={time})
        var interrupt=false
        val interruptedStorage=object:DcaStorage {
            override suspend fun read()=plans.read()
            override suspend fun write(state:DcaState){
                if(interrupt&&state.occurrences.any {it.status==OccurrenceStatus.CREATED})throw DcaFailure(DcaProblem.STORAGE)
                plans.write(state)
            }
        }
        fun repository()=DcaRepository(interruptedStorage,buys,buyRepository,now={time})
        try {
            val fund=BuyFund("isolated-account","17","000017","synthetic-private-plan")
            val config=DcaConfig(BigDecimal("100.123456789"),BigDecimal("0.0015"),DcaFrequency.DAILY,1,1)
            val p=repository().create(fund,config,true,"885e825b-6aa0-4a30-9786-c65a2c41141e")
            assertEquals(p,repository().restore().plans.single())
            assertEquals(0,gets);assertTrue(buys.read().isEmpty())
            time=Instant.parse("2026-10-04T01:00:00Z");interrupt=true
            try {repository().execute(p.id,p.nextDate,p.revision);fail("Expected interrupted plan completion")}
            catch(e:DcaFailure){assertEquals(DcaProblem.STORAGE,e.problem)}
            assertEquals(1,buys.read().size)
            assertEquals(OccurrenceStatus.RESERVED,plans.read().occurrences.single().status)
            interrupt=false
            val recovered=repository().restore()
            assertEquals(OccurrenceStatus.CREATED,recovered.occurrences.single().status)
            val transaction=repository().execute(p.id,p.nextDate,p.revision)
            assertEquals(BuyStatus.WAITING_NAV,transaction.status)
            assertEquals(p.id,transaction.originPlanId)
            assertEquals(p.nextDate,transaction.scheduledDate)
            assertEquals(1,gets);assertEquals(1,buys.read().size)
            val paused=repository().setEnabled(p.id,false,recovered.plans.single().revision)
            assertFalse(repository().restore().plans.single().enabled)
            val resumed=repository().setEnabled(p.id,true,paused.revision)
            repository().delete(p.id,resumed.revision)
            assertTrue(repository().restore().plans.single().deleted)
            assertEquals(1,buys.read().size)
            assertFalse(File(directory,"ndx-dca-plans-v1.enc").readBytes().toString(Charsets.UTF_8).contains("synthetic-private-plan"))
            assertEquals(userBuy,AndroidBuyStorage(target).read())
            assertEquals(userSell,AndroidSellStorage(target).read())
            assertEquals(userPlans,AndroidDcaStorage(target).read())
        }finally {
            for(name in listOf("ndx-dca-plans-v1.enc","ndx-buy-transactions-v1.enc"))android.util.AtomicFile(File(directory,name)).delete()
            directory.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
        }
    }
}
