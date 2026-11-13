package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import java.math.BigDecimal
import java.time.*
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[PosixAtomicFileShadow::class])
class AndroidDcaStorageTest {
    @Test fun encryptedPlansAndReservationsRestoreWithoutTouchingBuyOrSellFiles()=runTest {
        val c=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val provider=CredentialKeyProvider{key}
        val now=Instant.parse("2026-10-03T00:00:00Z");val date=LocalDate.parse("2026-10-04")
        val fund=BuyFund("1","17","000017","private-plan-fund");val config=DcaConfig(BigDecimal("100.123456789"),BigDecimal("0.0015"),DcaFrequency.DAILY,1,1)
        val p=DcaPlan("private-plan",fund,config,date,date,true,createdAt=now,updatedAt=now,history=listOf(DcaVersion(now,config,true,date,1)))
        val o=DcaOccurrence(p.id,date,"private-buy",BuyInput(fund,config.amount,config.feeRate,date,CutoffType.BEFORE_1500),requestedAt=now,officialNav=OfficialBuyNav(date,BigDecimal("2.123456789")))
        val buy=java.io.File(c.noBackupFilesDir,"ndx-buy-transactions-v1.enc").apply {writeText("BUY-UNCHANGED")}
        val sell=java.io.File(c.noBackupFilesDir,"ndx-sell-transactions-v1.enc").apply {writeText("SELL-UNCHANGED")}
        val review=DcaCatchupReview("885e825b-6aa0-4a30-9786-c65a2c41141e",fund,
            listOf(DcaCatchupSlot(p.id,date.plusDays(1),config.amount,config.feeRate,BigDecimal("2.123456789"),1)),BuyBaseline(BigDecimal("100"),BigDecimal("2")),now)
        val state=DcaState(listOf(p),listOf(o),listOf(review));AndroidDcaStorage(c,provider).write(state)
        assertEquals(state,AndroidDcaStorage(c,provider).read())
        val bytes=java.io.File(c.noBackupFilesDir,"ndx-dca-plans-v1.enc").readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("private-plan"));assertFalse(bytes.contains("100.123456789"));assertEquals("BUY-UNCHANGED",buy.readText());assertEquals("SELL-UNCHANGED",sell.readText())
        for(decision in listOf(CatchupDecision.APPROVED,CatchupDecision.SKIPPED,CatchupDecision.SUPERSEDED)) {
            val decided=state.copy(catchupReviews=listOf(review.copy(decision=decision,decidedAt=now.plusSeconds(1))))
            AndroidDcaStorage(c,provider).write(decided);assertEquals(decided,AndroidDcaStorage(c,provider).read())
        }
    }
    @Test fun damagedJournalFailsClosedWithoutResettingPlans()=runTest {
        val c=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val storage=AndroidDcaStorage(c,CredentialKeyProvider{key})
        storage.write(DcaState());val f=java.io.File(c.noBackupFilesDir,"ndx-dca-plans-v1.enc");val b=f.readBytes()
        b[b.lastIndex]=(b.last().toInt() xor 1).toByte();f.writeBytes(b)
        assertEquals(DcaProblem.STORAGE,assertFailsWith<DcaFailure>{storage.read()}.problem);assertContentEquals(b,f.readBytes())
    }
}
