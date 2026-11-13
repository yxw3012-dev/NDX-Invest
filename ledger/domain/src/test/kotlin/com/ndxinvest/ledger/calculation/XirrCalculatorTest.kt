package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CashFlowKind
import com.ndxinvest.ledger.model.DatedCashFlow
import com.ndxinvest.ledger.model.HistoryScope
import com.ndxinvest.ledger.model.XirrLabel
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class XirrCalculatorTest {
    @Test
    fun `full history XIRR uses dated external cash flows and terminal value`() {
        val result = assertIs<XirrCalculation>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2025-01-01", "-1000.00", CashFlowKind.PURCHASE),
                    flow("2026-01-01", "1100.00", CashFlowKind.TERMINAL_VALUE),
                ),
                historyScope = HistoryScope.FULL_HISTORY,
                outputScale = 8,
            ),
        )

        assertEquals(BigDecimal("0.10000000"), result.annualRate)
        assertEquals(XirrLabel.ANNUALIZED_RETURN, result.label)
    }

    @Test
    fun `legacy baseline is labelled tracking-period annualized and never full-history XIRR`() {
        val result = assertIs<XirrCalculation>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2025-01-01", "-1000.00", CashFlowKind.TRACKING_BASELINE),
                    flow("2026-01-01", "1100.00", CashFlowKind.TERMINAL_VALUE),
                ),
                historyScope = HistoryScope.TRACKING_PERIOD,
                outputScale = 8,
            ),
        )

        assertEquals(BigDecimal("0.10000000"), result.annualRate)
        assertEquals(XirrLabel.TRACKING_PERIOD_ANNUALIZED, result.label)
    }

    @Test
    fun `multiple purchases redemption dividend and terminal value use dated external cash flows`() {
        val result = assertIs<XirrCalculation>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2025-01-01", "-500.00", CashFlowKind.PURCHASE),
                    flow("2025-03-01", "-500.00", CashFlowKind.PURCHASE),
                    flow("2025-06-01", "200.00", CashFlowKind.REDEMPTION),
                    flow("2025-09-01", "50.00", CashFlowKind.CASH_DIVIDEND),
                    flow("2026-01-01", "750.00", CashFlowKind.TERMINAL_VALUE),
                ),
                historyScope = HistoryScope.FULL_HISTORY,
                outputScale = 8,
            ),
        )

        assertEquals(BigDecimal("0.00000000"), result.annualRate)
    }

    @Test
    fun `tracking-period result requires an explicit baseline cash flow`() {
        assertFailsWith<IllegalArgumentException> {
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2025-01-01", "-1000.00", CashFlowKind.PURCHASE),
                    flow("2026-01-01", "1100.00", CashFlowKind.TERMINAL_VALUE),
                ),
                historyScope = HistoryScope.TRACKING_PERIOD,
            )
        }
    }

    @Test
    fun `same-day flows return explicit too-short state`() {
        val result = assertIs<XirrUnavailable>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2026-01-01", "-100.00", CashFlowKind.PURCHASE),
                    flow("2026-01-01", "110.00", CashFlowKind.TERMINAL_VALUE),
                ),
                historyScope = HistoryScope.FULL_HISTORY,
            ),
        )

        assertEquals(XirrUnavailableReason.TOO_SHORT_PERIOD, result.reason)
        assertEquals(XirrLabel.ANNUALIZED_RETURN, result.label)
    }

    @Test
    fun `unsupported cash flows return explicit no-root state`() {
        val result = assertIs<XirrUnavailable>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2026-01-01", "-100.00", CashFlowKind.PURCHASE),
                    flow("2026-01-02", "200.00", CashFlowKind.REDEMPTION),
                    flow("2026-01-03", "-150.00", CashFlowKind.PURCHASE),
                ),
                historyScope = HistoryScope.FULL_HISTORY,
            ),
        )

        assertEquals(XirrUnavailableReason.NO_ROOT, result.reason)
    }

    @Test
    fun `cash flows with multiple supported roots return explicit multiple-roots state`() {
        val result = assertIs<XirrUnavailable>(
            XirrCalculator.calculate(
                cashFlows = listOf(
                    flow("2026-01-01", "-100.00", CashFlowKind.PURCHASE),
                    flow("2026-01-02", "230.00", CashFlowKind.REDEMPTION),
                    flow("2026-01-03", "-132.00", CashFlowKind.PURCHASE),
                ),
                historyScope = HistoryScope.FULL_HISTORY,
            ),
        )

        assertEquals(XirrUnavailableReason.MULTIPLE_ROOTS, result.reason)
    }

    private fun flow(date: String, amount: String, kind: CashFlowKind): DatedCashFlow =
        DatedCashFlow(LocalDate.parse(date), BigDecimal(amount), kind)
}
