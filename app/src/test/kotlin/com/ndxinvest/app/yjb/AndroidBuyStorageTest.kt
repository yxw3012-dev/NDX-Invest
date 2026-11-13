package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.*
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

/** Host AES/filesystem test, not a real YJB write or device Keystore acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[PosixAtomicFileShadow::class])
class AndroidBuyStorageTest {
    @Test fun encryptedJournalRestoresWaitingReadyAndDeliveryMarker()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>();val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val provider=CredentialKeyProvider{key}
        fun storage()=AndroidBuyStorage(context,provider)
        val now=Instant.parse("2026-10-03T00:00:00Z");val date=LocalDate.parse("2026-09-28")
        val waiting=BuyTransaction("test-private-record",BuyInput(BuyFund("1","17","270042","private-fund-name"),BigDecimal("1000.123456789"),BigDecimal("0.0015"),date,CutoffType.BEFORE_1500),BuyBaseline(BigDecimal("100"),BigDecimal("1.2")),date,createdAt=now,updatedAt=now,calendarSource="test-only")
        storage().write(listOf(waiting));assertEquals(waiting,storage().read().single())
        val file=java.io.File(context.noBackupFilesDir,"ndx-buy-transactions-v1.enc");val bytes=file.readBytes().toString(Charsets.UTF_8);assertFalse(bytes.contains("private-fund"));assertFalse(bytes.contains("1000.123456789"))
        val ready=BuyCalculation.confirm(waiting,date,BigDecimal("2.123456789"),now);storage().write(listOf(ready));assertFalse(bytes==file.readBytes().toString(Charsets.UTF_8),"Replacement must change ciphertext");assertEquals(ready,storage().read().single())
        val sent=ready.copy(status=BuyStatus.SYNCING,postMayHaveBeenSent=true);storage().write(listOf(sent));assertEquals(sent,storage().read().single())
        // Local history survives all session operations because it uses a separate file/AAD/key.
        assertFalse(file.name.contains("session"));assertEquals(sent,storage().read().single())
    }
    @Test fun corruptJournalFailsClosedInsteadOfResettingHistory()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>();val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val s=AndroidBuyStorage(context,CredentialKeyProvider{key});s.write(emptyList())
        val file=java.io.File(context.noBackupFilesDir,"ndx-buy-transactions-v1.enc");val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertEquals(BuyProblem.STORAGE,assertFailsWith<BuyFailure>{s.read()}.problem);assertContentEquals(bytes,file.readBytes())
    }
}
