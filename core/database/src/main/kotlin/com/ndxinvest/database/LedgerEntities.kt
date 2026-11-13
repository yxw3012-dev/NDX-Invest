package com.ndxinvest.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "funds",
    indices = [Index(value = ["code"], unique = true)],
    primaryKeys = ["id"],
)
public data class FundEntity public constructor(
    public val id: String,
    public val code: String,
    public val name: String,
    public val shortName: String,
    public val fundType: String,
    public val trackingIndex: String?,
    public val providerMetadataJson: String,
    public val cardStyle: String?,
    public val moneyScale: Int? = null,
    public val shareScale: Int? = null,
    public val navScale: Int? = null,
    public val roundingMode: String? = null,
)

@Entity(
    tableName = "fund_rule_versions",
    foreignKeys = [
        ForeignKey(
            entity = FundEntity::class,
            parentColumns = ["id"],
            childColumns = ["fundId"],
        ),
    ],
    indices = [Index(value = ["fundId"])],
    primaryKeys = ["id"],
)
public data class FundRuleVersionEntity public constructor(
    public val id: String,
    public val fundId: String,
    public val effectiveFrom: String,
    public val effectiveTo: String?,
    public val cutoffTime: String,
    public val purchaseConfirmationRule: String,
    public val redemptionConfirmationRule: String,
    public val openDayRule: String,
    public val purchaseFeeRuleJson: String,
    public val redemptionFeeScheduleJson: String,
    public val allocationPolicy: String,
    public val authority: String,
    public val source: String,
    public val fetchedAt: String,
    public val manualOverrideFieldsJson: String,
)

@Entity(
    tableName = "nav_records",
    foreignKeys = [
        ForeignKey(
            entity = FundEntity::class,
            parentColumns = ["id"],
            childColumns = ["fundId"],
        ),
    ],
    indices = [Index(value = ["fundId"])],
    primaryKeys = ["fundId", "navDate"],
)
public data class NavRecordEntity public constructor(
    public val fundId: String,
    public val navDate: String,
    public val unitNavDecimal: String,
    public val accumulatedNavDecimal: String?,
    public val authority: String,
    public val source: String,
    public val fetchedAt: String,
)

@Entity(
    tableName = "ledger_transactions",
    foreignKeys = [
        ForeignKey(
            entity = FundEntity::class,
            parentColumns = ["id"],
            childColumns = ["fundId"],
        ),
        ForeignKey(
            entity = FundRuleVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleVersionId"],
        ),
    ],
    indices = [Index(value = ["fundId"]), Index(value = ["ruleVersionId"])],
    primaryKeys = ["id"],
)
public data class LedgerTransactionEntity public constructor(
    public val id: String,
    public val fundId: String,
    public val type: String,
    public val status: String,
    public val origin: String,
    public val authority: String,
    public val applicationTime: String,
    public val tradeDate: String,
    public val navDate: String?,
    public val confirmationDate: String?,
    public val settlementDate: String?,
    public val ruleVersionId: String,
    public val requestedAmountDecimal: String?,
    public val grossAmountDecimal: String?,
    public val netAmountDecimal: String?,
    public val sharesDecimal: String?,
    public val unitNavDecimal: String?,
    public val purchaseFeeDecimal: String?,
    public val redemptionFeeDecimal: String?,
    public val actualCashFlowDecimal: String?,
    public val isManualCorrected: Boolean,
    public val createdAt: String,
    public val updatedAt: String,
    @ColumnInfo(defaultValue = "NULL")
    public val cashFlowDate: String? = null,
)

@Entity(
    tableName = "lots",
    foreignKeys = [
        ForeignKey(entity = FundEntity::class, parentColumns = ["id"], childColumns = ["fundId"]),
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceTransactionId"],
        ),
    ],
    indices = [Index(value = ["fundId"]), Index(value = ["sourceTransactionId"], unique = true)],
    primaryKeys = ["id"],
)
public data class LotEntity public constructor(
    public val id: String,
    public val fundId: String,
    public val sourceTransactionId: String,
    public val type: String,
    public val confirmationDate: String,
    public val originalSharesDecimal: String,
    public val remainingSharesDecimal: String,
    @ColumnInfo(defaultValue = "NULL")
    public val grossCashOutDecimal: String?,
    @ColumnInfo(defaultValue = "NULL")
    public val netSubscriptionAmountDecimal: String?,
    public val purchaseFeeDecimal: String,
    public val costBasisDecimal: String,
    public val unitNavDecimal: String,
)

@Entity(
    tableName = "legacy_lots",
    foreignKeys = [
        ForeignKey(entity = FundEntity::class, parentColumns = ["id"], childColumns = ["fundId"]),
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceTransactionId"],
        ),
    ],
    indices = [Index(value = ["fundId"]), Index(value = ["sourceTransactionId"], unique = true)],
    primaryKeys = ["id"],
)
public data class LegacyLotEntity public constructor(
    public val id: String,
    public val fundId: String,
    public val sourceTransactionId: String,
    public val remainingSharesDecimal: String,
    public val costBasisDecimal: String,
    public val trackingStartDate: String?,
)

@Entity(
    tableName = "recurring_plans",
    foreignKeys = [ForeignKey(entity = FundEntity::class, parentColumns = ["id"], childColumns = ["fundId"])],
    indices = [Index(value = ["fundId"])],
    primaryKeys = ["id"],
)
public data class RecurringPlanEntity public constructor(
    public val id: String,
    public val fundId: String,
    public val amountDecimal: String,
    public val frequency: String,
    public val scheduleRule: String,
    public val nonOpenDayPolicy: String,
    public val enabled: Boolean,
    public val lastGeneratedDate: String?,
)

@Entity(
    tableName = "recurring_executions",
    foreignKeys = [
        ForeignKey(entity = RecurringPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"]),
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
        ),
    ],
    indices = [Index(value = ["transactionId"], unique = true)],
    primaryKeys = ["planId", "scheduledTradeDate"],
)
public data class RecurringExecutionEntity public constructor(
    public val planId: String,
    public val scheduledTradeDate: String,
    public val transactionId: String,
    public val deliveredAt: String? = null,
)

public data class RecurringExecutionNotificationRow public constructor(
    public val planId: String,
    public val scheduledTradeDate: String,
    public val transactionId: String,
    public val deliveredAt: String?,
    public val fundId: String,
    public val amountDecimal: String,
)

@Entity(
    tableName = "ledger_entries",
    foreignKeys = [ForeignKey(entity = FundEntity::class, parentColumns = ["id"], childColumns = ["fundId"])],
    indices = [Index(value = ["fundId"]), Index(value = ["sequence"], unique = true)],
    primaryKeys = ["id"],
)
public data class LedgerEntryEntity public constructor(
    public val id: String,
    public val sequence: Long,
    public val fundId: String,
    public val entryType: String,
    public val payloadJson: String,
)

@Entity(
    tableName = "transaction_corrections",
    foreignKeys = [
        ForeignKey(
            entity = LedgerTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
        ),
    ],
    indices = [Index(value = ["transactionId"])],
    primaryKeys = ["id"],
)
public data class TransactionCorrectionEntity public constructor(
    public val id: String,
    public val transactionId: String,
    public val field: String,
    public val originalValueType: String,
    public val originalValue: String?,
    public val correctedValueType: String,
    public val correctedValue: String?,
    public val source: String,
    public val correctedAt: String,
)
