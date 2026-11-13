package com.ndxinvest.security

import com.ndxinvest.network.CredentialReference
import com.ndxinvest.network.EncryptedCredentialRecord
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CredentialRecordCodecTest {
    @Test
    fun `record codec round trips exact versioned ciphertext`() {
        val record = record()

        val encoded = CredentialRecordCodec.encode(record)

        assertEquals(record, CredentialRecordCodec.decode(encoded))
        assertFalse(encoded.toString(Charsets.US_ASCII).contains("user-supplied-token"))
    }

    @Test
    fun `record codec rejects damaged magic truncation and trailing bytes`() {
        val encoded = CredentialRecordCodec.encode(record())
        val damagedMagic = encoded.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(0)

        assertFailsWith<IllegalArgumentException> { CredentialRecordCodec.decode(damagedMagic) }
        assertFailsWith<EOFException> { CredentialRecordCodec.decode(truncated) }
        assertFailsWith<IllegalArgumentException> { CredentialRecordCodec.decode(trailing) }
    }

    @Test
    fun `record codec rejects unsupported field sizes before allocation`() {
        val encoded = CredentialRecordCodec.encode(record()).copyOf()
        encoded[15] = 23

        assertFailsWith<IllegalArgumentException> { CredentialRecordCodec.decode(encoded) }
    }

    @Test
    fun `record filenames are deterministic opaque hashes scoped by reference`() {
        val firstReference = CredentialReference("secure-store:provider:sample")
        val secondReference = CredentialReference("secure-store:provider:another")

        val first = credentialRecordFileName(firstReference)
        val repeated = credentialRecordFileName(firstReference)
        val second = credentialRecordFileName(secondReference)

        assertEquals(first, repeated)
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}\\.bin")))
        assertFalse(first.contains(firstReference.value))
    }

    @Test
    fun `encoding the same record is stable and contains no reference metadata`() {
        val first = CredentialRecordCodec.encode(record())
        val second = CredentialRecordCodec.encode(record())

        assertContentEquals(first, second)
        assertFalse(first.toString(Charsets.US_ASCII).contains("secure-store"))
    }

    private fun record(): EncryptedCredentialRecord = EncryptedCredentialRecord(
        version = 1,
        initializationVectorHex = "00112233445566778899aabb",
        ciphertextHex = "00112233445566778899aabbccddeeff",
    )
}
