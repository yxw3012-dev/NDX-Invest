package com.ndxinvest.network

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AesGcmCredentialVaultTest {
    @Test
    fun `store clears input and persists only authenticated ciphertext`() = runTest {
        val records = MemoryRecordStore()
        val vault = vault(records)
        val reference = CredentialReference("secure-store:provider:sample")
        val expected = listOf("user", "supplied", "value").joinToString("-")
        val input = expected.toCharArray()

        assertEquals(CredentialSaveResult.STORED, vault.storeAndClear(reference, input))
        assertTrue(input.all { it == '\u0000' })
        assertEquals(CredentialState.CONFIGURED, vault.state(reference))

        val record = assertIs<EncryptedCredentialRecord>(records.read(reference))
        val plaintextHex = expected.toByteArray().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        assertFalse(record.ciphertextHex.contains(plaintextHex, ignoreCase = true))
        val resolved = assertIs<ResolvedCredential>(vault.resolve(reference))
        resolved.use {
            assertEquals(expected, it.reveal())
            assertEquals("[REDACTED]", it.toString())
        }
    }

    @Test
    fun `overwrite uses a fresh IV and resolves only the newest value`() = runTest {
        val records = MemoryRecordStore()
        val vault = vault(records)
        val reference = CredentialReference("secure-store:provider:sample")

        assertEquals(CredentialSaveResult.STORED, vault.storeAndClear(reference, "first-value".toCharArray()))
        val first = assertIs<EncryptedCredentialRecord>(records.read(reference))
        assertEquals(CredentialSaveResult.STORED, vault.storeAndClear(reference, "second-value".toCharArray()))
        val second = assertIs<EncryptedCredentialRecord>(records.read(reference))

        assertFalse(first.initializationVectorHex == second.initializationVectorHex)
        assertFalse(first.ciphertextHex == second.ciphertextHex)
        vault.resolve(reference)?.use { assertEquals("second-value", it.reveal()) }
    }

    @Test
    fun `record cannot be substituted for another credential reference`() = runTest {
        val records = MemoryRecordStore()
        val vault = vault(records)
        val source = CredentialReference("secure-store:provider:sample")
        val destination = CredentialReference("secure-store:provider:another")

        assertEquals(CredentialSaveResult.STORED, vault.storeAndClear(source, "bound-value".toCharArray()))
        records.write(destination, assertIs<EncryptedCredentialRecord>(records.read(source)))

        assertNull(vault.resolve(destination))
        vault.resolve(source)?.use { assertEquals("bound-value", it.reveal()) }
    }

    @Test
    fun `tampering and unavailable storage fail closed without exposing details`() = runTest {
        val records = MemoryRecordStore()
        val vault = vault(records)
        val reference = CredentialReference("secure-store:provider:sample")
        vault.storeAndClear(reference, "original-value".toCharArray())
        val original = assertIs<EncryptedCredentialRecord>(records.read(reference))
        val changedLastDigit = if (original.ciphertextHex.last() == '0') '1' else '0'
        records.write(
            reference,
            original.copy(ciphertextHex = original.ciphertextHex.dropLast(1) + changedLastDigit),
        )

        assertNull(vault.resolve(reference))

        val unavailable = AesGcmCredentialVault(
            keyProvider = CredentialKeyProvider { error("sensitive key failure") },
            recordStore = ThrowingRecordStore,
        )
        val input = "another-value".toCharArray()
        assertEquals(CredentialState.UNAVAILABLE, unavailable.state(reference))
        assertEquals(CredentialSaveResult.UNAVAILABLE, unavailable.storeAndClear(reference, input))
        assertTrue(input.all { it == '\u0000' })
        assertNull(unavailable.resolve(reference))
        assertEquals(CredentialRemoveResult.UNAVAILABLE, unavailable.remove(reference))
    }

    @Test
    fun `invalid input is rejected and removal exposes no credential value`() = runTest {
        val records = MemoryRecordStore()
        val vault = vault(records)
        val reference = CredentialReference("secure-store:provider:sample")
        val invalid = "line\nbreak".toCharArray()

        assertEquals(CredentialSaveResult.REJECTED, vault.storeAndClear(reference, invalid))
        assertTrue(invalid.all { it == '\u0000' })
        assertEquals(CredentialState.NOT_CONFIGURED, vault.state(reference))
        assertEquals(CredentialRemoveResult.NOT_FOUND, vault.remove(reference))

        vault.storeAndClear(reference, "stored-value".toCharArray())
        assertEquals(CredentialRemoveResult.REMOVED, vault.remove(reference))
        assertEquals(CredentialState.NOT_CONFIGURED, vault.state(reference))
        assertNull(vault.resolve(reference))
    }

    @Test
    fun `cancellation propagates while credential input is still cleared`() = runTest {
        val reference = CredentialReference("secure-store:provider:sample")
        val input = "cancelled-value".toCharArray()
        val vault = AesGcmCredentialVault(
            keyProvider = CredentialKeyProvider { throw CancellationException("cancelled") },
            recordStore = MemoryRecordStore(),
        )

        assertFailsWith<CancellationException> { vault.storeAndClear(reference, input) }
        assertTrue(input.all { it == '\u0000' })
    }

    private fun vault(records: MemoryRecordStore): AesGcmCredentialVault {
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmCredentialVault(CredentialKeyProvider { key }, records)
    }

    private class MemoryRecordStore : EncryptedCredentialRecordStore {
        private val records: MutableMap<CredentialReference, EncryptedCredentialRecord> = mutableMapOf()

        override suspend fun read(reference: CredentialReference): EncryptedCredentialRecord? = records[reference]

        override suspend fun write(reference: CredentialReference, record: EncryptedCredentialRecord) {
            records[reference] = record
        }

        override suspend fun remove(reference: CredentialReference): Boolean = records.remove(reference) != null
    }

    private object ThrowingRecordStore : EncryptedCredentialRecordStore {
        override suspend fun read(reference: CredentialReference): EncryptedCredentialRecord =
            error("sensitive read failure")

        override suspend fun write(reference: CredentialReference, record: EncryptedCredentialRecord) {
            error("sensitive write failure")
        }

        override suspend fun remove(reference: CredentialReference): Boolean = error("sensitive remove failure")
    }
}
