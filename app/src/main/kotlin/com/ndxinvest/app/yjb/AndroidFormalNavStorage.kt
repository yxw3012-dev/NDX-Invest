package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.BuyFailure
import com.ndxinvest.yjb.buy.BuyProblem
import com.ndxinvest.yjb.buy.FormalNavStorage
import kotlinx.serialization.json.JsonArray

internal class AndroidFormalNavStorage(context:Context,key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_formal_nav_v1"),local:Boolean=false):FormalNavStorage {
    private val encrypted=AndroidEncryptedImports(context,key,if(local)"ndx-formal-nav-v1-local.enc" else "ndx-formal-nav-v1.enc",
        if(local)"ndx-formal-nav-v1-local" else "ndx-formal-nav-v1")
    override suspend fun read():JsonArray=encrypted.read()
    override suspend fun write(rows:JsonArray){if(!encrypted.write(rows))throw BuyFailure(BuyProblem.STORAGE)}
}
