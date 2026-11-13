package com.ndxinvest.app

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.yjb.AndroidBuyStorage
import com.ndxinvest.app.yjb.AndroidSellStorage
import com.ndxinvest.yjb.buy.BuyStatus
import kotlinx.coroutines.runBlocking

/** Boolean-only UI smoke. Does not choose a fund/amount, create BUY or send any YJB POST. */
class ProductionNavigationDeviceTest {
    @Test fun productionLaunchesFrozenUiAndNavigatesWithoutCreatingTransactions() {
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR=="production")
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val sellBefore=runBlocking {AndroidSellStorage(target).read()}
        ActivityScenario.launch(MainActivity::class.java).use {scenario->
            lateinit var view:WebView
            scenario.onActivity {view=(it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout).getChildAt(0) as WebView}
            fun evaluate(script:String):String {val latch=CountDownLatch(1);var result="null";scenario.onActivity {view.evaluateJavascript(script){result=it;latch.countDown()}};assertTrue(latch.await(5,TimeUnit.SECONDS));return result}
            val ready="Boolean(window.V2Store&&document.getElementById('framePortfolio').contentWindow.V2Portfolio)"
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            while(evaluate(ready)!="true"&&System.nanoTime()<end)Thread.sleep(100)
            assertEquals("true",evaluate(ready));assertEquals("true",evaluate("Boolean(window.NDXYjbRead&&window.YjbReadApply)"))
            val acceptanceId=InstrumentationRegistry.getArguments().getString("ndxVerifyBuyId")
            if(acceptanceId!=null) {
                require(Regex("[0-9a-f-]{36}").matches(acceptanceId))
                val before=runBlocking {AndroidBuyStorage(InstrumentationRegistry.getInstrumentation().targetContext).read()}
                val trade=before.single {it.id==acceptanceId}
                assertEquals(BuyStatus.SYNCED,trade.status);assertNotNull(trade.syncedAt)
                assertNotNull(trade.submittedTarget);assertTrue(trade.postMayHaveBeenSent)
                val visible="Boolean(window.V2Store.getState().transactions.find(t=>t.id==='$acceptanceId'&&t.kind==='BUY'&&t.transactionStatus==='SYNCED'))"
                val restoredUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while(evaluate(visible)!="true"&&System.nanoTime()<restoredUntil)Thread.sleep(100)
                assertEquals("true",evaluate(visible))
                evaluate("window.__ndxReadBefore=window.V2Store.getState().yjbAccount.lastSyncAt;window.V2Store.startHoldingSync();true") // GET only
                val refreshed="Boolean(window.V2Store.getState().holdingsState.status==='READY'&&window.V2Store.getState().yjbAccount.lastSyncAt!==window.__ndxReadBefore)"
                val refreshedUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
                while(evaluate(refreshed)!="true"&&System.nanoTime()<refreshedUntil)Thread.sleep(100)
                assertEquals("true",evaluate(refreshed));assertEquals("true",evaluate(visible))
                assertEquals("true",evaluate("Boolean(window.V2Store.getState().details.find(d=>d.id===window.V2Store.getState().transactions.find(t=>t.id==='$acceptanceId').fundId)?.shares==='${trade.submittedTarget!!.shares.toPlainString()}')"))
                evaluate("window.V2App.openRecords();true")
                Thread.sleep(300)
                assertEquals("true",evaluate("Boolean(document.getElementById('frameRecords').contentDocument.querySelector('[data-record-id=\"$acceptanceId\"]'))"))
                evaluate("window.V2App.closeRecords();true")
                assertEquals(before,runBlocking {AndroidBuyStorage(InstrumentationRegistry.getInstrumentation().targetContext).read()})
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                    putString("ndxBuyUiRestore","SYNCED_RESTORED=true RECORD_VISIBLE=true HOLDINGS_REFRESH_READY=true BUY_JOURNAL_UNCHANGED=true")
                })
            }
            for(name in listOf("持仓","我的","NDX100")){
                val id=when(name){"持仓"->"framePortfolio";"我的"->"frameMine";else->"frameNDX100"}
                val active="Boolean(document.getElementById('$id')?.classList.contains('is-active'))"
                val navigationUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
                // The frozen shell intentionally ignores navigation during sheet/page transitions.
                // Wait/retry the read-only navigation action, rather than treating a fixed 400ms as settled.
                do {
                    evaluate("document.querySelector('[data-page=\"$name\"]').click();true")
                    if(evaluate(active)=="true")break
                    Thread.sleep(150)
                }while(System.nanoTime()<navigationUntil)
                assertEquals("true",evaluate(active))
            }
            assertEquals(sellBefore,runBlocking {AndroidSellStorage(target).read()})
        }
    }
}
