package com.ndxinvest.app.yjb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.read.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.security.MessageDigest
import java.util.UUID

/** Test-APK-only one SELL review/run. Default skips before credentials/network; never automatic real probing. */
@RunWith(AndroidJUnit4::class)
class SingleSellRealAcceptanceProbe {
    @Test fun oneExplicitlySelectedSellOnly()=runBlocking {
        val args=InstrumentationRegistry.getArguments();val mode=args.getString("ndxSellAcceptanceMode")
        assumeTrue(BuildConfig.FLAVOR=="production"&&mode in setOf("PLAN","RUN"))
        val directory=InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        java.io.RandomAccessFile(java.io.File(directory,"ndx-sell-acceptance.lock"),"rw").use {file->
            file.channel.use {channel->
                val lock=channel.tryLock()?:error("SELL_ACCEPTANCE_ALREADY_RUNNING")
                try {verify(args,mode!!)}finally {lock.release()}
            }
        }
    }
    private suspend fun verify(args:android.os.Bundle,mode:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val storage=AndroidYjbStorage(context)
        val proof=AndroidWriteEvidenceStore(context);val verified=proof.verified()?:error("PRECISION_NOT_VERIFIED")
        val writer=AndroidVerifiedYjbWriter(storage,proof).also {it.restore()}
        val api=YjbReadApi(HttpsYjbWire(),storage::protocolKey);val gateway=YjbBuyGateway(api,storage::token,writer)
        val plans=AndroidEncryptedImports(context,AndroidKeystoreCredentialKeyProvider("ndx_sell_acceptance_v1"),"ndx-sell-acceptance-v1.enc","ndx-sell-acceptance-v1")
        if(mode=="PLAN") {
            val code=args.getString("ndxSellFundCode")?:error("EXPLICIT_FUND_REQUIRED")
            val token=storage.token()?:error("NO_SESSION")
            val snapshot=try {api.snapshot(token)}finally {token.fill('\u0000')}
            val candidates=YjbMapper.map(snapshot)["holdings"]!!.jsonArray.filter {it.jsonObject.text("fundCode")==code}
            check(candidates.size==1) {"FUND_NOT_SELECTABLE_OR_AMBIGUOUS"}
            val h=candidates.single().jsonObject;val parts=h.text("id").split(':');val fund=BuyFund(parts[1],parts[2],code,h.text("name"))
            check(fund.accountId==verified.accountId) {"ACCOUNT_OUTSIDE_VERIFIED_SCOPE"}
            val shares=decimal(args.getString("ndxSellShares")?:error("EXPLICIT_SHARES_REQUIRED"))
            val percent=decimal(args.getString("ndxSellFeePercent")?:error("EXPLICIT_FEE_REQUIRED"))
            val sourceBuyId=args.getString("ndxSellSourceBuyId")
            if(sourceBuyId!=null)verifySourceBuy(context,sourceBuyId,fund,shares)
            val date=LocalDate.parse(args.getString("ndxSellTradeDate")?:error("EXPLICIT_DATE_REQUIRED"))
            val cutoff=when(args.getString("ndxSellCutoff")){"before"->CutoffType.BEFORE_1500;"after"->CutoffType.AFTER_1500;else->error("EXPLICIT_CUTOFF_REQUIRED")}
            check(shares.signum()>0&&shares.precision()<=64&&shares.scale() in 0..24&&percent.signum()>=0&&percent<java.math.BigDecimal("100")) {"INVALID_SELL_INPUT"}
            check(date<=LocalDate.now(ZoneId.of("Asia/Shanghai"))) {"FUTURE_TRADE_DATE"}
            val baseline=gateway.current(fund);check(shares<=baseline.shares) {"INSUFFICIENT_CURRENT_SHARES"}
            val input=SellInput(fund,shares,percent.movePointLeft(2),date,cutoff,"真实 SELL 链路验收")
            val calendar=BundledBuyCalendar.forCode(code);val (expected,source)=BuyNavDates.forecast(date,cutoff,calendar)
            val nav=gateway.resolveOfficialNav(fund,BuyNavDates.searchStart(date,cutoff)) {BuyNavDates.knownClosed(it,calendar)}
            val id=UUID.randomUUID().toString();val now=Instant.now()
            val draft=SellTransaction(id,input,baseline,expected,createdAt=now,updatedAt=now,calendarSource=source)
            val confirmed=nav?.let {SellCalculation.confirm(draft,it,now)};val target=confirmed?.target?.let(writer::normalize)
            if(target!=null)check(target.shares<baseline.shares&&target.unitCost.compareTo(baseline.unitCost)==0&&
                (if(confirmed.target!!.shares.signum()==0)target.shares.signum()==0 else target.shares.signum()>0)) {"UNREPRESENTABLE_SAFE_TARGET"}
            val review=buildJsonObject {
                put("transactionId",id);put("accountId",fund.accountId);put("fundId",fund.fundId);put("fundCode",code);put("fundName",fund.fundName)
                put("sourceBuyId",sourceBuyId?.let(::JsonPrimitive)?:JsonNull)
                put("shares",shares.toPlainString());put("feePercent",percent.toPlainString());put("tradeDate",date.toString());put("cutoff",cutoff.name)
                put("expectedNavDate",expected.toString());put("confirmedNavDate",nav?.date?.toString()?.let(::JsonPrimitive)?:JsonNull);put("officialNav",nav?.value?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("baselineShares",baseline.shares.toPlainString());put("baselineUnitCost",baseline.unitCost.toPlainString())
                put("submittedShares",target?.shares?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("submittedUnitCost",target?.unitCost?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("submittedShareDecrease",target?.let {baseline.shares.subtract(it.shares).toPlainString()}?.let(::JsonPrimitive)?:JsonNull)
                put("grossAmount",confirmed?.grossAmount?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("feeAmount",confirmed?.feeAmount?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("netAmount",confirmed?.netAmount?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
                put("costPolicy","KEEP_UNIT_COST");put("cashReceiptConfirmed",false);put("precisionDigest",hash(verified.profile.evidence));put("note",input.note)
            }
            val digest=hash(review.toString());val record=buildJsonObject {put("review",review);put("digest",digest);put("attempted",false);put("capturedAt",now.toString())}
            check(plans.write(JsonArray(plans.read()+record))) {"STORAGE"}
            status("ndxSellReview",JsonObject(review.filterKeys {it !in setOf("accountId","fundId")}+mapOf("reviewDigest" to JsonPrimitive(digest))).toString())
            return
        }
        check(args.getString("ndxAllowOneRealSell")=="YES_ONE_SELL") {"EXPLICIT_SELL_EXECUTION_REQUIRED"}
        val digest=args.getString("ndxApprovedSellDigest")?:error("EXACT_REVIEW_REQUIRED")
        val rows=plans.read();val index=rows.indexOfLast {it.jsonObject.text("digest")==digest};check(index>=0) {"PLAN_NOT_FOUND"}
        val record=rows[index].jsonObject;check(record["attempted"]!!.jsonPrimitive.boolean==false) {"ALREADY_ATTEMPTED_NO_REPEAT"}
        val review=record["review"]!!.jsonObject;check(hash(review.toString())==digest&&review.text("precisionDigest")==hash(verified.profile.evidence)) {"REVIEW_OR_PROFILE_CHANGED"}
        val fund=BuyFund(review.text("accountId"),review.text("fundId"),review.text("fundCode"),review.text("fundName"))
        check(fund.accountId==verified.accountId) {"WRONG_ACCOUNT"}
        review["sourceBuyId"]?.jsonPrimitive?.contentOrNull?.let {verifySourceBuy(context,it,fund,decimal(review.text("shares")))}
        val baseline=BuyBaseline(decimal(review.text("baselineShares")),decimal(review.text("baselineUnitCost")))
        check(gateway.current(fund).equivalent(baseline)) {"BASELINE_CHANGED_NO_SELL"}
        check(review["officialNav"]!=JsonNull) {"TARGET_NAV_NOT_PUBLISHED"}
        val date=LocalDate.parse(review.text("confirmedNavDate"));val nav=gateway.officialNav(fund,date)
        check(nav!=null&&nav.compareTo(decimal(review.text("officialNav")))==0) {"NAV_CHANGED_NO_SELL"}
        val marker=JsonObject(record+mapOf("attempted" to JsonPrimitive(true),"startedAt" to JsonPrimitive(Instant.now().toString())))
        check(plans.write(JsonArray(rows.mapIndexed {i,r->if(i==index)marker else r}))) {"STORAGE"};check(plans.read()[index]==marker) {"MARKER_NOT_DURABLE"}
        val id=review.text("transactionId");check(AndroidBuyStorage(context).read().none {it.id==id}) {"BUY_ID_COLLISION"}
        val r=SingleSellRepository(AndroidSellStorage(context),gateway,emit={saved->saved.singleOrNull {it.id==id}?.let {status("ndxSellState",it.status.name)}})
        r.restore();val waiting=r.create(SellInput(fund,decimal(review.text("shares")),decimal(review.text("feePercent")).movePointLeft(2),LocalDate.parse(review.text("tradeDate")),CutoffType.valueOf(review.text("cutoff")),review.text("note")),id)
        if(!waiting.baseline.equivalent(baseline)){r.cancel(id);error("BASELINE_CHANGED_DURING_CREATE_NO_POST")}
        val ready=r.confirmNav(id);check(ready.status==SellStatus.READY_TO_SYNC&&ready.confirmedNavDate==date&&ready.confirmedNav?.compareTo(nav)==0) {"NOT_READY_NO_POST"}
        val target=writer.normalize(ready.target!!);check(target.equivalent(BuyBaseline(decimal(review.text("submittedShares")),decimal(review.text("submittedUnitCost"))))) {"TARGET_CHANGED_NO_POST"}
        val result=r.sync(id) // one sync invocation only; fresh baseline GET, durable marker, POST then GET verify
        val latest=plans.read();check(plans.write(JsonArray(latest.mapIndexed {i,row->if(i==index)JsonObject(marker+mapOf("resultStatus" to JsonPrimitive(result.status.name)))else row}))) {"STORAGE"}
        status("ndxSellAcceptance",buildJsonObject {put("status",result.status.name);put("fundCode",fund.fundCode);put("transactionId",id);put("problem",result.problem?.name);put("postMayHaveBeenSent",result.postMayHaveBeenSent)}.toString())
        check(result.status==SellStatus.SYNCED) {"REAL_SELL_NOT_SYNCED_NO_AUTOMATIC_REPEAT"}
    }
    private suspend fun verifySourceBuy(context:android.content.Context,id:String,fund:BuyFund,shares:java.math.BigDecimal) {
        val source=AndroidBuyStorage(context).read().singleOrNull {it.id==id}?:error("SOURCE_BUY_NOT_FOUND")
        check(source.status==BuyStatus.SYNCED&&source.input.fund==fund&&source.submittedTarget!=null) {"SOURCE_BUY_NOT_VERIFIED_OR_WRONG_FUND"}
        val actualIncrease=source.submittedTarget!!.shares.subtract(source.baseline.shares)
        check(actualIncrease.signum()>0&&shares.compareTo(actualIncrease)==0) {"SELL_MUST_MATCH_VERIFIED_BUY_INCREASE"}
    }
    private fun decimal(s:String)=com.ndxinvest.yjb.verification.ExistingPositionEvidence.decimal(s)
    private fun JsonObject.text(k:String)=(get(k) as? JsonPrimitive)?.contentOrNull?:error("INCOMPLETE_REVIEW")
    private fun hash(s:String):String {val b=s.toByteArray();return try {MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}}finally {b.fill(0)}}
    private fun status(k:String,v:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {putString(k,v)})
}
