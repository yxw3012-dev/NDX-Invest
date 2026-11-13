package com.ndxinvest.yjb.buy

import com.ndxinvest.yjb.read.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate

/** Separate capability: production MUST supply confirmed overwrite/precision evidence first. */
interface VerifiedYjbWrite {
    val evidence:String
    fun normalize(target:BuyBaseline):BuyBaseline
    fun matches(actual:BuyBaseline,target:BuyBaseline):Boolean
    suspend fun post(fund:BuyFund,target:BuyBaseline)
    suspend fun postGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit) {beforeSend();post(fund,target)}
}
class YjbBuyGateway(private val api:YjbReadApi,private val token:suspend()->CharArray?,private val writer:VerifiedYjbWrite?=null,private val navCache:FormalNavCache?=null,private val allowAbsent:suspend(BuyFund)->Boolean={false}):BuyGateway {
    private suspend fun call(path:String):JsonElement {
        val session=token()?:throw BuyFailure(BuyProblem.AUTH_EXPIRED)
        try{return api.call(path,session)}catch(e:YjbFailure){throw BuyFailure(when(e.kind){ReadError.AUTH_EXPIRED->BuyProblem.AUTH_EXPIRED;ReadError.STORAGE->BuyProblem.STORAGE;ReadError.PROTOCOL->BuyProblem.NAV_PROTOCOL;ReadError.RATE_LIMITED->BuyProblem.RATE_LIMITED;else->BuyProblem.NETWORK})}finally{session.fill('\u0000')}
    }
    override suspend fun current(fund:BuyFund):BuyBaseline {
        if(!Regex("[0-9]+").matches(fund.accountId)||!Regex("[0-9]+").matches(fund.fundId))throw BuyFailure(BuyProblem.INPUT)
        val accounts=call("/users/v1/user-account").jsonObject["list"] as? JsonArray?:throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
        if(accounts.none {it.jsonObject["id"]?.jsonPrimitive?.content==fund.accountId})throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
        val holdings=call("/position/v1/static/fund-accounts/${fund.accountId}/funds").jsonObject["list"] as? JsonArray?:throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val matching=holdings.filter {it.jsonObject["fund_id"]?.jsonPrimitive?.content==fund.fundId}
        if(matching.isEmpty()&&allowAbsent(fund))return BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false)
        val h=matching.singleOrNull()?.jsonObject?:throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
        try{return BuyBaseline(decimal(h["hold_share"]),decimal(h["hold_cost"]))}catch(_:Exception){throw BuyFailure(BuyProblem.NAV_PROTOCOL)}
    }
    private suspend fun history(fund:BuyFund):Map<LocalDate,BigDecimal> {
        if(!Regex("[0-9]+").matches(fund.fundId))throw BuyFailure(BuyProblem.INPUT)
        return try {navCache?.history(fund.fundId){fetchHistory(fund)}?:fetchHistory(fund)}
        catch(_:YjbFailure){throw BuyFailure(BuyProblem.STORAGE)}
    }
    private suspend fun fetchHistory(fund:BuyFund):Map<LocalDate,BigDecimal> {
        val data=call("/market/v1/fund-nav/fund-history-nav?fund_id=${fund.fundId}").jsonObject
        val list=data["list"] as? JsonArray?:throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val dated=try {list.map {it.jsonObject}.map {LocalDate.parse(it["day"]!!.jsonPrimitive.content) to decimal(it["unit_net"])}}catch(_:Exception){throw BuyFailure(BuyProblem.NAV_PROTOCOL)}
        if(dated.map {it.first}.distinct().size!=dated.size||dated.any {it.second.signum()<=0})throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        return dated.toMap()
    }
    override suspend fun officialHistory(fund:BuyFund)=history(fund).map {OfficialBuyNav(it.key,it.value)}.sortedBy {it.date}
    override suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal? {
        val dated=history(fund)
        if(targetDate !in dated){
            if(dated.isNotEmpty()&&targetDate<dated.keys.min())throw BuyFailure(BuyProblem.NAV_OUTSIDE_WINDOW)
            return null
        }
        return dated.getValue(targetDate)
    }
    override suspend fun resolveOfficialNav(fund:BuyFund,startDate:LocalDate,knownClosed:(LocalDate)->Boolean):OfficialBuyNav? {
        val dated=history(fund)
        var date=startDate
        // Prefer actual formal history, even over a calendar prediction. Never infer closure from a gap.
        while(date !in dated&&knownClosed(date))date=date.plusDays(1)
        dated[date]?.let {return OfficialBuyNav(date,it)}
        if(dated.isNotEmpty()&&date<dated.keys.min())throw BuyFailure(BuyProblem.NAV_OUTSIDE_WINDOW)
        return null // missing target is pending, even when a later NAV exists
    }
    private fun decimal(e:JsonElement?):BigDecimal {
        val text=e?.jsonPrimitive?.content?:throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        if(text.length>256)throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val n=text.toBigDecimalOrNull()?:throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        if(n.precision()>128||n.scale() !in -64..64)throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        return n
    }
    override fun writeAvailable()=writer?.evidence?.isNotBlank()==true
    override fun normalizeTarget(target:BuyBaseline)=writer?.normalize(target)?:throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
    override fun matches(actual:BuyBaseline,target:BuyBaseline)=writer?.matches(actual,target)?:actual.equivalent(target)
    override suspend fun postFinalGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit) {
        val w=writer?:throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
        if(w.evidence.isBlank())throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
        w.postGuarded(fund,target,beforeSend)
    }
    override suspend fun postFinal(fund:BuyFund,target:BuyBaseline) {val w=writer?:throw BuyFailure(BuyProblem.WRITE_UNVERIFIED);if(w.evidence.isBlank())throw BuyFailure(BuyProblem.WRITE_UNVERIFIED);w.post(fund,target)}
}
