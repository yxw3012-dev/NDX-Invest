package com.ndxinvest.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

public object LedgerMigrations {
    public val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE lots ADD COLUMN grossCashOutDecimal TEXT DEFAULT NULL")
            connection.execSQL("ALTER TABLE lots ADD COLUMN netSubscriptionAmountDecimal TEXT DEFAULT NULL")
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS transaction_corrections (
                    id TEXT NOT NULL,
                    transactionId TEXT NOT NULL,
                    field TEXT NOT NULL,
                    originalValueType TEXT NOT NULL,
                    originalValue TEXT,
                    correctedValueType TEXT NOT NULL,
                    correctedValue TEXT,
                    source TEXT NOT NULL,
                    correctedAt TEXT NOT NULL,
                    PRIMARY KEY(id),
                    FOREIGN KEY(transactionId) REFERENCES ledger_transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transaction_corrections_transactionId " +
                    "ON transaction_corrections(transactionId)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS legacy_lots (
                    id TEXT NOT NULL,
                    fundId TEXT NOT NULL,
                    sourceTransactionId TEXT NOT NULL,
                    remainingSharesDecimal TEXT NOT NULL,
                    costBasisDecimal TEXT NOT NULL,
                    trackingStartDate TEXT,
                    PRIMARY KEY(id),
                    FOREIGN KEY(fundId) REFERENCES funds(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(sourceTransactionId) REFERENCES ledger_transactions(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS index_legacy_lots_fundId ON legacy_lots(fundId)")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_legacy_lots_sourceTransactionId " +
                    "ON legacy_lots(sourceTransactionId)",
            )
        }
    }

    public val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS provider_configurations (
                    id TEXT NOT NULL,
                    dataKind TEXT NOT NULL,
                    displayName TEXT NOT NULL,
                    endpoint TEXT NOT NULL,
                    queryJson TEXT NOT NULL,
                    credentialReference TEXT,
                    credentialLocation TEXT,
                    credentialParameterName TEXT,
                    fieldMappingsJson TEXT NOT NULL,
                    optionsJson TEXT NOT NULL,
                    enabled INTEGER NOT NULL,
                    priority INTEGER NOT NULL,
                    authority TEXT,
                    updatedAt TEXT NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_provider_configurations_dataKind_priority " +
                    "ON provider_configurations(dataKind, priority)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS provider_cache (
                    cacheKey TEXT NOT NULL,
                    schemaVersion INTEGER NOT NULL,
                    providerId TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    fetchedAt TEXT NOT NULL,
                    PRIMARY KEY(cacheKey)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_provider_cache_providerId ON provider_cache(providerId)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS provider_daily_success (
                    budgetKey TEXT NOT NULL,
                    successDate TEXT NOT NULL,
                    providerId TEXT NOT NULL,
                    fetchedAt TEXT NOT NULL,
                    PRIMARY KEY(budgetKey, successDate)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_provider_daily_success_providerId " +
                    "ON provider_daily_success(providerId)",
            )
        }
    }

    public val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE provider_configurations ADD COLUMN requestMethod TEXT NOT NULL DEFAULT 'GET'",
            )
            connection.execSQL(
                "ALTER TABLE provider_configurations ADD COLUMN jsonBodyJson TEXT NOT NULL DEFAULT '{}'",
            )
        }
    }

    public val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE provider_configurations " +
                    "ADD COLUMN requestHeadersJson TEXT NOT NULL DEFAULT '{}'",
            )
        }
    }

    public val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS drawdown_bands (
                    id TEXT NOT NULL,
                    thresholdRateDecimal TEXT NOT NULL,
                    plannedAmountDecimal TEXT NOT NULL,
                    sortOrder INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS drawdown_monitor_state (
                    monitorId TEXT NOT NULL,
                    activeBandIdsJson TEXT NOT NULL,
                    lastTriggeredBandId TEXT,
                    lastDrawdownRateDecimal TEXT,
                    observedAt TEXT,
                    revision INTEGER NOT NULL,
                    PRIMARY KEY(monitorId)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS drawdown_alert_events (
                    id TEXT NOT NULL,
                    monitorId TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    bandId TEXT NOT NULL,
                    thresholdRateDecimal TEXT NOT NULL,
                    plannedAmountDecimal TEXT NOT NULL,
                    currentPointDecimal TEXT NOT NULL,
                    allTimeHighPointDecimal TEXT NOT NULL,
                    allTimeHighDate TEXT NOT NULL,
                    drawdownRateDecimal TEXT NOT NULL,
                    observedAt TEXT NOT NULL,
                    deliveredAt TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_drawdown_alert_events_monitorId_deliveredAt " +
                    "ON drawdown_alert_events(monitorId, deliveredAt)",
            )
        }
    }

    public val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE funds ADD COLUMN moneyScale INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE funds ADD COLUMN shareScale INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE funds ADD COLUMN navScale INTEGER DEFAULT NULL")
            connection.execSQL("ALTER TABLE funds ADD COLUMN roundingMode TEXT DEFAULT NULL")
        }
    }

    public val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE recurring_executions ADD COLUMN deliveredAt TEXT DEFAULT NULL")
        }
    }

    public val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS actionable_exception_states (
                    identity TEXT NOT NULL,
                    category TEXT NOT NULL,
                    subjectId TEXT NOT NULL,
                    active INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    firstObservedAt TEXT NOT NULL,
                    lastObservedAt TEXT NOT NULL,
                    resolvedAt TEXT,
                    PRIMARY KEY(identity)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS actionable_exception_events (
                    id TEXT NOT NULL,
                    identity TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    subjectId TEXT NOT NULL,
                    observedAt TEXT NOT NULL,
                    deliveredAt TEXT,
                    closedAt TEXT,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_actionable_exception_events_identity_revision " +
                    "ON actionable_exception_events(identity, revision)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_actionable_exception_events_deliveredAt " +
                    "ON actionable_exception_events(deliveredAt)",
            )
        }
    }

    public val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "ALTER TABLE actionable_exception_states " +
                    "ADD COLUMN requiredConsecutive INTEGER NOT NULL DEFAULT 1",
            )
            connection.execSQL(
                "ALTER TABLE actionable_exception_states " +
                    "ADD COLUMN consecutiveCount INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "ALTER TABLE actionable_exception_states ADD COLUMN lastObservationKey TEXT",
            )
            connection.execSQL(
                "ALTER TABLE actionable_exception_states ADD COLUMN lastObservationPresent INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE actionable_exception_states " +
                    "ADD COLUMN resolutionPending INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(
                "UPDATE actionable_exception_states SET consecutiveCount = 1 WHERE active = 1",
            )
        }
    }

    public val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS fund_calendar_snapshots (
                    fundId TEXT NOT NULL,
                    sourceId TEXT NOT NULL,
                    authority TEXT NOT NULL,
                    coverageStart TEXT NOT NULL,
                    coverageEnd TEXT NOT NULL,
                    publishedAt TEXT NOT NULL,
                    importedAt TEXT NOT NULL,
                    revision TEXT NOT NULL,
                    PRIMARY KEY(fundId),
                    FOREIGN KEY(fundId) REFERENCES funds(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_fund_calendar_snapshots_sourceId " +
                    "ON fund_calendar_snapshots(sourceId)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS fund_calendar_open_dates (
                    fundId TEXT NOT NULL,
                    openDate TEXT NOT NULL,
                    PRIMARY KEY(fundId, openDate),
                    FOREIGN KEY(fundId) REFERENCES fund_calendar_snapshots(fundId)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_fund_calendar_open_dates_openDate " +
                    "ON fund_calendar_open_dates(openDate)",
            )
        }
    }

    public val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // Unknown old cash dates stay unknown. Never synthesize them from T/NAV/confirmation.
            connection.execSQL("ALTER TABLE ledger_transactions ADD COLUMN cashFlowDate TEXT DEFAULT NULL")
        }
    }

    public val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // An explicit starting valuation is never inferred from old cost, dates or NAV cache.
            connection.execSQL("""
                CREATE TABLE IF NOT EXISTS tracking_baselines (
                    fundId TEXT NOT NULL,
                    payloadJson TEXT NOT NULL,
                    PRIMARY KEY(fundId),
                    FOREIGN KEY(fundId) REFERENCES funds(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                )
            """.trimIndent())
        }
    }

    public val ALL: List<Migration> = listOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
    )
}
