package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CashFlowKind
import com.ndxinvest.ledger.model.DatedCashFlow
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.HistoryScope
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class LedgerCashFlowAssemblerTest {
    private val fundId = FundId("cash-flow-fund")
    private val precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP)
    private val valuationDate = LocalDate.parse("2026-04-01")

    @Test
    fun `estimated purchase amount never becomes an actual external investment`() {
        val purchase = transaction("purchase", TransactionType.PURCHASE, null, "2026-01-01")
            .copy(grossAmount = BigDecimal("1000.00"), requestedAmount = BigDecimal("1000.00"))

        val result = LedgerCashFlowAssembler.assemble(
            fundId, listOf(purchase), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        )

        val incomplete = assertIs<LedgerCashFlowAssembly.Incomplete>(result)
        assertEquals(listOf(CashFlowGap(purchase.id, CashFlowGapReason.MISSING_ACTUAL_CASH)), incomplete.gaps)
    }

    @Test
    fun `only final dated purchase sale and cash dividend form cumulative return inputs`() {
        val transactions = listOf(
            transaction("buy", TransactionType.PURCHASE, "-1000.00", "2026-01-01"),
            transaction("sell", TransactionType.REDEMPTION, "250.00", "2026-02-01"),
            transaction("dividend", TransactionType.CASH_DIVIDEND, "25.00", "2026-03-01"),
            transaction("reinvest", TransactionType.DIVIDEND_REINVESTMENT, null, null),
        )

        val result = LedgerCashFlowAssembler.assemble(
            fundId, transactions, BigDecimal("900.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        )

        val complete = assertIs<LedgerCashFlowAssembly.Complete>(result)
        assertEquals(BigDecimal("1000.00"), complete.performance.externalCashInvested)
        assertEquals(BigDecimal("275.00"), complete.performance.externalCashReturned)
        assertEquals(BigDecimal("175.00"), complete.performance.cumulativeReturn)
        assertEquals(
            listOf(CashFlowKind.PURCHASE, CashFlowKind.REDEMPTION, CashFlowKind.CASH_DIVIDEND, CashFlowKind.TERMINAL_VALUE),
            complete.cashFlows.map { it.kind },
        )
        assertEquals(BigDecimal("250.00"), complete.cashFlows[1].amount)
    }

    @Test
    fun `Legacy history cannot masquerade as full history and tracking needs an explicit baseline`() {
        val sale = transaction("legacy-sale", TransactionType.REDEMPTION, "250.00", "2026-02-01")
        val full = LedgerCashFlowAssembler.assemble(
            fundId, listOf(sale), BigDecimal("300.00"), valuationDate,
            HistoryScope.FULL_HISTORY, true, precision = precision,
        )
        assertEquals(
            CashFlowGapReason.LEGACY_HISTORY_REQUIRES_BASELINE,
            assertIs<LedgerCashFlowAssembly.Incomplete>(full).gaps.single().reason,
        )
        val missingBaseline = LedgerCashFlowAssembler.assemble(
            fundId, listOf(sale), BigDecimal("300.00"), valuationDate,
            HistoryScope.TRACKING_PERIOD, true, precision = precision,
        )
        assertEquals(
            CashFlowGapReason.TRACKING_BASELINE_REQUIRED,
            assertIs<LedgerCashFlowAssembly.Incomplete>(missingBaseline).gaps.single().reason,
        )
        val baseline = DatedCashFlow(
            LocalDate.parse("2026-01-01"), BigDecimal("-500.00"), CashFlowKind.TRACKING_BASELINE,
        )
        val tracked = LedgerCashFlowAssembler.assemble(
            fundId, listOf(sale), BigDecimal("300.00"), valuationDate,
            HistoryScope.TRACKING_PERIOD, true, baseline, precision,
        )
        val complete = assertIs<LedgerCashFlowAssembly.Complete>(tracked)
        assertEquals(HistoryScope.TRACKING_PERIOD, complete.historyScope)
        assertEquals(BigDecimal("50.00"), complete.performance.cumulativeReturn)
        assertEquals(CashFlowKind.TRACKING_BASELINE, complete.cashFlows.first().kind)
    }

    @Test
    fun `unfinished sale and future cash date prevent a partial performance total`() {
        val pending = transaction("pending", TransactionType.REDEMPTION, null, null)
            .copy(status = TransactionStatus.PENDING_CALC, authority = DataAuthority.APP_RULE_CALCULATION)
        val future = transaction("future", TransactionType.CASH_DIVIDEND, "25.00", "2026-04-02")

        val result = LedgerCashFlowAssembler.assemble(
            fundId, listOf(pending, future), BigDecimal("300.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        )

        assertEquals(
            listOf(
                CashFlowGap(pending.id, CashFlowGapReason.UNFINISHED_TRANSACTION),
                CashFlowGap(future.id, CashFlowGapReason.CASH_AFTER_VALUATION),
            ),
            assertIs<LedgerCashFlowAssembly.Incomplete>(result).gaps,
        )
    }

    @Test
    fun `zero actual redemption cash is a known loss without a zero dated flow`() {
        val transactions = listOf(
            transaction("buy", TransactionType.PURCHASE, "-1000.00", "2026-01-01"),
            transaction("sell", TransactionType.REDEMPTION, "0.00", "2026-02-01"),
        )
        val result = LedgerCashFlowAssembler.assemble(
            fundId, transactions, BigDecimal.ZERO, valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        )

        val complete = assertIs<LedgerCashFlowAssembly.Complete>(result)
        assertEquals(BigDecimal("-1000.00"), complete.performance.cumulativeReturn)
        assertEquals(listOf(CashFlowKind.PURCHASE), complete.cashFlows.map { it.kind })
    }

    @Test
    fun `an empty fund or isolated dividend cannot claim complete investment history`() {
        val empty = LedgerCashFlowAssembler.assemble(
            fundId, emptyList(), BigDecimal.ZERO, valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        )
        val dividendOnly = LedgerCashFlowAssembler.assemble(
            fundId, listOf(transaction("dividend", TransactionType.CASH_DIVIDEND, "25.00", "2026-03-01")),
            BigDecimal.ZERO, valuationDate, HistoryScope.FULL_HISTORY, false, precision = precision,
        )
        assertEquals(CashFlowGapReason.MISSING_INVESTMENT_HISTORY,
            assertIs<LedgerCashFlowAssembly.Incomplete>(empty).gaps.single().reason)
        assertEquals(CashFlowGapReason.MISSING_INVESTMENT_HISTORY,
            assertIs<LedgerCashFlowAssembly.Incomplete>(dividendOnly).gaps.single().reason)
    }

    @Test
    fun `missing dates invalid cash signs and excess precision are explicit gaps`() {
        val cases = listOf(
            transaction("no-date", TransactionType.PURCHASE, "-1000.00", null) to
                CashFlowGapReason.MISSING_CASH_DATE,
            transaction("positive-buy", TransactionType.PURCHASE, "1000.00", "2026-01-01") to
                CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION,
            transaction("negative-sell", TransactionType.REDEMPTION, "-250.00", "2026-02-01") to
                CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION,
            transaction("zero-dividend", TransactionType.CASH_DIVIDEND, "0.00", "2026-02-01") to
                CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION,
            transaction("precise-buy", TransactionType.PURCHASE, "-1000.001", "2026-01-01") to
                CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION,
            transaction("cash-reinvest", TransactionType.DIVIDEND_REINVESTMENT, "25.00", "2026-02-01") to
                CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION,
            transaction("adjustment", TransactionType.ADJUSTMENT, "25.00", "2026-02-01") to
                CashFlowGapReason.UNSUPPORTED_ADJUSTMENT,
        )
        cases.forEach { (transaction, reason) ->
            val result = LedgerCashFlowAssembler.assemble(
                fundId, listOf(transaction), BigDecimal("1100.00"), valuationDate,
                HistoryScope.FULL_HISTORY, false, precision = precision,
            )
            assertEquals(CashFlowGap(transaction.id, reason),
                assertIs<LedgerCashFlowAssembly.Incomplete>(result).gaps.single())
        }
    }

    @Test
    fun `cancelled unexecuted and imported history do not become new actual cash`() {
        val purchase = transaction("buy", TransactionType.PURCHASE, "-1000.00", "2026-01-01")
        val cancelled = transaction("cancelled", TransactionType.REDEMPTION, null, null)
            .copy(status = TransactionStatus.CANCELLED)
        val unexecuted = cancelled.copy(id = TransactionId("not-executed"), status = TransactionStatus.NOT_EXECUTED)
        val complete = assertIs<LedgerCashFlowAssembly.Complete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(purchase, cancelled, unexecuted), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        ))
        assertEquals(BigDecimal("100.00"), complete.performance.cumulativeReturn)
        val imported = purchase.copy(origin = TransactionOrigin.IMPORTED)
        val incomplete = assertIs<LedgerCashFlowAssembly.Incomplete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(imported), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        ))
        assertEquals(CashFlowGapReason.LEGACY_HISTORY_REQUIRES_BASELINE, incomplete.gaps.single().reason)
    }

    @Test
    fun `over precise tracking baseline is rejected rather than silently rounded`() {
        val baseline = DatedCashFlow(
            LocalDate.parse("2026-01-01"), BigDecimal("-500.001"), CashFlowKind.TRACKING_BASELINE,
        )
        val result = LedgerCashFlowAssembler.assemble(
            fundId, emptyList(), BigDecimal("550.00"), valuationDate,
            HistoryScope.TRACKING_PERIOD, true, baseline, precision,
        )
        assertEquals(CashFlowGapReason.INVALID_TRACKING_BASELINE,
            assertIs<LedgerCashFlowAssembly.Incomplete>(result).gaps.single().reason)
    }

    @Test
    fun `reinvestment later than valuation cannot enter an earlier terminal value`() {
        val reinvestment = transaction("reinvest", TransactionType.DIVIDEND_REINVESTMENT, null, null)
        listOf(
            reinvestment.copy(tradeDate = valuationDate.plusDays(1)),
            reinvestment.copy(navDate = valuationDate.plusDays(1)),
            reinvestment.copy(confirmationDate = valuationDate.plusDays(1)),
        ).forEach { future ->
            val result = LedgerCashFlowAssembler.assemble(
                fundId, listOf(future), BigDecimal("550.00"), valuationDate,
                HistoryScope.FULL_HISTORY, false, precision = precision,
            )
            assertEquals(CashFlowGap(future.id, CashFlowGapReason.TRANSACTION_AFTER_VALUATION),
                assertIs<LedgerCashFlowAssembly.Incomplete>(result).gaps.single())
        }
    }

    @Test
    fun `unnecessary rounding still reports imprecise cash as a gap rather than throwing`() {
        val noRounding = precision.copy(roundingMode = RoundingMode.UNNECESSARY)
        val purchase = transaction("buy", TransactionType.PURCHASE, "-1000.001", "2026-01-01")
        val incomplete = assertIs<LedgerCashFlowAssembly.Incomplete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(purchase), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = noRounding,
        ))
        assertEquals(CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION, incomplete.gaps.single().reason)
        val baseline = DatedCashFlow(
            LocalDate.parse("2026-01-01"), BigDecimal("-1000.001"), CashFlowKind.TRACKING_BASELINE,
        )
        val invalidBaseline = assertIs<LedgerCashFlowAssembly.Incomplete>(LedgerCashFlowAssembler.assemble(
            fundId, emptyList(), BigDecimal("1100.00"), valuationDate,
            HistoryScope.TRACKING_PERIOD, true, baseline, noRounding,
        ))
        assertEquals(CashFlowGapReason.INVALID_TRACKING_BASELINE, invalidBaseline.gaps.single().reason)
        val complete = assertIs<LedgerCashFlowAssembly.Complete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(purchase.copy(actualCashFlow = BigDecimal("-1000.0000"))),
            BigDecimal("1100.00"), valuationDate, HistoryScope.FULL_HISTORY, false, precision = noRounding,
        ))
        assertEquals(BigDecimal("100.00"), complete.performance.cumulativeReturn)
    }

    @Test
    fun `duplicate identities cannot double count a cash flow`() {
        val purchase = transaction("buy", TransactionType.PURCHASE, "-1000.00", "2026-01-01")
        assertFailsWith<IllegalArgumentException> {
            LedgerCashFlowAssembler.assemble(
                fundId, listOf(purchase, purchase), BigDecimal("1100.00"), valuationDate,
                HistoryScope.FULL_HISTORY, false, precision = precision,
            )
        }
    }

    @Test
    fun `actual purchase cash uses its own pre trade date instead of settlement or confirmation`() {
        val paidDate = LocalDate.parse("2026-01-01")
        val purchase = transaction("pre-trade", TransactionType.PURCHASE, "-1000.00", null).copy(
            tradeDate = paidDate.plusDays(1), navDate = paidDate.plusDays(1),
            confirmationDate = paidDate.plusDays(2), settlementDate = paidDate.plusDays(3), cashFlowDate = paidDate,
        )
        val result = assertIs<LedgerCashFlowAssembly.Complete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(purchase), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        ))
        assertEquals(paidDate, result.cashFlows.first().date)
        assertEquals(BigDecimal("100.00"), result.performance.cumulativeReturn)
        val undated = purchase.copy(cashFlowDate = null, settlementDate = null)
        val incomplete = assertIs<LedgerCashFlowAssembly.Incomplete>(LedgerCashFlowAssembler.assemble(
            fundId, listOf(undated), BigDecimal("1100.00"), valuationDate,
            HistoryScope.FULL_HISTORY, false, precision = precision,
        ))
        assertEquals(CashFlowGapReason.MISSING_CASH_DATE, incomplete.gaps.single().reason)
    }

    private fun transaction(
        id: String,
        type: TransactionType,
        actualCash: String?,
        cashDate: String?,
    ): LedgerTransaction = LedgerTransaction(
        id = TransactionId(id),
        fundId = fundId,
        type = type,
        status = TransactionStatus.CONFIRMED_ACTUAL,
        origin = TransactionOrigin.USER_ENTERED,
        authority = DataAuthority.USER_OR_PLATFORM_FINAL,
        applicationTime = Instant.parse("2026-01-01T10:00:00Z"),
        tradeDate = LocalDate.parse("2026-01-01"),
        navDate = null,
        confirmationDate = null,
        settlementDate = cashDate?.let(LocalDate::parse),
        ruleVersionId = RuleVersionId("rule"),
        actualCashFlow = actualCash?.let(::BigDecimal),
    )
}
