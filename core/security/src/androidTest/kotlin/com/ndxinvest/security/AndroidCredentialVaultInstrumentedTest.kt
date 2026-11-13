package com.ndxinvest.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ndxinvest.network.AesGcmCredentialVault
import com.ndxinvest.network.CredentialReference
import com.ndxinvest.network.CredentialRemoveResult
import com.ndxinvest.network.CredentialSaveResult
import com.ndxinvest.network.CredentialState
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCredentialVaultInstrumentedTest {
    @Test
    fun keystoreKeyAndNoBackupCiphertextRoundTrip(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val reference = CredentialReference("secure-store:provider:sample:instrumented")
        val alias = "com.ndxinvest.credentials.instrumented.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyProvider = AndroidKeystoreCredentialKeyProvider(alias)
        val recordStore = NoBackupEncryptedCredentialRecordStore(context)
        val vault = AesGcmCredentialVault(keyProvider, recordStore)
        val expected = "instrumented-user-token"
        val input = expected.toCharArray()

        recordStore.remove(reference)
        keyStore.deleteEntry(alias)
        try {
            assertEquals(CredentialSaveResult.STORED, vault.storeAndClear(reference, input))
            assertTrue(input.all { it == '\u0000' })
            assertEquals(CredentialState.CONFIGURED, vault.state(reference))

            val key = keyProvider.getOrCreate()
            assertNull(key.encoded)
            assertEquals("AES", key.algorithm)

            val recordDirectory = File(context.noBackupFilesDir, NoBackupEncryptedCredentialRecordStore.DIRECTORY_NAME)
            val storedFile = recordDirectory.listFiles()?.singleOrNull {
                it.name == credentialRecordFileName(reference)
            }
            assertNotNull(storedFile)
            val persisted = requireNotNull(storedFile).readBytes()
            assertFalse(persisted.toString(Charsets.US_ASCII).contains(expected))
            assertFalse(persisted.toString(Charsets.US_ASCII).contains(reference.value))

            vault.resolve(reference)?.use { resolved ->
                assertEquals("[REDACTED]", resolved.toString())
            } ?: throw AssertionError("Credential did not resolve")
            assertEquals(CredentialRemoveResult.REMOVED, vault.remove(reference))
            assertEquals(CredentialState.NOT_CONFIGURED, vault.state(reference))
        } finally {
            recordStore.remove(reference)
            keyStore.deleteEntry(alias)
            input.fill('\u0000')
        }
    }
}
