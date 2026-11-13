package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// Shared frozen transaction statuses and final-position gateway; no second write protocol/state machine.
typealias SellStatus=BuyStatus
data class SellInput(val fund:BuyFund,val shares:BigDecimal,val feeRate:BigDecimal,val tradeDate:LocalDate,
    val cutoffType:CutoffType,val note:String="")
data class SellTransaction(val id:String,val input:SellInput,val baseline:BuyBaseline,val expectedNavDate:LocalDate,
    val status:SellStatus=SellStatus.WAITING_NAV,val confirmedNavDate:LocalDate?=null,val confirmedNav:BigDecimal?=null,
    val grossAmount:BigDecimal?=null,val feeAmount:BigDecimal?=null,val netAmount:BigDecimal?=null,
    val soldCost:BigDecimal?=null,val calculatedProfit:BigDecimal?=null,val target:BuyBaseline?=null,
    val postMayHaveBeenSent:Boolean=false,val submittedTarget:BuyBaseline?=null,val external:BuyBaseline?=null,
    val problem:BuyProblem?=null,val createdAt:Instant,val updatedAt:Instant,val syncedAt:Instant?=null,
    val calendarSource:String,
    val navConfirmedAt:Instant?=null,
    val autoSync:Boolean=false,
    /** Creation snapshot remains immutable; execution baseline carries audited queue handoff. */
    val executionBaseline:BuyBaseline?=null,
    val queuePredecessors:List<String> = emptyList(),
    val writeStage:WriteStage?=null,
    val writeProblem:BuyProblem?=null,
    val writeStartedAt:Instant?=null,
    val recoveryAttempts:List<Instant> = emptyList(),

) {override fun toString()="SellTransaction[$status,REDACTED]"}

/** User-selected average unit cost policy. All products/subtractions are exact BigDecimal. */
object SellCalculation {
    fun confirm(t:SellTransaction,nav:OfficialBuyNav,now:Instant):SellTransaction {
        require(nav.date==t.expectedNavDate)
        val baseline=t.executionBaseline?:t.baseline
        if(t.input.shares.signum()<=0||t.input.shares>baseline.shares)throw BuyFailure(BuyProblem.INPUT)
        val gross=t.input.shares.multiply(nav.value)
        val fee=gross.multiply(t.input.feeRate)
        val net=gross.subtract(fee)
        val soldCost=t.input.shares.multiply(baseline.unitCost)
        // Zero shares retains the fund/cost metadata. No deletion or fabricated old cash history.
        val target=BuyBaseline(baseline.shares.subtract(t.input.shares),baseline.unitCost)
        return t.copy(status=SellStatus.READY_TO_SYNC,confirmedNavDate=nav.date,confirmedNav=nav.value,
            grossAmount=gross,feeAmount=fee,netAmount=net,soldCost=soldCost,calculatedProfit=net.subtract(soldCost),
            target=target,updatedAt=now,problem=null,navConfirmedAt=t.navConfirmedAt?:now)
    }
}
