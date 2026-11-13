package com.ndxinvest.yjb.buy

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Instant
import java.time.DayOfWeek

enum class BuyStatus { WAITING_NAV, READY_TO_SYNC, SYNCING, SYNCED, SYNC_FAILED, EXTERNAL_CHANGED, CANCELLED }
enum class WriteStage { NOT_SENT, MAY_HAVE_SENT, ACKNOWLEDGED }

enum class CutoffType { BEFORE_1500, AFTER_1500 }
enum class BuyProblem { INPUT, CALENDAR_UNAVAILABLE, AUTH_EXPIRED, NETWORK, STORAGE, NAV_PROTOCOL, NAV_OUTSIDE_WINDOW, NAV_DATE_UNRESOLVED, WRITE_UNVERIFIED, VERIFY_FAILED, ACCOUNT_MISMATCH, EXTERNAL_CHANGED, PENDING_TRANSACTION, RATE_LIMITED, WRITE_PENDING, CATCHUP_REVIEW }
class BuyFailure(val problem:BuyProblem):Exception(problem.name)

data class BuyFund(val accountId:String,val fundId:String,val fundCode:String,val fundName:String) {
    val uiId:String get()="yjb:$accountId:$fundId"
}
data class BuyBaseline(val shares:BigDecimal,val unitCost:BigDecimal,val exists:Boolean=true) {
    val totalCost:BigDecimal get()=shares.multiply(unitCost)
    init { require(shares.signum()>=0&&unitCost.signum()>=0) }
    fun equivalent(other:BuyBaseline)=exists==other.exists&&shares.compareTo(other.shares)==0&&unitCost.compareTo(other.unitCost)==0
}
data class BuyInput(val fund:BuyFund,val amount:BigDecimal,val feeRate:BigDecimal,val tradeDate:LocalDate,val cutoffType:CutoffType,val note:String="")
data class OfficialBuyNav(val date:LocalDate,val value:BigDecimal) {
    init {require(value.signum()>0)}
}

/** A provisional date may be stored, but is never used to auto-confirm without calendar evidence. */
object BuyNavDates {
    const val PROVISIONAL_SOURCE="PROVISIONAL_CUTOFF_WEEKEND"
    fun searchStart(input:BuyInput)=searchStart(input.tradeDate,input.cutoffType)
    fun searchStart(date:LocalDate,cutoff:CutoffType)=if(cutoff==CutoffType.AFTER_1500)date.plusDays(1)else date
    fun weekend(date:LocalDate)=date.dayOfWeek in setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)
    fun knownClosed(date:LocalDate,calendar:BuyCalendar?)=weekend(date)||calendar?.isClosed(date)==true
    fun forecast(input:BuyInput,calendar:BuyCalendar?)=forecast(input.tradeDate,input.cutoffType,calendar)
    fun target(date:LocalDate,cutoff:CutoffType,calendar:BuyCalendar?):LocalDate =
        calendar?.expected(date,cutoff)?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
    fun forecast(tradeDate:LocalDate,cutoff:CutoffType,calendar:BuyCalendar?):Pair<LocalDate,String> {
        val start=searchStart(tradeDate,cutoff)
        val verified=calendar?.let {runCatching {it.expected(tradeDate,cutoff)}.getOrNull()}
        if(verified!=null)return verified to calendar.source
        var date=start
        while(weekend(date))date=date.plusDays(1)
        return date to PROVISIONAL_SOURCE
    }
}
data class BuyTransaction(
    val id:String,val input:BuyInput,val baseline:BuyBaseline,val expectedNavDate:LocalDate,
    val status:BuyStatus=BuyStatus.WAITING_NAV,val confirmedNavDate:LocalDate?=null,val confirmedNav:BigDecimal?=null,
    val feeAmount:BigDecimal?=null,val confirmedShares:BigDecimal?=null,val target:BuyBaseline?=null,
    val postMayHaveBeenSent:Boolean=false,val external:BuyBaseline?=null,val problem:BuyProblem?=null,
    val createdAt:Instant,val updatedAt:Instant,val syncedAt:Instant?=null,
    val calendarSource:String,
    val submittedTarget:BuyBaseline?=null,
    val originPlanId:String?=null,
    val scheduledDate:LocalDate?=null,
    /** When NDX first confirmed the formal NAV, distinct from the NAV's belonging date. */
    val navConfirmedAt:Instant?=null,
    /** Explicit consent at creation. Old journals default to manual synchronization. */
    val autoSync:Boolean=false,
    /** 0: legacy calculation; 1: external-charge subscription, currency/shares HALF_UP 2. */
    val calculationVersion:Int=1,
    /** Creation snapshot remains immutable; execution baseline carries audited queue handoff. */
    val executionBaseline:BuyBaseline?=null,
    val queuePredecessors:List<String> = emptyList(),
    val writeStage:WriteStage?=null,
    val writeProblem:BuyProblem?=null,
    val writeStartedAt:Instant?=null,
    val recoveryAttempts:List<Instant> = emptyList(),

) { override fun toString()="BuyTransaction[$status,REDACTED]" }

/** Units: feeRate is a decimal fraction (0.0015 = 0.15%), never UI percentage. */
object BuyCalculation {
    // Never truncate the integer part of large values; retain at least 32 fractional digits.
    // Additional scale handles tiny subscriptions / high-precision official NAV as well.
    private fun divide(a:BigDecimal,b:BigDecimal)=a.divide(b,maxOf(32,a.scale()+b.precision()+32),RoundingMode.HALF_UP)
    fun confirm(t:BuyTransaction,date:LocalDate,nav:BigDecimal,now:Instant):BuyTransaction {
        require(date==t.expectedNavDate&&nav.signum()>0)
        return calculate(t,date,nav,now)
    }
    /** Date comes from the gateway's formal history resolver, never user input/latest NAV. */
    fun confirmResolved(t:BuyTransaction,nav:OfficialBuyNav,now:Instant):BuyTransaction {
        require(nav.date==t.expectedNavDate)
        return calculate(t,nav.date,nav.value,now)
    }
    /** Handoff changes only the holding target, never a previously confirmed fee/NAV/share result. */
    internal fun rebase(t:BuyTransaction,current:BuyBaseline):BuyTransaction {
        val shares=t.confirmedShares?:throw BuyFailure(BuyProblem.INPUT)
        val newShares=current.shares.add(shares)
        return t.copy(target=BuyBaseline(newShares,divide(current.totalCost.add(t.input.amount),newShares)))
    }
    private fun calculate(t:BuyTransaction,date:LocalDate,nav:BigDecimal,now:Instant):BuyTransaction {
        // Currency and confirmed share rounding are settlement rules, not UI formatting.
        val net=divide(t.input.amount,BigDecimal.ONE.add(t.input.feeRate)).setScale(2,RoundingMode.HALF_UP)
        val fee=if(t.input.feeRate.signum()==0)BigDecimal("0.00") else t.input.amount.subtract(net).setScale(2,RoundingMode.HALF_UP)
        val shares=net.divide(nav,2,RoundingMode.HALF_UP)
        if(shares.signum()<=0)throw BuyFailure(BuyProblem.INPUT)
        val baseline=t.executionBaseline?:t.baseline
        val newShares=baseline.shares.add(shares)
        val newTotalCost=baseline.totalCost.add(t.input.amount)
        val target=BuyBaseline(newShares,divide(newTotalCost,newShares))
        return t.copy(calculationVersion=1,status=BuyStatus.READY_TO_SYNC,confirmedNavDate=date,confirmedNav=nav,feeAmount=fee,confirmedShares=shares,target=target,updatedAt=now,problem=null,navConfirmedAt=t.navConfirmedAt?:now)
    }
}

class BuyCalendar(val fundCode:String,val coverageStart:LocalDate,val coverageEnd:LocalDate,openDates:Set<LocalDate>,val source:String,private val additionalClosedDates:Set<LocalDate> = emptySet()) {
    private val days=openDates.sorted()
    init {require(days.isNotEmpty()&&days.all {it in coverageStart..coverageEnd})}
    fun isClosed(date:LocalDate)=date in additionalClosedDates||(date in coverageStart..coverageEnd&&date !in days)
    fun covers(date:LocalDate)=date in coverageStart..coverageEnd
    fun isOpen(date:LocalDate):Boolean {if(!covers(date))throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE);return date in days}
    fun expected(date:LocalDate,cutoff:CutoffType):LocalDate {
        if(date !in coverageStart..coverageEnd)throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
        val start=if(cutoff==CutoffType.AFTER_1500)date.plusDays(1)else date
        return days.firstOrNull {it>=start}?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
    }
}

