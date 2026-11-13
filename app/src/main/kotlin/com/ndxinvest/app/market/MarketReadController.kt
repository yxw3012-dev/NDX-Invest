package com.ndxinvest.app.market

import android.content.Context
import android.util.AtomicFile
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import com.ndxinvest.network.*
import com.ndxinvest.providers.config.*
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient

/** A closed public-data bridge; no URL, credential or transaction input accepted from the page. */
internal class MarketReadController(activity:ComponentActivity,render:(JsonObject)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mapper=MarketUiMapper()
    private val wire=OkHttpProviderTransport(
        OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
            .callTimeout(20,TimeUnit.SECONDS).build(),CredentialResolver {null},
    )
    private val repository=MarketReadRepository(
        listOf(TencentNdxQuoteBinding.provider(wire),SinaNdxQuoteBinding.provider(wire)),
        listOf(EastMoneyUsdCnyBinding.provider(wire),TencentUsdCnyBinding.provider(wire)),
        {date->NasdaqNdxHistoryBinding.provider(wire,MarketReadRepository.HISTORY_FROM,date)},
        AndroidMarketCache(activity.applicationContext),
        emit={s->
            val payload=mapper.map(s,Instant.now())
            if(!s.loading&&s.index!=null&&!s.index.cached&&s.history!=null&&!s.historyFailed) {
                payload["nd100"]?.jsonObject?.get("drawdownRate")?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()?.let {rate->
                    com.ndxinvest.app.notifications.NdxReminders.observeMarket(activity.applicationContext,rate,s.index.value.quoteTime.atZone(java.time.ZoneId.of("America/New_York")).toLocalDate())
                }
            }
            withContext(Dispatchers.Main.immediate){render(payload)}
        },
    )
    private var booted=false
    @JavascriptInterface fun post(command:String) {
        if(command!="boot"&&command!="refresh")return
        scope.launch {
            if(command=="boot"&&booted)return@launch
            booted=true
            repository.refresh()
        }
    }
    fun close(){scope.cancel()}
}

/** Contains only public decimal market facts. Independent atomic file; existing Room untouched. */
internal class AndroidMarketCache(context:Context):MarketCache {
    private val file=AtomicFile(File(context.noBackupFilesDir,"ndx-market-read-v1.json"))
    override suspend fun read():JsonObject?=withContext(Dispatchers.IO) {
        try {
            file.openRead().use {input->
                val bytes=input.readNBytesBounded()
                Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
    override suspend fun write(value:JsonObject):Boolean=withContext(Dispatchers.IO) {
        try {
            val bytes=value.toString().toByteArray()
            if(bytes.size>MAX_BYTES)return@withContext false
            val out=file.startWrite()
            try{out.write(bytes);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
            file.openRead().use {it.readNBytesBounded().contentEquals(bytes)}
        }catch(e:CancellationException){throw e}catch(_:Exception){false}
    }
    private fun java.io.InputStream.readNBytesBounded():ByteArray {
        val out=java.io.ByteArrayOutputStream()
        val chunk=ByteArray(8192)
        while(true){val size=read(chunk);if(size<0)break;check(out.size()+size<=MAX_BYTES);out.write(chunk,0,size)}
        return out.toByteArray()
    }
    companion object {private const val MAX_BYTES=4*1024*1024}
}
