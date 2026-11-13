package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.CancellationException

/** Separate, atomic, authenticated encrypted local history; never cleared with session/cache. */
internal class AndroidBuyStorage(context:Context,key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_buy_journal_v1"),local:Boolean=false):BuyStorage {
    private val file=AndroidEncryptedImports(context,key,if(local)"ndx-buy-transactions-v1-local.enc" else "ndx-buy-transactions-v1.enc",if(local)"ndx-buy-transactions-v1-local" else "ndx-buy-transactions-v1")
    override suspend fun read():List<BuyTransaction> = try{BuyCodec.decode(file.read())}catch(e:CancellationException){throw e}catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
    override suspend fun write(rows:List<BuyTransaction>) {if(!file.write(BuyCodec.encode(rows)))throw BuyFailure(BuyProblem.STORAGE)}
}
