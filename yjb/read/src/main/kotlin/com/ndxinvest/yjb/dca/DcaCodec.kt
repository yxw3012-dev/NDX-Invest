package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.time.*
import kotlinx.serialization.json.*

object DcaCodec {
    private fun JsonObject.s(k:String)=get(k)!!.jsonPrimitive.content
    private fun fund(f:BuyFund)=buildJsonObject {put("accountId",f.accountId);put("fundId",f.fundId);put("fundCode",f.fundCode);put("fundName",f.fundName)}
    private fun parseFund(o:JsonObject)=BuyFund(o.s("accountId"),o.s("fundId"),o.s("fundCode"),o.s("fundName"))
    private fun config(c:DcaConfig)=buildJsonObject {put("amount",c.amount.toPlainString());put("feeRate",c.feeRate.toPlainString());put("frequency",c.frequency.name);put("weekday",c.weekday);put("monthDay",c.monthDay);put("note",c.note)}
    private fun parseConfig(p:JsonObject)=DcaConfig(p.s("amount").toBigDecimal(),p.s("feeRate").toBigDecimal(),DcaFrequency.valueOf(p.s("frequency")),p.s("weekday").toInt(),p.s("monthDay").toInt(),p.s("note"))
    fun encode(s:DcaState)=JsonArray(listOf(buildJsonObject {
        put("schemaVersion",1)
        put("plans",JsonArray(s.plans.map {p->buildJsonObject {
            put("id",p.id);put("fund",fund(p.fund));put("amount",p.config.amount.toPlainString());put("feeRate",p.config.feeRate.toPlainString())
            put("frequency",p.config.frequency.name);put("weekday",p.config.weekday);put("monthDay",p.config.monthDay);put("note",p.config.note)
            put("firstDate",p.firstDate.toString());put("nextDate",p.nextDate.toString());put("enabled",p.enabled);put("deleted",p.deleted);put("revision",p.revision)
            put("createdAt",p.createdAt.toString());put("updatedAt",p.updatedAt.toString())
            put("history",JsonArray(p.history.map {v->buildJsonObject {put("effectiveAt",v.effectiveAt.toString());put("config",config(v.config));put("enabled",v.enabled);put("firstDate",v.firstDate.toString());put("revision",v.revision)}}))
            p.lastCheckedAt?.let {put("lastCheckedAt",it.toString())};p.automationProblem?.let {put("automationProblem",it.name)}
            p.lastObservedNavDate?.let {put("lastObservedNavDate",it.toString())}
        }}))
        put("occurrences",JsonArray(s.occurrences.map {o->buildJsonObject {
            put("planId",o.planId);put("scheduledDate",o.scheduledDate.toString());put("transactionId",o.transactionId);put("status",o.status.name);put("requestedAt",o.requestedAt.toString())
            put("fund",fund(o.input.fund));put("amount",o.input.amount.toPlainString());put("feeRate",o.input.feeRate.toPlainString());put("tradeDate",o.input.tradeDate.toString());put("cutoff",o.input.cutoffType.name);put("note",o.input.note)
            o.officialNav?.let {put("officialNav",buildJsonObject {put("date",it.date.toString());put("value",it.value.toPlainString())})}
        }}))
        put("catchupReviews",JsonArray(s.catchupReviews.map {r->buildJsonObject {
            put("id",r.id);put("fund",fund(r.fund));put("createdAt",r.createdAt.toString());put("decision",r.decision.name);r.decidedAt?.let {put("decidedAt",it.toString())}
            put("baseline",buildJsonObject {put("shares",r.baseline.shares.toPlainString());put("unitCost",r.baseline.unitCost.toPlainString())})
            put("slots",JsonArray(r.slots.map {v->buildJsonObject {put("planId",v.planId);put("date",v.date.toString());put("amount",v.amount.toPlainString());put("feeRate",v.feeRate.toPlainString());put("nav",v.nav.toPlainString());put("versionRevision",v.versionRevision)}}))
        }}))
    }))
    fun decode(rows:JsonArray):DcaState {
      return try {
        if(rows.isEmpty())return DcaState()
        require(rows.size==1);val o=rows.single().jsonObject;require(o.s("schemaVersion")=="1")
        DcaState(o["plans"]!!.jsonArray.map {r->val p=r.jsonObject
            DcaPlan(p.s("id"),parseFund(p["fund"]!!.jsonObject),parseConfig(p),LocalDate.parse(p.s("firstDate")),LocalDate.parse(p.s("nextDate")),p.s("enabled").toBooleanStrict(),p.s("deleted").toBooleanStrict(),p.s("revision").toLong(),Instant.parse(p.s("createdAt")),Instant.parse(p.s("updatedAt")),
                p["history"]?.jsonArray?.map {row->val v=row.jsonObject;DcaVersion(Instant.parse(v.s("effectiveAt")),parseConfig(v["config"]!!.jsonObject),v.s("enabled").toBooleanStrict(),LocalDate.parse(v.s("firstDate")),v.s("revision").toLong())}.orEmpty(),p["lastCheckedAt"]?.jsonPrimitive?.content?.let(Instant::parse),p["automationProblem"]?.jsonPrimitive?.content?.let(BuyProblem::valueOf),p["lastObservedNavDate"]?.jsonPrimitive?.content?.let(LocalDate::parse))
        },o["occurrences"]!!.jsonArray.map {r->val p=r.jsonObject
            DcaOccurrence(p.s("planId"),LocalDate.parse(p.s("scheduledDate")),p.s("transactionId"),BuyInput(parseFund(p["fund"]!!.jsonObject),p.s("amount").toBigDecimal(),p.s("feeRate").toBigDecimal(),LocalDate.parse(p.s("tradeDate")),CutoffType.valueOf(p.s("cutoff")),p.s("note")),OccurrenceStatus.valueOf(p.s("status")),Instant.parse(p.s("requestedAt")),p["officialNav"]?.jsonObject?.let {OfficialBuyNav(LocalDate.parse(it.s("date")),it.s("value").toBigDecimal())})
        },o["catchupReviews"]?.jsonArray?.map {row->val r=row.jsonObject;val b=r["baseline"]!!.jsonObject
            DcaCatchupReview(r.s("id"),parseFund(r["fund"]!!.jsonObject),r["slots"]!!.jsonArray.map {entry->val v=entry.jsonObject
                DcaCatchupSlot(v.s("planId"),LocalDate.parse(v.s("date")),v.s("amount").toBigDecimal(),v.s("feeRate").toBigDecimal(),v.s("nav").toBigDecimal(),v.s("versionRevision").toLong())
            },BuyBaseline(b.s("shares").toBigDecimal(),b.s("unitCost").toBigDecimal()),Instant.parse(r.s("createdAt")),CatchupDecision.valueOf(r.s("decision")),r["decidedAt"]?.jsonPrimitive?.content?.let(Instant::parse))
        }.orEmpty()).also {s->
            require(s.plans.map {it.id}.distinct().size==s.plans.size)
            require(s.occurrences.map {it.planId to it.scheduledDate}.distinct().size==s.occurrences.size)
            require(s.occurrences.map {it.transactionId}.distinct().size==s.occurrences.size)
            require(s.occurrences.all {o->s.plans.any {it.id==o.planId&&it.fund==o.input.fund}})
            require(s.plans.all {p->p.history.zipWithNext().all {(a,b)->a.effectiveAt<=b.effectiveAt&&a.revision<b.revision}&&p.history.all {it.revision<=p.revision&&it.effectiveAt>=p.createdAt&&valid(it.config)}})
            require(s.plans.all {valid(it.config)})
            require(s.occurrences.all {o->o.officialNav?.let {it.date==o.scheduledDate&&o.input.tradeDate==it.date&&o.input.cutoffType==CutoffType.BEFORE_1500&&it.value.signum()>0&&it.value.precision()<=128&&it.value.scale() in -64..64}!=false})
            require(s.catchupReviews.map {it.id}.distinct().size==s.catchupReviews.size)
            require(s.catchupReviews.filter {it.decision==CatchupDecision.PENDING}.map {it.fund.accountId to it.fund.fundId}.distinct().size==s.catchupReviews.count {it.decision==CatchupDecision.PENDING})
            require(s.catchupReviews.all {r->Regex("[0-9a-fA-F-]{36}").matches(r.id)&&r.slots.size in 1..DcaCatchup.MAX_REVIEW_SLOTS&&r.slots.map {it.planId to it.date}.distinct().size==r.slots.size&&
                r.slots.all {v->v.amount.signum()>0&&v.amount.precision()<=64&&v.amount.scale() in 0..24&&v.feeRate.signum()>=0&&v.feeRate<java.math.BigDecimal.ONE&&v.nav.signum()>0&&v.nav.precision()<=128&&v.nav.scale() in -64..64&&v.versionRevision>0&&s.plans.any {it.id==v.planId&&it.fund.accountId==r.fund.accountId&&it.fund.fundId==r.fund.fundId}}&&
                r.baseline.shares.precision()<=128&&r.baseline.unitCost.precision()<=128&&(r.decision==CatchupDecision.PENDING)==(r.decidedAt==null)})
        }
      }catch(_:Exception){throw DcaFailure(DcaProblem.STORAGE)}
    }
    fun ui(s:DcaState,accounts:Set<String>,now:Instant)=JsonArray(s.plans.filter {!it.deleted&&it.fund.accountId in accounts}.map {p->buildJsonObject {
        val today=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val pending=s.occurrences.any {it.planId==p.id&&it.scheduledDate==p.nextDate&&it.status==OccurrenceStatus.RESERVED}
        val display=DcaDateDisplay.next(p,s,today)
        put("id",p.id);put("fundId",p.fund.uiId);put("amountInput",p.config.amount.toPlainString());put("frequency",p.config.frequency.name.lowercase())
        put("weekday",p.config.weekday);put("monthDay",p.config.monthDay);put("firstDate",p.firstDate.toString());put("nextDate",p.nextDate.toString())
        put("nextDateDisplay",display.first.replace('-','.'));put("nextStatusText",display.second)
        put("nextDateText",if(!p.enabled)"已暂停" else display.first+" · "+display.second)
        put("enabled",p.enabled);put("note",p.config.note);put("buyRateInput",p.config.feeRate.movePointRight(2).toPlainString());put("revision",p.revision)
        put("canExecute",false);put("pendingCreation",pending);put("executionMode","OFFICIAL_NAV_AUTO_SYNC")
        p.lastCheckedAt?.let {put("lastCheckedAt",it.toString())};p.automationProblem?.let {put("automationProblem",it.name)}
        val review=s.catchupReviews.lastOrNull {it.fund.accountId==p.fund.accountId&&it.fund.fundId==p.fund.fundId&&it.decision==CatchupDecision.PENDING}
        review?.let {r->put("catchupReview",buildJsonObject {
            put("id",r.id);put("count",r.slots.size);put("totalAmount",r.slots.fold(java.math.BigDecimal.ZERO){a,v->a+v.amount}.toPlainString())
            put("fromDate",r.slots.minOf {it.date}.toString());put("toDate",r.slots.maxOf {it.date}.toString())
            put("slots",JsonArray(r.slots.map {v->buildJsonObject {put("planId",v.planId);put("date",v.date.toString());put("amount",v.amount.toPlainString())}}))
        })}
    }})
    private fun valid(c:DcaConfig)=c.amount.signum()>0&&c.amount.precision()<=64&&c.amount.scale() in 0..24&&c.feeRate.signum()>=0&&c.feeRate<java.math.BigDecimal.ONE&&c.feeRate.scale() in 0..24&&c.note.length<=2000&&
        (c.frequency !in setOf(DcaFrequency.WEEKLY,DcaFrequency.BIWEEKLY)||c.weekday in 1..7)&&(c.frequency!=DcaFrequency.MONTHLY||c.monthDay in 1..31)
}
