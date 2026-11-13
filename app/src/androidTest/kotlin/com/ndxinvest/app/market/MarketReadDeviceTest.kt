package com.ndxinvest.app.market

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.app.MainActivity
import com.ndxinvest.network.*
import com.ndxinvest.providers.config.*
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import android.content.ContextWrapper
import android.webkit.WebView
import android.widget.FrameLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit test transport is public GET-only. Foreground host retains its already authorized production services. */
@RunWith(AndroidJUnit4::class)
class MarketReadDeviceTest {
    @Test fun realPublicMarketReadsMapAndRestoreOfflineCache()=runBlocking {
        assumeTrue(BuildConfig.FLAVOR=="production"&&InstrumentationRegistry.getArguments().getString("ndxMarketRead")=="READ_ONLY")
        // Some device vendors kill instrumentation without a foreground activity after ~30s.
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(target.noBackupFilesDir,"market-public-read-acceptance").apply {mkdirs()}
        val context=object:ContextWrapper(target){override fun getNoBackupFilesDir()=directory}
        try {
        val client=OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build()
        val network=OkHttpProviderTransport(client,CredentialResolver {error("Public quotes must never resolve a credential")})
        var gets=0
        val guard=ProviderTransport {request->
            assertEquals(ProviderHttpMethod.GET,request.method);assertNull(request.credential)
            assertTrue(request.endpoint.startsWith("https://qt.gtimg.cn/")||request.endpoint.startsWith("https://hq.sinajs.cn/")||request.endpoint.startsWith("https://push2.eastmoney.com/")||request.endpoint.startsWith("https://api.nasdaq.com/"))
            gets++;network.execute(request)
        }
        var live=MarketReadState()
        MarketReadRepository(
            listOf(TencentNdxQuoteBinding.provider(guard),SinaNdxQuoteBinding.provider(guard)),
            listOf(EastMoneyUsdCnyBinding.provider(guard),TencentUsdCnyBinding.provider(guard)),
            {date->NasdaqNdxHistoryBinding.provider(guard,MarketReadRepository.HISTORY_FROM,date)},
            AndroidMarketCache(context),emit={live=it},
        ).refresh()
        assertNotNull("Live index GET required",live.index);assertFalse(live.index!!.cached)
        assertNotNull("Live FX GET required",live.fx);assertFalse(live.fx!!.cached)
        assertTrue(gets>=2);assertFalse(live.loading);assertFalse(live.cacheFailed)
        val mapped=MarketUiMapper().map(live,Instant.now())["nd100"]!!.jsonObject
        assertNotEquals("--",mapped["indexText"]!!.jsonPrimitive.content)
        assertNotEquals("--",mapped["fxText"]!!.jsonPrimitive.content)
        assertNotNull("Live historical closes required",live.history)
        assertNotEquals("--",mapped["drawdownText"]!!.jsonPrimitive.content)
        assertEquals(4,mapped["performance"]!!.jsonArray.size)
        assertFalse(mapped.toString().contains("Mock"))
        var restored=MarketReadState()
        val offline=ProviderTransport {TransportResult.NetworkError()}
        MarketReadRepository(emptyList(),emptyList(),{date->NasdaqNdxHistoryBinding.provider(offline,MarketReadRepository.HISTORY_FROM,date)},AndroidMarketCache(context),emit={restored=it}).refresh()
        assertEquals(live.index!!.value,restored.index!!.value);assertTrue(restored.index!!.cached)
        assertEquals(live.fx!!.value,restored.fx!!.value);assertTrue(restored.fx!!.cached)
        val viewState=AtomicReference<String>()
        var visible=false
        repeat(60) {
            if(!visible){
                val latch=CountDownLatch(1)
                scenario.onActivity {activity->
                    val content=activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    val webView=(content.getChildAt(0) as FrameLayout).getChildAt(0) as WebView
                    webView.evaluateJavascript("JSON.stringify({index:window.V2Store?.getState().nd100.indexText,fx:window.V2Store?.getState().nd100.fxText,drawdown:window.V2Store?.getState().nd100.drawdownText,plan:window.V2Store?.getState().nd100.planText,periods:window.V2Store?.getState().nd100.performance.length})") {value->viewState.set(value);latch.countDown()}
                }
                check(latch.await(5,TimeUnit.SECONDS))
                visible=runCatching {
                    val text=org.json.JSONArray("[${viewState.get()}]").getString(0)
                    val value=org.json.JSONObject(text)
                    value.optString("index").any {c->c.isDigit()}&&value.optString("fx").any {c->c.isDigit()}&&value.optString("drawdown").any {c->c.isDigit()}&&value.optString("plan").any {c->c.isDigit()}
                }.getOrDefault(false)
                if(!visible)kotlinx.coroutines.delay(1000)
            }
        }
        // No fixed price assertions: quotes can change between two real reads.
        val encoded=viewState.get()
        val text=org.json.JSONArray("[$encoded]").getString(0)
        val ui=org.json.JSONObject(text)
        assertNotEquals("--",ui.getString("index"));assertNotEquals("--",ui.getString("fx"))
        assertNotEquals("--",ui.getString("drawdown"));assertNotEquals("--",ui.getString("plan"));assertEquals(4,ui.getInt("periods"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putString("ndxMarketRead","REAL_INDEX=true REAL_FX=true RESTORED_CACHE=true FRONTEND_STATE=true GET_ONLY=true index=${mapped["indexText"]!!.jsonPrimitive.content} fx=${mapped["fxText"]!!.jsonPrimitive.content} historyRows=${live.history?.value?.size?:0}")
        })
        }finally {scenario.close();android.util.AtomicFile(File(directory,"ndx-market-read-v1.json")).delete();directory.delete()}
    }
}
