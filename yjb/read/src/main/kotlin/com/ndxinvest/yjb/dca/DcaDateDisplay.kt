package com.ndxinvest.yjb.dca

import com.ndxinvest.yjb.buy.*
import java.time.*

/** Read-only presentation; never advances the execution cursor or invents an occurrence. */
internal object DcaDateDisplay {
    fun next(p:DcaPlan,s:DcaState,today:LocalDate):Pair<String,String> {
        if(!p.enabled)return "--" to "已暂停"
        val c=BundledBuyCalendar.forCode(p.fund.fundCode)?:return "--" to "日期待核验"
        if(p.history.isEmpty())return "--" to "日期待核验"
        val consumed=s.occurrences.filter {it.planId==p.id&&it.status==OccurrenceStatus.CREATED}.map {it.scheduledDate}.toSet()+s.catchupReviews.filter {it.decision==CatchupDecision.SKIPPED}.flatMap {r->r.slots.filter {it.planId==p.id}.map {it.date}}
        var date=maxOf(p.history.first().firstDate,p.history.first().effectiveAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate())
        while(date<=today) {
            if(!c.covers(date))return "--" to "日期待核验"
            val v=DcaSchedule.version(p,date)
            if(c.isOpen(date)&&v!=null&&DcaSchedule.matches(date,v)&&date !in consumed)
                return date.toString() to if(p.automationProblem==null)"待执行" else "待处理"
            date=date.plusDays(1)
        }
        date=p.nextDate
        repeat(800) {
            if(!c.covers(date))return "--" to "日期待核验"
            if(date>today&&c.isOpen(date)&&date !in consumed)return date.toString() to "待执行"
            date=DcaSchedule.after(date,p.config)
        }
        return "--" to "日期待核验"
    }
}
