package com.ndxinvest.database

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LedgerRoomDatabaseTest {
    @Test
    fun `NAV business key and decimal text preserve scale`() = withDatabase { dao ->
        seedFundAndRule(dao)
        dao.upsertNav(
            NavRecordEntity(
                fundId = FUND_ID,
                navDate = "2026-09-11",
                unitNavDecimal = "1.2300",
                accumulatedNavDecimal = "2.3400",
                authority = "FUND_OFFICIAL",
                source = "fund-company",
                fetchedAt = "2026-09-11T10:00:00Z",
            ),
        )
        dao.upsertNav(
            NavRecordEntity(
                fundId = FUND_ID,
                navDate = "2026-09-11",
                unitNavDecimal = "1.2400",
                accumulatedNavDecimal = null,
                authority = "FUND_OFFICIAL",
                source = "fund-company",
                fetchedAt = "2026-09-11T12:00:00Z",
            ),
        )

        val stored = dao.findNav(FUND_ID, "2026-09-11")
        assertEquals("1.2400", stored?.unitNavDecimal)
        assertEquals("2026-09-11T12:00:00Z", stored?.fetchedAt)
    }

    @Test
    fun `latest NAV query is fund scoped and ordered by explicit NAV date`() = withDatabase { dao ->
        seedFundAndRule(dao)
        listOf("2026-09-08", "2026-09-14", "2026-09-11").forEach { date ->
            dao.upsertNav(
                NavRecordEntity(
                    fundId = FUND_ID,
                    navDate = date,
                    unitNavDecimal = "1.2300",
                    accumulatedNavDecimal = null,
                    authority = "FUND_OFFICIAL",
                    source = "fund-company",
                    fetchedAt = "2026-09-15T10:00:00Z",
                ),
            )
        }

        assertEquals("2026-09-14", dao.findLatestNav(FUND_ID)?.navDate)
        assertNull(dao.findLatestNav("another-fund"))
    }

    @Test
    fun `recurring business key makes generation idempotent and rolls back the duplicate transaction`() =
        withDatabase { dao ->
            seedFundAndRule(dao)
            dao.upsertRecurringPlan(plan())
            dao.insertRecurringExecutionAtomically(
                transaction = transaction("recurring-1", "RECURRING_PURCHASE", "PLANNED", "AUTO_PLAN_ASSUMED"),
                execution = RecurringExecutionEntity("plan-a", "2026-09-11", "recurring-1"),
            )

            expectFailure {
                dao.insertRecurringExecutionAtomically(
                    transaction = transaction("recurring-duplicate", "RECURRING_PURCHASE", "PLANNED", "AUTO_PLAN_ASSUMED"),
                    execution = RecurringExecutionEntity("plan-a", "2026-09-11", "recurring-duplicate"),
                )
            }

            assertEquals(1, dao.countRecurringExecution("plan-a", "2026-09-11"))
            assertNull(dao.findTransaction("recurring-duplicate"))
        }

    @Test
    fun `purchase confirmation writes transaction event and lot or rolls all of them back`() = withDatabase { dao ->
        seedFundAndRule(dao)
        dao.insertTransaction(transaction("purchase-1"))
        dao.confirmPurchaseAtomically(
            transaction = transaction("purchase-1", status = "CALCULATED", updatedAt = LATER),
            lot = lot("lot-1", "purchase-1"),
            entry = entry("entry-1", 1, "REAL_LOT_OPENED"),
        )
        assertEquals("CALCULATED", dao.findTransaction("purchase-1")?.status)
        assertNotNull(dao.findLot("lot-1"))

        dao.insertTransaction(transaction("purchase-2"))
        expectFailure {
            dao.confirmPurchaseAtomically(
                transaction = transaction("purchase-2", status = "CALCULATED", updatedAt = LATER),
                lot = lot("lot-1", "purchase-2"),
                entry = entry("entry-2", 2, "REAL_LOT_OPENED"),
            )
        }

        assertEquals("PENDING_CALC", dao.findTransaction("purchase-2")?.status)
        assertEquals(listOf("entry-1"), dao.loadLedgerEntries(FUND_ID).map { it.id })
        assertNull(dao.findLotBySourceTransaction("purchase-2"))
    }

    @Test
    fun `correction audit conflict rolls back the corrected transaction facts`() = withDatabase { dao ->
        seedFundAndRule(dao)
        val actual = transaction(
            id = "actual-1",
            status = "CONFIRMED_ACTUAL",
            authority = "USER_OR_PLATFORM_FINAL",
            unitNav = "1.2000",
        )
        dao.insertTransaction(actual)
        val existingAudit = correction("correction-1", "actual-1", "1.2000", "1.2500")
        dao.insertCorrection(existingAudit)

        expectFailure {
            dao.recordCorrectionAtomically(
                transaction = actual.copy(
                    unitNavDecimal = "1.3000",
                    isManualCorrected = true,
                    updatedAt = LATER,
                ),
                correction = existingAudit.copy(correctedValue = "1.3000"),
            )
        }

        assertEquals("1.2000", dao.findTransaction("actual-1")?.unitNavDecimal)
    }

    @Test
    fun `real redemption commits transaction event and exact lot projection together`() = withDatabase { dao ->
        seedFundAndRule(dao)
        dao.insertTransaction(transaction("purchase-source", status = "CALCULATED"))
        val before = lot("lot-a", "purchase-source")
        dao.insertLot(before)
        dao.insertTransaction(transaction("redemption-1", type = "REDEMPTION"))
        val after = before.copy(remainingSharesDecimal = "40.00", costBasisDecimal = "400.00")

        dao.recordRedemptionAtomically(
            transaction = transaction("redemption-1", type = "REDEMPTION", status = "CALCULATED", updatedAt = LATER),
            expectedLots = listOf(before),
            updatedLots = listOf(after),
            entry = entry("entry-redemption", 1, "LOTS_REDEEMED"),
        )

        assertEquals("CALCULATED", dao.findTransaction("redemption-1")?.status)
        assertEquals("40.00", dao.findLot("lot-a")?.remainingSharesDecimal)
        assertEquals("entry-redemption", dao.loadLedgerEntries(FUND_ID).single().id)
    }

    @Test
    fun `Legacy import and redemption stay in their separate auditable projection`() = withDatabase { dao ->
        seedFundAndRule(dao)
        val imported = transaction(
            id = "legacy-import",
            type = "ADJUSTMENT",
            status = "CONFIRMED_ACTUAL",
            origin = "IMPORTED",
            authority = "USER_OR_PLATFORM_FINAL",
        )
        val before = LegacyLotEntity(
            id = "legacy-a",
            fundId = FUND_ID,
            sourceTransactionId = imported.id,
            remainingSharesDecimal = "100.00",
            costBasisDecimal = "500.00",
            trackingStartDate = "2026-01-01",
        )
        dao.insertLegacyLotAtomically(imported, before, entry("entry-legacy-open", 1, "LEGACY_LOT_OPENED"))
        dao.insertTransaction(transaction("legacy-redeem", type = "REDEMPTION"))
        val after = before.copy(remainingSharesDecimal = "60.00", costBasisDecimal = "300.00")

        dao.recordLegacyRedemptionAtomically(
            transaction = transaction(
                "legacy-redeem",
                type = "REDEMPTION",
                status = "CALCULATED",
                updatedAt = LATER,
            ),
            expectedLot = before,
            updatedLot = after,
            entry = entry("entry-legacy-redeem", 2, "LEGACY_LOT_REDEEMED"),
        )

        assertEquals("60.00", dao.findLegacyLot("legacy-a")?.remainingSharesDecimal)
        assertEquals(listOf("LEGACY_LOT_OPENED", "LEGACY_LOT_REDEEMED"), dao.loadLedgerEntries(FUND_ID).map { it.entryType })
    }

    @Test
    fun `NOT_EXECUTED reversal removes only an unconsumed assumed purchase lot atomically`() = withDatabase { dao ->
        seedFundAndRule(dao)
        val assumed = transaction(
            id = "assumed-1",
            type = "RECURRING_PURCHASE",
            status = "CALCULATED",
            origin = "AUTO_PLAN_ASSUMED",
        )
        val projection = lot("assumed-lot", assumed.id)
        dao.insertTransaction(assumed)
        dao.insertLot(projection)

        dao.reverseAssumedPurchaseAtomically(
            transaction = assumed.copy(status = "NOT_EXECUTED", updatedAt = LATER),
            expectedLot = projection,
            entry = entry("entry-reversal", 1, "ASSUMED_PURCHASE_REVERSED"),
        )

        assertEquals("NOT_EXECUTED", dao.findTransaction(assumed.id)?.status)
        assertNull(dao.findLot("assumed-lot"))
        assertEquals("ASSUMED_PURCHASE_REVERSED", dao.loadLedgerEntries(FUND_ID).single().entryType)
    }

    @Test
    fun `consumed assumed lot rejects reversal without changing transaction or entries`() = withDatabase { dao ->
        seedFundAndRule(dao)
        val assumed = transaction(
            id = "assumed-consumed",
            type = "RECURRING_PURCHASE",
            status = "CALCULATED",
            origin = "AUTO_PLAN_ASSUMED",
        )
        val consumed = lot("consumed-lot", assumed.id).copy(
            remainingSharesDecimal = "90.00",
            costBasisDecimal = "900.00",
        )
        dao.insertTransaction(assumed)
        dao.insertLot(consumed)

        expectFailure {
            dao.reverseAssumedPurchaseAtomically(
                assumed.copy(status = "NOT_EXECUTED", updatedAt = LATER),
                consumed,
                entry("entry-invalid-reversal", 1, "ASSUMED_PURCHASE_REVERSED"),
            )
        }

        assertEquals("CALCULATED", dao.findTransaction(assumed.id)?.status)
        assertNotNull(dao.findLot(consumed.id))
        assertEquals(emptyList(), dao.loadLedgerEntries(FUND_ID))
    }

    private fun withDatabase(block: suspend (LedgerDao) -> Unit) = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            block(database.ledgerDao())
        } finally {
            database.close()
        }
    }

    private suspend fun seedFundAndRule(dao: LedgerDao) {
        dao.upsertFund(
            FundEntity(FUND_ID, "NDX001", "Nasdaq Fund", "NDX", "INDEX", "NDX", "{}", null),
        )
        dao.insertRuleVersion(
            FundRuleVersionEntity(
                id = RULE_ID,
                fundId = FUND_ID,
                effectiveFrom = "2026-01-01",
                effectiveTo = null,
                cutoffTime = "15:00",
                purchaseConfirmationRule = "T_PLUS_1",
                redemptionConfirmationRule = "T_PLUS_2",
                openDayRule = "FUND_OPEN_DAY",
                purchaseFeeRuleJson = "{\"type\":\"none\"}",
                redemptionFeeScheduleJson = "{\"tiers\":[{\"maxHoldingDaysExclusive\":null,\"rate\":\"0\"}]}",
                allocationPolicy = "FIFO",
                authority = "FUND_OFFICIAL",
                source = "fund-company",
                fetchedAt = NOW,
                manualOverrideFieldsJson = "[]",
            ),
        )
    }

    private fun transaction(
        id: String,
        type: String = "PURCHASE",
        status: String = "PENDING_CALC",
        origin: String = "USER_ENTERED",
        authority: String = "APP_RULE_CALCULATION",
        unitNav: String? = null,
        updatedAt: String = NOW,
    ): LedgerTransactionEntity = LedgerTransactionEntity(
        id = id,
        fundId = FUND_ID,
        type = type,
        status = status,
        origin = origin,
        authority = authority,
        applicationTime = NOW,
        tradeDate = "2026-09-11",
        navDate = "2026-09-11",
        confirmationDate = "2026-09-14",
        settlementDate = null,
        ruleVersionId = RULE_ID,
        requestedAmountDecimal = "1000.00",
        grossAmountDecimal = if (status == "PENDING_CALC" || status == "PLANNED") null else "1000.00",
        netAmountDecimal = if (status == "PENDING_CALC" || status == "PLANNED") null else "1000.00",
        sharesDecimal = if (status == "PENDING_CALC" || status == "PLANNED") null else "100.00",
        unitNavDecimal = unitNav,
        purchaseFeeDecimal = null,
        redemptionFeeDecimal = null,
        actualCashFlowDecimal = null,
        isManualCorrected = false,
        createdAt = NOW,
        updatedAt = updatedAt,
    )

    private fun lot(id: String, sourceTransactionId: String): LotEntity = LotEntity(
        id = id,
        fundId = FUND_ID,
        sourceTransactionId = sourceTransactionId,
        type = "REAL",
        confirmationDate = "2026-09-14",
        originalSharesDecimal = "100.00",
        remainingSharesDecimal = "100.00",
        grossCashOutDecimal = "1000.00",
        netSubscriptionAmountDecimal = "1000.00",
        purchaseFeeDecimal = "0.00",
        costBasisDecimal = "1000.00",
        unitNavDecimal = "10.0000",
    )

    @Test
    fun `baseline transaction compares full fund NAV and history again and never replaces an existing row`() = withDatabase { dao ->
        seedFundAndRule(dao)
        val nav = NavRecordEntity(FUND_ID, "2026-09-11", "10.0000", null, "FUND_OFFICIAL", "company", NOW)
        dao.upsertNav(nav)
        val fund = requireNotNull(dao.findFund(FUND_ID))
        val expected = dao.loadHistorySnapshot(FUND_ID)
        // This low-level test checks the SQL guard; the repository separately validates the versioned payload.
        val baseline = TrackingBaselineEntity(FUND_ID, "{\"version\":1,\"evidence\":\"test\"}")
        dao.upsertFund(fund.copy(name = "Changed"))
        assertEquals(false, dao.establishTrackingBaselineAtomically(baseline, expected, nav, fund))
        assertNull(dao.findTrackingBaseline(FUND_ID))
        dao.upsertFund(fund)
        dao.upsertNav(nav.copy(source = "changed-company"))
        assertEquals(false, dao.establishTrackingBaselineAtomically(baseline, expected, nav, fund))
        assertNull(dao.findTrackingBaseline(FUND_ID))
        dao.upsertNav(nav)
        dao.insertTransaction(transaction("new-request", status = "PENDING_CALC"))
        assertEquals(false, dao.establishTrackingBaselineAtomically(baseline, expected, nav, fund))
        assertNull(dao.findTrackingBaseline(FUND_ID))
        val fresh = dao.loadHistorySnapshot(FUND_ID)
        assertEquals(true, dao.establishTrackingBaselineAtomically(baseline, fresh, nav, fund))
        assertEquals(true, dao.establishTrackingBaselineAtomically(baseline, fresh, nav, fund))
        assertEquals(false, dao.establishTrackingBaselineAtomically(baseline.copy(payloadJson = "changed"), fresh, nav, fund))
        assertEquals(baseline, dao.findTrackingBaseline(FUND_ID))
        assertEquals(fresh, dao.loadHistorySnapshot(FUND_ID))
    }

    private fun entry(id: String, sequence: Long, type: String): LedgerEntryEntity =
        LedgerEntryEntity(id, sequence, FUND_ID, type, "{\"schemaVersion\":1}")

    private fun correction(
        id: String,
        transactionId: String,
        original: String,
        corrected: String,
    ): TransactionCorrectionEntity = TransactionCorrectionEntity(
        id = id,
        transactionId = transactionId,
        field = "UNIT_NAV",
        originalValueType = "DECIMAL",
        originalValue = original,
        correctedValueType = "DECIMAL",
        correctedValue = corrected,
        source = "USER",
        correctedAt = LATER,
    )

    private fun plan(): RecurringPlanEntity = RecurringPlanEntity(
        id = "plan-a",
        fundId = FUND_ID,
        amountDecimal = "1000.00",
        frequency = "MONTHLY",
        scheduleRule = "DAY_11",
        nonOpenDayPolicy = "NEXT_OPEN_DAY",
        enabled = true,
        lastGeneratedDate = null,
    )

    private suspend fun expectFailure(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull(failure, "Expected the database operation to fail")
    }

    private companion object {
        const val FUND_ID: String = "fund-a"
        const val RULE_ID: String = "rule-a"
        const val NOW: String = "2026-09-11T10:00:00Z"
        const val LATER: String = "2026-09-11T12:00:00Z"
    }
}
