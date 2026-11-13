package com.ndxinvest.yjb.statistics

import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.text
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

/** Read-only statistical projection. It never reconstructs authoritative holdings or calls YJB. */
object PersonalStatistics {
    fun matchesSnapshot(snapshot:JsonObject,displayedAt:String?):Boolean=
        !displayedAt.isNullOrBlank()&&snapshot.text("lastSyncAt")==displayedAt
    private val zone=ZoneId.of("Asia/Shanghai")
    private val cent=BigDecimal("0.01")
    private data class Position(val fund:BuyFund,val raw:JsonObject,val quote:JsonObject)
    private data class Result(val id:String,val value:BigDecimal?,val valuationDate:LocalDate?,val closed:Boolean,
        val flows:List<PersonalCashFlow>,val initial:BigDecimal?,val invested:BigDecimal?,val proceeds:BigDecimal?,
        val cumulative:BigDecimal?,val realized:BigDecimal?,val reason:String?,val annualReason:String?,
        val rate:BigDecimal?,val start:LocalDate?,val days:Long?)

    fun calculate(snapshot:JsonObject,imports:JsonArray,buys:List<BuyTransaction>,sells:List<SellTransaction>,
        now:Instant=Instant.now()):JsonObject {
        val today=now.atZone(zone).toLocalDate()
        val quotes=snapshot["quotes"]!!.jsonArray.associate {it.jsonObject.text("fund_id") to it.jsonObject}
        val positions=snapshot["groups"]!!.jsonArray.flatMap {group->
            group.jsonObject["funds"]!!.jsonArray.map {item->val h=item.jsonObject;val id=h.text("fund_id")!!
                Position(BuyFund(group.jsonObject.text("accountId")!!,id,quotes[id]?.text("code")?:h.text("code")?:"--",""),h,quotes[id]?:JsonObject(emptyMap()))}
        }
        val duplicateFunds=positions.groupingBy {it.fund.fundId}.eachCount()
        val imported=imports.groupBy {it.jsonObject.text("fundId")}
        val buyGroups=buys.groupBy {it.input.fund.uiId};val sellGroups=sells.groupBy {it.input.fund.uiId}
        val results=positions.map {p->
            try {
                val row=imported[p.fund.fundId]?.singleOrNull()?.jsonObject
                val fundBuys=buyGroups[p.fund.uiId].orEmpty()
                val result=fund(p,row,fundBuys,sellGroups[p.fund.uiId].orEmpty(),today,duplicateFunds[p.fund.fundId]!=1)
                val start=if(duplicateFunds[p.fund.fundId]==1)holdingOrigin(row,fundBuys,today)else null
                result.copy(start=start,days=start?.takeIf {decimal(p.raw,"hold_share")?.signum()==1&&it<=today}?.let {ChronoUnit.DAYS.between(it,today)})
            }
            catch(_:Exception){unavailable(p.fund.uiId,"INCOMPLETE_DATA")}
        }
        return buildJsonObject {
            put("funds",buildJsonObject {results.forEach {put(it.id,ui(it))}})
            put("overview",overview(results))
            put("basis","NDX_LOCAL_CONTRIBUTIONS_365_DAYS")
        }
    }
    private fun holdingOrigin(row:JsonObject?,buys:List<BuyTransaction>,today:LocalDate):LocalDate? {
        val dates=buys.filter {it.status==BuyStatus.SYNCED}.mapNotNull {it.confirmedNavDate?.takeIf {d->d<=today}}.toMutableList()
        if(row!=null){
            val imported=Instant.parse(row.text("importedAt")).atZone(zone).toLocalDate()
            val entries=row["initialContributions"] as? JsonArray
            if(!entries.isNullOrEmpty()){
                val values=entries.map {it.jsonObject}
                val sum=values.fold(BigDecimal.ZERO){a,v->a+(decimal(v,"amount")?:error("Missing amount"))}
                val cost=decimal(row,"initialHoldingCost")
                val entryDates=values.mapNotNull {parsedDate(it.text("date"))?.takeIf {d->d<=minOf(imported,today)&&d.year>=1900}}
                if(cost!=null&&(sum-cost).abs()<=cent&&entryDates.size==entries.size)dates+=entryDates
            }else parsedDate(row.text("holdingStartDate"))?.takeIf {it<=minOf(imported,today)}?.let(dates::add)
        }
        return dates.minOrNull()
    }
    private fun fund(p:Position,importRow:JsonObject?,buys:List<BuyTransaction>,sells:List<SellTransaction>,today:LocalDate,ambiguous:Boolean):Result {
        if(ambiguous)return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        // A verified first BUY from an absent position starts at zero. This transient calculation
        // seed is not an import record and adds no invented historical cash flow.
        val first=buys.filter {it.status==BuyStatus.SYNCED&&!it.baseline.exists}.minByOrNull {it.createdAt}
        val imported=importRow?:first?.let {buildJsonObject {put("importedAt",it.createdAt.minusNanos(1).toString());put("initialHoldingCost","0");put("initialShares","0");put("initialUnitCost","0")}}?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        val importedAt=Instant.parse(imported.text("importedAt"))
        val importedDate=importedAt.atZone(zone).toLocalDate()
        val initial=decimal(imported,"initialHoldingCost")?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        if(initial.signum()<0)return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        var expected=BuyBaseline(decimal(imported,"initialShares")?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA"),decimal(imported,"initialUnitCost")?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA"),importRow!=null)
        val entries=imported["initialContributions"] as? JsonArray
        val hasEntries=!entries.isNullOrEmpty()
        val flows=mutableListOf<PersonalCashFlow>();var annualReason:String?=null
        val date=parsedDate(imported.text("holdingStartDate"))?.takeIf {it<=minOf(importedDate,today)}
        var initialStart:LocalDate?=null
        if(hasEntries){
            val input=entries!!.map {it.jsonObject}.map {entry->
                val d=parsedDate(entry.text("date"))?:error("Invalid contribution date")
                val a=decimal(entry,"amount")?:error("Invalid contribution amount")
                require(d.year>=1900&&d<=minOf(importedDate,today)&&a.signum()>0)
                PersonalCashFlow(d,-a)
            }
            val sum=input.fold(BigDecimal.ZERO){a,f->a-f.amount}
            if((sum-initial).abs()>cent)annualReason="INITIAL_AMOUNT_MISMATCH"
            else {flows+=input;initialStart=input.minOf {it.date}}
        }else if(initial.signum()>0){
            if(date==null)annualReason="MISSING_INITIAL_DATE" else {flows+=PersonalCashFlow(date,-initial);initialStart=date}
        }else initialStart=date
        // Observed snapshot already includes completed transactions at/before import: never double count them.
        val b=buys.filter {it.status==BuyStatus.SYNCED&&it.syncedAt!=null&&it.syncedAt>importedAt}
        val s=sells.filter {it.status==SellStatus.SYNCED&&it.syncedAt!=null&&it.syncedAt>importedAt}
        if(buys.any {it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}||
            sells.any {it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)})return unavailable(p.fund.uiId,"PENDING_WRITE")
        if(buys.any {it.status==BuyStatus.SYNCED&&it.syncedAt==null}||sells.any {it.status==SellStatus.SYNCED&&it.syncedAt==null})return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        val ordered=(b.map {Triple(it.syncedAt!!,it.id,it as Any)}+s.map {Triple(it.syncedAt!!,it.id,it as Any)}).sortedWith(compareBy({it.first},{it.second}))
        for((_,_,tx) in ordered){
            val baseline=if(tx is BuyTransaction)tx.executionBaseline?:tx.baseline else (tx as SellTransaction).executionBaseline?:tx.baseline
            if(!expected.equivalent(baseline))return unavailable(p.fund.uiId,"EXTERNAL_POSITION_CHANGED")
            expected=if(tx is BuyTransaction)tx.submittedTarget?:tx.target?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
                else (tx as SellTransaction).submittedTarget?:tx.target?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        }
        val shares=decimal(p.raw,"hold_share")?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        val unit=decimal(p.raw,"hold_cost")?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
        if(!expected.equivalent(BuyBaseline(shares,unit)))return unavailable(p.fund.uiId,"EXTERNAL_POSITION_CHANGED")
        val observedCost=decimal(p.raw,"holding_cost")
        if(observedCost!=null&&(observedCost-expected.totalCost).abs()>cent)return unavailable(p.fund.uiId,"EXTERNAL_POSITION_CHANGED")
        val value=if(shares.signum()==0)BigDecimal.ZERO else decimal(p.raw,"money")?.takeIf {it.signum()>=0}
        val invested=b.fold(BigDecimal.ZERO){a,t->a+t.input.amount}
        var proceeds=BigDecimal.ZERO;var realized=BigDecimal.ZERO
        for(t in b)flows+=PersonalCashFlow(t.input.tradeDate,-t.input.amount)
        for(t in s){
            val net=t.netAmount?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
            val profit=t.calculatedProfit?:return unavailable(p.fund.uiId,"INCOMPLETE_DATA")
            proceeds+=net;realized+=profit;flows+=PersonalCashFlow(t.input.tradeDate,net)
        }
        val navDate=parsedDate(p.quote.text("net_day")?:p.raw.text("net_day"))?.takeIf {it<=today}
        val valuation=if(shares.signum()==0)ordered.lastOrNull()?.let {if(it.third is SellTransaction)(it.third as SellTransaction).confirmedNavDate else (it.third as BuyTransaction).confirmedNavDate}?:navDate else navDate
        val start=(listOfNotNull(initialStart)+b.mapNotNull {it.confirmedNavDate}).minOrNull()
        val days=start?.takeIf {shares.signum()>0&&it<=today}?.let {ChronoUnit.DAYS.between(it,today)}
        val cumulative=value?.let {it+proceeds-initial-invested}
        if(value==null)annualReason=annualReason?:"MISSING_MARKET_VALUE"
        if(valuation==null)annualReason=annualReason?:"MISSING_VALUATION_DATE"
        if(valuation!=null&&flows.any {it.date>valuation})annualReason=annualReason?:"VALUATION_BEFORE_CASH_FLOW"
        val annual=if(annualReason==null&&value!=null&&valuation!=null)PersonalXirr.calculate(flows+PersonalCashFlow(valuation,value))else PersonalXirrResult(reason=annualReason)
        return Result(p.fund.uiId,value,valuation,shares.signum()==0,flows,initial,invested,proceeds,cumulative,realized,null,annual.reason,annual.rate,start,days)
    }
    private fun overview(results:List<Result>):JsonObject {
        if(results.isEmpty())return buildJsonObject {put("annualized","--");put("reason","INCOMPLETE_DATA")}
        val failure=results.firstOrNull {it.reason!=null}
        if(failure!=null)return buildJsonObject {put("annualized","--");put("cumulativeProfit","--");put("realizedProfit","--");put("reason",failure.reason)}
        fun total(get:(Result)->BigDecimal?):BigDecimal?=if(results.any {get(it)==null})null else results.fold(BigDecimal.ZERO){a,r->a+get(r)!!}
        val dates=results.filter {!it.closed}.map {it.valuationDate}.distinct()
        val date=if(dates.isEmpty())results.mapNotNull {it.valuationDate}.maxOrNull()else dates.singleOrNull()
        val flows=results.flatMap {it.flows};val value=total {it.value}
        var reason=results.firstNotNullOfOrNull {it.annualReason?.takeUnless {r->r=="UNDER_90_DAYS"}}
        if(date==null)reason=reason?:"VALUATION_DATES_NOT_ALIGNED"
        if(value==null)reason=reason?:"MISSING_MARKET_VALUE"
        if(date!=null&&flows.any {it.date>date})reason=reason?:"VALUATION_BEFORE_CASH_FLOW"
        val rate=if(reason==null&&date!=null&&value!=null)PersonalXirr.calculate(flows+PersonalCashFlow(date,value))else PersonalXirrResult(reason=reason)
        return buildJsonObject {
            put("annualized",percent(rate.rate));put("cumulativeProfit",money(total {it.cumulative}));put("realizedProfit",money(total {it.realized}))
            put("reason",rate.reason?.let(::JsonPrimitive)?:JsonNull);put("message",message(rate.reason));put("valuationDate",date?.toString()?.let(::JsonPrimitive)?:JsonNull)
        }
    }
    private fun unavailable(id:String,reason:String)=Result(id,null,null,false,emptyList(),null,null,null,null,null,reason,reason,null,null,null)
    private fun ui(r:Result)=buildJsonObject {
        put("annualized",percent(r.rate));put("realizedProfit",money(r.realized));put("cumulativeProfit",money(r.cumulative))
        val total=if(r.initial!=null&&r.invested!=null)r.initial+r.invested else null
        val simple=if(total!=null&&total.signum()>0&&r.cumulative!=null)r.cumulative.divide(total,MathContext.DECIMAL128)else null
        put("cumulativeRate",percent(simple));put("reason",(r.reason?:r.annualReason)?.let(::JsonPrimitive)?:JsonNull);put("message",message(r.reason?:r.annualReason))
        put("holdingStartDate",r.start?.toString()?.let(::JsonPrimitive)?:JsonNull);put("holdingDays",r.days?.let(::JsonPrimitive)?:JsonNull)
        put("valuationDate",r.valuationDate?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("raw",buildJsonObject {put("annualRate",r.rate?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("cumulativeProfit",r.cumulative?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("realizedProfit",r.realized?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)})
    }
    private fun decimal(o:JsonObject,key:String):BigDecimal?=o.text(key)?.let {require(it.length<=256);it.toBigDecimal().also {v->require(v.precision()<=128&&v.scale() in -64..64)}}
    private fun parsedDate(text:String?):LocalDate?=text?.let {runCatching {LocalDate.parse(it)}.getOrNull()}
    private fun money(value:BigDecimal?):String=value?.let {(if(it.signum()>0)"+" else if(it.signum()<0)"-" else "")+"¥"+it.abs().setScale(2,RoundingMode.HALF_UP).toPlainString()}?:"--"
    private fun percent(value:BigDecimal?):String=value?.let {(if(it.signum()>0)"+" else "")+it.multiply(BigDecimal(100)).setScale(2,RoundingMode.HALF_UP).toPlainString()+"%"}?:"--"
    fun message(reason:String?):String=when(reason){
        null->"";"UNDER_90_DAYS"->"统计区间未满 90 天，暂不展示年化。";"INITIAL_AMOUNT_MISMATCH"->"初始投入明细与初始成本不一致，请补齐。"
        "MISSING_INITIAL_DATE"->"请补充初始持有日期或初始投入明细。";"EXTERNAL_POSITION_CHANGED"->"养基宝持仓有本地记录无法解释的变化，收益资料暂不完整。"
        "PENDING_WRITE"->"持仓写入结果仍在核对，暂不计算本地收益。";"VALUATION_DATES_NOT_ALIGNED"->"基金估值日期未对齐，暂不展示整体年化。"
        "VALUATION_BEFORE_CASH_FLOW"->"当前估值早于最新投入或取出，等待更新。";"MULTIPLE_ROOTS"->"现金流存在多个年化解，暂不展示。"
        "COMPLEX_CASH_FLOWS"->"现金流结构复杂，暂无可靠年化结果。";"NO_RELIABLE_ROOT"->"当前现金流暂无可靠年化结果。"
        else->"收益统计资料不完整，暂不展示。"
    }
}
