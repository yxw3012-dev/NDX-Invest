package com.ndxinvest.yjb.read

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import kotlinx.serialization.json.*

/** An observation baseline, never a BUY or a transaction-state-machine event. */
object InitialPositionStore {
    fun import(previous:JsonArray,snapshot:JsonObject,excludedUiIds:Set<String> = emptySet()):JsonArray {
        val rows=previous.toMutableList();val known=rows.map {it.jsonObject.text("fundId")}.toMutableSet()
        val quotes=snapshot["quotes"]!!.jsonArray.associate {it.jsonObject.text("fund_id") to it.jsonObject}
        for(group in snapshot["groups"]!!.jsonArray)for(value in group.jsonObject["funds"]!!.jsonArray) {
            val fund=value.jsonObject;val id=fund.text("fund_id")?:continue
            if("yjb:${group.jsonObject.text("accountId")}:$id" in excludedUiIds)continue
            if(!known.add(id))continue
            val quote=quotes[id]
            val shares=fund.text("hold_share");val unit=fund.text("hold_cost")
            val cost=fund.text("holding_cost")?:if(shares!=null&&unit!=null)BigDecimal(shares).multiply(BigDecimal(unit)).toPlainString()else null
            rows+=buildJsonObject {
                put("id","initial:YJB_IMPORT:$id");put("kind","INITIAL_POSITION");put("source","YJB_IMPORT")
                put("fundId",id);put("fundCode",quote?.text("code")?:fund.text("code")?:"--")
                put("fundName",quote?.text("short_name")?:fund.text("short_name")?:"基金 $id")
                put("importedAt",snapshot["lastSyncAt"]!!);put("initialShares",shares?.let(::JsonPrimitive)?:JsonNull)
                put("initialUnitCost",unit?.let(::JsonPrimitive)?:JsonNull);put("initialHoldingCost",cost?.let(::JsonPrimitive)?:JsonNull)
                put("initialMarketValue",fund.text("money")?.let(::JsonPrimitive)?:JsonNull)
                put("holdingStartDate",JsonNull)
            }
        }
        return JsonArray(rows)
    }
    fun edit(rows:JsonArray,id:String,date:String,today:LocalDate=LocalDate.now(ZoneId.of("Asia/Shanghai"))):JsonArray {
        val parsed=if(date.isBlank())null else try{LocalDate.parse(date)}catch(_:Exception){throw YjbFailure(ReadError.PROTOCOL)}
        if(parsed!=null&&(parsed>today||parsed.year<1900||parsed.toString()!=date))throw YjbFailure(ReadError.PROTOCOL)
        if(rows.none {it.jsonObject.text("id")==id})throw YjbFailure(ReadError.PROTOCOL)
        val imported=Instant.parse(rows.single {it.jsonObject.text("id")==id}.jsonObject.text("importedAt")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        if(parsed!=null&&parsed>imported)throw YjbFailure(ReadError.PROTOCOL)
        return JsonArray(rows.map {row->if(row.jsonObject.text("id")==id)JsonObject(row.jsonObject+mapOf("holdingStartDate" to (parsed?.toString()?.let(::JsonPrimitive)?:JsonNull)))else row})
    }
    fun holdingDays(row:JsonObject,now:Instant):Long?=row.text("holdingStartDate")?.let {ChronoUnit.DAYS.between(LocalDate.parse(it),now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())}

    /** Auxiliary inputs only: immutable observation facts and transaction history are never edited. */
    fun editContributions(rows:JsonArray,id:String,entries:JsonArray,today:LocalDate=LocalDate.now(ZoneId.of("Asia/Shanghai"))):JsonArray {
        require(entries.size<=10_000) { "Too many contributions" }
        val original=rows.singleOrNull {it.jsonObject.text("id")==id}?.jsonObject?:throw YjbFailure(ReadError.PROTOCOL)
        val imported=Instant.parse(original.text("importedAt")).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        val lastDate=minOf(imported,today)
        val clean=entries.map {entry->
            val item=entry.jsonObject
            val date=item.text("date")?:throw YjbFailure(ReadError.PROTOCOL)
            val parsed=LocalDate.parse(date)
            require(parsed.year>=1900&&parsed<=lastDate&&parsed.toString()==date)
            val text=item.text("amount")?:throw YjbFailure(ReadError.PROTOCOL)
            require(text.length<=128&&Regex("[0-9]+(?:\\.[0-9]+)?").matches(text))
            val amount=BigDecimal(text)
            require(amount.signum()>0&&amount.precision()<=128&&amount.scale()<=32)
            buildJsonObject {put("date",date);put("amount",amount.toPlainString())}
        }
        return JsonArray(rows.map {if(it.jsonObject.text("id")==id)JsonObject(it.jsonObject+mapOf("initialContributions" to JsonArray(clean)))else it})
    }
}
