package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.CancellationException
import java.time.*

/** Caller serializes this engine with all native transaction/session commands.
 * Formal NAV dates are evidence, not publication dates. Every returned date is re-scanned:
 * no high-water mark may discard an older NAV that appeared late.
 */
class DcaNavEngine(private val storage:DcaStorage,private val buyStorage:BuyStorage,
    private val plans:DcaRepository,private val buys:SingleBuyRepository,private val gateway:BuyGateway,
    private val pendingSell:suspend(BuyFund)->Boolean={false},
    private val calendar:(String)->BuyCalendar?=BundledBuyCalendar::forCode,
    private val now:()->Instant=Instant::now) {
    data class Report(val synced:Int,val problems:Set<BuyProblem>)
    suspend fun run(accounts:Set<String>):Report {
        val state=plans.restore()
        val eligible=state.plans.filter {p->p.fund.accountId in accounts&&
            ((p.enabled&&!p.deleted)||state.occurrences.any {it.planId==p.id&&it.status==OccurrenceStatus.CREATED&&it.officialNav!=null})}
        var synced=0;val errors=mutableSetOf<BuyProblem>()
        for(fundPlans in eligible.groupBy {it.fund.accountId to it.fund.fundId}.values) {
            val fund=fundPlans.first().fund
            val ids=fundPlans.map {it.id}.toSet()
            try {
                val auto=state.occurrences.filter {it.planId in ids&&it.officialNav!=null}
                val owned=auto.map {it.transactionId}.toSet()
                if(pendingSell(fund)||buyStorage.read().any {it.input.fund.accountId==fund.accountId&&it.input.fund.fundId==fund.fundId&&it.id !in owned&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)})throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
                // Recover frozen reservations even if their NAV has left the current history window.
                for(o in auto.sortedBy {it.scheduledDate}) {
                    val saved=buyStorage.read().singleOrNull {it.id==o.transactionId}
                    val p=fundPlans.single {it.id==o.planId}
                    val t=if(saved?.status==BuyStatus.WAITING_NAV)buys.createFromPlannedNav(o.input,o.transactionId,o.planId,o.scheduledDate,o.officialNav!!)
                        else saved?:if(p.enabled&&!p.deleted)plans.executeNav(o.planId,o.officialNav!!)?:continue else continue
                    if(sync(t))synced++
                }
                val activePlans=fundPlans.filter {it.enabled&&!it.deleted}
                if(activePlans.isEmpty())continue
                val c=calendar(fund.fundCode)?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
                val history=gateway.officialHistory(fund).sortedBy {it.date}
                val today=now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
                if(history.map {it.date}.distinct().size!=history.size||history.any {it.value.signum()<=0||it.date>today})throw BuyFailure(BuyProblem.NAV_PROTOCOL)
                // A long offline interval may exceed the endpoint's returned history. Do not silently
                // substitute the latest NAV or invent holiday decisions for the uncovered interval.
                val oldest=history.firstOrNull()?.date
                val firstEligible=activePlans.minOf {p->maxOf(p.history.first().firstDate,p.history.first().effectiveAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())}
                val byDate=history.associateBy {it.date}
                val currentState=storage.read()
                val consumed=currentState.occurrences.map {it.planId to it.scheduledDate}.toSet()+
                    currentState.catchupReviews.filter {it.decision==CatchupDecision.SKIPPED}.flatMap {r->r.slots.map {it.planId to it.date}}
                val candidates=mutableListOf<DcaCatchupSlot>()
                var date=firstEligible
                // Stop at the first eligible unpublished NAV. Absence never means closure.
                dateLoop@ while(date<=today) {
                    if(c.isOpen(date))for(p in activePlans.sortedBy {it.id}) {
                        val v=DcaSchedule.version(p,date)
                        if(v==null||!DcaSchedule.matches(date,v)||(p.id to date) in consumed)continue
                        val nav=byDate[date]?:if(oldest!=null&&date<oldest)throw BuyFailure(BuyProblem.NAV_OUTSIDE_WINDOW)else break@dateLoop
                        candidates+=DcaCatchupSlot(p.id,date,v.config.amount,v.config.feeRate,nav.value,v.revision)
                    }
                    date=date.plusDays(1)
                }
                fun approval(slot:DcaCatchupSlot)=currentState.catchupReviews.lastOrNull {r->r.decision==CatchupDecision.APPROVED&&r.slots.any {DcaCatchup.same(it,slot)}}
                val pendingReview=currentState.catchupReviews.any {it.fund.accountId==fund.accountId&&it.fund.fundId==fund.fundId&&it.decision==CatchupDecision.PENDING}
                if(candidates.isNotEmpty()&&pendingReview) {
                    plans.requestCatchupReview(fund,candidates.take(DcaCatchup.MAX_REVIEW_SLOTS),gateway.current(fund))
                    throw BuyFailure(BuyProblem.CATCHUP_REVIEW)
                }
                for((index,slot) in candidates.withIndex()) {
                    val review=approval(slot)
                    if(review==null&&DcaCatchup.needsReview(candidates.drop(index).filter {approval(it)==null},activePlans,now())) {
                        plans.requestCatchupReview(fund,candidates.drop(index).take(DcaCatchup.MAX_REVIEW_SLOTS),gateway.current(fund))
                        throw BuyFailure(BuyProblem.CATCHUP_REVIEW)
                    }
                    if(review!=null) {
                        // Compare the last verified submitted target only; never rebuild a holding from the ledger.
                        val journal=buyStorage.read()
                        val previous=review.slots.mapNotNull {v->journal.singleOrNull {it.originPlanId==v.planId&&it.scheduledDate==v.date&&it.status==BuyStatus.SYNCED}}
                            .lastOrNull()?.submittedTarget?:review.baseline
                        val actual=gateway.current(fund)
                        if(!previous.equivalent(actual)) {
                            val remaining=candidates.filter {v->journal.none {it.originPlanId==v.planId&&it.scheduledDate==v.date}}
                            plans.requestCatchupReview(fund,remaining.take(DcaCatchup.MAX_REVIEW_SLOTS),actual)
                            throw BuyFailure(BuyProblem.CATCHUP_REVIEW)
                        }
                    }
                    val t=plans.executeNav(slot.planId,OfficialBuyNav(slot.date,slot.nav))?:continue
                    if(sync(t))synced++
                }
                plans.checked(ids,null,history.lastOrNull()?.date)
            }catch(e:CancellationException){throw e}
            catch(e:BuyFailure){errors+=e.problem;plans.checked(ids,e.problem);if(e.problem in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break}
            // Storage corruption must fail closed across the entire run, not fall through to another POST.
        }
        return Report(synced,errors)
    }
    /** Production calendar generation only. All writes are delegated to the unified trade queue. */
    suspend fun materializeDue(accounts:Set<String>):Report {
        val state=plans.restore();val errors=mutableSetOf<BuyProblem>()
        val active=state.plans.filter {it.fund.accountId in accounts&&it.enabled&&!it.deleted}
        val local=now().atZone(ZoneId.of("Asia/Shanghai"))
        val until=if(local.toLocalTime()<LocalTime.of(15,0))local.toLocalDate().minusDays(1)else local.toLocalDate()
        for(group in active.groupBy {it.fund.uiId}.values)try {
            val fund=group.first().fund;if(pendingSell(fund))throw BuyFailure(BuyProblem.PENDING_TRANSACTION);val c=calendar(fund.fundCode)?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
            val current=storage.read()
            val consumed=current.occurrences.filter {it.status==OccurrenceStatus.CREATED}.map {it.planId to it.scheduledDate}.toSet()+current.catchupReviews.filter {it.decision==CatchupDecision.SKIPPED}.flatMap {it.slots.map {v->v.planId to v.date}}
            val slots=mutableListOf<DcaCatchupSlot>()
            val first=group.minOf {maxOf(it.history.first().firstDate,it.history.first().effectiveAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())}
            var date=first
            while(date<=until) {
                if(!c.covers(date))throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
                if(c.isOpen(date))for(p in group.sortedBy {it.id}) {
                    val v=DcaSchedule.version(p,date)?:continue
                    if(DcaSchedule.matches(date,v)&&(p.id to date) !in consumed)slots+=DcaCatchupSlot(p.id,date,v.config.amount,v.config.feeRate,java.math.BigDecimal.ONE,v.revision)
                }
                date=date.plusDays(1)
            }
            val unapproved=slots.filter {slot->current.catchupReviews.none {r->r.decision==CatchupDecision.APPROVED&&r.slots.any {it.planId==slot.planId&&it.date==slot.date&&it.versionRevision==slot.versionRevision&&it.amount.compareTo(slot.amount)==0&&it.feeRate.compareTo(slot.feeRate)==0}}}
            if(unapproved.isNotEmpty()&&(current.catchupReviews.any {it.fund.uiId==fund.uiId&&it.decision==CatchupDecision.PENDING}||DcaCatchup.needsReview(unapproved,group,now()))) {
                // Existing catchup review stores actual formal NAV evidence. Do not invent NAV in its persisted slots.
                val history=gateway.officialHistory(fund)
                if(history.map {it.date}.distinct().size!=history.size||history.any {it.date>local.toLocalDate()||it.value.signum()<=0})throw BuyFailure(BuyProblem.NAV_PROTOCOL)
                val verified=unapproved.mapNotNull {slot->history.singleOrNull {it.date==slot.date}?.let {slot.copy(nav=it.value)}}
                if(verified.size!=unapproved.size)throw BuyFailure(BuyProblem.CATCHUP_REVIEW)
                plans.requestCatchupReview(fund,verified.take(DcaCatchup.MAX_REVIEW_SLOTS),gateway.current(fund))
                throw BuyFailure(BuyProblem.CATCHUP_REVIEW)
            }
            for(review in current.catchupReviews.filter {it.fund.uiId==fund.uiId&&it.decision==CatchupDecision.APPROVED&&slots.any {slot->it.slots.any {v->v.planId==slot.planId&&v.date==slot.date}}}) {
                val last=buyStorage.read().filter {t->t.status==BuyStatus.SYNCED&&review.slots.any {it.planId==t.originPlanId&&it.date==t.scheduledDate}}.maxByOrNull {it.syncedAt?:it.updatedAt}
                if(!(last?.submittedTarget?:review.baseline).equivalent(gateway.current(fund)))throw BuyFailure(BuyProblem.EXTERNAL_CHANGED)
            }
            for(slot in slots)plans.executeScheduled(slot.planId,slot.date)
            plans.checked(group.map {it.id}.toSet(),null)
        }catch(e:CancellationException){throw e}
        catch(e:BuyFailure){errors+=e.problem;plans.checked(group.map {it.id}.toSet(),e.problem);if(e.problem in setOf(BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE,BuyProblem.RATE_LIMITED))break}
        return Report(0,errors)
    }
    /** Unmaterialized eligible dates block later trades, including catchup awaiting consent. */
    suspend fun reservedBefore(fund:BuyFund,target:LocalDate):BuyProblem? {
        val state=storage.read();val today=now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        for(p in state.plans.filter {it.fund.uiId==fund.uiId&&it.enabled&&!it.deleted&&it.history.isNotEmpty()}) {
            val c=calendar(fund.fundCode)?:return BuyProblem.CALENDAR_UNAVAILABLE
            var date=maxOf(p.history.first().firstDate,p.history.first().effectiveAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
            while(date<=minOf(target,today)) {
                if(!c.covers(date))return BuyProblem.CALENDAR_UNAVAILABLE
                val v=DcaSchedule.version(p,date)
                val due=date.atTime(15,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
                val consumed=state.occurrences.any {it.planId==p.id&&it.scheduledDate==date&&it.status==OccurrenceStatus.CREATED}||state.catchupReviews.any {r->r.decision==CatchupDecision.SKIPPED&&r.slots.any {it.planId==p.id&&it.date==date}}
                if(now()>=due&&c.isOpen(date)&&v!=null&&DcaSchedule.matches(date,v)&&!consumed)return if(p.automationProblem==BuyProblem.CATCHUP_REVIEW)BuyProblem.CATCHUP_REVIEW else BuyProblem.PENDING_TRANSACTION
                date=date.plusDays(1)
            }
        }
        return null
    }
    private suspend fun sync(t:BuyTransaction):Boolean {
        if(t.status in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED))return false
        if(t.status !in setOf(BuyStatus.READY_TO_SYNC,BuyStatus.SYNC_FAILED,BuyStatus.SYNCING))throw BuyFailure(t.problem?:BuyProblem.EXTERNAL_CHANGED)
        val result=buys.sync(t.id)
        if(result.status!=BuyStatus.SYNCED)throw BuyFailure(result.problem?:if(result.status==BuyStatus.EXTERNAL_CHANGED)BuyProblem.EXTERNAL_CHANGED else BuyProblem.VERIFY_FAILED)
        return true
    }
}
