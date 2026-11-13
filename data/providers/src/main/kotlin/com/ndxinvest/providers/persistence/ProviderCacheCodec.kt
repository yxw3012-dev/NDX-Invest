package com.ndxinvest.providers.persistence

import com.ndxinvest.providers.contract.ProviderId

public interface ProviderCacheCodec<T> {
    public val schemaVersion: Int

    public fun encode(value: T): String

    public fun decode(payload: String): T

    public fun sourceOf(value: T): ProviderId
}
