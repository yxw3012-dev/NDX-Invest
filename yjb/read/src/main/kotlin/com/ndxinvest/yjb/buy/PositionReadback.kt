package com.ndxinvest.yjb.buy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Bounded GET-only reconciliation. A cached baseline/third value cannot prove POST non-delivery. */
class PositionReadback(private val pause:suspend(Long)->Unit={delay(it)}) {
    suspend fun verify(fund:BuyFund,target:BuyBaseline,gateway:BuyGateway):FinalPositionSync.Result {
        var problem=BuyProblem.WRITE_PENDING
        var observed:BuyBaseline?=null
        for(wait in listOf(0L,1_000L,3_000L)) {
            if(wait>0)pause(wait)
            try {
                val actual=gateway.current(fund)
                if(gateway.matches(actual,target))return FinalPositionSync.Result(BuyStatus.SYNCED)
                observed=actual;problem=BuyProblem.WRITE_PENDING
            }catch(e:CancellationException){throw e}
            catch(e:BuyFailure){
                problem=if(e.problem==BuyProblem.NETWORK)BuyProblem.WRITE_PENDING else e.problem
                if(e.problem in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.RATE_LIMITED,BuyProblem.STORAGE))break
            }
        }
        return FinalPositionSync.Result(BuyStatus.SYNCING,problem,observed)
    }
}
