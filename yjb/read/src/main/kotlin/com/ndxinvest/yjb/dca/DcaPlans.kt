package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DcaFrequency { DAILY, WEEKLY, BIWEEKLY, MONTHLY }
enum class DcaProblem { INPUT, STALE, NOT_DUE, PAUSED, PENDING_CREATION, STORAGE }
class DcaFailure(val problem:DcaProblem):Exception("DCA_${problem.name}")
data class DcaConfig(val amount:BigDecimal,val feeRate:BigDecimal,val frequency:DcaFrequency,val weekday:Int,val monthDay:Int,val note:String="") {override fun toString()="DcaConfig[REDACTED]"}
data class DcaVersion(val effectiveAt:Instant,val config:DcaConfig,val enabled:Boolean,val firstDate:LocalDate,val revision:Long) {override fun toString()="DcaVersion[REDACTED]"}
data class DcaPlan(val id:String,val fund:BuyFund,val config:DcaConfig,val firstDate:LocalDate,val nextDate:LocalDate,
    val enabled:Boolean,val deleted:Boolean=false,val revision:Long=1,val createdAt:Instant,val updatedAt:Instant,val history:List<DcaVersion> = emptyList(),
    val lastCheckedAt:Instant?=null,val automationProblem:BuyProblem?=null,val lastObservedNavDate:LocalDate?=null) {override fun toString()="DcaPlan[REDACTED]"}
enum class OccurrenceStatus { RESERVED, CREATED }
/** Immutable request snapshot before BUY creation. Automatic occurrences use the formal NAV date;
 * older manual occurrences retain their original click-time trade date and are never taken over. */
data class DcaOccurrence(val planId:String,val scheduledDate:LocalDate,val transactionId:String,val input:BuyInput,
    val status:OccurrenceStatus=OccurrenceStatus.RESERVED,val requestedAt:Instant,val officialNav:OfficialBuyNav?=null) {override fun toString()="DcaOccurrence[$status,REDACTED]"}
data class DcaState(val plans:List<DcaPlan> = emptyList(),val occurrences:List<DcaOccurrence> = emptyList(),val catchupReviews:List<DcaCatchupReview> = emptyList()) {override fun toString()="DcaState[REDACTED]"}
interface DcaStorage {suspend fun read():DcaState;suspend fun write(state:DcaState)}

/** Date-only local plan cadence. No fund calendar, task dispatch or implicit trade is performed. */
object DcaSchedule {
    fun version(p:DcaPlan,date:LocalDate)=p.history.lastOrNull {it.effectiveAt<date.atTime(15,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()}
    fun matches(date:LocalDate,v:DcaVersion):Boolean=date>=v.firstDate&&v.enabled&&when(v.config.frequency) {
        DcaFrequency.DAILY->true
        DcaFrequency.WEEKLY->date.dayOfWeek.value==v.config.weekday
        DcaFrequency.BIWEEKLY->java.time.temporal.ChronoUnit.DAYS.between(v.firstDate,date)%14L==0L
        DcaFrequency.MONTHLY->date.dayOfMonth==minOf(v.config.monthDay,date.lengthOfMonth())
    }
    fun firstAfter(today:LocalDate,c:DcaConfig):LocalDate=when(c.frequency) {
        DcaFrequency.DAILY->today.plusDays(1)
        DcaFrequency.WEEKLY,DcaFrequency.BIWEEKLY->{val n=(c.weekday-today.dayOfWeek.value+7)%7;today.plusDays(if(n==0)7 else n.toLong())}
        DcaFrequency.MONTHLY->{val candidate=today.withDayOfMonth(minOf(c.monthDay,today.lengthOfMonth()));if(candidate>today)candidate else monthly(today.plusMonths(1),c.monthDay)}
    }
    private fun monthly(date:LocalDate,day:Int)=date.withDayOfMonth(minOf(day,date.lengthOfMonth()))
    fun after(date:LocalDate,c:DcaConfig):LocalDate=when(c.frequency) {
        DcaFrequency.DAILY->date.plusDays(1)
        DcaFrequency.WEEKLY->date.plusWeeks(1)
        DcaFrequency.BIWEEKLY->date.plusWeeks(2)
        DcaFrequency.MONTHLY->monthly(date.plusMonths(1),c.monthDay)
    }
}

/** Encrypted local plans, effective configuration history and durable occurrence reservations. */
class DcaRepository(private val storage:DcaStorage,private val buyStorage:BuyStorage,private val buys:SingleBuyRepository,
    private val now:()->Instant=Instant::now,private val emit:suspend(DcaState)->Unit={},private val rejectCollision:suspend(String)->Unit={}) {
    private val mutex=Mutex()
    private fun today()=now().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
    private fun validate(c:DcaConfig) {
        if(c.amount.signum()<=0||c.amount.precision()>64||c.amount.scale() !in 0..24||c.feeRate.signum()<0||c.feeRate>=BigDecimal.ONE||c.feeRate.scale() !in 0..24||c.note.length>2000||
            (c.frequency in setOf(DcaFrequency.WEEKLY,DcaFrequency.BIWEEKLY)&&c.weekday !in 1..7)||(c.frequency==DcaFrequency.MONTHLY&&c.monthDay !in 1..31))throw DcaFailure(DcaProblem.INPUT)
    }
    private suspend fun save(s:DcaState):DcaState {storage.write(s);emit(s);return s}
    private fun active(s:DcaState,id:String)=s.plans.singleOrNull {it.id==id&&!it.deleted}?:throw DcaFailure(DcaProblem.INPUT)
    private fun revision(p:DcaPlan,r:Long){if(p.revision!=r)throw DcaFailure(DcaProblem.STALE)}
    private fun invalidateReviews(s:DcaState,p:DcaPlan)=s.catchupReviews.map {if(it.fund.accountId==p.fund.accountId&&it.fund.fundId==p.fund.fundId&&it.decision in setOf(CatchupDecision.PENDING,CatchupDecision.APPROVED))it.copy(decision=CatchupDecision.SUPERSEDED,decidedAt=now())else it}
    private fun finish(s:DcaState,o:DcaOccurrence):DcaState {
        val p=s.plans.single {it.id==o.planId}
        val next=p.copy(nextDate=if(p.nextDate==o.scheduledDate||(o.officialNav!=null&&p.nextDate<o.scheduledDate))DcaSchedule.after(o.scheduledDate,p.config)else p.nextDate,revision=p.revision+1,updatedAt=now())
        return s.copy(plans=s.plans.map {if(it.id==p.id)next else it},occurrences=s.occurrences.map {if(it.transactionId==o.transactionId)it.copy(status=OccurrenceStatus.CREATED)else it})
    }
    private fun matches(t:BuyTransaction,o:DcaOccurrence)=t.originPlanId==o.planId&&t.scheduledDate==o.scheduledDate&&t.input.fund==o.input.fund&&
        (o.officialNav==null||(t.input==o.input&&(t.confirmedNavDate==null||t.confirmedNavDate==o.officialNav.date)&&(t.confirmedNav==null||t.confirmedNav.compareTo(o.officialNav.value)==0)))
    /** Reconcile an already-durable BUY without GET, creating another BUY or sending POST. */
    private suspend fun reconcile():DcaState {
        var s=storage.read();val journal=buyStorage.read()
        // Legacy plans have no historical configuration proof: activate automatic history only from upgrade time.
        if(s.plans.any {it.history.isEmpty()})s=save(s.copy(plans=s.plans.map {p->if(p.history.isEmpty())p.copy(history=listOf(DcaVersion(now(),p.config,p.enabled&&!p.deleted,p.firstDate,p.revision)))else p}))
        for(o in s.occurrences.filter {it.status==OccurrenceStatus.RESERVED}) {
            val t=journal.singleOrNull {it.id==o.transactionId}?:continue
            if(!matches(t,o))throw DcaFailure(DcaProblem.STORAGE)
            s=save(finish(s,o))
        }
        return s
    }
    suspend fun restore():DcaState=mutex.withLock {reconcile().also {emit(it)}}
    suspend fun create(fund:BuyFund,c:DcaConfig,enabled:Boolean,requestId:String):DcaPlan=mutex.withLock {
        validate(c);if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw DcaFailure(DcaProblem.INPUT)
        val s=reconcile();s.plans.singleOrNull {it.id==requestId}?.let {
            if(it.fund!=fund||it.config!=c||it.enabled!=enabled||it.deleted)throw DcaFailure(DcaProblem.INPUT)
            return@withLock it
        }
        val date=DcaSchedule.firstAfter(today(),c);val time=now()
        val p=DcaPlan(requestId,fund,c,date,date,enabled,createdAt=time,updatedAt=time,history=listOf(DcaVersion(time,c,enabled,date,1)))
        save(s.copy(plans=s.plans+p));p
    }
    suspend fun update(id:String,c:DcaConfig,r:Long):DcaPlan=mutex.withLock {
        validate(c);val s=reconcile();val p=active(s,id);revision(p,r)
        if(s.occurrences.any {it.planId==id&&it.status==OccurrenceStatus.RESERVED})throw DcaFailure(DcaProblem.PENDING_CREATION)
        val cadenceChanged=c.frequency!=p.config.frequency||c.weekday!=p.config.weekday||c.monthDay!=p.config.monthDay
        var date=if(cadenceChanged)DcaSchedule.firstAfter(today(),c)else p.nextDate
        // Changed cadence must never reuse an already consumed plan/date key.
        while(s.occurrences.any {it.planId==id&&it.scheduledDate==date})date=DcaSchedule.after(date,c)
        val next=p.copy(config=c,nextDate=date,revision=p.revision+1,updatedAt=now(),history=p.history+DcaVersion(now(),c,p.enabled,if(cadenceChanged)date else p.history.last().firstDate,p.revision+1));save(s.copy(plans=s.plans.map {if(it.id==id)next else it},catchupReviews=invalidateReviews(s,p)));next
    }
    suspend fun setEnabled(id:String,enabled:Boolean,r:Long):DcaPlan=mutex.withLock {
        val s=reconcile();val p=active(s,id);revision(p,r)
        val next=p.copy(enabled=enabled,revision=p.revision+1,updatedAt=now(),history=p.history+DcaVersion(now(),p.config,enabled,p.history.last().firstDate,p.revision+1));save(s.copy(plans=s.plans.map {if(it.id==id)next else it},catchupReviews=invalidateReviews(s,p)));next
    }
    suspend fun delete(id:String,r:Long)=mutex.withLock {
        val s=reconcile();val p=active(s,id);revision(p,r)
        // Tombstone retains occurrence identities and all linked BUY history.
        save(s.copy(plans=s.plans.map {if(it.id==id)it.copy(deleted=true,enabled=false,revision=it.revision+1,updatedAt=now())else it},catchupReviews=invalidateReviews(s,p)));Unit
    }
    suspend fun execute(id:String,period:LocalDate,r:Long):BuyTransaction=mutex.withLock {
        var s=reconcile();val p=active(s,id)
        val existing=s.occurrences.singleOrNull {it.planId==id&&it.scheduledDate==period}
        if(existing?.status==OccurrenceStatus.CREATED)return@withLock buyStorage.read().singleOrNull {it.id==existing.transactionId&&matches(it,existing)}?:throw DcaFailure(DcaProblem.STORAGE)
        revision(p,r);if(!p.enabled)throw DcaFailure(DcaProblem.PAUSED)
        if(period!=p.nextDate||period>today())throw DcaFailure(DcaProblem.NOT_DUE)
        val occurrence=existing?:run {
            val time=now();val local=time.atZone(ZoneId.of("Asia/Shanghai"))
            val input=BuyInput(p.fund,p.config.amount,p.config.feeRate,local.toLocalDate(),if(local.toLocalTime()<LocalTime.of(15,0))CutoffType.BEFORE_1500 else CutoffType.AFTER_1500,p.config.note)
            val txId=UUID.nameUUIDFromBytes("ndx-dca:${p.fund.accountId}:$id:$period".toByteArray(Charsets.UTF_8)).toString()
            DcaOccurrence(id,period,txId,input,requestedAt=time).also {s=save(s.copy(occurrences=s.occurrences+it))}
        }
        // No NAV confirmation or sync here. Ordinary BUY handles both through its existing actions.
        rejectCollision(occurrence.transactionId)
        val t=buys.create(occurrence.input,occurrence.transactionId,id,period)
        save(finish(s,occurrence));t
    }
    /** Calendar-confirmed native occurrence: persist its identity before creating a waiting BUY. */
    suspend fun executeScheduled(id:String,date:LocalDate):BuyTransaction?=mutex.withLock {
        var s=reconcile();val p=active(s,id)
        val existing=s.occurrences.singleOrNull {it.planId==id&&it.scheduledDate==date}
        if(existing?.status==OccurrenceStatus.CREATED)return@withLock buyStorage.read().single {it.id==existing.transactionId}
        if(!p.enabled)return@withLock null
        val c=BundledBuyCalendar.forCode(p.fund.fundCode)?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
        val due=date.atTime(15,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        if(now()<due||!c.isOpen(date))return@withLock null
        val v=DcaSchedule.version(p,date)?:return@withLock null
        if(!DcaSchedule.matches(date,v))return@withLock null
        val o=existing?:DcaOccurrence(id,date,UUID.nameUUIDFromBytes("ndx-dca:${p.fund.accountId}:$id:$date".toByteArray(Charsets.UTF_8)).toString(),
            BuyInput(p.fund,v.config.amount,v.config.feeRate,date,CutoffType.BEFORE_1500,v.config.note),requestedAt=now()).also {s=save(s.copy(occurrences=s.occurrences+it))}
        rejectCollision(o.transactionId)
        val t=buys.create(o.input,o.transactionId,id,date,autoSync=true)
        save(finish(s,o));t
    }
    /** Calendar-free, native formal-NAV processing. Missing dates are never permanently skipped. */
    suspend fun executeNav(id:String,nav:OfficialBuyNav):BuyTransaction?=mutex.withLock {
        var s=reconcile();val p=active(s,id)
        if(!p.enabled)return@withLock null
        val existing=s.occurrences.singleOrNull {it.planId==id&&it.scheduledDate==nav.date}
        if(existing!=null&&existing.officialNav==null)return@withLock null // never take over an earlier manual occurrence
        if(existing?.status==OccurrenceStatus.CREATED){
            val t=buyStorage.read().singleOrNull {it.id==existing.transactionId&&matches(it,existing)}?:throw DcaFailure(DcaProblem.STORAGE)
            return@withLock if(t.status==BuyStatus.WAITING_NAV)buys.createFromPlannedNav(existing.input,existing.transactionId,id,existing.scheduledDate,existing.officialNav!!)else t
        }
        if(nav.date>today())throw BuyFailure(BuyProblem.NAV_PROTOCOL)
        val cutoff=nav.date.atTime(15,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()
        val v=p.history.lastOrNull {it.effectiveAt<cutoff}?:return@withLock null
        if(!DcaSchedule.matches(nav.date,v))return@withLock null
        val occurrence=existing?:run {
            val txId=UUID.nameUUIDFromBytes("ndx-dca:${p.fund.accountId}:$id:${nav.date}".toByteArray(Charsets.UTF_8)).toString()
            val input=BuyInput(p.fund,v.config.amount,v.config.feeRate,nav.date,CutoffType.BEFORE_1500,v.config.note)
            DcaOccurrence(id,nav.date,txId,input,requestedAt=now(),officialNav=nav).also {s=save(s.copy(occurrences=s.occurrences+it))}
        }
        rejectCollision(occurrence.transactionId)
        val t=buys.createFromPlannedNav(occurrence.input,occurrence.transactionId,id,nav.date,occurrence.officialNav!!)
        save(finish(s,occurrence));t
    }
    suspend fun checked(ids:Set<String>,problem:BuyProblem?,observed:LocalDate?=null)=mutex.withLock {
        val s=storage.read();save(s.copy(plans=s.plans.map {if(it.id in ids)it.copy(lastCheckedAt=if(problem==null)now()else it.lastCheckedAt,automationProblem=problem,lastObservedNavDate=if(problem==null)listOfNotNull(it.lastObservedNavDate,observed).maxOrNull()else it.lastObservedNavDate)else it}));Unit
    }
    suspend fun requestCatchupReview(fund:BuyFund,slots:List<DcaCatchupSlot>,baseline:BuyBaseline)=mutex.withLock {
        require(slots.isNotEmpty()&&slots.size<=DcaCatchup.MAX_REVIEW_SLOTS)
        val s=storage.read();val digest=DcaCatchup.id(fund,slots,baseline)
        val existing=s.catchupReviews.singleOrNull {it.decision==CatchupDecision.PENDING&&DcaCatchup.id(it.fund,it.slots,it.baseline)==digest}
        if(existing!=null)return@withLock existing
        val review=DcaCatchupReview(UUID.randomUUID().toString(),fund,slots,baseline,now())
        val old=s.catchupReviews.map {if(it.fund.accountId==fund.accountId&&it.fund.fundId==fund.fundId&&it.decision in setOf(CatchupDecision.PENDING,CatchupDecision.APPROVED))it.copy(decision=CatchupDecision.SUPERSEDED,decidedAt=now())else it}
        val ids=slots.map {it.planId}.toSet()
        save(s.copy(catchupReviews=old+review,plans=s.plans.map {if(it.id in ids)it.copy(automationProblem=BuyProblem.CATCHUP_REVIEW,revision=it.revision+1,updatedAt=now())else it}));review
    }
    /** Explicit consent applies only to this immutable batch, never future periods. Skip creates no BUY. */
    suspend fun resolveCatchupReview(planId:String,revision:Long,reviewId:String,approve:Boolean,current:BuyBaseline?=null)=mutex.withLock {
        val s=reconcile();val p=active(s,planId);revision(p,revision)
        val review=s.catchupReviews.singleOrNull {it.id==reviewId}?:throw DcaFailure(DcaProblem.STALE)
        if(review.decision!=CatchupDecision.PENDING||review.fund.accountId!=p.fund.accountId||review.fund.fundId!=p.fund.fundId)throw DcaFailure(DcaProblem.STALE)
        for(slot in review.slots) {
            val plan=active(s,slot.planId);if(!plan.enabled)throw DcaFailure(DcaProblem.PAUSED)
            val version=DcaSchedule.version(plan,slot.date)?:throw DcaFailure(DcaProblem.STALE)
            if(!DcaSchedule.matches(slot.date,version)||version.revision!=slot.versionRevision||version.config.amount.compareTo(slot.amount)!=0||version.config.feeRate.compareTo(slot.feeRate)!=0||s.occurrences.any {it.planId==slot.planId&&it.scheduledDate==slot.date})throw DcaFailure(DcaProblem.STALE)
        }
        if(approve&&(current==null||!review.baseline.equivalent(current)))throw BuyFailure(BuyProblem.EXTERNAL_CHANGED)
        val next=review.copy(decision=if(approve)CatchupDecision.APPROVED else CatchupDecision.SKIPPED,decidedAt=now())
        val ids=review.slots.map {it.planId}.toSet()
        save(s.copy(catchupReviews=s.catchupReviews.map {if(it.id==reviewId)next else it},plans=s.plans.map {plan->
            if(plan.id !in ids)plan else {
                val last=review.slots.filter {it.planId==plan.id}.maxOf {it.date}
                plan.copy(nextDate=if(!approve&&plan.nextDate<=last)DcaSchedule.after(last,plan.config)else plan.nextDate,
                    revision=plan.revision+1,updatedAt=now(),lastCheckedAt=now(),automationProblem=null)
            }
        }));Unit
    }
}
