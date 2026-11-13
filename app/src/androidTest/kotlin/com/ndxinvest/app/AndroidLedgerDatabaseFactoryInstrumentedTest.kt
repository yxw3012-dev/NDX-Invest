package com.ndxinvest.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLedgerDatabaseFactoryInstrumentedTest {
    @Test
    fun opensAndroidDatabaseAndExecutesDaoQuery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "instrumented-${UUID.randomUUID()}.db"
        val database = AndroidLedgerDatabaseFactory.open(context, databaseName)

        try {
            assertTrue(database.ledgerDao().loadFunds().isEmpty())
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            context.getDatabasePath("$databaseName.lck").delete()
        }
    }
}
