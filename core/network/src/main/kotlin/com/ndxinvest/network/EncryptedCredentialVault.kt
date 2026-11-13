package com.ndxinvest.network

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException

public enum class CredentialState {
    NOT_CONFIGURED,
    CONFIGURED,
    UNAVAILABLE,
}

public enum class CredentialSaveResult {
    STORED,
    REJECTED,
    UNAVAILABLE,
}

public enum class CredentialRemoveResult {
    REMOVED,
    NOT_FOUND,
    UNAVAILABLE,
}

public interface CredentialVault : CredentialResolver {
    public suspend fun state(reference: CredentialReference): CredentialState

    /** Stores the credential and clears [characters] before returning. */
    public suspend fun storeAndClear(
        reference: CredentialReference,
        characters: CharArray,
    ): CredentialSaveResult

    public suspend fun remove(reference: CredentialReference): CredentialRemoveResult
}

public fun interface CredentialKeyProvider {
    public suspend fun getOrCreate(): SecretKey
}

public interface EncryptedCredentialRecordStore {
    public suspend fun read(reference: CredentialReference): EncryptedCredentialRecord?

    public suspend fun write(
        reference: CredentialReference,
        record: EncryptedCredentialRecord,
    )

    public suspend fun remove(reference: CredentialReference): Boolean
}

public data class EncryptedCredentialRecord public constructor(
    public val version: Int,
    public val initializationVectorHex: String,
    public val ciphertextHex: String,
) {
    init {
        require(version > 0) { "Credential record version must be positive" }
        require(initializationVectorHex.length == GCM_IV_BYTES * 2 && initializationVectorHex.isHex()) {
            "Credential record IV is invalid"
        }
        require(ciphertextHex.length >= GCM_TAG_BYTES * 2 && ciphertextHex.isHex()) {
            "Credential record ciphertext is invalid"
        }
    }
}

public class AesGcmCredentialVault public constructor(
    private val keyProvider: CredentialKeyProvider,
    private val recordStore: EncryptedCredentialRecordStore,
) : CredentialVault {
    override suspend fun state(reference: CredentialReference): CredentialState = try {
        when (recordStore.read(reference)?.version) {
            null -> CredentialState.NOT_CONFIGURED
            RECORD_VERSION -> CredentialState.CONFIGURED
            else -> CredentialState.UNAVAILABLE
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialState.UNAVAILABLE
    }

    override suspend fun storeAndClear(
        reference: CredentialReference,
        characters: CharArray,
    ): CredentialSaveResult {
        var plaintext: ByteArray? = null
        return try {
            if (!characters.isValidCredential()) return CredentialSaveResult.REJECTED
            plaintext = characters.encodeUtf8()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
            cipher.updateAAD(reference.aad())
            val ciphertext = cipher.doFinal(plaintext)
            recordStore.write(
                reference,
                EncryptedCredentialRecord(
                    version = RECORD_VERSION,
                    initializationVectorHex = cipher.iv.toHex(),
                    ciphertextHex = ciphertext.toHex(),
                ),
            )
            CredentialSaveResult.STORED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CredentialSaveResult.UNAVAILABLE
        } finally {
            plaintext?.fill(0)
            characters.fill('\u0000')
        }
    }

    override suspend fun resolve(reference: CredentialReference): ResolvedCredential? {
        var plaintext: ByteArray? = null
        var characters: CharArray? = null
        return try {
            val record = recordStore.read(reference) ?: return null
            if (record.version != RECORD_VERSION) return null
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider.getOrCreate(),
                GCMParameterSpec(GCM_TAG_BITS, record.initializationVectorHex.hexToBytes()),
            )
            cipher.updateAAD(reference.aad())
            plaintext = cipher.doFinal(record.ciphertextHex.hexToBytes())
            characters = plaintext.decodeUtf8()
            ResolvedCredential.from(characters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            characters?.fill('\u0000')
            plaintext?.fill(0)
        }
    }

    override suspend fun remove(reference: CredentialReference): CredentialRemoveResult = try {
        if (recordStore.remove(reference)) CredentialRemoveResult.REMOVED else CredentialRemoveResult.NOT_FOUND
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CredentialRemoveResult.UNAVAILABLE
    }

    private companion object {
        const val RECORD_VERSION: Int = 1
        const val CIPHER_TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS: Int = 128
    }
}

private const val GCM_IV_BYTES: Int = 12
private const val GCM_TAG_BYTES: Int = 16
private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()

private fun CharArray.isValidCredential(): Boolean =
    isNotEmpty() && any { !it.isWhitespace() } && none { Character.isISOControl(it.code) }

private fun CharArray.encodeUtf8(): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also { output ->
        buffer.get(output)
        if (buffer.hasArray()) buffer.array().fill(0)
    }
}

private fun ByteArray.decodeUtf8(): CharArray {
    val buffer = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
    return CharArray(buffer.remaining()).also { output ->
        buffer.get(output)
        if (buffer.hasArray()) buffer.array().fill('\u0000')
    }
}

private fun CredentialReference.aad(): ByteArray = "$RECORD_FORMAT_PREFIX$value".toByteArray(Charsets.UTF_8)

private const val RECORD_FORMAT_PREFIX: String = "ndx-invest-credential-v1:"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    val offset = index * 2
    ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
}

private fun String.isHex(): Boolean =
    length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
