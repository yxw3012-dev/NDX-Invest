package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.*

interface UndoStorage {suspend fun read():JsonArray;suspend fun write(rows:JsonArray)}
class UndoFailure(val reason:String):Exception(reason)
data class UndoQuote(val id:String,val fund:BuyFund,val before:BuyBaseline?,val restore:BuyBaseline?,val pendingBuys:List<BuyTransaction> = emptyList())
/** All calls serialized by NativeTransactionGate. This journal never authorizes automatic POST. */
class TransactionUndo(private val buys:BuyStorage,private val sells:SellStorage,private val journal:UndoStorage,
    private val gateway:BuyGateway,private val now:()->Instant=Instant::now,private val readback:PositionReadback=PositionReadback()) {
    private data class Row(val id:String,val fund:BuyFund,val status:BuyStatus,val sent:Boolean,val baseline:BuyBaseline,val target:BuyBaseline?,val synced:Instant?)
    private suspend fun rows()=buys.read().map {Row(it.id,it.input.fund,it.status,it.postMayHaveBeenSent,it.executionBaseline?:it.baseline,it.submittedTarget,it.syncedAt)}+
        sells.read().map {Row(it.id,it.input.fund,it.status,it.postMayHaveBeenSent,it.executionBaseline?:it.baseline,it.submittedTarget,it.syncedAt)}
    private suspend fun row(id:String,accounts:Set<String>)=rows().singleOrNull {it.id==id&&it.fund.accountId in accounts}?:throw UndoFailure("记录不属于当前账户")
    private suspend fun saved(id:String)=journal.read().map {it.jsonObject}.singleOrNull {it["id"]?.jsonPrimitive?.content==id}
    private fun phase(o:JsonObject)=o["phase"]!!.jsonPrimitive.content
    private fun holding(o:JsonObject)=BuyBaseline(o["shares"]!!.jsonPrimitive.content.toBigDecimal(),o["unitCost"]!!.jsonPrimitive.content.toBigDecimal())
    private fun json(b:BuyBaseline)=buildJsonObject {put("shares",b.shares.toPlainString());put("unitCost",b.unitCost.toPlainString())}
    private suspend fun save(o:JsonObject){val all=journal.read();journal.write(JsonArray(all.filter {it.jsonObject["id"]!=o["id"]}+o))}
    suspend fun blocked(fund:BuyFund):Boolean {
        val all=rows()
        return journal.read().any {e->val o=e.jsonObject;o["fund"]?.jsonPrimitive?.content==fund.uiId&&
            (phase(o)!="DONE"||all.singleOrNull {it.id==o["id"]?.jsonPrimitive?.content}?.status!=BuyStatus.CANCELLED)}
    }
    suspend fun deletedIds(accounts:Set<String>):List<String> {
        val cancelled=rows().filter {it.status==BuyStatus.CANCELLED}.map {it.id}.toSet()
        return journal.read().map {it.jsonObject}.filter {phase(it)=="DONE"&&it["account"]?.jsonPrimitive?.content in accounts&&it["id"]?.jsonPrimitive?.content in cancelled}.map {it["id"]!!.jsonPrimitive.content}
    }
    private suspend fun cancel(id:String){
        val b=buys.read();if(b.any {it.id==id})buys.write(b.map {if(it.id==id)it.copy(status=BuyStatus.CANCELLED,problem=null,updatedAt=now())else it})
        else {val s=sells.read();if(s.none {it.id==id})throw UndoFailure("原记录丢失，停止撤销");sells.write(s.map {if(it.id==id)it.copy(status=BuyStatus.CANCELLED,problem=null,updatedAt=now())else it})}
    }
    private suspend fun retained(t:Row):List<BuyTransaction> {
        val candidates=buys.read().filter {it.id!=t.id&&it.input.fund.uiId==t.fund.uiId&&it.status!=BuyStatus.CANCELLED&&it.status!=BuyStatus.SYNCED}
        val target=t.target?:throw UndoFailure("原写入目标缺失")
        val safe=candidates.filter { !it.postMayHaveBeenSent&&it.status in setOf(BuyStatus.WAITING_NAV,BuyStatus.READY_TO_SYNC,BuyStatus.SYNC_FAILED)&&
            t.synced!=null&&it.createdAt>=t.synced&&gateway.matches(it.executionBaseline?:it.baseline,target)}
        val safeIds=safe.map {it.id}.toSet()
        if(rows().any {it.id!=t.id&&it.fund.uiId==t.fund.uiId&&it.status!=BuyStatus.CANCELLED&&it.id !in safeIds&&
            (it.status!=BuyStatus.SYNCED||it.synced==null||t.synced==null||it.synced>=t.synced)})throw UndoFailure("存在已发送、已完成的后续交易或无法安全衔接的记录，停止回退")
        return safe
    }
    suspend fun quote(accounts:Set<String>,id:String):UndoQuote {
        val t=row(id,accounts)
        if(saved(id)?.let {phase(it)=="SENDING"}==true)throw UndoFailure("撤销写入结果未定，只能重新核对，不能再次提交")
        if(t.status==BuyStatus.SYNCING)throw UndoFailure("原交易同步结果尚未确定，请先核对原交易")
        if(!t.sent||t.status==BuyStatus.CANCELLED)return UndoQuote(id,t.fund,null,null)
        if(t.status!=BuyStatus.SYNCED)throw UndoFailure("原交易同步结果尚未确定，请先核对原交易")
        val pending=retained(t)
        val before=t.target?:throw UndoFailure("原写入目标缺失，不能回退")
        if(!gateway.matches(gateway.current(t.fund),before))throw UndoFailure("养基宝当前持仓与交易完成时不同，停止回退")
        if(!gateway.writeAvailable())throw UndoFailure("写入协议当前不可用")
        val restore=gateway.normalizeTarget(BuyBaseline(t.baseline.shares,t.baseline.unitCost))
        return UndoQuote(id,t.fund,before,restore,pending)
    }
    suspend fun confirm(accounts:Set<String>,q:UndoQuote):Boolean {
        val fresh=quote(accounts,q.id)
        if(fresh!=q)throw UndoFailure("持仓或交易状态已变化，请重新核对")
        var record=saved(q.id)?:buildJsonObject {put("id",q.id);put("account",q.fund.accountId);put("fund",q.fund.uiId);put("createdAt",now().toString());put("phase","PREPARED");put("pendingBuys",BuyCodec.encode(q.pendingBuys));q.before?.let {put("before",json(it))};q.restore?.let {put("restore",json(it))}}
        if(phase(record)=="DONE"){cancel(q.id);return true}
        if(q.restore==null){save(JsonObject(record+mapOf("phase" to JsonPrimitive("DONE"))));cancel(q.id);return true}
        if(holding(record["restore"]!!.jsonObject)!=q.restore||holding(record["before"]!!.jsonObject)!=q.before)throw UndoFailure("撤销目标已冻结，禁止更换")
        if(BuyCodec.decode(record["pendingBuys"]?.jsonArray?:JsonArray(emptyList()))!=q.pendingBuys)throw UndoFailure("待保留买入已变化，未发送回退请求")
        save(record)
        try {
            if(!gateway.matches(gateway.current(q.fund),q.before!!))throw UndoFailure("发送前持仓已变化，未写入")
            gateway.postFinalGuarded(q.fund,q.restore){
                record=JsonObject(record+mapOf("phase" to JsonPrimitive("SENDING"),"sentAt" to JsonPrimitive(now().toString())))
                save(record)
            }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){if(phase(record)!="SENDING")throw e}
        return reconcile(accounts,q.id)
    }
    suspend fun reconcile(accounts:Set<String>,id:String):Boolean {
        val t=row(id,accounts);val record=saved(id)?:return false
        if(phase(record)=="DONE"){cancel(id);return true}
        if(phase(record)=="VERIFIED"){finish(record);return true}
        if(phase(record)!="SENDING")return false
        val target=holding(record["restore"]!!.jsonObject)
        if(readback.verify(t.fund,target,gateway).status!=BuyStatus.SYNCED)return false
        val verified=JsonObject(record+mapOf("phase" to JsonPrimitive("VERIFIED"),"verifiedAt" to JsonPrimitive(now().toString())))
        save(verified);finish(verified);return true
    }
    private suspend fun finish(record:JsonObject) {
        val id=record["id"]!!.jsonPrimitive.content
        val target=holding(record["restore"]!!.jsonObject)
        val at=Instant.parse(record["verifiedAt"]!!.jsonPrimitive.content)
        val original=BuyCodec.decode(record["pendingBuys"]?.jsonArray?:JsonArray(emptyList()))
        val rewritten=original.associate {old->
            val updated=old.copy(executionBaseline=target,queuePredecessors=old.queuePredecessors+("undo:"+id),updatedAt=at)
            old.id to if(old.confirmedShares!=null)BuyCalculation.rebase(updated,target)else updated
        }
        val all=buys.read()
        for(old in original){val live=all.singleOrNull {it.id==old.id}?:throw UndoFailure("待保留记录丢失，已停止后续写入")
            if(live!=old&&live!=rewritten[old.id])throw UndoFailure("待保留记录已变化，保留撤销凭证并停止后续写入")}
        buys.write(all.map {row->rewritten[row.id]?:if(row.id==id)row.copy(status=BuyStatus.CANCELLED,problem=null,updatedAt=at)else row})
        if(all.none {it.id==id})cancel(id)
        save(JsonObject(record+mapOf("phase" to JsonPrimitive("DONE"))))
    }

}
