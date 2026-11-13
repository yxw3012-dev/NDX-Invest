package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface BuyStorage {
    suspend fun read():List<BuyTransaction>
    /** Atomic replacement; a failed write must leave the prior complete journal intact. */
    suspend fun write(rows:List<BuyTransaction>)
}
interface BuyGateway {
    suspend fun current(fund:BuyFund):BuyBaseline
    suspend fun officialNav(fund:BuyFund,targetDate:LocalDate):BigDecimal?
    suspend fun officialHistory(fund:BuyFund):List<OfficialBuyNav> = throw BuyFailure(BuyProblem.NAV_PROTOCOL)
    /** Exact formal NAV at the earliest eligible date. Unknown gaps are not holidays. */
    suspend fun resolveOfficialNav(fund:BuyFund,startDate:LocalDate,knownClosed:(LocalDate)->Boolean):OfficialBuyNav? {
        var date=startDate
        while(knownClosed(date))date=date.plusDays(1)
        return officialNav(fund,date)?.let {OfficialBuyNav(date,it)}
    }
    /** The adapter validates the verified overwrite contract before enabling any POST. */
    fun writeAvailable():Boolean
    fun normalizeTarget(target:BuyBaseline):BuyBaseline
    fun matches(actual:BuyBaseline,target:BuyBaseline):Boolean
    suspend fun postFinal(fund:BuyFund,target:BuyBaseline)
    /** Production invokes the durable marker after local preflight, immediately before network dispatch. */
    suspend fun postFinalGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit) {beforeSend();postFinal(fund,target)}
}

/** This journal is never a Portfolio authority. YJB GET remains the holding source. */
class SingleBuyRepository(private val storage:BuyStorage,internal val gateway:BuyGateway,
    private val calendar:(String)->BuyCalendar?,private val now:()->Instant=Instant::now,
    private val id:()->String={UUID.randomUUID().toString()},private val emit:suspend(List<BuyTransaction>)->Unit={},
    private val readback:PositionReadback=PositionReadback(),private val pendingOther:suspend(BuyFund)->Boolean={false}
) {
    private val operations=Mutex()
    private suspend fun save(t:BuyTransaction):BuyTransaction {
        val rows=storage.read();val next=if(rows.any {it.id==t.id})rows.map {if(it.id==t.id)t else it}else rows+t
        storage.write(next);emit(next);return t
    }
    suspend fun restore():List<BuyTransaction> = operations.withLock {
        val rows=storage.read().map {if(it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED))it.copy(status=BuyStatus.SYNCING,problem=it.problem?:BuyProblem.WRITE_PENDING,updatedAt=now())else it}
        storage.write(rows);emit(rows);rows
    }
    suspend fun create(input:BuyInput,requestId:String?=null,originPlanId:String?=null,scheduledDate:LocalDate?=null,autoSync:Boolean=false):BuyTransaction=operations.withLock {
        createLocked(input,requestId,originPlanId,scheduledDate,null,autoSync)
    }
    /** Native scheduler only: a pre-existing plan occurrence and exact formal history, never a UI NAV input. */
    suspend fun createFromPlannedNav(input:BuyInput,requestId:String,originPlanId:String,scheduledDate:LocalDate,nav:OfficialBuyNav):BuyTransaction=operations.withLock {
        if(input.tradeDate!=scheduledDate||nav.date!=scheduledDate||input.cutoffType!=CutoffType.BEFORE_1500||nav.date>now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val t=createLocked(input,requestId,originPlanId,scheduledDate,nav)
        if(t.status==BuyStatus.WAITING_NAV)save(BuyCalculation.confirmResolved(t,nav,now()))else t
    }
    private suspend fun createLocked(input:BuyInput,requestId:String?,originPlanId:String?,scheduledDate:LocalDate?,nav:OfficialBuyNav?,autoSync:Boolean=false):BuyTransaction {
        if((originPlanId==null)!=(scheduledDate==null)||(originPlanId!=null&&!Regex("[0-9a-fA-F-]{36}").matches(originPlanId)))throw BuyFailure(BuyProblem.INPUT)
        if(requestId!=null){
            if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw BuyFailure(BuyProblem.INPUT)
            storage.read().singleOrNull {it.id==requestId}?.let {if(it.input!=input||it.originPlanId!=originPlanId||it.scheduledDate!=scheduledDate)throw BuyFailure(BuyProblem.INPUT);return it}
        }
        if(input.amount.signum()<=0||input.feeRate.signum()<0||input.feeRate>=BigDecimal.ONE||input.note.length>2000||input.amount.precision()>64||input.amount.scale() !in 0..24||input.feeRate.scale() !in 0..24)throw BuyFailure(BuyProblem.INPUT)
        if(input.tradeDate>now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())throw BuyFailure(BuyProblem.INPUT)
        val (expected,source)=if(nav!=null)nav.date to "OFFICIAL_NAV_PLAN" else BuyNavDates.forecast(input,calendar(input.fund.fundCode))
        val baseline=gateway.current(input.fund) // fresh authenticated GET, never formatted UI strings
        val time=now()
        return save(BuyTransaction(requestId?:id(),input,baseline,expected,createdAt=time,updatedAt=time,calendarSource=source,originPlanId=originPlanId,scheduledDate=scheduledDate,autoSync=autoSync))
    }
    private suspend fun find(id:String)=storage.read().singleOrNull {it.id==id}?:throw BuyFailure(BuyProblem.INPUT)
    suspend fun confirmNav(id:String):BuyTransaction=operations.withLock {
        val t=find(id);if(t.status!=BuyStatus.WAITING_NAV)return@withLock t
        try {
            val c=calendar(t.input.fund.fundCode)
            // Resolve provisional legacy drafts once coverage exists; freeze established targets.
            val target=if(t.calendarSource==BuyNavDates.PROVISIONAL_SOURCE)
                BuyNavDates.target(t.input.tradeDate,t.input.cutoffType,c) else t.expectedNavDate
            val waiting=if(target!=t.expectedNavDate||t.calendarSource==BuyNavDates.PROVISIONAL_SOURCE)
                save(t.copy(expectedNavDate=target,calendarSource=c!!.source,updatedAt=now())) else t
            val nav=gateway.officialNav(t.input.fund,target)
            if(nav==null)save(waiting.copy(updatedAt=now(),problem=null))else {
                if(target>now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())throw BuyFailure(BuyProblem.NAV_PROTOCOL)
                save(BuyCalculation.confirmResolved(waiting,OfficialBuyNav(target,nav),now()))
            }
        }catch(e:BuyFailure){save(find(id).copy(problem=e.problem,updatedAt=now()))}
    }
    suspend fun cancel(id:String):BuyTransaction=operations.withLock {
        val t=find(id)
        if(t.status !in setOf(BuyStatus.WAITING_NAV,BuyStatus.EXTERNAL_CHANGED)||t.postMayHaveBeenSent)throw BuyFailure(BuyProblem.INPUT)
        save(t.copy(status=BuyStatus.CANCELLED,updatedAt=now(),problem=null))
    }
    suspend fun edit(id:String,amount:BigDecimal,rate:BigDecimal,date:LocalDate,cutoff:CutoffType,note:String):BuyTransaction=operations.withLock {
        val t=find(id)
        if(t.status!=BuyStatus.WAITING_NAV||t.postMayHaveBeenSent||amount.signum()<=0||amount.precision()>64||amount.scale() !in 0..24||rate.signum()<0||rate>=BigDecimal.ONE||rate.scale() !in 0..24||note.length>2000||date>now().atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate())throw BuyFailure(BuyProblem.INPUT)
        val input=t.input.copy(amount=amount,feeRate=rate,tradeDate=date,cutoffType=cutoff,note=note)
        val (expected,source)=BuyNavDates.forecast(input,calendar(input.fund.fundCode))
        save(t.copy(input=input,expectedNavDate=expected,calendarSource=source,updatedAt=now(),problem=null))
    }
    /** Called only by the serialized queue after proving the complete verified predecessor chain. */
    internal suspend fun handoff(id:String,current:BuyBaseline,proof:List<String>)=operations.withLock {
        val t=find(id)
        if(t.postMayHaveBeenSent||t.status !in setOf(BuyStatus.WAITING_NAV,BuyStatus.READY_TO_SYNC))throw BuyFailure(BuyProblem.INPUT)
        require(proof.isNotEmpty()&&proof.distinct().size==proof.size&&id !in proof)
        val next=t.copy(executionBaseline=current,queuePredecessors=proof,updatedAt=now())
        save(if(t.confirmedNav!=null)BuyCalculation.rebase(next,current) else next)
    }
    suspend fun sync(id:String):BuyTransaction=operations.withLock {
        var t=find(id)
        if(t.status !in setOf(BuyStatus.READY_TO_SYNC,BuyStatus.SYNC_FAILED,BuyStatus.SYNCING,BuyStatus.EXTERNAL_CHANGED))return@withLock t
        if(t.status==BuyStatus.EXTERNAL_CHANGED&&!t.postMayHaveBeenSent)return@withLock t
        val baseline=t.executionBaseline?:t.baseline
        val target=t.target?:throw BuyFailure(BuyProblem.INPUT)
        try {
            if(!t.postMayHaveBeenSent&&(pendingOther(t.input.fund)||storage.read().any {it.id!=t.id&&it.input.fund.accountId==t.input.fund.accountId&&it.input.fund.fundId==t.input.fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}))throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
            val result=FinalPositionSync.run(t.input.fund,baseline,target,t.submittedTarget,t.postMayHaveBeenSent,gateway,
                validate={it.shares>baseline.shares&&it.totalCost>baseline.totalCost},
                markSending={wire->t=save(t.copy(status=BuyStatus.SYNCING,submittedTarget=wire,postMayHaveBeenSent=true,problem=null,writeStage=WriteStage.MAY_HAVE_SENT,writeProblem=null,writeStartedAt=now(),updatedAt=now()))},readback=readback,
                outcome={stage,error->t=save(t.copy(writeStage=stage,writeProblem=error,updatedAt=now()))})
            if(result.problem==BuyProblem.WRITE_UNVERIFIED)save(t.copy(problem=result.problem,updatedAt=now()))
            else save(t.copy(status=result.status,external=result.external,problem=result.problem,updatedAt=now(),syncedAt=if(result.status==BuyStatus.SYNCED)now()else t.syncedAt))
        }catch(e:CancellationException){throw e}
        catch(e:BuyFailure){save(t.copy(status=if(t.postMayHaveBeenSent)BuyStatus.SYNCING else BuyStatus.READY_TO_SYNC,problem=e.problem,updatedAt=now()))}
    }
    suspend fun reconcileOnly(id:String):BuyTransaction=operations.withLock {
        val t=find(id);if(!t.postMayHaveBeenSent||t.status in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED))return@withLock t
        val target=t.submittedTarget?:throw BuyFailure(BuyProblem.STORAGE)
        val result=readback.verify(t.input.fund,target,gateway)
        save(t.copy(status=result.status,problem=result.problem,external=result.external,updatedAt=now(),syncedAt=if(result.status==BuyStatus.SYNCED)now()else t.syncedAt))
    }

    suspend fun reviewRecovery(id:String):WriteRecoveryQuote?=operations.withLock {
        val t=find(id)
        if(!WriteDiagnostics.needsReview(t.postMayHaveBeenSent,t.status,t.writeStartedAt,t.recoveryAttempts,t.createdAt,now()))throw BuyFailure(BuyProblem.INPUT)
        val target=t.submittedTarget?:throw BuyFailure(BuyProblem.STORAGE)
        val baseline=t.executionBaseline?:t.baseline
        val actual=gateway.current(t.input.fund)
        if(gateway.matches(actual,target)){save(t.copy(status=BuyStatus.SYNCED,problem=null,external=null,syncedAt=now(),updatedAt=now()));return@withLock null}
        if(!baseline.equivalent(actual)){save(t.copy(status=BuyStatus.EXTERNAL_CHANGED,problem=BuyProblem.EXTERNAL_CHANGED,external=actual,updatedAt=now()));return@withLock null}
        if(!(target.shares>baseline.shares&&target.totalCost>baseline.totalCost))throw BuyFailure(BuyProblem.INPUT)
        WriteRecoveryQuote(t.id,t.input.fund,baseline,target,t.recoveryAttempts.size)
    }
    /** Requires a quote reviewed in the native confirmation dialog; never used by automation. */
    suspend fun recoverConfirmed(quote:WriteRecoveryQuote):BuyTransaction=operations.withLock {
        var t=find(quote.id)
        if(!WriteDiagnostics.needsReview(t.postMayHaveBeenSent,t.status,t.writeStartedAt,t.recoveryAttempts,t.createdAt,now())||
            t.input.fund!=quote.fund||t.recoveryAttempts.size!=quote.attempts||t.submittedTarget?.equivalent(quote.target)!=true||
            !(t.executionBaseline?:t.baseline).equivalent(quote.baseline))throw BuyFailure(BuyProblem.INPUT)
        if(pendingOther(t.input.fund)||storage.read().any {it.id!=t.id&&it.input.fund.uiId==t.input.fund.uiId&&it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)})throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
        val result=FinalPositionSync.recover(t.input.fund,quote.baseline,quote.target,gateway,
            markSending={wire->t=save(t.copy(status=BuyStatus.SYNCING,postMayHaveBeenSent=true,submittedTarget=wire,
                writeStage=WriteStage.MAY_HAVE_SENT,writeProblem=null,recoveryAttempts=t.recoveryAttempts+now(),problem=null,updatedAt=now()))},readback=readback,
            outcome={stage,error->t=save(t.copy(writeStage=if(stage==WriteStage.NOT_SENT)t.writeStage else stage,writeProblem=error,updatedAt=now()))})
        save(t.copy(status=result.status,problem=result.problem,external=result.external,updatedAt=now(),syncedAt=if(result.status==BuyStatus.SYNCED)now()else t.syncedAt))
    }
}
