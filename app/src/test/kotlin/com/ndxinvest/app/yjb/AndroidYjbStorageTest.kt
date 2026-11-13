package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.*
import com.ndxinvest.security.NoBackupEncryptedCredentialRecordStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import kotlin.test.*

/** Host filesystem/encryption tests with an injected AES key, not Android Keystore device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class AndroidYjbStorageTest {
    @Test fun initialContributionDraftPersistsEncryptedAcrossRestartWithoutEditingInitialFacts()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val provider=CredentialKeyProvider{key}
        fun storage()=AndroidYjbStorage(context,AesGcmCredentialVault(provider,NoBackupEncryptedCredentialRecordStore(context)),provider)
        val original=Json.parseToJsonElement("""[{"id":"initial:YJB_IMPORT:17","fundId":"17","importedAt":"2026-01-01T01:00:00Z","initialShares":"100","initialUnitCost":"10","initialHoldingCost":"1000","holdingStartDate":"2025-01-01"}]""").jsonArray
        val entries=Json.parseToJsonElement("""[{"date":"2025-01-01","amount":"400.12345678"},{"date":"2025-01-02","amount":"599.87654322"}]""").jsonArray
        val updated=com.ndxinvest.yjb.read.InitialPositionStore.editContributions(original,"initial:YJB_IMPORT:17",entries)
        assertTrue(storage().saveImports(updated));assertEquals(updated,storage().imports())
        assertEquals(original[0].jsonObject["importedAt"],storage().imports()[0].jsonObject["importedAt"])
        // Assert over the dedicated encrypted files rather than relying on a filename.
        val clear=context.noBackupFilesDir.listFiles().orEmpty().filter {it.isFile}.joinToString {it.readBytes().toString(Charsets.UTF_8)}
        assertFalse(clear.contains("400.12345678"));assertFalse(clear.contains("initialContributions"))
        storage().clear();assertEquals(updated,storage().imports())
    }
    @Test fun sessionAndSnapshotAreEncryptedPersistAcrossInstancesAndDisconnectIsIsolated()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val provider=CredentialKeyProvider{key}
        fun storage()=AndroidYjbStorage(context,AesGcmCredentialVault(provider,NoBackupEncryptedCredentialRecordStore(context)),provider)
        val first=storage();first.clear()
        val sentinel=context.getDatabasePath("phase1-history-sentinel.db");sentinel.parentFile!!.mkdirs();sentinel.writeText("history-preserved")
        val token="fake-session-never-live".toCharArray();assertTrue(first.saveToken(token));assertTrue(token.all{it=='\u0000'})
        val raw=Json.parseToJsonElement("""{"groups":[],"quotes":[],"displayName":"private-fixture-name","maskedId":"138****0000","lastSyncAt":"2026-10-02T01:30:00Z"}""").jsonObject
        assertTrue(first.saveSnapshot(raw))
        val encrypted=context.noBackupFilesDir.walkTopDown().filter{it.isFile}.map{it.readBytes().toString(Charsets.UTF_8)}.joinToString()
        assertFalse(encrypted.contains("fake-session-never-live"));assertFalse(encrypted.contains("private-fixture-name"))
        val restart=storage();val restored=restart.token()!!;assertEquals("fake-session-never-live",restored.concatToString());restored.fill('\u0000')
        assertEquals(raw,restart.snapshot())
        val imports=Json.parseToJsonElement("""[{"id":"initial:YJB_IMPORT:17","kind":"INITIAL_POSITION","source":"YJB_IMPORT","fundId":"17","importedAt":"2026-10-02T01:30:00Z","holdingStartDate":null}]""").jsonArray
        assertTrue(first.saveImports(imports));assertEquals(imports,restart.imports())
        restart.clear();assertNull(restart.token());assertNull(restart.snapshot());assertEquals("history-preserved",sentinel.readText())
        assertEquals(imports,storage().imports(),"Disconnect must preserve local import history")
        sentinel.delete() // Isolated host-test sentinel only.
    }
    @Test fun tamperedCiphertextAndWrongKeyCannotBecomeHoldings()=runTest {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        fun provider()=CredentialKeyProvider{KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()}
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val p=CredentialKeyProvider{key}
        val vault=AesGcmCredentialVault(p,NoBackupEncryptedCredentialRecordStore(context))
        val first=AndroidYjbStorage(context,vault,p);first.clear()
        assertTrue(first.saveSnapshot(JsonObject(emptyMap())))
        assertNull(AndroidYjbStorage(context,vault,provider()).snapshot())
        val file=java.io.File(context.noBackupFilesDir,"yjb-read-snapshot.enc");val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertNull(first.snapshot());first.clear()
    }
}
