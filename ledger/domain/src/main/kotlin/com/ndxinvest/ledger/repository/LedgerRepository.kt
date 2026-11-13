package com.ndxinvest.ledger.repository

import com.ndxinvest.ledger.calculation.AssumedPurchaseReversed
import com.ndxinvest.ledger.calculation.LedgerEntry
import com.ndxinvest.ledger.calculation.LegacyLotOpened
import com.ndxinvest.ledger.calculation.LegacyLotRedeemed
import com.ndxinvest.ledger.calculation.LotConsumption
import com.ndxinvest.ledger.calculation.LotsRedeemed
import com.ndxinvest.ledger.calculation.RealLotOpened
import com.ndxinvest.ledger.model.CorrectedTransaction
import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.Fund
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.RecurringExecutionKey
import com.ndxinvest.ledger.model.RecurringExecutionNotification
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RecurringPlanId
import com.ndxinvest.ledger.model.TransactionCorrection
import com.ndxinvest.ledger.model.TransactionId
import java.time.Instant
import com.ndxinvest.ledger.rules.PurchasePlatformShareConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformAmountConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformNavConfirmation
import com.ndxinvest.ledger.calculation.LedgerHistorySnapshot
import com.ndxinvest.ledger.calculation.LedgerHistoryInspection
public interface LedgerRepository : FundRuleVersionStore, FundNavStore {
    public suspend fun previewConsumedPurchaseShareImpact(fundId: FundId, purchaseId: TransactionId,
        facts: com.ndxinvest.ledger.rules.ConsumedPurchaseShareFacts): com.ndxinvest.ledger.rules.ConsumedPurchaseShareImpactPreview =
        throw UnsupportedOperationException("Historical share impact preview is unavailable in this repository")
    public suspend fun previewConsumedPurchaseCostImpact(fundId: FundId, purchaseId: TransactionId,
        facts: com.ndxinvest.ledger.rules.ConsumedPurchaseCostFacts): com.ndxinvest.ledger.rules.ConsumedPurchaseCostImpactPreview =
        throw UnsupportedOperationException("Historical cost impact preview is unavailable in this repository")
    public suspend fun correctFinalRedemptionReceipt(correction: com.ndxinvest.ledger.rules.FinalRedemptionReceiptCorrection): Boolean = false
    public suspend fun correctConsumedPurchaseCost(correction: com.ndxinvest.ledger.rules.ConsumedPurchaseCostCorrection): Boolean = false

    public suspend fun correctConsumedPurchaseShares(correction: com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection): Boolean = false
    public suspend fun recordReviewedLegacy(review: com.ndxinvest.ledger.calculation.ReviewedLegacy): Boolean =
        throw UnsupportedOperationException("Reviewed Legacy entries are unavailable in this repository")
    public suspend fun recordReviewedDividend(review: com.ndxinvest.ledger.calculation.ReviewedDividend): Boolean =
        throw UnsupportedOperationException("Reviewed dividends are unavailable in this repository")
    public suspend fun establishTrackingBaseline(baseline: com.ndxinvest.ledger.calculation.TrackingBaseline): Boolean
    public suspend fun findTrackingBaseline(fundId: FundId): com.ndxinvest.ledger.calculation.TrackingBaseline?
    public suspend fun loadHistorySnapshot(fundId: FundId): LedgerHistorySnapshot
    public suspend fun inspectHistory(fundId: FundId): LedgerHistoryInspection
    public suspend fun saveFund(fund: Fund)
    public suspend fun findFund(fundId: FundId): Fund?
    public suspend fun loadFunds(): List<Fund>
    public suspend fun saveRecurringPlan(plan: RecurringPlan)
    public suspend fun findRecurringPlan(planId: RecurringPlanId): RecurringPlan?
    public suspend fun loadEnabledRecurringPlans(): List<RecurringPlan>
    public suspend fun createTransaction(transaction: LedgerTransaction)
    public suspend fun recordReviewedAmountRedemption(review: com.ndxinvest.ledger.calculation.ReviewedAmountRedemption): Boolean = false
    public suspend fun createPendingRedemptionIfAvailable(transaction: LedgerTransaction): Boolean
    public suspend fun activatePlannedAmountRedemptionIfAvailable(
        transactionId: TransactionId,
        confirmedShares: java.math.BigDecimal,
        updatedAt: Instant,
    ): Boolean
    public suspend fun confirmPurchase(transaction: LedgerTransaction, lot: Lot, entry: RealLotOpened)
    public suspend fun confirmDividendReinvestment(transaction: LedgerTransaction, lot: Lot, entry: RealLotOpened)
    public suspend fun recordConfirmedDividendReinvestment(
        transaction: LedgerTransaction,
        lot: Lot,
        entry: RealLotOpened,
    )
    public suspend fun scheduleRecurringPurchase(transaction: LedgerTransaction, executionKey: RecurringExecutionKey)
    public suspend fun materializeRecurringPurchase(
        updatedPlan: RecurringPlan,
        transaction: LedgerTransaction,
        executionKey: RecurringExecutionKey,
    ): Boolean
    public suspend fun loadPendingRecurringExecutionNotifications(): List<RecurringExecutionNotification>
    public suspend fun markRecurringExecutionNotificationDelivered(
        notification: RecurringExecutionNotification,
        deliveredAt: Instant,
    )
    public suspend fun confirmRedemption(transaction: LedgerTransaction, updatedLots: List<Lot>, entry: LotsRedeemed)
    public suspend fun recordLegacyLot(transaction: LedgerTransaction, lot: LegacyLot, entry: LegacyLotOpened)
    public suspend fun confirmLegacyRedemption(
        transaction: LedgerTransaction,
        updatedLot: LegacyLot,
        entry: LegacyLotRedeemed,
    )
    public suspend fun recordConfirmedLegacyRedemption(
        transaction: LedgerTransaction,
        expectedLot: LegacyLot,
        updatedLot: LegacyLot,
        entry: LegacyLotRedeemed,
    )
    public suspend fun findTransaction(transactionId: TransactionId): LedgerTransaction?
    public suspend fun loadTransactions(fundId: FundId): List<LedgerTransaction>
    public suspend fun loadPendingPurchaseTransactions(fundId: FundId): List<LedgerTransaction>
    public suspend fun loadPendingRedemptionTransactions(fundId: FundId): List<LedgerTransaction>
    public suspend fun loadLots(fundId: FundId): List<Lot>
    public suspend fun materializeCalculatedPurchase(
        transaction: LedgerTransaction,
        lot: Lot,
        entryId: LedgerEntryId,
    ): Boolean
    public suspend fun materializeCalculatedRedemption(
        transaction: LedgerTransaction,
        expectedLots: List<Lot>,
        updatedLots: List<Lot>,
        entryId: LedgerEntryId,
        consumptions: List<LotConsumption>,
    ): Boolean
    public suspend fun findLotBySourceTransaction(transactionId: TransactionId): Lot?
    public suspend fun findLegacyLot(lotId: LotId): LegacyLot?
    public suspend fun loadLegacyLots(fundId: FundId): List<LegacyLot>
    public suspend fun loadLedgerEntries(fundId: FundId): List<LedgerEntry>
    public suspend fun markAssumedPurchaseNotExecuted(
        transaction: LedgerTransaction,
        expectedLot: Lot,
        entry: AssumedPurchaseReversed,
    )
    public suspend fun recordCorrection(correctedTransaction: CorrectedTransaction)
    public suspend fun confirmCalculatedRedemptionActualCash(
        expected: LedgerTransaction,
        confirmed: LedgerTransaction,
        cashAudit: TransactionCorrection,
        cashFlowDateAudit: TransactionCorrection,
    ): Boolean
    public suspend fun confirmMatchingCalculatedPurchaseActualCash(
        expected: LedgerTransaction,
        expectedLot: Lot,
        confirmed: LedgerTransaction,
        cashAudit: TransactionCorrection,
        paymentAudit: TransactionCorrection,
    ): Boolean
    public suspend fun findCorrection(correctionId: CorrectionId): TransactionCorrection?
    public suspend fun confirmUnconsumedPurchasePlatformShares(confirmation: PurchasePlatformShareConfirmation): Boolean
    public suspend fun confirmUnconsumedPurchasePlatformAmounts(confirmation: PurchasePlatformAmountConfirmation): Boolean
    public suspend fun confirmUnconsumedPurchasePlatformNav(confirmation: PurchasePlatformNavConfirmation): Boolean
}
