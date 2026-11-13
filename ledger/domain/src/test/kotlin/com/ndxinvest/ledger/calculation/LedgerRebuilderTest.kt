package com.ndxinvest.ledger.calculation

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
import kotlin.test.assertFailsWith

class LedgerRebuilderTest {
    private val fundId = FundId("fund-a")

    @Test
    fun `full replay and incremental application yield the same Lot state`() {
        val entries = listOf(
            RealLotOpened(LedgerEntryId("e1"), 1, realLot("real-1", "100.00", "100.00")),
            LegacyLotOpened(
                LedgerEntryId("e2"),
                2,
                LegacyLot(LotId("legacy-1"), fundId, BigDecimal("50.00"), BigDecimal("100.00"), null),
            ),
            LotsRedeemed(
                LedgerEntryId("e3"),
                3,
                fundId,
                listOf(LotConsumption(LotId("real-1"), BigDecimal("40.00"), BigDecimal("40.00"))),
            ),
            LegacyLotRedeemed(
                LedgerEntryId("e4"),
                4,
                fundId,
                LotConsumption(LotId("legacy-1"), BigDecimal("10.00"), BigDecimal("20.00")),
            ),
            RealLotOpened(LedgerEntryId("e5"), 5, realLot("reinvested-1", "5.00", "10.00")),
        )

        val incremental = entries.fold(RebuiltLedger.empty()) { state, entry ->
            LedgerRebuilder.apply(state, entry)
        }
        val replayed = LedgerRebuilder.rebuild(entries.reversed())

        assertEquals(incremental, replayed)
        assertEquals(BigDecimal("60.00"), replayed.realLots.first { it.id == LotId("real-1") }.remainingShares)
        assertEquals(BigDecimal("40.00"), replayed.legacyLots.single().remainingShares)
        assertEquals(BigDecimal("150.00"), replayed.totalRemainingCostBasis)
        assertEquals(BigDecimal("105.00"), replayed.totalShares)
    }

    @Test
    fun `replay rejects duplicate immutable entry identities`() {
        val entry = RealLotOpened(LedgerEntryId("same"), 1, realLot("real-1", "10.00", "10.00"))

        assertFailsWith<IllegalArgumentException> {
            LedgerRebuilder.rebuild(listOf(entry, entry))
        }
    }

    @Test
    fun `NOT_EXECUTED assumed purchase reversal removes its unconsumed Lot impact`() {
        val opened = RealLotOpened(
            LedgerEntryId("e1"),
            1,
            realLot("assumed-lot", "100.00", "100.00", transactionId = "assumed-tx"),
        )
        val reversed = AssumedPurchaseReversed(
            id = LedgerEntryId("e2"),
            sequence = 2,
            fundId = fundId,
            sourceTransactionId = TransactionId("assumed-tx"),
        )

        val result = LedgerRebuilder.rebuild(listOf(opened, reversed))

        assertEquals(emptyList(), result.realLots)
        assertEquals(BigDecimal.ZERO, result.totalShares)
        assertEquals(BigDecimal.ZERO, result.totalRemainingCostBasis)
    }

    private fun realLot(
        id: String,
        shares: String,
        cost: String,
        transactionId: String = "tx-$id",
    ): Lot = Lot(
        id = LotId(id),
        fundId = fundId,
        sourceTransactionId = TransactionId(transactionId),
        type = LotType.REAL,
        confirmationDate = LocalDate.parse("2026-01-01"),
        originalShares = BigDecimal(shares),
        remainingShares = BigDecimal(shares),
        grossCashOut = BigDecimal(cost),
        netSubscriptionAmount = BigDecimal(cost),
        purchaseFee = BigDecimal.ZERO,
        costBasis = BigDecimal(cost),
        unitNav = BigDecimal.ONE,
    )
}
