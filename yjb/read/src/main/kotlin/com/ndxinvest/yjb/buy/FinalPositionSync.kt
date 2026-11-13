package com.ndxinvest.yjb.buy

import kotlinx.coroutines.CancellationException

/** Durable sending marker belongs at the transport boundary, after all non-writing preflight. */
object FinalPositionSync {
    data class Result(val status:BuyStatus,val problem:BuyProblem?=null,val external:BuyBaseline?=null)
    suspend fun run(fund:BuyFund,baseline:BuyBaseline,target:BuyBaseline,submitted:BuyBaseline?,mayHaveSent:Boolean,
        gateway:BuyGateway,validate:(BuyBaseline)->Boolean,markSending:suspend(BuyBaseline)->Unit,
        readback:PositionReadback=PositionReadback(),outcome:suspend(WriteStage,BuyProblem?)->Unit={_,_->}):Result {
        if(mayHaveSent)return readback.verify(fund,submitted?:throw BuyFailure(BuyProblem.STORAGE),gateway)
        val current=gateway.current(fund)
        if(!baseline.equivalent(current))return Result(BuyStatus.EXTERNAL_CHANGED,BuyProblem.EXTERNAL_CHANGED,current)
        if(!gateway.writeAvailable())return Result(BuyStatus.READY_TO_SYNC,BuyProblem.WRITE_UNVERIFIED)
        val wireTarget=gateway.normalizeTarget(target)
        if(!validate(wireTarget))throw BuyFailure(BuyProblem.INPUT)
        return dispatch(fund,wireTarget,gateway,markSending,readback,outcome,false)
    }
    /** Never called by automatic sync. Caller requires fresh, explicit native confirmation of this frozen target. */
    internal suspend fun recover(fund:BuyFund,baseline:BuyBaseline,frozen:BuyBaseline,gateway:BuyGateway,
        markSending:suspend(BuyBaseline)->Unit,readback:PositionReadback,
        outcome:suspend(WriteStage,BuyProblem?)->Unit):Result {
        val actual=gateway.current(fund)
        if(gateway.matches(actual,frozen))return Result(BuyStatus.SYNCED)
        if(!baseline.equivalent(actual))return Result(BuyStatus.EXTERNAL_CHANGED,BuyProblem.EXTERNAL_CHANGED,actual)
        if(!gateway.writeAvailable()||!gateway.normalizeTarget(frozen).equivalent(frozen))return Result(BuyStatus.SYNCING,BuyProblem.WRITE_UNVERIFIED)
        return dispatch(fund,frozen,gateway,markSending,readback,outcome,true)
    }
    private suspend fun dispatch(fund:BuyFund,target:BuyBaseline,gateway:BuyGateway,
        markSending:suspend(BuyBaseline)->Unit,readback:PositionReadback,
        outcome:suspend(WriteStage,BuyProblem?)->Unit,recovery:Boolean):Result {
        var marked=false;var marking=false
        try {
            gateway.postFinalGuarded(fund,target) {
                if(marked)throw BuyFailure(BuyProblem.STORAGE)
                marking=true;markSending(target);marked=true
            }
        }catch(e:CancellationException){throw e}
        catch(e:BuyFailure){
            if(marking&&!marked)throw e
            outcome(if(marked)WriteStage.MAY_HAVE_SENT else WriteStage.NOT_SENT,e.problem)
            if(!marked)return Result(if(recovery)BuyStatus.SYNCING else BuyStatus.READY_TO_SYNC,e.problem)
            return readback.verify(fund,target,gateway)
        }
        if(!marked)throw BuyFailure(BuyProblem.STORAGE)
        outcome(WriteStage.ACKNOWLEDGED,null)
        return readback.verify(fund,target,gateway)
    }
}
