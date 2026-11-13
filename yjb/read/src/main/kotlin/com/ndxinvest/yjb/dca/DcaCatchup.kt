package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.*

enum class CatchupDecision { PENDING, APPROVED, SKIPPED, SUPERSEDED }
data class DcaCatchupSlot(val planId:String,val date:LocalDate,val amount:BigDecimal,val feeRate:BigDecimal,
    val nav:BigDecimal,val versionRevision:Long) {override fun toString()="DcaCatchupSlot[REDACTED]"}
data class DcaCatchupReview(val id:String,val fund:BuyFund,val slots:List<DcaCatchupSlot>,val baseline:BuyBaseline,
    val createdAt:Instant,val decision:CatchupDecision=CatchupDecision.PENDING,val decidedAt:Instant?=null) {
    override fun toString()="DcaCatchupReview[$decision,REDACTED]"
}

object DcaCatchup {
    const val PERIOD_LIMIT=3
    const val SILENCE_DAYS=7L
    const val MAX_REVIEW_SLOTS=50
    fun same(a:DcaCatchupSlot,b:DcaCatchupSlot)=a.planId==b.planId&&a.date==b.date&&a.versionRevision==b.versionRevision&&
        a.amount.compareTo(b.amount)==0&&a.feeRate.compareTo(b.feeRate)==0&&a.nav.compareTo(b.nav)==0
    fun needsReview(slots:List<DcaCatchupSlot>,plans:List<DcaPlan>,now:Instant):Boolean = slots.size>=PERIOD_LIMIT||
        slots.any {s->plans.single {it.id==s.planId}.let {p->Duration.between(p.lastCheckedAt?:p.createdAt,now)>=Duration.ofDays(SILENCE_DAYS)}}
    fun id(fund:BuyFund,slots:List<DcaCatchupSlot>,baseline:BuyBaseline):String {
        fun BigDecimal.key()=stripTrailingZeros().toPlainString()
        val text=listOf("ndx-catchup-v1",fund.accountId,fund.fundId,baseline.shares.key(),baseline.unitCost.key()).joinToString("|")+"|"+
            slots.joinToString(";"){"${it.planId}|${it.date}|${it.amount.key()}|${it.feeRate.key()}|${it.nav.key()}|${it.versionRevision}"}
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
