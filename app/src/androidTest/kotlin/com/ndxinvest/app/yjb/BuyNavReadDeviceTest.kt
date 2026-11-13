package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in GET-only check of existing acceptance. No writer, create, confirm/save or sync exists here. */
@RunWith(AndroidJUnit4::class)
class BuyNavReadDeviceTest {
    @Test fun realHistoryResolvesWithoutCalendarAndPreservesSyncedJournal()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.FLAVOR=="production"&&args.getString("ndxCheckBuyNav")=="READ_ONLY")
        val id=args.getString("ndxVerifyBuyId")?:error("EXISTING_TRANSACTION_REQUIRED")
        require(Regex("[0-9a-f-]{36}").matches(id))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val journal=AndroidBuyStorage(context);val before=journal.read()
        val trade=before.single {it.id==id}
        assertEquals(BuyStatus.SYNCED,trade.status)
        val storage=AndroidYjbStorage(context)
        val gateway=YjbBuyGateway(YjbReadApi(HttpsYjbWire(),storage::protocolKey),storage::token)
        assertFalse(gateway.writeAvailable())
        val forecast=BuyNavDates.forecast(trade.input,null)
        assertEquals(BuyNavDates.PROVISIONAL_SOURCE,forecast.second)
        val actual=gateway.resolveOfficialNav(trade.input.fund,BuyNavDates.searchStart(trade.input),BuyNavDates::weekend)
        assertNotNull(actual);assertEquals(trade.confirmedNavDate,actual!!.date)
        assertEquals(0,trade.confirmedNav!!.compareTo(actual.value))
        // Same real history safely skips Saturday/Sunday, without any fund-calendar access.
        val saturday=java.time.LocalDate.parse("2026-09-26")
        val weekend=gateway.resolveOfficialNav(trade.input.fund,saturday,BuyNavDates::weekend)
        assertNotNull(weekend);assertEquals(java.time.LocalDate.parse("2026-09-28"),weekend!!.date)
        assertEquals(before,journal.read())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("ndxBuyNavRead","REAL_HISTORY_EXACT=true WEEKEND_ROLL=true NO_CALENDAR=true JOURNAL_UNCHANGED=true NO_POST=true")
        })
    }
}
