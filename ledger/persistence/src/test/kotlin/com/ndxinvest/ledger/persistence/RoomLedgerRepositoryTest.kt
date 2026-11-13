package com.ndxinvest.ledger.persistence

import com.ndxinvest.ledger.model.CorrectionSource
import com.ndxinvest.ledger.rules.FinalRedemptionReceiptCorrection
import com.ndxinvest.ledger.rules.ConsumedPurchaseCostCorrection
import kotlin.test.assertFalse

import com.ndxinvest.database.LedgerDatabaseFactory
import com.ndxinvest.ledger.calculation.AssumedPurchaseReversed
import com.ndxinvest.ledger.calculation.LegacyLotOpened
import com.ndxinvest.ledger.calculation.LegacyLotRedeemed
import com.ndxinvest.ledger.calculation.LotConsumption
import com.ndxinvest.ledger.calculation.LotsRedeemed
import com.ndxinvest.ledger.calculation.RealLotOpened
import com.ndxinvest.ledger.calculation.LedgerCashFlowAssembler
import com.ndxinvest.ledger.calculation.LedgerCashFlowAssembly
import com.ndxinvest.ledger.calculation.LedgerRealizedReturnAssembler
import com.ndxinvest.ledger.calculation.LedgerRealizedReturnAssembly
import com.ndxinvest.ledger.calculation.RealizedReturnGapReason
import com.ndxinvest.ledger.calculation.LedgerAnnualizedReturnAssembler
import com.ndxinvest.ledger.calculation.LedgerAnnualizedReturnAssembly
import com.ndxinvest.ledger.calculation.AnnualizedReturnGapReason
import com.ndxinvest.ledger.calculation.XirrCalculation
import com.ndxinvest.ledger.calculation.TrackingBaseline
import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.CorrectionValue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.Fund
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.FundRuleField
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.HistoryScope
import com.ndxinvest.ledger.model.HoldingPeriodBoundary
import com.ndxinvest.ledger.model.HoldingPeriodUnit
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.PurchaseFeeTier
import com.ndxinvest.ledger.model.RecurringExecutionKey
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RecurringPlanId
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import com.ndxinvest.ledger.model.RedemptionFeeTier
import com.ndxinvest.ledger.model.RuleExpression
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionCorrectionField
import com.ndxinvest.ledger.model.TransactionCorrection
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import com.ndxinvest.ledger.rules.TransactionCorrectionService
import com.ndxinvest.ledger.rules.PurchasePlatformShareConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformAmountConfirmation
import com.ndxinvest.ledger.rules.PurchasePlatformNavConfirmation
import com.ndxinvest.ledger.calculation.LedgerRebuilder
import com.ndxinvest.ledger.calculation.LedgerHistorySnapshot
import com.ndxinvest.ledger.calculation.LedgerHistoryInspection
import com.ndxinvest.ledger.calculation.HistoryIssueReason
import com.ndxinvest.database.LedgerDao
import com.ndxinvest.database.LedgerEntryEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomLedgerRepositoryTest {
    @Test
    fun `domain facts round trip through Room without losing decimal scale or provenance`() = withRepository { repo ->
        seedFundAndRule(repo)
        val nav = NavRecord(
            FUND_ID,
            DATE,
            BigDecimal("1.2300"),
            BigDecimal("2.3400"),
            DataAuthority.FUND_OFFICIAL,
            DataSourceId("fund-company"),
            NOW,
        )
        val plan = recurringPlan()
        val pending = transaction("pending-a")
        repo.saveNav(nav)
        repo.saveRecurringPlan(plan)
        repo.createTransaction(pending)

        assertEquals(fund(), repo.findFund(FUND_ID))
        assertEquals(rule(), repo.findRuleVersion(RULE_ID))
        assertEquals(nav, repo.findNav(FUND_ID, DATE))
        assertEquals(plan, repo.findRecurringPlan(plan.id))
        assertEquals(pending, repo.findTransaction(pending.id))
    }

    @Test
    fun `latest NAV lookup returns the newest published date without inventing a common date`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val older = NavRecord(
                FUND_ID,
                LocalDate.parse("2026-09-10"),
                BigDecimal("8.0487"),
                BigDecimal("8.3187"),
                DataAuthority.RELIABLE_AGGREGATOR,
                DataSourceId("tiantian-eastmoney"),
                NOW,
            )
            val latest = older.copy(
                navDate = LocalDate.parse("2026-09-14"),
                unitNav = BigDecimal("8.0448"),
                accumulatedNav = BigDecimal("8.3148"),
                authority = DataAuthority.FUND_OFFICIAL,
                source = DataSourceId("gffunds-official"),
            )
            repo.saveNav(latest)
            repo.saveNav(older)

            assertEquals(latest, repo.findLatestNav(FUND_ID))
        }

    @Test
    fun `tiered purchase fee rule round trips exact amount boundaries through Room`() = withRepository { repo ->
        repo.saveFund(fund())
        val tieredRule = rule().copy(
            id = RuleVersionId("rule-tiered"),
            purchaseFeeRule = PurchaseFeeRule.Tiered(
                listOf(
                    PurchaseFeeTier(
                        minGrossAmountInclusive = BigDecimal("0.00"),
                        maxGrossAmountExclusive = BigDecimal("1000000.00"),
                        feeRule = PurchaseFeeRule.Rate(BigDecimal("0.0130")),
                    ),
                    PurchaseFeeTier(
                        minGrossAmountInclusive = BigDecimal("1000000.00"),
                        maxGrossAmountExclusive = null,
                        feeRule = PurchaseFeeRule.Fixed(BigDecimal("1000.00")),
                    ),
                ),
            ),
        )

        repo.saveRuleVersion(tieredRule)

        assertEquals(tieredRule, repo.findRuleVersion(tieredRule.id))
    }

    @Test
    fun `actual fee rule versions remain distinct and load in effective order`() = withRepository { repo ->
        repo.saveFund(fund())
        val later = rule().copy(
            id = RuleVersionId("actual-fee-2026-09"),
            effectiveFrom = LocalDate.parse("2026-09-01"),
            purchaseFeeRule = PurchaseFeeRule.Rate(BigDecimal("0.0010")),
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            source = DataSourceId("gffunds-official+user-confirmed"),
            manualOverrideFields = setOf(FundRuleField.PURCHASE_FEE_RULE),
        )
        val earlier = later.copy(
            id = RuleVersionId("actual-fee-2026-07"),
            effectiveFrom = LocalDate.parse("2026-07-01"),
            purchaseFeeRule = PurchaseFeeRule.Rate(BigDecimal("0.0012")),
        )

        repo.saveRuleVersion(later)
        repo.saveRuleVersion(earlier)

        assertEquals(listOf(earlier, later), repo.loadRuleVersions(FUND_ID))
    }

    @Test
    fun `calendar holding boundaries round trip while legacy day JSON remains readable`() = withRepository { repo ->
        repo.saveFund(fund())
        val calendarRule = rule().copy(
            id = RuleVersionId("rule-calendar-holding-period"),
            redemptionFeeSchedule = RedemptionFeeSchedule(
                listOf(
                    RedemptionFeeTier(
                        HoldingPeriodBoundary(7, HoldingPeriodUnit.DAYS),
                        BigDecimal("0.0150"),
                    ),
                    RedemptionFeeTier(
                        HoldingPeriodBoundary(1, HoldingPeriodUnit.YEARS),
                        BigDecimal("0.0050"),
                    ),
                    RedemptionFeeTier(null, BigDecimal.ZERO),
                ),
            ),
        )

        repo.saveRuleVersion(calendarRule)

        assertEquals(calendarRule, repo.findRuleVersion(calendarRule.id))

        val legacyEntity = rule().toEntity().copy(
            id = "legacy-day-json",
            redemptionFeeScheduleJson =
                """{"tiers":[{"maxHoldingDaysExclusive":30,"rate":"0.0100"},{"maxHoldingDaysExclusive":null,"rate":"0"}]}""",
        )
        assertEquals(
            RedemptionFeeSchedule(
                listOf(
                    RedemptionFeeTier(HoldingPeriodBoundary(30, HoldingPeriodUnit.DAYS), BigDecimal("0.0100")),
                    RedemptionFeeTier(null, BigDecimal.ZERO),
                ),
            ),
            legacyEntity.toDomain().redemptionFeeSchedule,
        )
    }

    @Test
    fun `lower authority NAV cannot overwrite an official fact for the same fund and date`() =
        withRepository { repo ->
            repo.saveFund(fund())
            val official = NavRecord(
                FUND_ID,
                DATE,
                BigDecimal("1.2300"),
                null,
                DataAuthority.FUND_OFFICIAL,
                DataSourceId("fund-company"),
                NOW,
            )
            val laterAggregator = official.copy(
                unitNav = BigDecimal("9.9999"),
                authority = DataAuthority.RELIABLE_AGGREGATOR,
                source = DataSourceId("aggregator"),
                fetchedAt = LATER,
            )

            repo.saveNav(official)
            repo.saveNav(laterAggregator)

            assertEquals(official, repo.findNav(FUND_ID, DATE))
        }

    @Test
    fun `higher authority NAV replaces an aggregator while non-newer equal authority data does not`() =
        withRepository { repo ->
            repo.saveFund(fund())
            val aggregator = NavRecord(
                FUND_ID,
                DATE,
                BigDecimal("1.2200"),
                null,
                DataAuthority.RELIABLE_AGGREGATOR,
                DataSourceId("aggregator"),
                LATER,
            )
            val official = aggregator.copy(
                unitNav = BigDecimal("1.2300"),
                authority = DataAuthority.FUND_OFFICIAL,
                source = DataSourceId("fund-company"),
                fetchedAt = NOW,
            )
            val olderOfficial = official.copy(
                unitNav = BigDecimal("8.8888"),
                source = DataSourceId("other-official"),
                fetchedAt = NOW.minusSeconds(1),
            )
            val sameTimeOfficial = official.copy(
                unitNav = BigDecimal("7.7777"),
                source = DataSourceId("same-time-official"),
            )

            repo.saveNav(aggregator)
            repo.saveNav(official)
            repo.saveNav(olderOfficial)
            repo.saveNav(sameTimeOfficial)

            assertEquals(official, repo.findNav(FUND_ID, DATE))
        }

    @Test
    fun `confirmed fact correction updates the transaction and stores its audit atomically`() = withRepository { repo ->
        seedFundAndRule(repo)
        val actual = transaction(
            id = "actual-a",
            status = TransactionStatus.CONFIRMED_ACTUAL,
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            unitNav = BigDecimal("1.2000"),
        )
        repo.createTransaction(actual)
        val corrected = TransactionCorrectionService.applyManualCorrection(
            transaction = actual,
            correctionId = CorrectionId("correction-a"),
            field = TransactionCorrectionField.UNIT_NAV,
            correctedValue = CorrectionValue.Decimal(BigDecimal("1.3000")),
            correctedAt = LATER,
        )

        repo.recordCorrection(corrected)

        assertEquals(corrected.transaction, repo.findTransaction(actual.id))
        assertEquals(corrected.audit, repo.findCorrection(corrected.audit.id))
    }

    @Test
    fun `platform actual cash date commits with cash while preserving a different settlement estimate`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val estimatedSettlement = DATE.plusDays(2)
            val receivedDate = DATE.plusDays(1)
            val calculated = transaction(
                id = "cash-final-redemption",
                type = TransactionType.REDEMPTION,
                status = TransactionStatus.CALCULATED,
                unitNav = BigDecimal("1.2500"),
            ).copy(
                requestedAmount = null,
                settlementDate = estimatedSettlement,
                shares = BigDecimal("40.00"),
                grossAmount = BigDecimal("50.00"),
                netAmount = BigDecimal("49.50"),
                redemptionFee = BigDecimal("0.50"),
            )
            repo.createTransaction(calculated)
            val cash = TransactionCorrectionService.applySalesPlatformFinalCorrection(
                calculated, CorrectionId("cash-final-audit"), TransactionCorrectionField.ACTUAL_CASH_FLOW,
                CorrectionValue.Decimal(BigDecimal("49.25")), LATER,
            )
            val date = TransactionCorrectionService.applySalesPlatformFinalCorrection(
                cash.transaction, CorrectionId("cash-final-date-audit"), TransactionCorrectionField.CASH_FLOW_DATE,
                CorrectionValue.Date(receivedDate), LATER,
            )
            val confirmed = date.transaction.copy(
                status = TransactionStatus.CONFIRMED_ACTUAL,
                netAmount = BigDecimal("49.25"),
            )

            assertEquals(false, repo.confirmCalculatedRedemptionActualCash(
                calculated, confirmed.copy(shares = BigDecimal("41.00")), cash.audit, date.audit,
            ))
            assertEquals(calculated, repo.findTransaction(calculated.id))
            assertNull(repo.findCorrection(cash.audit.id))
            assertNull(repo.findCorrection(date.audit.id))
            assertEquals(true, repo.confirmCalculatedRedemptionActualCash(
                calculated, confirmed, cash.audit, date.audit,
            ))
            assertEquals(confirmed, repo.findTransaction(calculated.id))
            assertEquals(cash.audit, repo.findCorrection(cash.audit.id))
            assertEquals(date.audit, repo.findCorrection(date.audit.id))
            assertEquals(receivedDate, repo.findTransaction(calculated.id)?.cashFlowDate)
            assertEquals(estimatedSettlement, repo.findTransaction(calculated.id)?.settlementDate)
            assertEquals(false, repo.confirmCalculatedRedemptionActualCash(
                calculated, confirmed, cash.audit, date.audit,
            ))
        }

    @Test
    fun `redemption cash date audit collision rolls back the cash amount and first audit`() = withRepository { repo ->
        seedFundAndRule(repo)
        val calculated = transaction(
            id = "cash-date-audit-rollback-sale",
            type = TransactionType.REDEMPTION,
            status = TransactionStatus.CALCULATED,
            unitNav = BigDecimal("1.2500"),
        ).copy(requestedAmount = null, shares = BigDecimal("40.00"), grossAmount = BigDecimal("50.00"))
        repo.createTransaction(calculated)

        val collisionId = CorrectionId("cash-date-audit-collision")
        val owner = transaction("cash-date-audit-owner", unitNav = BigDecimal("1.0000"))
        repo.createTransaction(owner)
        val occupied = TransactionCorrectionService.applyManualCorrection(
            owner, collisionId, TransactionCorrectionField.UNIT_NAV, CorrectionValue.Decimal(BigDecimal("1.1000")), LATER,
        )
        repo.recordCorrection(occupied)

        val cash = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            calculated, CorrectionId("cash-date-rollback-cash"), TransactionCorrectionField.ACTUAL_CASH_FLOW,
            CorrectionValue.Decimal(BigDecimal("49.25")), LATER,
        )
        val cashDate = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            cash.transaction, collisionId, TransactionCorrectionField.CASH_FLOW_DATE,
            CorrectionValue.Date(DATE.plusDays(1)), LATER,
        )
        val confirmed = cashDate.transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL, netAmount = BigDecimal("49.25"))

        expectFailure { repo.confirmCalculatedRedemptionActualCash(calculated, confirmed, cash.audit, cashDate.audit) }

        assertEquals(calculated, repo.findTransaction(calculated.id))
        assertNull(repo.findCorrection(cash.audit.id))
        assertEquals(occupied.audit, repo.findCorrection(collisionId))
    }

    @Test
    fun `recurring generation is idempotent then confirmation materializes exactly one assumed lot`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val plan = recurringPlan()
            repo.saveRecurringPlan(plan)
            val pending = transaction(
                id = "recurring-a",
                type = TransactionType.RECURRING_PURCHASE,
                origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            )
            val key = RecurringExecutionKey(plan.id, DATE)
            repo.scheduleRecurringPurchase(pending, key)
            expectFailure {
                repo.scheduleRecurringPurchase(
                    pending.copy(id = TransactionId("recurring-duplicate")),
                    key,
                )
            }
            assertNull(repo.findTransaction(TransactionId("recurring-duplicate")))

            val calculated = calculatedPurchase(pending)
            val lot = lot("recurring-lot", pending.id)
            val event = RealLotOpened(LedgerEntryId("entry-recurring"), 1, lot)
            repo.confirmPurchase(calculated, lot, event)

            assertEquals(calculated, repo.findTransaction(pending.id))
            assertEquals(lot, repo.findLotBySourceTransaction(pending.id))
            assertEquals(listOf(event), repo.loadLedgerEntries(FUND_ID))
        }

    @Test
    fun `recurring worker materialization advances the plan and keeps a retryable notice outbox`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val plan = recurringPlan()
            repo.saveRecurringPlan(plan)
            val scheduledDate = LocalDate.parse("2026-09-11")
            val transaction = transaction(
                id = "recurring-worker-plan-a-2026-09-11",
                type = TransactionType.RECURRING_PURCHASE,
                origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            )
            val updatedPlan = plan.copy(lastGeneratedDate = scheduledDate)
            val key = RecurringExecutionKey(plan.id, scheduledDate)

            assertEquals(true, repo.materializeRecurringPurchase(updatedPlan, transaction, key))
            assertEquals(false, repo.materializeRecurringPurchase(updatedPlan, transaction, key))
            assertEquals(updatedPlan, repo.findRecurringPlan(plan.id))
            val notice = repo.loadPendingRecurringExecutionNotifications().single()
            assertEquals(transaction.id, notice.transactionId)

            repo.markRecurringExecutionNotificationDelivered(notice, LATER)

            assertEquals(emptyList(), repo.loadPendingRecurringExecutionNotifications())
            assertEquals(1, repo.loadTransactions(FUND_ID).count { it.id == transaction.id })
        }

    @Test
    fun `real redemption persists event and lot projection as one lifecycle operation`() = withRepository { repo ->
        seedFundAndRule(repo)
        val purchase = transaction("purchase-a")
        repo.createTransaction(purchase)
        val openedLot = lot("lot-a", purchase.id)
        repo.confirmPurchase(
            calculatedPurchase(purchase),
            openedLot,
            RealLotOpened(LedgerEntryId("entry-open"), 1, openedLot),
        )
        val redemption = transaction("redemption-a", type = TransactionType.REDEMPTION)
        repo.createTransaction(redemption)
        val updated = openedLot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
        val event = LotsRedeemed(
            LedgerEntryId("entry-redeem"),
            2,
            FUND_ID,
            listOf(LotConsumption(openedLot.id, BigDecimal("40.00"), BigDecimal("400.00"))),
        )

        repo.confirmRedemption(
            redemption.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("40.00"), updatedAt = LATER),
            listOf(updated),
            event,
        )

        assertEquals(updated, repo.findLotBySourceTransaction(purchase.id))
        assertEquals(event.copy(sourceTransactionId = redemption.id), repo.loadLedgerEntries(FUND_ID).last())
    }

    @Test
    fun `Legacy import avoids fake history and later redemption updates only the Legacy projection`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val import = transaction(
                id = "legacy-import",
                type = TransactionType.ADJUSTMENT,
                status = TransactionStatus.CONFIRMED_ACTUAL,
                origin = TransactionOrigin.IMPORTED,
                authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            )
            val legacy = LegacyLot(
                LotId("legacy-a"),
                FUND_ID,
                BigDecimal("100.00"),
                BigDecimal("500.00"),
                DATE,
            )
            repo.recordLegacyLot(import, legacy, LegacyLotOpened(LedgerEntryId("entry-legacy"), 1, legacy))
            val redemption = transaction("legacy-redemption", type = TransactionType.REDEMPTION)
            repo.createTransaction(redemption)
            val updated = legacy.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("300.00"))
            repo.confirmLegacyRedemption(
                redemption.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("40.00"), updatedAt = LATER),
                updated,
                LegacyLotRedeemed(
                    LedgerEntryId("entry-legacy-redeem"),
                    2,
                    FUND_ID,
                    LotConsumption(legacy.id, BigDecimal("40.00"), BigDecimal("200.00")),
                ),
            )

            assertEquals(updated, repo.findLegacyLot(legacy.id))
            assertEquals(listOf(updated), repo.loadLegacyLots(FUND_ID))
            assertEquals(listOf(TransactionOrigin.IMPORTED, TransactionOrigin.USER_ENTERED), repo.loadTransactions(FUND_ID).map { it.origin })
        }

    @Test
    fun `confirmed Legacy sale atomically writes actual cash cost consumption and rejects stale lots`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val imported = transaction(
                id = "legacy-atomic-import",
                type = TransactionType.ADJUSTMENT,
                status = TransactionStatus.CONFIRMED_ACTUAL,
                origin = TransactionOrigin.IMPORTED,
                authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            )
            val original = LegacyLot(
                LotId("legacy-atomic-lot"), FUND_ID, BigDecimal("100.00"), BigDecimal("500.00"), DATE,
            )
            repo.recordLegacyLot(imported, original, LegacyLotOpened(LedgerEntryId("legacy-atomic-open"), 1, original))
            val confirmed = transaction(
                id = "legacy-atomic-sale",
                type = TransactionType.REDEMPTION,
                status = TransactionStatus.CONFIRMED_ACTUAL,
                authority = DataAuthority.USER_OR_PLATFORM_FINAL,
                actualCashFlow = BigDecimal("250.00"),
            ).copy(
                requestedAmount = null,
                netAmount = BigDecimal("250.00"),
                shares = BigDecimal("40.00"),
                navDate = null,
                confirmationDate = null,
                settlementDate = DATE,
            )
            val remaining = original.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("300.00"))
            val event = LegacyLotRedeemed(
                LedgerEntryId("legacy-atomic-sale-event"), 1, FUND_ID,
                LotConsumption(original.id, BigDecimal("40.00"), BigDecimal("200.00")),
            )
            repo.recordConfirmedLegacyRedemption(confirmed, original, remaining, event)

            assertEquals(confirmed, repo.findTransaction(confirmed.id))
            assertEquals(remaining, repo.findLegacyLot(original.id))
            assertEquals(2L, repo.loadLedgerEntries(FUND_ID).last().sequence)
            val stale = confirmed.copy(id = TransactionId("legacy-stale-sale"))
            expectFailure { repo.recordConfirmedLegacyRedemption(stale, original, remaining, event.copy(
                id = LedgerEntryId("legacy-stale-event"),
            )) }
            assertNull(repo.findTransaction(stale.id))
            assertEquals(remaining, repo.findLegacyLot(original.id))
            assertEquals(2, repo.loadLedgerEntries(FUND_ID).size)
        }

    @Test
    fun `cash dividend remains an external transaction while reinvestment opens a real lot`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val cashDividend = transaction(
                id = "cash-dividend",
                type = TransactionType.CASH_DIVIDEND,
                status = TransactionStatus.CONFIRMED_ACTUAL,
                authority = DataAuthority.USER_OR_PLATFORM_FINAL,
                actualCashFlow = BigDecimal("25.00"),
            )
            repo.createTransaction(cashDividend)
            val pendingReinvestment = transaction("reinvestment", type = TransactionType.DIVIDEND_REINVESTMENT)
            repo.createTransaction(pendingReinvestment)
            val reinvestmentLot = lot("reinvestment-lot", pendingReinvestment.id)
            val reinvestmentEvent = RealLotOpened(LedgerEntryId("entry-reinvestment"), 1, reinvestmentLot)
            repo.confirmDividendReinvestment(
                calculatedPurchase(pendingReinvestment),
                reinvestmentLot,
                reinvestmentEvent,
            )

            assertEquals(BigDecimal("25.00"), repo.findTransaction(cashDividend.id)?.actualCashFlow)
            assertNull(repo.findLotBySourceTransaction(cashDividend.id))
            assertEquals(reinvestmentLot, repo.findLotBySourceTransaction(pendingReinvestment.id))
        }

    @Test
    fun `confirmed reinvestment creates transaction lot and event atomically and rolls back a lot conflict`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val first = transaction(
                id = "confirmed-reinvestment-a",
                type = TransactionType.DIVIDEND_REINVESTMENT,
                status = TransactionStatus.CONFIRMED_ACTUAL,
                authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            ).copy(
                requestedAmount = null,
                grossAmount = BigDecimal("50.00"),
                netAmount = BigDecimal("50.00"),
                shares = BigDecimal("40.00"),
                unitNav = BigDecimal("1.2500"),
                purchaseFee = BigDecimal("0.00"),
            )
            val firstLot = lot("confirmed-reinvestment-lot", first.id).copy(
                originalShares = BigDecimal("40.00"),
                remainingShares = BigDecimal("40.00"),
                grossCashOut = BigDecimal("0.00"),
                netSubscriptionAmount = BigDecimal("50.00"),
                purchaseFee = BigDecimal("0.00"),
                costBasis = BigDecimal("50.00"),
                unitNav = BigDecimal("1.2500"),
            )
            val firstEntry = RealLotOpened(LedgerEntryId("confirmed-reinvestment-entry-a"), 1, firstLot)
            expectFailure { repo.recordConfirmedDividendReinvestment(first, firstLot, firstEntry) }
            assertNull(repo.findTransaction(first.id))
            assertNull(repo.findLotBySourceTransaction(first.id))
            assertEquals(emptyList(), repo.loadLedgerEntries(FUND_ID))
            repo.saveNav(
                NavRecord(
                    FUND_ID, DATE, BigDecimal("1.2500"), null,
                    DataAuthority.FUND_OFFICIAL, DataSourceId("official-reinvestment"), NOW,
                ),
            )
            repo.recordConfirmedDividendReinvestment(
                first, firstLot, firstEntry,
            )
            assertEquals(first, repo.findTransaction(first.id))
            assertEquals(firstLot, repo.findLotBySourceTransaction(first.id))
            assertEquals(1L, repo.loadLedgerEntries(FUND_ID).single().sequence)

            val second = first.copy(id = TransactionId("confirmed-reinvestment-b"))
            val conflictingLot = firstLot.copy(sourceTransactionId = second.id)
            expectFailure {
                repo.recordConfirmedDividendReinvestment(
                    second,
                    conflictingLot,
                    RealLotOpened(LedgerEntryId("confirmed-reinvestment-entry-b"), 1, conflictingLot),
                )
            }
            assertNull(repo.findTransaction(second.id))
            assertNull(repo.findLotBySourceTransaction(second.id))
            assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
        }

    @Test
    fun `assumed purchase marked not executed is reversed without deleting its immutable history`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val plan = recurringPlan()
            repo.saveRecurringPlan(plan)
            val pending = transaction(
                id = "assumed-a",
                type = TransactionType.RECURRING_PURCHASE,
                origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
            )
            repo.scheduleRecurringPurchase(pending, RecurringExecutionKey(plan.id, DATE))
            val calculated = calculatedPurchase(pending)
            val assumedLot = lot("assumed-lot", pending.id)
            repo.confirmPurchase(
                calculated,
                assumedLot,
                RealLotOpened(LedgerEntryId("entry-assumed-open"), 1, assumedLot),
            )
            val reversal = AssumedPurchaseReversed(
                LedgerEntryId("entry-assumed-reverse"),
                2,
                FUND_ID,
                pending.id,
            )

            repo.markAssumedPurchaseNotExecuted(
                calculated.copy(status = TransactionStatus.NOT_EXECUTED, updatedAt = LATER.plusSeconds(1)),
                assumedLot,
                reversal,
            )

            assertEquals(TransactionStatus.NOT_EXECUTED, repo.findTransaction(pending.id)?.status)
            assertNull(repo.findLotBySourceTransaction(pending.id))
            assertEquals(2, repo.loadLedgerEntries(FUND_ID).size)
            assertEquals(reversal, repo.loadLedgerEntries(FUND_ID).last())
        }

    @Test
    fun `automatic purchase materialization is idempotent and allocates ledger sequence inside Room`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val firstPending = transaction("nav-worker-first")
            val secondPending = transaction("nav-worker-second")
            repo.createTransaction(firstPending)
            repo.createTransaction(secondPending)

            val firstCalculated = calculatedPurchase(firstPending)
            val firstLot = lot("auto-lot:nav-worker-first", firstPending.id)
            assertEquals(
                true,
                repo.materializeCalculatedPurchase(
                    firstCalculated,
                    firstLot,
                    LedgerEntryId("auto-entry:nav-worker-first"),
                ),
            )
            assertEquals(
                false,
                repo.materializeCalculatedPurchase(
                    firstCalculated.copy(updatedAt = LATER.plusSeconds(1)),
                    firstLot,
                    LedgerEntryId("auto-entry:nav-worker-first"),
                ),
            )

            val secondCalculated = calculatedPurchase(secondPending).copy(updatedAt = LATER.plusSeconds(2))
            val secondLot = lot("auto-lot:nav-worker-second", secondPending.id)
            assertEquals(
                true,
                repo.materializeCalculatedPurchase(
                    secondCalculated,
                    secondLot,
                    LedgerEntryId("auto-entry:nav-worker-second"),
                ),
            )

            assertEquals(listOf(1L, 2L), repo.loadLedgerEntries(FUND_ID).map { it.sequence })
            assertEquals(firstLot, repo.findLotBySourceTransaction(firstPending.id))
            assertEquals(secondLot, repo.findLotBySourceTransaction(secondPending.id))
        }

    @Test
    fun `automatic redemption materialization is idempotent and consumes lots in one Room transaction`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val purchase = transaction("auto-redemption-source")
            repo.createTransaction(purchase)
            val openedLot = lot("auto-redemption-lot", purchase.id)
            repo.confirmPurchase(
                calculatedPurchase(purchase),
                openedLot,
                RealLotOpened(LedgerEntryId("entry-auto-redemption-source"), 1, openedLot),
            )
            val pending = transaction("auto-redemption", type = TransactionType.REDEMPTION).copy(
                requestedAmount = null,
                shares = BigDecimal("40.00"),
            )
            repo.createTransaction(pending)
            val calculated = pending.copy(
                status = TransactionStatus.CALCULATED,
                grossAmount = BigDecimal("396.00"),
                netAmount = BigDecimal("392.04"),
                unitNav = BigDecimal("9.9000"),
                redemptionFee = BigDecimal("3.96"),
                actualCashFlow = BigDecimal("392.04"),
                updatedAt = LATER,
            )
            val updatedLot = openedLot.copy(
                remainingShares = BigDecimal("60.00"),
                costBasis = BigDecimal("600.00"),
            )
            val consumptions = listOf(
                LotConsumption(openedLot.id, BigDecimal("40.00"), BigDecimal("400.00")),
            )

            assertEquals(
                true,
                repo.materializeCalculatedRedemption(
                    calculated,
                    listOf(openedLot),
                    listOf(updatedLot),
                    LedgerEntryId("auto-entry:auto-redemption"),
                    consumptions,
                ),
            )
            assertEquals(
                false,
                repo.materializeCalculatedRedemption(
                    calculated.copy(updatedAt = LATER.plusSeconds(1)),
                    listOf(openedLot),
                    listOf(updatedLot),
                    LedgerEntryId("auto-entry:auto-redemption"),
                    consumptions,
                ),
            )

            assertEquals(updatedLot, repo.loadLots(FUND_ID).single())
            assertEquals(listOf(1L, 2L), repo.loadLedgerEntries(FUND_ID).map { it.sequence })
            assertEquals(TransactionStatus.CALCULATED, repo.findTransaction(pending.id)?.status)
        }

    @Test
    fun `pending redemptions reserve real shares atomically before NAV calculation`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val purchase = transaction("reservation-source")
            repo.createTransaction(purchase)
            val openedLot = lot("reservation-lot", purchase.id)
            repo.confirmPurchase(
                calculatedPurchase(purchase),
                openedLot,
                RealLotOpened(LedgerEntryId("entry-reservation-source"), 1, openedLot),
            )
            val first = transaction("reservation-first", type = TransactionType.REDEMPTION).copy(
                requestedAmount = null,
                shares = BigDecimal("60.00"),
            )
            val second = transaction("reservation-second", type = TransactionType.REDEMPTION).copy(
                requestedAmount = null,
                shares = BigDecimal("60.00"),
            )

            assertEquals(true, repo.createPendingRedemptionIfAvailable(first))
            assertEquals(false, repo.createPendingRedemptionIfAvailable(second))
            assertEquals(listOf(first), repo.loadPendingRedemptionTransactions(FUND_ID))
        }

    @Test
    fun `amount redemption activation reserves platform shares atomically and leaves failed plans untouched`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            val purchase = transaction("amount-reservation-source")
            repo.createTransaction(purchase)
            val openedLot = lot("amount-reservation-lot", purchase.id)
            repo.confirmPurchase(
                calculatedPurchase(purchase),
                openedLot,
                RealLotOpened(LedgerEntryId("entry-amount-reservation-source"), 1, openedLot),
            )
            val first = transaction("amount-plan-first", type = TransactionType.REDEMPTION).copy(
                status = TransactionStatus.PLANNED,
                requestedAmount = BigDecimal("250.00"),
                shares = null,
            )
            val second = first.copy(id = TransactionId("amount-plan-second"))
            repo.createTransaction(first)
            repo.createTransaction(second)
            assertEquals(emptyList(), repo.loadPendingRedemptionTransactions(FUND_ID))

            assertEquals(true, repo.activatePlannedAmountRedemptionIfAvailable(first.id, BigDecimal("60.00"), LATER))
            assertEquals(false, repo.activatePlannedAmountRedemptionIfAvailable(second.id, BigDecimal("40.01"), LATER))
            assertEquals(TransactionStatus.PLANNED, repo.findTransaction(second.id)?.status)
            assertEquals(null, repo.findTransaction(second.id)?.shares)
            assertEquals(false, repo.activatePlannedAmountRedemptionIfAvailable(first.id, BigDecimal("60.00"), LATER))
            assertEquals(true, repo.activatePlannedAmountRedemptionIfAvailable(second.id, BigDecimal("40.00"), LATER))
            assertEquals(listOf(first.id, second.id), repo.loadPendingRedemptionTransactions(FUND_ID).map { it.id })
            assertEquals(BigDecimal("250.00"), repo.findTransaction(first.id)?.requestedAmount)
            assertEquals(openedLot, repo.loadLots(FUND_ID).single())
        }

    @Test
    fun `amount redemption activation rejects excess precision and active Legacy holdings`() = withRepository { repo ->
        seedFundAndRule(repo)
        val source = transaction("amount-legacy-source")
        repo.createTransaction(source)
        val openedLot = lot("amount-legacy-real", source.id)
        repo.confirmPurchase(
            calculatedPurchase(source), openedLot,
            RealLotOpened(LedgerEntryId("amount-legacy-real-open"), 1, openedLot),
        )
        val planned = transaction("amount-legacy-plan", type = TransactionType.REDEMPTION).copy(
            status = TransactionStatus.PLANNED,
            requestedAmount = BigDecimal("250.00"),
            shares = null,
        )
        repo.createTransaction(planned)
        assertEquals(false, repo.activatePlannedAmountRedemptionIfAvailable(planned.id, BigDecimal("1.001"), LATER))

        val imported = transaction(
            id = "amount-legacy-import", type = TransactionType.ADJUSTMENT,
            status = TransactionStatus.CONFIRMED_ACTUAL,
            origin = TransactionOrigin.IMPORTED,
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
        )
        val legacy = LegacyLot(LotId("amount-legacy-lot"), FUND_ID, BigDecimal("10.00"), BigDecimal("50.00"), DATE)
        repo.recordLegacyLot(imported, legacy, LegacyLotOpened(LedgerEntryId("amount-legacy-open"), 2, legacy))
        assertEquals(false, repo.activatePlannedAmountRedemptionIfAvailable(planned.id, BigDecimal("1.00"), LATER))
        assertEquals(planned, repo.findTransaction(planned.id))
        assertEquals(emptyList(), repo.loadPendingRedemptionTransactions(FUND_ID))
    }

    private suspend fun legacyReview(repo: RoomLedgerRepository): com.ndxinvest.ledger.calculation.ReviewedLegacy {
        seedFundAndRule(repo)
        val lot = LegacyLot(LotId("reviewed-old"), FUND_ID, BigDecimal("100.00"), BigDecimal("700.00"), null)
        val tx = LedgerTransaction(TransactionId("reviewed-import"), FUND_ID, TransactionType.ADJUSTMENT,
            TransactionStatus.CONFIRMED_ACTUAL, TransactionOrigin.IMPORTED, DataAuthority.USER_OR_PLATFORM_FINAL,
            LATER, DATE, null, null, null, RULE_ID, shares = lot.remainingShares)
        return com.ndxinvest.ledger.calculation.ReviewedLegacy(fund(), repo.loadHistorySnapshot(FUND_ID), listOf(rule()), tx,
            null, lot, LegacyLotOpened(LedgerEntryId("reviewed-old-open"), 1, lot))
    }
    @Test fun `reviewed Legacy import atomically stores explicit old facts without cash and rejects repeated identity`() = withRepository { repo ->
        val proof = legacyReview(repo)
        assertTrue(repo.recordReviewedLegacy(proof))
        assertEquals(proof.updatedLot, repo.findLegacyLot(proof.updatedLot.id))
        assertNull(assertNotNull(repo.findTransaction(proof.transaction.id)).actualCashFlow)
        assertIs<LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
        expectFailure { repo.recordReviewedLegacy(proof.copy(history = repo.loadHistorySnapshot(FUND_ID))) }
        assertEquals(false, repo.recordReviewedLegacy(proof))
        assertEquals(1, repo.loadTransactions(FUND_ID).size)
    }
    @Test fun `reviewed Legacy import rejects stale fund rules and history without partial projection`() = runTest {
        for (change in listOf("fund", "rule", "history")) withRepository { repo ->
            val proof = legacyReview(repo)
            when (change) {
                "fund" -> repo.saveFund(fund().copy(shortName = "changed"))
                "rule" -> repo.saveRuleVersion(rule().copy(id = RuleVersionId("changed")))
                else -> repo.createTransaction(transaction("unseen"))
            }
            assertEquals(false, repo.recordReviewedLegacy(proof))
            assertNull(repo.findTransaction(proof.transaction.id)); assertNull(repo.findLegacyLot(proof.updatedLot.id))
        }
    }
    @Test fun `reviewed Legacy import opening identity collision rolls back transaction and lot`() = withRepository { repo ->
        val proof = legacyReview(repo)
        assertTrue(repo.recordReviewedLegacy(proof))
        val other = FundId("second-fund")
        val otherFund = proof.fund.copy(id = other, code = "SECOND")
        val otherRule = rule().copy(id = RuleVersionId("second-rule"), fundId = other)
        repo.saveFund(otherFund); repo.saveRuleVersion(otherRule)
        val second = proof.copy(fund = otherFund, rules = listOf(otherRule), history = repo.loadHistorySnapshot(other),
            transaction = proof.transaction.copy(id = TransactionId("second-import"), fundId = other, ruleVersionId = otherRule.id),
            updatedLot = proof.updatedLot.copy(id = LotId("second-old"), fundId = other))
        val collision = second.copy(entry = LegacyLotOpened(proof.entry.id, 1, second.updatedLot))
        expectFailure { repo.recordReviewedLegacy(collision) }
        assertNull(repo.findTransaction(second.transaction.id)); assertNull(repo.findLegacyLot(second.updatedLot.id))
        assertEquals(proof.updatedLot, repo.findLegacyLot(proof.updatedLot.id))
    }
    @Test fun `reviewed Legacy sale rechecks current official calendar and preserves import provenance and cost`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao())
            val proof = legacyReview(repo); assertTrue(repo.recordReviewedLegacy(proof))
            val shares = BigDecimal("40.00"); val cash = BigDecimal("300.00")
            val calculated = com.ndxinvest.ledger.calculation.LegacyLotCalculator.redeem(proof.updatedLot, shares, cash, requireNotNull(fund().precision))
            val tx = proof.transaction.copy(id = TransactionId("reviewed-sale"), type = TransactionType.REDEMPTION,
                origin = TransactionOrigin.USER_ENTERED, applicationTime = NOW, shares = shares, netAmount = cash,
                actualCashFlow = cash, settlementDate = DATE)
            val sale = proof.copy(history = repo.loadHistorySnapshot(FUND_ID), transaction = tx, expectedLot = proof.updatedLot,
                updatedLot = calculated.updatedLot, entry = LegacyLotRedeemed(LedgerEntryId("reviewed-sale-event"), 1,
                    FUND_ID, LotConsumption(proof.updatedLot.id, shares, calculated.allocatedCostBasis), tx.id))
            assertEquals(false, repo.recordReviewedLegacy(sale))
            assertNull(repo.findTransaction(tx.id)); assertEquals(proof.updatedLot, repo.findLegacyLot(proof.updatedLot.id))
            val calendar = com.ndxinvest.database.FundCalendarSnapshotEntity(FUND_ID.value, "official", "FUND_OFFICIAL",
                DATE.minusDays(1).toString(), DATE.plusDays(1).toString(), NOW.minusSeconds(60).toString(), NOW.toString(), "one")
            database.fundCalendarDao().importSnapshotAtomically(calendar,
                listOf(com.ndxinvest.database.FundCalendarOpenDateEntity(FUND_ID.value, DATE.minusDays(1).toString())))
            assertEquals(false, repo.recordReviewedLegacy(sale))
            assertNull(repo.findTransaction(tx.id))
            database.fundCalendarDao().importSnapshotAtomically(calendar.copy(revision = "two"),
                listOf(com.ndxinvest.database.FundCalendarOpenDateEntity(FUND_ID.value, DATE.toString())))
            assertTrue(repo.recordReviewedLegacy(sale))
            assertEquals(calculated.updatedLot, repo.findLegacyLot(proof.updatedLot.id))
            assertEquals(proof.transaction.id.value, database.ledgerDao().findLegacyLot(proof.updatedLot.id.value)?.sourceTransactionId)
            assertIs<LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
            assertEquals(BigDecimal("20.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(
                LedgerRealizedReturnAssembler.assemble(repo.loadHistorySnapshot(FUND_ID), DATE, requireNotNull(fund().precision))).realizedReturn)
            assertEquals(false, repo.recordReviewedLegacy(sale))
        } finally { database.close() }
    }

    private suspend fun amountReview(repo: RoomLedgerRepository): com.ndxinvest.ledger.calculation.ReviewedAmountRedemption {
        seedShareCorrection(repo)
        val t = DATE.plusDays(3); val recorded = LATER.plusSeconds(4 * 86400)
        val tx = transaction("reviewed-amount", type = TransactionType.REDEMPTION, status = TransactionStatus.PLANNED).copy(
            applicationTime = t.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant(), tradeDate = t, navDate = t,
            confirmationDate = t.plusDays(2), requestedAmount = BigDecimal("400.00"), createdAt = recorded, updatedAt = recorded)
        return com.ndxinvest.ledger.calculation.ReviewedAmountRedemption(fund(), repo.loadHistorySnapshot(FUND_ID), listOf(rule()), tx,
            null, com.ndxinvest.ledger.calculation.ReviewedFundOpenCalendar(t.minusDays(2), t.plusDays(5),
                (0L..5L).map { t.plusDays(it) }.toSet()), null)
    }
    private suspend fun installAmountCalendar(database: com.ndxinvest.database.LedgerDatabase,
        proof: com.ndxinvest.ledger.calculation.ReviewedAmountRedemption, omitted: java.time.LocalDate? = null) {
        val calendar = requireNotNull(proof.calendar)
        database.fundCalendarDao().importSnapshotAtomically(com.ndxinvest.database.FundCalendarSnapshotEntity(FUND_ID.value,
            "official", "FUND_OFFICIAL", calendar.coverageStart.toString(), calendar.coverageEnd.toString(), NOW.toString(), LATER.toString(), if (omitted == null) "complete" else "partial"),
            calendar.openDates.filterNot { it == omitted }.map { com.ndxinvest.database.FundCalendarOpenDateEntity(FUND_ID.value, it.toString()) })
    }
    private suspend fun activateAmountReview(repo: RoomLedgerRepository, proof: com.ndxinvest.ledger.calculation.ReviewedAmountRedemption):
        com.ndxinvest.ledger.calculation.ReviewedAmountRedemption {
        val tx = proof.transaction; val shares = BigDecimal("40.00"); val updated = tx.createdAt.plusSeconds(1)
        return proof.copy(history = repo.loadHistorySnapshot(FUND_ID), calendar = null, expected = tx,
            transaction = tx.copy(status = TransactionStatus.PENDING_CALC, shares = shares, updatedAt = updated),
            shareAudit = TransactionCorrection(CorrectionId("platform-redemption-shares:${tx.id.value}"), tx.id,
                TransactionCorrectionField.SHARES, CorrectionValue.Missing, CorrectionValue.Decimal(shares), CorrectionSource.SALES_PLATFORM_FINAL, updated))
    }
    @Test fun `reviewed amount creation requires unchanged official calendar and never guesses cash or consumes lots`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao()); val proof = amountReview(repo)
            assertFalse(repo.recordReviewedAmountRedemption(proof)); assertNull(repo.findTransaction(proof.transaction.id))
            installAmountCalendar(database, proof, proof.transaction.tradeDate.plusDays(1))
            assertFalse(repo.recordReviewedAmountRedemption(proof)); assertNull(repo.findTransaction(proof.transaction.id))
            installAmountCalendar(database, proof)
            assertTrue(repo.recordReviewedAmountRedemption(proof))
            assertEquals(proof.transaction, repo.findTransaction(proof.transaction.id))
            val history = repo.loadHistorySnapshot(FUND_ID)
            assertEquals(proof.history.realLots, history.realLots); assertEquals(proof.history.entries, history.entries)
            assertFalse(repo.recordReviewedAmountRedemption(proof))
            val activation = activateAmountReview(repo, proof)
            assertTrue(repo.recordReviewedAmountRedemption(activation))
            assertEquals(activation.transaction, repo.findTransaction(proof.transaction.id))
            assertEquals(activation.shareAudit, repo.findCorrection(requireNotNull(activation.shareAudit).id))
            assertEquals(proof.history.realLots, repo.loadHistorySnapshot(FUND_ID).realLots)
            assertNull(repo.findTransaction(proof.transaction.id)?.actualCashFlow)
            assertIs<LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
        } finally { database.close() }
    }
    @Test fun `reviewed final share audit collision rolls back activation with no partial write`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao()); val proof = amountReview(repo); installAmountCalendar(database, proof)
            assertTrue(repo.recordReviewedAmountRedemption(proof)); val activation = activateAmountReview(repo, proof)
            val collision = requireNotNull(activation.shareAudit).copy(correctedValue = CorrectionValue.Decimal(BigDecimal("39.00")))
            database.ledgerDao().insertCorrection(collision.toEntity())
            expectFailure { repo.recordReviewedAmountRedemption(activation) }
            assertEquals(proof.transaction, repo.findTransaction(proof.transaction.id))
            assertEquals(collision, repo.findCorrection(collision.id))
            assertEquals(proof.history.realLots, repo.loadHistorySnapshot(FUND_ID).realLots)
        } finally { database.close() }
    }
    @Test fun `concurrent pending reservation or changed fund invalidates reviewed final shares atomically`() = runTest {
        for (change in listOf("reservation", "fund", "rule")) {
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao()); val proof = amountReview(repo); installAmountCalendar(database, proof)
                assertTrue(repo.recordReviewedAmountRedemption(proof)); val activation = activateAmountReview(repo, proof)
                when (change) {
                    "reservation" -> repo.createTransaction(activation.transaction.copy(id = TransactionId("competing-sale"), shares = BigDecimal("70.00")))
                    "fund" -> repo.saveFund(fund().copy(cardStyle = "changed"))
                    else -> repo.saveRuleVersion(rule().copy(id = RuleVersionId("new-rule"), effectiveFrom = DATE.plusDays(1)))
                }
                assertFalse(repo.recordReviewedAmountRedemption(activation))
                assertEquals(proof.transaction, repo.findTransaction(proof.transaction.id))
                assertNull(repo.findCorrection(requireNotNull(activation.shareAudit).id))
                assertEquals(proof.history.realLots, repo.loadHistorySnapshot(FUND_ID).realLots)
            } finally { database.close() }
        }
    }
    private fun costImpactFacts(history: LedgerHistorySnapshot): com.ndxinvest.ledger.rules.ConsumedPurchaseCostFacts {
        val lot = history.realLots.single()
        return com.ndxinvest.ledger.rules.ConsumedPurchaseCostFacts(lot.grossCashOut.add(BigDecimal("10.00")), lot.netSubscriptionAmount,
            lot.purchaseFee.add(BigDecimal("10.00")), lot.originalShares, lot.unitNav, DATE.minusDays(1), lot.confirmationDate)
    }
    private val costClock = java.time.Clock.fixed(LATER.plusSeconds(10 * 86400), java.time.ZoneOffset.UTC)
    private fun shareImpactFacts(history: LedgerHistorySnapshot): com.ndxinvest.ledger.rules.ConsumedPurchaseShareFacts {
        val lot = history.realLots.single(); val buy = history.transactions.single { it.id == lot.sourceTransactionId }
        return com.ndxinvest.ledger.rules.ConsumedPurchaseShareFacts(lot.grossCashOut, lot.netSubscriptionAmount, lot.purchaseFee,
            BigDecimal("110.00"), lot.unitNav, requireNotNull(buy.cashFlowDate ?: buy.settlementDate), lot.confirmationDate)
    }
    private suspend fun shareCorrection(repo: RoomLedgerRepository, key: String = "share-room-review", shares: String = "110.00"):
        com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection {
        val history = repo.loadHistorySnapshot(FUND_ID); val id = history.realLots.single().sourceTransactionId
        return com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection.prepare(key,
            repo.previewConsumedPurchaseShareImpact(FUND_ID, id, shareImpactFacts(history).copy(shares = BigDecimal(shares))), LocalDate.now(costClock))
    }
    @Test fun `reviewed shares commit append only event projection all audits preserve baseline and exact retry`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo); val proof = shareCorrection(repo)
            val before = repo.loadHistorySnapshot(FUND_ID); val rules = repo.loadRuleVersions(FUND_ID)
            val nav = NavRecord(FUND_ID, DATE.plusDays(7), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("share-final-official"), costClock.instant())
            repo.saveNav(nav); val baseline = TrackingBaseline.prepare(before, nav, requireNotNull(fund().precision), costClock.instant(), LocalDate.now(costClock))
            assertTrue(repo.establishTrackingBaseline(baseline)); assertTrue(repo.correctConsumedPurchaseShares(proof))
            val after = repo.loadHistorySnapshot(FUND_ID)
            assertEquals(before.entries, after.entries.dropLast(1)); assertEquals(before.transactions.filter { it.id != proof.confirmed.id }, after.transactions.filter { it.id != proof.confirmed.id })
            assertEquals(proof.confirmed, repo.findTransaction(proof.confirmed.id)); assertEquals(rules, repo.loadRuleVersions(FUND_ID))
            proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
            assertEquals(BigDecimal("70.00"), after.realLots.single().remainingShares); assertEquals(BigDecimal("636.36"), after.realLots.single().costBasis)
            assertEquals(BigDecimal("86.36"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after, LocalDate.now(costClock), requireNotNull(fund().precision))).realizedReturn)
            assertEquals(baseline, repo.findTrackingBaseline(FUND_ID)); assertEquals(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED,
                assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(com.ndxinvest.ledger.calculation.LedgerTrackingReturnAssembler.assemble(after, baseline, nav, LocalDate.now(costClock), requireNotNull(fund().precision))).reason)
            assertTrue(repo.correctConsumedPurchaseShares(proof)); assertEquals(after, repo.loadHistorySnapshot(FUND_ID))
        } finally { db.close() }
    }
    @Test fun `share save rejects stale fund receipt history tampering and swapped direct DAO witnesses`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); val receipt = finalReceiptReview(repo); val proof = shareCorrection(repo)
            val before = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            for (invalid in listOf(proof.copy(audits = emptyList()), proof.copy(confirmed = proof.confirmed.copy(netAmount = BigDecimal("1.00"))),
                proof.copy(entry = proof.entry.copy(correctedLot = proof.entry.correctedLot.copy(costBasis = BigDecimal("1.00")))))) {
                expectFailure { repo.correctConsumedPurchaseShares(invalid) }
                expectFailure { db.ledgerDao().correctConsumedPurchaseSharesAtomically(invalid, before, costClock.instant(), LocalDate.now(costClock)) }
            }
            repo.saveFund(fund().copy(precision = requireNotNull(fund().precision).copy(shareScale = 3)))
            assertFalse(repo.correctConsumedPurchaseShares(proof)); repo.saveFund(fund())
            assertTrue(repo.correctFinalRedemptionReceipt(receipt)); val current = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            assertFalse(repo.correctConsumedPurchaseShares(proof))
            assertFalse(db.ledgerDao().correctConsumedPurchaseSharesAtomically(proof, current, costClock.instant(), LocalDate.now(costClock)))
            val fresh = shareCorrection(repo, "share-fresh"); repo.createTransaction(transaction("share-new-pending"))
            val pending = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            assertFalse(repo.correctConsumedPurchaseShares(fresh)); assertEquals(pending, db.ledgerDao().loadHistorySnapshot(FUND_ID.value))
            proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
        } finally { db.close() }
    }
    @Test fun `share audit or event collision rolls back transaction residual and every new evidence row`() = runTest {
        for (kind in listOf("audit", "event")) {
            val db = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo); val proof = shareCorrection(repo)
                if (kind == "audit") db.ledgerDao().insertCorrection(proof.audits.last().copy(source = CorrectionSource.USER).toEntity())
                else {
                    val other = fund().copy(id = FundId("share-collision"), code = "OTHER"); repo.saveFund(other)
                    val lot = LegacyLot(LotId("share-collision-lot"), other.id, BigDecimal("1.00"), BigDecimal("1.00"), null)
                    db.ledgerDao().insertLedgerEntry(LedgerEntryCodec.encode(LegacyLotOpened(proof.entry.id, 100, lot)))
                }
                val before = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
                expectFailure { repo.correctConsumedPurchaseShares(proof) }; assertEquals(before, db.ledgerDao().loadHistorySnapshot(FUND_ID.value))
                if (kind == "event") proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
            } finally { db.close() }
        }
    }
    @Test fun `share evidence survives reopen later sales and revised share cost value cycles block older handles`() = runTest {
        val path = Files.createTempFile("consumed-shares-saved", ".db")
        lateinit var proof: com.ndxinvest.ledger.rules.ConsumedPurchaseShareCorrection
        lateinit var saved: LedgerHistorySnapshot
        val first = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(first.ledgerDao(), costClock); finalReceiptReview(repo); proof = shareCorrection(repo)
            assertTrue(repo.correctConsumedPurchaseShares(proof)); saved = repo.loadHistorySnapshot(FUND_ID)
        } finally { first.close() }
        val db = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock)
            assertEquals(saved, repo.loadHistorySnapshot(FUND_ID)); assertTrue(repo.correctConsumedPurchaseShares(proof))
            val lot = saved.realLots.single(); val t = DATE.plusDays(5)
            val priorSale = saved.transactions.single { it.type == TransactionType.REDEMPTION }
            val pending = priorSale.copy(id = TransactionId("share-later-sale"), status = TransactionStatus.PENDING_CALC, authority = DataAuthority.APP_RULE_CALCULATION,
                tradeDate = t, navDate = t, confirmationDate = t.plusDays(1), shares = BigDecimal("20.00"), actualCashFlow = null, cashFlowDate = null, settlementDate = null)
            repo.createTransaction(pending)
            val final = pending.copy(status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
                confirmationDate = t.plusDays(1), actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"), cashFlowDate = t.plusDays(1), updatedAt = costClock.instant())
            repo.confirmRedemption(final, listOf(lot.copy(remainingShares = BigDecimal("50.00"), costBasis = BigDecimal("454.54"))),
                LotsRedeemed(LedgerEntryId("share-later-event"), 4, FUND_ID, listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("181.82"))), final.id))
            val later = repo.loadHistorySnapshot(FUND_ID); assertTrue(repo.correctConsumedPurchaseShares(proof)); assertEquals(later, repo.loadHistorySnapshot(FUND_ID))
            val next = shareCorrection(repo, "share-second", "120.00"); assertTrue(repo.correctConsumedPurchaseShares(next)); assertFalse(repo.correctConsumedPurchaseShares(proof))
            val cycle = shareCorrection(repo, "share-cycle", "110.00"); assertEquals(proof.confirmed, cycle.confirmed)
            assertTrue(repo.correctConsumedPurchaseShares(cycle)); assertFalse(repo.correctConsumedPurchaseShares(proof)); assertFalse(repo.correctConsumedPurchaseShares(next))
            val cost = costCorrection(repo, "share-following-cost"); assertTrue(repo.correctConsumedPurchaseCost(cost)); assertFalse(repo.correctConsumedPurchaseShares(cycle))
            val latest = shareCorrection(repo, "share-following-cost-final", "120.00"); assertTrue(repo.correctConsumedPurchaseShares(latest)); assertFalse(repo.correctConsumedPurchaseCost(cost))
            assertEquals(final, repo.findTransaction(final.id)); assertIs<com.ndxinvest.ledger.calculation.LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
        } finally { db.close(); Files.deleteIfExists(path) }
    }
    @Test fun `consumed share preview reads atomic fund history and never writes facts audit rules or saved baseline`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo)
            val history = repo.loadHistorySnapshot(FUND_ID); val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }
            val nav = NavRecord(FUND_ID, DATE.plusDays(7), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL,
                DataSourceId("official-share-preview"), costClock.instant())
            repo.saveNav(nav)
            val baseline = TrackingBaseline.prepare(history, nav, requireNotNull(fund().precision), costClock.instant(), LocalDate.now(costClock))
            assertTrue(repo.establishTrackingBaseline(baseline))
            val raw = db.ledgerDao().loadFundHistorySnapshot(FUND_ID.value); val rules = repo.loadRuleVersions(FUND_ID)
            val cashAudit = repo.findCorrection(CorrectionId("cash:${buy.id.value}"))
            val result = repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, shareImpactFacts(history))
            assertEquals(history, result.history); assertEquals(fund(), result.fund); assertEquals(costClock.instant(), result.preparedAt)
            assertEquals(BigDecimal("70.00"), result.proposedLot.remainingShares); assertEquals(BigDecimal("636.36"), result.proposedLot.costBasis)
            assertEquals(BigDecimal("363.64"), result.sales.single().proposedSaleCost); assertEquals(BigDecimal("86.36"), result.sales.single().proposedRecordedIncome)
            assertEquals(raw, db.ledgerDao().loadFundHistorySnapshot(FUND_ID.value)); assertEquals(rules, repo.loadRuleVersions(FUND_ID))
            assertEquals(baseline, repo.findTrackingBaseline(FUND_ID)); assertEquals(cashAudit, repo.findCorrection(CorrectionId("cash:${buy.id.value}")))
            val unsafe = TransactionCorrectionService.applySalesPlatformFinalCorrection(buy, CorrectionId("unsafe-share-preview-save"),
                TransactionCorrectionField.SHARES, CorrectionValue.Decimal(BigDecimal("110.00")), costClock.instant())
            expectFailure { repo.recordCorrection(unsafe) }
            assertNull(repo.findCorrection(unsafe.audit.id)); assertEquals(history, repo.loadHistorySnapshot(FUND_ID))
        } finally { db.close() }
    }
    @Test fun `share preview rejects invalid foreign pending evidence and rereads changed receipt fund and effective cost`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); val receipt = finalReceiptReview(repo)
            val history = repo.loadHistorySnapshot(FUND_ID); val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }
            val facts = shareImpactFacts(history); val old = repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, facts)
            for (invalid in listOf(facts.copy(shares = BigDecimal("110.001")), facts.copy(shares = BigDecimal("39.99")),
                facts.copy(purchaseFee = facts.purchaseFee + BigDecimal("1.00")), facts.copy(paymentDate = DATE.plusDays(11))))
                expectFailure { repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, invalid) }
            expectFailure { repo.previewConsumedPurchaseShareImpact(FundId("foreign"), buy.id, facts) }
            expectFailure { repo.previewConsumedPurchaseShareImpact(FUND_ID, TransactionId("foreign"), facts) }
            assertEquals(history, repo.loadHistorySnapshot(FUND_ID))
            assertTrue(repo.correctFinalRedemptionReceipt(receipt)); val changedReceipt = repo.loadHistorySnapshot(FUND_ID)
            assertFalse(old.isBasedOn(fund(), changedReceipt))
            val fresh = repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, facts)
            assertEquals(BigDecimal("96.36"), fresh.sales.single().proposedRecordedIncome)
            val cost = costCorrection(repo, "prior-cost-for-share-preview"); assertTrue(repo.correctConsumedPurchaseCost(cost))
            val changedCost = repo.loadHistorySnapshot(FUND_ID)
            assertFalse(fresh.isBasedOn(fund(), changedCost))
            val afterCost = repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, shareImpactFacts(changedCost))
            assertEquals(BigDecimal("404.00"), afterCost.sales.single().originalSaleCost)
            assertEquals(BigDecimal("367.27"), afterCost.sales.single().proposedSaleCost)
            repo.saveFund(fund().copy(cardStyle = "changed")); assertFalse(afterCost.isBasedOn(requireNotNull(repo.findFund(FUND_ID)), changedCost))
            repo.createTransaction(receipt.expected.copy(id = TransactionId("pending-share-preview"), status = TransactionStatus.PENDING_CALC,
                authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null, settlementDate = null))
            val pending = db.ledgerDao().loadFundHistorySnapshot(FUND_ID.value)
            expectFailure { repo.previewConsumedPurchaseShareImpact(FUND_ID, buy.id, shareImpactFacts(changedCost)) }
            assertEquals(pending, db.ledgerDao().loadFundHistorySnapshot(FUND_ID.value))
        } finally { db.close() }
    }
    @Test fun `share previews are repeatable after database reopen without persisting proposal or any new event`() = runTest {
        val path = Files.createTempFile("consumed-share-readonly", ".db")
        lateinit var history: LedgerHistorySnapshot
        lateinit var preview: com.ndxinvest.ledger.rules.ConsumedPurchaseShareImpactPreview
        val first = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(first.ledgerDao(), costClock); finalReceiptReview(repo); history = repo.loadHistorySnapshot(FUND_ID)
            preview = repo.previewConsumedPurchaseShareImpact(FUND_ID, history.realLots.single().sourceTransactionId, shareImpactFacts(history))
            assertEquals(preview, repo.previewConsumedPurchaseShareImpact(FUND_ID, preview.originalPurchase.id, shareImpactFacts(history)))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao(), costClock)
            assertEquals(history, repo.loadHistorySnapshot(FUND_ID)); assertNull(repo.findTrackingBaseline(FUND_ID))
            assertEquals(preview, repo.previewConsumedPurchaseShareImpact(FUND_ID, preview.originalPurchase.id, shareImpactFacts(history)))
            assertEquals(BigDecimal("60.00"), repo.loadHistorySnapshot(FUND_ID).realLots.single().remainingShares)
        } finally { reopened.close(); Files.deleteIfExists(path) }
    }
    private suspend fun costCorrection(repo: RoomLedgerRepository, reviewId: String = "cost-room-review"): ConsumedPurchaseCostCorrection {
        val history = repo.loadHistorySnapshot(FUND_ID)
        val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }
        return ConsumedPurchaseCostCorrection.prepare(reviewId, repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, costImpactFacts(history)),
            LocalDate.now(costClock))
    }
    @Test fun `reviewed consumed cost commits projection complete audits and append only event without changing saved sales or rules`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo)
            val proof = costCorrection(repo); val before = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            val rules = repo.loadRuleVersions(FUND_ID); val oldAudit = repo.findCorrection(CorrectionId("cash:${proof.confirmed.id.value}"))
            assertTrue(repo.correctConsumedPurchaseCost(proof))
            val after = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            assertEquals(before.entries, after.entries.dropLast(1))
            assertEquals(before.transactions.filter { it.id != proof.confirmed.id.value }, after.transactions.filter { it.id != proof.confirmed.id.value })
            assertEquals(before.legacyLots, after.legacyLots); assertEquals(rules, repo.loadRuleVersions(FUND_ID))
            assertEquals(proof.confirmed, repo.findTransaction(proof.confirmed.id)); assertEquals(oldAudit, repo.findCorrection(CorrectionId("cash:${proof.confirmed.id.value}")))
            proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
            val history = repo.loadHistorySnapshot(FUND_ID)
            assertEquals(BigDecimal("60.00"), history.realLots.single().remainingShares)
            assertEquals(BigDecimal("606.00"), history.realLots.single().costBasis)
            assertEquals(BigDecimal("46.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(history,
                LocalDate.now(costClock), requireNotNull(fund().precision))).realizedReturn)
            assertTrue(repo.correctConsumedPurchaseCost(proof)); assertEquals(after, db.ledgerDao().loadHistorySnapshot(FUND_ID.value))
        } finally { db.close() }
    }
    @Test fun `cost review rejects changed fund receipt and history and direct DAO cannot swap captured facts`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); val receipt = finalReceiptReview(repo); val proof = costCorrection(repo)
            repo.saveFund(fund().copy(precision = requireNotNull(fund().precision).copy(moneyScale = 3)))
            assertFalse(repo.correctConsumedPurchaseCost(proof)); repo.saveFund(fund())
            assertTrue(repo.correctFinalRedemptionReceipt(receipt)); val current = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            assertFalse(repo.correctConsumedPurchaseCost(proof))
            assertFalse(db.ledgerDao().correctConsumedPurchaseCostAtomically(proof, current, costClock.instant(), LocalDate.now(costClock)))
            val fresh = costCorrection(repo, "cost-fresh")
            repo.createTransaction(transaction("cost-new-pending"))
            val withPending = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            assertFalse(repo.correctConsumedPurchaseCost(fresh)); assertEquals(withPending, db.ledgerDao().loadHistorySnapshot(FUND_ID.value))
            proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
        } finally { db.close() }
    }
    @Test fun `cost review tampering missing audits and future metadata fail in both repository and DAO before writes`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo); val proof = costCorrection(repo)
            val before = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            for (invalid in listOf(proof.copy(audits = proof.audits.dropLast(1)), proof.copy(confirmed = proof.confirmed.copy(shares = BigDecimal("99.00"))),
                proof.copy(entry = proof.entry.copy(correctedLot = proof.entry.correctedLot.copy(costBasis = BigDecimal("605.00")))),
                proof.copy(preview = proof.preview.copy(preparedAt = costClock.instant().plusSeconds(1))))) {
                expectFailure { repo.correctConsumedPurchaseCost(invalid) }
                expectFailure { db.ledgerDao().correctConsumedPurchaseCostAtomically(invalid, before, costClock.instant(), LocalDate.now(costClock)) }
            }
            assertEquals(before, db.ledgerDao().loadHistorySnapshot(FUND_ID.value)); proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
        } finally { db.close() }
    }
    @Test fun `last cost audit or event collision rolls back projection transaction event and every earlier new audit`() = runTest {
        for (collisionKind in listOf("audit", "event")) {
            val db = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(db.ledgerDao(), costClock); finalReceiptReview(repo); val proof = costCorrection(repo)
                val collision = proof.audits.last().copy(source = CorrectionSource.USER)
                if (collisionKind == "audit") db.ledgerDao().insertCorrection(collision.toEntity())
                else {
                    val otherFund = fund().copy(id = FundId("cost-collision-fund"), code = "OTHER")
                    repo.saveFund(otherFund)
                    val otherLot = LegacyLot(LotId("cost-collision-lot"), otherFund.id, BigDecimal("1.00"), BigDecimal("1.00"), null)
                    db.ledgerDao().insertLedgerEntry(LedgerEntryCodec.encode(LegacyLotOpened(proof.entry.id, 100, otherLot)))
                }
                val before = db.ledgerDao().loadHistorySnapshot(FUND_ID.value)
                expectFailure { repo.correctConsumedPurchaseCost(proof) }
                assertEquals(before, db.ledgerDao().loadHistorySnapshot(FUND_ID.value))
                proof.audits.dropLast(1).forEach { assertNull(repo.findCorrection(it.id)) }
                if (collisionKind == "audit") assertEquals(collision, repo.findCorrection(collision.id))
                else proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
            } finally { db.close() }
        }
    }
    @Test fun `saved cost retry after later sale retains all shares and newer explicit correction blocks old replay`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); val receipt = finalReceiptReview(repo); val proof = costCorrection(repo)
            assertTrue(repo.correctConsumedPurchaseCost(proof))
            val lot = repo.loadLots(FUND_ID).single(); val t = DATE.plusDays(5)
            val pending = receipt.expected.copy(id = TransactionId("cost-later-sale"), status = TransactionStatus.PENDING_CALC,
                authority = DataAuthority.APP_RULE_CALCULATION, tradeDate = t, navDate = t, confirmationDate = t.plusDays(1),
                shares = BigDecimal("20.00"), actualCashFlow = null, cashFlowDate = null, settlementDate = null)
            repo.createTransaction(pending)
            val final = pending.copy(status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
                actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"), cashFlowDate = t.plusDays(1), updatedAt = costClock.instant())
            val nextSequence = repo.loadLedgerEntries(FUND_ID).maxOf { it.sequence } + 1
            repo.confirmRedemption(final, listOf(lot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("404.00"))),
                LotsRedeemed(LedgerEntryId("cost-later-consumed"), nextSequence, FUND_ID, listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("202.00"))), final.id))
            val beforeRetry = repo.loadHistorySnapshot(FUND_ID)
            assertTrue(repo.correctConsumedPurchaseCost(proof)); assertEquals(beforeRetry, repo.loadHistorySnapshot(FUND_ID))
            val next = costCorrection(repo, "cost-second-review")
            repo.saveRuleVersion(rule().copy(id = RuleVersionId("cost-later-rule"), effectiveFrom = t, purchaseFeeRule = PurchaseFeeRule.Rate(BigDecimal("0.02"))))
            assertTrue(repo.correctConsumedPurchaseCost(next)); assertFalse(repo.correctConsumedPurchaseCost(proof))
            assertEquals(beforeRetry.entries, repo.loadLedgerEntries(FUND_ID).dropLast(1)); assertEquals(final, repo.findTransaction(final.id))
            assertEquals(BigDecimal("408.00"), repo.loadLots(FUND_ID).single().costBasis)
            assertEquals(BigDecimal("48.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(
                repo.loadHistorySnapshot(FUND_ID), LocalDate.now(costClock), requireNotNull(fund().precision))).realizedReturn)
            val third = ConsumedPurchaseCostCorrection.prepare("cost-third-review", repo.previewConsumedPurchaseCostImpact(FUND_ID,
                proof.confirmed.id, proof.preview.facts), LocalDate.now(costClock))
            assertEquals(proof.confirmed, third.confirmed) // Same values/time, but a different newer explicit correction.
            assertTrue(repo.correctConsumedPurchaseCost(third)); assertFalse(repo.correctConsumedPurchaseCost(proof))
            assertFalse(repo.correctConsumedPurchaseCost(next)); assertTrue(repo.correctConsumedPurchaseCost(third))
        } finally { db.close() }
    }
    @Test fun `cost correction retains immutable baseline and stale receipt cannot write until effective cost is reviewed again`() = runTest {
        val db = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(db.ledgerDao(), costClock); val receipt = finalReceiptReview(repo); val proof = costCorrection(repo)
            val nav = NavRecord(FUND_ID, DATE.plusDays(7), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("cost-official"), costClock.instant())
            repo.saveNav(nav)
            val baseline = TrackingBaseline.prepare(proof.preview.history, nav, requireNotNull(fund().precision), costClock.instant(), LocalDate.now(costClock))
            assertTrue(repo.establishTrackingBaseline(baseline)); assertTrue(repo.correctConsumedPurchaseCost(proof))
            assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
            val after = repo.loadHistorySnapshot(FUND_ID)
            assertEquals(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED, assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(
                com.ndxinvest.ledger.calculation.LedgerTrackingReturnAssembler.assemble(after, baseline, nav,
                    LocalDate.now(costClock), requireNotNull(fund().precision))).reason)
            assertFalse(repo.correctFinalRedemptionReceipt(receipt))
            val freshReceipt = FinalRedemptionReceiptCorrection.prepare("cost-following-receipt", fund(), after, receipt.expected,
                BigDecimal("460.00"), requireNotNull(receipt.confirmed.cashFlowDate), receipt.expected.confirmationDate,
                costClock.instant(), LocalDate.now(costClock))
            assertEquals(BigDecimal("404.00"), freshReceipt.cost.allocatedCost)
            assertTrue(repo.correctFinalRedemptionReceipt(freshReceipt))
            assertEquals(BigDecimal("56.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(
                repo.loadHistorySnapshot(FUND_ID), LocalDate.now(costClock), requireNotNull(fund().precision))).realizedReturn)
        } finally { db.close() }
    }
    @Test fun `saved cost history audits same retry and a baseline containing correction survive file reopening`() = runTest {
        val path = Files.createTempFile("consumed-cost-saved", ".db")
        lateinit var proof: ConsumedPurchaseCostCorrection
        lateinit var after: LedgerHistorySnapshot
        lateinit var baseline: TrackingBaseline
        val first = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(first.ledgerDao(), costClock); finalReceiptReview(repo); proof = costCorrection(repo)
            assertTrue(repo.correctConsumedPurchaseCost(proof)); after = repo.loadHistorySnapshot(FUND_ID)
            val nav = NavRecord(FUND_ID, DATE.plusDays(7), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("cost-official"), costClock.instant())
            repo.saveNav(nav); baseline = TrackingBaseline.prepare(after, nav, requireNotNull(fund().precision), costClock.instant(), LocalDate.now(costClock))
            assertTrue(repo.establishTrackingBaseline(baseline))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao(), costClock)
            assertEquals(after, repo.loadHistorySnapshot(FUND_ID)); assertTrue(repo.correctConsumedPurchaseCost(proof))
            assertEquals(baseline, repo.findTrackingBaseline(FUND_ID)); proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
            val next = costCorrection(repo, "cost-reopened-review"); assertTrue(repo.correctConsumedPurchaseCost(next)); assertFalse(repo.correctConsumedPurchaseCost(proof))
            assertEquals(BigDecimal("408.00"), assertIs<com.ndxinvest.ledger.calculation.LedgerHistoryInspection.Consistent>(
                repo.inspectHistory(FUND_ID)).redemptionCosts.single().allocatedCost)
        } finally { reopened.close(); Files.deleteIfExists(path) }
    }
    @Test fun `consumed cost preview reads fund and full Room history atomically without modifying facts audits rules or baseline`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val previewClock = java.time.Clock.fixed(LATER.plusSeconds(10 * 86400), java.time.ZoneOffset.UTC)
            val repo = RoomLedgerRepository(database.ledgerDao(), previewClock); val seed = finalReceiptReview(repo)
            val history = repo.loadHistorySnapshot(FUND_ID); val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }
            val baselineNav = NavRecord(FUND_ID, DATE.plusDays(7), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL,
                DataSourceId("official-preview"), previewClock.instant())
            repo.saveNav(baselineNav)
            val baseline = TrackingBaseline.prepare(history, baselineNav, requireNotNull(fund().precision), previewClock.instant(), DATE.plusDays(10))
            assertTrue(repo.establishTrackingBaseline(baseline))
            val rawBefore = database.ledgerDao().loadFundHistorySnapshot(FUND_ID.value); val rules = repo.loadRuleVersions(FUND_ID)
            val oldCashAudit = repo.findCorrection(CorrectionId("cash:${buy.id.value}"))
            val preview = repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, costImpactFacts(history))
            assertEquals(history, preview.history); assertEquals(buy, preview.originalPurchase)
            assertEquals(BigDecimal("404.00"), preview.sales.single().proposedSaleCost)
            assertEquals(BigDecimal("46.00"), preview.sales.single().proposedRecordedIncome)
            assertEquals(BigDecimal("606.00"), preview.proposedLot.costBasis)
            assertEquals(seed.expected, preview.sales.single().sale)
            assertEquals(rawBefore, database.ledgerDao().loadFundHistorySnapshot(FUND_ID.value))
            assertEquals(rules, repo.loadRuleVersions(FUND_ID)); assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
            assertEquals(oldCashAudit, repo.findCorrection(CorrectionId("cash:${buy.id.value}")))
            val unsafe = TransactionCorrectionService.applySalesPlatformFinalCorrection(buy, CorrectionId("unsafe-preview-save"),
                TransactionCorrectionField.GROSS_AMOUNT, CorrectionValue.Decimal(BigDecimal("1010.00")), previewClock.instant())
            expectFailure { repo.recordCorrection(unsafe) }
            assertNull(repo.findCorrection(unsafe.audit.id)); assertEquals(history, repo.loadHistorySnapshot(FUND_ID))
        } finally { database.close() }
    }
    @Test fun `cost impact preview rejects foreign future imprecise and pending facts and rereads changed receipt or precision`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao(), java.time.Clock.fixed(LATER.plusSeconds(10 * 86400), java.time.ZoneOffset.UTC))
            val seed = finalReceiptReview(repo); val history = repo.loadHistorySnapshot(FUND_ID)
            val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }; val facts = costImpactFacts(history)
            val preview = repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, facts)
            for (invalid in listOf(facts.copy(actualCashPaid = BigDecimal("1010.001")), facts.copy(shares = BigDecimal("99.99")),
                facts.copy(paymentDate = DATE.plusDays(11)))) expectFailure { repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, invalid) }
            expectFailure { repo.previewConsumedPurchaseCostImpact(FundId("foreign"), buy.id, facts) }
            expectFailure { repo.previewConsumedPurchaseCostImpact(FUND_ID, TransactionId("foreign"), facts) }
            assertEquals(history, repo.loadHistorySnapshot(FUND_ID))
            assertTrue(repo.correctFinalRedemptionReceipt(seed))
            val current = repo.loadHistorySnapshot(FUND_ID)
            assertFalse(preview.isBasedOn(fund(), current))
            val fresh = repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, facts)
            assertEquals(BigDecimal("56.00"), fresh.sales.single().proposedRecordedIncome)
            assertEquals(BigDecimal("404.00"), fresh.sales.single().proposedSaleCost)
            repo.saveFund(fund().copy(precision = requireNotNull(fund().precision).copy(moneyScale = 3)))
            assertFalse(fresh.isBasedOn(requireNotNull(repo.findFund(FUND_ID)), current))
            repo.createTransaction(seed.expected.copy(id = TransactionId("pending-cost-preview"), status = TransactionStatus.PENDING_CALC,
                authority = DataAuthority.APP_RULE_CALCULATION, actualCashFlow = null, cashFlowDate = null, settlementDate = null))
            val withPending = repo.loadHistorySnapshot(FUND_ID)
            expectFailure { repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, facts) }
            assertEquals(withPending, repo.loadHistorySnapshot(FUND_ID))
        } finally { database.close() }
    }
    @Test fun `persistent reopen after repeated cost previews retains only original recorded transaction and event history`() = runTest {
        val path = Files.createTempFile("consumed-cost-readonly", ".db")
        val clock = java.time.Clock.fixed(LATER.plusSeconds(10 * 86400), java.time.ZoneOffset.UTC)
        lateinit var history: LedgerHistorySnapshot
        lateinit var preview: com.ndxinvest.ledger.rules.ConsumedPurchaseCostImpactPreview
        val first = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(first.ledgerDao(), clock); finalReceiptReview(repo)
            history = repo.loadHistorySnapshot(FUND_ID); val buy = history.transactions.single { it.type == TransactionType.RECURRING_PURCHASE }
            preview = repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, costImpactFacts(history))
            assertEquals(preview, repo.previewConsumedPurchaseCostImpact(FUND_ID, buy.id, costImpactFacts(history)))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao(), clock)
            assertEquals(history, repo.loadHistorySnapshot(FUND_ID)); assertEquals(history.realLots.single().costBasis, BigDecimal("600.00"))
            assertEquals(preview, repo.previewConsumedPurchaseCostImpact(FUND_ID, preview.originalPurchase.id, costImpactFacts(history)))
        } finally { reopened.close(); Files.deleteIfExists(path) }
    }
    private suspend fun finalReceiptReview(repo: RoomLedgerRepository): FinalRedemptionReceiptCorrection {
        val opening = seedShareCorrection(repo)
        val (paid, cashAudit, dateAudit) = purchaseCashConfirmation(opening.expected)
        assertTrue(repo.confirmMatchingCalculatedPurchaseActualCash(opening.expected, opening.expectedLot, paid, cashAudit, dateAudit))
        val t = DATE.plusDays(3)
        val pending = transaction("final-receipt-sale", type = TransactionType.REDEMPTION).copy(requestedAmount = null,
            tradeDate = t, navDate = t, confirmationDate = t.plusDays(1), shares = BigDecimal("40.00"))
        repo.createTransaction(pending)
        val calculated = pending.copy(status = TransactionStatus.CALCULATED, grossAmount = BigDecimal("396.00"), netAmount = BigDecimal("392.04"),
            redemptionFee = BigDecimal("3.96"), unitNav = BigDecimal("9.9000"), updatedAt = LATER.plusSeconds(5 * 86400))
        val lot = opening.expectedLot
        assertTrue(repo.materializeCalculatedRedemption(calculated, listOf(lot),
            listOf(lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))), LedgerEntryId("receipt-consumed"),
            listOf(LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("400.00")))))
        val correctedCash = TransactionCorrectionService.applySalesPlatformFinalCorrection(calculated, CorrectionId("receipt-initial-cash"),
            TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(BigDecimal("450.00")), LATER.plusSeconds(6 * 86400))
        val correctedDate = TransactionCorrectionService.applySalesPlatformFinalCorrection(correctedCash.transaction, CorrectionId("receipt-initial-cash-date"),
            TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(t.plusDays(2)), LATER.plusSeconds(6 * 86400))
        val final = correctedDate.transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL, netAmount = BigDecimal("450.00"))
        assertTrue(repo.confirmCalculatedRedemptionActualCash(calculated, final, correctedCash.audit, correctedDate.audit))
        return FinalRedemptionReceiptCorrection.prepare("receipt-review", fund(), repo.loadHistorySnapshot(FUND_ID), final,
            BigDecimal("460.00"), t.plusDays(1), final.confirmationDate, LATER.plusSeconds(7 * 86400), DATE.plusDays(7))
    }
    @Test fun `final receipt correction commits full audits and changes income without rewriting any lot or event`() = withRepository { repo ->
        val proof = finalReceiptReview(repo)
        assertTrue(repo.correctFinalRedemptionReceipt(proof))
        assertEquals(proof.confirmed, repo.findTransaction(proof.expected.id))
        proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        val after = repo.loadHistorySnapshot(FUND_ID)
        assertEquals(proof.history.realLots, after.realLots); assertEquals(proof.history.entries, after.entries)
        assertEquals(BigDecimal("60.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after,
            DATE.plusDays(7), requireNotNull(fund().precision))).realizedReturn)
        assertTrue(repo.correctFinalRedemptionReceipt(proof)); assertEquals(after, repo.loadHistorySnapshot(FUND_ID))
    }
    @Test fun `stale final receipt fund history or newer correction cannot overwrite current final facts`() = withRepository { repo ->
        val proof = finalReceiptReview(repo)
        repo.saveFund(fund().copy(cardStyle = "changed"))
        assertFalse(repo.correctFinalRedemptionReceipt(proof)); repo.saveFund(fund())
        val newer = FinalRedemptionReceiptCorrection.prepare("newer-review", fund(), proof.history, proof.expected,
            BigDecimal("470.00"), proof.previousCashDate, proof.expected.confirmationDate, proof.confirmed.updatedAt, DATE.plusDays(7))
        assertTrue(repo.correctFinalRedemptionReceipt(newer)); assertFalse(repo.correctFinalRedemptionReceipt(proof))
        assertEquals(newer.confirmed, repo.findTransaction(proof.expected.id)); proof.audits.forEach { assertNull(repo.findCorrection(it.id)) }
        val another = FinalRedemptionReceiptCorrection.prepare("third-review", fund(), repo.loadHistorySnapshot(FUND_ID), newer.confirmed,
            BigDecimal("480.00"), proof.previousCashDate, proof.expected.confirmationDate, proof.confirmed.updatedAt, DATE.plusDays(7))
        repo.createTransaction(transaction("new-pending-history"))
        val before = repo.loadHistorySnapshot(FUND_ID)
        assertFalse(repo.correctFinalRedemptionReceipt(another)); assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
    }
    @Test fun `last receipt audit identity collision rolls back cash date net and all earlier new audits`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao()); val proof = finalReceiptReview(repo)
            val collision = proof.audits.last().copy(correctedValue = CorrectionValue.Date(DATE.plusDays(5)))
            database.ledgerDao().insertCorrection(collision.toEntity())
            expectFailure { repo.correctFinalRedemptionReceipt(proof) }
            assertEquals(proof.history, repo.loadHistorySnapshot(FUND_ID))
            proof.audits.dropLast(1).forEach { assertNull(repo.findCorrection(it.id)) }
            assertEquals(collision, repo.findCorrection(collision.id))
        } finally { database.close() }
    }
    @Test fun `tampered receipt bundle and generic final redemption corrections cannot bypass audited history guard`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val repo = RoomLedgerRepository(database.ledgerDao()); val proof = finalReceiptReview(repo)
            for (invalid in listOf(proof.copy(audits = proof.audits.dropLast(1)),
                proof.copy(confirmed = proof.confirmed.copy(shares = BigDecimal("39.00"))),
                proof.copy(confirmed = proof.confirmed.copy(unitNav = BigDecimal("10.0000"))),
                proof.copy(cost = proof.cost.copy(allocatedCost = BigDecimal("399.00"))),
                proof.copy(confirmed = proof.confirmed.copy(updatedAt = java.time.Instant.now().plusSeconds(86400)))))
                expectFailure { repo.correctFinalRedemptionReceipt(invalid) }
            val rows = database.ledgerDao().loadHistorySnapshot(FUND_ID.value)
            expectFailure { database.ledgerDao().correctFinalRedemptionReceiptAtomically(fund().toEntity(), rows, proof.expected.toEntity(),
                proof.confirmed.copy(shares = BigDecimal("39.00")).toEntity(), proof.audits.map { it.toEntity() }) }
            expectFailure { database.ledgerDao().correctFinalRedemptionReceiptAtomically(fund().toEntity(), rows, proof.expected.toEntity(),
                proof.confirmed.toEntity(), proof.audits.dropLast(1).map { it.toEntity() }) }
            for ((field, value) in listOf(TransactionCorrectionField.ACTUAL_CASH_FLOW to CorrectionValue.Decimal(BigDecimal("460.00")),
                TransactionCorrectionField.SHARES to CorrectionValue.Decimal(BigDecimal("39.00")),
                TransactionCorrectionField.CASH_FLOW_DATE to CorrectionValue.Date(DATE.plusDays(4)),
                TransactionCorrectionField.CONFIRMATION_DATE to CorrectionValue.Date(DATE.plusDays(3)))) {
                val generic = TransactionCorrectionService.applySalesPlatformFinalCorrection(proof.expected, CorrectionId("generic:$field"),
                    field, value, proof.confirmed.updatedAt)
                expectFailure { repo.recordCorrection(generic) }
                assertNull(repo.findCorrection(generic.audit.id))
            }
            assertEquals(proof.history, repo.loadHistorySnapshot(FUND_ID))
        } finally { database.close() }
    }
    @Test fun `earlier receipt correction leaves later consumed cost and changed rule version untouched`() = withRepository { repo ->
        val initial = finalReceiptReview(repo)
        val lot = initial.history.realLots.single(); val t = initial.expected.tradeDate.plusDays(1)
        val later = initial.expected.copy(id = TransactionId("later-final-sale"), status = TransactionStatus.PENDING_CALC,
            authority = DataAuthority.APP_RULE_CALCULATION, tradeDate = t, navDate = t, confirmationDate = t, settlementDate = null,
            actualCashFlow = null, cashFlowDate = null, netAmount = null, grossAmount = null, unitNav = null, redemptionFee = null,
            shares = BigDecimal("20.00"), isManualCorrected = false)
        repo.createTransaction(later)
        val final = later.copy(status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            actualCashFlow = BigDecimal("210.00"), netAmount = BigDecimal("210.00"), cashFlowDate = t.plusDays(1))
        repo.confirmRedemption(final, listOf(lot.copy(remainingShares = BigDecimal("40.00"), costBasis = BigDecimal("400.00"))),
            LotsRedeemed(LedgerEntryId("later-consumed"), 3, FUND_ID, listOf(LotConsumption(lot.id, BigDecimal("20.00"), BigDecimal("200.00"))), final.id))
        repo.saveRuleVersion(rule().copy(id = RuleVersionId("later-rule"), effectiveFrom = t, purchaseFeeRule = PurchaseFeeRule.Rate(BigDecimal("0.02"))))
        val before = repo.loadHistorySnapshot(FUND_ID)
        val proof = FinalRedemptionReceiptCorrection.prepare("after-later-sale", fund(), before, initial.expected,
            BigDecimal("460.00"), initial.confirmed.cashFlowDate!!, initial.expected.confirmationDate, initial.confirmed.updatedAt, DATE.plusDays(7))
        assertTrue(repo.correctFinalRedemptionReceipt(proof))
        val after = repo.loadHistorySnapshot(FUND_ID)
        assertEquals(before.realLots, after.realLots); assertEquals(before.entries, after.entries)
        assertEquals(final, repo.findTransaction(final.id)); assertEquals(initial.expected.ruleVersionId, repo.findTransaction(initial.expected.id)?.ruleVersionId)
        assertEquals(BigDecimal("70.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after,
            DATE.plusDays(7), requireNotNull(fund().precision))).realizedReturn)
    }
    @Test fun `final Legacy receipt correction never creates historical NAV cash or a replacement cost`() = withRepository { repo ->
        seedFundAndRule(repo)
        val imported = transaction("receipt-legacy-import", type = TransactionType.ADJUSTMENT, status = TransactionStatus.CONFIRMED_ACTUAL,
            origin = TransactionOrigin.IMPORTED, authority = DataAuthority.USER_OR_PLATFORM_FINAL)
        val lot = LegacyLot(LotId("receipt-legacy"), FUND_ID, BigDecimal("100.00"), BigDecimal("500.00"), null)
        repo.recordLegacyLot(imported, lot, LegacyLotOpened(LedgerEntryId("receipt-legacy-open"), 1, lot))
        val sale = transaction("receipt-legacy-sale", type = TransactionType.REDEMPTION, status = TransactionStatus.CONFIRMED_ACTUAL,
            authority = DataAuthority.USER_OR_PLATFORM_FINAL, actualCashFlow = BigDecimal("250.00")).copy(
            netAmount = BigDecimal("250.00"), shares = BigDecimal("40.00"), navDate = null, confirmationDate = null, settlementDate = DATE)
        repo.recordConfirmedLegacyRedemption(sale, lot, lot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("300.00")),
            LegacyLotRedeemed(LedgerEntryId("receipt-legacy-consumed"), 2, FUND_ID, LotConsumption(lot.id, BigDecimal("40.00"), BigDecimal("200.00")), sale.id))
        val before = repo.loadHistorySnapshot(FUND_ID)
        val proof = FinalRedemptionReceiptCorrection.prepare("legacy-receipt-review", fund(), before, sale, BigDecimal("260.00"),
            DATE.plusDays(1), null, LATER.plusSeconds(2 * 86400), DATE.plusDays(2))
        assertTrue(repo.correctFinalRedemptionReceipt(proof))
        val after = repo.loadHistorySnapshot(FUND_ID)
        assertEquals(before.entries, after.entries); assertEquals(before.legacyLots, after.legacyLots); assertNull(repo.findNav(FUND_ID, DATE))
        assertEquals(BigDecimal("60.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(after,
            DATE.plusDays(2), requireNotNull(fund().precision))).realizedReturn)
    }
    @Test fun `receipt audits survive reopen and another explicit correction without allowing old replay`() = runTest {
        val path = Files.createTempDirectory("ndx-final-receipt").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val proof: FinalRedemptionReceiptCorrection
        try {
            val repo = RoomLedgerRepository(first.ledgerDao()); proof = finalReceiptReview(repo)
            assertTrue(repo.correctFinalRedemptionReceipt(proof))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(proof.confirmed, repo.findTransaction(proof.expected.id)); assertTrue(repo.correctFinalRedemptionReceipt(proof))
            proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
            val next = FinalRedemptionReceiptCorrection.prepare("next-receipt-review", fund(), repo.loadHistorySnapshot(FUND_ID), proof.confirmed,
                BigDecimal("470.00"), requireNotNull(proof.confirmed.cashFlowDate), proof.confirmed.confirmationDate,
                proof.confirmed.updatedAt.plusSeconds(1), DATE.plusDays(7))
            assertTrue(repo.correctFinalRedemptionReceipt(next)); assertFalse(repo.correctFinalRedemptionReceipt(proof))
            proof.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }; next.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
            assertEquals(proof.history.entries, repo.loadLedgerEntries(FUND_ID))
        } finally { reopened.close() }
    }
    @Test fun `pre baseline receipt correction preserves immutable start and requires review instead of inventing a new period return`() = withRepository { repo ->
        val proof = finalReceiptReview(repo)
        val nav = NavRecord(FUND_ID, DATE.plusDays(6), BigDecimal("10.0000"), null, DataAuthority.FUND_OFFICIAL, DataSourceId("official"),
            LATER.plusSeconds(6 * 86400))
        repo.saveNav(nav)
        val baseline = TrackingBaseline.prepare(proof.history, nav, requireNotNull(fund().precision), nav.fetchedAt, DATE.plusDays(7))
        assertTrue(repo.establishTrackingBaseline(baseline)); assertTrue(repo.correctFinalRedemptionReceipt(proof))
        val after = repo.loadHistorySnapshot(FUND_ID)
        assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
        val blocked = assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(com.ndxinvest.ledger.calculation.LedgerTrackingReturnAssembler.assemble(
            after, baseline, nav, DATE.plusDays(7), requireNotNull(fund().precision)))
        assertEquals(AnnualizedReturnGapReason.BASELINE_HISTORY_CHANGED, blocked.reason)
        assertEquals(after, repo.loadHistorySnapshot(FUND_ID))
    }
    private fun withRepository(block: suspend (RoomLedgerRepository) -> Unit) = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            block(RoomLedgerRepository(database.ledgerDao()))
        } finally {
            database.close()
        }
    }

    @Test
    fun `matching purchase cash confirms ordinary and assumed recurring facts atomically without changing lots`() =
        withRepository { repo ->
            seedFundAndRule(repo)
            listOf(TransactionType.PURCHASE, TransactionType.RECURRING_PURCHASE).forEach { type ->
                val pending = transaction(
                    id = "cash-${type.name}", type = type,
                    origin = if (type == TransactionType.RECURRING_PURCHASE) TransactionOrigin.AUTO_PLAN_ASSUMED else TransactionOrigin.USER_ENTERED,
                )
                repo.createTransaction(pending)
                val calculated = calculatedPurchase(pending)
                val lot = lot("lot-${type.name}", pending.id)
                repo.materializeCalculatedPurchase(calculated, lot, LedgerEntryId("open-${type.name}"))
                val entries = repo.loadLedgerEntries(FUND_ID)
                val (confirmed, cashAudit, paymentAudit) = purchaseCashConfirmation(calculated)
                assertEquals(false, repo.confirmMatchingCalculatedPurchaseActualCash(
                    calculated, lot, confirmed.copy(shares = BigDecimal("99.00")), cashAudit, paymentAudit,
                ))
                assertEquals(false, repo.confirmMatchingCalculatedPurchaseActualCash(
                    calculated, lot.copy(remainingShares = BigDecimal("50.00")), confirmed, cashAudit, paymentAudit,
                ))
                assertEquals(calculated, repo.findTransaction(pending.id))
                assertNull(repo.findCorrection(cashAudit.id))
                assertEquals(true, repo.confirmMatchingCalculatedPurchaseActualCash(
                    calculated, lot, confirmed, cashAudit, paymentAudit,
                ))
                assertEquals(confirmed, repo.findTransaction(pending.id))
                assertEquals(lot, repo.findLotBySourceTransaction(pending.id))
                assertEquals(entries, repo.loadLedgerEntries(FUND_ID))
                assertEquals(cashAudit, repo.findCorrection(cashAudit.id))
                assertEquals(paymentAudit, repo.findCorrection(paymentAudit.id))
                assertEquals(calculated.origin, confirmed.origin)
                assertEquals(DATE.minusDays(1), confirmed.cashFlowDate)
                assertNull(confirmed.settlementDate)
                assertEquals(calculated.tradeDate, confirmed.tradeDate)
                assertEquals(calculated.navDate, confirmed.navDate)
                assertEquals(false, repo.confirmMatchingCalculatedPurchaseActualCash(
                    calculated, lot, confirmed, cashAudit, paymentAudit,
                ))
                val performance = LedgerCashFlowAssembler.assemble(
                    FUND_ID, listOf(confirmed), BigDecimal("1100.00"), LocalDate.parse("2026-09-15"),
                    HistoryScope.FULL_HISTORY, false, precision = requireNotNull(fund().precision),
                ) as LedgerCashFlowAssembly.Complete
                assertEquals(BigDecimal("100.00"), performance.performance.cumulativeReturn)
                assertEquals(DATE.minusDays(1), performance.cashFlows.first().date)
            }
        }

    @Test
    fun `purchase cash confirmation refuses a stale consumed lot and never restores sold shares`() = withRepository { repo ->
        seedFundAndRule(repo)
        val purchase = transaction("cash-after-sale")
        repo.createTransaction(purchase)
        val calculatedPurchase = calculatedPurchase(purchase)
        val openedLot = lot("cash-after-sale-lot", purchase.id)
        repo.materializeCalculatedPurchase(calculatedPurchase, openedLot, LedgerEntryId("cash-after-sale-open"))
        val sale = transaction("cash-after-sale-redemption", type = TransactionType.REDEMPTION)
            .copy(requestedAmount = null, shares = BigDecimal("40.00"))
        repo.createTransaction(sale)
        val calculatedSale = sale.copy(
            status = TransactionStatus.CALCULATED, grossAmount = BigDecimal("396.00"),
            netAmount = BigDecimal("392.04"), unitNav = BigDecimal("9.9000"),
            redemptionFee = BigDecimal("3.96"), updatedAt = LATER,
        )
        val remainingLot = openedLot.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
        assertEquals(true, repo.materializeCalculatedRedemption(
            calculatedSale, listOf(openedLot), listOf(remainingLot), LedgerEntryId("cash-after-sale-consumed"),
            listOf(LotConsumption(openedLot.id, BigDecimal("40.00"), BigDecimal("400.00"))),
        ))
        val entries = repo.loadLedgerEntries(FUND_ID)
        val (confirmed, cashAudit, paymentAudit) = purchaseCashConfirmation(calculatedPurchase)
        assertEquals(false, repo.confirmMatchingCalculatedPurchaseActualCash(
            calculatedPurchase, openedLot, confirmed, cashAudit, paymentAudit,
        ))
        assertEquals(true, repo.confirmMatchingCalculatedPurchaseActualCash(
            calculatedPurchase, remainingLot, confirmed, cashAudit, paymentAudit,
        ))
        assertEquals(remainingLot, repo.findLotBySourceTransaction(purchase.id))
        assertEquals(entries, repo.loadLedgerEntries(FUND_ID))
    }

    @Test
    fun `purchase cash audit collision rolls back transaction and first audit together`() = withRepository { repo ->
        seedFundAndRule(repo)
        val pending = transaction("cash-rollback")
        repo.createTransaction(pending)
        val calculated = calculatedPurchase(pending)
        val lot = lot("cash-rollback-lot", pending.id)
        repo.materializeCalculatedPurchase(calculated, lot, LedgerEntryId("cash-rollback-open"))
        val (confirmed, cashAudit, paymentAudit) = purchaseCashConfirmation(calculated)
        val other = transaction("audit-owner")
        repo.createTransaction(other)
        val occupied = TransactionCorrectionService.applyManualCorrection(
            other, paymentAudit.id, TransactionCorrectionField.UNIT_NAV,
            CorrectionValue.Decimal(BigDecimal("1.0000")), LATER,
        )
        repo.recordCorrection(occupied)
        expectFailure {
            repo.confirmMatchingCalculatedPurchaseActualCash(calculated, lot, confirmed, cashAudit, paymentAudit)
        }
        assertEquals(calculated, repo.findTransaction(pending.id))
        assertEquals(lot, repo.findLotBySourceTransaction(pending.id))
        assertNull(repo.findCorrection(cashAudit.id))
        assertEquals(occupied.audit, repo.findCorrection(paymentAudit.id))
    }

    @Test
    fun `pre trade cash date and audit survive closing and reopening the persistent database`() = runTest {
        val path = Files.createTempDirectory("ndx-purchase-cash-date").resolve("ledger.db").toString()
        val calculated = calculatedPurchase(transaction("durable-pre-trade-payment"))
        val originalLot = lot("durable-pre-trade-lot", calculated.id)
        val (confirmed, cashAudit, paymentAudit) = purchaseCashConfirmation(calculated)
        val first = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            seedFundAndRule(repo)
            repo.createTransaction(transaction(calculated.id.value))
            repo.materializeCalculatedPurchase(calculated, originalLot, LedgerEntryId("durable-pre-trade-open"))
            assertEquals(true, repo.confirmMatchingCalculatedPurchaseActualCash(
                calculated, originalLot, confirmed, cashAudit, paymentAudit,
            ))
        } finally {
            first.close()
        }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(confirmed, repo.findTransaction(calculated.id))
            assertEquals(DATE.minusDays(1), repo.findTransaction(calculated.id)?.cashFlowDate)
            assertNull(repo.findTransaction(calculated.id)?.settlementDate)
            assertEquals(originalLot, repo.findLotBySourceTransaction(calculated.id))
            assertEquals(cashAudit, repo.findCorrection(cashAudit.id))
            assertEquals(paymentAudit, repo.findCorrection(paymentAudit.id))
            assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `restricted platform correction preserves opening history and updates Room projection atomically`() = withRepository { repo ->
        val confirmation = seedShareCorrection(repo)
        val opening = repo.loadLedgerEntries(FUND_ID).single()
        assertEquals(true, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        assertEquals(confirmation.confirmed, repo.findTransaction(confirmation.expected.id))
        val entries = repo.loadLedgerEntries(FUND_ID)
        assertEquals(opening, entries.first())
        assertEquals(2L, entries.last().sequence)
        assertEquals(confirmation.entry.correctedLot, repo.loadLots(FUND_ID).single())
        assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(entries).realLots)
        confirmation.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        assertEquals(false, repo.materializeCalculatedPurchase(
            confirmation.expected, confirmation.expectedLot, LedgerEntryId("platform-share-original-open"),
        ))
        assertEquals(2, repo.loadLedgerEntries(FUND_ID).size)
    }

    @Test
    fun `unfinished pending planned and abnormal redemptions all block share correction inside Room`() = runTest {
        listOf(TransactionStatus.PENDING_CALC, TransactionStatus.PLANNED, TransactionStatus.ABNORMAL).forEach { status ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val confirmation = seedShareCorrection(repo)
                val sale = transaction("blocked-sale", type = TransactionType.REDEMPTION).copy(status = status, shares = BigDecimal("20.00"))
                repo.createTransaction(sale)
                assertEquals(false, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
                assertEquals(confirmation.expected, repo.findTransaction(confirmation.expected.id))
                assertEquals(confirmation.expectedLot, repo.loadLots(FUND_ID).single())
                assertNull(repo.findCorrection(confirmation.audits.first().id))
                assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
            } finally { database.close() }
        }
    }

    @Test
    fun `later sale of another lot also blocks correction rather than reordering historical FIFO`() = withRepository { repo ->
        val confirmation = seedShareCorrection(repo)
        val other = transaction("earlier-lot-source")
        repo.createTransaction(other)
        val original = lot("earlier-lot", other.id)
        repo.materializeCalculatedPurchase(calculatedPurchase(other), original, LedgerEntryId("earlier-lot-open"))
        val sale = transaction("later-sale", type = TransactionType.REDEMPTION).copy(
            tradeDate = DATE.plusDays(4), navDate = DATE.plusDays(4), confirmationDate = DATE.plusDays(4),
        )
        repo.createTransaction(sale)
        repo.confirmRedemption(sale.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("10.00")),
            listOf(original.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))),
            LotsRedeemed(LedgerEntryId("later-sale-event"), 3, FUND_ID,
                listOf(LotConsumption(original.id, BigDecimal("10.00"), BigDecimal("100.00")))))
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        assertEquals(confirmation.expectedLot, repo.findLotBySourceTransaction(confirmation.expected.id))
        assertNull(repo.findCorrection(confirmation.audits.first().id))
    }

    @Test
    fun `consumption occurring after preparing correction cannot restore historical sold shares`() = withRepository { repo ->
        val confirmation = seedShareCorrection(repo)
        val before = confirmation.expectedLot
        val after = before.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))
        val pending = transaction("concurrent-sale", type = TransactionType.REDEMPTION)
        repo.createTransaction(pending)
        repo.confirmRedemption(pending.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("10.00")),
            listOf(after), LotsRedeemed(LedgerEntryId("concurrent-sale-event"), 2, FUND_ID,
                listOf(LotConsumption(before.id, BigDecimal("10.00"), BigDecimal("100.00")))))
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        assertEquals(after, repo.loadLots(FUND_ID).single())
        assertEquals(confirmation.expected, repo.findTransaction(confirmation.expected.id))
        assertNull(repo.findCorrection(confirmation.audits.first().id))
    }

    @Test
    fun `tampered fields missing audits and stale transaction cannot finalize a purchase`() = withRepository { repo ->
        val confirmation = seedShareCorrection(repo)
        expectFailure { repo.confirmUnconsumedPurchasePlatformShares(confirmation.copy(
            confirmed = confirmation.confirmed.copy(requestedAmount = BigDecimal("999.00")),
        )) }
        expectFailure { repo.confirmUnconsumedPurchasePlatformShares(confirmation.copy(audits = confirmation.audits.dropLast(1))) }
        assertEquals(confirmation.expectedLot, repo.loadLots(FUND_ID).single())
        repo.recordCorrection(TransactionCorrectionService.applyManualCorrection(
            confirmation.expected, CorrectionId("concurrent-update"), TransactionCorrectionField.ACTUAL_CASH_FLOW,
            CorrectionValue.Decimal(BigDecimal("-1000.00")), LATER.plusSeconds(1),
        ))
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        assertNull(repo.findCorrection(confirmation.audits.first().id))
        assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
    }

    @Test
    fun `late audit and ledger event identity conflicts roll back transaction lot and every new audit`() = runTest {
        listOf("audit", "event").forEach { conflict ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val confirmation = seedShareCorrection(repo)
                val other = transaction("conflict-owner")
                repo.createTransaction(other)
                if (conflict == "audit") {
                    repo.recordCorrection(TransactionCorrectionService.applyManualCorrection(
                        other, confirmation.audits.last().id, TransactionCorrectionField.UNIT_NAV,
                        CorrectionValue.Decimal(BigDecimal("1.0000")), LATER,
                    ))
                } else {
                    repo.materializeCalculatedPurchase(calculatedPurchase(other), lot("conflict-owner-lot", other.id), confirmation.entry.id)
                }
                val beforeEntries = repo.loadLedgerEntries(FUND_ID)
                expectFailure { repo.confirmUnconsumedPurchasePlatformShares(confirmation) }
                assertEquals(confirmation.expected, repo.findTransaction(confirmation.expected.id))
                assertEquals(confirmation.expectedLot, repo.findLotBySourceTransaction(confirmation.expected.id))
                confirmation.audits.dropLast(1).forEach { assertNull(repo.findCorrection(it.id)) }
                if (conflict == "event") assertNull(repo.findCorrection(confirmation.audits.last().id))
                assertEquals(beforeEntries, repo.loadLedgerEntries(FUND_ID))
            } finally { database.close() }
        }
    }

    @Test
    fun `platform corrected lot audits and replay survive persistent database reopen`() = runTest {
        val path = Files.createTempDirectory("ndx-platform-share-correction").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val confirmation: PurchasePlatformShareConfirmation
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            confirmation = seedShareCorrection(repo)
            assertEquals(true, repo.confirmUnconsumedPurchasePlatformShares(confirmation))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(confirmation.confirmed, repo.findTransaction(confirmation.expected.id))
            assertEquals(confirmation.entry.correctedLot, repo.loadLots(FUND_ID).single())
            assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(repo.loadLedgerEntries(FUND_ID)).realLots)
            confirmation.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        } finally { reopened.close() }
    }

    private suspend fun seedShareCorrection(repo: RoomLedgerRepository): PurchasePlatformShareConfirmation {
        seedFundAndRule(repo)
        val pending = transaction("platform-share-correction", type = TransactionType.RECURRING_PURCHASE,
            origin = TransactionOrigin.AUTO_PLAN_ASSUMED)
        repo.createTransaction(pending)
        val calculated = calculatedPurchase(pending)
        val originalLot = lot("platform-share-lot", pending.id)
        repo.materializeCalculatedPurchase(calculated, originalLot, LedgerEntryId("platform-share-original-open"))
        return PurchasePlatformShareConfirmation.prepare(
            calculated, originalLot, requireNotNull(fund().precision), BigDecimal("1000.00"), DATE.minusDays(1),
            BigDecimal("99.99"), originalLot.confirmationDate.plusDays(1), LATER.plusSeconds(7 * 86400), DATE.plusDays(7),
        )
    }

    @Test
    fun `platform amount fee cost cash and all audits commit together and feed cumulative return`() = withRepository { repo ->
        val correction = seedAmountCorrection(repo)
        val opening = repo.loadLedgerEntries(FUND_ID).single()
        assertEquals(true, repo.confirmUnconsumedPurchasePlatformAmounts(correction))
        val confirmed = requireNotNull(repo.findTransaction(correction.expected.id))
        assertEquals(correction.confirmed, confirmed)
        assertEquals(correction.entry.correctedLot, repo.loadLots(FUND_ID).single())
        val entries = repo.loadLedgerEntries(FUND_ID)
        assertEquals(opening, entries.first())
        assertEquals(2L, entries.last().sequence)
        assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(entries).realLots)
        correction.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        assertEquals(6, correction.audits.size)
        val performance = LedgerCashFlowAssembler.assemble(
            FUND_ID, listOf(confirmed), BigDecimal("891.00"), DATE.plusDays(7), HistoryScope.FULL_HISTORY,
            false, precision = requireNotNull(fund().precision),
        ) as LedgerCashFlowAssembly.Complete
        assertEquals(BigDecimal("-9.00"), performance.performance.cumulativeReturn)
        assertEquals(BigDecimal("-900.00"), performance.cashFlows.first().amount)
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformAmounts(correction))
        assertEquals(false, repo.materializeCalculatedPurchase(correction.expected, correction.expectedLot, opening.id))
        assertEquals(2, repo.loadLedgerEntries(FUND_ID).size)
    }

    @Test
    fun `all unfinished sale states and potentially affected historical sales block amount reconciliation`() = runTest {
        listOf(TransactionStatus.PLANNED, TransactionStatus.PENDING_CALC, TransactionStatus.ABNORMAL,
            TransactionStatus.CALCULATED, TransactionStatus.CONFIRMED_ACTUAL).forEach { status ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val correction = seedAmountCorrection(repo)
                repo.createTransaction(transaction("amount-blocking-sale", type = TransactionType.REDEMPTION,
                    status = status,
                    authority = if (status == TransactionStatus.CONFIRMED_ACTUAL) DataAuthority.USER_OR_PLATFORM_FINAL else DataAuthority.APP_RULE_CALCULATION,
                ).copy(tradeDate = DATE.plusDays(3), navDate = DATE.plusDays(3), shares = BigDecimal("10.00")))
                assertEquals(false, repo.confirmUnconsumedPurchasePlatformAmounts(correction))
                assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
                assertEquals(correction.expectedLot, repo.findLotBySourceTransaction(correction.expected.id))
                assertNull(repo.findCorrection(correction.audits.first().id))
            } finally { database.close() }
        }
    }

    @Test
    fun `tampered money bundle and direct generic gross net correction cannot bypass Lot reconciliation`() = withRepository { repo ->
        val correction = seedAmountCorrection(repo)
        expectFailure { repo.confirmUnconsumedPurchasePlatformAmounts(correction.copy(audits = correction.audits.dropLast(1))) }
        expectFailure { repo.confirmUnconsumedPurchasePlatformAmounts(correction.copy(
            confirmed = correction.confirmed.copy(grossAmount = BigDecimal("899.00")),
        )) }
        listOf(TransactionCorrectionField.GROSS_AMOUNT, TransactionCorrectionField.NET_AMOUNT).forEach { field ->
            val generic = TransactionCorrectionService.applySalesPlatformFinalCorrection(
                correction.expected, CorrectionId("unsafe-generic-${field.name}"), field,
                CorrectionValue.Decimal(BigDecimal("900.00")), correction.confirmed.updatedAt,
            )
            expectFailure { repo.recordCorrection(generic) }
            assertNull(repo.findCorrection(generic.audit.id))
        }
        assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
        assertEquals(correction.expectedLot, repo.loadLots(FUND_ID).single())
        assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
    }

    @Test
    fun `stale consumed lot cannot restore original cost through amount correction`() = withRepository { repo ->
        val correction = seedAmountCorrection(repo)
        val before = correction.expectedLot
        val after = before.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00"))
        val pending = transaction("amount-concurrent-sale", type = TransactionType.REDEMPTION)
        repo.createTransaction(pending)
        repo.confirmRedemption(pending.copy(status = TransactionStatus.CALCULATED, shares = BigDecimal("10.00")), listOf(after),
            LotsRedeemed(LedgerEntryId("amount-sale-event"), 2, FUND_ID,
                listOf(LotConsumption(before.id, BigDecimal("10.00"), BigDecimal("100.00")))))
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformAmounts(correction))
        assertEquals(after, repo.loadLots(FUND_ID).single())
        assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
        assertNull(repo.findCorrection(correction.audits.first().id))
    }

    @Test
    fun `late amount audit and immutable event collisions roll back every new monetary fact`() = runTest {
        listOf("audit", "event").forEach { conflict ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val correction = seedAmountCorrection(repo)
                val other = transaction("amount-conflict-owner")
                repo.createTransaction(other)
                if (conflict == "audit") {
                    repo.recordCorrection(TransactionCorrectionService.applyManualCorrection(
                        other, correction.audits.last().id, TransactionCorrectionField.UNIT_NAV,
                        CorrectionValue.Decimal(BigDecimal("1.0000")), LATER,
                    ))
                } else {
                    repo.materializeCalculatedPurchase(calculatedPurchase(other), lot("amount-conflict-owner-lot", other.id), correction.entry.id)
                }
                val entries = repo.loadLedgerEntries(FUND_ID)
                expectFailure { repo.confirmUnconsumedPurchasePlatformAmounts(correction) }
                assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
                assertEquals(correction.expectedLot, repo.findLotBySourceTransaction(correction.expected.id))
                correction.audits.dropLast(1).forEach { assertNull(repo.findCorrection(it.id)) }
                if (conflict == "event") assertNull(repo.findCorrection(correction.audits.last().id))
                assertEquals(entries, repo.loadLedgerEntries(FUND_ID))
            } finally { database.close() }
        }
    }

    @Test
    fun `amount corrected transaction cost fee and events survive persistent database reopening`() = runTest {
        val path = Files.createTempDirectory("ndx-platform-amount-correction").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val correction: PurchasePlatformAmountConfirmation
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            correction = seedAmountCorrection(repo)
            assertEquals(true, repo.confirmUnconsumedPurchasePlatformAmounts(correction))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(correction.confirmed, repo.findTransaction(correction.expected.id))
            assertEquals(correction.entry.correctedLot, repo.loadLots(FUND_ID).single())
            assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(repo.loadLedgerEntries(FUND_ID)).realLots)
            correction.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        } finally { reopened.close() }
    }

    @Test
    fun `official NAV reconciliation commits source witness audits and lot without rewriting original event`() = withRepository { repo ->
        val correction = seedNavCorrection(repo)
        val opening = repo.loadLedgerEntries(FUND_ID).single()
        assertEquals(true, repo.confirmUnconsumedPurchasePlatformNav(correction))
        assertEquals(correction.confirmed, repo.findTransaction(correction.expected.id))
        assertEquals(correction.entry.correctedLot, repo.loadLots(FUND_ID).single())
        val entries = repo.loadLedgerEntries(FUND_ID)
        assertEquals(opening, entries.first())
        assertEquals(correction.entry.copy(sequence = 2), entries.last())
        assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(entries).realLots)
        correction.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        assertEquals(false, repo.confirmUnconsumedPurchasePlatformNav(correction))
        assertEquals(false, repo.materializeCalculatedPurchase(correction.expected, correction.expectedLot, opening.id))
        assertEquals(correction.expected.navDate, repo.findTransaction(correction.expected.id)?.navDate)
    }

    @Test
    fun `missing exact NAV snapshot or changed official evidence rejects reconciliation atomically`() = runTest {
        listOf("missing", "changed", "source", "authority").forEach { change ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val correction = seedNavCorrection(repo, persistNav = change != "missing")
                val witness = correction.entry.officialNav
                when (change) {
                    "missing" -> repo.saveNav(witness.copy(navDate = witness.navDate.plusDays(1)))
                    "changed" -> repo.saveNav(witness.copy(unitNav = BigDecimal("9.7000"), fetchedAt = witness.fetchedAt.plusSeconds(1)))
                    "source" -> repo.saveNav(witness.copy(source = DataSourceId("other-official"), fetchedAt = witness.fetchedAt.plusSeconds(1)))
                    "authority" -> repo.saveNav(witness.copy(authority = DataAuthority.USER_OR_PLATFORM_FINAL, fetchedAt = witness.fetchedAt.plusSeconds(1)))
                }
                assertEquals(false, repo.confirmUnconsumedPurchasePlatformNav(correction))
                assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
                assertEquals(correction.expectedLot, repo.loadLots(FUND_ID).single())
                correction.audits.forEach { assertNull(repo.findCorrection(it.id)) }
                assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
            } finally { database.close() }
        }
    }

    @Test
    fun `unfinished and historical sales or consumed source lot block official NAV repair`() = runTest {
        listOf("pending", "planned", "abnormal", "history", "consumed").forEach { state ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val correction = seedNavCorrection(repo)
                val before = correction.expectedLot
                val sale = transaction("nav-sale", type = TransactionType.REDEMPTION).copy(
                    tradeDate = DATE.plusDays(3), navDate = DATE.plusDays(3), shares = BigDecimal("10.00"),
                    status = when (state) {
                        "planned" -> TransactionStatus.PLANNED
                        "abnormal" -> TransactionStatus.ABNORMAL
                        "history" -> TransactionStatus.CALCULATED
                        else -> TransactionStatus.PENDING_CALC
                    })
                repo.createTransaction(sale)
                val remaining = if (state == "consumed") before.copy(remainingShares = BigDecimal("90.00"), costBasis = BigDecimal("900.00")) else before
                if (state == "consumed") repo.confirmRedemption(sale.copy(status = TransactionStatus.CALCULATED), listOf(remaining),
                    LotsRedeemed(LedgerEntryId("nav-sale-event"), 2, FUND_ID,
                        listOf(LotConsumption(before.id, BigDecimal("10.00"), BigDecimal("100.00")))))
                assertEquals(false, repo.confirmUnconsumedPurchasePlatformNav(correction))
                assertEquals(remaining, repo.findLotBySourceTransaction(correction.expected.id))
                assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
                correction.audits.forEach { assertNull(repo.findCorrection(it.id)) }
            } finally { database.close() }
        }
    }

    @Test
    fun `tampered official bundle or generic materialized purchase facts cannot bypass reconciliation`() = withRepository { repo ->
        val correction = seedNavCorrection(repo)
        expectFailure { repo.confirmUnconsumedPurchasePlatformNav(correction.copy(audits = correction.audits.dropLast(1))) }
        expectFailure { repo.confirmUnconsumedPurchasePlatformNav(correction.copy(
            confirmed = correction.confirmed.copy(navDate = DATE.plusDays(1)))) }
        val changes = listOf(
            TransactionCorrectionField.UNIT_NAV to CorrectionValue.Decimal(BigDecimal("9.8000")),
            TransactionCorrectionField.SHARES to CorrectionValue.Decimal(BigDecimal("99.99")),
            TransactionCorrectionField.PURCHASE_FEE to CorrectionValue.Decimal(BigDecimal("9.00")),
            TransactionCorrectionField.CONFIRMATION_DATE to CorrectionValue.Date(requireNotNull(correction.expected.confirmationDate).plusDays(1)))
        changes.forEach { (field, value) ->
            val generic = TransactionCorrectionService.applyManualCorrection(correction.expected,
                CorrectionId("unsafe-nav-${field.name}"), field, value, correction.confirmed.updatedAt)
            expectFailure { repo.recordCorrection(generic) }
            assertNull(repo.findCorrection(generic.audit.id))
        }
        val smuggled = TransactionCorrectionService.applyManualCorrection(correction.expected, CorrectionId("smuggled-nav"),
            TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(BigDecimal("-1000.00")), correction.confirmed.updatedAt)
        expectFailure { repo.recordCorrection(smuggled.copy(transaction = smuggled.transaction.copy(unitNav = BigDecimal("9.8000")))) }
        expectFailure { repo.recordCorrection(smuggled.copy(transaction = smuggled.transaction.copy(navDate = DATE.plusDays(1)))) }
        assertNull(repo.findCorrection(smuggled.audit.id))
        assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
        assertEquals(correction.expectedLot, repo.loadLots(FUND_ID).single())
        assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
    }

    @Test
    fun `late NAV audit and event identity collisions roll back all new facts`() = runTest {
        listOf("audit", "event").forEach { collision ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val correction = seedNavCorrection(repo)
                val other = transaction("nav-conflict-owner")
                repo.createTransaction(other)
                if (collision == "audit") repo.recordCorrection(TransactionCorrectionService.applyManualCorrection(other,
                    correction.audits.last().id, TransactionCorrectionField.UNIT_NAV, CorrectionValue.Decimal(BigDecimal("1.0000")), LATER))
                else repo.materializeCalculatedPurchase(calculatedPurchase(other), lot("nav-conflict-owner-lot", other.id), correction.entry.id)
                val entries = repo.loadLedgerEntries(FUND_ID)
                expectFailure { repo.confirmUnconsumedPurchasePlatformNav(correction) }
                assertEquals(correction.expected, repo.findTransaction(correction.expected.id))
                assertEquals(correction.expectedLot, repo.findLotBySourceTransaction(correction.expected.id))
                correction.audits.dropLast(1).forEach { assertNull(repo.findCorrection(it.id)) }
                if (collision == "event") assertNull(repo.findCorrection(correction.audits.last().id))
                assertEquals(entries, repo.loadLedgerEntries(FUND_ID))
            } finally { database.close() }
        }
    }

    @Test
    fun `NAV witness remains immutable across later official refresh and database reopening`() = runTest {
        val path = Files.createTempDirectory("ndx-platform-nav-correction").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val correction: PurchasePlatformNavConfirmation
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            correction = seedNavCorrection(repo)
            assertEquals(true, repo.confirmUnconsumedPurchasePlatformNav(correction))
            repo.saveNav(correction.entry.officialNav.copy(unitNav = BigDecimal("9.7000"), fetchedAt = correction.confirmed.updatedAt.plusSeconds(1)))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(correction.confirmed, repo.findTransaction(correction.expected.id))
            assertEquals(correction.entry.correctedLot, repo.loadLots(FUND_ID).single())
            assertEquals(correction.entry.copy(sequence = 2), repo.loadLedgerEntries(FUND_ID).last())
            assertEquals(BigDecimal("9.7000"), repo.findNav(FUND_ID, requireNotNull(correction.expected.navDate))?.unitNav)
            assertEquals(repo.loadLots(FUND_ID), LedgerRebuilder.rebuild(repo.loadLedgerEntries(FUND_ID)).realLots)
            correction.audits.forEach { assertEquals(it, repo.findCorrection(it.id)) }
        } finally { reopened.close() }
    }

    @Test
    fun `atomic history snapshot verifies explicit sale cost without altering any stored facts`() = withRepository { repo ->
        val before = seedLinkedHistory(repo)
        val result = assertIs<LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
        val linked = before.entries.last() as LotsRedeemed
        assertEquals(TransactionId("linked-history-sale"), linked.sourceTransactionId)
        assertEquals(BigDecimal("400.00"), result.redemptionCosts.single().allocatedCost)
        assertEquals(before.realLots, result.rebuiltLedger.realLots)
        assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
        assertNull(before.transactions.last().actualCashFlow)
        val empty = repo.loadHistorySnapshot(FundId("other-empty-fund"))
        assertEquals(emptyList(), empty.entries)
        assertIs<LedgerHistoryInspection.Consistent>(repo.inspectHistory(empty.fundId))
    }

    @Test
    fun `wrong sale link share total and fund are rejected before transaction or projection writes`() = withRepository { repo ->
        seedFundAndRule(repo)
        val pending = transaction("history-guard-buy")
        repo.createTransaction(pending)
        val source = lot("history-guard-lot", pending.id)
        repo.materializeCalculatedPurchase(calculatedPurchase(pending), source, LedgerEntryId("history-guard-open"))
        val sale = transaction("history-guard-sale", type = TransactionType.REDEMPTION).copy(
            shares = BigDecimal("40.00"), tradeDate = DATE.plusDays(4), navDate = DATE.plusDays(4), confirmationDate = DATE.plusDays(5))
        repo.createTransaction(sale)
        val confirmed = sale.copy(status = TransactionStatus.CALCULATED, updatedAt = LATER.plusSeconds(7 * 86400))
        val after = source.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
        val event = LotsRedeemed(LedgerEntryId("history-guard-sale-event"), 2, FUND_ID,
            listOf(LotConsumption(source.id, BigDecimal("40.00"), BigDecimal("400.00"))), sale.id)
        val snapshot = repo.loadHistorySnapshot(FUND_ID)
        expectFailure { repo.confirmRedemption(confirmed, listOf(after), event.copy(sourceTransactionId = TransactionId("unrelated"))) }
        expectFailure { repo.confirmRedemption(confirmed.copy(shares = BigDecimal("39.99")), listOf(after), event) }
        expectFailure { repo.confirmRedemption(confirmed, listOf(after), event.copy(fundId = FundId("other"))) }
        expectFailure { repo.materializeCalculatedRedemption(confirmed.copy(shares = BigDecimal("39.99")), listOf(source), listOf(after), event.id, event.consumptions) }
        assertEquals(snapshot, repo.loadHistorySnapshot(FUND_ID))
    }

    @Test
    fun `old unlinked stored sale remains readable and requires review without guessing or backfill`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val dao = database.ledgerDao()
            val repo = RoomLedgerRepository(dao)
            val before = seedLinkedHistory(repo, oldDao = dao)
            val stored = dao.loadHistorySnapshot(FUND_ID.value)
            val issues = assertIs<LedgerHistoryInspection.NeedsReview>(repo.inspectHistory(FUND_ID)).issues
            assertTrue(issues.any { it.reason == HistoryIssueReason.UNLINKED_REDEMPTION })
            assertTrue(issues.any { it.reason == HistoryIssueReason.MISSING_REDEMPTION_EVENT })
            assertNull((before.entries.last() as LotsRedeemed).sourceTransactionId)
            assertEquals(stored, dao.loadHistorySnapshot(FUND_ID.value))
            assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
        } finally { database.close() }
    }

    @Test
    fun `explicit sale transaction link and historical cost report survive persistent database reopening`() = runTest {
        val path = Files.createTempDirectory("ndx-linked-history").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val snapshot: LedgerHistorySnapshot
        val report: LedgerHistoryInspection
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            snapshot = seedLinkedHistory(repo)
            report = repo.inspectHistory(FUND_ID)
            assertIs<LedgerHistoryInspection.Consistent>(report)
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(snapshot, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(report, repo.inspectHistory(FUND_ID))
        } finally { reopened.close() }
    }

    @Test
    fun `corrupt projection or unreadable immutable payload produces read only review report`() = runTest {
        listOf("projection", "payload").forEach { corruption ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val dao = database.ledgerDao()
                val repo = RoomLedgerRepository(dao)
                if (corruption == "projection") {
                    seedLinkedHistory(repo)
                    val row = dao.loadLots(FUND_ID.value).single()
                    dao.updateLot(row.copy(costBasisDecimal = "599.00"))
                } else {
                    seedFundAndRule(repo)
                    dao.insertLedgerEntry(LedgerEntryEntity("unreadable", 1, FUND_ID.value, "LOTS_REDEEMED", "{bad-json"))
                }
                val before = dao.loadHistorySnapshot(FUND_ID.value)
                val issues = assertIs<LedgerHistoryInspection.NeedsReview>(repo.inspectHistory(FUND_ID)).issues
                assertTrue(issues.any { it.reason == if (corruption == "projection") HistoryIssueReason.PROJECTION_MISMATCH else HistoryIssueReason.INVALID_EVENT_HISTORY })
                assertEquals(before, dao.loadHistorySnapshot(FUND_ID.value))
            } finally { database.close() }
        }
    }

    @Test
    fun `realized return reads final cash and immutable consumed cost without writes or latest NAV`() = withRepository { repo ->
        val pending = seedLinkedHistory(repo)
        val unknown = assertIs<LedgerRealizedReturnAssembly.Incomplete>(LedgerRealizedReturnAssembler.assemble(
            pending, DATE.plusDays(10), requireNotNull(fund().precision)))
        assertTrue(unknown.gaps.any { it.reason == RealizedReturnGapReason.UNFINISHED_TRANSACTION })
        val snapshot = confirmLinkedHistoryCash(repo, pending)
        val result = assertIs<LedgerRealizedReturnAssembly.Complete>(LedgerRealizedReturnAssembler.assemble(
            snapshot, DATE.plusDays(10), requireNotNull(fund().precision)))
        assertEquals(BigDecimal("75.00"), result.realizedReturn)
        assertEquals(BigDecimal("400.00"), result.contributions.first { it.type == TransactionType.REDEMPTION }.allocatedCost)
        assertNull(repo.findLatestNav(FUND_ID))
        assertEquals(pending.entries, snapshot.entries)
        assertEquals(pending.realLots, snapshot.realLots)
        repeat(2) {
            assertEquals(result, LedgerRealizedReturnAssembler.assemble(repo.loadHistorySnapshot(FUND_ID), DATE.plusDays(10), requireNotNull(fund().precision)))
        }
        assertEquals(snapshot, repo.loadHistorySnapshot(FUND_ID))
        assertNotNull(repo.findCorrection(CorrectionId("cash:linked-history-buy")))
        assertNotNull(repo.findCorrection(CorrectionId("linked-sale-cash")))
    }

    @Test
    fun `old unlinked sale still blocks realized return even after both cash amounts are confirmed`() = runTest {
        val database = LedgerDatabaseFactory.inMemory()
        try {
            val dao = database.ledgerDao()
            val repo = RoomLedgerRepository(dao)
            val initial = seedLinkedHistory(repo, oldDao = dao)
            val final = confirmLinkedHistoryCash(repo, initial)
            val result = assertIs<LedgerRealizedReturnAssembly.Incomplete>(LedgerRealizedReturnAssembler.assemble(
                final, DATE.plusDays(10), requireNotNull(fund().precision)))
            assertEquals(listOf(RealizedReturnGapReason.HISTORY_REVIEW_REQUIRED), result.gaps.map { it.reason })
            assertTrue(result.historyIssues.any { it.reason == HistoryIssueReason.UNLINKED_REDEMPTION })
            assertNull((repo.loadLedgerEntries(FUND_ID).last() as LotsRedeemed).sourceTransactionId)
            assertEquals(final, repo.loadHistorySnapshot(FUND_ID))
        } finally { database.close() }
    }

    @Test
    fun `realized cash income and its exact original allocations survive database reopening`() = runTest {
        val path = Files.createTempDirectory("ndx-realized-income").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val snapshot: LedgerHistorySnapshot
        val report: LedgerRealizedReturnAssembly
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            snapshot = confirmLinkedHistoryCash(repo, seedLinkedHistory(repo))
            report = LedgerRealizedReturnAssembler.assemble(snapshot, DATE.plusDays(10), requireNotNull(fund().precision))
            assertEquals(BigDecimal("75.00"), assertIs<LedgerRealizedReturnAssembly.Complete>(report).realizedReturn)
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(snapshot, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(report, LedgerRealizedReturnAssembler.assemble(repo.loadHistorySnapshot(FUND_ID), DATE.plusDays(10), requireNotNull(fund().precision)))
            assertNotNull(repo.findCorrection(CorrectionId("payment:linked-history-buy")))
            assertNotNull(repo.findCorrection(CorrectionId("linked-sale-cash-date")))
        } finally { reopened.close() }
    }

    @Test
    fun `annualized read needs final Room cash and current formal valuation without changing ledger facts`() = withRepository { repo ->
        val initial = seedLinkedHistory(repo)
        val nav = NavRecord(FUND_ID, DATE.plusDays(365), BigDecimal("11.0000"), null, DataAuthority.FUND_OFFICIAL,
            DataSourceId("annual-official"), LATER.plusSeconds(366 * 86400))
        repo.saveNav(nav)
        val precision = requireNotNull(fund().precision)
        assertEquals(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW,
            assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(LedgerAnnualizedReturnAssembler.assemble(initial, nav, nav.navDate, precision)).reason)
        val final = confirmLinkedHistoryCash(repo, initial)
        val result = assertIs<LedgerAnnualizedReturnAssembly.Complete>(LedgerAnnualizedReturnAssembler.assemble(
            final, repo.findLatestNav(FUND_ID), nav.navDate, precision))
        assertIs<XirrCalculation>(result.xirr)
        assertEquals(BigDecimal("660.00"), result.currentPositionValue)
        assertEquals(listOf(BigDecimal("-1000.00"), BigDecimal("450.00"), BigDecimal("25.00"), BigDecimal("660.00")),
            result.cashFlows.map { it.amount })
        assertEquals(DATE.minusDays(1), result.cashFlows.first().date)
        assertEquals(DATE.plusDays(6), result.cashFlows[1].date)
        assertEquals(nav.navDate, result.cashFlows.last().date)
        assertEquals(initial.entries, final.entries)
        assertEquals(final, repo.loadHistorySnapshot(FUND_ID))
        assertEquals(AnnualizedReturnGapReason.MISSING_PUBLISHED_NAV, assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(
            LedgerAnnualizedReturnAssembler.assemble(final, null, nav.navDate, precision)).reason)
    }

    @Test
    fun `annualized cash and published valuation survive reopening and never use drifted projections`() = runTest {
        val path = Files.createTempDirectory("ndx-annual-income").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val snapshot: LedgerHistorySnapshot
        val result: LedgerAnnualizedReturnAssembly
        val nav = NavRecord(FUND_ID, DATE.plusDays(365), BigDecimal("11.0000"), null, DataAuthority.FUND_OFFICIAL,
            DataSourceId("annual-reopen"), LATER.plusSeconds(366 * 86400))
        val precision = requireNotNull(fund().precision)
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            snapshot = confirmLinkedHistoryCash(repo, seedLinkedHistory(repo))
            repo.saveNav(nav)
            result = LedgerAnnualizedReturnAssembler.assemble(snapshot, repo.findLatestNav(FUND_ID), nav.navDate, precision)
            assertIs<LedgerAnnualizedReturnAssembly.Complete>(result)
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val dao = reopened.ledgerDao()
            val repo = RoomLedgerRepository(dao)
            assertEquals(snapshot, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(result, LedgerAnnualizedReturnAssembler.assemble(repo.loadHistorySnapshot(FUND_ID), repo.findLatestNav(FUND_ID), nav.navDate, precision))
            val row = dao.loadLots(FUND_ID.value).single()
            dao.updateLot(row.copy(remainingSharesDecimal = "59.00"))
            val corrupted = repo.loadHistorySnapshot(FUND_ID)
            assertEquals(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(
                LedgerAnnualizedReturnAssembler.assemble(corrupted, nav, nav.navDate, precision)).reason)
            assertEquals(corrupted, repo.loadHistorySnapshot(FUND_ID))
        } finally { reopened.close() }
    }

    @Test
    fun `explicit baseline saves one immutable current valuation without adding cash transactions or changing Lots`() = withRepository { repo ->
        val baseline = seedTrackingBaseline(repo)
        val before = repo.loadHistorySnapshot(FUND_ID)
        assertNull(repo.findTrackingBaseline(FUND_ID))
        assertTrue(repo.establishTrackingBaseline(baseline))
        assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
        assertTrue(repo.establishTrackingBaseline(baseline))
        assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
        assertEquals(BigDecimal("1000.00"), requireNotNull(repo.findTrackingBaseline(FUND_ID)).startingValue)
        assertEquals(BigDecimal("700.00"), repo.loadLegacyLots(FUND_ID).single().costBasis)
        assertNull(repo.loadTransactions(FUND_ID).single().actualCashFlow)
        val changed = TrackingBaseline.prepare(baseline.history, baseline.officialNav.copy(unitNav = BigDecimal("11.0000")),
            baseline.precision, baseline.recordedAt, DATE)
        assertEquals(false, repo.establishTrackingBaseline(changed))
        assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
        repo.createTransaction(transaction("after-baseline-request").copy(confirmationDate = null))
        assertTrue(repo.establishTrackingBaseline(baseline))
        assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
    }

    @Test
    fun `stale snapshot official NAV or saved precision rejects baseline without partial or historical writes`() = runTest {
        listOf("snapshot", "nav", "precision", "missing-nav").forEach { mismatch ->
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val dao = database.ledgerDao()
                val repo = RoomLedgerRepository(dao)
                val baseline = seedTrackingBaseline(repo, persistNav = mismatch != "missing-nav")
                when (mismatch) {
                    "snapshot" -> repo.createTransaction(transaction("later-request").copy(confirmationDate = null))
                    "nav" -> repo.saveNav(baseline.officialNav.copy(unitNav = BigDecimal("11.0000"), fetchedAt = LATER.plusSeconds(1)))
                    "precision" -> repo.saveFund(fund().copy(precision = baseline.precision.copy(shareScale = 3)))
                }
                val before = dao.loadHistorySnapshot(FUND_ID.value)
                if (mismatch == "precision") expectFailure { repo.establishTrackingBaseline(baseline) }
                else assertEquals(false, repo.establishTrackingBaseline(baseline))
                assertNull(dao.findTrackingBaseline(FUND_ID.value))
                assertEquals(before, dao.loadHistorySnapshot(FUND_ID.value))
            } finally { database.close() }
        }
    }

    @Test
    fun `altered future or backdated baseline metadata cannot bypass repository validation`() = withRepository { repo ->
        val baseline = seedTrackingBaseline(repo)
        expectFailure { repo.establishTrackingBaseline(baseline.copy(startingValue = BigDecimal("700.00"))) }
        expectFailure { repo.establishTrackingBaseline(baseline.copy(startingShares = BigDecimal("99.00"))) }
        expectFailure { repo.establishTrackingBaseline(baseline.copy(recordedAt = java.time.Instant.parse("2999-01-01T00:00:00Z"))) }
        expectFailure { repo.establishTrackingBaseline(baseline.copy(recordedAt = NOW.minusSeconds(86400))) }
        assertNull(repo.findTrackingBaseline(FUND_ID))
    }

    @Test
    fun `baseline evidence survives database reopen and later official refresh without silently changing the start`() = runTest {
        val path = Files.createTempDirectory("ndx-explicit-baseline").resolve("ledger.db").toString()
        val first = LedgerDatabaseFactory.open(path)
        val baseline: TrackingBaseline
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            baseline = seedTrackingBaseline(repo)
            assertTrue(repo.establishTrackingBaseline(baseline))
            repo.saveNav(baseline.officialNav.copy(unitNav = BigDecimal("11.0000"), fetchedAt = LATER.plusSeconds(1)))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path)
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            assertEquals(baseline, repo.findTrackingBaseline(FUND_ID))
            assertEquals(baseline.history, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(BigDecimal("11.0000"), repo.findNav(FUND_ID, DATE)?.unitNav)
            assertEquals(BigDecimal("10.0000"), repo.findTrackingBaseline(FUND_ID)?.officialNav?.unitNav)
            assertTrue(repo.establishTrackingBaseline(baseline))
        } finally { reopened.close() }
    }

    @Test
    fun `saved tracking valuation reads official terminal NAV after reopening without rewriting historical cash or cost`() = runTest {
        val path = java.nio.file.Files.createTempDirectory("ndx-tracking-return").resolve("ledger.db")
        val first = LedgerDatabaseFactory.open(path.toString())
        val saved: TrackingBaseline
        val end = DATE.plusYears(1)
        try {
            val repo = RoomLedgerRepository(first.ledgerDao())
            saved = seedTrackingBaseline(repo)
            assertTrue(repo.establishTrackingBaseline(saved))
            repo.saveNav(saved.officialNav.copy(navDate = end, unitNav = BigDecimal("11.0000"),
                fetchedAt = NOW.plusSeconds(365L * 86400L)))
        } finally { first.close() }
        val reopened = LedgerDatabaseFactory.open(path.toString())
        try {
            val repo = RoomLedgerRepository(reopened.ledgerDao())
            val history = repo.loadHistorySnapshot(FUND_ID)
            val baseline = assertNotNull(repo.findTrackingBaseline(FUND_ID))
            val result = kotlin.test.assertIs<LedgerAnnualizedReturnAssembly.Complete>(
                com.ndxinvest.ledger.calculation.LedgerTrackingReturnAssembler.assemble(history, baseline,
                    repo.findLatestNav(FUND_ID), end, baseline.precision))
            assertEquals(HistoryScope.TRACKING_PERIOD, result.historyScope)
            assertEquals(0, kotlin.test.assertIs<XirrCalculation>(result.xirr).annualRate.compareTo(BigDecimal("0.1")))
            assertEquals(BigDecimal("-1000.00"), result.cashFlows.first().amount)
            assertEquals(saved, repo.findTrackingBaseline(FUND_ID))
            assertEquals(history, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(BigDecimal("700.00"), history.legacyLots.single().costBasis)
            assertEquals(null, history.transactions.single().actualCashFlow)
            val pending = transaction("tracking-pending").copy(applicationTime = LATER.plusSeconds(86400),
                createdAt = LATER.plusSeconds(86400), updatedAt = LATER.plusSeconds(86400),
                tradeDate = DATE.plusDays(1), navDate = DATE.plusDays(1))
            repo.createTransaction(pending)
            val changed = repo.loadHistorySnapshot(FUND_ID)
            val blocked = kotlin.test.assertIs<LedgerAnnualizedReturnAssembly.Incomplete>(
                com.ndxinvest.ledger.calculation.LedgerTrackingReturnAssembler.assemble(changed, baseline,
                    repo.findLatestNav(FUND_ID), end, baseline.precision))
            assertEquals(AnnualizedReturnGapReason.RECORDED_FACTS_REQUIRE_REVIEW, blocked.reason)
            assertEquals(changed, repo.loadHistorySnapshot(FUND_ID))
            assertEquals(saved, repo.findTrackingBaseline(FUND_ID))
        } finally { reopened.close() }
    }

    @Test
    fun `reviewed cash dividend saves dated final cash once and never changes current holding cost`() = withRepository { repo ->
        val proof = reviewedDividend(repo, false)
        val before = repo.loadHistorySnapshot(FUND_ID)
        assertTrue(repo.recordReviewedDividend(proof))
        assertEquals(proof.transaction, repo.findTransaction(proof.transaction.id))
        assertEquals(before.realLots, repo.loadLots(FUND_ID))
        assertEquals(before.legacyLots, repo.loadLegacyLots(FUND_ID))
        assertEquals(before.entries, repo.loadLedgerEntries(FUND_ID))
        assertEquals(false, repo.recordReviewedDividend(proof))
        assertEquals(2, repo.loadTransactions(FUND_ID).size)
        assertEquals(BigDecimal("50.00"), repo.findTransaction(proof.transaction.id)?.actualCashFlow)
    }

    @Test
    fun `reviewed reinvestment saves transaction new lot and sequenced immutable opening together`() = withRepository { repo ->
        val proof = reviewedDividend(repo, true)
        assertTrue(repo.recordReviewedDividend(proof))
        val tx = assertNotNull(repo.findTransaction(proof.transaction.id))
        assertNull(tx.actualCashFlow)
        assertEquals(proof.lot, repo.findLotBySourceTransaction(tx.id))
        val opening = repo.loadLedgerEntries(FUND_ID).filterIsInstance<RealLotOpened>().single()
        assertEquals(proof.entry?.id, opening.id); assertEquals(2L, opening.sequence)
        assertEquals(proof.lot, opening.lot)
        assertEquals(BigDecimal("700.00"), repo.loadLegacyLots(FUND_ID).single().costBasis)
        kotlin.test.assertIs<com.ndxinvest.ledger.calculation.LedgerHistoryInspection.Consistent>(repo.inspectHistory(FUND_ID))
        assertEquals(false, repo.recordReviewedDividend(proof))
        assertEquals(1, repo.loadLots(FUND_ID).size)
    }

    @Test
    fun `reviewed dividends reject stale fund rule NAV or complete history without partial writes`() = runTest {
        for (change in listOf("fund", "rule", "nav", "history")) {
            val database = LedgerDatabaseFactory.inMemory()
            try {
                val repo = RoomLedgerRepository(database.ledgerDao())
                val proof = reviewedDividend(repo, true)
                when (change) {
                    "fund" -> repo.saveFund(proof.fund.copy(providerMetadata = mapOf("changed" to "platform")))
                    "rule" -> repo.saveRuleVersion(rule().copy(id = RuleVersionId("new-rule"), effectiveFrom = DATE))
                    "nav" -> repo.saveNav(requireNotNull(proof.nav).copy(unitNav = BigDecimal("11.0000"), fetchedAt = LATER))
                    else -> repo.createTransaction(transaction("new-pending"))
                }
                val before = repo.loadHistorySnapshot(FUND_ID)
                assertEquals(false, repo.recordReviewedDividend(proof))
                assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
                assertNull(repo.findTransaction(proof.transaction.id))
                assertNull(repo.findLotBySourceTransaction(proof.transaction.id))
            } finally { database.close() }
        }
    }

    @Test
    fun `tampered reviewed dividend cash cost or future evidence cannot bypass repository validation`() = withRepository { repo ->
        val proof = reviewedDividend(repo, true)
        for (changed in listOf(proof.copy(transaction = proof.transaction.copy(actualCashFlow = BigDecimal("50.00"))),
            proof.copy(lot = proof.lot?.copy(costBasis = BigDecimal("49.00"))),
            proof.copy(nav = proof.nav?.copy(fetchedAt = java.time.Instant.now().plusSeconds(86400))))) {
            expectFailure { repo.recordReviewedDividend(changed) }
            assertNull(repo.findTransaction(proof.transaction.id))
            assertEquals(0, repo.loadLots(FUND_ID).size)
            assertEquals(1, repo.loadLedgerEntries(FUND_ID).size)
        }
    }

    @Test
    fun `reviewed reinvestment event collision rolls back its transaction and lot completely`() = withRepository { repo ->
        val proof = reviewedDividend(repo, true)
        val before = repo.loadHistorySnapshot(FUND_ID)
        val colliding = proof.copy(entry = proof.entry?.copy(id = LedgerEntryId("baseline-open")))
        expectFailure { repo.recordReviewedDividend(colliding) }
        assertEquals(before, repo.loadHistorySnapshot(FUND_ID))
        assertNull(repo.findTransaction(proof.transaction.id))
        assertNull(repo.findLotBySourceTransaction(proof.transaction.id))
    }

    private suspend fun reviewedDividend(repo: RoomLedgerRepository, reinvestment: Boolean): com.ndxinvest.ledger.calculation.ReviewedDividend {
        val baseline = seedTrackingBaseline(repo)
        val tx = transaction("reviewed-dividend", type = if (reinvestment) TransactionType.DIVIDEND_REINVESTMENT else TransactionType.CASH_DIVIDEND,
            status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL).copy(
            requestedAmount = null, grossAmount = BigDecimal("50.00"), netAmount = if (reinvestment) BigDecimal("50.00") else null,
            shares = if (reinvestment) BigDecimal("5.00") else null, unitNav = if (reinvestment) baseline.officialNav.unitNav else null,
            purchaseFee = if (reinvestment) BigDecimal("0.00") else null, navDate = if (reinvestment) DATE else null,
            confirmationDate = if (reinvestment) DATE else null, settlementDate = if (reinvestment) null else DATE,
            actualCashFlow = if (reinvestment) null else BigDecimal("50.00"), createdAt = LATER, updatedAt = LATER)
        val lot = if (reinvestment) Lot(LotId("reviewed-dividend-lot"), FUND_ID, tx.id, LotType.REAL, DATE,
            BigDecimal("5.00"), BigDecimal("5.00"), BigDecimal("0.00"), BigDecimal("50.00"), BigDecimal("0.00"),
            BigDecimal("50.00"), baseline.officialNav.unitNav) else null
        return com.ndxinvest.ledger.calculation.ReviewedDividend(assertNotNull(repo.findFund(FUND_ID)), repo.loadHistorySnapshot(FUND_ID),
            repo.loadRuleVersions(FUND_ID), if (reinvestment) baseline.officialNav else null, tx, lot,
            lot?.let { RealLotOpened(LedgerEntryId("reviewed-dividend-entry"), 1, it) })
    }

    private suspend fun seedTrackingBaseline(repo: RoomLedgerRepository, persistNav: Boolean = true): TrackingBaseline {
        seedFundAndRule(repo)
        val imported = transaction("baseline-import", type = TransactionType.ADJUSTMENT, status = TransactionStatus.CONFIRMED_ACTUAL,
            origin = TransactionOrigin.IMPORTED, authority = DataAuthority.USER_OR_PLATFORM_FINAL).copy(
            requestedAmount = null, navDate = null, confirmationDate = null)
        val legacy = LegacyLot(LotId("baseline-legacy"), FUND_ID, BigDecimal("100.00"), BigDecimal("700.00"), null)
        repo.recordLegacyLot(imported, legacy, LegacyLotOpened(LedgerEntryId("baseline-open"), 1, legacy))
        val nav = NavRecord(FUND_ID, DATE, BigDecimal("10.0000"), BigDecimal("13.1234"), DataAuthority.FUND_OFFICIAL,
            DataSourceId("baseline-company"), NOW)
        if (persistNav) repo.saveNav(nav)
        return TrackingBaseline.prepare(repo.loadHistorySnapshot(FUND_ID), nav, requireNotNull(fund().precision), LATER, DATE)
    }

    private suspend fun confirmLinkedHistoryCash(repo: RoomLedgerRepository, snapshot: LedgerHistorySnapshot): LedgerHistorySnapshot {
        val buy = snapshot.transactions.single { it.type == TransactionType.PURCHASE }
        val (confirmed, cashAudit, paymentAudit) = purchaseCashConfirmation(buy)
        assertTrue(repo.confirmMatchingCalculatedPurchaseActualCash(buy, snapshot.realLots.single(), confirmed, cashAudit, paymentAudit))
        val sale = snapshot.transactions.single { it.type == TransactionType.REDEMPTION }
        val cash = TransactionCorrectionService.applySalesPlatformFinalCorrection(sale, CorrectionId("linked-sale-cash"),
            TransactionCorrectionField.ACTUAL_CASH_FLOW, CorrectionValue.Decimal(BigDecimal("450.00")), LATER.plusSeconds(8 * 86400))
        val paid = TransactionCorrectionService.applySalesPlatformFinalCorrection(cash.transaction, CorrectionId("linked-sale-cash-date"),
            TransactionCorrectionField.CASH_FLOW_DATE, CorrectionValue.Date(DATE.plusDays(6)), LATER.plusSeconds(8 * 86400))
        assertTrue(repo.confirmCalculatedRedemptionActualCash(sale, paid.transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL,
            netAmount = BigDecimal("450.00")), cash.audit, paid.audit))
        repo.createTransaction(transaction("linked-dividend", type = TransactionType.CASH_DIVIDEND).copy(
            status = TransactionStatus.CONFIRMED_ACTUAL, authority = DataAuthority.USER_OR_PLATFORM_FINAL,
            actualCashFlow = BigDecimal("25.00"), cashFlowDate = DATE.plusDays(7)))
        return repo.loadHistorySnapshot(FUND_ID)
    }

    private suspend fun seedLinkedHistory(repo: RoomLedgerRepository, oldDao: LedgerDao? = null): LedgerHistorySnapshot {
        seedFundAndRule(repo)
        val pending = transaction("linked-history-buy")
        repo.createTransaction(pending)
        val source = lot("linked-history-lot", pending.id)
        repo.materializeCalculatedPurchase(calculatedPurchase(pending), source, LedgerEntryId("linked-history-open"))
        val sale = transaction("linked-history-sale", type = TransactionType.REDEMPTION).copy(
            shares = BigDecimal("40.00"), tradeDate = DATE.plusDays(4), navDate = DATE.plusDays(4), confirmationDate = DATE.plusDays(5))
        repo.createTransaction(sale)
        val calculated = sale.copy(status = TransactionStatus.CALCULATED, updatedAt = LATER.plusSeconds(7 * 86400))
        val after = source.copy(remainingShares = BigDecimal("60.00"), costBasis = BigDecimal("600.00"))
        val consumptions = listOf(LotConsumption(source.id, BigDecimal("40.00"), BigDecimal("400.00")))
        val id = LedgerEntryId("auto-entry:${sale.id.value}")
        if (oldDao == null) repo.materializeCalculatedRedemption(calculated, listOf(source), listOf(after), id, consumptions)
        else oldDao.recordRedemptionAtomically(calculated.toEntity(), listOf(source.toEntity()), listOf(after.toEntity()),
            LedgerEntryCodec.encode(LotsRedeemed(id, 2, FUND_ID, consumptions)))
        return repo.loadHistorySnapshot(FUND_ID)
    }

    private suspend fun seedNavCorrection(repo: RoomLedgerRepository, persistNav: Boolean = true): PurchasePlatformNavConfirmation {
        val share = seedShareCorrection(repo)
        val witness = NavRecord(FUND_ID, requireNotNull(share.expected.navDate), BigDecimal("9.8000"), BigDecimal("12.3400"),
            DataAuthority.FUND_OFFICIAL, DataSourceId("official-reconciliation"), share.confirmed.updatedAt.minusSeconds(3600))
        if (persistNav) repo.saveNav(witness)
        return PurchasePlatformNavConfirmation.prepare(share.expected, share.expectedLot, requireNotNull(fund().precision), witness,
            BigDecimal("1000.00"), BigDecimal("990.00"), BigDecimal("10.00"), witness.unitNav, BigDecimal("101.02"),
            DATE.minusDays(1), share.expectedLot.confirmationDate, share.confirmed.updatedAt, DATE.plusDays(7))
    }

    private suspend fun seedAmountCorrection(repo: RoomLedgerRepository): PurchasePlatformAmountConfirmation {
        val share = seedShareCorrection(repo)
        return PurchasePlatformAmountConfirmation.prepare(
            share.expected, share.expectedLot, requireNotNull(fund().precision), BigDecimal("900.00"),
            BigDecimal("891.00"), BigDecimal("9.00"), share.expectedLot.unitNav, BigDecimal("90.00"),
            DATE.minusDays(1), share.expectedLot.confirmationDate, share.confirmed.updatedAt, DATE.plusDays(7),
        )
    }

    private fun purchaseCashConfirmation(
        calculated: LedgerTransaction,
    ): Triple<LedgerTransaction, TransactionCorrection, TransactionCorrection> {
        val recordedAt = LATER.plusSeconds(4 * 86400)
        val cash = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            calculated, CorrectionId("cash:${calculated.id.value}"), TransactionCorrectionField.ACTUAL_CASH_FLOW,
            CorrectionValue.Decimal(BigDecimal("-1000.00")), recordedAt,
        )
        val payment = TransactionCorrectionService.applySalesPlatformFinalCorrection(
            cash.transaction, CorrectionId("payment:${calculated.id.value}"), TransactionCorrectionField.CASH_FLOW_DATE,
            CorrectionValue.Date(DATE.minusDays(1)), recordedAt,
        )
        return Triple(payment.transaction.copy(status = TransactionStatus.CONFIRMED_ACTUAL), cash.audit, payment.audit)
    }

    private suspend fun seedFundAndRule(repo: RoomLedgerRepository) {
        repo.saveFund(fund())
        repo.saveRuleVersion(rule())
    }

    private fun fund(): Fund = Fund(
        id = FUND_ID,
        code = "NDX001",
        name = "Nasdaq Fund",
        shortName = "NDX",
        fundType = "INDEX",
        trackingIndex = "NDX",
        providerMetadata = mapOf("providerCode" to "000001", "market" to "CN"),
        cardStyle = "indigo",
        precision = FundPrecision(2, 2, 4, RoundingMode.HALF_UP),
    )

    private fun rule(): FundRuleVersion = FundRuleVersion(
        id = RULE_ID,
        fundId = FUND_ID,
        effectiveFrom = LocalDate.parse("2026-01-01"),
        effectiveTo = null,
        cutoffTime = LocalTime.parse("15:00"),
        purchaseConfirmationRule = RuleExpression("T_PLUS_1"),
        redemptionConfirmationRule = RuleExpression("T_PLUS_2"),
        openDayRule = RuleExpression("FUND_OPEN_DAY"),
        purchaseFeeRule = PurchaseFeeRule.Rate(BigDecimal("0.0015")),
        redemptionFeeSchedule = RedemptionFeeSchedule(
            listOf(
                RedemptionFeeTier(HoldingPeriodBoundary(30, HoldingPeriodUnit.DAYS), BigDecimal("0.01")),
                RedemptionFeeTier(null, BigDecimal.ZERO),
            ),
        ),
        allocationPolicy = LotAllocationPolicy.FIFO,
        authority = DataAuthority.FUND_OFFICIAL,
        source = DataSourceId("fund-company"),
        fetchedAt = NOW,
        manualOverrideFields = emptySet(),
    )

    private fun recurringPlan(): RecurringPlan = RecurringPlan(
        id = RecurringPlanId("plan-a"),
        fundId = FUND_ID,
        amount = BigDecimal("1000.00"),
        frequency = "MONTHLY",
        scheduleRule = "DAY_11",
        nonOpenDayPolicy = "NEXT_OPEN_DAY",
        enabled = true,
        lastGeneratedDate = null,
    )

    private fun transaction(
        id: String,
        type: TransactionType = TransactionType.PURCHASE,
        status: TransactionStatus = TransactionStatus.PENDING_CALC,
        origin: TransactionOrigin = TransactionOrigin.USER_ENTERED,
        authority: DataAuthority = DataAuthority.APP_RULE_CALCULATION,
        unitNav: BigDecimal? = null,
        actualCashFlow: BigDecimal? = null,
    ): LedgerTransaction = LedgerTransaction(
        id = TransactionId(id),
        fundId = FUND_ID,
        type = type,
        status = status,
        origin = origin,
        authority = authority,
        applicationTime = NOW,
        tradeDate = DATE,
        navDate = DATE,
        confirmationDate = LocalDate.parse("2026-09-14"),
        settlementDate = null,
        ruleVersionId = RULE_ID,
        requestedAmount = BigDecimal("1000.00"),
        unitNav = unitNav,
        actualCashFlow = actualCashFlow,
    )

    private fun calculatedPurchase(pending: LedgerTransaction): LedgerTransaction = pending.copy(
        status = TransactionStatus.CALCULATED,
        authority = DataAuthority.APP_RULE_CALCULATION,
        grossAmount = BigDecimal("1000.00"),
        netAmount = BigDecimal("990.00"),
        shares = BigDecimal("100.00"),
        unitNav = BigDecimal("9.9000"),
        purchaseFee = BigDecimal("10.00"),
        updatedAt = LATER,
    )

    private fun lot(id: String, source: TransactionId): Lot = Lot(
        id = LotId(id),
        fundId = FUND_ID,
        sourceTransactionId = source,
        type = LotType.REAL,
        confirmationDate = LocalDate.parse("2026-09-14"),
        originalShares = BigDecimal("100.00"),
        remainingShares = BigDecimal("100.00"),
        grossCashOut = BigDecimal("1000.00"),
        netSubscriptionAmount = BigDecimal("990.00"),
        purchaseFee = BigDecimal("10.00"),
        costBasis = BigDecimal("1000.00"),
        unitNav = BigDecimal("9.9000"),
    )

    private suspend fun expectFailure(block: suspend () -> Unit) {
        assertNotNull(runCatching { block() }.exceptionOrNull(), "Expected persistence operation to fail")
    }

    private companion object {
        val FUND_ID: FundId = FundId("fund-a")
        val RULE_ID: RuleVersionId = RuleVersionId("rule-a")
        val DATE: LocalDate = LocalDate.parse("2026-09-11")
        val NOW: Instant = Instant.parse("2026-09-11T10:00:00Z")
        val LATER: Instant = Instant.parse("2026-09-11T12:00:00Z")
    }
}
