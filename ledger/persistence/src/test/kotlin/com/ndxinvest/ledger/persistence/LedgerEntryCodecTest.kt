package com.ndxinvest.ledger.persistence

import com.ndxinvest.ledger.calculation.AssumedPurchaseReversed
import com.ndxinvest.ledger.calculation.LegacyLotOpened
import com.ndxinvest.ledger.calculation.LegacyLotRedeemed
import com.ndxinvest.ledger.calculation.LotConsumption
import com.ndxinvest.ledger.calculation.LotsRedeemed
import com.ndxinvest.ledger.calculation.RealLotOpened
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseLotCorrected
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseAmountsCorrected
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseNavCorrected
import com.ndxinvest.ledger.calculation.ConsumedPurchaseCostCorrected
import com.ndxinvest.ledger.calculation.RedemptionCostRevision
import com.ndxinvest.ledger.model.FundPrecision
import java.math.RoundingMode
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import java.time.Instant
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.TransactionId
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class LedgerEntryCodecTest {
    @Test fun `share revision round trips exact evidence and rejects missing unknown coerced or foreign witnesses`() {
        val lot = realLot().copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))
        val old = LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("100.00"))
        val entry = com.ndxinvest.ledger.calculation.ConsumedPurchaseSharesCorrected(LedgerEntryId("shares-codec"), 9, lot,
            lot.copy(originalShares = BigDecimal("110.00"), remainingShares = BigDecimal("100.00"), costBasis = BigDecimal("909.09")),
            listOf(RedemptionCostRevision(LedgerEntryId("sale-entry"), TransactionId("sale"), old, old.copy(allocatedCostBasis = BigDecimal("90.91")))),
            FundPrecision(2, 2, 4, RoundingMode.HALF_UP), Instant.parse("2026-09-20T08:00:00Z"))
        val row = LedgerEntryCodec.encode(entry)
        assertEquals("CONSUMED_PURCHASE_SHARES_CORRECTED", row.entryType); assertEquals(entry, LedgerEntryCodec.decode(row))
        assertEquals(row, LedgerEntryCodec.encode(LedgerEntryCodec.decode(row)))
        for (payload in listOf(row.payloadJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            row.payloadJson.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":true"),
            row.payloadJson.replace("\"allocations\":", "\"missingAllocations\":"), row.payloadJson.replace("\"9.9000\"", "9.9000")))
            assertFailsWith<IllegalArgumentException> { LedgerEntryCodec.decode(row.copy(payloadJson = payload)) }
        assertFailsWith<IllegalArgumentException> { LedgerEntryCodec.decode(row.copy(fundId = "foreign")) }
    }
    private fun costEvent(): ConsumedPurchaseCostCorrected {
        val lot = realLot().copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))
        val old = LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("100.00"))
        return ConsumedPurchaseCostCorrected(LedgerEntryId("cost-codec"), 9, lot,
            lot.copy(grossCashOut = BigDecimal("1010.00"), purchaseFee = BigDecimal("20.00"), costBasis = BigDecimal("909.00")),
            listOf(RedemptionCostRevision(LedgerEntryId("old-sale-entry"), TransactionId("old-sale"), old, old.copy(allocatedCostBasis = BigDecimal("101.00")))),
            FundPrecision(2, 2, 4, RoundingMode.HALF_UP), Instant.parse("2026-09-20T08:00:00Z"))
    }
    @Test fun `append only consumed cost evidence preserves original allocation decimal scale precision and timestamp`() {
        val entry = costEvent(); val encoded = LedgerEntryCodec.encode(entry)
        assertEquals("CONSUMED_PURCHASE_COST_CORRECTED", encoded.entryType)
        assertEquals(entry, LedgerEntryCodec.decode(encoded))
        assertEquals(encoded, LedgerEntryCodec.encode(LedgerEntryCodec.decode(encoded)))
        assertTrue(encoded.payloadJson.contains("\"allocatedCostBasis\":\"101.00\""))
    }
    @Test fun `new cost evidence rejects unknown missing numeric coerced and wrong fund fields without guessing`() {
        val row = LedgerEntryCodec.encode(costEvent())
        for (payload in listOf(row.payloadJson.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            row.payloadJson.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":true"),
            row.payloadJson.replace("\"allocations\":", "\"missingAllocations\":"),
            row.payloadJson.replace("\"9.9000\"", "9.9000")))
            assertFailsWith<IllegalArgumentException> { LedgerEntryCodec.decode(row.copy(payloadJson = payload)) }
        assertFailsWith<IllegalArgumentException> { LedgerEntryCodec.decode(row.copy(fundId = "foreign")) }
    }
    @Test
    fun `all immutable ledger event variants round trip through versioned deterministic JSON`() {
        val lot = realLot()
        val legacy = LegacyLot(LotId("legacy-a"), FUND_ID, BigDecimal("40.00"), BigDecimal("300.00"), null)
        val consumption = LotConsumption(LotId("lot-a"), BigDecimal("10.00"), BigDecimal("100.00"))
        val entries = listOf(
            RealLotOpened(LedgerEntryId("e1"), 1, lot),
            LegacyLotOpened(LedgerEntryId("e2"), 2, legacy),
            LotsRedeemed(LedgerEntryId("e3"), 3, FUND_ID, listOf(consumption), TransactionId("real-sale")),
            LegacyLotRedeemed(LedgerEntryId("e4"), 4, FUND_ID, consumption.copy(lotId = legacy.id), TransactionId("legacy-sale")),
            AssumedPurchaseReversed(LedgerEntryId("e5"), 5, FUND_ID, lot.sourceTransactionId),
            UnconsumedPurchaseLotCorrected(LedgerEntryId("e6"), 6, lot, lot.copy(
                originalShares = BigDecimal("99.99"), remainingShares = BigDecimal("99.99"),
                confirmationDate = lot.confirmationDate.plusDays(1),
            )),
            UnconsumedPurchaseAmountsCorrected(LedgerEntryId("e7"), 7, lot, lot.copy(
                grossCashOut = BigDecimal("900.00"), costBasis = BigDecimal("900.00"),
                netSubscriptionAmount = BigDecimal("891.00"), purchaseFee = BigDecimal("9.00"),
                originalShares = BigDecimal("90.00"), remainingShares = BigDecimal("90.00"),
            )),
            UnconsumedPurchaseNavCorrected(LedgerEntryId("e8"), 8, lot, lot.copy(
                unitNav = BigDecimal("9.8000"), originalShares = BigDecimal("101.02"), remainingShares = BigDecimal("101.02"),
            ), NavRecord(FUND_ID, lot.confirmationDate.minusDays(1), BigDecimal("9.8000"), BigDecimal("12.3400"),
                DataAuthority.FUND_OFFICIAL, DataSourceId("official-source"), Instant.parse("2026-09-14T08:00:00Z"))),
        )

        entries.forEach { entry ->
            val encoded = LedgerEntryCodec.encode(entry)
            assertEquals(entry, LedgerEntryCodec.decode(encoded))
            assertTrue(encoded.payloadJson.startsWith("{\"schemaVersion\":1"))
            assertEquals(encoded.payloadJson, LedgerEntryCodec.encode(entry).payloadJson)
        }
    }

    @Test
    fun `old redemption JSON remains unchanged and event ID never supplies a missing source link`() {
        val consumption = LotConsumption(realLot().id, BigDecimal("10.00"), BigDecimal("100.00"))
        val oldReal = LotsRedeemed(LedgerEntryId("auto-entry:old-sale"), 2, FUND_ID, listOf(consumption))
        val oldLegacy = LegacyLotRedeemed(LedgerEntryId("legacy-redemption:old-sale"), 3, FUND_ID, consumption)
        val realEncoded = LedgerEntryCodec.encode(oldReal)
        val legacyEncoded = LedgerEntryCodec.encode(oldLegacy)
        assertTrue(!realEncoded.payloadJson.contains("sourceTransactionId"))
        assertTrue(!legacyEncoded.payloadJson.contains("sourceTransactionId"))
        assertEquals(oldReal, LedgerEntryCodec.decode(realEncoded))
        assertEquals(oldLegacy, LedgerEntryCodec.decode(legacyEncoded))
        assertNull((LedgerEntryCodec.decode(realEncoded) as LotsRedeemed).sourceTransactionId)
        assertNull((LedgerEntryCodec.decode(legacyEncoded) as LegacyLotRedeemed).sourceTransactionId)
    }

    private fun realLot(): Lot = Lot(
        id = LotId("lot-a"),
        fundId = FUND_ID,
        sourceTransactionId = TransactionId("purchase-a"),
        type = LotType.REAL,
        confirmationDate = LocalDate.parse("2026-09-14"),
        originalShares = BigDecimal("100.00"),
        remainingShares = BigDecimal("100.00"),
        grossCashOut = BigDecimal("1000.00"),
        netSubscriptionAmount = BigDecimal("990.00"),
        purchaseFee = BigDecimal("10.00"),
        costBasis = BigDecimal("1000.00"),
        unitNav = BigDecimal("9.9000"),
    )

    private companion object {
        val FUND_ID: FundId = FundId("fund-a")
    }
}
