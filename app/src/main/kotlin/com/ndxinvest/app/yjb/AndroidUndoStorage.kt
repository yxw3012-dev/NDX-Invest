package com.ndxinvest.app.yjb
import android.content.Context
import com.ndxinvest.yjb.buy.*
import kotlinx.serialization.json.JsonArray
internal class AndroidUndoStorage(context:Context,local:Boolean=false):UndoStorage {
    private val file=AndroidEncryptedImports(context,com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider("ndx_transaction_undo_v1"),fileName=if(local)"ndx-transaction-undo-v1-local.enc" else "ndx-transaction-undo-v1.enc",aadName=if(local)"ndx-transaction-undo-v1-local" else "ndx-transaction-undo-v1")
    override suspend fun read()=file.read()
    override suspend fun write(rows:JsonArray){if(!file.write(rows))throw BuyFailure(BuyProblem.STORAGE)}
}
