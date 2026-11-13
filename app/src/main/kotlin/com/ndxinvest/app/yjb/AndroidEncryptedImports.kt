package com.ndxinvest.app.yjb

import android.content.Context
import android.util.AtomicFile
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.read.*
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class AndroidEncryptedImports(context:Context,private val key:CredentialKeyProvider,
    fileName:String="yjb-initial-positions-v1.enc",aadName:String="ndx-initial-positions-v1") {
    private val file=AtomicFile(File(context.noBackupFilesDir,fileName))
    private val aad=aadName.toByteArray()
    suspend fun read():JsonArray=withContext(Dispatchers.IO) {
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return@withContext JsonArray(emptyList())
        try {
            val bytes=file.openRead().use {input->val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);while(true){val n=input.read(chunk);if(n<0)break;if(out.size()+n>4*1024*1024)throw YjbFailure(ReadError.STORAGE);out.write(chunk,0,n)};out.toByteArray()}
            val b=ByteBuffer.wrap(bytes);if(b.int!=1)throw YjbFailure(ReadError.STORAGE)
            val iv=ByteArray(12).also {b.get(it)};val encrypted=ByteArray(b.remaining()).also {b.get(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key.getOrCreate(),GCMParameterSpec(128,iv));cipher.updateAAD(aad)
            val clear=cipher.doFinal(encrypted)
            try{Json.parseToJsonElement(clear.toString(Charsets.UTF_8)).jsonArray}finally{clear.fill(0)}
        }catch(e:CancellationException){throw e}catch(_:Exception){throw YjbFailure(ReadError.STORAGE)}
    }
    suspend fun write(rows:JsonArray):Boolean=withContext(Dispatchers.IO) {
        val clear=rows.toString().toByteArray()
        try {
            if(clear.size>4*1024*1024-64)return@withContext false
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key.getOrCreate());c.updateAAD(aad)
            val encrypted=c.doFinal(clear);val bytes=ByteBuffer.allocate(16+encrypted.size).putInt(1).put(c.iv).put(encrypted).array()
            val out=file.startWrite();try{out.write(bytes);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
            // AtomicFile may log a rename failure instead of throwing. Never acknowledge stale data.
            val persisted=file.openRead().use {input->val data=ByteArray(bytes.size+1);var size=0;while(size<data.size){val n=input.read(data,size,data.size-size);if(n<0)break;size+=n};data.copyOf(size)}
            persisted.contentEquals(bytes)
        }catch(e:CancellationException){throw e}catch(_:Exception){false}finally{clear.fill(0)}
    }
}
