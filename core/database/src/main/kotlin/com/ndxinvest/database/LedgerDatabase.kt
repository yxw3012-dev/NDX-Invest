package com.ndxinvest.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        FundEntity::class,
        FundRuleVersionEntity::class,
        NavRecordEntity::class,
        LedgerTransactionEntity::class,
        LotEntity::class,
        LegacyLotEntity::class,
        RecurringPlanEntity::class,
        RecurringExecutionEntity::class,
        LedgerEntryEntity::class,
        TransactionCorrectionEntity::class,
        ProviderConfigurationEntity::class,
        ProviderCacheEntity::class,
        ProviderDailySuccessEntity::class,
        DrawdownBandEntity::class,
        DrawdownMonitorStateEntity::class,
        DrawdownAlertEventEntity::class,
        ActionableExceptionStateEntity::class,
        ActionableExceptionEventEntity::class,
        FundCalendarSnapshotEntity::class,
        FundCalendarOpenDateEntity::class,
        TrackingBaselineEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
public abstract class LedgerDatabase : RoomDatabase() {
    public abstract fun ledgerDao(): LedgerDao

    public abstract fun providerDao(): ProviderDao

    public abstract fun automationDao(): AutomationDao

    public abstract fun fundCalendarDao(): FundCalendarDao
}
