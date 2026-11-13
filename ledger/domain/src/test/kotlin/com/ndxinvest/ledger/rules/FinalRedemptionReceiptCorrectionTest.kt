package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class FinalRedemptionReceiptCorrectionTest {
    private val id = FundId("receipt-fund")
    private val date = LocalDate.parse("2026-09-14")
    private val today = date.plusDays(6)
    private val now = Instant.parse("2026-09-20T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val fund = Fund(id, "FUND", "基金", "基金", "INDEX", null, emptyMap(), null, precision)
    private val lot = Lot(LotId("lot"), id, TransactionId("buy"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, id, TransactionType.PURCHASE, TransactionStatus.CONFIRMED_ACTUAL,
        TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL, now.minusSeconds(10 * 86400), date.minusDays(3),
        date.minusDays(3), date, null, RuleVersionId("original-rule"), grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount,
        purchaseFee = lot.purchaseFee, shares = lot.originalShares, unitNav = lot.unitNav, actualCashFlow = lot.grossCashOut.negate(), cashFlowDate = date.minusDays(4))
    private val sale = buy.copy(id = TransactionId("sale"), type = TransactionType.REDEMPTION, tradeDate = date.plusDays(3),
        navDate = date.plusDays(3), confirmationDate = date.plusDays(4), settlementDate = date.plusDays(5), cashFlowDate = null,
        requestedAmount = BigDecimal("400.00"), shares = BigDecimal("40.00"), grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("450.00"),
        purchaseFee = null, redemptionFee = BigDecimal("3.96"), actualCashFlow = BigDecimal("450.00"))
    private val event = LotsRedeemed(LedgerEntryId("sale-event"), 2, id,
        listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
    private val history = LedgerHistorySnapshot(id, listOf(buy, sale), listOf(lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))),
        emptyList(), listOf(RealLotOpened(LedgerEntryId("open"), 1, lot), event))
    private fun prepare(snapshot: LedgerHistorySnapshot = history, original: LedgerTransaction = sale, cash: String = "460.00",
        cashDate: LocalDate = date.plusDays(4), confirmation: LocalDate? = sale.confirmationDate) =
        FinalRedemptionReceiptCorrection.prepare("review-1", fund, snapshot, original, BigDecimal(cash), cashDate, confirmation, now, today)

    @Test fun `receipt preview preserves every consumed lot event and original NAV fees dates and rule`() {
        val correction = prepare(); correction.validate(now, today)
        assertEquals(history, correction.history); assertEquals(sale.settlementDate, correction.confirmed.settlementDate)
        assertEquals(sale.copy(actualCashFlow = BigDecimal("460.00"), netAmount = BigDecimal("460.00"), cashFlowDate = date.plusDays(4),
            isManualCorrected = true, updatedAt = now), correction.confirmed)
        assertEquals(BigDecimal("400.00"), correction.cost.allocatedCost)
        assertEquals(BigDecimal("50.00"), correction.previousRecordedIncome); assertEquals(BigDecimal("60.00"), correction.correctedRecordedIncome)
        assertEquals(setOf(TransactionCorrectionField.ACTUAL_CASH_FLOW, TransactionCorrectionField.NET_AMOUNT, TransactionCorrectionField.CASH_FLOW_DATE),
            correction.audits.map { it.field }.toSet())
        assertTrue(correction.audits.all { it.source == CorrectionSource.SALES_PLATFORM_FINAL })
    }
    @Test fun `zero actual receipt and independent confirmation date are explicit facts not recalculated fees`() {
        val corrected = prepare(cash = "0.00", confirmation = date.plusDays(3)); corrected.validate(now, today)
        assertEquals(BigDecimal("-400.00"), corrected.correctedRecordedIncome)
        assertEquals(sale.redemptionFee, corrected.confirmed.redemptionFee); assertEquals(sale.grossAmount, corrected.confirmed.grossAmount)
        assertEquals(CorrectionValue.Date(sale.confirmationDate!!), corrected.audits.single { it.field == TransactionCorrectionField.CONFIRMATION_DATE }.originalValue)
    }
    @Test fun `invalid precision signs future dates removed confirmation and unchanged facts cannot preview`() {
        for (operation in listOf<() -> Unit>({ prepare(cash = "-1.00") }, { prepare(cash = "460.001") },
            { prepare(cashDate = today.plusDays(1)) }, { prepare(cashDate = sale.tradeDate.minusDays(1)) },
            { prepare(confirmation = today.plusDays(1)) }, { prepare(confirmation = sale.tradeDate.minusDays(1)) },
            { prepare(confirmation = null) }, { prepare(cash = "450.000", cashDate = sale.settlementDate!!) }))
            assertFailsWith<IllegalArgumentException> { operation() }
        assertFailsWith<IllegalArgumentException> { prepare().validate(now.minusSeconds(1), today) }
    }
    @Test fun `tampered shares NAV rule settlement cost and missing audit cannot validate full bundle`() {
        val proof = prepare()
        for (tampered in listOf(proof.copy(confirmed = proof.confirmed.copy(shares = BigDecimal("39.00"))),
            proof.copy(confirmed = proof.confirmed.copy(unitNav = BigDecimal("10.0000"))),
            proof.copy(confirmed = proof.confirmed.copy(ruleVersionId = RuleVersionId("new-rule"))),
            proof.copy(confirmed = proof.confirmed.copy(settlementDate = date.plusDays(4))),
            proof.copy(cost = proof.cost.copy(allocatedCost = BigDecimal("399.00"))), proof.copy(audits = proof.audits.dropLast(1))))
            assertFailsWith<IllegalArgumentException> { tampered.validate(now, today) }
        assertFailsWith<IllegalArgumentException> { prepare(history.copy(entries = history.entries.filterNot { it == event })) }
        val unlinked = event.copy(sourceTransactionId = null)
        assertFailsWith<IllegalArgumentException> { prepare(history.copy(entries = listOf(history.entries.first(), unlinked))) }
    }
    @Test fun `later historical sale remains untouched when correcting an earlier final receipt`() {
        val later = sale.copy(id = TransactionId("later-sale"), tradeDate = date.plusDays(4), navDate = date.plusDays(4), shares = BigDecimal("20.00"),
            actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"))
        val laterEvent = event.copy(id = LedgerEntryId("later-event"), sequence = 3, sourceTransactionId = later.id,
            consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00"))))
        val input = history.copy(transactions = history.transactions + later, entries = history.entries + laterEvent,
            realLots = listOf(lot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("400.00"))))
        val correction = prepare(input); correction.validate(now, today)
        val after = input.copy(transactions = input.transactions.map { if (it.id == sale.id) correction.confirmed else it })
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(after))
        assertEquals(input.entries, after.entries); assertEquals(input.realLots, after.realLots); assertEquals(later, after.transactions.last())
        val income = assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after, today, precision))
        assertEquals(BigDecimal("70.00"), income.realizedReturn)
        assertEquals(date.plusDays(4), income.contributions.first { it.transactionId == sale.id }.cashDate)
    }
    @Test fun `Legacy receipt can retain unknown confirmation NAV and incomplete old history`() {
        val old = LegacyLot(LotId("old"), id, BigDecimal("100.00"), BigDecimal("700.00"), null)
        val imported = buy.copy(id = TransactionId("import"), type = TransactionType.ADJUSTMENT, origin = TransactionOrigin.IMPORTED,
            shares = old.remainingShares, actualCashFlow = null, cashFlowDate = null)
        val oldSale = sale.copy(navDate = null, confirmationDate = null, unitNav = null, grossAmount = null, redemptionFee = null)
        val input = LedgerHistorySnapshot(id, listOf(imported, oldSale), emptyList(), listOf(old.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("420.00"))),
            listOf(LegacyLotOpened(LedgerEntryId("old-open"), 1, old), LegacyLotRedeemed(LedgerEntryId("old-sale"), 2, id,
                LotConsumption(old.id, BigDecimal("40.00"), BigDecimal("280.00")), oldSale.id)))
        val correction = prepare(input, oldSale, confirmation = null); correction.validate(now, today)
        assertNull(correction.confirmed.confirmationDate); assertNull(correction.confirmed.unitNav)
        assertEquals(BigDecimal("180.00"), correction.correctedRecordedIncome); assertEquals(LotType.LEGACY, correction.cost.lotType)
        assertEquals(input.legacyLots, correction.history.legacyLots)
    }
    @Test fun `known final cash with different prior scale keeps original representation when only date changes`() {
        val original = sale.copy(actualCashFlow = BigDecimal("450"), netAmount = BigDecimal("450.000"))
        val input = history.copy(transactions = listOf(buy, original))
        val correction = prepare(input, original, cash = "450.00")
        correction.validate(now, today)
        assertEquals(original.actualCashFlow, correction.confirmed.actualCashFlow)
        assertEquals(original.netAmount, correction.confirmed.netAmount)
        assertEquals(listOf(TransactionCorrectionField.CASH_FLOW_DATE), correction.audits.map { it.field })
    }
    @Test fun `opposing invalid per lot costs or shares cannot cancel into a valid aggregate receipt proof`() {
        for (kind in listOf("cost", "shares")) {
            val second = lot.copy(id = LotId("second-lot"), sourceTransactionId = TransactionId("second-buy"))
            val secondBuy = buy.copy(id = second.sourceTransactionId)
            val costs = if (kind == "cost") listOf(BigDecimal("200.005"), BigDecimal("199.995")) else listOf(BigDecimal("200.00"), BigDecimal("200.00"))
            val shares = if (kind == "shares") listOf(BigDecimal("20.005"), BigDecimal("19.995")) else listOf(BigDecimal("20.00"), BigDecimal("20.00"))
            val lots = listOf(lot, second)
            val input = history.copy(transactions = listOf(buy, secondBuy, sale),
                realLots = lots.mapIndexed { index, value -> value.copy(remainingShares = value.remainingShares.subtract(shares[index]),
                    costBasis = value.costBasis.subtract(costs[index])) }, entries = listOf(history.entries.first(),
                    RealLotOpened(LedgerEntryId("second-open"), 2, second), event.copy(sequence = 3,
                        consumptions = lots.mapIndexed { index, value -> LotConsumption(value.id, shares[index], costs[index]) })))
            assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
            assertFailsWith<IllegalArgumentException> { prepare(input) }
        }
    }
}
