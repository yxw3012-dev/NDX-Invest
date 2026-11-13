package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundRuleField
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.TransactionStatus
import java.time.LocalDate

public object RuleVersionResolver {
    public fun resolve(
        fundId: FundId,
        tradeDate: LocalDate,
        versions: List<FundRuleVersion>,
    ): FundRuleVersion = versions
        .filter { it.fundId == fundId && it.isEffectiveOn(tradeDate) }
        .maxWithOrNull(
            compareBy<FundRuleVersion>({ it.authority.priority }, { it.effectiveFrom }, { it.id.value }),
        )
        ?: error("No fund rule version is effective on $tradeDate")

    public fun transactionsEligibleForRecalculation(
        newVersion: FundRuleVersion,
        transactions: List<LedgerTransaction>,
    ): List<LedgerTransaction> = transactions.filter { transaction ->
        transaction.fundId == newVersion.fundId &&
            !transaction.tradeDate.isBefore(newVersion.effectiveFrom) &&
            !transaction.status.isFinal
    }
}

public object FundRuleUpdateMerger {
    public fun mergeAutomatic(
        existing: FundRuleVersion,
        incoming: FundRuleVersion,
    ): FundRuleVersion {
        require(existing.fundId == incoming.fundId) { "Rule versions must belong to the same fund" }
        require(!incoming.effectiveFrom.isBefore(existing.effectiveFrom)) {
            "An automatic rule update must not move the effective date backwards"
        }
        require(incoming.manualOverrideFields.isEmpty()) {
            "An automatic rule update must not declare manual fields"
        }
        require(incoming.authority != DataAuthority.USER_OR_PLATFORM_FINAL) {
            "A final-authority rule update is not automatic"
        }

        val manualFields = existing.manualOverrideFields
        return incoming.copy(
            cutoffTime = existing.cutoffTime.takeIf {
                FundRuleField.CUTOFF_TIME in manualFields
            } ?: incoming.cutoffTime,
            purchaseConfirmationRule = existing.purchaseConfirmationRule.takeIf {
                FundRuleField.PURCHASE_CONFIRMATION_RULE in manualFields
            } ?: incoming.purchaseConfirmationRule,
            redemptionConfirmationRule = existing.redemptionConfirmationRule.takeIf {
                FundRuleField.REDEMPTION_CONFIRMATION_RULE in manualFields
            } ?: incoming.redemptionConfirmationRule,
            openDayRule = existing.openDayRule.takeIf {
                FundRuleField.OPEN_DAY_RULE in manualFields
            } ?: incoming.openDayRule,
            purchaseFeeRule = existing.purchaseFeeRule.takeIf {
                FundRuleField.PURCHASE_FEE_RULE in manualFields
            } ?: incoming.purchaseFeeRule,
            redemptionFeeSchedule = existing.redemptionFeeSchedule.takeIf {
                FundRuleField.REDEMPTION_FEE_RULE in manualFields
            } ?: incoming.redemptionFeeSchedule,
            allocationPolicy = existing.allocationPolicy.takeIf {
                FundRuleField.LOT_ALLOCATION_RULE in manualFields
            } ?: incoming.allocationPolicy,
            authority = if (manualFields.isEmpty()) incoming.authority else DataAuthority.USER_OR_PLATFORM_FINAL,
            manualOverrideFields = manualFields,
        )
    }
}

public object LedgerRevisionPolicy {
    public fun canApply(
        existing: LedgerTransaction,
        incomingAuthority: DataAuthority,
    ): Boolean = when (existing.status) {
        TransactionStatus.CONFIRMED_ACTUAL -> false

        TransactionStatus.NOT_EXECUTED,
        TransactionStatus.CANCELLED,
        -> false

        else -> incomingAuthority.canOverride(existing.authority)
    }
}

public object TransactionStateMachine {
    public fun canTransition(from: TransactionStatus, to: TransactionStatus): Boolean =
        to in allowedTransitions.getValue(from)

    public fun transition(
        transaction: LedgerTransaction,
        to: TransactionStatus,
        authority: DataAuthority = transaction.authority,
    ): LedgerTransaction {
        require(canTransition(transaction.status, to)) {
            "Illegal transaction transition: ${transaction.status} -> $to"
        }
        return transaction.copy(status = to, authority = authority)
    }

    private val allowedTransitions: Map<TransactionStatus, Set<TransactionStatus>> = mapOf(
        TransactionStatus.PLANNED to setOf(
            TransactionStatus.PENDING_CALC,
            TransactionStatus.ABNORMAL,
            TransactionStatus.NOT_EXECUTED,
            TransactionStatus.CANCELLED,
        ),
        TransactionStatus.PENDING_CALC to setOf(
            TransactionStatus.CALCULATED,
            TransactionStatus.ABNORMAL,
            TransactionStatus.NOT_EXECUTED,
            TransactionStatus.CANCELLED,
        ),
        TransactionStatus.CALCULATED to setOf(
            TransactionStatus.PENDING_CALC,
            TransactionStatus.CONFIRMED_ACTUAL,
            TransactionStatus.ABNORMAL,
            TransactionStatus.NOT_EXECUTED,
            TransactionStatus.CANCELLED,
        ),
        TransactionStatus.ABNORMAL to setOf(
            TransactionStatus.PENDING_CALC,
            TransactionStatus.CALCULATED,
            TransactionStatus.CONFIRMED_ACTUAL,
            TransactionStatus.NOT_EXECUTED,
            TransactionStatus.CANCELLED,
        ),
        TransactionStatus.CONFIRMED_ACTUAL to emptySet(),
        TransactionStatus.NOT_EXECUTED to emptySet(),
        TransactionStatus.CANCELLED to emptySet(),
    )
}
