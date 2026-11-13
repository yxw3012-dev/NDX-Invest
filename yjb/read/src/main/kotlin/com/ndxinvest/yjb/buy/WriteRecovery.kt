package com.ndxinvest.yjb.buy

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.*

/** No credentials or arbitrary target supplied by the browser. Frozen journal values only. */
data class WriteRecoveryQuote(val id:String,val fund:BuyFund,val baseline:BuyBaseline,val target:BuyBaseline,val attempts:Int)
object WriteDiagnostics {
    fun needsReview(sent:Boolean,status:BuyStatus,started:Instant?,attempts:List<Instant>,created:Instant,now:Instant)=
        sent&&status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)&&Duration.between(attempts.lastOrNull()?:started?:created,now)>=Duration.ofMinutes(15)
    fun ui(stage:WriteStage?,problem:BuyProblem?,started:Instant?,attempts:List<Instant>,sent:Boolean,status:BuyStatus,created:Instant)=buildJsonObject {
        put("writeStage",stage?.name?.let(::JsonPrimitive)?:JsonNull)
        put("writeFailure",problem?.name?.let(::JsonPrimitive)?:JsonNull)
        put("writeStartedAt",started?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("recoveryCount",attempts.size)
        put("writeReviewRequired",needsReview(sent,status,started,attempts,created,Instant.now()))
        put("writeDiagnosticText",when(stage){WriteStage.NOT_SENT->"发送前检查未通过，尚未提交持仓。";WriteStage.MAY_HAVE_SENT->"请求可能已发送，正在核对最终持仓。";WriteStage.ACKNOWLEDGED->"写入请求已返回成功，仍以持仓回读验证为准。";null->if(sent)"旧记录缺少发送阶段证据，不能确认首次失败原因。" else ""}+when(problem){BuyProblem.AUTH_EXPIRED->" 最近写入遇到登录失效。";BuyProblem.RATE_LIMITED->" 最近写入遇到请求限流。";BuyProblem.NETWORK->" 最近写入遇到网络异常。";BuyProblem.STORAGE->" 最近写入遇到本地保存失败。";BuyProblem.ACCOUNT_MISMATCH->" 最近写入遇到账户核对失败。";null->"";else->" 最近写入未通过检查（${problem.name}）。"})
    }
}
