package com.ndxinvest.yjb.buy

import java.time.*
import kotlinx.coroutines.CancellationException

/** Serialized per account/fund queue. Holdings remain authoritative authenticated GET values. */
class OrdinaryTradeAutomation(private val buyStorage:BuyStorage,private val sellStorage:SellStorage,
    private val buys:SingleBuyRepository,private val sells:SingleSellRepository,
    private val reservedBefore:suspend(BuyFund,LocalDate)->BuyProblem?={_,_->null},
    private val undoBlocked:suspend(BuyFund)->Boolean={false}) {
    data class Report(val synced:Int,val problems:Set<BuyProblem>)
    private data class Entry(val id:String,val fund:BuyFund,val date:LocalDate,val created:Instant,
        val status:BuyStatus,val automatic:Boolean,val sent:Boolean,val baseline:BuyBaseline,
        val target:BuyBaseline?,val syncedAt:Instant?,val problem:BuyProblem?,val buy:Boolean,val proof:List<String>)
    private val order=compareBy<Entry>({it.date},{it.created},{it.id})
    private suspend fun entries()=buyStorage.read().map {Entry(it.id,it.input.fund,it.expectedNavDate,it.createdAt,it.status,it.autoSync||it.originPlanId!=null,it.postMayHaveBeenSent,it.executionBaseline?:it.baseline,it.submittedTarget,it.syncedAt,it.problem,true,it.queuePredecessors)}+
        sellStorage.read().map {Entry(it.id,it.input.fund,it.expectedNavDate,it.createdAt,it.status,it.autoSync,it.postMayHaveBeenSent,it.executionBaseline?:it.baseline,it.submittedTarget,it.syncedAt,it.problem,false,it.queuePredecessors)}
    private fun same(a:BuyFund,b:BuyFund)=a.accountId==b.accountId&&a.fundId==b.fundId
    suspend fun run(accounts:Set<String>,onlyId:String?=null):Report {
        var synced=0;val errors=mutableSetOf<BuyProblem>();val blocked=mutableSetOf<String>()
        val candidates=entries().filter {it.fund.accountId in accounts&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}.sortedWith(compareByDescending<Entry> {it.sent}.then(order))
        for(candidate in candidates) {
            val fund=candidate.fund
            if(fund.uiId in blocked)continue
            if(undoBlocked(fund)){errors+=BuyProblem.PENDING_TRANSACTION;blocked+=fund.uiId;continue}
            try {
                val all=entries();val t=all.single {it.id==candidate.id}
                if(t.sent) {
                    if(onlyId!=null&&onlyId!=t.id){blocked+=fund.uiId;continue}
                    val result=if(t.buy)buys.reconcileOnly(t.id).let {it.status to it.problem}else sells.reconcileOnly(t.id).let {it.status to it.problem}
                    if(result.first==BuyStatus.SYNCED)synced++ else {errors+=result.second?:BuyProblem.WRITE_PENDING;blocked+=fund.uiId}
                    if(result.second in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break
                    continue
                }
                val prior=all.filter {same(it.fund,fund)&&it.id!=t.id&&order.compare(it,t)<0&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}
                val inFlight=all.filter {same(it.fund,fund)&&it.id!=t.id&&it.sent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}
                if(prior.isNotEmpty()||inFlight.isNotEmpty()) {
                    (prior+inFlight).mapNotNull {it.problem}.filterNot {it in normalWaits}.forEach {errors+=it}
                    blocked+=fund.uiId;continue
                }
                val reserved=reservedBefore(fund,t.date)
                if(reserved!=null){if(reserved !in normalWaits)errors+=reserved;blocked+=fund.uiId;continue}
                if(!t.automatic&&onlyId!=t.id){blocked+=fund.uiId;continue}
                if(onlyId!=null&&onlyId!=t.id){blocked+=fund.uiId;continue}
                if(t.status==BuyStatus.EXTERNAL_CHANGED){errors+=BuyProblem.EXTERNAL_CHANGED;blocked+=fund.uiId;continue}
                if(t.status==BuyStatus.SYNC_FAILED&&onlyId!=t.id){errors+=t.problem?:BuyProblem.VERIFY_FAILED;blocked+=fund.uiId;continue}
                // Prove every completed NDX transition since this execution baseline was captured.
                // A merely matching historical target is insufficient: require dated, contiguous GET-verified writes.
                val actual=buys.gateway.current(fund)
                if(!t.baseline.equivalent(actual)) {
                    val chain=all.filter {same(it.fund,fund)&&it.id!=t.id&&it.id !in t.proof&&order.compare(it,t)<0&&it.status==BuyStatus.SYNCED&&it.syncedAt!=null&&it.syncedAt>=t.created&&it.sent&&it.target!=null}
                        .sortedWith(compareBy<Entry>({it.syncedAt}).then(order))
                    var cursor=t.baseline;val proof=mutableListOf<String>()
                    for(e in chain){if(!e.baseline.equivalent(cursor)){proof.clear();break};cursor=e.target!!;proof+=e.id}
                    if(proof.isNotEmpty()&&cursor.equivalent(actual)) {
                        if(t.buy)buys.handoff(t.id,actual,t.proof+proof)else sells.handoff(t.id,actual,t.proof+proof)
                    }
                    // If proof fails, existing repository conflict handling persists EXTERNAL_CHANGED.
                }
                val ready=if(t.buy)buys.confirmNav(t.id).let {it.status to it.problem}else sells.confirmNav(t.id).let {it.status to it.problem}
                if(ready.second!=null){errors+=ready.second!!;blocked+=fund.uiId;if(ready.second in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break;continue}
                if(ready.first!=BuyStatus.READY_TO_SYNC&&!(onlyId==t.id&&ready.first==BuyStatus.SYNC_FAILED)){blocked+=fund.uiId;continue}
                val result=if(t.buy)buys.sync(t.id).let {it.status to it.problem}else sells.sync(t.id).let {it.status to it.problem}
                if(result.first==BuyStatus.SYNCED)synced++ else {errors+=result.second?:if(result.first==BuyStatus.EXTERNAL_CHANGED)BuyProblem.EXTERNAL_CHANGED else BuyProblem.VERIFY_FAILED;blocked+=fund.uiId}
                if(result.second in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break
            }catch(e:CancellationException){throw e}
            catch(e:BuyFailure){errors+=e.problem;blocked+=fund.uiId;if(e.problem in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break}
        }
        return Report(synced,errors-normalWaits)
    }
    private suspend fun recoveryEntry(accounts:Set<String>,id:String):Entry {
        val all=entries();val t=all.singleOrNull {it.id==id&&it.fund.accountId in accounts}?:throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
        if(undoBlocked(t.fund))throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
        if(!t.sent||t.status in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED))throw BuyFailure(BuyProblem.INPUT)
        if(all.any {it.id!=id&&same(it.fund,t.fund)&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)&&(it.sent||order.compare(it,t)<0)})throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
        reservedBefore(t.fund,t.date)?.let {throw BuyFailure(it)}
        return t
    }
    suspend fun reviewRecovery(accounts:Set<String>,id:String):WriteRecoveryQuote? {
        val t=recoveryEntry(accounts,id)
        return if(t.buy)buys.reviewRecovery(id)else sells.reviewRecovery(id)
    }
    suspend fun recoverConfirmed(accounts:Set<String>,quote:WriteRecoveryQuote) {
        val t=recoveryEntry(accounts,quote.id)
        if(t.buy)buys.recoverConfirmed(quote)else sells.recoverConfirmed(quote)
    }
    companion object {val normalWaits=setOf(BuyProblem.NAV_DATE_UNRESOLVED,BuyProblem.WRITE_PENDING,BuyProblem.PENDING_TRANSACTION)}
}
