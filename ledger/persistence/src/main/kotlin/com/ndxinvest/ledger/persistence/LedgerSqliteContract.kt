package com.ndxinvest.ledger.persistence

/**
 * SQLite invariants that the Room schema must preserve.
 *
 * Financial decimals are TEXT facts, and business idempotency is enforced by
 * database keys rather than by a pre-insert query.
 */
public object LedgerSqliteContract {
    public val createStatements: List<String> = listOf(
        "PRAGMA foreign_keys = ON",
        """
            CREATE TABLE ledger_transactions (
                transaction_id TEXT NOT NULL PRIMARY KEY,
                fund_id TEXT NOT NULL,
                status TEXT NOT NULL
            )
        """.trimIndent(),
        """
            CREATE TABLE lots (
                lot_id TEXT NOT NULL PRIMARY KEY,
                transaction_id TEXT NOT NULL UNIQUE,
                remaining_shares_decimal TEXT NOT NULL,
                cost_basis_decimal TEXT NOT NULL,
                FOREIGN KEY(transaction_id) REFERENCES ledger_transactions(transaction_id)
            )
        """.trimIndent(),
        """
            CREATE TABLE recurring_transactions (
                transaction_id TEXT NOT NULL PRIMARY KEY,
                plan_id TEXT NOT NULL,
                scheduled_trade_date TEXT NOT NULL,
                UNIQUE(plan_id, scheduled_trade_date)
            )
        """.trimIndent(),
        """
            CREATE TABLE nav_records (
                fund_id TEXT NOT NULL,
                nav_date TEXT NOT NULL,
                unit_nav_decimal TEXT NOT NULL,
                authority TEXT NOT NULL,
                PRIMARY KEY(fund_id, nav_date)
            )
        """.trimIndent(),
    )
}
