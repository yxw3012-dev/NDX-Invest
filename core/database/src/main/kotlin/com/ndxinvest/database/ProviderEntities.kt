package com.ndxinvest.database

import androidx.room3.Entity
import androidx.room3.Index

@Entity(
    tableName = "provider_configurations",
    indices = [Index(value = ["dataKind", "priority"])],
    primaryKeys = ["id"],
)
public data class ProviderConfigurationEntity public constructor(
    public val id: String,
    public val dataKind: String,
    public val displayName: String,
    public val endpoint: String,
    public val queryJson: String,
    public val requestHeadersJson: String,
    public val requestMethod: String,
    public val jsonBodyJson: String,
    public val credentialReference: String?,
    public val credentialLocation: String?,
    public val credentialParameterName: String?,
    public val fieldMappingsJson: String,
    public val optionsJson: String,
    public val enabled: Boolean,
    public val priority: Int,
    public val authority: String?,
    public val updatedAt: String,
)

@Entity(
    tableName = "provider_cache",
    indices = [Index(value = ["providerId"])],
    primaryKeys = ["cacheKey"],
)
public data class ProviderCacheEntity public constructor(
    public val cacheKey: String,
    public val schemaVersion: Int,
    public val providerId: String,
    public val payloadJson: String,
    public val fetchedAt: String,
)

@Entity(
    tableName = "provider_daily_success",
    indices = [Index(value = ["providerId"])],
    primaryKeys = ["budgetKey", "successDate"],
)
public data class ProviderDailySuccessEntity public constructor(
    public val budgetKey: String,
    public val successDate: String,
    public val providerId: String,
    public val fetchedAt: String,
)
