package com.ndxinvest.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerRoomMigrationTest {
    @Test
    fun `migration 1 to 2 preserves old facts and creates correction and Legacy projections`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-migration").resolve("ledger.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of(requireNotNull(System.getProperty("ndx.roomSchemaDir"))),
            databasePath = databasePath,
            driver = BundledSQLiteDriver(),
            databaseClass = LedgerDatabase::class,
            databaseFactory = { LedgerDatabase_Impl() },
        )
        helper.createDatabase(1).use { connection ->
            connection.execSQL(
                """
                INSERT INTO funds(
                    id, code, name, shortName, fundType, trackingIndex, providerMetadataJson, cardStyle
                ) VALUES ('fund-a', 'NDX001', 'Nasdaq Fund', 'NDX', 'INDEX', 'NDX', '{}', NULL)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(2, listOf(LedgerMigrations.MIGRATION_1_2)).use { connection ->
            assertEquals("Nasdaq Fund", connection.singleText("SELECT name FROM funds WHERE id = 'fund-a'"))
            assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM transaction_corrections"))
            assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM legacy_lots"))
            assertTrue(connection.columns("lots").containsAll(setOf("grossCashOutDecimal", "netSubscriptionAmountDecimal")))
        }
    }

    @Test
    fun `migration 2 to 3 adds durable Provider configuration cache and success budget`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-provider-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(2).close()

        helper.runMigrationsAndValidate(3, listOf(LedgerMigrations.MIGRATION_2_3)).use { connection ->
            assertTrue(
                connection.tables().containsAll(
                    setOf("provider_configurations", "provider_cache", "provider_daily_success"),
                ),
            )
            connection.execSQL(
                """
                INSERT INTO provider_configurations(
                    id, dataKind, displayName, endpoint, queryJson, credentialReference,
                    credentialLocation, credentialParameterName,
                    fieldMappingsJson, optionsJson, enabled, priority, authority, updatedAt
                ) VALUES (
                    'tencent', 'NDX_QUOTE', 'Tencent', 'https://example.invalid', '{}', NULL, NULL, NULL,
                    '{}', '{}', 1, 0, NULL, '2026-09-12T00:00:00Z'
                )
                """.trimIndent(),
            )
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM provider_configurations"))
        }
    }

    @Test
    fun `migration 3 to 4 preserves Provider rows and defaults old requests to GET without a body`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-provider-request-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(3).use { connection ->
            connection.execSQL(
                """
                INSERT INTO provider_configurations(
                    id, dataKind, displayName, endpoint, queryJson, credentialReference,
                    credentialLocation, credentialParameterName,
                    fieldMappingsJson, optionsJson, enabled, priority, authority, updatedAt
                ) VALUES (
                    'legacy-get', 'NDX_QUOTE', 'Legacy GET', 'https://example.invalid', '{}', NULL, NULL, NULL,
                    '{}', '{}', 1, 0, NULL, '2026-09-12T00:00:00Z'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(4, listOf(LedgerMigrations.MIGRATION_3_4)).use { connection ->
            assertEquals(
                "GET",
                connection.singleText("SELECT requestMethod FROM provider_configurations WHERE id = 'legacy-get'"),
            )
            assertEquals(
                "{}",
                connection.singleText("SELECT jsonBodyJson FROM provider_configurations WHERE id = 'legacy-get'"),
            )
        }
    }

    @Test
    fun `migration 4 to 5 preserves Provider rows and defaults old requests to no static headers`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-provider-header-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(4).use { connection ->
            connection.execSQL(
                """
                INSERT INTO provider_configurations(
                    id, dataKind, displayName, endpoint, queryJson, requestMethod, jsonBodyJson,
                    credentialReference, credentialLocation, credentialParameterName,
                    fieldMappingsJson, optionsJson, enabled, priority, authority, updatedAt
                ) VALUES (
                    'legacy-headerless', 'NDX_QUOTE', 'Legacy', 'https://example.invalid', '{}', 'GET', '{}',
                    NULL, NULL, NULL, '{}', '{}', 1, 0, NULL, '2026-09-12T00:00:00Z'
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(5, listOf(LedgerMigrations.MIGRATION_4_5)).use { connection ->
            assertEquals(
                "{}",
                connection.singleText(
                    "SELECT requestHeadersJson FROM provider_configurations WHERE id = 'legacy-headerless'",
                ),
            )
        }
    }

    @Test
    fun `migration 5 to 6 adds persistent drawdown configuration state and notification outbox`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-automation-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(5).close()

        helper.runMigrationsAndValidate(6, listOf(LedgerMigrations.MIGRATION_5_6)).use { connection ->
            assertTrue(
                connection.tables().containsAll(
                    setOf("drawdown_bands", "drawdown_monitor_state", "drawdown_alert_events"),
                ),
            )
            connection.execSQL(
                """
                INSERT INTO drawdown_bands(id, thresholdRateDecimal, plannedAmountDecimal, sortOrder)
                VALUES ('first', '-0.10', '1000.00', 0)
                """.trimIndent(),
            )
            assertEquals("-0.10", connection.singleText("SELECT thresholdRateDecimal FROM drawdown_bands"))
        }
    }

    @Test
    fun `migration 6 to 7 preserves funds and leaves previously unknown precision unconfigured`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-fund-precision-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(6).use { connection ->
            connection.execSQL(
                """
                INSERT INTO funds(
                    id, code, name, shortName, fundType, trackingIndex, providerMetadataJson, cardStyle
                ) VALUES ('fund-precision', '270042', '广发纳指100ETF联接A', '广发纳指100A', 'INDEX', 'NDX', '{}', NULL)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(7, listOf(LedgerMigrations.MIGRATION_6_7)).use { connection ->
            assertTrue(
                connection.columns("funds").containsAll(
                    setOf("moneyScale", "shareScale", "navScale", "roundingMode"),
                ),
            )
            assertEquals(
                1,
                connection.singleLong(
                    "SELECT COUNT(*) FROM funds WHERE id = 'fund-precision' " +
                        "AND moneyScale IS NULL AND shareScale IS NULL AND navScale IS NULL AND roundingMode IS NULL",
                ),
            )
        }
    }

    @Test
    fun `migration 7 to 8 adds recurring notification delivery state without inventing delivery`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-recurring-outbox-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(7).close()

        helper.runMigrationsAndValidate(8, listOf(LedgerMigrations.MIGRATION_7_8)).use { connection ->
            assertTrue(connection.columns("recurring_executions").contains("deliveredAt"))
            assertEquals(
                0,
                connection.singleLong(
                    "SELECT COUNT(*) FROM recurring_executions WHERE deliveredAt IS NOT NULL",
                ),
            )
        }
    }

    @Test
    fun `migration 8 to 9 adds actionable exception state and a pending outbox`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-actionable-exception-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(8).close()

        helper.runMigrationsAndValidate(9, listOf(LedgerMigrations.MIGRATION_8_9)).use { connection ->
            assertTrue(
                connection.tables().containsAll(
                    setOf("actionable_exception_states", "actionable_exception_events"),
                ),
            )
            connection.execSQL(
                """
                INSERT INTO actionable_exception_states(
                    identity, category, subjectId, active, revision,
                    firstObservedAt, lastObservedAt, resolvedAt
                ) VALUES (
                    'rule:transaction-a', 'RULE_TRANSACTION_MISMATCH', 'transaction-a', 1, 1,
                    '2026-09-17T10:00:00Z', '2026-09-17T10:00:00Z', NULL
                )
                """.trimIndent(),
            )
            connection.execSQL(
                """
                INSERT INTO actionable_exception_events(
                    id, identity, revision, category, subjectId, observedAt, deliveredAt, closedAt
                ) VALUES (
                    'action-required:rule:transaction-a:1', 'rule:transaction-a', 1,
                    'RULE_TRANSACTION_MISMATCH', 'transaction-a', '2026-09-17T10:00:00Z', NULL, NULL
                )
                """.trimIndent(),
            )
            assertEquals(
                1,
                connection.singleLong(
                    "SELECT COUNT(*) FROM actionable_exception_events WHERE deliveredAt IS NULL",
                ),
            )
        }
    }

    @Test
    fun `migration 9 to 10 adds durable actionable qualification without changing active events`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-actionable-qualification-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(9).use { connection ->
            connection.execSQL(
                """
                INSERT INTO actionable_exception_states(
                    identity, category, subjectId, active, revision,
                    firstObservedAt, lastObservedAt, resolvedAt
                ) VALUES (
                    'rule:transaction-a', 'RULE_TRANSACTION_MISMATCH', 'transaction-a', 1, 1,
                    '2026-09-17T10:00:00Z', '2026-09-17T10:00:00Z', NULL
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(10, listOf(LedgerMigrations.MIGRATION_9_10)).use { connection ->
            assertTrue(
                connection.columns("actionable_exception_states").containsAll(
                    setOf(
                        "requiredConsecutive",
                        "consecutiveCount",
                        "lastObservationKey",
                        "lastObservationPresent",
                        "resolutionPending",
                    ),
                ),
            )
            assertEquals(
                1,
                connection.singleLong(
                    "SELECT COUNT(*) FROM actionable_exception_states " +
                        "WHERE identity = 'rule:transaction-a' AND active = 1 " +
                        "AND requiredConsecutive = 1 AND consecutiveCount = 1 AND resolutionPending = 0",
                ),
            )
        }
    }

    @Test
    fun `migration 10 to 11 adds empty auditable official fund calendar storage`() = runTest {
        val databasePath = Files.createTempDirectory("ndx-room-fund-calendar-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(10).use { connection ->
            connection.execSQL(
                """
                INSERT INTO funds(
                    id, code, name, shortName, fundType, trackingIndex, providerMetadataJson, cardStyle,
                    moneyScale, shareScale, navScale, roundingMode
                ) VALUES (
                    'fund-calendar', '270042', 'Calendar Fund', 'CAL', 'INDEX', 'NDX', '{}', NULL,
                    NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(11, listOf(LedgerMigrations.MIGRATION_10_11)).use { connection ->
            assertTrue(
                connection.tables().containsAll(
                    setOf("fund_calendar_snapshots", "fund_calendar_open_dates"),
                ),
            )
            connection.execSQL(
                """
                INSERT INTO fund_calendar_snapshots(
                    fundId, sourceId, authority, coverageStart, coverageEnd,
                    publishedAt, importedAt, revision
                ) VALUES (
                    'fund-calendar', 'fund-company', 'FUND_OFFICIAL', '2026-09-01', '2026-09-30',
                    '2026-08-31T00:00:00Z', '2026-09-01T00:00:00Z', '2026-09-v1'
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO fund_calendar_open_dates(fundId, openDate) " +
                    "VALUES ('fund-calendar', '2026-09-01')",
            )
            assertEquals(
                "fund-company",
                connection.singleText(
                    "SELECT sourceId FROM fund_calendar_snapshots WHERE fundId = 'fund-calendar'",
                ),
            )
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM fund_calendar_open_dates"))
        }
    }

    @Test
    fun `migration chain 1 to 11 preserves ledger facts while adding Provider automation precision outbox and calendar storage`() =
        runTest {
        val databasePath = Files.createTempDirectory("ndx-room-full-migration").resolve("ledger.db")
        val helper = migrationHelper(databasePath)
        helper.createDatabase(1).use { connection ->
            connection.execSQL(
                """
                INSERT INTO funds(
                    id, code, name, shortName, fundType, trackingIndex, providerMetadataJson, cardStyle
                ) VALUES ('fund-chain', 'NDX002', 'Chain Fund', 'CHAIN', 'INDEX', 'NDX', '{}', NULL)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            11,
            listOf(
                LedgerMigrations.MIGRATION_1_2,
                LedgerMigrations.MIGRATION_2_3,
                LedgerMigrations.MIGRATION_3_4,
                LedgerMigrations.MIGRATION_4_5,
                LedgerMigrations.MIGRATION_5_6,
                LedgerMigrations.MIGRATION_6_7,
                LedgerMigrations.MIGRATION_7_8,
                LedgerMigrations.MIGRATION_8_9,
                LedgerMigrations.MIGRATION_9_10,
                LedgerMigrations.MIGRATION_10_11,
            ),
        ).use { connection ->
            assertEquals("Chain Fund", connection.singleText("SELECT name FROM funds WHERE id = 'fund-chain'"))
            assertTrue(connection.tables().contains("provider_cache"))
            assertTrue(connection.columns("provider_configurations").contains("requestHeadersJson"))
            assertTrue(connection.tables().contains("drawdown_monitor_state"))
            assertTrue(connection.columns("funds").containsAll(setOf("moneyScale", "shareScale", "navScale", "roundingMode")))
            assertTrue(connection.columns("recurring_executions").contains("deliveredAt"))
            assertTrue(connection.tables().containsAll(setOf("actionable_exception_states", "actionable_exception_events")))
            assertTrue(connection.columns("actionable_exception_states").contains("requiredConsecutive"))
            assertTrue(connection.tables().containsAll(setOf("fund_calendar_snapshots", "fund_calendar_open_dates")))
        }
    }

    @Test
    fun `migration 11 to 12 preserves explicit old cash and audit dates without inventing a new cash date`() = runTest {
        val path = Files.createTempDirectory("ndx-room-cash-date-migration").resolve("ledger.db")
        val helper = migrationHelper(path)
        helper.createDatabase(11).use { connection ->
            connection.execSQL("""
                INSERT INTO funds(id, code, name, shortName, fundType, providerMetadataJson)
                VALUES ('cash-fund', 'CASH001', 'Cash Fund', 'CASH', 'INDEX', '{}')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO fund_rule_versions(
                    id, fundId, effectiveFrom, cutoffTime, purchaseConfirmationRule, redemptionConfirmationRule,
                    openDayRule, purchaseFeeRuleJson, redemptionFeeScheduleJson, allocationPolicy,
                    authority, source, fetchedAt, manualOverrideFieldsJson
                ) VALUES ('cash-rule', 'cash-fund', '2026-01-01', '15:00', 'T_PLUS_1', 'T_PLUS_2',
                    'FUND_OPEN_DAY', '{}', '{}', 'FIFO', 'FUND_OFFICIAL', 'official', '2026-09-01T00:00:00Z', '[]')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO ledger_transactions(
                    id, fundId, type, status, origin, authority, applicationTime, tradeDate, navDate,
                    confirmationDate, settlementDate, ruleVersionId, actualCashFlowDecimal,
                    isManualCorrected, createdAt, updatedAt
                ) VALUES ('old-paid', 'cash-fund', 'PURCHASE', 'CONFIRMED_ACTUAL', 'USER_ENTERED',
                    'USER_OR_PLATFORM_FINAL', '2026-09-09T08:00:00Z', '2026-09-10', '2026-09-10',
                    '2026-09-12', '2026-09-11', 'cash-rule', '-1000.00', 1,
                    '2026-09-09T08:00:00Z', '2026-09-12T08:00:00Z')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO ledger_transactions(
                    id, fundId, type, status, origin, authority, applicationTime, tradeDate, navDate,
                    confirmationDate, ruleVersionId, isManualCorrected, createdAt, updatedAt
                ) VALUES ('old-unknown', 'cash-fund', 'RECURRING_PURCHASE', 'CALCULATED', 'AUTO_PLAN_ASSUMED',
                    'APP_RULE_CALCULATION', '2026-09-09T08:00:00Z', '2026-09-10', '2026-09-10',
                    '2026-09-12', 'cash-rule', 0, '2026-09-09T08:00:00Z', '2026-09-12T08:00:00Z')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO transaction_corrections(
                    id, transactionId, field, originalValueType, correctedValueType, correctedValue, source, correctedAt
                ) VALUES ('old-payment-audit', 'old-paid', 'SETTLEMENT_DATE', 'MISSING', 'DATE', '2026-09-11',
                    'SALES_PLATFORM_FINAL', '2026-09-12T08:00:00Z')
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(12, listOf(LedgerMigrations.MIGRATION_11_12)).use { connection ->
            assertEquals(2, connection.singleLong("SELECT COUNT(*) FROM ledger_transactions WHERE cashFlowDate IS NULL"))
            assertEquals("-1000.00", connection.singleText("SELECT actualCashFlowDecimal FROM ledger_transactions WHERE id = 'old-paid'"))
            assertEquals("2026-09-10", connection.singleText("SELECT tradeDate FROM ledger_transactions WHERE id = 'old-paid'"))
            assertEquals("2026-09-10", connection.singleText("SELECT navDate FROM ledger_transactions WHERE id = 'old-paid'"))
            assertEquals("2026-09-12", connection.singleText("SELECT confirmationDate FROM ledger_transactions WHERE id = 'old-paid'"))
            assertEquals("2026-09-11", connection.singleText("SELECT settlementDate FROM ledger_transactions WHERE id = 'old-paid'"))
            assertEquals("2026-09-11", connection.singleText("SELECT correctedValue FROM transaction_corrections WHERE id = 'old-payment-audit'"))
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM ledger_transactions WHERE actualCashFlowDecimal IS NULL"))
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM transaction_corrections"))
        }
    }

    @Test
    fun `every historical schema upgrades to 12 without deleting funds or creating cash facts`() = runTest {
        (1..11).forEach { version ->
            val path = Files.createTempDirectory("ndx-room-to12-$version").resolve("ledger.db")
            val helper = migrationHelper(path)
            helper.createDatabase(version).use { connection ->
                connection.execSQL("""
                    INSERT INTO funds(id, code, name, shortName, fundType, providerMetadataJson)
                    VALUES ('old-fund', 'MIG001', 'Original Fund', 'OLD', 'INDEX', '{}')
                """.trimIndent())
            }
            helper.runMigrationsAndValidate(12, LedgerMigrations.ALL).use { connection ->
                assertEquals("Original Fund", connection.singleText("SELECT name FROM funds WHERE id = 'old-fund'"))
                assertTrue(connection.columns("ledger_transactions").contains("cashFlowDate"))
                assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM ledger_transactions"))
                assertTrue(connection.tables().containsAll(setOf("legacy_lots", "provider_cache", "fund_calendar_snapshots")))
            }
        }
    }

    @Test
    fun `migration 12 to 13 adds an empty explicit baseline table without guessing old holdings or cash`() = runTest {
        val path = Files.createTempDirectory("ndx-room-baseline-migration").resolve("ledger.db")
        val helper = migrationHelper(path)
        helper.createDatabase(12).use { connection ->
            connection.execSQL("""
                INSERT INTO funds(id, code, name, shortName, fundType, providerMetadataJson)
                VALUES ('old-fund', 'BASE001', 'Original Fund', 'OLD', 'INDEX', '{}')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO fund_rule_versions(
                    id, fundId, effectiveFrom, cutoffTime, purchaseConfirmationRule, redemptionConfirmationRule,
                    openDayRule, purchaseFeeRuleJson, redemptionFeeScheduleJson, allocationPolicy,
                    authority, source, fetchedAt, manualOverrideFieldsJson
                ) VALUES ('old-rule', 'old-fund', '2026-01-01', '15:00', 'T_PLUS_1', 'T_PLUS_2',
                    'FUND_OPEN_DAY', '{}', '{}', 'FIFO', 'FUND_OFFICIAL', 'official', '2026-09-01T00:00:00Z', '[]')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO ledger_transactions(id, fundId, type, status, origin, authority, applicationTime,
                    tradeDate, ruleVersionId, isManualCorrected, createdAt, updatedAt)
                VALUES ('old-import', 'old-fund', 'ADJUSTMENT', 'CONFIRMED_ACTUAL', 'IMPORTED',
                    'USER_OR_PLATFORM_FINAL', '2026-09-11T08:00:00Z', '2026-09-11', 'old-rule', 0,
                    '2026-09-11T08:00:00Z', '2026-09-11T08:00:00Z')
            """.trimIndent())
            connection.execSQL("""
                INSERT INTO legacy_lots(id, fundId, sourceTransactionId, remainingSharesDecimal, costBasisDecimal, trackingStartDate)
                VALUES ('old-lot', 'old-fund', 'old-import', '100.0000', '700.00', NULL)
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(13, listOf(LedgerMigrations.MIGRATION_12_13)).use { connection ->
            assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM tracking_baselines"))
            assertEquals("100.0000", connection.singleText("SELECT remainingSharesDecimal FROM legacy_lots WHERE id = 'old-lot'"))
            assertEquals("700.00", connection.singleText("SELECT costBasisDecimal FROM legacy_lots WHERE id = 'old-lot'"))
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM legacy_lots WHERE trackingStartDate IS NULL"))
            assertEquals(1, connection.singleLong("SELECT COUNT(*) FROM ledger_transactions WHERE id = 'old-import' AND actualCashFlowDecimal IS NULL AND cashFlowDate IS NULL"))
        }
    }

    @Test
    fun `all twelve old schema versions upgrade to 13 without fabricating a tracking baseline`() = runTest {
        (1..12).forEach { version ->
            val path = Files.createTempDirectory("ndx-room-to13-$version").resolve("ledger.db")
            val helper = migrationHelper(path)
            helper.createDatabase(version).use { connection ->
                connection.execSQL("""
                    INSERT INTO funds(id, code, name, shortName, fundType, providerMetadataJson)
                    VALUES ('old-fund', 'MIG001', 'Original Fund', 'OLD', 'INDEX', '{}')
                """.trimIndent())
            }
            helper.runMigrationsAndValidate(13, LedgerMigrations.ALL).use { connection ->
                assertEquals("Original Fund", connection.singleText("SELECT name FROM funds WHERE id = 'old-fund'"))
                assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM tracking_baselines"))
                assertEquals(0, connection.singleLong("SELECT COUNT(*) FROM ledger_transactions"))
                assertTrue(connection.columns("ledger_transactions").contains("cashFlowDate"))
            }
        }
    }

    private fun migrationHelper(databasePath: Path): MigrationTestHelper = MigrationTestHelper(
        schemaDirectoryPath = Path.of(requireNotNull(System.getProperty("ndx.roomSchemaDir"))),
        databasePath = databasePath,
        driver = BundledSQLiteDriver(),
        databaseClass = LedgerDatabase::class,
        databaseFactory = { LedgerDatabase_Impl() },
    )

    private fun SQLiteConnection.singleText(sql: String): String = prepare(sql).use { statement ->
        check(statement.step()) { "Query returned no row" }
        statement.getText(0)
    }

    private fun SQLiteConnection.singleLong(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step()) { "Query returned no row" }
        statement.getLong(0)
    }

    private fun SQLiteConnection.columns(table: String): Set<String> = prepare("PRAGMA table_info($table)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

    private fun SQLiteConnection.tables(): Set<String> = prepare(
        "SELECT name FROM sqlite_master WHERE type = 'table'",
    ).use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }
}
