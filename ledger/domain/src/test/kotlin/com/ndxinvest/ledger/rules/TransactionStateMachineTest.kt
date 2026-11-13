package com.ndxinvest.ledger.rules

import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionStateMachineTest {
    @Test
    fun `application T NAV confirmation and settlement dates remain independent facts`() {
        val transaction = transaction(TransactionStatus.CALCULATED).copy(
            applicationTime = Instant.parse("2026-09-10T16:01:00Z"),
            tradeDate = LocalDate.parse("2026-09-11"),
            navDate = LocalDate.parse("2026-09-12"),
            confirmationDate = LocalDate.parse("2026-09-14"),
            settlementDate = LocalDate.parse("2026-09-16"),
        )

        assertEquals(LocalDate.parse("2026-09-11"), transaction.tradeDate)
        assertEquals(LocalDate.parse("2026-09-12"), transaction.navDate)
        assertEquals(LocalDate.parse("2026-09-14"), transaction.confirmationDate)
        assertEquals(LocalDate.parse("2026-09-16"), transaction.settlementDate)
    }

    @Test
    fun `automatic plan remains assumed and terminal actual history cannot reenter calculation`() {
        val planned = transaction(TransactionStatus.PLANNED)
        val pending = TransactionStateMachine.transition(planned, TransactionStatus.PENDING_CALC)
        val calculated = TransactionStateMachine.transition(pending, TransactionStatus.CALCULATED)
        val actual = calculated.copy(
            status = TransactionStatus.CONFIRMED_ACTUAL,
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
        )

        assertEquals(TransactionOrigin.AUTO_PLAN_ASSUMED, actual.origin)
        assertFailsWith<IllegalArgumentException> {
            TransactionStateMachine.transition(actual, TransactionStatus.PENDING_CALC)
        }
    }

    @Test
    fun `final actual transition requires final authority in the same operation`() {
        val calculated = transaction(TransactionStatus.CALCULATED)

        val actual = TransactionStateMachine.transition(
            transaction = calculated,
            to = TransactionStatus.CONFIRMED_ACTUAL,
            authority = DataAuthority.USER_OR_PLATFORM_FINAL,
        )

        assertEquals(TransactionStatus.CONFIRMED_ACTUAL, actual.status)
        assertEquals(DataAuthority.USER_OR_PLATFORM_FINAL, actual.authority)
    }

    private fun transaction(status: TransactionStatus): LedgerTransaction = LedgerTransaction(
        id = TransactionId("tx-1"),
        fundId = FundId("fund-a"),
        type = TransactionType.RECURRING_PURCHASE,
        status = status,
        origin = TransactionOrigin.AUTO_PLAN_ASSUMED,
        authority = DataAuthority.APP_RULE_CALCULATION,
        applicationTime = Instant.parse("2026-09-10T15:00:00Z"),
        tradeDate = LocalDate.parse("2026-09-11"),
        navDate = null,
        confirmationDate = null,
        settlementDate = null,
        ruleVersionId = RuleVersionId("v1"),
    )
}
