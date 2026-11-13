package com.ndxinvest.app.yjb

import android.content.Context
import android.util.AtomicFile
import com.ndxinvest.network.*
import com.ndxinvest.security.AndroidCredentialVaultFactory
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.read.YjbReadStorage
import com.ndxinvest.yjb.read.YjbFailure
import com.ndxinvest.yjb.read.ReadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal class AndroidYjbStorage(context:Context,
    private val vault:CredentialVault=AndroidCredentialVaultFactory.create(context),
    private val key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_yjb_snapshot_v1")
):YjbReadStorage {
    private val session=CredentialReference("yjb.session.v1")
    private val signing=CredentialReference("yjb.protocol.v1")
    private val snapshotFile=AtomicFile(File(context.noBackupFilesDir,"yjb-read-snapshot.enc"))
    override suspend fun token():CharArray?=vault.resolve(session)?.use { it.withCharacters { chars -> chars.copyOf() } }
    override suspend fun saveToken(value:CharArray):Boolean=vault.storeAndClear(session,value)==CredentialSaveResult.STORED
    suspend fun protocolKey():CharArray {
        vault.resolve(signing)?.use { return it.withCharacters { chars->chars.copyOf() } }
        error("Legacy protocol configuration unavailable")
    }
    override suspend fun snapshot():JsonObject?=withContext(Dispatchers.IO) {
        try {
            val file=snapshotFile.openRead().use { input ->
                val length=snapshotFile.baseFile.length();if(length !in 33..MAX_CACHE_BYTES) return@withContext null
                val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                while(true){val n=input.read(chunk);if(n<0)break;if(output.size()+n>MAX_CACHE_BYTES)return@withContext null;output.write(chunk,0,n)}
                output.toByteArray()
            }
            val buffer=ByteBuffer.wrap(file)
            if(buffer.int!=FORMAT_MAGIC)return@withContext null
            val iv=ByteArray(12).also(buffer::get);val encrypted=ByteArray(buffer.remaining()).also(buffer::get)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key.getOrCreate(),GCMParameterSpec(128,iv));cipher.updateAAD(AAD)
            val clear=cipher.doFinal(encrypted)
            try { Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonObject } finally { clear.fill(0) }
        } catch (_:Exception) {null}
    }
    override suspend fun saveSnapshot(value:JsonObject):Boolean=withContext(Dispatchers.IO) {
        val clear=value.toString().toByteArray(Charsets.UTF_8)
        try {
            if(clear.size>MAX_CACHE_BYTES-64)return@withContext false
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key.getOrCreate());cipher.updateAAD(AAD)
            val encrypted=cipher.doFinal(clear)
            val bytes=ByteBuffer.allocate(4+12+encrypted.size).putInt(FORMAT_MAGIC).put(cipher.iv).put(encrypted).array()
            val output=snapshotFile.startWrite()
            try {output.write(bytes);snapshotFile.finishWrite(output)}catch(e:Exception){snapshotFile.failWrite(output);throw e}
            true
        }catch(_:Exception){false}finally{clear.fill(0)}
    }
    // Separate encrypted local history: disconnect clears session/cache, never these imports.
    private val history=AndroidEncryptedImports(context,key)
    override suspend fun imports():JsonArray=history.read()
    override suspend fun saveImports(value:JsonArray):Boolean=history.write(value)
    override suspend fun clear() {
        if(vault.remove(session)==CredentialRemoveResult.UNAVAILABLE)throw YjbFailure(ReadError.STORAGE)
        withContext(Dispatchers.IO){snapshotFile.delete();if(snapshotFile.baseFile.exists())throw YjbFailure(ReadError.STORAGE)}
    }
    private companion object {
        const val MAX_CACHE_BYTES=4L*1024*1024
        const val FORMAT_MAGIC=0x594A4231
        val AAD="ndx-yjb-read-snapshot-v1".toByteArray(Charsets.UTF_8)
    }
}
