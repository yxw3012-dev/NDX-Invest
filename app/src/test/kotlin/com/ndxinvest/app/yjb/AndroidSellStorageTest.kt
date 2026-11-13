package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.*
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[PosixAtomicFileShadow::class])
class AndroidSellStorageTest {
    @Test fun sellJournalIsEncryptedIndependentAndRestoresPendingReadyAndDeliveryState()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val provider=CredentialKeyProvider{key}
        fun storage()=AndroidSellStorage(context,provider)
        val buyFile=java.io.File(context.noBackupFilesDir,"ndx-buy-transactions-v1.enc");buyFile.writeText("BUY-SENTINEL-UNCHANGED")
        val time=Instant.parse("2026-10-03T00:00:00Z");val date=LocalDate.parse("2026-09-28")
        val pending=SellTransaction("private-sell",SellInput(BuyFund("1","17","000017","private-fund"),BigDecimal("25.123456789"),BigDecimal("0.015"),date,CutoffType.BEFORE_1500),BuyBaseline(BigDecimal("100"),BigDecimal("1.123456789")),date,createdAt=time,updatedAt=time,calendarSource=BuyNavDates.PROVISIONAL_SOURCE)
        storage().write(listOf(pending));assertEquals(pending,storage().read().single())
        val sellFile=java.io.File(context.noBackupFilesDir,"ndx-sell-transactions-v1.enc");val bytes=sellFile.readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("private-fund"));assertFalse(bytes.contains("25.123456789"))
        val ready=SellCalculation.confirm(pending,OfficialBuyNav(date,BigDecimal("2.1234")),time)
        storage().write(listOf(ready));assertEquals(ready,storage().read().single())
        val sent=ready.copy(status=SellStatus.SYNCING,submittedTarget=ready.target,postMayHaveBeenSent=true)
        storage().write(listOf(sent));assertEquals(sent,storage().read().single());assertEquals("BUY-SENTINEL-UNCHANGED",buyFile.readText())
    }
    @Test fun corruptSellJournalFailsClosedAndDoesNotResetIt()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val s=AndroidSellStorage(context,CredentialKeyProvider{key})
        s.write(emptyList());val f=java.io.File(context.noBackupFilesDir,"ndx-sell-transactions-v1.enc")
        val bytes=f.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();f.writeBytes(bytes)
        assertEquals(BuyProblem.STORAGE,assertFailsWith<BuyFailure>{s.read()}.problem);assertContentEquals(bytes,f.readBytes())
    }
}
