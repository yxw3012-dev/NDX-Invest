package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.YjbFailure
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import javax.crypto.KeyGenerator
import kotlin.test.*

/** Host AES/filesystem verification. Does not claim real device Keystore acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[PosixAtomicFileShadow::class])
class AndroidCloseoutStorageTest {
    @Test fun durableCreationIntentAndResultRestoreEncryptedWithoutSharingTransactionFiles()=runTest {
        val c=ApplicationProvider.getApplicationContext<android.content.Context>()
        val k=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val key=CredentialKeyProvider{k}
        val body=buildJsonObject {put("fundId","private-fund-id");put("amountInput","12345.123456789")}
        val first=CreationRequests(AndroidCreationRequests(c,key)).prepare("buy-create:",body)
        val id=first["requestId"]!!.jsonPrimitive.content
        val restored=CreationRequests(AndroidCreationRequests(c,key));assertEquals(first,restored.pending())
        val file=java.io.File(c.noBackupFilesDir,"ndx-create-requests-v1.enc")
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private-fund"));assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("12345.123"))
        restored.complete(id,buildJsonObject {put("requestId",id);put("ok",true)})
        assertTrue(CreationRequests(AndroidCreationRequests(c,key)).pending()!!["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        restored.acknowledge(id);assertNull(CreationRequests(AndroidCreationRequests(c,key)).pending())
        assertFalse(java.io.File(c.noBackupFilesDir,"ndx-buy-transactions-v1.enc").exists())
    }
    @Test fun formalNavCacheRestoresSeparatelyAndTamperingNeverResetsIt()=runTest {
        val c=ApplicationProvider.getApplicationContext<android.content.Context>()
        val k=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey();val key=CredentialKeyProvider{k}
        val rows=JsonArray(listOf(buildJsonObject {put("fundId","private-nav-fund");put("unit_net","4.123456789")}))
        AndroidFormalNavStorage(c,key).write(rows);assertEquals(rows,AndroidFormalNavStorage(c,key).read())
        val file=java.io.File(c.noBackupFilesDir,"ndx-formal-nav-v1.enc");val bytes=file.readBytes();assertFalse(bytes.toString(Charsets.UTF_8).contains("private-nav"))
        bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertFailsWith<YjbFailure>{AndroidFormalNavStorage(c,key).read()};assertContentEquals(bytes,file.readBytes())
    }
}
