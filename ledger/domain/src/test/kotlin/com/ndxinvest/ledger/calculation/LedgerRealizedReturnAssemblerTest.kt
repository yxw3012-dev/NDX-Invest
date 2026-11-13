package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

class LedgerRealizedReturnAssemblerTest {
    private val fund = FundId("fund")
    private val date = LocalDate.parse("2026-09-14")
    private val now = Instant.parse("2026-09-20T08:00:00Z")
    private val asOf = date.plusDays(6)
    private val precision = FundPrecision(2, 2, 4, RoundingMode.UNNECESSARY)
    private val lot = Lot(LotId("lot"), fund, TransactionId("buy"), LotType.REAL, date,
        BigDecimal("100.00"), BigDecimal("100.00"), BigDecimal("1000.00"), BigDecimal("990.00"),
        BigDecimal("10.00"), BigDecimal("1000.00"), BigDecimal("9.9000"))
    private val buy = LedgerTransaction(lot.sourceTransactionId, fund, TransactionType.PURCHASE,
        TransactionStatus.CONFIRMED_ACTUAL, TransactionOrigin.USER_ENTERED, DataAuthority.USER_OR_PLATFORM_FINAL,
        now.minusSeconds(86400 * 10), date.minusDays(3), date.minusDays(3), date, null, RuleVersionId("rule"),
        grossAmount = lot.grossCashOut, netAmount = lot.netSubscriptionAmount, purchaseFee = lot.purchaseFee,
        shares = lot.originalShares, unitNav = lot.unitNav, actualCashFlow = BigDecimal("-1000.00"), cashFlowDate = date.minusDays(4))
    private val sale = buy.copy(id = TransactionId("sale"), type = TransactionType.REDEMPTION,
        tradeDate = date.plusDays(3), navDate = date.plusDays(3), confirmationDate = date.plusDays(4),
        shares = BigDecimal("40.00"), grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("392.04"),
        purchaseFee = null, redemptionFee = BigDecimal("3.96"), actualCashFlow = BigDecimal("450.00"), cashFlowDate = date.plusDays(5))
    private val opening = RealLotOpened(LedgerEntryId("opening"), 1, lot)
    private val redemption = LotsRedeemed(LedgerEntryId("sale-event"), 2, fund,
        listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
    private val remaining = lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
    private fun snapshot() = LedgerHistorySnapshot(fund, listOf(buy, sale), listOf(remaining), emptyList(), listOf(opening, redemption))
    private fun complete(input: LedgerHistorySnapshot) = assertIs<LedgerRealizedReturnAssembly.Complete>(
        LedgerRealizedReturnAssembler.assemble(input, asOf, precision))
    private fun reasons(input: LedgerHistorySnapshot): Set<RealizedReturnGapReason> =
        assertIs<LedgerRealizedReturnAssembly.Incomplete>(LedgerRealizedReturnAssembler.assemble(input, asOf, precision))
            .gaps.map { it.reason }.toSet()
    private fun dividend(amount: String = "25.00") = sale.copy(id = TransactionId("dividend"),
        type = TransactionType.CASH_DIVIDEND, shares = null, unitNav = null, grossAmount = null,
        netAmount = null, redemptionFee = null, actualCashFlow = BigDecimal(amount))

    @Test
    fun `final proceeds less verified cost plus cash dividend never subtracts fees twice`() {
        val input = snapshot().copy(transactions = listOf(sale, dividend(), buy))
        val before = input.copy()
        val result = complete(input)
        assertEquals(BigDecimal("50.00"), result.redemptionIncome)
        assertEquals(BigDecimal("25.00"), result.cashDividendIncome)
        assertEquals(BigDecimal("75.00"), result.realizedReturn)
        assertEquals(RealizedReturnScope.RECORDED_TRANSACTIONS, result.scope)
        assertFalse(result.hasLegacyHistory)
        assertEquals(BigDecimal("400.00"), result.contributions.first { it.type == TransactionType.REDEMPTION }.allocatedCost)
        assertEquals(before, input)
        assertEquals(result, complete(input))
    }

    @Test
    fun `zero actual sale proceeds are an explicit loss and final purchase only has known zero income`() {
        assertEquals(BigDecimal("-400.00"), complete(snapshot().copy(transactions = listOf(buy,
            sale.copy(actualCashFlow = BigDecimal.ZERO)))).realizedReturn)
        val onlyBuy = snapshot().copy(transactions = listOf(buy), realLots = listOf(lot), entries = listOf(opening))
        assertEquals(BigDecimal("0.00"), complete(onlyBuy).realizedReturn)
        assertTrue(complete(onlyBuy).contributions.isEmpty())
        assertEquals(setOf(RealizedReturnGapReason.MISSING_RECORDED_HISTORY), reasons(
            LedgerHistorySnapshot(fund, emptyList(), emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `missing cash date or unfinished source prevents a partial known income`() {
        val input = snapshot().copy(transactions = listOf(buy, sale, dividend()))
        assertEquals(setOf(RealizedReturnGapReason.MISSING_ACTUAL_CASH), reasons(input.copy(transactions =
            listOf(buy, sale.copy(actualCashFlow = null, cashFlowDate = null), dividend()))))
        assertEquals(setOf(RealizedReturnGapReason.MISSING_CASH_DATE), reasons(input.copy(transactions =
            listOf(buy, sale.copy(cashFlowDate = null), dividend()))))
        val calculated = buy.copy(status = TransactionStatus.CALCULATED, authority = DataAuthority.APP_RULE_CALCULATION,
            actualCashFlow = null, cashFlowDate = null)
        assertEquals(setOf(RealizedReturnGapReason.UNFINISHED_TRANSACTION), reasons(input.copy(transactions = listOf(calculated, sale, dividend()))))
        assertEquals(setOf(RealizedReturnGapReason.PURCHASE_COST_CASH_MISMATCH), reasons(input.copy(transactions =
            listOf(buy.copy(actualCashFlow = BigDecimal("-999.00")), sale, dividend()))))
    }

    @Test
    fun `independent payment date and old explicit settlement fallback do not use inferred dates`() {
        assertEquals(BigDecimal("50.00"), complete(snapshot()).realizedReturn)
        val oldSale = sale.copy(cashFlowDate = null, settlementDate = sale.cashFlowDate)
        assertEquals(BigDecimal("50.00"), complete(snapshot().copy(transactions = listOf(buy, oldSale))).realizedReturn)
        val explicit = sale.copy(settlementDate = asOf.plusDays(1))
        assertEquals(BigDecimal("50.00"), complete(snapshot().copy(transactions = listOf(buy, explicit))).realizedReturn)
        assertEquals(setOf(RealizedReturnGapReason.CASH_DATE_OUT_OF_RANGE), reasons(snapshot().copy(transactions =
            listOf(buy, sale.copy(cashFlowDate = sale.tradeDate.minusDays(1))))))
    }

    @Test
    fun `future cash and trade nav or confirmation cannot become current income`() {
        listOf(sale.copy(cashFlowDate = asOf.plusDays(1)), sale.copy(tradeDate = asOf.plusDays(1),
            navDate = asOf.plusDays(1), confirmationDate = asOf.plusDays(2), cashFlowDate = asOf.plusDays(3)),
            sale.copy(navDate = asOf.plusDays(1)), sale.copy(confirmationDate = asOf.plusDays(1))).forEach { later ->
            val actual = reasons(snapshot().copy(transactions = listOf(buy, later)))
            assertTrue(actual.single() in setOf(RealizedReturnGapReason.CASH_DATE_OUT_OF_RANGE, RealizedReturnGapReason.TRANSACTION_AFTER_AS_OF))
        }
    }

    @Test
    fun `cash signs and over precision fail closed without rounding while trailing zeros are harmless`() {
        listOf("-1.00", "450.001").forEach { cash ->
            assertEquals(setOf(RealizedReturnGapReason.INVALID_CASH_SIGN_OR_PRECISION), reasons(snapshot().copy(
                transactions = listOf(buy, sale.copy(actualCashFlow = BigDecimal(cash))))))
        }
        assertEquals(BigDecimal("50.00"), complete(snapshot().copy(transactions = listOf(buy,
            sale.copy(actualCashFlow = BigDecimal("450.00000"))))).realizedReturn)
        assertEquals(setOf(RealizedReturnGapReason.INVALID_CASH_SIGN_OR_PRECISION), reasons(snapshot().copy(
            transactions = listOf(buy, sale, dividend("0.00")))))
    }

    @Test
    fun `each stored allocation must have exact precision even when opposing errors cancel in total`() {
        val secondLot = lot.copy(id = LotId("second"), sourceTransactionId = TransactionId("second-buy"))
        val secondBuy = buy.copy(id = secondLot.sourceTransactionId)
        val consumes = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.001")),
            LotConsumption(secondLot.id, BigDecimal("20.00"), BigDecimal("199.999")))
        val event = redemption.copy(sequence = 3, consumptions = consumes)
        val lots = listOf(lot.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("799.999")),
            secondLot.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("800.001")))
        val input = snapshot().copy(transactions = listOf(buy, secondBuy, sale), realLots = lots,
            entries = listOf(opening, RealLotOpened(LedgerEntryId("second-open"), 2, secondLot), event))
        assertIs<LedgerHistoryInspection.Consistent>(LedgerHistoryInspector.inspect(input))
        assertEquals(setOf(RealizedReturnGapReason.INVALID_ALLOCATION_PRECISION), reasons(input))
        val fractional = snapshot().copy(transactions = listOf(buy, sale.copy(shares = BigDecimal("40.001"))),
            realLots = listOf(remaining.copy(remainingShares = BigDecimal("59.999"))),
            entries = listOf(opening, redemption.copy(consumptions = listOf(
                LotConsumption(lot.id, BigDecimal("40.001"), BigDecimal("400.00"))))))
        assertEquals(setOf(RealizedReturnGapReason.INVALID_ALLOCATION_PRECISION), reasons(fractional))
    }

    @Test
    fun `unlinked duplicate or corrupt stored history preserves review reasons instead of guessing profit`() {
        listOf(snapshot().copy(entries = listOf(opening, redemption.copy(sourceTransactionId = null))),
            snapshot().copy(realLots = listOf(remaining.copy(costBasis = BigDecimal("599.00")))),
            snapshot().copy(transactions = listOf(buy, sale, sale))).forEach { input ->
            val result = assertIs<LedgerRealizedReturnAssembly.Incomplete>(LedgerRealizedReturnAssembler.assemble(input, asOf, precision))
            assertEquals(listOf(RealizedReturnGap(null, RealizedReturnGapReason.HISTORY_REVIEW_REQUIRED)), result.gaps)
            assertEquals(assertIs<LedgerHistoryInspection.NeedsReview>(LedgerHistoryInspector.inspect(input)).issues, result.historyIssues)
        }
    }

    @Test
    fun `reinvestment cost joins sale cost but reinvested dividend is not external cash income`() {
        val reinvested = Lot(LotId("reinvest"), fund, TransactionId("reinvest-buy"), LotType.REAL, date.plusDays(1),
            BigDecimal("5.00"), BigDecimal("5.00"), BigDecimal("0.00"), BigDecimal("50.00"), BigDecimal("0.00"),
            BigDecimal("50.00"), BigDecimal("10.0000"))
        val reinvestment = buy.copy(id = reinvested.sourceTransactionId, type = TransactionType.DIVIDEND_REINVESTMENT,
            confirmationDate = reinvested.confirmationDate, grossAmount = BigDecimal("50.00"), netAmount = BigDecimal("50.00"),
            purchaseFee = BigDecimal("0.00"), unitNav = reinvested.unitNav, shares = reinvested.originalShares,
            actualCashFlow = null, cashFlowDate = null)
        val input = snapshot().copy(transactions = listOf(buy, reinvestment, sale.copy(shares = BigDecimal("25.00"))),
            realLots = listOf(lot.copy(remainingShares = BigDecimal("80.00"), costBasis = BigDecimal("800.00")),
                reinvested.copy(remainingShares = BigDecimal("0.00"), costBasis = BigDecimal("0.00"))),
            entries = listOf(opening, RealLotOpened(LedgerEntryId("reinvest-open"), 2, reinvested),
                redemption.copy(sequence = 3, consumptions = listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00")),
                    LotConsumption(reinvested.id, BigDecimal("5.00"), BigDecimal("50.00"))))))
        val result = complete(input)
        assertEquals(BigDecimal("200.00"), result.realizedReturn)
        assertEquals(BigDecimal("0.00"), result.cashDividendIncome)
        assertEquals(1, result.contributions.size)
    }

    @Test
    fun `Legacy profit covers only recorded sales at explicit initial cost not fabricated lifetime history`() {
        val legacy = LegacyLot(LotId("legacy"), fund, BigDecimal("100.00"), BigDecimal("500.00"), date.minusDays(5))
        val imported = buy.copy(type = TransactionType.ADJUSTMENT, origin = TransactionOrigin.IMPORTED,
            shares = null, unitNav = null, grossAmount = null, netAmount = null, purchaseFee = null,
            actualCashFlow = null, cashFlowDate = null)
        val input = LedgerHistorySnapshot(fund, listOf(imported, sale.copy(actualCashFlow = BigDecimal("250.00"))),
            emptyList(), listOf(legacy.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("300.00"))),
            listOf(LegacyLotOpened(LedgerEntryId("legacy-open"), 1, legacy), LegacyLotRedeemed(LedgerEntryId("legacy-sale"),
                2, fund, LotConsumption(legacy.id, BigDecimal("40.00"), BigDecimal("200.00")), sale.id)))
        val result = complete(input)
        assertEquals(BigDecimal("50.00"), result.realizedReturn)
        assertTrue(result.hasLegacyHistory)
        assertEquals(RealizedReturnScope.RECORDED_TRANSACTIONS, result.scope)
        assertEquals(LotType.LEGACY, result.contributions.single().lotType)
        assertNull(input.transactions.first().actualCashFlow)
    }

    @Test
    fun `cancelled and unexecuted requests are ignored but unsupported adjustments block exact income`() {
        val ignored = sale.copy(id = TransactionId("cancelled"), status = TransactionStatus.CANCELLED,
            actualCashFlow = null, cashFlowDate = null)
        assertEquals(BigDecimal("50.00"), complete(snapshot().copy(transactions = listOf(buy, sale, ignored,
            ignored.copy(id = TransactionId("not-executed"), status = TransactionStatus.NOT_EXECUTED)))).realizedReturn)
        val adjustment = ignored.copy(id = TransactionId("adjustment"), type = TransactionType.ADJUSTMENT,
            status = TransactionStatus.CONFIRMED_ACTUAL)
        assertEquals(setOf(RealizedReturnGapReason.UNSUPPORTED_ADJUSTMENT), reasons(snapshot().copy(transactions = listOf(buy, sale, adjustment))))
    }
}
