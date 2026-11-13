package com.ndxinvest.yjb.statistics

import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*

class PersonalStatisticsTest {
    @Test fun verifiedFirstBuyFromAbsentPositionNeedsNoInventedImportRecord() {
        val created=Instant.parse("2026-01-02T01:00:00Z")
        val draft=BuyTransaction("first",BuyInput(fund,BigDecimal("1000"),BigDecimal.ZERO,LocalDate.parse("2026-01-02"),CutoffType.BEFORE_1500),BuyBaseline(BigDecimal.ZERO,BigDecimal.ZERO,false),LocalDate.parse("2026-01-02"),createdAt=created,updatedAt=created,calendarSource="test")
        val confirmed=BuyCalculation.confirm(draft,draft.expectedNavDate,BigDecimal("10"),created)
        val synced=confirmed.copy(status=BuyStatus.SYNCED,syncedAt=created.plusSeconds(60),submittedTarget=baseline,postMayHaveBeenSent=true)
        val result=PersonalStatistics.calculate(snapshot(),JsonArray(emptyList()),listOf(synced),emptyList(),now)
        val view=result["funds"]!!.jsonObject[fund.uiId]!!.jsonObject
        assertNotEquals("--",view["annualized"]!!.jsonPrimitive.content)
        assertEquals("+¥100.00",view["cumulativeProfit"]!!.jsonPrimitive.content)
    }
    @Test fun olderCacheCannotSupplyStatisticsForNewerDisplayedHoldings() {
        val snapshot=buildJsonObject {put("lastSyncAt","2026-10-05T01:00:00Z")}
        assertTrue(PersonalStatistics.matchesSnapshot(snapshot,"2026-10-05T01:00:00Z"))
        assertFalse(PersonalStatistics.matchesSnapshot(snapshot,"2026-10-06T01:00:00Z"))
        assertFalse(PersonalStatistics.matchesSnapshot(snapshot,null))
        assertFalse(PersonalStatistics.matchesSnapshot(JsonObject(emptyMap()),"2026-10-05T01:00:00Z"))
    }
    private val fund=BuyFund("1","17","270042","测试基金")
    private val importedAt=Instant.parse("2026-01-01T01:00:00Z")
    private val now=Instant.parse("2026-10-05T01:00:00Z")
    private val baseline=BuyBaseline(BigDecimal("100"),BigDecimal("10"))
    private fun snapshot(position:BuyBaseline=baseline,value:String?="1100",date:String?="2026-10-05")=buildJsonObject {
        put("groups",buildJsonArray {add(buildJsonObject {put("accountId","1");put("funds",buildJsonArray {add(buildJsonObject {
            put("fund_id","17");put("hold_share",position.shares.toPlainString());put("hold_cost",position.unitCost.toPlainString());put("holding_cost",position.totalCost.toPlainString());value?.let {put("money",it)};put("hold_earn","100")
        })})})})
        put("quotes",buildJsonArray {add(buildJsonObject {put("fund_id","17");put("code","270042");put("last_net","11");date?.let {put("net_day",it)}})})
        put("lastSyncAt",now.toString());put("displayName","test");put("maskedId","***")
    }
    private fun imports(date:String?="2025-10-05",entries:JsonArray?=null)=JsonArray(listOf(buildJsonObject {
        put("id","initial:YJB_IMPORT:17");put("kind","INITIAL_POSITION");put("source","YJB_IMPORT");put("fundId","17");put("importedAt",importedAt.toString())
        put("initialShares","100");put("initialUnitCost","10");put("initialHoldingCost","1000");put("holdingStartDate",date?.let(::JsonPrimitive)?:JsonNull);entries?.let {put("initialContributions",it)}
    }))
    private fun entries(vararg rows:Pair<String,String>)=JsonArray(rows.map {(date,amount)->buildJsonObject {put("date",date);put("amount",amount)}})
    private fun result(raw:JsonObject=snapshot(),rows:JsonArray=imports(),buys:List<BuyTransaction> = emptyList(),sells:List<SellTransaction> = emptyList())=PersonalStatistics.calculate(raw,rows,buys,sells,now)
    private fun fundResult(all:JsonObject)=all["funds"]!!.jsonObject[fund.uiId]!!.jsonObject
    private fun text(all:JsonObject,key:String)=fundResult(all)[key]!!.jsonPrimitive.content
    private fun buy(base:BuyBaseline=baseline,amount:String="100",rate:String="0",date:String="2026-03-01",synced:String="2026-03-03T01:00:00Z"):BuyTransaction {
        val day=LocalDate.parse(date);val at=Instant.parse(synced)
        val tx=BuyTransaction("b:$date",BuyInput(fund,BigDecimal(amount),BigDecimal(rate),day,CutoffType.BEFORE_1500),base,day,createdAt=at,updatedAt=at,calendarSource="TEST")
        val confirmed=BuyCalculation.confirm(tx,day,BigDecimal("10"),at)
        val target=confirmed.target!!.let {BuyBaseline(it.shares.setScale(2,java.math.RoundingMode.HALF_UP),it.unitCost.setScale(8,java.math.RoundingMode.HALF_UP))}
        return confirmed.copy(status=BuyStatus.SYNCED,syncedAt=at,submittedTarget=target)
    }
    private fun sell(base:BuyBaseline=baseline,shares:String="10",rate:String="0",date:String="2026-04-01",synced:String="2026-04-03T01:00:00Z"):SellTransaction {
        val day=LocalDate.parse(date);val at=Instant.parse(synced)
        val tx=SellTransaction("s:$date",SellInput(fund,BigDecimal(shares),BigDecimal(rate),day,CutoffType.BEFORE_1500),base,day,createdAt=at,updatedAt=at,calendarSource="TEST")
        return SellCalculation.confirm(tx,OfficialBuyNav(day,BigDecimal("11")),at).let {it.copy(status=SellStatus.SYNCED,syncedAt=at,submittedTarget=it.target)}
    }
    @Test fun initialDefaultHasTenPercentAnnualAndFixedCumulativeWithoutChangingSnapshot() {
        val raw=snapshot();val all=result(raw);assertEquals("+10.00%",text(all,"annualized"));assertEquals("+¥100.00",text(all,"cumulativeProfit"));assertEquals("¥0.00",text(all,"realizedProfit"));assertEquals("+10.00%",text(all,"cumulativeRate"));assertEquals("1100",raw["groups"]!!.jsonArray[0].jsonObject["funds"]!!.jsonArray[0].jsonObject["money"]!!.jsonPrimitive.content)
        assertEquals("+10.00%",all["overview"]!!.jsonObject["annualized"]!!.jsonPrimitive.content)
    }
    @Test fun missingInitialDateHidesOnlyAnnualNotKnownProfit() {
        val all=result(rows=imports(null));assertEquals("--",text(all,"annualized"));assertEquals("MISSING_INITIAL_DATE",text(all,"reason"));assertEquals("+¥100.00",text(all,"cumulativeProfit"))
    }
    @Test fun detailRowsReplaceDefaultAndAreMergedByDate() {
        val rows=imports("2025-01-01",entries("2025-10-05" to "400","2025-10-05" to "600"));val all=result(rows=rows)
        assertEquals("+10.00%",text(all,"annualized"));assertEquals("2025-10-05",text(all,"holdingStartDate"));assertEquals("+¥100.00",text(all,"cumulativeProfit"))
    }
    @Test fun incompleteDetailDoesNotFallBackToEarlierDefaultDate() {
        val all=result(rows=imports("2025-01-01",entries("2025-10-05" to "800")))
        assertEquals("--",text(all,"annualized"));assertEquals("INITIAL_AMOUNT_MISMATCH",text(all,"reason"));assertEquals(JsonNull,fundResult(all)["holdingStartDate"])
    }
    @Test fun recentBuyParticipatesAndFeeIsNotDeductedTwice() {
        val tx=buy(amount="100",rate="0.01",date="2026-10-04",synced="2026-10-05T00:00:00Z")
        val all=result(snapshot(tx.submittedTarget!!,"1200"),buys=listOf(tx))
        assertEquals("+¥100.00",text(all,"cumulativeProfit"));assertNotEquals("--",text(all,"annualized"))
        val expected=PersonalXirr.calculate(listOf(PersonalCashFlow(LocalDate.parse("2025-10-05"),BigDecimal("-1000")),PersonalCashFlow(tx.input.tradeDate,BigDecimal("-100")),PersonalCashFlow(LocalDate.parse("2026-10-05"),BigDecimal("1200"))))
        assertTrue(BigDecimal(fundResult(all)["raw"]!!.jsonObject["annualRate"]!!.jsonPrimitive.content).subtract(expected.rate).abs()<BigDecimal("0.0000001"))
    }
    @Test fun sellUsesNetProceedsAndBaselineCostWithExactProfit() {
        val tx=sell(rate="0.01");val all=result(snapshot(tx.target!!,"990"),sells=listOf(tx))
        assertEquals("+¥8.90",text(all,"realizedProfit"));assertEquals("+¥98.90",text(all,"cumulativeProfit"));assertNotEquals("--",text(all,"annualized"))
    }
    @Test fun chainedBuyAndSellReconcileRoundedTargets() {
        val b=buy(rate="0.0015");val s=sell(b.submittedTarget!!);val all=result(snapshot(s.target!!,"1100"),buys=listOf(b),sells=listOf(s))
        assertNotEquals("--",text(all,"cumulativeProfit"));assertNotEquals("--",text(all,"annualized"))
    }
    @Test fun statesOtherThanSyncedDoNotCountAsFlowsOrGenerateOrders() {
        for(status in BuyStatus.entries.filter {it!=BuyStatus.SYNCED}){
            val tx=buy().copy(status=status,syncedAt=null,postMayHaveBeenSent=false)
            assertEquals("+10.00%",text(result(buys=listOf(tx)),"annualized"));assertEquals("+¥100.00",text(result(buys=listOf(tx)),"cumulativeProfit"))
        }
    }
    @Test fun alreadySyncedAtImportIsNotCountedTwice() {
        val tx=buy(synced="2025-12-31T01:00:00Z");assertEquals("+10.00%",text(result(buys=listOf(tx)),"annualized"))
    }
    @Test fun backdatedTradeSyncedAfterImportIsCountedWithItsTradeDate() {
        val tx=buy(date="2025-12-30",synced="2026-01-03T01:00:00Z")
        assertEquals("+¥100.00",text(result(snapshot(tx.submittedTarget!!,"1200"),buys=listOf(tx)),"cumulativeProfit"))
    }
    @Test fun externalChangeBlocksLocalStatisticsButKeepsKnownHoldingOrigin() {
        val all=result(snapshot(BuyBaseline(BigDecimal("101"),BigDecimal("10"))))
        assertEquals("--",text(all,"cumulativeProfit"));assertEquals("EXTERNAL_POSITION_CHANGED",text(all,"reason"));assertEquals("2025-10-05",text(all,"holdingStartDate"))
    }
    @Test fun ambiguousDeliveryTemporarilyBlocksStatistics() {
        val tx=buy().copy(status=BuyStatus.SYNC_FAILED,syncedAt=null,postMayHaveBeenSent=true)
        assertEquals("PENDING_WRITE",text(result(buys=listOf(tx)),"reason"))
    }
    @Test fun fullExitRetainsHistoryAndZeroTerminalInsteadOfMissingCurrentValue() {
        val tx=sell(shares="100",date="2026-10-05",synced="2026-10-05T00:00:00Z")
        val all=result(snapshot(tx.target!!,null),sells=listOf(tx))
        assertEquals("+10.00%",text(all,"annualized"));assertEquals("+¥100.00",text(all,"cumulativeProfit"));assertEquals("+¥100.00",text(all,"realizedProfit"));assertEquals(JsonNull,fundResult(all)["holdingDays"])
    }
    @Test fun missingValueOrDateNeverInventsAnnualReturn() {
        assertEquals("--",text(result(snapshot(value=null)),"annualized"));assertEquals("--",text(result(snapshot(date=null)),"annualized"))
    }
    @Test fun oldCachedValuationCannotBeMovedForwardByRefreshObservationDate() {
        val all=result(snapshot(date="2026-01-01"),imports("2025-10-05"));assertEquals("2026-01-01",text(all,"valuationDate"))
        assertEquals("UNDER_90_DAYS",text(all,"reason"))
    }
    @Test fun lastCashFlowAfterValuationWaitsRatherThanUsingStaleValue() {
        val b=buy(date="2026-10-04",synced="2026-10-05T00:00:00Z")
        assertEquals("VALUATION_BEFORE_CASH_FLOW",text(result(snapshot(b.submittedTarget!!,"1100","2026-10-03"),buys=listOf(b)),"reason"))
    }
    @Test fun overallRateCombinesCashFlowsAndRequiresAlignedDates() {
        val raw=snapshot();val second=raw["groups"]!!.jsonArray[0].jsonObject["funds"]!!.jsonArray[0].jsonObject.let {JsonObject(it+mapOf("fund_id" to JsonPrimitive("18"),"money" to JsonPrimitive("1200")))}
        val groups=buildJsonArray {add(buildJsonObject {put("accountId","1");put("funds",JsonArray(listOf(raw["groups"]!!.jsonArray[0].jsonObject["funds"]!!.jsonArray[0],second)))})}
        fun two(date:String)=JsonObject(raw+mapOf("groups" to groups,"quotes" to JsonArray(raw["quotes"]!!.jsonArray+buildJsonObject {put("fund_id","18");put("net_day",date)})))
        val rows=JsonArray(imports()+JsonObject(imports()[0].jsonObject+mapOf("id" to JsonPrimitive("initial:YJB_IMPORT:18"),"fundId" to JsonPrimitive("18"))))
        assertEquals("+15.00%",result(two("2026-10-05"),rows)["overview"]!!.jsonObject["annualized"]!!.jsonPrimitive.content)
        assertEquals("VALUATION_DATES_NOT_ALIGNED",result(two("2026-10-04"),rows)["overview"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
    }
    @Test fun minimumHoldingDateUsesOfficialBelongingDateNotObservationTime() {
        val b=buy(date="2025-12-20",synced="2025-12-29T01:00:00Z")
        val all=result(rows=imports("2025-12-25"),buys=listOf(b));assertEquals("2025-12-20",text(all,"holdingStartDate"))
    }
    @Test fun malformedSnapshotAndDetailsFailConservatively() {
        val all=result(rows=imports(null,entries("not-date" to "1000")));assertEquals("INCOMPLETE_DATA",text(all,"reason"))
    }
}
