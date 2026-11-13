package com.ndxinvest.yjb.buy

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal

object BuyCodec {
    private fun JsonObject.s(key:String)=this[key]!!.jsonPrimitive.content
    private fun JsonObject.optional(key:String)=this[key]?.takeUnless {it is JsonNull}?.jsonPrimitive?.content
    private fun baseline(b:BuyBaseline)=buildJsonObject {put("exists",b.exists);put("shares",b.shares.toPlainString());put("unitCost",b.unitCost.toPlainString());put("totalCost",b.totalCost.toPlainString())}
    private fun parseBaseline(b:JsonObject)=BuyBaseline(b.s("shares").toBigDecimal(),b.s("unitCost").toBigDecimal(),b["exists"]?.jsonPrimitive?.booleanOrNull?:true)
    fun encode(rows:List<BuyTransaction>):JsonArray=JsonArray(rows.map {t->buildJsonObject {
        put("calculationVersion",t.calculationVersion);put("schemaVersion",1);put("autoSync",t.autoSync);put("id",t.id);put("type","BUY");put("accountId",t.input.fund.accountId);put("fundId",t.input.fund.fundId);put("fundCode",t.input.fund.fundCode);put("fundName",t.input.fund.fundName)
        put("amount",t.input.amount.toPlainString());put("feeRate",t.input.feeRate.toPlainString());put("tradeDate",t.input.tradeDate.toString());put("cutoffType",t.input.cutoffType.name);put("note",t.input.note)
        put("expectedNavDate",t.expectedNavDate.toString());put("status",t.status.name);put("baseline",baseline(t.baseline));t.target?.let {put("target",baseline(it))}
        t.submittedTarget?.let {put("submittedTarget",baseline(it))}
        t.writeStage?.let {put("writeStage",it.name)};t.writeProblem?.let {put("writeProblem",it.name)}
        t.writeStartedAt?.let {put("writeStartedAt",it.toString())};put("recoveryAttempts",JsonArray(t.recoveryAttempts.map {JsonPrimitive(it.toString())}))
        t.executionBaseline?.let {put("executionBaseline",baseline(it))};put("queuePredecessors",JsonArray(t.queuePredecessors.map(::JsonPrimitive)))
        t.originPlanId?.let {put("originPlanId",it)};t.scheduledDate?.let {put("scheduledDate",it.toString())}
        t.external?.let {put("external",baseline(it))};put("postMayHaveBeenSent",t.postMayHaveBeenSent);t.problem?.let {put("problem",it.name)}
        t.confirmedNavDate?.let {put("confirmedNavDate",it.toString())};t.confirmedNav?.let {put("confirmedNav",it.toPlainString())};t.confirmedShares?.let {put("confirmedShares",it.toPlainString())};t.feeAmount?.let {put("feeAmount",it.toPlainString())}
        t.navConfirmedAt?.let {put("navConfirmedAt",it.toString())};put("createdAt",t.createdAt.toString());put("updatedAt",t.updatedAt.toString());t.syncedAt?.let {put("syncedAt",it.toString())};put("calendarSource",t.calendarSource)
    }})
    fun decode(rows:JsonArray):List<BuyTransaction> = try {
        val parsed=rows.map {e->val o=e.jsonObject;require(o.s("schemaVersion")=="1"&&o.s("type")=="BUY");
            BuyTransaction(o.s("id"),BuyInput(BuyFund(o.s("accountId"),o.s("fundId"),o.s("fundCode"),o.s("fundName")),o.s("amount").toBigDecimal(),o.s("feeRate").toBigDecimal(),LocalDate.parse(o.s("tradeDate")),CutoffType.valueOf(o.s("cutoffType")),o.s("note")),
                parseBaseline(o["baseline"]!!.jsonObject),LocalDate.parse(o.s("expectedNavDate")),BuyStatus.valueOf(o.s("status")),o.optional("confirmedNavDate")?.let(LocalDate::parse),o.optional("confirmedNav")?.toBigDecimal(),o.optional("feeAmount")?.toBigDecimal(),o.optional("confirmedShares")?.toBigDecimal(),o["target"]?.jsonObject?.let(::parseBaseline),o.s("postMayHaveBeenSent").toBooleanStrict(),o["external"]?.jsonObject?.let(::parseBaseline),o.optional("problem")?.let(BuyProblem::valueOf),Instant.parse(o.s("createdAt")),Instant.parse(o.s("updatedAt")),o.optional("syncedAt")?.let(Instant::parse),o.s("calendarSource"),o["submittedTarget"]?.jsonObject?.let(::parseBaseline),o.optional("originPlanId"),o.optional("scheduledDate")?.let(LocalDate::parse),o.optional("navConfirmedAt")?.let(Instant::parse),o.optional("autoSync")?.toBooleanStrict()?:false,o.optional("calculationVersion")?.toInt()?:0,o["executionBaseline"]?.jsonObject?.let(::parseBaseline),o["queuePredecessors"]?.jsonArray?.map {it.jsonPrimitive.content}.orEmpty(),o.optional("writeStage")?.let(WriteStage::valueOf),o.optional("writeProblem")?.let(BuyProblem::valueOf),o.optional("writeStartedAt")?.let(Instant::parse),o["recoveryAttempts"]?.jsonArray?.map {Instant.parse(it.jsonPrimitive.content)}.orEmpty())
        };require(parsed.map {it.id}.distinct().size==parsed.size);parsed
    }catch(_:Exception){throw BuyFailure(BuyProblem.STORAGE)}
    fun ui(rows:List<BuyTransaction>):JsonArray=JsonArray(rows.sortedByDescending {it.createdAt}.map {t->buildJsonObject {
        WriteDiagnostics.ui(t.writeStage,t.writeProblem,t.writeStartedAt,t.recoveryAttempts,t.postMayHaveBeenSent,t.status,t.createdAt).forEach {(k,v)->put(k,v)}
        put("id",t.id);put("kind","BUY");put("autoSync",t.autoSync);put("type",if(t.originPlanId==null)"买入" else "定投");put("direction","BUY");put("fundId",t.input.fund.uiId);put("fundCode",t.input.fund.fundCode);put("fundName",t.input.fund.fundName)
        put("postMayHaveBeenSent",t.postMayHaveBeenSent)
        t.originPlanId?.let {put("originPlanId",it)};t.scheduledDate?.let {put("scheduledDate",it.toString())}
        put("dateKey",t.input.tradeDate.toString());put("dateLabel",t.input.tradeDate.toString());put("weekday","");put("time",t.createdAt.toString());put("primaryText","¥"+t.input.amount.toPlainString());put("secondaryText","本地 BUY Transaction");put("amountInput",t.input.amount.toPlainString())
        put("feeRate",t.input.feeRate.multiply(BigDecimal(100)).toPlainString());put("feeInput",t.feeAmount?.toPlainString()?:"--");put("timePeriod",if(t.input.cutoffType==CutoffType.BEFORE_1500)"before" else "after");put("note",t.input.note);put("transactionStatus",t.status.name);put("status",t.status.name)
        put("navConfirmedAt",t.navConfirmedAt?.toString()?.let(::JsonPrimitive)?:JsonNull);put("confirmationDate",t.navConfirmedAt?.atZone(java.time.ZoneId.of("Asia/Shanghai"))?.toLocalDate()?.toString()?.let(::JsonPrimitive)?:JsonNull);put("expectedNavDate",t.expectedNavDate.toString());put("confirmedNavDate",t.confirmedNavDate?.toString()?.let(::JsonPrimitive)?:JsonNull);put("confirmedNav",t.confirmedNav?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("confirmedShares",t.confirmedShares?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("syncedAt",t.syncedAt?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("baselineShares",t.baseline.shares.toPlainString());put("baselineUnitCost",t.baseline.unitCost.toPlainString());put("baselineTotalCost",t.baseline.totalCost.toPlainString())
        put("baseline",buildJsonObject {put("shares",t.baseline.shares.toPlainString());put("cost",t.baseline.totalCost.toPlainString())});t.external?.let {b->put("externalHolding",buildJsonObject {put("exists",b.exists);put("shares",b.shares.toPlainString());put("cost",b.totalCost.toPlainString())})}
        put("createdShares",t.baseline.shares.toPlainString());put("createdCost",t.baseline.totalCost.toPlainString());put("currentShares",t.external?.shares?.toPlainString()?.let(::JsonPrimitive)?:JsonNull);put("currentCost",t.external?.totalCost?.toPlainString()?.let(::JsonPrimitive)?:JsonNull)
        put("shareDelta",t.confirmedShares?.toPlainString()?.let {JsonPrimitive("+$it")}?:JsonNull)
        put("syncError",when(t.problem){BuyProblem.CALENDAR_UNAVAILABLE->"该基金或年份的交易日期规则尚未核验，暂不确认净值或同步持仓。";BuyProblem.WRITE_UNVERIFIED->"养基宝写入配置或本地协议核对记录不可用，未发送写入请求。";BuyProblem.AUTH_EXPIRED->"登录已失效，请重新登录。";BuyProblem.WRITE_PENDING->"写入结果尚在核对，仅重新读取，不会重复提交持仓。";BuyProblem.CATCHUP_REVIEW->"定投积压期次需确认，请查看当前基金的计划管理。";BuyProblem.RATE_LIMITED->"养基宝请求频繁，已暂停请求，请稍后重新读取核对。";BuyProblem.NAV_OUTSIDE_WINDOW->"候选日期超出养基宝历史净值返回范围，暂无法确认；未使用其它日期替代。";BuyProblem.NAV_DATE_UNRESOLVED->"候选日期暂无正式净值，尚不能确认是否休市；继续等待，不使用下一天净值替代。";BuyProblem.VERIFY_FAILED->"写入后未验证到目标持仓，请先重新读取核对。";null->"";else->"请求或保存未完成，请重试。"})
    }})
}
