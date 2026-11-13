package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.CancellationException

/** Independent app-private AES-GCM SELL events; no Room/BUY journal migration or clear-on-logout. */
internal class AndroidSellStorage(context:Context,key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_sell_journal_v1"),local:Boolean=false):SellStorage {
    private val file=AndroidEncryptedImports(context,key,if(local)"ndx-sell-transactions-v1-local.enc" else "ndx-sell-transactions-v1.enc",if(local)"ndx-sell-transactions-v1-local" else "ndx-sell-transactions-v1")
    override suspend fun read():List<SellTransaction> = try{SellCodec.decode(file.read())}catch(e:CancellationException){throw e}catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
    override suspend fun write(rows:List<SellTransaction>){if(!file.write(SellCodec.encode(rows)))throw BuyFailure(BuyProblem.STORAGE)}
}
