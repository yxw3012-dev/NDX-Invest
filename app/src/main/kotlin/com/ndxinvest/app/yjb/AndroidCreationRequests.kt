package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.serialization.json.JsonArray

internal class AndroidCreationRequests(context:Context,key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_create_requests_v1"),local:Boolean=false):CreationRequestStorage {
    private val file=AndroidEncryptedImports(context,key,if(local)"ndx-create-requests-v1-local.enc" else "ndx-create-requests-v1.enc",if(local)"ndx-create-requests-v1-local" else "ndx-create-requests-v1")
    override suspend fun read()=file.read()
    override suspend fun write(rows:JsonArray){if(!file.write(rows))throw BuyFailure(BuyProblem.STORAGE)}
}
