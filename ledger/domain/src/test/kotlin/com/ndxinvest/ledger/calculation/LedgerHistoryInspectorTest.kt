package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import com.ndxinvest.ledger.rules.PurchasePlatformShareConfirmation
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class LedgerHistoryInspectorTest {
    private val fund = FundId("fund")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-20T08:00:00Z")
    private val lot = Lot(LotId("lot"), fund, TransactionId("buy"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, fund, TransactionType.PURCHASE, TransactionStatus.CALCULATED,
        TransactionOrigin.USER_ENTERED, DataAuthority.APP_RULE_CALCULATION, now.minusSeconds(86400 * 10),
        date.minusDays(3), date.minusDays(3), date, null, RuleVersionId("rule"),
        grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, purchaseFee = lot.purchaseFee,
        shares = lot.originalShares, unitNav = lot.unitNav)
    private val sale = buy.copy(id = TransactionId("sale"), type = TransactionType.REDEMPTION,
        tradeDate = date.plusDays(3), navDate = date.plusDays(3), confirmationDate = date.plusDays(4), shares = BigDecimal("40.00"),
        grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("392.04"), purchaseFee = null, redemptionFee = BigDecimal("3.96"))
    private val opening = RealLotOpened(LedgerEntryId("opening"), 1, lot)
    private val redemption = LotsRedeemed(LedgerEntryId("arbitrary-sale-event-name"), 2, fund,
        listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
    private val remaining = lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
    private fun snapshot() = LedgerHistorySnapshot(fund, listOf(buy, sale), listOf(remaining), emptyList(), listOf(opening, redemption))
    private fun reasons(value: LedgerHistorySnapshot): Set<HistoryIssueReason> =
        assertIs<LedgerHistoryInspection.NeedsReview>(LedgerHistoryInspector.inspect(value)).issues.map { it.reason }.toSet()

    @Test
    fun `explicit sale link verifies original consumed cost without actual cash or a new fee calculation`() {
        val input = snapshot()
        val result = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertEquals(listOf(remaining), result.rebuiltLedger.realLots)
        assertEquals(VerifiedRedemptionCost(sale.id, redemption.id, LotType.REAL, sale.tradeDate,
            BigDecimal("40.00"), BigDecimal("400.00")), result.redemptionCosts.single())
        assertNull(sale.actualCashFlow)
        assertEquals(listOf(opening, redemption), input.entries)
        assertEquals(buy, input.transactions.first())
        val scaled = input.copy(realLots = listOf(remaining.copy(costBasis = BigDecimal("600.000"), unitNav = BigDecimal("9.90000"))))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(scaled))
    }

    @Test
    fun `old event identity dates and identical amounts never infer a missing transaction link`() {
        val old = redemption.copy(id = LedgerEntryId("auto-entry:${sale.id.value}"), sourceTransactionId = null)
        assertEquals(setOf(HistoryIssueReason.UNLINKED_REDEMPTION, HistoryIssueReason.MISSING_REDEMPTION_EVENT),
            reasons(snapshot().copy(entries = listOf(opening, old))))
        assertNull(old.sourceTransactionId)
    }

    @Test
    fun `wrong source duplicate link missing event or mismatched confirmed shares require review`() {
        assertTrue(HistoryIssueReason.EVENT_TRANSACTION_MISMATCH in reasons(snapshot().copy(
            entries = listOf(opening, redemption.copy(sourceTransactionId = TransactionId("unknown"))))))
        assertTrue(HistoryIssueReason.EVENT_TRANSACTION_MISMATCH in reasons(snapshot().copy(
            entries = listOf(opening, redemption.copy(sourceTransactionId = buy.id)))))
        assertTrue(HistoryIssueReason.REDEMPTION_SHARES_MISMATCH in reasons(snapshot().copy(transactions = listOf(buy, sale.copy(shares = BigDecimal("39.99"))))))
        assertTrue(HistoryIssueReason.EVENT_TRANSACTION_MISMATCH in reasons(snapshot().copy(transactions = listOf(buy, sale.copy(status = TransactionStatus.PLANNED)))))
        assertTrue(HistoryIssueReason.MISSING_REDEMPTION_EVENT in reasons(snapshot().copy(entries = listOf(opening), realLots = listOf(lot))))
        val second = redemption.copy(id = LedgerEntryId("second"), sequence = 3,
            consumptions = listOf(LotConsumption(lot.id, BigDecimal("10.00"), BigDecimal("100.00"))))
        assertTrue(HistoryIssueReason.DUPLICATE_REDEMPTION_LINK in reasons(snapshot().copy(entries = listOf(opening, redemption, second),
            realLots = listOf(lot.copy(remainingShares = BigDecimal("50.00"), costBasis = BigDecimal("500.00"))))))
    }

    @Test
    fun `replay corruption projection drift purchase fact drift and a sale before confirmation cannot pass`() {
        assertEquals(setOf(HistoryIssueReason.INVALID_EVENT_HISTORY), reasons(snapshot().copy(entries = listOf(opening, opening, redemption))))
        val invalid = redemption.copy(consumptions = listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("1001.00"))))
        assertEquals(setOf(HistoryIssueReason.INVALID_EVENT_HISTORY), reasons(snapshot().copy(entries = listOf(opening, invalid))))
        assertTrue(HistoryIssueReason.PROJECTION_MISMATCH in reasons(snapshot().copy(realLots = listOf(remaining.copy(costBasis = BigDecimal("599.00"))))))
        assertTrue(HistoryIssueReason.PURCHASE_FACTS_MISMATCH in reasons(snapshot().copy(transactions = listOf(buy.copy(shares = BigDecimal("99.00")), sale))))
        assertTrue(HistoryIssueReason.MISSING_PURCHASE_EVENT in reasons(snapshot().copy(
            transactions = listOf(buy), entries = emptyList(), realLots = emptyList())))
        val inventedCost = lot.copy(costBasis = BigDecimal("900.00"))
        assertTrue(HistoryIssueReason.PURCHASE_FACTS_MISMATCH in reasons(snapshot().copy(transactions = listOf(buy),
            entries = listOf(opening.copy(lot = inventedCost)), realLots = listOf(inventedCost))))
    }

    @Test
    fun `wrong fund duplicate projection or a historical sale before the source lot requires review`() {
        assertEquals(setOf(HistoryIssueReason.WRONG_FUND), reasons(snapshot().copy(fundId = FundId("other"))))
        assertEquals(setOf(HistoryIssueReason.DUPLICATE_IDENTITY), reasons(snapshot().copy(realLots = listOf(remaining, remaining))))
        assertEquals(setOf(HistoryIssueReason.DUPLICATE_IDENTITY), reasons(snapshot().copy(transactions = listOf(buy, sale, sale))))
        assertTrue(HistoryIssueReason.REDEMPTION_BEFORE_LOT in reasons(snapshot().copy(transactions = listOf(buy, sale.copy(tradeDate = date.minusDays(1))))))
    }

    @Test
    fun `correction followed by sale is inspected using final original shares and unchanged opening history`() {
        val confirmation = PurchasePlatformShareConfirmation.prepare(buy, lot, FundPrecision(2, 2, 4, RoundingMode.HALF_UP),
            BigDecimal("1000.00"), date.minusDays(4), BigDecimal("99.99"), date, now, date.plusDays(6))
        val corrected = confirmation.entry.correctedLot
        val input = snapshot().copy(transactions = listOf(confirmation.confirmed, sale),
            realLots = listOf(corrected.copy(remainingShares = BigDecimal("59.99"), costBasis = BigDecimal("600.00"))),
            entries = listOf(opening, confirmation.entry.copy(sequence = 2), redemption.copy(sequence = 3)))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertEquals(lot, (input.entries.first() as RealLotOpened).lot)
    }

    @Test
    fun `multi lot sale includes reinvestment cost without inventing external investment or dividend cash`() {
        val reinvested = Lot(LotId("reinvested"), fund, TransactionId("reinvested-transaction"), LotType.REAL, date.plusDays(1),
            BigDecimal("5.00"), BigDecimal("5.00"), BigDecimal("0.00"), BigDecimal("50.00"), BigDecimal("0.00"),
            BigDecimal("50.00"), BigDecimal("10.0000"))
        val reinvestment = buy.copy(id = reinvested.sourceTransactionId, type = TransactionType.DIVIDEND_REINVESTMENT,
            status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            confirmationDate = reinvested.confirmationDate, grossAmount = BigDecimal("50.00"), netAmount = BigDecimal("50.00"),
            purchaseFee = BigDecimal("0.00"), unitNav = reinvested.unitNav, shares = reinvested.originalShares)
        val consumed = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00")),
            LotConsumption(reinvested.id, BigDecimal("5.00"), BigDecimal("50.00")))
        val input = snapshot().copy(transactions = listOf(buy, reinvestment, sale.copy(shares = BigDecimal("25.00"))),
            realLots = listOf(lot.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("800.00")),
                reinvested.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = listOf(opening, RealLotOpened(LedgerEntryId("reinvestment-open"), 2, reinvested),
                redemption.copy(sequence = 3, consumptions = consumed)))
        val result = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertEquals(BigDecimal("250.00"), result.redemptionCosts.single().allocatedCost)
        assertEquals(BigDecimal("25.00"), result.redemptionCosts.single().shares)
        assertNull(reinvestment.actualCashFlow)
    }

    @Test
    fun `Legacy sales verify only stored starting cost and do not fabricate purchase or NAV history`() {
        val legacy = LegacyLot(LotId("legacy"), fund, BigDecimal("100.00"), BigDecimal("500.00"), date.minusDays(5))
        val imported = buy.copy(type = TransactionType.ADJUSTMENT, origin = TransactionOrigin.IMPORTED,
            status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            shares = null, unitNav = null, grossAmount = null, netAmount = null, purchaseFee = null)
        val entry = LegacyLotRedeemed(LedgerEntryId("legacy-sale"), 2, fund,
            LotConsumption(legacy.id, BigDecimal("40.00"), BigDecimal("200.00")), sale.id)
        val input = LedgerHistorySnapshot(fund, listOf(imported, sale), emptyList(),
            listOf(legacy.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("300.00"))),
            listOf(LegacyLotOpened(LedgerEntryId("legacy-open"), 1, legacy), entry))
        val result = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertEquals(LotType.LEGACY, result.redemptionCosts.single().lotType)
        assertEquals(BigDecimal("200.00"), result.redemptionCosts.single().allocatedCost)
        assertEquals(null, input.transactions.first().unitNav)
        assertTrue(HistoryIssueReason.UNLINKED_REDEMPTION in reasons(input.copy(entries = listOf(input.entries.first(), entry.copy(sourceTransactionId = null)))))
    }

    @Test
    fun `assumed reversal needs an explicit unexecuted recurring source while empty snapshot claims no income`() {
        val recurring = buy.copy(type = TransactionType.RECURRING_PURCHASE, origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            status = TransactionStatus.NOT_EXECUTED)
        val reversal = AssumedPurchaseReversed(LedgerEntryId("reverse"), 2, fund, buy.id)
        val input = LedgerHistorySnapshot(fund, listOf(recurring), emptyList(), emptyList(), listOf(opening, reversal))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertTrue(HistoryIssueReason.REVERSAL_TRANSACTION_MISMATCH in reasons(input.copy(transactions = listOf(buy))))
        val empty = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(
            LedgerHistorySnapshot(fund, emptyList(), emptyList(), emptyList(), emptyList())))
        assertEquals(emptyList(), empty.redemptionCosts)
    }
}
