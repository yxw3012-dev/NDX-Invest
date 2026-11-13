package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.dca.*
import kotlinx.coroutines.CancellationException

/** Plans and period reservations in one atomic encrypted file; no Room or BUY migration. */
internal class AndroidDcaStorage(context:Context,key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_dca_plans_v1"),local:Boolean=false):DcaStorage {
    private val file=AndroidEncryptedImports(context,key,if(local)"ndx-dca-plans-v1-local.enc" else "ndx-dca-plans-v1.enc",if(local)"ndx-dca-plans-v1-local" else "ndx-dca-plans-v1")
    override suspend fun read():DcaState=try {DcaCodec.decode(file.read())}catch(e:CancellationException){throw e}catch(_:Exception){throw DcaFailure(DcaProblem.STORAGE)}
    override suspend fun write(state:DcaState){if(!file.write(DcaCodec.encode(state)))throw DcaFailure(DcaProblem.STORAGE)}
}
