package com.ndxinvest.app.market

import com.ndxinvest.market.model.*
import com.ndxinvest.providers.contract.*
import com.ndxinvest.providers.persistence.ProviderCacheCodec
import com.ndxinvest.providers.persistence.ProviderCacheCodecs
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Public market data only. No session, holdings, ledger, Room or trading services. */
internal interface MarketCache {
    suspend fun read(): JsonObject?
    suspend fun write(value: JsonObject): Boolean
}
internal data class MarketValue<T>(val value:T, val fetchedAt:Instant, val cached:Boolean=false)
internal data class MarketReadState(
    val index:MarketValue<NdxQuote>?=null,
    val fx:MarketValue<UsdCnyQuote>?=null,
    val history:MarketValue<List<NdxDaily>>?=null,
    val loading:Boolean=false,
    val cacheFailed:Boolean=false,
    val historyFailed:Boolean=false,
)

internal class MarketReadRepository(
    private val indices:List<DataProvider<NdxQuote>>,
    private val currencies:List<DataProvider<UsdCnyQuote>>,
    private val historyProvider:(LocalDate)->DataProvider<List<NdxDaily>>,
    private val cache:MarketCache,
    private val clock:Clock=Clock.systemUTC(),
    private val emit:suspend (MarketReadState)->Unit,
) {
    private val gate=Mutex()
    private val stateGate=Mutex()
    private var state=MarketReadState()
    private var restored=false

    suspend fun refresh() {
        if(!gate.tryLock())return
        try {
            if(!restored){
                state=decode(safe {cache.read()},clock.instant())
                restored=true
            }
            update {it.copy(loading=true)}
            coroutineScope {
                listOf(async {
                    val result=firstValid(indices) {validateIndex(it,clock.instant())}
                    update {it.copy(index=result?:it.index?.copy(cached=true))}
                },async {
                    val result=firstValid(currencies) {validateFx(it,clock.instant())}
                    update {it.copy(fx=result?:it.fx?.copy(cached=true))}
                },async {
                    val old=state.history
                    // Full pagination is expensive; a successful history snapshot is reused for 24h.
                    if(old!=null&&Duration.between(old.fetchedAt,clock.instant())<Duration.ofHours(24)) {
                        update {it.copy(history=old.copy(cached=true),historyFailed=false)}
                    }else {
                        val result=firstValid(listOf(historyProvider(LocalDate.now(clock.withZone(NEW_YORK))))) {
                            validateHistory(it,clock.instant())
                        }
                        update {it.copy(history=result?:it.history?.copy(cached=true),historyFailed=result==null)}
                    }
                }).awaitAll()
            }
            update {it.copy(loading=false)}
        }finally {gate.unlock()}
    }

    private suspend fun update(change:(MarketReadState)->MarketReadState)=stateGate.withLock {
        state=change(state)
        if(!state.loading) {
            val saved=safe {cache.write(encode(state))}==true
            state=state.copy(cacheFailed=!saved)
        }
        emit(state)
    }
    private suspend fun <T> firstValid(providers:List<DataProvider<T>>,validate:(MarketValue<T>)->Unit):MarketValue<T>? {
        for(provider in providers) {
            val result=safe {provider.fetch()}
            if(result is ProviderResult.Success) {
                val value=MarketValue(result.value,result.fetchedAt)
                if(runCatching {validate(value)}.isSuccess)return value
            }
        }
        return null
    }
    private suspend fun <T> safe(block:suspend ()->T):T?=try {block()}catch(e:CancellationException){throw e}catch(_:Exception){null}

    companion object {
        // Broad request range only; the source need not return inception coverage to enable drawdown.
        val HISTORY_FROM:LocalDate=LocalDate.of(1985,1,31)
        private val NEW_YORK=ZoneId.of("America/New_York")
        private fun time(fetched:Instant,quoted:Instant,now:Instant) {
            require(fetched>Instant.EPOCH&&fetched<=now.plusSeconds(300))
            require(quoted>Instant.EPOCH&&quoted<=fetched.plusSeconds(300))
        }
        fun validateIndex(v:MarketValue<NdxQuote>,now:Instant) {
            time(v.fetchedAt,v.value.quoteTime,now)
            require(v.value.point.toPlainString().length<=32&&v.value.change.abs()<v.value.point)
            require(v.value.changeRate.abs()<=java.math.BigDecimal.ONE)
        }
        fun validateFx(v:MarketValue<UsdCnyQuote>,now:Instant) {
            time(v.fetchedAt,v.value.quoteTime,now)
            require(v.value.rate<java.math.BigDecimal("1000"))
            val change=v.value.changeRate
            require(change==null||change.abs()<java.math.BigDecimal.ONE)
        }
        fun validateHistory(v:MarketValue<List<NdxDaily>>,now:Instant) {
            require(v.fetchedAt>Instant.EPOCH&&v.fetchedAt<=now.plusSeconds(300))
            val rows=v.value
            require(rows.size in 3..30000&&rows.map {it.date}.distinct().size==rows.size)
            require(rows.all {it.date>=HISTORY_FROM&&it.date<=now.atZone(NEW_YORK).toLocalDate()&&it.close.toPlainString().length<=32})
        }
        fun encode(s:MarketReadState):JsonObject=buildJsonObject {
            put("version",1)
            fun <T> entry(name:String,v:MarketValue<T>?,codec:ProviderCacheCodec<T>){
                if(v!=null)put(name,buildJsonObject {put("fetchedAt",v.fetchedAt.toString());put("value",codec.encode(v.value))})
            }
            entry("index",s.index,ProviderCacheCodecs.ndxQuote)
            entry("fx",s.fx,ProviderCacheCodecs.usdCnyQuote)
            entry("history",s.history,ProviderCacheCodecs.ndxHistory)
        }
        fun decode(json:JsonObject?,now:Instant):MarketReadState {
            if(json?.get("version")?.jsonPrimitive?.content!="1")return MarketReadState()
            fun <T> entry(name:String,codec:ProviderCacheCodec<T>,valid:(MarketValue<T>,Instant)->Unit):MarketValue<T>?=runCatching {
                val row=json.getValue(name).jsonObject
                MarketValue(codec.decode(row.getValue("value").jsonPrimitive.content),Instant.parse(row.getValue("fetchedAt").jsonPrimitive.content),true).also {valid(it,now)}
            }.getOrNull()
            return MarketReadState(entry("index",ProviderCacheCodecs.ndxQuote,::validateIndex),entry("fx",ProviderCacheCodecs.usdCnyQuote,::validateFx),entry("history",ProviderCacheCodecs.ndxHistory,::validateHistory))
        }
    }
}
