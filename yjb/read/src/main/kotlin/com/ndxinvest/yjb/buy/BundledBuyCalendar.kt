package com.ndxinvest.yjb.buy

import kotlinx.serialization.json.*
import java.time.LocalDate

/** Product-wide China/US market calendar. All funds use the same date rules. */
object BundledBuyCalendar {
    private val profile by lazy {
        val stream=javaClass.getResourceAsStream("/calendars/fund-270042-2026.json")
            ?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
        stream.bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}.also {
            require(it["authority"]!!.jsonPrimitive.content=="FUND_OFFICIAL")
        }
    }
    private val start by lazy {LocalDate.parse(profile["coverageStart"]!!.jsonPrimitive.content)}
    private val end by lazy {LocalDate.parse(profile["coverageEnd"]!!.jsonPrimitive.content)}
    private val dates by lazy {profile["openDates"]!!.jsonArray.map {LocalDate.parse(it.jsonPrimitive.content)}.toSet()}
    private val partial2027Closures by lazy {
        val stream=javaClass.getResourceAsStream("/calendars/shared-cn-us-2027.json")
            ?:throw BuyFailure(BuyProblem.CALENDAR_UNAVAILABLE)
        val partial=stream.bufferedReader().use {Json.parseToJsonElement(it.readText()).jsonObject}
        require(partial["authority"]!!.jsonPrimitive.content=="MARKET_OFFICIAL_PARTIAL")
        require(partial["openDates"]!!.jsonArray.isEmpty())
        partial["closedDates"]!!.jsonArray.map {LocalDate.parse(it.jsonPrimitive.content)}.toSet()
    }
    // Preserve fund identity while reusing one shared calendar, without a code whitelist.
    fun forCode(code:String):BuyCalendar = BuyCalendar(code,start,end,dates,"shared-cn-us-2026",partial2027Closures)
}

