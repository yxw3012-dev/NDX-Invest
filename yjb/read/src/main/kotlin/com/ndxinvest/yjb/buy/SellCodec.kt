package com.ndxinvest.yjb.buy

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Separate versioned SELL journal; BUY history is never migrated or reinterpreted as shares. */
object SellCodec {
    private fun JsonObject.s(k:String)=get(k)!!.jsonPrimitive.content
    private fun JsonObject.optional(k:String)=get(k)?.takeUnless {it is JsonNull}?.jsonPrimitive?.content
    private fun baseline(b:BuyBaseline)=buildJsonObject {put("shares",b.shares.toPlainString());put("unitCost",b.unitCost.toPlainString())}
    private fun parse(b:JsonObject)=BuyBaseline(b.s("shares").toBigDecimal(),b.s("unitCost").toBigDecimal())
    fun encode(rows:List<SellTransaction>)=JsonArray(rows.map {t->buildJsonObject {
        put("schemaVersion",1);put("autoSync",t.autoSync);put("id",t.id);put("type","SELL");put("costPolicy","KEEP_UNIT_COST")
        put("accountId",t.input.fund.accountId);put("fundId",t.input.fund.fundId);put("fundCode",t.input.fund.fundCode);put("fundName",t.input.fund.fundName)
        put("shares",t.input.shares.toPlainString());put("feeRate",t.input.feeRate.toPlainString());put("tradeDate",t.input.tradeDate.toString());put("cutoffType",t.input.cutoffType.name);put("note",t.input.note)
        put("expectedNavDate",t.expectedNavDate.toString());put("status",t.status.name);put("baseline",baseline(t.baseline))
        t.confirmedNavDate?.let {put("confirmedNavDate",it.toString())};t.confirmedNav?.let {put("confirmedNav",it.toPlainString())}
        t.grossAmount?.let {put("grossAmount",it.toPlainString())};t.feeAmount?.let {put("feeAmount",it.toPlainString())};t.netAmount?.let {put("netAmount",it.toPlainString())}
        t.soldCost?.let {put("soldCost",it.toPlainString())};t.calculatedProfit?.let {put("calculatedProfit",it.toPlainString())}
        t.target?.let {put("target",baseline(it))};t.submittedTarget?.let {put("submittedTarget",baseline(it))};t.external?.let {put("external",baseline(it))}
        t.writeStage?.let {put("writeStage",it.name)};t.writeProblem?.let {put("writeProblem",it.name)}
        t.writeStartedAt?.let {put("writeStartedAt",it.toString())};put("recoveryAttempts",JsonArray(t.recoveryAttempts.map {JsonPrimitive(it.toString())}))
        t.executionBaseline?.let {put("executionBaseline",baseline(it))};put("queuePredecessors",JsonArray(t.queuePredecessors.map(::JsonPrimitive)))
        put("postMayHaveBeenSent",t.postMayHaveBeenSent);t.problem?.let {put("problem",it.name)}
        t.navConfirmedAt?.let {put("navConfirmedAt",it.toString())};put("createdAt",t.createdAt.toString());put("updatedAt",t.updatedAt.toString());t.syncedAt?.let {put("syncedAt",it.toString())};put("calendarSource",t.calendarSource)
    }})
    fun decode(rows:JsonArray):List<SellTransaction> = try {
        rows.map {e->val o=e.jsonObject;require(o.s("schemaVersion")=="1"&&o.s("type")=="SELL"&&o.s("costPolicy")=="KEEP_UNIT_COST")
            SellTransaction(id=o.s("id"),input=SellInput(BuyFund(o.s("accountId"),o.s("fundId"),o.s("fundCode"),o.s("fundName")),o.s("shares").toBigDecimal(),o.s("feeRate").toBigDecimal(),LocalDate.parse(o.s("tradeDate")),CutoffType.valueOf(o.s("cutoffType")),o.s("note")),
                baseline=parse(o["baseline"]!!.jsonObject),expectedNavDate=LocalDate.parse(o.s("expectedNavDate")),status=SellStatus.valueOf(o.s("status")),
                confirmedNavDate=o.optional("confirmedNavDate")?.let(LocalDate::parse),confirmedNav=o.optional("confirmedNav")?.toBigDecimal(),
                grossAmount=o.optional("grossAmount")?.toBigDecimal(),feeAmount=o.optional("feeAmount")?.toBigDecimal(),netAmount=o.optional("netAmount")?.toBigDecimal(),
                soldCost=o.optional("soldCost")?.toBigDecimal(),calculatedProfit=o.optional("calculatedProfit")?.toBigDecimal(),
                target=o["target"]?.jsonObject?.let(::parse),submittedTarget=o["submittedTarget"]?.jsonObject?.let(::parse),external=o["external"]?.jsonObject?.let(::parse),
                postMayHaveBeenSent=o.s("postMayHaveBeenSent").toBooleanStrict(),problem=o.optional("problem")?.let(BuyProblem::valueOf),createdAt=Instant.parse(o.s("createdAt")),updatedAt=Instant.parse(o.s("updatedAt")),syncedAt=o.optional("syncedAt")?.let(Instant::parse),calendarSource=o.s("calendarSource"),navConfirmedAt=o.optional("navConfirmedAt")?.let(Instant::parse),autoSync=o.optional("autoSync")?.toBooleanStrict()?:false,executionBaseline=o["executionBaseline"]?.jsonObject?.let(::parse),queuePredecessors=o["queuePredecessors"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty(),writeStage=o.optional("writeStage")?.let(WriteStage::valueOf),writeProblem=o.optional("writeProblem")?.let(BuyProblem::valueOf),writeStartedAt=o.optional("writeStartedAt")?.let(Instant::parse),recoveryAttempts=o["recoveryAttempts"]?.jsonArray?.map {Instant.parse(it.jsonPrimitive.content)}.orEmpty())
        }.also {require(it.map {t->t.id}.distinct().size==it.size)}
    }catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
    private fun money(n:BigDecimal?)=n?.let {"¥"+DecimalFormat("#,##0.00",DecimalFormatSymbols.getInstance(Locale.ROOT)).apply {roundingMode=RoundingMode.HALF_UP}.format(it)}?:"--"
    fun ui(rows:List<SellTransaction>)=JsonArray(rows.sortedByDescending {it.createdAt}.map {t->buildJsonObject {
        WriteDiagnostics.ui(t.writeStage,t.writeProblem,t.writeStartedAt,t.recoveryAttempts,t.postMayHaveBeenSent,t.status,t.createdAt).forEach {(k,v)->put(k,v)}
        put("id",t.id);put("kind","SELL");put("autoSync",t.autoSync);put("type","卖出");put("direction","SELL");put("fundId",t.input.fund.uiId);put("fundCode",t.input.fund.fundCode);put("fundName",t.input.fund.fundName)
        put("dateKey",t.input.tradeDate.toString());put("dateLabel",t.input.tradeDate.toString());put("weekday","");put("time",t.createdAt.toString())
        put("amountInput",t.input.shares.toPlainString());put("primaryText",t.input.shares.toPlainString()+" 份");put("secondaryText","本地 SELL Transaction")
        put("feeRate",t.input.feeRate.movePointRight(2).toPlainString());put("feeInput",t.feeAmount?.toPlainString()?:"--")
        put("timePeriod",if(t.input.cutoffType==CutoffType.BEFORE_1500)"before"else "after");put("note",t.input.note)
        put("status",t.status.name);put("transactionStatus",t.status.name);put("postMayHaveBeenSent",t.postMayHaveBeenSent)
        put("navConfirmedAt",t.navConfirmedAt?.toString()?.let(::JsonPrimitive)?:JsonNull);put("confirmationDate",t.navConfirmedAt?.atZone(java.time.ZoneId.of("Asia/Shanghai"))?.toLocalDate()?.toString()?.let(::JsonPrimitive)?:JsonNull);put("expectedNavDate",t.expectedNavDate.toString());put("confirmedNavDate",t.confirmedNavDate?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("confirmedNav",t.confirmedNav?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
        put("confirmedShares",if(t.confirmedNav!=null)JsonPrimitive(t.input.shares.toPlainString())else JsonNull)
        val delta=if(t.status==SellStatus.SYNCED&&t.submittedTarget!=null)t.baseline.shares.subtract(t.submittedTarget.shares)else t.input.shares
        put("shareDelta",if(t.confirmedNav!=null)JsonPrimitive("-"+delta.toPlainString())else JsonNull)
        put("confirmedAmount",if(t.netAmount!=null)JsonPrimitive(money(t.netAmount))else JsonNull)
        put("grossAmount",t.grossAmount?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("netAmount",t.netAmount?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
        put("soldCost",t.soldCost?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("calculatedProfit",t.calculatedProfit?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
        put("costPolicy","KEEP_UNIT_COST");put("cashReceiptConfirmed",false)
        put("baselineShares",t.baseline.shares.toPlainString());put("baselineUnitCost",t.baseline.unitCost.toPlainString());put("baselineTotalCost",t.baseline.totalCost.toPlainString())
        put("createdShares",t.baseline.shares.toPlainString());put("createdCost",t.baseline.totalCost.toPlainString())
        put("currentShares",t.external?.shares?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("currentCost",t.external?.totalCost?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
        put("syncedAt",t.syncedAt?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("syncError",when(t.problem){
            BuyProblem.CALENDAR_UNAVAILABLE->"该基金或年份的交易日期规则尚未核验，暂不确认净值或同步持仓。"
            BuyProblem.AUTH_EXPIRED->"登录已失效，请重新登录。"
            BuyProblem.WRITE_PENDING->"写入结果尚在核对，仅重新读取，不会重复提交持仓。"
            BuyProblem.CATCHUP_REVIEW->"定投积压期次需确认，请查看当前基金的计划管理。"
            BuyProblem.RATE_LIMITED->"养基宝请求频繁，已暂停请求，请稍后重新读取核对。"
            BuyProblem.WRITE_UNVERIFIED->"养基宝写入配置或本地协议核对记录不可用，未发送写入请求。"
            BuyProblem.NAV_DATE_UNRESOLVED->"候选日期暂无正式净值，尚不能确认是否休市；继续等待。"
            BuyProblem.NAV_OUTSIDE_WINDOW->"候选日期超出养基宝历史净值返回范围，暂无法确认。"
            BuyProblem.VERIFY_FAILED->"写入后未验证到目标持仓，请先重新读取核对。"
            BuyProblem.INPUT->"卖出份额无法按已验证精度安全写入，请调整份额；未改变持仓成本或意外清仓。"
            null->""
            else->"请求或保存未完成，请重试。"
        })
    }})
}
