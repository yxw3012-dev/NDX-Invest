package com.ndxinvest.ledger.persistence

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*

class TrackingBaselineCodecTest {
    private val fund = FundId("baseline")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    private fun baseline(): TrackingBaseline {
        val lot = Lot(LotId("real"), fund, TransactionId("buy"), LotType.REAL, date, BigDecimal("10.00"), BigDecimal("10.00"),
            BigDecimal("95.00"), BigDecimal("90.00"), BigDecimal("5.00"), BigDecimal("95.00"), BigDecimal("9.0000"))
        val legacy = LegacyLot(LotId("legacy"), fund, BigDecimal("100.0000"), BigDecimal("700.00"), null)
        val buy = LedgerTransaction(lot.sourceTransactionId, fund, TransactionType.RECURRING_PURCHASE, TransactionStatus.CONFIRMED_ACTUAL,
            TransactionOrigin.AUTO_PLAN_ASSUMED, DataAuthority.USER_OR_PLATFORM_FINAL, now.minusSeconds(86400), date, date, date,
            null, RuleVersionId("rule"), grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, shares = lot.originalShares,
            unitNav = lot.unitNav, purchaseFee = lot.purchaseFee, actualCashFlow = BigDecimal("-95.00"), cashFlowDate = date.minusDays(1),
            isManualCorrected = true, updatedAt = now)
        val snapshot = LedgerHistorySnapshot(fund, listOf(buy), listOf(lot), listOf(legacy),
            listOf(RealLotOpened(LedgerEntryId("real-open"), 1, lot), LegacyLotOpened(LedgerEntryId("legacy-open"), 2, legacy)))
        val nav = NavRecord(fund, date, BigDecimal("10.0000"), BigDecimal("13.1234"), DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now)
        return TrackingBaseline.prepare(snapshot, nav, FundPrecision(2, 2, 4, RoundingMode.HALF_UP), now, date.plusDays(1))
    }

    @Test
    fun `complete valuation transaction lot and immutable evidence round trip without losing decimal or null facts`() {
        val expected = baseline()
        val row = TrackingBaselineCodec.encode(expected)
        val restored = TrackingBaselineCodec.decode(row)
        assertEquals(expected, restored)
        assertEquals(row, TrackingBaselineCodec.encode(restored))
        assertEquals(TransactionOrigin.AUTO_PLAN_ASSUMED, restored.history.transactions.single().origin)
        assertEquals(date.minusDays(1), restored.history.transactions.single().cashFlowDate)
        assertEquals(BigDecimal("100.0000"), restored.history.legacyLots.single().remainingShares)
        assertNull(restored.history.legacyLots.single().trackingStartDate)
        assertNull(restored.history.transactions.single().requestedAmount)
        assertEquals(BigDecimal("1100.00"), restored.startingValue)
    }

    @Test
    fun `tampered value shares scope version missing or unknown evidence is rejected rather than repaired`() {
        val row = TrackingBaselineCodec.encode(baseline())
        val root = Json.parseToJsonElement(row.payloadJson).jsonObject
        listOf(root + ("startingValue" to JsonPrimitive("795.00")), root + ("startingShares" to JsonPrimitive("109.00")),
            root + ("version" to JsonPrimitive(2)), root - "startingValue", root + ("unknown" to JsonPrimitive("ignored"))).forEach { changed ->
            assertFails { TrackingBaselineCodec.decode(row.copy(payloadJson = JsonObject(changed).toString())) }
        }
        assertFailsWith<IllegalArgumentException> { TrackingBaselineCodec.decode(row.copy(fundId = "other")) }
        assertFails { TrackingBaselineCodec.decode(row.copy(payloadJson = "{invalid")) }
    }

    @Test
    fun `corrupt nested snapshot or official provenance cannot pass the immutable evidence decoder`() {
        val row = TrackingBaselineCodec.encode(baseline())
        val root = Json.parseToJsonElement(row.payloadJson).jsonObject
        val nav = root.getValue("officialNav").jsonObject
        val snapshot = root.getValue("history").jsonObject
        val lot = snapshot.getValue("legacyLots").jsonArray.single().jsonObject
        val badHistory = snapshot + ("legacyLots" to JsonArray(listOf(JsonObject(lot + ("costBasis" to JsonPrimitive("600.00"))))))
        listOf(root + ("officialNav" to JsonObject(nav + ("authority" to JsonPrimitive("RELIABLE_AGGREGATOR")))),
            root + ("history" to JsonObject(badHistory)), root + ("officialNav" to JsonObject(nav - "source"))).forEach { changed ->
            assertFails { TrackingBaselineCodec.decode(row.copy(payloadJson = JsonObject(changed).toString())) }
        }
    }
}
