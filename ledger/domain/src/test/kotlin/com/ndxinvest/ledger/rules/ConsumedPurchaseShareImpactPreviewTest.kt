package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.calculation.*
import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import kotlin.test.*

class ConsumedPurchaseShareImpactPreviewTest {
    private val id = FundId("share-preview")
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
        shares = BigDecimal("40.00"), grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("450.00"),
        purchaseFee = null, redemptionFee = BigDecimal("3.96"), actualCashFlow = BigDecimal("450.00"))
    private val event = LotsRedeemed(LedgerEntryId("sale-event"), 2, id,
        listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
    private val history = LedgerHistorySnapshot(id, listOf(buy, sale),
        listOf(lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))), emptyList(),
        listOf(RealLotOpened(LedgerEntryId("open"), 1, lot), event))
    private val facts = ConsumedPurchaseShareFacts(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee,
        BigDecimal("110.00"), lot.unitNav, date.minusDays(4), date)
    private fun preview(snapshot: LedgerHistorySnapshot = history, original: LedgerTransaction = buy,
        input: ConsumedPurchaseShareFacts = facts) = ConsumedPurchaseShareImpactPreview.prepare(fund, snapshot, original, input, now, today)
    private fun twoSales(): LedgerHistorySnapshot {
        val second = sale.copy(id = TransactionId("second"), shares = BigDecimal("20.00"), tradeDate = date.plusDays(2), navDate = date.plusDays(2))
        return history.copy(transactions = history.transactions + second,
            realLots = listOf(lot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("400.00"))),
            entries = history.entries + event.copy(id = LedgerEntryId("second-event"), sequence = 3, sourceTransactionId = second.id,
                consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00")))))
    }
    @Test fun `greater platform shares preview new remaining shares and lower allocated cost without changing saved facts`() {
        val result = preview(); result.validate(now, today)
        assertEquals(BigDecimal("110.00"), result.proposedLot.originalShares); assertEquals(BigDecimal("70.00"), result.proposedLot.remainingShares)
        assertEquals(BigDecimal("636.36"), result.proposedLot.costBasis)
        assertEquals(BigDecimal("363.64"), result.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("86.36"), result.sales.single().proposedRecordedIncome)
        assertEquals(BigDecimal("50.00"), result.sales.single().originalRecordedIncome)
        assertEquals(sale, result.sales.single().sale); assertEquals(event, result.sales.single().event)
        assertEquals(history, result.history); assertEquals(buy, result.originalPurchase)
        assertEquals(result.originalLot.copy(originalShares = BigDecimal("110.00"), remainingShares = BigDecimal("70.00"),
            costBasis = BigDecimal("636.36")), result.proposedLot)
    }
    @Test fun `fewer platform shares increases allocated cost without inventing a new sale cash fee or date`() {
        val result = preview(input = facts.copy(shares = BigDecimal("90.00")))
        assertEquals(BigDecimal("50.00"), result.proposedLot.remainingShares); assertEquals(BigDecimal("555.56"), result.proposedLot.costBasis)
        assertEquals(BigDecimal("444.44"), result.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("5.56"), result.sales.single().proposedRecordedIncome)
        assertEquals(event.consumptions.single().shares, result.sales.single().proposedConsumption.shares)
        assertEquals(sale.redemptionFee, result.sales.single().sale.redemptionFee)
    }
    @Test fun `multiple sales retain sequence and closing residual conserves cost over many proposed share amounts`() {
        val snapshot = twoSales()
        val closed = preview(snapshot, input = facts.copy(shares = BigDecimal("60.00")))
        assertEquals(BigDecimal("0.00"), closed.proposedLot.remainingShares); assertEquals(BigDecimal("0.00"), closed.proposedLot.costBasis)
        assertEquals(listOf(BigDecimal("666.67"), BigDecimal("333.33")), closed.sales.map { it.proposedSaleCost })
        for (shares in listOf("60.01", "61.00", "75.31", "90.00", "99.99", "100.01", "110.00", "200.00")) {
            val result = preview(snapshot.copy(entries = snapshot.entries.reversed()), input = facts.copy(shares = BigDecimal(shares)))
            assertEquals(listOf("sale", "second"), result.sales.map { it.sale.id.value })
            assertEquals(lot.grossCashOut, result.sales.fold(result.proposedLot.costBasis) { total, impact -> total + impact.proposedSaleCost })
            assertEquals(BigDecimal(shares), result.sales.fold(result.proposedLot.remainingShares) { total, impact -> total + impact.proposedConsumption.shares })
        }
    }
    @Test fun `fully sold original batch may show newly evidenced residual but insufficient shares cannot remap other lots`() {
        val fullSale = sale.copy(shares = BigDecimal("100.00"))
        val full = history.copy(transactions = listOf(buy, fullSale), realLots = listOf(lot.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = listOf(history.entries.first(), event.copy(consumptions = listOf(LotConsumption(lot.id, BigDecimal("100.00"), BigDecimal("1000.00"))))))
        val result = preview(full)
        assertEquals(BigDecimal("10.00"), result.proposedLot.remainingShares); assertEquals(BigDecimal("90.91"), result.proposedLot.costBasis)
        assertEquals(BigDecimal("909.09"), result.sales.single().proposedSaleCost)
        assertFailsWith<IllegalArgumentException> { preview(full, input = facts.copy(shares = BigDecimal("99.99"))) }
        assertFailsWith<IllegalArgumentException> { preview(twoSales(), input = facts.copy(shares = BigDecimal("59.99"))) }
    }
    @Test fun `calculated recurring origin and estimated unknown income stay unchanged`() {
        val assumed = buy.copy(type = TransactionType.RECURRING_PURCHASE, origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null)
        val estimated = sale.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null)
        val result = preview(history.copy(transactions = listOf(assumed, estimated)), assumed)
        assertEquals(assumed, result.originalPurchase); assertEquals(estimated, result.sales.single().sale)
        assertNull(result.sales.single().originalRecordedIncome); assertNull(result.sales.single().proposedRecordedIncome)
    }
    @Test fun `changed money fee NAV confirmation payment no op precision sign time and identity reject`() {
        for (invalid in listOf(facts.copy(shares = BigDecimal.ZERO), facts.copy(shares = BigDecimal("110.001")), facts.copy(shares = lot.originalShares),
            facts.copy(actualCashPaid = BigDecimal("1001.00")), facts.copy(netAmount = BigDecimal("989.00")), facts.copy(purchaseFee = BigDecimal.ZERO),
            facts.copy(unitNav = BigDecimal("9.9001")), facts.copy(confirmationDate = date.plusDays(1)), facts.copy(paymentDate = date.minusDays(3))))
            assertFailsWith<IllegalArgumentException> { preview(input = invalid) }
        for (original in listOf(buy.copy(origin = TransactionOrigin.IMPORTED), buy.copy(type = TransactionType.DIVIDEND_REINVESTMENT),
            buy.copy(actualCashFlow = null, cashFlowDate = null), buy.copy(cashFlowDate = null), buy.copy(actualCashFlow = BigDecimal("1000.00"))))
            assertFailsWith<IllegalArgumentException> { preview(history.copy(transactions = listOf(original, sale)), original) }
        assertFailsWith<IllegalArgumentException> { preview(original = buy.copy(id = TransactionId("foreign"))) }
        assertFailsWith<IllegalArgumentException> { preview(history.copy(fundId = FundId("foreign"))) }
        assertFailsWith<IllegalArgumentException> { preview().validate(now.minusSeconds(1), today) }
        assertFailsWith<IllegalArgumentException> { preview().validate(now, date) }
        for (invalid in listOf(sale.copy(updatedAt = now.plusSeconds(1)), sale.copy(actualCashFlow = BigDecimal("-1.00")),
            sale.copy(actualCashFlow = BigDecimal("450.001")), sale.copy(settlementDate = today.plusDays(1)),
            sale.copy(settlementDate = null)))
            assertFailsWith<IllegalArgumentException> { preview(history.copy(transactions = listOf(buy, invalid))) }
    }
    @Test fun `unfinished inconsistent unconsumed and non proportional history reject without guessed repair`() {
        for (status in listOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.ABNORMAL)) {
            val pending = sale.copy(id = TransactionId("pending"), status = status, actualCashFlow = null)
            assertFailsWith<IllegalArgumentException> { preview(history.copy(transactions = history.transactions + pending)) }
        }
        assertFailsWith<IllegalArgumentException> { preview(history.copy(entries = history.entries.take(1))) }
        val unconsumed = history.copy(transactions = listOf(buy), entries = history.entries.take(1), realLots = listOf(lot))
        assertFailsWith<IllegalArgumentException> { preview(unconsumed) }
        val adjusted = history.copy(realLots = listOf(history.realLots.single().copy(costBasis = BigDecimal("601.00"))),
            entries = listOf(history.entries.first(), event.copy(consumptions = listOf(event.consumptions.single().copy(allocatedCostBasis = BigDecimal("399.00"))))))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(adjusted))
        assertFailsWith<IllegalArgumentException> { preview(adjusted) }
    }
    private fun withOther(confirmation: LocalDate): LedgerHistorySnapshot {
        val other = lot.copy(id = LotId("other-lot"), sourceTransactionId = TransactionId("other-buy"), confirmationDate = confirmation)
        val otherBuy = buy.copy(id = other.sourceTransactionId, confirmationDate = confirmation)
        return history.copy(transactions = history.transactions + otherBuy, realLots = history.realLots + other,
            entries = listOf(history.entries.first(), RealLotOpened(LedgerEntryId("other-open"), 2, other), event.copy(sequence = 3)))
    }
    @Test fun `competing eligible batches and later other batch sales stop while not yet eligible batch stays untouched`() {
        assertFailsWith<IllegalArgumentException> { preview(withOther(date)) }
        val multiple = withOther(date).let { snapshot -> snapshot.copy(
            realLots = snapshot.realLots.map { it.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("800.00")) },
            entries = snapshot.entries.dropLast(1) + event.copy(sequence = 3, consumptions = listOf(
                LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00")),
                LotConsumption(LotId("other-lot"), BigDecimal("20.00"), BigDecimal("200.00"))))) }
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(multiple))
        assertFailsWith<IllegalArgumentException> { preview(multiple) }
        val futureEligible = withOther(date.plusDays(4)); val result = preview(futureEligible)
        assertEquals(futureEligible, result.history); assertEquals(history.realLots.single(), result.originalLot)
        val otherSale = sale.copy(id = TransactionId("other-sale"), shares = BigDecimal("20.00"), tradeDate = date.plusDays(5), navDate = date.plusDays(5),
            confirmationDate = date.plusDays(5), settlementDate = date.plusDays(5))
        val later = futureEligible.copy(transactions = futureEligible.transactions + otherSale,
            realLots = futureEligible.realLots.map { if (it.id.value == "other-lot") it.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("800.00")) else it },
            entries = futureEligible.entries + LotsRedeemed(LedgerEntryId("other-sale-event"), 4, id,
                listOf(LotConsumption(LotId("other-lot"), BigDecimal("20.00"), BigDecimal("200.00"))), otherSale.id))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(later))
        assertFailsWith<IllegalArgumentException> { preview(later) }
    }
    @Test fun `existing cost correction uses effective costs once and does not replace original events`() {
        val costFacts = ConsumedPurchaseCostFacts(BigDecimal("1010.00"), lot.netSubscriptionAmount, BigDecimal("20.00"),
            lot.originalShares, lot.unitNav, facts.paymentDate, date)
        val cost = ConsumedPurchaseCostCorrection.prepare("prior-cost", ConsumedPurchaseCostImpactPreview.prepare(fund, history, buy, costFacts, now, today), today)
        val saved = history.copy(transactions = listOf(cost.confirmed, sale), realLots = listOf(cost.entry.correctedLot),
            entries = history.entries + cost.entry.copy(sequence = 3))
        val result = preview(saved, cost.confirmed, facts.copy(actualCashPaid = BigDecimal("1010.00"), purchaseFee = BigDecimal("20.00")))
        assertEquals(BigDecimal("404.00"), result.sales.single().originalSaleCost); assertEquals(BigDecimal("367.27"), result.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("642.73"), result.proposedLot.costBasis); assertEquals(event, result.sales.single().event)
        assertEquals(saved, result.history)
    }
    @Test fun `prior unconsumed share correction defines original capacity without double applying the opening`() {
        val corrected = lot.copy(originalShares = BigDecimal("120.00"), remainingShares = BigDecimal("120.00"))
        val original = buy.copy(shares = corrected.originalShares)
        val snapshot = history.copy(transactions = listOf(original, sale),
            realLots = listOf(corrected.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("666.67"))),
            entries = listOf(history.entries.first(), UnconsumedPurchaseLotCorrected(LedgerEntryId("prior-shares"), 2, lot, corrected),
                event.copy(sequence = 3, consumptions = listOf(event.consumptions.single().copy(allocatedCostBasis = BigDecimal("333.33"))))))
        val result = preview(snapshot, original, facts.copy(shares = BigDecimal("130.00")))
        assertEquals(BigDecimal("90.00"), result.proposedLot.remainingShares)
        assertEquals(BigDecimal("307.69"), result.sales.single().proposedSaleCost)
        assertEquals(BigDecimal("692.31"), result.proposedLot.costBasis); assertEquals(snapshot, result.history)
    }
    private fun saved(proof: ConsumedPurchaseShareCorrection): LedgerHistorySnapshot = proof.preview.history.copy(
        transactions = proof.preview.history.transactions.map { if (it.id == proof.confirmed.id) proof.confirmed else it },
        realLots = proof.preview.history.realLots.map { if (it.id == proof.entry.expectedLot.id) proof.entry.correctedLot else it },
        entries = proof.preview.history.entries + proof.entry.copy(sequence = proof.preview.history.entries.maxOf { it.sequence } + 1))

    @Test fun `reviewed shares append one event and final audit without modifying purchase money or sale facts`() {
        val proof = ConsumedPurchaseShareCorrection.prepare("share-review", preview(), today)
        proof.validate(now, today)
        assertEquals(listOf(TransactionCorrectionField.SHARES), proof.audits.map { it.field })
        assertEquals(buy.copy(shares = BigDecimal("110.00"), isManualCorrected = true, updatedAt = now), proof.confirmed)
        val after = saved(proof)
        val inspected = assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(after))
        assertEquals(BigDecimal("363.64"), inspected.redemptionCosts.single().allocatedCost)
        assertEquals(history.entries, after.entries.dropLast(1)); assertEquals(sale, after.transactions.last())
        assertEquals(BigDecimal("86.36"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after, today, precision)).realizedReturn)
    }
    @Test fun `assumed calculated source explicitly confirms matching cash and date retaining origin rule and unknown sale income`() {
        val assumed = buy.copy(type = TransactionType.RECURRING_PURCHASE, origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null)
        val estimated = sale.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, settlementDate = null)
        val proof = ConsumedPurchaseShareCorrection.prepare("assumed-share", preview(history.copy(transactions = listOf(assumed, estimated)), assumed), today)
        assertEquals(listOf(TransactionCorrectionField.SHARES, TransactionCorrectionField.ACTUAL_CASH_FLOW, TransactionCorrectionField.CASH_FLOW_DATE), proof.audits.map { it.field })
        assertEquals(TransactionOrigin.AUTO_PLAN_ASSUMED, proof.confirmed.origin); assertEquals(buy.ruleVersionId, proof.confirmed.ruleVersionId)
        assertEquals(buy.actualCashFlow, proof.confirmed.actualCashFlow); assertEquals(facts.paymentDate, proof.confirmed.cashFlowDate)
        assertNull(proof.preview.sales.single().proposedRecordedIncome)
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(saved(proof)))
    }
    @Test fun `replay rejects omitted altered or reordered allocations stale source and wrong residual conservation`() {
        val proof = ConsumedPurchaseShareCorrection.prepare("many", preview(twoSales()), today)
        val entry = proof.entry
        for (invalid in listOf(entry.copy(allocations = entry.allocations.take(1)), entry.copy(allocations = entry.allocations.reversed()),
            entry.copy(correctedLot = entry.correctedLot.copy(costBasis = BigDecimal("1.00"))),
            entry.copy(allocations = entry.allocations.mapIndexed { index, revision -> revision.copy(transactionId = TransactionId("wrong-$index")) }),
            entry.copy(expectedLot = entry.expectedLot.copy(costBasis = BigDecimal("1.00"))))) {
            assertFailsWith<IllegalArgumentException> { LedgerRebuilder.rebuild(proof.preview.history.entries + invalid.copy(sequence = 4)) }
        }
        assertFailsWith<IllegalArgumentException> { entry.copy(correctedLot = entry.correctedLot.copy(purchaseFee = BigDecimal("20.00"))) }
        for (invalid in listOf(proof.copy(audits = emptyList()), proof.copy(confirmed = proof.confirmed.copy(netAmount = BigDecimal("1.00"))),
            proof.copy(entry = entry.copy(recordedAt = now.plusSeconds(1)))))
            assertFailsWith<IllegalArgumentException> { invalid.validate(now, today) }
    }
    @Test fun `later sale consumes revised remaining shares and cost once and subsequent share cost cycles remain consistent`() {
        val first = ConsumedPurchaseShareCorrection.prepare("first", preview(), today)
        val revised = saved(first)
        val later = sale.copy(id = TransactionId("later-sale"), shares = BigDecimal("20.00"), tradeDate = date.plusDays(3), navDate = date.plusDays(3), confirmationDate = date.plusDays(3))
        val laterEvent = event.copy(id = LedgerEntryId("later-event"), sequence = 4, sourceTransactionId = later.id,
            consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("181.82"))))
        val afterSale = revised.copy(transactions = revised.transactions + later,
            realLots = listOf(first.entry.correctedLot.copy(remainingShares = BigDecimal("50.00"), costBasis = BigDecimal("454.54"))), entries = revised.entries + laterEvent)
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(afterSale))
        val second = ConsumedPurchaseShareCorrection.prepare("second", preview(afterSale, first.confirmed, facts.copy(shares = BigDecimal("120.00"))), today)
        val secondSaved = saved(second)
        assertEquals(BigDecimal("60.00"), second.entry.correctedLot.remainingShares); assertEquals(BigDecimal("500.00"), second.entry.correctedLot.costBasis)
        val cost = ConsumedPurchaseCostCorrection.prepare("after-share", ConsumedPurchaseCostImpactPreview.prepare(fund, secondSaved, second.confirmed,
            ConsumedPurchaseCostFacts(BigDecimal("1010.00"), facts.netAmount, BigDecimal("20.00"), BigDecimal("120.00"), facts.unitNav, facts.paymentDate, facts.confirmationDate), now, today), today)
        val costSaved = secondSaved.copy(transactions = secondSaved.transactions.map { if (it.id == buy.id) cost.confirmed else it },
            realLots = listOf(cost.entry.correctedLot), entries = secondSaved.entries + cost.entry.copy(sequence = 6))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(costSaved))
        val third = ConsumedPurchaseShareCorrection.prepare("after-cost", preview(costSaved, cost.confirmed,
            facts.copy(actualCashPaid = BigDecimal("1010.00"), purchaseFee = BigDecimal("20.00"), shares = BigDecimal("110.00"))), today)
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(saved(third)))
        assertEquals(history.entries, saved(third).entries.take(2))
    }
    @Test fun `fully consumed original may have explicitly revised residual and baseline cannot be rewritten`() {
        val full = history.copy(transactions = listOf(buy, sale.copy(shares = BigDecimal("100.00"))),
            realLots = listOf(lot.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = listOf(history.entries.first(), event.copy(consumptions = listOf(LotConsumption(lot.id, BigDecimal("100.00"), BigDecimal("1000.00"))))))
        val proof = ConsumedPurchaseShareCorrection.prepare("full", preview(full), today)
        assertEquals(BigDecimal("10.00"), proof.entry.correctedLot.remainingShares)
        assertEquals(BigDecimal("90.91"), proof.entry.correctedLot.costBasis)
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(saved(proof)))
        val nav = NavRecord(id, date.plusDays(4), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("official"), now)
        val baseline = TrackingBaseline.prepare(history, nav, precision, now, today)
        val after = saved(ConsumedPurchaseShareCorrection.prepare("baseline", preview(), today))
        assertEquals(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED,
            assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(LedgerTrackingReturnAssembler.assemble(after, baseline, nav, today, precision)).reason)
    }
    @Test fun `tampered omitted or changed evidence cannot validate and stale snapshots never match`() {
        val result = preview()
        for (tampered in listOf(result.copy(sales = emptyList()), result.copy(proposedLot = result.proposedLot.copy(costBasis = BigDecimal("0.00"))),
            result.copy(sales = listOf(result.sales.single().copy(proposedRecordedIncome = BigDecimal("999.00")))),
            result.copy(facts = facts.copy(shares = BigDecimal("120.00")))))
            assertFailsWith<IllegalArgumentException> { tampered.validate(now, today) }
        assertTrue(result.isBasedOn(fund, history))
        assertFalse(result.isBasedOn(fund.copy(precision = precision.copy(shareScale = 3)), history))
        assertFalse(result.isBasedOn(fund, history.copy(transactions = listOf(buy, sale.copy(actualCashFlow = BigDecimal("460.00"))))))
    }
}
