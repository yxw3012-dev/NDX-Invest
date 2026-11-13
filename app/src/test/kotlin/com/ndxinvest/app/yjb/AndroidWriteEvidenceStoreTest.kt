package com.ndxinvest.app.yjb

import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.verification.ExistingPositionEvidence as E
import com.ndxinvest.yjb.verification.WritePrecisionEvidence as W
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import kotlin.test.*

/** Host encryption/gating test only. Synthetic proof never touches device production storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[PosixAtomicFileShadow::class])
class AndroidWriteEvidenceStoreTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun proof():JsonObject {
        val b=Json.parseToJsonElement("""{"fund_id":2,"hold_share":100.12,"hold_cost":2.1234}""").jsonObject
        val d=E.reviewDigest("1","2",b.toString(),"100.13012345","2.12352345","0.02","0.0002","1")
        return buildJsonObject {
            put("semanticsEvidence",W.SEMANTICS);put("probeId","HOST_SYNTHETIC_ONLY")
            put("realPostMayHaveBeenSent",true);put("ownedAccountChecked",true);put("freshBaselineChecked",true);put("getVerified",true)
            put("postStartedAt","2026-10-03T00:00:00Z");put("getAt","2026-10-03T00:00:01Z")
            put("accountId","1");put("fundId","2");put("baseline",b)
            put("actual",Json.parseToJsonElement("""{"fund_id":2,"hold_share":100.13,"hold_cost":2.1235}"""))
            put("shareTarget","100.13012345");put("costTarget","2.12352345")
            put("shareLimit","0.02");put("costLimit","0.0002");put("totalCostLimit","1")
            put("approvedDigest",d);put("reviewDigest",d)
        }
    }
    private fun key()=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
    private fun releaseProof()=JsonObject(proof()+mapOf("actual" to
        Json.parseToJsonElement("""{"fund_id":2,"hold_share":100.13,"hold_cost":2.12352345}""")))
    @Test fun proposalAloneNeverBecomesPrivateVerifiedEvidence()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store.write(buildJsonArray {add(buildJsonObject {put("realPostMayHaveBeenSent",false)})})
        assertNull(store.verified())
    }
    @Test fun encryptedVerifiedEvidenceRestoresAcrossInstancesAndKeepsValuesPrivate()=runTest {
        val k=key();fun store()=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store().write(JsonArray(listOf(proof())))
        assertEquals(2,store().verified()!!.profile.shareScale)
        assertEquals(4,store().verified()!!.profile.unitCostScale)
        val bytes=java.io.File(context.noBackupFilesDir,"ndx-yjb-precision-evidence-v1.enc").readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains("HOST_SYNTHETIC_ONLY"));assertFalse(bytes.contains("100.13012345"))
    }
    @Test fun unresolvedNewerPostCannotFallBackToOlderVerifiedEvidence()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store.write(JsonArray(listOf(proof(),JsonObject(proof()+mapOf("getVerified" to JsonPrimitive(false))))))
        assertNull(store.verified())
    }
    @Test fun corruptedEvidenceDisablesWritesWithoutDeletingOrRepairingTheFile()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store.write(JsonArray(listOf(proof())))
        val file=java.io.File(context.noBackupFilesDir,"ndx-yjb-precision-evidence-v1.enc")
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertNull(store.verified());assertContentEquals(bytes,file.readBytes())
    }
    @Test fun nativeProductionGateUsesReleaseProtocolAndLiveOwnershipAndClosesOnUnresolvedAttempt()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k});store.write(JsonArray(emptyList()))
        var posts=0;var factories=0;var ownershipChecks=0
        val gate=AndroidVerifiedYjbWriter(AndroidYjbStorage(context),store,createWriter={p->
            factories++
            object:VerifiedYjbWrite {
                override val evidence=p.evidence
                override fun normalize(target:BuyBaseline)=target
                override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
                override suspend fun post(fund:BuyFund,target:BuyBaseline) {posts++} // fake only; never opens a socket
            }
        },checkOwnership={fund->ownershipChecks++;if(fund.accountId!="1")throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)})
        gate.restore();assertTrue(gate.evidence.isNotBlank());assertEquals(1,factories)
        store.write(JsonArray(listOf(releaseProof())));gate.restore();assertTrue(gate.evidence.isNotBlank());assertEquals(2,factories)
        val target=BuyBaseline("100.13".toBigDecimal(),"2.1235".toBigDecimal())
        var marked=false
        assertEquals(BuyProblem.ACCOUNT_MISMATCH,assertFailsWith<BuyFailure>{gate.postGuarded(BuyFund("99","2","270042","synthetic"),target){marked=true}}.problem)
        assertFalse(marked)
        assertEquals(0,posts)
        gate.post(BuyFund("1","2","270042","synthetic"),target);assertEquals(1,posts)
        assertEquals(2,ownershipChecks)
        store.write(JsonArray(listOf(releaseProof(),JsonObject(releaseProof()+mapOf("getVerified" to JsonPrimitive(false))))))
        gate.restore();assertEquals("",gate.evidence);assertEquals(2,factories)
        assertFailsWith<BuyFailure>{gate.post(BuyFund("1","2","270042","synthetic"),target)}
        assertEquals(1,posts)
        assertEquals(2,ownershipChecks)
    }
    @Test fun sharedProtocolWorksForNewOwnedAccountWithoutCopyingOldAccountProof()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store.write(JsonArray(listOf(releaseProof())))
        var posts=0
        val gate=AndroidVerifiedYjbWriter(AndroidYjbStorage(context),store,createWriter={p->
            assertEquals(2,p.shareScale);assertEquals(8,p.unitCostScale)
            object:VerifiedYjbWrite {
                override val evidence=p.evidence
                override fun normalize(target:BuyBaseline)=target
                override fun matches(actual:BuyBaseline,target:BuyBaseline)=actual.equivalent(target)
                override suspend fun post(fund:BuyFund,target:BuyBaseline){posts++}
            }
        },checkOwnership={fund->if(fund.accountId!="99")throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)})
        gate.restore()
        gate.post(BuyFund("99","8","040046","synthetic new user"),BuyBaseline("1".toBigDecimal(),"2".toBigDecimal()))
        assertEquals(1,posts)
        assertEquals(BuyProblem.ACCOUNT_MISMATCH,assertFailsWith<BuyFailure>{
            gate.post(BuyFund("1","2","270042","old proof owner"),BuyBaseline("1".toBigDecimal(),"2".toBigDecimal()))
        }.problem)
        assertEquals(1,posts)
    }
    @Test fun newInstallGetsReleaseScalesButCorruptOrDifferentLocalEvidenceNeverFallsBack()=runTest {
        val k=key();val store=AndroidWriteEvidenceStore(context,CredentialKeyProvider{k})
        store.write(JsonArray(emptyList()))
        assertEquals(2,store.productionPrecision()!!.shareScale);assertEquals(8,store.productionPrecision()!!.unitCostScale)
        store.write(JsonArray(listOf(proof()))) // valid synthetic 2/4 evidence is incompatible with release 2/8
        assertNull(store.productionPrecision())
        store.write(JsonArray(listOf(releaseProof())))
        val file=java.io.File(context.noBackupFilesDir,"ndx-yjb-precision-evidence-v1.enc")
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertNull(store.productionPrecision());assertContentEquals(bytes,file.readBytes())
    }
}
