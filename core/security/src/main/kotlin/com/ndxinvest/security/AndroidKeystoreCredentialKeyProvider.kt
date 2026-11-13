package com.ndxinvest.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.ndxinvest.network.CredentialKeyProvider
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Supplies a non-exportable AES-256 key whose material remains inside Android Keystore. */
public class AndroidKeystoreCredentialKeyProvider public constructor(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialKeyProvider {
    init {
        require(keyAlias.isNotBlank() && keyAlias.length <= MAX_ALIAS_LENGTH) {
            "Android Keystore alias is invalid"
        }
        require(keyAlias.none { Character.isISOControl(it.code) }) {
            "Android Keystore alias is invalid"
        }
    }

    override suspend fun getOrCreate(): SecretKey = withContext(ioDispatcher) {
        keystoreMutex.withLock {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null)
            when (existing) {
                null -> generateKey()
                is SecretKey -> existing.also(::validateKey)
                else -> throw GeneralSecurityException("Credential key alias has an incompatible entry")
            }
        }
    }

    private fun generateKey(): SecretKey {
        val specification = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
            .apply { init(specification) }
            .generateKey()
            .also(::validateKey)
    }

    private fun validateKey(key: SecretKey) {
        if (key.algorithm != KeyProperties.KEY_ALGORITHM_AES || key.encoded != null) {
            throw GeneralSecurityException("Credential key is not a non-exportable AES key")
        }
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE_PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        val requiredPurposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val valid = keyInfo.keySize == KEY_SIZE_BITS &&
            keyInfo.purposes == requiredPurposes &&
            keyInfo.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM) &&
            keyInfo.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE) &&
            !keyInfo.isUserAuthenticationRequired
        if (!valid) throw GeneralSecurityException("Credential key parameters are incompatible")
    }

    public companion object {
        public const val DEFAULT_KEY_ALIAS: String = "com.ndxinvest.credentials.v1"

        private const val ANDROID_KEYSTORE_PROVIDER: String = "AndroidKeyStore"
        private const val KEY_SIZE_BITS: Int = 256
        private const val MAX_ALIAS_LENGTH: Int = 128
        private val keystoreMutex: Mutex = Mutex()
    }
}
