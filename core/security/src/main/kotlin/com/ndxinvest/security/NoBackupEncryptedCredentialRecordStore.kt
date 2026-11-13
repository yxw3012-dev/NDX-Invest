package com.ndxinvest.security

import android.content.Context
import android.util.AtomicFile
import com.ndxinvest.network.CredentialReference
import com.ndxinvest.network.EncryptedCredentialRecord
import com.ndxinvest.network.EncryptedCredentialRecordStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Persists only versioned ciphertext records in the app-private no-backup directory. */
public class NoBackupEncryptedCredentialRecordStore public constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EncryptedCredentialRecordStore {
    private val directory: File = File((context.applicationContext ?: context).noBackupFilesDir, DIRECTORY_NAME)

    override suspend fun read(reference: CredentialReference): EncryptedCredentialRecord? = withContext(ioDispatcher) {
        storageMutex.withLock {
            val atomicFile = atomicFile(reference)
            val encoded = try {
                atomicFile.openRead().use(::readBounded)
            } catch (_: FileNotFoundException) {
                return@withLock null
            }
            try {
                CredentialRecordCodec.decode(encoded)
            } finally {
                encoded.fill(0)
            }
        }
    }

    override suspend fun write(
        reference: CredentialReference,
        record: EncryptedCredentialRecord,
    ): Unit = withContext(ioDispatcher) {
        storageMutex.withLock {
            ensureDirectory()
            val encoded = CredentialRecordCodec.encode(record)
            try {
                val atomicFile = atomicFile(reference)
                var output: FileOutputStream? = null
                try {
                    output = atomicFile.startWrite()
                    output.write(encoded)
                    atomicFile.finishWrite(output)
                    output = null
                } finally {
                    output?.let(atomicFile::failWrite)
                }
            } finally {
                encoded.fill(0)
            }
        }
    }

    override suspend fun remove(reference: CredentialReference): Boolean = withContext(ioDispatcher) {
        storageMutex.withLock {
            val atomicFile = atomicFile(reference)
            val existed = try {
                atomicFile.openRead().use { }
                true
            } catch (_: FileNotFoundException) {
                false
            }
            if (existed) atomicFile.delete()
            existed
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            error("Credential record directory is unavailable")
        }
    }

    private fun atomicFile(reference: CredentialReference): AtomicFile =
        AtomicFile(File(directory, credentialRecordFileName(reference)))

    private fun readBounded(input: FileInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= CredentialRecordCodec.MAX_FILE_BYTES) { "Credential record is too large" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    public companion object {
        public const val DIRECTORY_NAME: String = "ndx-invest/credentials-v1"

        private const val READ_BUFFER_BYTES: Int = 4_096
        private val storageMutex: Mutex = Mutex()
    }
}
