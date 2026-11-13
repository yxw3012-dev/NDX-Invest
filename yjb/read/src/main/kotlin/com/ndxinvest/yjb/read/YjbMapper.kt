package com.ndxinvest.yjb.read

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.*

/** Pure projection of platform values. Never computes market value or holding profit. */
object YjbMapper {
    fun map(snapshot: JsonObject): JsonObject {
        val quotes=(snapshot["quotes"] as? JsonArray).orEmpty().associate { it.jsonObject.text("fund_id") to it.jsonObject }
        val holdings=mutableListOf<JsonObject>();val details=mutableListOf<JsonObject>();val catalog=mutableListOf<JsonObject>()
        val totals=mutableListOf<Triple<BigDecimal?,BigDecimal?,BigDecimal?>>()
        for(group in snapshot["groups"]!!.jsonArray) for(item in group.jsonObject["funds"]!!.jsonArray) {
            val h=item.jsonObject;val fundId=h.text("fund_id")?:throw YjbFailure(ReadError.PROTOCOL)
            val q=quotes[fundId]?:JsonObject(emptyMap())
            val shares=decimal(h,"hold_share")
            if(shares!=null && shares.signum()<0)throw YjbFailure(ReadError.PROTOCOL)
            val unitCost=decimal(h,"hold_cost")
            val explicitCost=decimal(h,"holding_cost")
            // Research evidence confirms hold_cost is per-unit cost, not a total cost.
            val cost=explicitCost ?: if(shares!=null&&unitCost!=null) shares.multiply(unitCost) else null
            val value=decimal(h,"money");val profit=decimal(h,"hold_earn")
            val rate=if(cost!=null&&cost.signum()>0&&profit!=null)profit.multiply(BigDecimal(100)).divide(cost,4,RoundingMode.HALF_UP) else null
            val nav=decimal(q,"last_net")?:decimal(h,"last_net")
            val navDate=q.text("net_day")?:h.text("net_day")
            val id="yjb:${group.jsonObject.text("accountId")}:$fundId"
            val code=q.text("code")?:h.text("code")?:"--"
            val name=q.text("short_name")?:h.text("short_name")?:"基金 $fundId"
            val meta=group.jsonObject.text("accountTitle")?:"养基宝基金"
            val row=buildJsonObject {
                put("id",id);put("fundId",fundId);put("fundCode",code);put("name",name);put("meta",meta)
                put("amount",money(value));put("profit",money(profit,true));put("autoInvest",false)
                put("availableSharesText",plain(shares));put("sellPresets",buildJsonObject {
                    for((key,ratio) in listOf(".25" to "0.25",".5" to "0.5","1" to "1"))put(key,shares?.multiply(BigDecimal(ratio))?.toPlainString()?:"")
                })
            }
            catalog+=row
            if(shares!=null&&shares.signum()==0)continue // catalog retains existing closed funds; current holdings do not
            totals+=Triple(cost,value,profit);holdings+=row
            details+=buildJsonObject {
                put("id",id);put("amount",money(value));put("cost",money(cost));put("shares",plain(shares));put("avgNav",plain(unitCost))
                put("autoInvest","未设置");put("profit",money(profit,true));put("profitRate",rate?.let { signed(it)+"%" }?:"--")
                put("annualized","--");put("realizedProfit","--");put("cumulativeProfit","--")
                put("nav",plain(nav));put("navDelta","--");put("navDate",navDate?:"--");put("navStatus",if(nav==null)"暂无正式净值" else if(snapshot.text("displayName")=="本地持仓")"天天基金 · 正式净值" else "养基宝 · 正式净值")
                put("officialNav",nav?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("officialNavTime",navDate?.let(::JsonPrimitive)?:JsonNull)
                put("estimatedNav",JsonNull);put("estimatedNavTime",JsonNull) // unverified estimate fields are never invented
            }
        }
        fun total(position:Int): BigDecimal? {
            val values=totals.map { when(position){0->it.first;1->it.second;else->it.third} }
            return if(values.any { it==null })null else values.fold(BigDecimal.ZERO){a,v->a+v!!}
        }
        val totalCost=total(0);val totalValue=total(1);val totalProfit=total(2)
        val rate=if(totalCost!=null&&totalCost.signum()>0&&totalProfit!=null)totalProfit.multiply(BigDecimal(100)).divide(totalCost,4,RoundingMode.HALF_UP)else null
        return buildJsonObject {
            put("fundCatalog",JsonArray(catalog));put("holdings",JsonArray(holdings));put("details",JsonArray(details));put("overview",buildJsonObject {
                put("amount",money(totalValue));put("metrics",JsonArray(listOf(money(totalCost),money(totalProfit,true),rate?.let { signed(it)+"%" }?:"--","--").map(::JsonPrimitive)))
            });put("displayName",snapshot["displayName"]!!);put("maskedId",snapshot["maskedId"]!!);put("lastSyncAt",snapshot["lastSyncAt"]!!)
        }
    }
    private fun decimal(o:JsonObject,key:String):BigDecimal? {
        val value=o.text(key)?:return null
        if(value.length>256)throw YjbFailure(ReadError.PROTOCOL)
        val n=value.toBigDecimalOrNull()?:throw YjbFailure(ReadError.PROTOCOL)
        if(n.precision()>128||n.scale() !in -64..64)throw YjbFailure(ReadError.PROTOCOL)
        return n
    }
    private fun plain(v:BigDecimal?)=v?.stripTrailingZeros()?.toPlainString()?:"--"
    private fun signed(v:BigDecimal)=(if(v.signum()>0)"+" else "")+plain(v)
    private fun money(v:BigDecimal?,sign:Boolean=false)=v?.let { (if(sign&&it.signum()>0)"+" else if(it.signum()<0)"-" else "")+"¥"+plain(it.abs()) }?:"--"
}
