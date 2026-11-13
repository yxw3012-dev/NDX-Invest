package com.ndxinvest.yjb.statistics

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

data class PersonalCashFlow(val date:LocalDate,val amount:BigDecimal)
data class PersonalXirrResult(val rate:BigDecimal?=null,val reason:String?=null)

/** Rate solver only. All cash amounts, fees, costs and aggregations remain BigDecimal.
 * Uses derivative root isolation for mixed flows; never chooses one of several roots.
 * Bounded rate/complexity support is explicit, rather than guessing on nonconvergence. */
object PersonalXirr {
    private data class Term(val years:Double,val coefficient:Double)
    private const val LEFT=-13.815510557964274 // log(1+r), r=-0.999999
    private const val RIGHT=13.815511557963774 // r=1,000,000
    private const val EPS=1e-12
    fun calculate(flows:List<PersonalCashFlow>,minimumDays:Long=90):PersonalXirrResult {
        // The evaluation period includes a zero terminal value or a same-day net-zero flow.
        val firstDate=flows.minOfOrNull {it.date}?:return PersonalXirrResult(reason="NO_RELIABLE_ROOT")
        val lastDate=flows.maxOf {it.date}
        if(ChronoUnit.DAYS.between(firstDate,lastDate)<minimumDays)return PersonalXirrResult(reason="UNDER_90_DAYS")
        val grouped=flows.groupBy {it.date}.map { (date,items)->PersonalCashFlow(date,items.fold(BigDecimal.ZERO){a,f->a+f.amount}) }.filter {it.amount.signum()!=0}.sortedBy {it.date}
        if(grouped.size<2)return PersonalXirrResult(reason="NO_RELIABLE_ROOT")
        val first=grouped.first().date
        if(grouped.none {it.amount.signum()<0}||grouped.none {it.amount.signum()>0})return PersonalXirrResult(reason="NO_RELIABLE_ROOT")
        val maximum=grouped.maxOf {it.amount.abs()}
        val terms=grouped.map {Term(ChronoUnit.DAYS.between(first,it.date)/365.0,it.amount.divide(maximum,MathContext.DECIMAL128).toDouble())}
        val variations=variations(terms)
        if(variations>1&&terms.size>64)return PersonalXirrResult(reason="COMPLEX_CASH_FLOWS")
        val roots=roots(terms)
        if(roots.size>1)return PersonalXirrResult(reason="MULTIPLE_ROOTS")
        if(roots.isEmpty())return PersonalXirrResult(reason="NO_RELIABLE_ROOT")
        val root=roots.single()
        if(abs(value(terms,root))>1e-9)return PersonalXirrResult(reason="NO_RELIABLE_ROOT")
        val rate=expm1(root)
        return if(rate.isFinite())PersonalXirrResult(BigDecimal.valueOf(rate))else PersonalXirrResult(reason="NO_RELIABLE_ROOT")
    }
    private fun variations(terms:List<Term>)=terms.zipWithNext().count {(a,b)->sign(a.coefficient)!=sign(b.coefficient)}
    private fun value(terms:List<Term>,x:Double):Double {
        val logs=terms.map {ln(abs(it.coefficient))-it.years*x}
        val max=logs.maxOrNull()?:return 0.0
        // Scale each evaluation; no huge exponent, and sign is unchanged.
        var total=0.0;var correction=0.0
        for(i in terms.indices){val next=sign(terms[i].coefficient)*exp(logs[i]-max)-correction;val sum=total+next;correction=(sum-total)-next;total=sum}
        return total
    }
    private fun roots(input:List<Term>):List<Double> {
        if(input.size<2||variations(input)==0)return emptyList()
        val offset=input.first().years
        val terms=input.map {it.copy(years=it.years-offset)}
        if(variations(terms)==1){val a=value(terms,LEFT);val b=value(terms,RIGHT);return if(a*b<0)listOf(bisect(terms,LEFT,RIGHT))else emptyList()}
        val derivative=terms.drop(1).map {Term(it.years,-it.years*it.coefficient)}
        val scale=derivative.maxOf {abs(it.coefficient)}
        val critical=roots(derivative.map {it.copy(coefficient=it.coefficient/scale)})
        val points=listOf(LEFT)+critical+RIGHT
        val result=mutableListOf<Double>()
        fun add(x:Double){if(x>LEFT&&x<RIGHT&&result.none {abs(it-x)<1e-8})result+=x}
        for(x in critical)if(abs(value(terms,x))<=EPS)add(x)
        for((a,b) in points.zipWithNext())if(value(terms,a)*value(terms,b)<0)add(bisect(terms,a,b))
        return result.sorted()
    }
    private fun bisect(terms:List<Term>,a:Double,b:Double):Double {
        var left=a;var right=b;var leftValue=value(terms,left)
        repeat(100){val middle=(left+right)/2;val v=value(terms,middle);if(abs(v)<1e-14||right-left<1e-13)return middle;if(sign(v)==sign(leftValue)){left=middle;leftValue=v}else right=middle}
        return (left+right)/2
    }
}
