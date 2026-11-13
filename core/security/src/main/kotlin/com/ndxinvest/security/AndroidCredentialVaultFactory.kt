package com.ndxinvest.security

import android.content.Context
import com.ndxinvest.network.AesGcmCredentialVault
import com.ndxinvest.network.CredentialVault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public object AndroidCredentialVaultFactory {
    public fun create(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): CredentialVault = AesGcmCredentialVault(
        keyProvider = AndroidKeystoreCredentialKeyProvider(ioDispatcher = ioDispatcher),
        recordStore = NoBackupEncryptedCredentialRecordStore(context, ioDispatcher),
    )
}
