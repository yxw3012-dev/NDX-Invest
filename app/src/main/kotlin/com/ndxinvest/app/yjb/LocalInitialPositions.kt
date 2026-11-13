package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.read.YjbReadStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class LocalInitialPositions(context: Context) : YjbReadStorage {
    private val encrypted = AndroidEncryptedImports(context,
        AndroidKeystoreCredentialKeyProvider("ndx_local_initial_v1"),
        "ndx-local-initial-v1.enc", "ndx-local-initial-v1")

    override suspend fun imports() = encrypted.read()
    override suspend fun saveImports(value: JsonArray) = encrypted.write(value)
    override suspend fun token(): CharArray? = null
    override suspend fun saveToken(value: CharArray) = false
    override suspend fun snapshot(): JsonObject? = null
    override suspend fun saveSnapshot(value: JsonObject) = false
    override suspend fun clear() = Unit
}
