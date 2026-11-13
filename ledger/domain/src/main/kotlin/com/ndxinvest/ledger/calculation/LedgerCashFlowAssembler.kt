package com.ndxinvest.ledger.calculation

import com.ndxinvest.ledger.model.CashFlowKind
import com.ndxinvest.ledger.model.DatedCashFlow
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.HistoryScope
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.time.LocalDate

public enum class CashFlowGapReason {
    LEGACY_HISTORY_REQUIRES_BASELINE,
    TRACKING_BASELINE_REQUIRED,
    INVALID_TRACKING_BASELINE,
    UNFINISHED_TRANSACTION,
    MISSING_ACTUAL_CASH,
    MISSING_CASH_DATE,
    INVALID_CASH_SIGN_OR_PRECISION,
    CASH_AFTER_VALUATION,
    CASH_BEFORE_BASELINE,
    TRANSACTION_AFTER_VALUATION,
    MISSING_INVESTMENT_HISTORY,
    UNSUPPORTED_ADJUSTMENT,
}

public data class CashFlowGap public constructor(
    public val transactionId: TransactionId?,
    public val reason: CashFlowGapReason,
)

public sealed interface LedgerCashFlowAssembly {
    public data class Complete public constructor(
        public val historyScope: HistoryScope,
        public val cashFlows: List<DatedCashFlow>,
        public val performance: PerformanceCalculation,
    ) : LedgerCashFlowAssembly

    public data class Incomplete public constructor(
        public val gaps: List<CashFlowGap>,
    ) : LedgerCashFlowAssembly {
        init {
            require(gaps.isNotEmpty()) { "Incomplete cash flows need at least one reason" }
        }
    }
}

/** Checks supplied ledger facts, not whether an unrecorded historical transaction exists. Estimates never enter this path. */
public object LedgerCashFlowAssembler {
    public fun assemble(
        fundId: FundId,
        transactions: List<LedgerTransaction>,
        currentPositionValue: BigDecimal,
        valuationDate: LocalDate,
        historyScope: HistoryScope,
        hasLegacyHistory: Boolean,
        trackingBaseline: DatedCashFlow? = null,
        precision: FundPrecision,
    ): LedgerCashFlowAssembly {
        require(currentPositionValue.signum() >= 0) { "Current position value must not be negative" }
        require(transactions.all { it.fundId == fundId }) { "Cash flow transactions must belong to one fund" }
        require(transactions.map { it.id }.distinct().size == transactions.size) {
            "Cash flow transactions must not contain duplicate identities"
        }
        val gaps = mutableListOf<CashFlowGap>()
        val imported = transactions.filter { it.origin == TransactionOrigin.IMPORTED }
        if (historyScope == HistoryScope.FULL_HISTORY && (hasLegacyHistory || imported.isNotEmpty())) {
            gaps += CashFlowGap(null, CashFlowGapReason.LEGACY_HISTORY_REQUIRES_BASELINE)
        }
        if (historyScope == HistoryScope.TRACKING_PERIOD && trackingBaseline == null) {
            gaps += CashFlowGap(null, CashFlowGapReason.TRACKING_BASELINE_REQUIRED)
        }
        val validBaseline = trackingBaseline?.takeIf {
            historyScope == HistoryScope.TRACKING_PERIOD && it.kind == CashFlowKind.TRACKING_BASELINE &&
                !it.date.isAfter(valuationDate) && it.amount.stripTrailingZeros().scale() <= precision.moneyScale &&
                imported.all { transaction -> !transaction.tradeDate.isAfter(it.date) }
        }
        if (trackingBaseline != null && validBaseline == null) {
            gaps += CashFlowGap(null, CashFlowGapReason.INVALID_TRACKING_BASELINE)
        }

        val flows = mutableListOf<DatedCashFlow>()
        validBaseline?.let(flows::add)
        transactions.filter { it.origin != TransactionOrigin.IMPORTED }.forEach { transaction ->
            if (transaction.status == TransactionStatus.NOT_EXECUTED || transaction.status == TransactionStatus.CANCELLED) {
                return@forEach
            }
            if (transaction.status != TransactionStatus.CONFIRMED_ACTUAL) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.UNFINISHED_TRANSACTION)
                return@forEach
            }
            if (transaction.tradeDate.isAfter(valuationDate) ||
                transaction.navDate?.isAfter(valuationDate) == true ||
                transaction.confirmationDate?.isAfter(valuationDate) == true
            ) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.TRANSACTION_AFTER_VALUATION)
                return@forEach
            }
            val kind = when (transaction.type) {
                TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE -> CashFlowKind.PURCHASE
                TransactionType.REDEMPTION -> CashFlowKind.REDEMPTION
                TransactionType.CASH_DIVIDEND -> CashFlowKind.CASH_DIVIDEND
                TransactionType.DIVIDEND_REINVESTMENT -> {
                    if (transaction.actualCashFlow != null) {
                        gaps += CashFlowGap(transaction.id, CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION)
                    }
                    return@forEach
                }
                TransactionType.ADJUSTMENT -> {
                    gaps += CashFlowGap(transaction.id, CashFlowGapReason.UNSUPPORTED_ADJUSTMENT)
                    return@forEach
                }
            }
            val amount = transaction.actualCashFlow
            if (amount == null) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.MISSING_ACTUAL_CASH)
                return@forEach
            }
            // Older final cash records used an explicit settlement/payment date; do not rewrite those facts.
            // A new actual deduction may precede T and must use its own date, never the rule-derived confirmation date.
            val cashDate = transaction.cashFlowDate ?: transaction.settlementDate
            if (cashDate == null) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.MISSING_CASH_DATE)
                return@forEach
            }
            val signValid = when (kind) {
                CashFlowKind.PURCHASE -> amount.signum() < 0
                CashFlowKind.REDEMPTION -> amount.signum() >= 0
                CashFlowKind.CASH_DIVIDEND -> amount.signum() > 0
                else -> false
            }
            if (!signValid || amount.stripTrailingZeros().scale() > precision.moneyScale) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.INVALID_CASH_SIGN_OR_PRECISION)
                return@forEach
            }
            if (cashDate.isAfter(valuationDate)) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.CASH_AFTER_VALUATION)
                return@forEach
            }
            if (validBaseline?.date?.isAfter(cashDate) == true) {
                gaps += CashFlowGap(transaction.id, CashFlowGapReason.CASH_BEFORE_BASELINE)
                return@forEach
            }
            if (amount.signum() != 0) flows += DatedCashFlow(cashDate, amount, kind)
        }
        if (gaps.isEmpty() && flows.none { it.amount.signum() < 0 }) {
            gaps += CashFlowGap(null, CashFlowGapReason.MISSING_INVESTMENT_HISTORY)
        }
        if (gaps.isNotEmpty()) return LedgerCashFlowAssembly.Incomplete(gaps.distinct())

        val invested = flows.filter { it.amount.signum() < 0 }
            .fold(BigDecimal.ZERO) { total, flow -> total.subtract(flow.amount) }
        val returned = flows.filter { it.amount.signum() > 0 }
            .fold(BigDecimal.ZERO) { total, flow -> total.add(flow.amount) }
        val performance = PerformanceCalculator.calculate(currentPositionValue, invested, returned, precision)
        val terminalValue = precision.roundMoney(currentPositionValue)
        if (terminalValue.signum() > 0) flows += DatedCashFlow(valuationDate, terminalValue, CashFlowKind.TERMINAL_VALUE)
        return LedgerCashFlowAssembly.Complete(historyScope, flows.sortedBy { it.date }, performance)
    }
}
