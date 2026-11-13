package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class ConsumedPurchaseCostImpactPreviewTest {
    private val id = FundId("cost-preview")
    private val date = LocalDate.parse("2026-09-14")
    private val today = date.plusDays(6)
    private val now = Instant.parse("2026-09-20T08:00:00Z")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val fund = Fund(id, "TEST", "测试", "测试", "INDEX", null, emptyMap(), null, precision)
    private val lot = Lot(LotId("buy-lot"), id, TransactionId("buy"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, id, TransactionType.PURCHASE, TransactionStatus.CONFIRMED_ACTUAL,
        TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL, now.minusSeconds(10 * 86400), date.minusDays(3),
        date.minusDays(3), date, null, RuleVersionId("original-rule"), grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount,
        purchaseFee = lot.purchaseFee, shares = lot.originalShares, unitNav = lot.unitNav, actualCashFlow = lot.grossCashOut.negate(), cashFlowDate = date.minusDays(4))
    private val sale = buy.copy(id = TransactionId("sale"), type = TransactionType.REDEMPTION, tradeDate = date.plusDays(1),
        navDate = date.plusDays(1), confirmationDate = date.plusDays(2), settlementDate = date.plusDays(3), cashFlowDate = null,
        requestedAmount = BigDecimal("400.00"), shares = BigDecimal("40.00"), grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("450.00"),
        purchaseFee = null, redemptionFee = BigDecimal("3.96"), actualCashFlow = BigDecimal("450.00"))
    private val event = LotsRedeemed(LedgerEntryId("sale-event"), 2, id,
        listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
    private val history = LedgerHistorySnapshot(id, listOf(buy, sale),
        listOf(lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))), emptyList(),
        listOf(RealLotOpened(LedgerEntryId("open"), 1, lot), event))
    private val facts = ConsumedPurchaseCostFacts(BigDecimal("1010.00"), lot.netSubscriptionAmount, BigDecimal("20.00"),
        lot.originalShares, lot.unitNav, date.minusDays(4), date)
    private fun prepare(snapshot: LedgerHistorySnapshot = history, original: LedgerTransaction = buy,
        value: ConsumedPurchaseCostFacts = facts) = ConsumedPurchaseCostImpactPreview.prepare(fund, snapshot, original, value, now, today)

    private fun saved(proof: ConsumedPurchaseCostCorrection): LedgerHistorySnapshot = proof.preview.history.copy(
        transactions = proof.preview.history.transactions.map { if (it.id == proof.confirmed.id) proof.confirmed else it },
        realLots = proof.preview.history.realLots.map { if (it.id == proof.entry.expectedLot.id) proof.entry.correctedLot else it },
        entries = proof.preview.history.entries + proof.entry.copy(sequence = proof.preview.history.entries.maxOf { it.sequence } + 1))

    @Test fun `reviewed cost correction preserves original history and replays revised costs without consuming shares again`() {
        val proof = ConsumedPurchaseCostCorrection.prepare("cost-first", prepare(), today); proof.validate(now, today)
        assertEquals(buy.copy(grossAmount = BigDecimal("1010.00"), purchaseFee = BigDecimal("20.00"),
            actualCashFlow = BigDecimal("-1010.00"), isManualCorrected = true, updatedAt = now), proof.confirmed)
        assertEquals(setOf(TransactionCorrectionField.GROSS_AMOUNT, TransactionCorrectionField.PURCHASE_FEE,
            TransactionCorrectionField.ACTUAL_CASH_FLOW), proof.audits.map { it.field }.toSet())
        assertTrue(proof.audits.all { it.source == CorrectionSource.SALES_PLATFORM_FINAL && it.correctedAt == now })
        val after = saved(proof)
        assertEquals(history.entries, after.entries.dropLast(1)); assertEquals(sale, after.transactions.single { it.id == sale.id })
        val inspection = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(after))
        assertEquals(BigDecimal("404.00"), inspection.redemptionCosts.single().allocatedCost)
        assertEquals(history.realLots.single().remainingShares, inspection.rebuiltLedger.realLots.single().remainingShares)
        assertEquals(event, inspection.rebuiltLedger.recordedRedemptions.single())
        assertEquals(BigDecimal("46.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after, today, precision)).realizedReturn)
        val incremental = LedgerRebuilder.apply(LedgerRebuilder.rebuild(history.entries), after.entries.last())
        assertEquals(inspection.rebuiltLedger, incremental); assertEquals(incremental, LedgerRebuilder.rebuild(after.entries.reversed()))
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(incremental, after.entries.last()) }
    }
    @Test fun `calculated recurring purchase finalizes only explicit cash facts and retains assumed provenance`() {
        val original = buy.copy(type = TransactionType.RECURRING_PURCHASE, origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null)
        val input = history.copy(transactions = listOf(original, sale))
        val proof = ConsumedPurchaseCostCorrection.prepare("cost-assumed", prepare(input, original), today)
        assertEquals(TransactionStatus.CONFIRMED_ACTUAL, proof.confirmed.status)
        assertEquals(TransactionOrigin.AUTO_PLAN_ASSUMED, proof.confirmed.origin)
        assertEquals(CorrectionValue.Missing, proof.audits.single { it.field == TransactionCorrectionField.ACTUAL_CASH_FLOW }.originalValue)
        assertEquals(CorrectionValue.Missing, proof.audits.single { it.field == TransactionCorrectionField.CASH_FLOW_DATE }.originalValue)
        assertEquals(facts.paymentDate, proof.confirmed.cashFlowDate)
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(saved(proof)))
    }
    @Test fun `another explicit correction and a later sale use effective costs once but never alter saved events`() {
        val first = ConsumedPurchaseCostCorrection.prepare("cost-first", prepare(), today)
        val after = saved(first)
        val laterSale = sale.copy(id = TransactionId("cost-later-sale"), shares = BigDecimal("20.00"),
            actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"), tradeDate = date.plusDays(2), navDate = date.plusDays(2))
        val laterEvent = event.copy(id = LedgerEntryId("cost-later-event"), sequence = 4, sourceTransactionId = laterSale.id,
            consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("202.00"))))
        val withLater = after.copy(transactions = after.transactions + laterSale,
            realLots = listOf(first.entry.correctedLot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("404.00"))),
            entries = after.entries + laterEvent)
        val preview = prepare(withLater, first.confirmed, facts.copy(actualCashPaid = BigDecimal("1020.00"), purchaseFee = BigDecimal("30.00")))
        assertEquals(listOf(BigDecimal("404.00"), BigDecimal("202.00")), preview.sales.map { it.originalSaleCost })
        assertEquals(listOf(BigDecimal("408.00"), BigDecimal("204.00")), preview.sales.map { it.proposedSaleCost })
        val next = ConsumedPurchaseCostCorrection.prepare("cost-second", preview, today)
        val final = saved(next)
        assertEquals(withLater.entries, final.entries.dropLast(1))
        assertEquals(BigDecimal("408.00"), LedgerRebuilder.rebuild(final.entries).realLots.single().costBasis)
        assertEquals(BigDecimal("48.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(final, today, precision)).realizedReturn)
        val receipt = FinalRedemptionReceiptCorrection.prepare("after-cost-receipt", fund, final, sale,
            BigDecimal("460.00"), requireNotNull(sale.settlementDate), sale.confirmationDate, now, today)
        assertEquals(BigDecimal("408.00"), receipt.cost.allocatedCost); assertEquals(BigDecimal("52.00"), receipt.correctedRecordedIncome)
    }
    @Test fun `altered omitted or out of sequence historical revision cannot become an effective cost`() {
        val proof = ConsumedPurchaseCostCorrection.prepare("cost-first", prepare(), today)
        val entry = proof.entry.copy(sequence = 3); val revision = entry.allocations.single()
        val prefix = LedgerRebuilder.rebuild(history.entries)
        for (invalid in listOf(entry.copy(allocations = listOf(revision.copy(transactionId = TransactionId("foreign")))),
            entry.copy(allocations = listOf(revision.copy(entryId = LedgerEntryId("missing")))),
            entry.copy(allocations = listOf(revision.copy(corrected = revision.corrected.copy(allocatedCostBasis = BigDecimal("403.00"))))),
            entry.copy(correctedLot = entry.correctedLot.copy(costBasis = BigDecimal("605.00"))),
            entry.copy(expectedLot = entry.expectedLot.copy(costBasis = BigDecimal("599.00")))))
            assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(prefix, invalid) }
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(LedgerRebuilder.rebuild(history.entries.take(1)), entry) }
        for (invalid in listOf(proof.copy(audits = proof.audits.dropLast(1)), proof.copy(audits = proof.audits.reversed()),
            proof.copy(confirmed = proof.confirmed.copy(shares = BigDecimal("99.00"))),
            proof.copy(entry = proof.entry.copy(recordedAt = now.plusSeconds(1)))))
            assertFailsWith<IllegalArgumentException> { invalid.validate(now, today) }
    }
    @Test fun `cost correction conserves closing residual and subsequent receipt remains independently correctable`() {
        val close = sale.copy(id = TransactionId("cost-close"), shares = BigDecimal("60.00"), actualCashFlow = BigDecimal.ZERO,
            netAmount = BigDecimal.ZERO, tradeDate = date.plusDays(2), navDate = date.plusDays(2))
        val input = history.copy(transactions = history.transactions + close,
            realLots = listOf(lot.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = history.entries + event.copy(id = LedgerEntryId("cost-close-event"), sequence = 3, sourceTransactionId = close.id,
                consumptions = listOf(LotConsumption(lot.id, BigDecimal("60.00"), BigDecimal("600.00")))))
        val preview = prepare(input, value = facts.copy(actualCashPaid = BigDecimal("1000.01"), purchaseFee = BigDecimal("10.01")))
        val proof = ConsumedPurchaseCostCorrection.prepare("cost-close-review", preview, today)
        val inspection = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(saved(proof)))
        assertEquals(BigDecimal("1000.01"), inspection.redemptionCosts.fold(BigDecimal.ZERO) { sum, it -> sum.add(it.allocatedCost) })
        assertEquals(BigDecimal("0.00"), inspection.rebuiltLedger.realLots.single().costBasis)
        val swapped = proof.entry.copy(sequence = 4, allocations = proof.entry.allocations.reversed())
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(LedgerRebuilder.rebuild(input.entries), swapped) }
        val omitted = proof.entry.copy(sequence = 4, allocations = proof.entry.allocations.take(1))
        assertFailsWith<IllegalArgumentException> { LedgerRebuilder.apply(LedgerRebuilder.rebuild(input.entries), omitted) }
    }
    @Test fun `multiple batch revisions retain other allocations and read their latest effective costs`() {
        val second = lot.copy(id = LotId("other"), sourceTransactionId = TransactionId("other-buy"))
        val secondBuy = buy.copy(id = second.sourceTransactionId)
        val combinedSale = sale.copy(shares = BigDecimal("50.00"))
        val combinedEvent = event.copy(sequence = 3, consumptions = event.consumptions + LotConsumption(second.id, BigDecimal("10.00"), BigDecimal("100.00")))
        val input = history.copy(transactions = listOf(buy, secondBuy, combinedSale),
            realLots = history.realLots + second.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00")),
            entries = listOf(history.entries.first(), RealLotOpened(LedgerEntryId("other-open"), 2, second), combinedEvent))
        val first = ConsumedPurchaseCostCorrection.prepare("cost-batch-one", prepare(input), today)
        val nextPreview = prepare(saved(first), secondBuy, facts)
        assertEquals(BigDecimal("504.00"), nextPreview.sales.single().originalSaleCost)
        assertEquals(BigDecimal("505.00"), nextPreview.sales.single().proposedSaleCost)
        val secondProof = ConsumedPurchaseCostCorrection.prepare("cost-batch-two", nextPreview, today)
        val after = saved(secondProof)
        assertEquals(BigDecimal("505.00"), assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(after)).redemptionCosts.single().allocatedCost)
        assertEquals(combinedEvent, after.entries.filterIsInstance<LotsRedeemed>().single())
    }

    @Test fun `read only preview reveals per sale income and remaining cost but preserves all original facts`() {
        val preview = prepare(); preview.validate(now, today)
        assertEquals(history, preview.history); assertEquals(buy, preview.originalPurchase)
        assertEquals(history.realLots.single(), preview.originalLot)
        assertEquals(BigDecimal("606.00"), preview.proposedLot.costBasis)
        assertEquals(preview.originalLot.copy(grossCashOut = facts.actualCashPaid, purchaseFee = facts.purchaseFee,
            costBasis = BigDecimal("606.00")), preview.proposedLot)
        val affected = preview.sales.single()
        assertEquals(event, affected.event); assertEquals(sale, affected.sale)
        assertEquals(BigDecimal("400.00"), affected.originalSaleCost); assertEquals(BigDecimal("404.00"), affected.proposedSaleCost)
        assertEquals(BigDecimal("50.00"), affected.originalRecordedIncome); assertEquals(BigDecimal("46.00"), affected.proposedRecordedIncome)
        assertEquals(event.consumptions.single().shares, affected.proposedConsumption.shares)
        assertEquals(history, preview.history); assertTrue(preview.isBasedOn(fund, history))
        val lowerCost = prepare(value = facts.copy(actualCashPaid = BigDecimal("990.00"), purchaseFee = BigDecimal("0.00")))
        assertEquals(BigDecimal("396.00"), lowerCost.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("54.00"), lowerCost.sales.single().proposedRecordedIncome)
        assertEquals(BigDecimal("594.00"), lowerCost.proposedLot.costBasis)
    }
    @Test fun `two sales use sequential original allocations without recomputing final cash NAV fee or rule`() {
        val later = sale.copy(id = TransactionId("later"), tradeDate = date.plusDays(2), navDate = date.plusDays(2),
            shares = BigDecimal("20.00"), actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"))
        val input = history.copy(transactions = history.transactions + later,
            realLots = listOf(lot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("400.00"))),
            entries = history.entries + event.copy(id = LedgerEntryId("later-event"), sequence = 3, sourceTransactionId = later.id,
                consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00")))))
        val preview = prepare(input)
        assertEquals(listOf(BigDecimal("404.00"), BigDecimal("202.00")), preview.sales.map { it.proposedSaleCost })
        assertEquals(listOf(BigDecimal("46.00"), BigDecimal("8.00")), preview.sales.map { it.proposedRecordedIncome })
        assertEquals(BigDecimal("404.00"), preview.proposedLot.costBasis)
        assertEquals(listOf(sale, later), preview.sales.map { it.sale }); assertEquals(input.entries, preview.history.entries)
    }
    @Test fun `full closing assigns all rounding residual and zero remaining cost without fabricated closing cash`() {
        val later = sale.copy(id = TransactionId("close"), shares = BigDecimal("60.00"), actualCashFlow = BigDecimal.ZERO,
            netAmount = BigDecimal.ZERO, tradeDate = date.plusDays(2), navDate = date.plusDays(2))
        val input = history.copy(transactions = history.transactions + later,
            realLots = listOf(lot.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = history.entries + event.copy(id = LedgerEntryId("close-event"), sequence = 3, sourceTransactionId = later.id,
                consumptions = listOf(LotConsumption(lot.id, BigDecimal("60.00"), BigDecimal("600.00")))))
        val preview = prepare(input, value = facts.copy(actualCashPaid = BigDecimal("1000.01"), purchaseFee = BigDecimal("10.01")))
        assertEquals(BigDecimal("0.00"), preview.proposedLot.costBasis)
        assertEquals(BigDecimal("1000.01"), preview.sales.fold(BigDecimal.ZERO) { total, impact -> total.add(impact.proposedConsumption.allocatedCostBasis) })
        assertEquals(BigDecimal("600.01"), preview.sales.last().proposedSaleCost)
        assertEquals(BigDecimal("-600.01"), preview.sales.last().proposedRecordedIncome)
    }
    @Test fun `multiple lot sale changes only selected lot cost while unrelated real and Legacy holdings are preserved`() {
        val second = lot.copy(id = LotId("other"), sourceTransactionId = TransactionId("other-buy"))
        val secondBuy = buy.copy(id = second.sourceTransactionId)
        val combinedSale = sale.copy(shares = BigDecimal("50.00"))
        val combined = event.copy(sequence = 3, consumptions = event.consumptions + LotConsumption(second.id, BigDecimal("10.00"), BigDecimal("100.00")))
        val legacy = LegacyLot(LotId("legacy"), id, BigDecimal("50.00"), BigDecimal("300.00"), null)
        val input = history.copy(transactions = listOf(buy, secondBuy, combinedSale),
            realLots = history.realLots + second.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00")),
            legacyLots = listOf(legacy), entries = listOf(history.entries.first(), RealLotOpened(LedgerEntryId("other-open"), 2, second),
                combined, LegacyLotOpened(LedgerEntryId("legacy-open"), 4, legacy)))
        val preview = prepare(input); assertEquals(BigDecimal("504.00"), preview.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("-54.00"), preview.sales.single().proposedRecordedIncome)
        assertEquals(input, preview.history); assertEquals(lot.id, preview.sales.single().proposedConsumption.lotId)
    }
    @Test fun `calculated purchase and sale can preview costs but estimates never become recorded income`() {
        val purchase = buy.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION,
            actualCashFlow = null, cashFlowDate = null)
        val redemption = sale.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION,
            actualCashFlow = null, cashFlowDate = null, settlementDate = null)
        val preview = prepare(history.copy(transactions = listOf(purchase, redemption)), purchase)
        assertEquals(BigDecimal("404.00"), preview.sales.single().proposedSaleCost)
        assertNull(preview.sales.single().originalRecordedIncome); assertNull(preview.sales.single().proposedRecordedIncome)
    }
    @Test fun `share NAV net or confirmation changes future facts and incomplete fees are not treated as cost only`() {
        for (value in listOf(facts.copy(shares = BigDecimal("101.00")), facts.copy(unitNav = BigDecimal("10.0000")),
            facts.copy(netAmount = BigDecimal("1000.00"), purchaseFee = BigDecimal("10.00")),
            facts.copy(confirmationDate = date.plusDays(1)), facts.copy(paymentDate = date.plusDays(1)),
            facts.copy(actualCashPaid = BigDecimal("1010.001")), facts.copy(purchaseFee = BigDecimal("19.00")),
            facts.copy(actualCashPaid = BigDecimal("1000.00"), purchaseFee = BigDecimal("10.00")),
            facts.copy(purchaseFee = BigDecimal("-1.00")), facts.copy(actualCashPaid = BigDecimal("0.00"))))
            assertFailsWith<IllegalArgumentException> { prepare(value = value) }
        assertFailsWith<IllegalArgumentException> { prepare().validate(now.minusSeconds(1), today) }
    }
    @Test fun `unlinked corrupt pending foreign or unconsumed history cannot be guessed into an impact preview`() {
        val pending = sale.copy(id = TransactionId("pending"), status = TransactionStatus.PENDING_CALC,
            authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null)
        for (input in listOf(history.copy(entries = listOf(history.entries.first(), event.copy(sourceTransactionId = null))),
            history.copy(realLots = listOf(history.realLots.single().copy(costBasis = BigDecimal("599.00")))),
            history.copy(transactions = history.transactions + pending), history.copy(fundId = FundId("foreign")),
            history.copy(entries = listOf(history.entries.first()), transactions = listOf(buy), realLots = listOf(lot))))
            assertFailsWith<IllegalArgumentException> { prepare(input) }
        assertFailsWith<IllegalArgumentException> { prepare(original = buy.copy(id = TransactionId("foreign"))) }
        assertFailsWith<IllegalArgumentException> { prepare(history.copy(transactions = listOf(buy.copy(actualCashFlow = null, cashFlowDate = null), sale)),
            buy.copy(actualCashFlow = null, cashFlowDate = null)) }
    }
    @Test fun `non proportional existing allocations and per consumption precision errors require separate review`() {
        for (cost in listOf(BigDecimal("399.00"), BigDecimal("400.001"))) {
            val input = history.copy(realLots = listOf(history.realLots.single().copy(costBasis = BigDecimal("1000.00").subtract(cost))),
                entries = listOf(history.entries.first(), event.copy(consumptions = listOf(event.consumptions.single().copy(allocatedCostBasis = cost)))))
            assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
            assertFailsWith<IllegalArgumentException> { prepare(input) }
        }
    }
    @Test fun `preview cannot omit or tamper impacts and evidence changes invalidate the exact snapshot`() {
        val preview = prepare()
        for (tampered in listOf(preview.copy(sales = emptyList()), preview.copy(proposedLot = preview.originalLot),
            preview.copy(sales = listOf(preview.sales.single().copy(proposedSaleCost = BigDecimal("0.00"))))))
            assertFailsWith<IllegalArgumentException> { tampered.validate(now, today) }
        assertFalse(preview.isBasedOn(fund.copy(precision = precision.copy(moneyScale = 3)), history))
        assertFalse(preview.isBasedOn(fund, history.copy(transactions = listOf(buy, sale.copy(actualCashFlow = BigDecimal("460.00"))))))
    }
    @Test fun `assumed recurring provenance remains unchanged and previously confirmed unconsumed corrections are read not reapplied`() {
        val assumed = buy.copy(type = TransactionType.RECURRING_PURCHASE, origin = TransactionOrigin.AUTO_PLAN_ASSUMED)
        val old = lot.copy(grossCashOut = BigDecimal("990.00"), purchaseFee = BigDecimal("0.00"), costBasis = BigDecimal("990.00"))
        val correction = UnconsumedPurchaseAmountsCorrected(LedgerEntryId("prior-correction"), 2, old, lot)
        val input = history.copy(transactions = listOf(assumed, sale), entries = listOf(RealLotOpened(LedgerEntryId("open"), 1, old),
            correction, event.copy(sequence = 3)))
        val preview = prepare(input, assumed)
        assertEquals(TransactionOrigin.AUTO_PLAN_ASSUMED, preview.originalPurchase.origin)
        assertEquals(BigDecimal("404.00"), preview.sales.single().proposedSaleCost); assertEquals(input.entries, preview.history.entries)
    }
}
