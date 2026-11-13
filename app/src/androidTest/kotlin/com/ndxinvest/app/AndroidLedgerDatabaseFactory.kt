package com.ndxinvest.app

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ndxinvest.database.LedgerDatabase
import com.ndxinvest.database.LedgerDatabaseFactory
import com.ndxinvest.database.LedgerMigrations

internal object AndroidLedgerDatabaseFactory {
    fun open(context: Context, name: String): LedgerDatabase = Room.databaseBuilder(
        context = context.applicationContext,
        name = name,
        factory = LedgerDatabaseFactory::createImplementation,
    ).setDriver(BundledSQLiteDriver())
        .addMigrations(*LedgerMigrations.ALL.toTypedArray())
        .build()
}
