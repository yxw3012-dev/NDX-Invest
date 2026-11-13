package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.network.CredentialKeyProvider
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.verification.WritePrecisionEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Separate encrypted protocol evidence; no credential, holdings-cache, history or Room mutation. */
internal class AndroidWriteEvidenceStore(context:Context,
    key:CredentialKeyProvider=AndroidKeystoreCredentialKeyProvider("ndx_yjb_precision_evidence_v1")) {
    private val encrypted=AndroidEncryptedImports(context,key,"ndx-yjb-precision-evidence-v1.enc","ndx-yjb-precision-evidence-v1")
    suspend fun read():JsonArray=encrypted.read()
    suspend fun write(rows:JsonArray) {
        if(!encrypted.write(rows)||encrypted.read()!=rows)throw BuyFailure(BuyProblem.STORAGE)
    }
    suspend fun verified():WritePrecisionEvidence.Verified?=try {
        val attempted=read().map {it.jsonObject}.lastOrNull {it["realPostMayHaveBeenSent"]?.jsonPrimitive?.booleanOrNull==true}
        // An unresolved newer attempt must not fall back to old proof and allow concurrent writes.
        attempted?.let {WritePrecisionEvidence.verified(it)}
    }catch(e:CancellationException){throw e}catch(_:Exception){null}

    /** New-install absence is allowed; existing uncertain/corrupt/incompatible evidence stays closed. */
    suspend fun productionPrecision():VerifiedWritePrecision? {
        return try {
            val attempts=read().map {it.jsonObject}
            val attempted=attempts.lastOrNull {it["realPostMayHaveBeenSent"]?.jsonPrimitive?.booleanOrNull==true}
            val release=BundledYjbWriteProtocol.precision
            if(attempted==null)return release
            if(attempted["getVerified"]?.jsonPrimitive?.booleanOrNull!=true)return null
            val verified=WritePrecisionEvidence.verified(attempted)
            if(verified.profile.shareScale!=release.shareScale||verified.profile.unitCostScale!=release.unitCostScale)return null
            release
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }
}

/** Release protocol portability never authorizes an account: authenticate and check ownership per POST. */
internal class AndroidVerifiedYjbWriter(private val storage:AndroidYjbStorage,
    private val evidenceStore:AndroidWriteEvidenceStore,
    private val createWriter:(VerifiedWritePrecision)->VerifiedYjbWrite={YjbFinalPositionWriter(it,storage::token,storage::protocolKey)},
    private val checkOwnership:suspend(BuyFund)->Unit={fund->
        val api=com.ndxinvest.yjb.read.YjbReadApi(com.ndxinvest.yjb.read.HttpsYjbWire(),storage::protocolKey)
        // Independent live GET of owned accounts + existing holding; no cached account/probe identity.
        YjbBuyGateway(api,storage::token).current(fund)
    }):VerifiedYjbWrite {
    private var delegate:VerifiedYjbWrite?=null
    override val evidence:String get()=delegate?.evidence?:""
    suspend fun restore() {
        delegate=null
        val profile=evidenceStore.productionPrecision()?:return
        delegate=createWriter(profile)
    }
    override fun normalize(target:BuyBaseline)=delegate?.normalize(target)?:throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
    override fun matches(actual:BuyBaseline,target:BuyBaseline)=delegate?.matches(actual,target)?:actual.equivalent(target)
    override suspend fun post(fund:BuyFund,target:BuyBaseline)=postGuarded(fund,target) {}
    override suspend fun postGuarded(fund:BuyFund,target:BuyBaseline,beforeSend:suspend()->Unit) {
        val writer=delegate?:throw BuyFailure(BuyProblem.WRITE_UNVERIFIED)
        checkOwnership(fund)
        writer.postGuarded(fund,target,beforeSend)
    }
}
