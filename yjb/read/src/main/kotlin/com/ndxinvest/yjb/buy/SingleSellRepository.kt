package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SellStorage {
    suspend fun read():List<SellTransaction>
    suspend fun write(rows:List<SellTransaction>)
}

/** Local SELL events only. Current holdings always come from fresh YJB GET, never BUY/SELL history. */
class SingleSellRepository(private val storage:SellStorage,private val gateway:BuyGateway,
    private val calendar:(String)->BuyCalendar?=BundledBuyCalendar::forCode,private val now:()->Instant=Instant::now,
    private val id:()->String={UUID.randomUUID().toString()},private val emit:suspend(List<SellTransaction>)->Unit={},
    private val readback:PositionReadback=PositionReadback(),private val pendingOther:suspend(BuyFund)->Boolean={false}) {
    private val operations=Mutex()
    private fun validate(input:SellInput) {
        if(input.shares.signum()<=0||input.shares.precision()>64||input.shares.scale() !in 0..24||
            input.feeRate.signum()<0||input.feeRate>=BigDecimal.ONE||input.feeRate.scale() !in 0..24||
            input.note.length>2000||input.tradeDate>now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())throw BuyFailure(BuyProblem.INPUT)
    }
    private suspend fun save(t:SellTransaction):SellTransaction {
        val rows=storage.read();val next=if(rows.any {it.id==t.id})rows.map {if(it.id==t.id)t else it}else rows+t
        storage.write(next);emit(next);return t
    }
    private suspend fun find(id:String)=storage.read().singleOrNull {it.id==id}?:throw BuyFailure(BuyProblem.INPUT)
    suspend fun restore():List<SellTransaction> = operations.withLock {
        val rows=storage.read().map {if(it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED))it.copy(status=SellStatus.SYNCING,problem=it.problem?:BuyProblem.WRITE_PENDING,updatedAt=now())else it}
        storage.write(rows);emit(rows);rows
    }
    suspend fun create(input:SellInput,requestId:String?=null,autoSync:Boolean=false):SellTransaction = operations.withLock {
        if(requestId!=null) {
            if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw BuyFailure(BuyProblem.INPUT)
            storage.read().singleOrNull {it.id==requestId}?.let {if(it.input!=input)throw BuyFailure(BuyProblem.INPUT);return@withLock it}
        }
        validate(input)
        val baseline=gateway.current(input.fund)
        if(input.shares>baseline.shares)throw BuyFailure(BuyProblem.INPUT)
        val (expected,source)=BuyNavDates.forecast(input.tradeDate,input.cutoffType,calendar(input.fund.fundCode));val time=now()
        save(SellTransaction(requestId?:id(),input,baseline,expected,createdAt=time,updatedAt=time,calendarSource=source,autoSync=autoSync))
    }
    suspend fun confirmNav(id:String):SellTransaction = operations.withLock {
        val t=find(id);if(t.status!=SellStatus.WAITING_NAV)return@withLock t
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
                save(SellCalculation.confirm(waiting,OfficialBuyNav(target,nav),now()))
            }
        }catch(e:BuyFailure){save(find(id).copy(problem=e.problem,updatedAt=now()))}
    }
    suspend fun edit(id:String,shares:BigDecimal,rate:BigDecimal,date:LocalDate,cutoff:CutoffType,note:String):SellTransaction = operations.withLock {
        val t=find(id);if(t.status!=SellStatus.WAITING_NAV||t.postMayHaveBeenSent)throw BuyFailure(BuyProblem.INPUT)
        val input=t.input.copy(shares=shares,feeRate=rate,tradeDate=date,cutoffType=cutoff,note=note);validate(input)
        if(shares>t.baseline.shares)throw BuyFailure(BuyProblem.INPUT)
        val (expected,source)=BuyNavDates.forecast(date,cutoff,calendar(input.fund.fundCode))
        save(t.copy(input=input,expectedNavDate=expected,calendarSource=source,updatedAt=now(),problem=null))
    }
    suspend fun cancel(id:String):SellTransaction = operations.withLock {
        val t=find(id);if(t.status !in setOf(SellStatus.WAITING_NAV,SellStatus.EXTERNAL_CHANGED)||t.postMayHaveBeenSent)throw BuyFailure(BuyProblem.INPUT)
        save(t.copy(status=SellStatus.CANCELLED,updatedAt=now(),problem=null))
    }
    /** Called only by the serialized queue after proving the complete verified predecessor chain. */
    internal suspend fun handoff(id:String,current:BuyBaseline,proof:List<String>)=operations.withLock {
        val t=find(id)
        if(t.postMayHaveBeenSent||t.status !in setOf(BuyStatus.WAITING_NAV,BuyStatus.READY_TO_SYNC))throw BuyFailure(BuyProblem.INPUT)
        require(proof.isNotEmpty()&&proof.distinct().size==proof.size&&id !in proof)
        val next=t.copy(executionBaseline=current,queuePredecessors=proof,updatedAt=now())
        save(if(t.confirmedNav!=null)SellCalculation.confirm(next,OfficialBuyNav(t.expectedNavDate,t.confirmedNav),now()) else next)
    }
    suspend fun sync(id:String):SellTransaction = operations.withLock {
        var t=find(id);if(t.status !in setOf(SellStatus.READY_TO_SYNC,SellStatus.SYNC_FAILED,SellStatus.SYNCING,SellStatus.EXTERNAL_CHANGED))return@withLock t
        if(t.status==SellStatus.EXTERNAL_CHANGED&&!t.postMayHaveBeenSent)return@withLock t
        val baseline=t.executionBaseline?:t.baseline
        val target=t.target?:throw BuyFailure(BuyProblem.INPUT)
        try {
            if(!t.postMayHaveBeenSent&&(pendingOther(t.input.fund)||storage.read().any {it.id!=t.id&&it.input.fund.accountId==t.input.fund.accountId&&it.input.fund.fundId==t.input.fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)}))throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
            val result=FinalPositionSync.run(t.input.fund,baseline,target,t.submittedTarget,t.postMayHaveBeenSent,gateway,
                validate={wire->wire.shares<baseline.shares&&wire.unitCost.compareTo(baseline.unitCost)==0&&
                    (if(target.shares.signum()==0)wire.shares.signum()==0 else wire.shares.signum()>0)},
                markSending={wire->t=save(t.copy(status=SellStatus.SYNCING,submittedTarget=wire,postMayHaveBeenSent=true,problem=null,writeStage=WriteStage.MAY_HAVE_SENT,writeProblem=null,writeStartedAt=now(),updatedAt=now()))},readback=readback,
                outcome={stage,error->t=save(t.copy(writeStage=stage,writeProblem=error,updatedAt=now()))})
            if(result.problem==BuyProblem.WRITE_UNVERIFIED)save(t.copy(problem=result.problem,updatedAt=now()))
            else save(t.copy(status=result.status,external=result.external,problem=result.problem,updatedAt=now(),syncedAt=if(result.status==SellStatus.SYNCED)now()else t.syncedAt))
        }catch(e:CancellationException){throw e}
        catch(e:BuyFailure){save(t.copy(status=if(t.postMayHaveBeenSent)SellStatus.SYNCING else SellStatus.READY_TO_SYNC,problem=e.problem,updatedAt=now()))}
    }
    suspend fun reconcileOnly(id:String):SellTransaction=operations.withLock {
        val t=find(id);if(!t.postMayHaveBeenSent||t.status in setOf(SellStatus.SYNCED,SellStatus.CANCELLED))return@withLock t
        val target=t.submittedTarget?:throw BuyFailure(BuyProblem.STORAGE)
        val result=readback.verify(t.input.fund,target,gateway)
        save(t.copy(status=result.status,problem=result.problem,external=result.external,updatedAt=now(),syncedAt=if(result.status==SellStatus.SYNCED)now()else t.syncedAt))
    }

    suspend fun reviewRecovery(id:String):WriteRecoveryQuote?=operations.withLock {
        val t=find(id)
        if(!WriteDiagnostics.needsReview(t.postMayHaveBeenSent,t.status,t.writeStartedAt,t.recoveryAttempts,t.createdAt,now()))throw BuyFailure(BuyProblem.INPUT)
        val target=t.submittedTarget?:throw BuyFailure(BuyProblem.STORAGE)
        val baseline=t.executionBaseline?:t.baseline
        val actual=gateway.current(t.input.fund)
        if(gateway.matches(actual,target)){save(t.copy(status=SellStatus.SYNCED,problem=null,external=null,syncedAt=now(),updatedAt=now()));return@withLock null}
        if(!baseline.equivalent(actual)){save(t.copy(status=SellStatus.EXTERNAL_CHANGED,problem=BuyProblem.EXTERNAL_CHANGED,external=actual,updatedAt=now()));return@withLock null}
        if(!(target.shares<baseline.shares&&target.unitCost.compareTo(baseline.unitCost)==0))throw BuyFailure(BuyProblem.INPUT)
        WriteRecoveryQuote(t.id,t.input.fund,baseline,target,t.recoveryAttempts.size)
    }
    /** Requires a quote reviewed in the native confirmation dialog; never used by automation. */
    suspend fun recoverConfirmed(quote:WriteRecoveryQuote):SellTransaction=operations.withLock {
        var t=find(quote.id)
        if(!WriteDiagnostics.needsReview(t.postMayHaveBeenSent,t.status,t.writeStartedAt,t.recoveryAttempts,t.createdAt,now())||
            t.input.fund!=quote.fund||t.recoveryAttempts.size!=quote.attempts||t.submittedTarget?.equivalent(quote.target)!=true||
            !(t.executionBaseline?:t.baseline).equivalent(quote.baseline))throw BuyFailure(BuyProblem.INPUT)
        if(pendingOther(t.input.fund)||storage.read().any {it.id!=t.id&&it.input.fund.uiId==t.input.fund.uiId&&it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)})throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
        val result=FinalPositionSync.recover(t.input.fund,quote.baseline,quote.target,gateway,
            markSending={wire->t=save(t.copy(status=SellStatus.SYNCING,postMayHaveBeenSent=true,submittedTarget=wire,
                writeStage=WriteStage.MAY_HAVE_SENT,writeProblem=null,recoveryAttempts=t.recoveryAttempts+now(),problem=null,updatedAt=now()))},readback=readback,
            outcome={stage,error->t=save(t.copy(writeStage=if(stage==WriteStage.NOT_SENT)t.writeStage else stage,writeProblem=error,updatedAt=now()))})
        save(t.copy(status=result.status,problem=result.problem,external=result.external,updatedAt=now(),syncedAt=if(result.status==SellStatus.SYNCED)now()else t.syncedAt))
    }
}
