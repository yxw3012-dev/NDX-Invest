package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.yjb.read.LocalFundStorage
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import kotlinx.serialization.json.JsonArray

internal class AndroidLocalFunds(context:Context,local:Boolean=false):LocalFundStorage {
    private val encrypted=AndroidEncryptedImports(context,AndroidKeystoreCredentialKeyProvider("ndx_local_funds_v1"),if(local)"ndx-local-funds-v1-local.enc" else "ndx-local-funds-v1.enc",if(local)"ndx-local-funds-v1-local" else "ndx-local-funds-v1")
    override suspend fun read()=encrypted.read()
    override suspend fun write(rows:JsonArray)=encrypted.write(rows)
}
