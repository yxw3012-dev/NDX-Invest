package com.ndxinvest.security

import com.ndxinvest.network.CredentialReference
import com.ndxinvest.network.EncryptedCredentialRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal object CredentialRecordCodec {
    const val MAX_FILE_BYTES: Int = 131_200

    fun encode(record: EncryptedCredentialRecord): ByteArray {
        val initializationVector = record.initializationVectorHex.toByteArray(Charsets.US_ASCII)
        val ciphertext = record.ciphertextHex.toByteArray(Charsets.US_ASCII)
        require(ciphertext.size in MIN_CIPHERTEXT_HEX_BYTES..MAX_CIPHERTEXT_HEX_BYTES) {
            "Credential ciphertext is outside the supported size"
        }
        return ByteArrayOutputStream(FIXED_OVERHEAD_BYTES + initializationVector.size + ciphertext.size).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(record.version)
                output.writeInt(initializationVector.size)
                output.write(initializationVector)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray().also {
                require(it.size <= MAX_FILE_BYTES) { "Credential record is too large" }
            }
        }
    }

    fun decode(bytes: ByteArray): EncryptedCredentialRecord {
        require(bytes.size in FIXED_OVERHEAD_BYTES..MAX_FILE_BYTES) { "Credential record size is invalid" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC) { "Credential record magic is invalid" }
            require(input.readInt() == FORMAT_VERSION) { "Credential record format is unsupported" }
            val recordVersion = input.readInt()
            val initializationVector = input.readAsciiExact(EXPECTED_IV_HEX_BYTES)
            val ciphertextLength = input.readInt()
            require(ciphertextLength in MIN_CIPHERTEXT_HEX_BYTES..MAX_CIPHERTEXT_HEX_BYTES) {
                "Credential ciphertext size is invalid"
            }
            val ciphertext = input.readAscii(ciphertextLength)
            require(input.read() == -1) { "Credential record has trailing data" }
            EncryptedCredentialRecord(
                version = recordVersion,
                initializationVectorHex = initializationVector,
                ciphertextHex = ciphertext,
            )
        }
    }

    private fun DataInputStream.readAsciiExact(expectedLength: Int): String {
        val declaredLength = readInt()
        require(declaredLength == expectedLength) { "Credential record field size is invalid" }
        return readAscii(declaredLength)
    }

    private fun DataInputStream.readAscii(length: Int): String {
        val field = ByteArray(length)
        readFully(field)
        return field.toString(Charsets.US_ASCII)
    }

    private const val MAGIC: Int = 0x4e445843
    private const val FORMAT_VERSION: Int = 1
    private const val EXPECTED_IV_HEX_BYTES: Int = 24
    private const val MIN_CIPHERTEXT_HEX_BYTES: Int = 32
    private const val MAX_CIPHERTEXT_HEX_BYTES: Int = 131_072
    private const val FIXED_OVERHEAD_BYTES: Int = 20
}

internal fun credentialRecordFileName(reference: CredentialReference): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("ndx-invest-credential-file-v1:${reference.value}".toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2 + RECORD_FILE_SUFFIX.length) {
        digest.forEach { byte -> append(byte.toUByte().toString(radix = 16).padStart(2, '0')) }
        append(RECORD_FILE_SUFFIX)
    }
}

private const val RECORD_FILE_SUFFIX: String = ".bin"
