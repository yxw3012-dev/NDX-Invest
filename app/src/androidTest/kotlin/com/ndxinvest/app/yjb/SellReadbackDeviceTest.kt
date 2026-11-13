package com.ndxinvest.app.yjb

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.app.MainActivity
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit ID, native journal + fresh GET + cold-start UI verification. No writer or create action. */
@RunWith(AndroidJUnit4::class)
class SellReadbackDeviceTest {
    @Test fun savedSellRestoresAndUiUsesRemainingPlatformHolding() {
        val args=InstrumentationRegistry.getArguments()
        val id=args.getString("ndxVerifySellId")
        assumeTrue(BuildConfig.FLAVOR=="production"&&id!=null)
        require(Regex("[0-9a-f-]{36}").matches(id!!))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val before=runBlocking {AndroidSellStorage(context).read()}
        val buyBefore=runBlocking {AndroidBuyStorage(context).read()}
        val trade=before.single {it.id==id}
        assertEquals(SellStatus.SYNCED,trade.status);assertNotNull(trade.syncedAt)
        assertTrue(trade.postMayHaveBeenSent);assertNotNull(trade.submittedTarget)
        assertEquals(0,trade.baseline.unitCost.compareTo(trade.submittedTarget!!.unitCost))
        val storage=AndroidYjbStorage(context)
        val readOnly=YjbBuyGateway(YjbReadApi(HttpsYjbWire(),storage::protocolKey),storage::token) // writer null
        val actual=runBlocking {readOnly.current(trade.input.fund)}
        assertTrue(actual.equivalent(trade.submittedTarget!!))
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var view:WebView
            scenario.onActivity {view=(it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout).getChildAt(0) as WebView}
            fun evaluate(script:String):String {
                val latch=CountDownLatch(1);var result="null"
                scenario.onActivity {view.evaluateJavascript(script){result=it;latch.countDown()}}
                assertTrue(latch.await(5,TimeUnit.SECONDS));return result
            }
            fun waitTrue(script:String) {
                val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
                while(evaluate(script)!="true"&&System.nanoTime()<until)Thread.sleep(100)
                assertEquals("true",evaluate(script))
            }
            waitTrue("Boolean(window.V2Store&&window.V2App&&document.getElementById('framePortfolio').contentWindow.V2Portfolio)")
            val visible="Boolean(window.V2Store.getState().transactions.find(t=>t.id==='$id'&&t.kind==='SELL'&&t.transactionStatus==='SYNCED'))"
            waitTrue(visible)
            evaluate("window.__ndxSellReadBefore=window.V2Store.getState().yjbAccount.lastSyncAt;window.V2Store.startHoldingSync();true")
            waitTrue("Boolean(window.V2Store.getState().holdingsState.status==='READY'&&window.V2Store.getState().yjbAccount.lastSyncAt!==window.__ndxSellReadBefore)")
            assertEquals("true",evaluate("Boolean(window.V2Store.getState().details.find(d=>d.id===window.V2Store.getState().transactions.find(t=>t.id==='$id').fundId)?.shares==='${trade.submittedTarget!!.shares.toPlainString()}')"))
            evaluate("window.V2App.openRecords();true")
            val record="document.getElementById('frameRecords').contentDocument.querySelector('[data-record-id=\"$id\"]')"
            waitTrue("Boolean($record)")
            evaluate("$record.click();true")
            waitTrue("Boolean(document.querySelector('[data-native-sell-note]'))")
            assertEquals("true",evaluate("Boolean(document.getElementById('settingsScroll').textContent.includes('不代表实际到账'))"))
            assertEquals("true",evaluate("Boolean(document.getElementById('settingsScroll').textContent.includes('${trade.confirmedNav!!.toPlainString()}'))"))
            assertEquals("true",evaluate("Boolean(!document.querySelector('[data-gap-action=rebase]')&&!document.querySelector('[data-gap-action=edit]'))"))
            val sourceBuy=args.getString("ndxVerifySourceBuyId")
            if(sourceBuy!=null) {
                require(Regex("[0-9a-f-]{36}").matches(sourceBuy))
                assertEquals("true",evaluate("Boolean(window.V2Store.getState().transactions.find(t=>t.id==='$sourceBuy'&&t.kind==='BUY'&&t.transactionStatus==='SYNCED'))"))
            }
        }
        assertEquals(before,runBlocking {AndroidSellStorage(context).read()})
        assertEquals(buyBefore,runBlocking {AndroidBuyStorage(context).read()})
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("ndxSellReadback","REAL_GET_MATCH=true SYNCED_RESTORED=true RECORD_DETAIL_VISIBLE=true HOLDINGS_REFRESH_READY=true BUY_AND_SELL_JOURNALS_UNCHANGED=true")
        })
    }
}
