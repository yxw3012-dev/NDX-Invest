package com.ndxinvest.ledger.persistence

import com.ndxinvest.ledger.model.DecimalStorageCodec
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerSqliteContractTest {
    @Test
    fun `Test H1 recurring execution business key rejects a duplicate transaction`() = withDatabase { connection ->
        connection.prepareStatement(
            "INSERT INTO recurring_transactions(transaction_id, plan_id, scheduled_trade_date) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, "tx-1")
            statement.setString(2, "plan-1")
            statement.setString(3, "2026-09-11")
            statement.executeUpdate()
        }

        assertFailsWith<SQLException> {
            connection.prepareStatement(
                "INSERT INTO recurring_transactions(transaction_id, plan_id, scheduled_trade_date) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "tx-2")
                statement.setString(2, "plan-1")
                statement.setString(3, "2026-09-11")
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `NAV identity is fund plus NAV date and decimal TEXT round trip is lossless`() = withDatabase { connection ->
        val original = BigDecimal("12345678901234567890.00123000")
        connection.prepareStatement(
            "INSERT INTO nav_records(fund_id, nav_date, unit_nav_decimal, authority) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, "fund-a")
            statement.setString(2, "2026-09-11")
            statement.setString(3, DecimalStorageCodec.encode(original))
            statement.setString(4, "FUND_OFFICIAL")
            statement.executeUpdate()
        }

        val restored = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT unit_nav_decimal FROM nav_records").use { result ->
                result.next()
                DecimalStorageCodec.decode(result.getString(1))
            }
        }
        assertEquals(original, restored)
        assertFailsWith<SQLException> {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO nav_records VALUES ('fund-a', '2026-09-11', '9.9999', 'RELIABLE_AGGREGATOR')",
                )
            }
        }
    }

    @Test
    fun `transaction and Lot changes roll back together`() = withDatabase { connection ->
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO ledger_transactions VALUES ('tx-1', 'fund-a', 'CALCULATED')")
            statement.executeUpdate("INSERT INTO lots VALUES ('lot-1', 'tx-1', '100.00', '100.00')")
        }
        connection.rollback()

        assertEquals(0, countRows(connection, "ledger_transactions"))
        assertEquals(0, countRows(connection, "lots"))
        connection.autoCommit = true
    }

    private fun withDatabase(block: (Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                LedgerSqliteContract.createStatements.forEach(statement::execute)
            }
            block(connection)
        }
    }

    private fun countRows(connection: Connection, table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
}
