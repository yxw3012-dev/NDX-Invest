package com.ndxinvest.yjb.read

import com.ndxinvest.yjb.buy.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant

interface LocalFundStorage {
    suspend fun read():JsonArray
    suspend fun write(rows:JsonArray):Boolean
}

/** Local catalogue/creation intent only. It is not a holding authority or a BUY journal. */
class FundAddition(private val store:LocalFundStorage,private val gateway:BuyGateway,private val localWrites:Boolean=false) {
    suspend fun permitsAbsent(f:BuyFund)=store.read().any {it.jsonObject["id"]?.jsonPrimitive?.content==f.uiId&&it.jsonObject["kind"]?.jsonPrimitive?.content in setOf("PLACEHOLDER","ADDING")}
    suspend fun rows()=store.read()
    suspend fun rememberNav(fundId:String,nav:OfficialBuyNav) {
        val rows=store.read();val next=JsonArray(rows.map {e->val r=e.jsonObject;if(r["fundId"]?.jsonPrimitive?.content==fundId&&r["kind"]?.jsonPrimitive?.content=="PLACEHOLDER")JsonObject(r+mapOf("officialNav" to JsonPrimitive(nav.value.toPlainString()),"officialNavDate" to JsonPrimitive(nav.date.toString())))else r})
        if(next!=rows&&!store.write(next))throw BuyFailure(BuyProblem.STORAGE)
    }
    suspend fun reconcile(data:JsonObject) {
        val ids=data["fundCatalog"]!!.jsonArray.map {it.jsonObject["id"]}.toSet()
        val rows=store.read();val next=JsonArray(rows.map {e->val r=e.jsonObject;if(r["kind"]?.jsonPrimitive?.content=="PLACEHOLDER"&&r["id"] in ids)JsonObject(r+mapOf("kind" to JsonPrimitive("RESOLVED")))else r})
        if(next!=rows&&!store.write(next))throw BuyFailure(BuyProblem.STORAGE)
    }
    private suspend fun save(row:JsonObject) {
        val rows=store.read().filterNot {it.jsonObject["id"]==row["id"]}+row
        if(!store.write(JsonArray(rows)))throw BuyFailure(BuyProblem.STORAGE)
    }
    suspend fun add(f:BuyFund,shares:BigDecimal,cost:BigDecimal):String {
        require(Regex("[0-9]{1,20}").matches(f.accountId)&&Regex("[0-9]{1,20}").matches(f.fundId)&&Regex("[0-9]{6}").matches(f.fundCode))
        require(f.fundName.length in 1..256&&shares.signum()>=0&&cost.signum()>=0&&!(shares.signum()==0&&cost.signum()>0))
        require(listOf(shares,cost).all {it.precision()<=32&&it.scale() in 0..16})
        val old=store.read().singleOrNull {it.jsonObject["id"]?.jsonPrimitive?.content==f.uiId}?.jsonObject
        if(old!=null&&old["kind"]?.jsonPrimitive?.content!="ADDING")throw BuyFailure(BuyProblem.INPUT)
        if(old!=null&&(old["shares"]!!.jsonPrimitive.content.toBigDecimal().compareTo(shares)!=0||old["unitCost"]!!.jsonPrimitive.content.toBigDecimal().compareTo(cost)!=0))throw BuyFailure(BuyProblem.INPUT)
        var row=old?:buildJsonObject {
            put("id",f.uiId);put("accountId",f.accountId);put("fundId",f.fundId);put("fundCode",f.fundCode);put("name",f.fundName)
            put("shares",shares.toPlainString());put("unitCost",cost.toPlainString());put("kind","ADDING");put("createdAt",Instant.now().toString());put("postMayHaveBeenSent",false)
        }
        // Persist authorization/intent before the live ownership and duplicate check; unknown writes stay blocked.
        save(row)
        val current=gateway.current(f)
        if(row["postMayHaveBeenSent"]?.jsonPrimitive?.booleanOrNull==true) {
            val target=BuyBaseline(row["submittedShares"]!!.jsonPrimitive.content.toBigDecimal(),row["submittedCost"]!!.jsonPrimitive.content.toBigDecimal())
            if(current.exists&&gateway.matches(current,target)){save(JsonObject(row+mapOf("kind" to JsonPrimitive("IMPORTED"))));return "VERIFIED"}
            if(localWrites&&!current.exists){
                gateway.postFinal(f,target)
                save(JsonObject(row+mapOf("kind" to JsonPrimitive("IMPORTED"))))
                return "VERIFIED"
            }
            // Even an unchanged GET does not prove that a timed-out POST will never be applied.
            throw BuyFailure(if(current.exists)BuyProblem.EXTERNAL_CHANGED else BuyProblem.WRITE_PENDING)
        }
        if(current.exists)throw BuyFailure(BuyProblem.EXTERNAL_CHANGED)
        if(shares.signum()==0){save(JsonObject(row+mapOf("kind" to JsonPrimitive("PLACEHOLDER"))));return "PLACEHOLDER"}
        if(!gateway.writeAvailable())throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
        val target=gateway.normalizeTarget(BuyBaseline(shares,cost))
        if(target.shares.signum()<=0)throw BuyFailure(BuyProblem.INPUT)
        row=JsonObject(row+mapOf("postMayHaveBeenSent" to JsonPrimitive(true),"submittedShares" to JsonPrimitive(target.shares.toPlainString()),"submittedCost" to JsonPrimitive(target.unitCost.toPlainString())))
        save(row)
        try{gateway.postFinal(f,target)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){/* GET decides; never repeat POST. */}
        val actual=gateway.current(f)
        if(!actual.exists||!gateway.matches(actual,target))throw BuyFailure(if(actual.exists)BuyProblem.EXTERNAL_CHANGED else BuyProblem.WRITE_PENDING)
        save(JsonObject(row+mapOf("kind" to JsonPrimitive("IMPORTED"))));return "VERIFIED"
    }
    suspend fun remove(id:String,pending:Boolean) {
        require(!pending)
        val rows=store.read();val row=rows.singleOrNull {it.jsonObject["id"]?.jsonPrimitive?.content==id}?.jsonObject?:throw BuyFailure(BuyProblem.INPUT)
        require(row["kind"]?.jsonPrimitive?.content=="PLACEHOLDER")
        if(!store.write(JsonArray(rows.filterNot {it==row})))throw BuyFailure(BuyProblem.STORAGE)
    }
}

object LocalFundProjection {
    /** Preserve all platform values. Local cards are excluded from platform totals and statistics. */
    fun merge(data:JsonObject,rows:JsonArray,accounts:Set<String>,navs:Map<String,OfficialBuyNav>):JsonObject {
        val catalog=data["fundCatalog"]!!.jsonArray.toMutableList();val holdings=data["holdings"]!!.jsonArray.toMutableList();val details=data["details"]!!.jsonArray.toMutableList()
        for(e in rows) {
            val r=e.jsonObject;val id=r["id"]!!.jsonPrimitive.content;val fundId=r["fundId"]!!.jsonPrimitive.content
            if(r["accountId"]!!.jsonPrimitive.content !in accounts||r["kind"]!!.jsonPrimitive.content!="PLACEHOLDER"||catalog.any {it.jsonObject["id"]==r["id"]})continue
            val row=buildJsonObject {put("id",id);put("fundId",fundId);put("fundCode",r["fundCode"]!!);put("name",r["name"]!!);put("meta","");put("amount","--");put("profit","--");put("availableSharesText","--");put("sellPresets",buildJsonObject {});put("autoInvest",false);put("isPlaceholder",true)}
            catalog+=row;holdings+=row
            details+=buildJsonObject {
                put("id",id);put("isPlaceholder",true)
                for(key in listOf("amount","cost","shares","avgNav","profit","profitRate","annualized","realizedProfit","cumulativeProfit","holdingDays","navDelta"))put(key,"--")
                put("autoInvest","未设置");val n=navs[fundId];val value=n?.value?.toPlainString()?:r["officialNav"]?.jsonPrimitive?.content;val date=n?.date?.toString()?:r["officialNavDate"]?.jsonPrimitive?.content
                put("nav",value?:"--");put("navDate",date?:"--");put("navStatus",if(value==null)"暂无正式净值" else "正式净值");put("officialNav",value?.let(::JsonPrimitive)?:JsonNull);put("officialNavTime",date?.let(::JsonPrimitive)?:JsonNull);put("estimatedNav",JsonNull);put("estimatedNavTime",JsonNull)
            }
        }
        val overview=if(data["holdings"]!!.jsonArray.isEmpty()&&holdings.isNotEmpty())buildJsonObject {put("amount","--");put("metrics",JsonArray(List(4){JsonPrimitive("--")}))}else data["overview"]!!
        return JsonObject(data+mapOf("fundCatalog" to JsonArray(catalog),"holdings" to JsonArray(holdings),"details" to JsonArray(details),"overview" to overview))
    }
}
