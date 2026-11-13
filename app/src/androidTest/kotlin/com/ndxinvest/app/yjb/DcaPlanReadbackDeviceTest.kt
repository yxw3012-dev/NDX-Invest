package com.ndxinvest.app.yjb

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.app.MainActivity
import com.ndxinvest.yjb.dca.DcaFrequency
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read only: the user selected an existing Huaan daily 5-yuan NDX plan. No create/execute/sync. */
@RunWith(AndroidJUnit4::class)
class DcaPlanReadbackDeviceTest {
    @Test fun selectedHuaanPlanRestoresWithoutCreatingOrExecutingBuy() {
        assumeTrue(BuildConfig.FLAVOR=="production")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val before=runBlocking {AndroidDcaStorage(context).read()}
        val buyBefore=runBlocking {AndroidBuyStorage(context).read()}
        val sellBefore=runBlocking {AndroidSellStorage(context).read()}
        val accounts=runBlocking {AndroidYjbStorage(context).snapshot()}?.get("groups")?.jsonArray
            ?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}.orEmpty()
        val candidates=before.plans.filter {!it.deleted&&it.fund.accountId in accounts&&
            it.fund.fundName.contains("华安")&&it.config.frequency==DcaFrequency.DAILY&&it.config.amount.compareTo(BigDecimal("5"))==0}
        assertEquals("Expected one current-account Huaan daily 5-yuan plan; no mutation performed",1,candidates.size)
        val p=candidates.single()
        // This readback test must never trigger a naturally due real write on a later test date.
        assumeTrue(Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()<p.firstDate)
        require(Regex("[0-9a-fA-F-]{36}").matches(p.id))
        // Two new native controllers/WebViews independently restore from the encrypted journal.
        repeat(2) {
            ActivityScenario.launch(MainActivity::class.java).use {scenario->
                lateinit var view:WebView
                scenario.onActivity {view=(it.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout).getChildAt(0) as WebView}
                fun evaluate(script:String):String {
                    val latch=CountDownLatch(1);var result="null"
                    scenario.onActivity {view.evaluateJavascript(script){result=it;latch.countDown()}}
                    assertTrue(latch.await(5,TimeUnit.SECONDS));return result
                }
                val restored="Boolean(window.V2Store?.getState().plans.some(p=>p.id==='${p.id}'))"
                val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
                while(evaluate(restored)!="true"&&System.nanoTime()<end)Thread.sleep(100)
                assertEquals("true",evaluate(restored))
                val match="(()=>{const p=V2Store.getState().plans.find(p=>p.id==='${p.id}');return p.amountInput==='${p.config.amount.toPlainString()}'&&p.buyRateInput==='${p.config.feeRate.movePointRight(2).toPlainString()}'&&p.frequency==='daily'&&p.enabled===${p.enabled}&&p.nextDate==='${p.nextDate}';})()"
                assertEquals("true",evaluate(match))
                assertEquals("true",evaluate("Boolean(!V2Store.getState().transactions.some(t=>t.originPlanId==='${p.id}'))"))
            }
        }
        val after=runBlocking {AndroidDcaStorage(context).read()}
        // Automatic checks may append the conservative migration snapshot and check metadata.
        // User configuration and occurrence keys must remain unchanged before the plan's first date.
        assertEquals(before.plans.map {it.copy(history=emptyList(),lastCheckedAt=null,automationProblem=null,lastObservedNavDate=null)},after.plans.map {it.copy(history=emptyList(),lastCheckedAt=null,automationProblem=null,lastObservedNavDate=null)})
        assertEquals(before.occurrences,after.occurrences)
        assertEquals(buyBefore,runBlocking {AndroidBuyStorage(context).read()})
        assertEquals(sellBefore,runBlocking {AndroidSellStorage(context).read()})
        val today=Instant.now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val due=p.enabled&&p.nextDate<=today
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("ndxDcaPlanReadback","fund=${p.fund.fundName} code=${p.fund.fundCode} amount=${p.config.amount.toPlainString()} feePercent=${p.config.feeRate.movePointRight(2).toPlainString()} cadence=DAILY enabled=${p.enabled} nextDate=${p.nextDate} today=$today due=$due RESTORED_TWICE=true PLAN_CONFIG_AND_BUY_SELL_JOURNALS_UNCHANGED=true NO_NEW_OCCURRENCE=true")
        })
    }
}
