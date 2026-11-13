package com.ndxinvest.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import java.time.Instant

@Dao
public abstract class ProviderDao {
    @Upsert
    public abstract suspend fun upsertConfiguration(entity: ProviderConfigurationEntity)

    @Query("SELECT * FROM provider_configurations WHERE id = :id")
    public abstract suspend fun findConfiguration(id: String): ProviderConfigurationEntity?

    @Query("SELECT * FROM provider_configurations WHERE dataKind = :dataKind ORDER BY priority, id")
    public abstract suspend fun listConfigurations(dataKind: String): List<ProviderConfigurationEntity>

    @Query("DELETE FROM provider_configurations WHERE id = :id")
    public abstract suspend fun deleteConfiguration(id: String): Int

    @Upsert
    protected abstract suspend fun upsertCache(entity: ProviderCacheEntity)

    @Query("SELECT * FROM provider_cache WHERE cacheKey = :cacheKey")
    public abstract suspend fun findCache(cacheKey: String): ProviderCacheEntity?

    @Query("DELETE FROM provider_cache WHERE cacheKey = :cacheKey")
    public abstract suspend fun deleteCache(cacheKey: String): Int

    @Transaction
    public open suspend fun upsertCacheIfNotOlder(entity: ProviderCacheEntity): Boolean {
        val existing = findCache(entity.cacheKey)
        val mayReplace = existing == null || Instant.parse(entity.fetchedAt).isAfter(Instant.parse(existing.fetchedAt))
        if (mayReplace) upsertCache(entity)
        return mayReplace
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public abstract suspend fun insertDailySuccess(entity: ProviderDailySuccessEntity): Long

    @Query(
        "SELECT * FROM provider_daily_success " +
            "WHERE budgetKey = :budgetKey AND successDate = :successDate",
    )
    public abstract suspend fun findDailySuccess(
        budgetKey: String,
        successDate: String,
    ): ProviderDailySuccessEntity?
}
