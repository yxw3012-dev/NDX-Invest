package com.ndxinvest.app.market

import com.ndxinvest.market.calculation.CurrentDrawdownCalculator
import com.ndxinvest.market.calculation.LongTermPerformanceCalculator
import com.ndxinvest.market.model.*
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.*

/** Formats presentation only; raw decimals stay in typed facts/cache, never parsed back for accounting. */
internal class MarketUiMapper {
    private var previous:List<NdxDaily>?=null
    private var performance=performances(null)
    fun map(s:MarketReadState,now:Instant):JsonObject {
        val rows=s.history?.value?.sortedBy {it.date}
        if(s.history?.value!==previous){previous=s.history?.value;performance=performances(rows)}
        // Drawdown uses the actual available historical range, disclosed alongside the chart.
        val drawdown=if(!rows.isNullOrEmpty()&&s.index!=null)CurrentDrawdownCalculator.calculate(rows,s.index.value.point).drawdownRate else null
        val quote=s.index?.value
        val fx=s.fx?.value
        val last=listOfNotNull(s.index?.fetchedAt,s.fx?.fetchedAt).maxOrNull()
        return buildJsonObject {
            put("nd100",buildJsonObject {
                put("indexText",quote?.point?.let {number(it,2)}?:"--")
                put("changeText",quote?.change?.let {signed(it,2)}?:"--")
                put("changePercentText",quote?.changeRate?.let {percent(it,true)}?:"--")
                put("marketStatus",if(quote==null)if(s.loading)"读取中" else "暂不可用" else session(quote.marketState))
                put("updatedText",quote?.let {time(it.quoteTime)}?:"--")
                put("fxText",fx?.rate?.let {number(it,4)}?:"--")
                put("fxChangeText",fx?.changeRate?.let {percent(it,true)}?:"--")
                put("fxUpdatedText",fx?.let {"${source(it.source.value)} · 行情 ${time(it.quoteTime)}"}?:"暂无汇率时间")
                put("drawdownText",drawdown?.let {percent(it,false)}?:"--")
                put("drawdownRate",drawdown?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("historyText",if(rows==null)if(s.loading)"正在读取历史" else "历史数据暂不可用" else
                    "历史范围 ${rows.first().date}—${rows.last().date}"+(if(s.history!!.cached)" · 缓存" else ""))
                put("drawdownBasisText",if(rows==null)"历史数据暂不可用" else "基于 ${rows.first().date} 至 ${rows.last().date} 的历史最高收盘价")
                put("performance",performance)
                put("refreshing",s.loading)
                put("refreshSucceeded",!s.loading&&s.index!=null&&!s.index.cached&&s.fx!=null&&!s.fx.cached&&rows!=null&&!s.historyFailed&&!s.cacheFailed)
                put("refreshError",if(s.loading)"" else listOfNotNull(
                    if(s.index==null||s.index.cached)"指数更新失败" else null,
                    if(s.fx==null||s.fx.cached)"汇率更新失败" else null,
                    if(rows==null||s.historyFailed)"历史数据更新失败" else null,
                    if(s.cacheFailed)"数据缓存保存失败" else null,
                ).joinToString("；"))
            })
            put("dataSourceState",buildJsonObject {
                put("indexStatus",status(s.index,quote?.quoteTime,quote?.source?.value,now,s.loading))
                put("fxStatus",status(s.fx,fx?.quoteTime,fx?.source?.value,now,s.loading))
                put("historyStatus",if(rows==null)"历史数据暂不可用" else "Nasdaq · ${rows.first().date} 至 ${rows.last().date}"+(if(s.history!!.cached)" · 缓存" else ""))
                put("lastUpdatedAt",last?.let(::time)?:"--")
                put("cacheStatus",if(s.cacheFailed)"缓存未能保存，重启后可能不可用" else "正常")
            })
        }
    }
    private fun performances(rows:List<NdxDaily>?):JsonArray=buildJsonArray {
        listOf(1,3,5,10).forEach {years->
            val end=rows?.lastOrNull()?.date
            val from=end?.minusYears(years.toLong())
            // Use the last close on/before the anniversary, never claim unavailable early coverage.
            val start=if(from!=null)rows?.lastOrNull {it.date<=from} else null
            val window=if(start!=null)rows!!.filter {it.date>=start.date} else emptyList()
            val metrics=if(window.size>=3)runCatching {
                val span=BigDecimal(ChronoUnit.DAYS.between(window.first().date,window.last().date))
                    .divide(BigDecimal("365.2425"),MathContext.DECIMAL128)
                val p=LongTermPerformanceCalculator.calculate(window,span)
                listOf(percent(p.cumulativeReturn,true),percent(p.cagr,true),percent(p.maximumDrawdown.rate,false),percent(p.annualizedVolatility,false))
            }.getOrNull()else null
            add(JsonArray((metrics?:List(4){"--"}).map(::JsonPrimitive)))
        }
    }
    companion object {
        private val ZONE=ZoneId.of("Asia/Shanghai")
        private val FORMAT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE)
        fun time(t:Instant):String=FORMAT.format(t)
        fun number(n:BigDecimal,scale:Int):String=DecimalFormat("#,##0."+"0".repeat(scale),DecimalFormatSymbols(Locale.US)).apply {roundingMode=RoundingMode.HALF_UP}.format(n)
        private fun signed(n:BigDecimal,scale:Int):String=(if(n.signum()>0)"+" else "")+number(n,scale)
        private fun percent(n:BigDecimal,signed:Boolean):String=(if(signed&&n.signum()>0)"+" else "")+number(n.movePointRight(2),2)+"%"
        private fun source(s:String)=when(s){"tencent"->"腾讯";"sina"->"新浪";"eastmoney"->"东方财富";"nasdaq"->"Nasdaq";else->s}
        private fun session(s:MarketSessionState)=when(s){MarketSessionState.OPEN->"交易中";MarketSessionState.CLOSED->"已休市";MarketSessionState.PRE_MARKET->"盘前";MarketSessionState.AFTER_HOURS->"盘后";MarketSessionState.UNKNOWN->"最近行情"}
        private fun status(v:MarketValue<*>?,quoted:Instant?,provider:String?,now:Instant,loading:Boolean):String {
            if(v==null)return if(loading)"读取中" else "读取失败 · 暂无缓存"
            return listOfNotNull(if(v.cached)"缓存" else "已读取",source(provider!!),
                if(quoted!=null&&Duration.between(quoted,now)>Duration.ofHours(1))"最近报价 ${time(quoted)}" else quoted?.let {"报价 ${time(it)}"},
                if(loading)"更新中" else null).joinToString(" · ")
        }
    }
}
