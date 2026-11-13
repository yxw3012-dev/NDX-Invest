package com.ndxinvest.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

public object LedgerDatabaseFactory {
    public fun createImplementation(): LedgerDatabase = LedgerDatabase_Impl()

    public fun inMemory(): LedgerDatabase = Room.inMemoryDatabaseBuilder {
        createImplementation()
    }.setDriver(BundledSQLiteDriver()).build()

    public fun open(path: String): LedgerDatabase = Room.databaseBuilder(
        name = path,
        factory = ::createImplementation,
    ).setDriver(BundledSQLiteDriver())
        .addMigrations(*LedgerMigrations.ALL.toTypedArray())
        .build()
}
