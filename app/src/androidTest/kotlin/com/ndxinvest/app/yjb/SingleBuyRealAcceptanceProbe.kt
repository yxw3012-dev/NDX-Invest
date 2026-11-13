package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.security.MessageDigest
import java.util.UUID

/** Explicit real BUY acceptance only. No financial defaults, no automatic run in a test suite, no SELL. */
@RunWith(AndroidJUnit4::class)
class SingleBuyRealAcceptanceProbe {
    @Test fun oneUserSpecifiedBuyOnly()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("ndxBuyAcceptanceMode")
        assumeTrue(BuildConfig.FLAVOR=="production" && mode in setOf("PLAN","RUN"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=AndroidYjbStorage(context)
        val evidence=AndroidWriteEvidenceStore(context)
        val verified=evidence.verified()?:error("PRECISION_NOT_VERIFIED")
        val writer=AndroidVerifiedYjbWriter(storage,evidence).also {it.restore()}
        val api=YjbReadApi(HttpsYjbWire(),storage::protocolKey)
        val gateway=YjbBuyGateway(api,storage::token,writer)
        val plans=AndroidEncryptedImports(context,AndroidKeystoreCredentialKeyProvider("ndx_buy_acceptance_v1"),
            "ndx-buy-acceptance-v1.enc","ndx-buy-acceptance-v1")
        if(mode=="PLAN") {
            val code=args.getString("ndxBuyFundCode")?:error("EXPLICIT_FUND_CODE_REQUIRED")
            val calendar=BundledBuyCalendar.forCode(code)
            val token=storage.token()?:error("NO_SESSION")
            val snapshot=try {api.snapshot(token)}finally {token.fill('\u0000')}
            val holdings=YjbMapper.map(snapshot)["holdings"]!!.jsonArray
            val candidates=holdings.filter {it.jsonObject.text("fundCode")==code}
            check(candidates.size==1) {"FUND_NOT_AVAILABLE_IN_CURRENT_FRONTEND_OR_AMBIGUOUS"}
            val h=candidates.single().jsonObject;val ids=h.text("id").split(':')
            val fund=BuyFund(ids[1],ids[2],code,h.text("name"))
            check(fund.accountId==verified.accountId) {"ACCOUNT_OUTSIDE_VERIFIED_PRECISION_SCOPE"}
            val amount=args.getString("ndxBuyAmount")?:error("EXPLICIT_BUY_AMOUNT_REQUIRED")
            val percent=args.getString("ndxBuyFeePercent")?:error("EXPLICIT_FEE_RATE_REQUIRED")
            val date=args.getString("ndxBuyTradeDate")?:error("EXPLICIT_TRADE_DATE_REQUIRED")
            val cutoff=when(args.getString("ndxBuyCutoff")){"before"->CutoffType.BEFORE_1500;"after"->CutoffType.AFTER_1500;else->error("EXPLICIT_CUTOFF_REQUIRED")}
            val amountValue=decimal(amount);val feeValue=decimal(percent).movePointLeft(2)
            check(amountValue.signum()>0 && feeValue.signum()>=0 && feeValue<java.math.BigDecimal.ONE) {"INVALID_BUY_INPUT"}
            val tradeDate=LocalDate.parse(date)
            check(tradeDate<=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))) {"FUTURE_TRADE_DATE"}
            val input=BuyInput(fund,amountValue,feeValue,tradeDate,cutoff,"真实 BUY 链路验收")
            val (expected,source)=BuyNavDates.forecast(input,calendar)
            val baseline=gateway.current(fund)
            val resolved=gateway.resolveOfficialNav(fund,BuyNavDates.searchStart(input)) {BuyNavDates.knownClosed(it,calendar)}
            val nav=resolved?.value
            val id=UUID.randomUUID().toString();val now=Instant.now()
            val draft=BuyTransaction(id,input,baseline,expected,createdAt=now,updatedAt=now,calendarSource=source)
            val confirmed=resolved?.let {BuyCalculation.confirmResolved(draft,it,now)}
            val submitted=confirmed?.target?.let(writer::normalize)
            val review=buildJsonObject {
                put("transactionId",id);put("accountId",fund.accountId);put("fundId",fund.fundId);put("fundCode",fund.fundCode);put("fundName",fund.fundName)
                put("amount",amount);put("feePercent",percent);put("tradeDate",date);put("cutoff",cutoff.name)
                put("expectedNavDate",expected.toString());put("officialNav",nav?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("confirmedNavDate",resolved?.date?.toString()?.let(::JsonPrimitive)?:JsonNull)
                put("baselineShares",baseline.shares.toPlainString());put("baselineUnitCost",baseline.unitCost.toPlainString())
                put("submittedShares",submitted?.shares?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("submittedUnitCost",submitted?.unitCost?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("shareScale",verified.profile.shareScale);put("unitCostScale",verified.profile.unitCostScale)
                put("precisionEvidenceDigest",hash(verified.profile.evidence));put("note",draft.input.note)
            }
            val digest=hash(review.toString())
            val record=buildJsonObject {put("reviewPayload",review);put("reviewDigest",digest);put("attempted",false);put("capturedAt",now.toString())}
            check(plans.write(JsonArray(plans.read()+record))) {"STORAGE"}
            status("ndxBuyReview",JsonObject(review.filterKeys {it !in setOf("accountId","fundId")}+mapOf("reviewDigest" to JsonPrimitive(digest))).toString())
            return@runBlocking
        }
        check(args.getString("ndxAllowOneRealBuy")=="YES_ONE_BUY") {"EXPLICIT_BUY_EXECUTION_REQUIRED"}
        val digest=args.getString("ndxApprovedBuyDigest")?:error("EXACT_BUY_PLAN_REQUIRED")
        val rows=plans.read();val index=rows.indexOfLast {it.jsonObject.text("reviewDigest")==digest}
        check(index>=0) {"BUY_PLAN_NOT_FOUND"}
        val record=rows[index].jsonObject;check(!record.flag("attempted")) {"BUY_ALREADY_ATTEMPTED_NO_AUTOMATIC_REPEAT"}
        val review=record["reviewPayload"]!!.jsonObject;check(hash(review.toString())==digest) {"BUY_PLAN_CHANGED"}
        check(review.text("precisionEvidenceDigest")==hash(verified.profile.evidence)) {"PRECISION_PROFILE_CHANGED"}
        val fund=BuyFund(review.text("accountId"),review.text("fundId"),review.text("fundCode"),review.text("fundName"))
        check(fund.accountId==verified.accountId) {"WRONG_BUY_SCOPE"}
        val baseline=BuyBaseline(decimal(review.text("baselineShares")),decimal(review.text("baselineUnitCost")))
        check(gateway.current(fund).equivalent(baseline)) {"BASELINE_CHANGED_NO_BUY"}
        check(review["officialNav"]!=JsonNull) {"TARGET_NAV_NOT_YET_PUBLISHED"}
        val confirmedDate=LocalDate.parse((review["confirmedNavDate"] as? JsonPrimitive)?.contentOrNull?:review.text("expectedNavDate"))
        val nav=gateway.officialNav(fund,confirmedDate)
        check(nav!=null && nav.compareTo(decimal(review.text("officialNav")))==0) {"OFFICIAL_NAV_CHANGED_NO_BUY"}
        val marker=JsonObject(record+mapOf("attempted" to JsonPrimitive(true),"startedAt" to JsonPrimitive(Instant.now().toString())))
        check(plans.write(JsonArray(rows.mapIndexed {i,r->if(i==index)marker else r}))) {"STORAGE"}
        check(plans.read()[index]==marker) {"ATTEMPT_MARKER_NOT_DURABLE"}
        val id=review.text("transactionId")
        val journal=AndroidBuyStorage(context)
        val repository=SingleBuyRepository(journal,gateway,BundledBuyCalendar::forCode,emit={saved->
            saved.singleOrNull {it.id==id}?.let {status("ndxBuyState",it.status.name)}
        })
        repository.restore()
        val waiting=repository.create(BuyInput(fund,decimal(review.text("amount")),decimal(review.text("feePercent")).movePointLeft(2),
            LocalDate.parse(review.text("tradeDate")),CutoffType.valueOf(review.text("cutoff")),review.text("note")),id)
        if(!waiting.baseline.equivalent(baseline)) {repository.cancel(id);error("BASELINE_CHANGED_DURING_CREATE_NO_POST")}
        val ready=repository.confirmNav(id)
        check(ready.status==BuyStatus.READY_TO_SYNC && ready.confirmedNavDate==confirmedDate && ready.confirmedNav?.compareTo(nav)==0) {"NOT_READY_NO_POST"}
        val target=writer.normalize(ready.target!!)
        check(target.equivalent(BuyBaseline(decimal(review.text("submittedShares")),decimal(review.text("submittedUnitCost"))))) {"TARGET_DIFFERS_FROM_REVIEW_NO_POST"}
        val result=repository.sync(id) // exactly one call; repository performs fresh conflict GET, POST and verification GET
        val final=JsonObject(marker+mapOf("resultStatus" to JsonPrimitive(result.status.name),"finishedAt" to JsonPrimitive(Instant.now().toString())))
        val latest=plans.read();check(plans.write(JsonArray(latest.mapIndexed {i,r->if(i==index)final else r}))) {"STORAGE"}
        status("ndxBuyAcceptance",buildJsonObject {
            put("status",result.status.name);put("fundCode",fund.fundCode);put("transactionId",id)
            put("confirmedNavDate",result.confirmedNavDate?.toString());put("confirmedNav",result.confirmedNav?.toPlainString())
            put("confirmedShares",result.confirmedShares?.toPlainString());put("feeAmount",result.feeAmount?.toPlainString())
            put("postMayHaveBeenSent",result.postMayHaveBeenSent);put("problem",result.problem?.name)
        }.toString())
        check(result.status==BuyStatus.SYNCED) {"REAL_BUY_NOT_SYNCED_NO_AUTOMATIC_REPEAT"}
    }
    private fun decimal(s:String)=com.ndxinvest.yjb.verification.ExistingPositionEvidence.decimal(s)
    private fun hash(s:String):String {val bytes=s.toByteArray();return try {MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}}finally {bytes.fill(0)}}
    private fun JsonObject.text(k:String)=(get(k) as? JsonPrimitive)?.contentOrNull?:error("INCOMPLETE_BUY_PLAN")
    private fun JsonObject.flag(k:String)=(get(k) as? JsonPrimitive)?.booleanOrNull==true
    private fun status(k:String,v:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString(k,v)})
}
