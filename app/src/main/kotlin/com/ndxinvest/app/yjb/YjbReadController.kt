package com.ndxinvest.app.yjb

import android.webkit.JavascriptInterface
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import com.ndxinvest.yjb.read.*
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import java.time.LocalDate
import java.math.BigDecimal

/** Closed read, single trades and local plan management. Automatic DCA runs natively under the same gate. */
internal class YjbReadController(private val activity:ComponentActivity,private val render:(JsonObject)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val storage=LocalInitialPositions(activity)
    private val buyGateway=LocalPortfolio(activity)
    private val migration=LegacyPortfolioMigration(activity)
    private var readPayload:JsonObject?=null
    private var buyRows=emptyList<BuyTransaction>()
    private var sellRows=emptyList<SellTransaction>()
    private val commandGate=NativeTransactionGate.mutex
    private val buyStorage=AndroidBuyStorage(activity,local=true)
    private val sellStorage=AndroidSellStorage(activity,local=true)
    private val planStorage=AndroidDcaStorage(activity,local=true)
    private val creations=CreationRequests(AndroidCreationRequests(activity,local=true))
    private var planState=DcaState()
    private val localFunds=AndroidLocalFunds(activity,local=true)
    private var localFundRows=JsonArray(emptyList())
    private var searchedFunds=emptyMap<String,JsonObject>()
    private val localNavs=mutableMapOf<String,OfficialBuyNav>()
    private val additions=FundAddition(localFunds,buyGateway,localWrites=true)
    private val buyRepository=SingleBuyRepository(buyStorage,buyGateway,BundledBuyCalendar::forCode,emit={rows->buyRows=rows;publish()},pendingOther={fund->sellStorage.read().any {it.input.fund.accountId==fund.accountId&&it.input.fund.fundId==fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)}})
    private val sellRepository=SingleSellRepository(sellStorage,buyGateway,emit={rows->sellRows=rows;publish()},pendingOther={fund->buyStorage.read().any {it.input.fund.accountId==fund.accountId&&it.input.fund.fundId==fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}})
    private val plans=DcaRepository(planStorage,buyStorage,buyRepository,emit={s->planState=s;publish()},rejectCollision={id->if(sellStorage.read().any {it.id==id})throw DcaFailure(DcaProblem.STORAGE)})
    private val undo=TransactionUndo(buyStorage,sellStorage,AndroidUndoStorage(activity,local=true),buyGateway)
    private val calendarEngine=DcaNavEngine(planStorage,buyStorage,plans,buyRepository,buyGateway,pendingSell=undo::blocked)
    private val tradeQueue=OrdinaryTradeAutomation(buyStorage,sellStorage,buyRepository,sellRepository,calendarEngine::reservedBefore,undo::blocked)
    private suspend fun syncQueued(id:String) {
        val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}?.toSet().orEmpty()
        tradeQueue.run(accounts,onlyId=id)
    }
    /** Read first, then explicit native confirmation; background automation never calls this. */
    private suspend fun recoverWriteWithinGate(id:String) {
        val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}?.toSet().orEmpty()
        val quote=tradeQueue.reviewRecovery(accounts,id)?:return
        val confirmed=suspendCancellableCoroutine<Boolean> { continuation->
            val prompt=AlertDialog.Builder(activity).setTitle("恢复原交易写入")
                .setMessage("${quote.fund.fundName}\n\n本地当前持仓仍与原快照一致。\n本次恢复的最终份额：${quote.target.shares.toPlainString()}\n最终单位成本：${quote.target.unitCost.toPlainString()}\n\n旧请求结果仍未确定。本次只重新写入原冻结目标，不新增交易。若曾在其它端修改持仓，请取消并核对。")
                .setNegativeButton("取消") {_,_->if(continuation.isActive)continuation.resume(false)}
                .setPositiveButton("确认恢复") {_,_->if(continuation.isActive)continuation.resume(true)}.create()
            prompt.setOnDismissListener {if(continuation.isActive)continuation.resume(false)}
            continuation.invokeOnCancellation {activity.runOnUiThread {prompt.dismiss()}}
            prompt.show()
        }
        if(confirmed)tradeQueue.recoverConfirmed(accounts,quote)
    }
    private var refreshBatchActive=false
    private var refreshCycle:JsonObject?=null
    private val repository=YjbReadRepository(null,storage,localSnapshot=buyGateway::snapshot,excludedImports={buyStorage.read().filter {!it.baseline.exists&&it.postMayHaveBeenSent}.map {it.input.fund.uiId}.toSet()}) { payload ->
        readPayload=payload
        if(!refreshBatchActive)scope.launch {if(!refreshBatchActive)publish()}
    }
    @Volatile private var booted=false
    private var statisticsKey:String?=null
    private var statistics:JsonObject?=null
    private val statisticsMutex=kotlinx.coroutines.sync.Mutex()
    private var publication=0L
    init {
        scope.launch {
            NativeDcaAutomation.changes.collect {
                commandGate.withLock {
                    try {buyRows=buyStorage.read();sellRows=sellStorage.read();planState=planStorage.read();repository.refresh();publish()}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){render(buildJsonObject {put("localError","自动定投状态暂无法安全读取，请稍后重试。")})}
                }
            }
        }
        scope.launch {
            while(isActive){delay(60_000);if(booted)NativeDcaAutomation.run(activity.applicationContext)}
        }
    }
    private suspend fun publish(result:JsonObject?=null) {
        val payload=readPayload?:return
        val revision=++publication
        val intent=try {creations.pending()}catch(e:CancellationException){throw e}catch(_:Exception){render(buildJsonObject {put("localError","提交结果暂无法安全读取，请勿重复提交。")});return}
        val delivered=if(result?.get("requestId")!=null&&intent?.get("requestId")==result["requestId"])settleCreation(result)else result
        val connected=payload["accountStatus"]?.jsonPrimitive?.content !in setOf("NOT_CONNECTED","CONNECTING","LOGIN_FAILED")
        val snapshot=if(connected)buyGateway.latestSnapshot else null
        val accounts=snapshot?.get("groups")?.jsonArray?.mapNotNull {it.jsonObject["accountId"]?.jsonPrimitive?.content}?.toSet().orEmpty()
        val imports=payload["initialPositions"] as? JsonArray?:JsonArray(emptyList())
        val sourceData=payload["data"]!!.jsonObject
        additions.reconcile(sourceData)
        localFundRows=additions.rows()
        val displayData=LocalFundProjection.merge(sourceData,localFundRows,accounts,localNavs)
        val visibleBuys=buyRows.filter {it.input.fund.accountId in accounts}
        val visibleSells=sellRows.filter {it.input.fund.accountId in accounts}
        // Compute off the UI thread and memoize unchanged projections; no network/transaction commands.
            val statisticsSource=snapshot?.takeIf {com.ndxinvest.yjb.statistics.PersonalStatistics.matchesSnapshot(it,payload["data"]?.jsonObject?.get("lastSyncAt")?.jsonPrimitive?.contentOrNull)}
        val localStatistics=if(statisticsSource!=null)withContext(Dispatchers.Default) {statisticsMutex.withLock {
            val key=statisticsSource.toString()+imports.toString()+BuyCodec.encode(visibleBuys).toString()+SellCodec.encode(visibleSells).toString()+LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            if(key!=statisticsKey){statistics=runCatching {com.ndxinvest.yjb.statistics.PersonalStatistics.calculate(statisticsSource,imports,visibleBuys,visibleSells)}.getOrNull();statisticsKey=key}
            statistics
        }}else null
        if(revision!=publication||readPayload!==payload)return
        val deletedIds=undo.deletedIds(accounts).toSet()
        val transactions=(BuyCodec.ui(visibleBuys)+SellCodec.ui(visibleSells)).filter {it.jsonObject["id"]?.jsonPrimitive?.content !in deletedIds}
        // Notifications only observe persisted states. Historical rows are seeded without alerts.
        val reminderEvents=com.ndxinvest.app.notifications.NdxReminders.events(visibleBuys,visibleSells)
        withContext(Dispatchers.IO) {
            com.ndxinvest.app.notifications.NdxReminders.observeTransactions(activity,reminderEvents)
            com.ndxinvest.app.notifications.NdxReminders.observeRead(activity,payload["accountStatus"]?.jsonPrimitive?.content,payload["holdingsStatus"]?.jsonPrimitive?.content)
        }
        val recovery=try {creations.pending()}catch(e:CancellationException){throw e}catch(_:Exception){render(buildJsonObject {put("localError","提交结果暂无法安全读取，请勿重复提交。")});return}
        if(revision!=publication||readPayload!==payload)return
        render(buildJsonObject {payload.forEach {(k,v)->put(k,v)};put("data",displayData);put("localFundIntents",JsonArray(localFundRows.filter {it.jsonObject["accountId"]?.jsonPrimitive?.content in accounts&&it.jsonObject["kind"]?.jsonPrimitive?.content=="ADDING"}));if(payload["holdingsStatus"]?.jsonPrimitive?.content=="EMPTY"&&displayData["holdings"]!!.jsonArray.isNotEmpty())put("holdingsStatus","READY");localStatistics?.let {put("localStatistics",it)};refreshCycle?.let {put("refreshCycle",it)};put("plans",DcaCodec.ui(planState,accounts,java.time.Instant.now()));put("transactions",JsonArray(transactions.sortedByDescending {it.jsonObject["time"]!!.jsonPrimitive.content}));recovery?.let {put("creationRecovery",it)};delivered?.let {if(it["contributionResult"]!=null)put("contributionResult",it["contributionResult"]!!)else put("buyResult",it)}})
    }
    /** Called under the transaction gate: an old create cannot still be saving a row. */
    private suspend fun settleCreation(result:JsonObject):JsonObject {
        val id=result["requestId"]!!.jsonPrimitive.content
        val intent=creations.pending()?:return result
        try {
            val saved=when(intent["prefix"]!!.jsonPrimitive.content) {
                "buy-create:"->buyStorage.read().any {it.id==id}
                "sell-create:"->sellStorage.read().any {it.id==id}
                "plan-action:"->planStorage.read().plans.any {it.id==id}
                else->throw BuyFailure(BuyProblem.STORAGE)
            }
            val settled=buildJsonObject {
                result.forEach {(k,v)->put(k,v)};put("ok",saved)
                if(saved){put("transactionId",id);if(result["ok"]?.jsonPrimitive?.boolean!=true)put("message","记录已保存，请在记录或计划中核对状态；不要重复创建。")}
            }
            return creations.complete(id,settled)
        }catch(e:CancellationException){throw e}
        catch(_:Exception){return buildJsonObject {put("requestId",id);put("ok",false);put("creationUncertain",true);put("message","保存结果暂无法安全核对，请稍后重试核对，不要重复提交。")}}
    }
    @JavascriptInterface fun prepareCreate(text:String):String=try {
        if(!booted || text.length>7500)throw BuyFailure(BuyProblem.INPUT)
        val request=Json.parseToJsonElement(text).jsonObject
        runBlocking {creations.prepare(request["prefix"]!!.jsonPrimitive.content,request["body"]!!.jsonObject)}.toString()
    }catch(_:Exception){buildJsonObject {put("message","前一笔提交仍在核对，或请求无法安全保存，请稍后核对。")}.toString()}
    @JavascriptInterface fun acknowledgeCreate(id:String):Boolean=try {
        require(booted && Regex("[0-9a-fA-F-]{36}").matches(id))
        runBlocking {creations.acknowledge(id)};true
    }catch(_:Exception){false}
    private suspend fun recoverCreation() {
        val intent=creations.pending()?:return
        if(intent["result"]==null)settleCreation(buildJsonObject {put("requestId",intent["requestId"]!!);put("ok",false);put("message","上一笔请求尚未生成记录，可以重新确认提交。")})
        publish()
    }
    private fun readError():String {
        val payload=readPayload?:return "持仓读取未完成"
        val status=payload["holdingsStatus"]?.jsonPrimitive?.content
        val error=payload["errorMessage"]?.jsonPrimitive?.content.orEmpty()
        return if(status in setOf("READY","EMPTY"))error else when(status) {
            "NOT_CONNECTED"->"本地持仓尚未打开"
            else->"持仓读取失败，已保留可用数据"
        }
    }
    private suspend fun refreshAll() {
        refreshBatchActive=true
        refreshCycle=buildJsonObject {put("status","RUNNING");put("errorMessage","")}
        publish()
        try {
            val result=RefreshBatch.run(
                read={repository.refresh();readError()},
                check={NativeDcaAutomation.checkWithinGate(activity.applicationContext,notify=false)},
                finalRead={changed->
                    buyRows=buyStorage.read();sellRows=sellStorage.read();planState=planStorage.read()
                    refreshLocalNavs();if(changed){repository.refresh();readError()}else ""
                },
            )
            refreshCycle=buildJsonObject {put("status",if(result.passed)"SUCCESS" else "FAILURE");put("errorMessage",result.errors.joinToString("；"))}
        }finally {refreshBatchActive=false;publish()}
    }
    private suspend fun visibleTransaction(id:String):BuyTransaction {
        val t=buyStorage.read().singleOrNull {it.id==id}?:throw BuyFailure(BuyProblem.INPUT)
        val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]?.jsonPrimitive?.content}.orEmpty()
        if(t.input.fund.accountId !in accounts)throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
        return t
    }
    private fun amount(o:JsonObject,k:String):BigDecimal {
        val s=o[k]?.jsonPrimitive?.content?:throw BuyFailure(BuyProblem.INPUT)
        if(s.length>128||!Regex("[0-9]+(?:\\.[0-9]+)?").matches(s))throw BuyFailure(BuyProblem.INPUT)
        return s.toBigDecimal()
    }
    private fun cutoff(o:JsonObject)=when(o["timePeriod"]?.jsonPrimitive?.content){"before"->CutoffType.BEFORE_1500;"after"->CutoffType.AFTER_1500;else->throw BuyFailure(BuyProblem.INPUT)}
    private fun mappedFund(uiId:String):BuyFund {
        val data=readPayload?.get("data")?.jsonObject?:throw BuyFailure(BuyProblem.INPUT)
        val mapped=(data["fundCatalog"]?:data["holdings"])!!.jsonArray.singleOrNull {it.jsonObject["id"]?.jsonPrimitive?.content==uiId}?.jsonObject?:localFundRows.singleOrNull {it.jsonObject["id"]?.jsonPrimitive?.content==uiId&&it.jsonObject["kind"]?.jsonPrimitive?.content=="PLACEHOLDER"}?.jsonObject?:throw BuyFailure(BuyProblem.INPUT)
        val parts=uiId.split(':');if(parts.size!=3||parts[0]!="yjb")throw BuyFailure(BuyProblem.INPUT)
        return BuyFund(parts[1],parts[2],mapped["fundCode"]!!.jsonPrimitive.content,mapped["name"]!!.jsonPrimitive.content)
    }
    private fun planConfig(o:JsonObject,note:String?=null):DcaConfig {
        val frequency=DcaFrequency.valueOf(o["frequency"]!!.jsonPrimitive.content.uppercase())
        return DcaConfig(amount(o,"amountInput"),amount(o,"buyRateInput").movePointLeft(2),frequency,
            if(frequency in setOf(DcaFrequency.WEEKLY,DcaFrequency.BIWEEKLY))o["weekday"]!!.jsonPrimitive.int else 1,
            if(frequency==DcaFrequency.MONTHLY)o["monthDay"]!!.jsonPrimitive.int else 1,note?:o["note"]?.jsonPrimitive?.content?:"")
    }
    private suspend fun processPlan(action:String):Boolean {
        var requestId=""
        try {
            val request=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
            requestId=request["requestId"]!!.jsonPrimitive.content
            if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw DcaFailure(DcaProblem.INPUT)
            val op=request["action"]!!.jsonPrimitive.content
            if(op=="create") {
                if(!creations.accepts("plan-action:",request))throw BuyFailure(BuyProblem.INPUT)
                val fund=mappedFund(request["fundId"]!!.jsonPrimitive.content)
                if(localFundRows.any {it.jsonObject["id"]?.jsonPrimitive?.content==fund.uiId&&it.jsonObject["kind"]?.jsonPrimitive?.content=="PLACEHOLDER"})throw BuyFailure(BuyProblem.INPUT)
                plans.create(fund,planConfig(request),request["enabled"]!!.jsonPrimitive.boolean,requestId)
            }else {
                val id=request["planId"]!!.jsonPrimitive.content
                val p=planStorage.read().plans.singleOrNull {it.id==id&&!it.deleted}?:throw DcaFailure(DcaProblem.INPUT)
                val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}.orEmpty()
                if(p.fund.accountId !in accounts||request["fundId"]!!.jsonPrimitive.content!=p.fund.uiId)throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
                val revision=request["revision"]!!.jsonPrimitive.long
                when(op) {
                    "update"->plans.update(id,planConfig(request,p.config.note),revision)
                    "enabled"->plans.setEnabled(id,request["enabled"]!!.jsonPrimitive.boolean,revision)
                    "delete"->plans.delete(id,revision)
                    "catchup-approve","catchup-skip"->plans.resolveCatchupReview(id,revision,request["reviewId"]!!.jsonPrimitive.content,op=="catchup-approve",if(op=="catchup-approve")buyGateway.current(p.fund)else null)
                    else->throw DcaFailure(DcaProblem.INPUT)
                }
            }
            publish(buildJsonObject {put("requestId",requestId);put("ok",true);put("message",when(op){"create"->"计划已保存，正式净值公布后自动记账并更新本地持仓。";"update"->"计划修改已保存。";"enabled"->if(request["enabled"]!!.jsonPrimitive.boolean)"计划已恢复，等待正式净值后自动同步。"else "计划已暂停。";"catchup-approve"->"本批次已确认，将逐笔核对并同步。";"catchup-skip"->"已跳过本批次，不生成买入记录。";else->"计划已删除，已有交易保留。"})})
            return op=="catchup-approve"
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            val problem=(e as? BuyFailure)?.problem
            if(problem==BuyProblem.AUTH_EXPIRED)repository.refresh()
            val text=when((e as? DcaFailure)?.problem){DcaProblem.STALE->"计划已更新，请核对最新状态后重试。";DcaProblem.NOT_DUE->"本期尚未到计划日期，未创建交易。";DcaProblem.PAUSED->"计划已暂停，请先恢复。";DcaProblem.PENDING_CREATION->"本期创建记录尚未完成，暂不能修改；可以删除计划，已有交易保留。";DcaProblem.STORAGE->"本地计划无法安全保存或恢复，请重试。";else->if(problem!=null)message(problem)else "请检查计划金额、费率和执行日。"}
            publish(buildJsonObject {put("requestId",requestId);put("ok",false);put("message",text)})
            return false
        }
    }
    private suspend fun processBuy(action:String) {
        var requestId=""
        try {
            val request=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
            requestId=request["requestId"]!!.jsonPrimitive.content
            if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw BuyFailure(BuyProblem.INPUT)
            val t=if(action.startsWith("buy-create:")) {
                if(!creations.accepts("buy-create:",request))throw BuyFailure(BuyProblem.INPUT)
                if(sellStorage.read().any {it.id==requestId})throw BuyFailure(BuyProblem.INPUT)
                val uiId=request["fundId"]!!.jsonPrimitive.content
                val fund=mappedFund(uiId)
                if(undo.blocked(fund))throw UndoFailure("该基金撤销尚未完成，请先核对撤销结果")
                buyRepository.create(BuyInput(fund,amount(request,"amountInput"),amount(request,"buyRateInput").movePointLeft(2),LocalDate.parse(request["date"]!!.jsonPrimitive.content),cutoff(request),request["note"]?.jsonPrimitive?.content?:""),requestId,autoSync=true)
            }else {
                val id=request["id"]!!.jsonPrimitive.content;visibleTransaction(id)
                if(undo.blocked(buyStorage.read().single {it.id==id}.input.fund))throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
                when(request["action"]!!.jsonPrimitive.content) {
                    "check-nav"->buyRepository.confirmNav(id)
                    "sync"->{syncQueued(id);buyStorage.read().single {it.id==id}}
                    "recover-write"->{recoverWriteWithinGate(id);buyStorage.read().single {it.id==id}}
                    "cancel"->buyRepository.cancel(id)
                    "edit"->{val d=request["draft"]!!.jsonObject;buyRepository.edit(id,amount(d,"amountInput"),amount(d,"feeRate").movePointLeft(2),LocalDate.parse(d["dateKey"]!!.jsonPrimitive.content),cutoff(d),d["note"]?.jsonPrimitive?.content?:"")}
                    else->throw BuyFailure(BuyProblem.INPUT)
                }
            }
            if(t.problem==BuyProblem.AUTH_EXPIRED)repository.refresh()
            if(t.status==BuyStatus.SYNCED)repository.refresh() // GET platform values; journal never drives holdings.
            publish(buildJsonObject {put("requestId",requestId);put("ok",t.problem==null);put("transactionId",t.id);put("status",t.status.name);put("message",if(t.problem==null)when(t.status){BuyStatus.WAITING_NAV->"已保存本地买入，等待目标日期正式净值。";BuyStatus.READY_TO_SYNC->"正式净值已确认，待更新本地持仓。";BuyStatus.SYNCED->"已记账，持仓已更新。";BuyStatus.EXTERNAL_CHANGED->"持仓已发生变化，未继续写入。";BuyStatus.CANCELLED->"交易已撤销。";else->"状态已更新。"}else message(t.problem))})
            // Acknowledge durable creation first, then one foreground lookup. A NAV/network
            // failure must not make a successfully saved creation look like an unsaved draft.
            if(action.startsWith("buy-create:")&&t.status==BuyStatus.WAITING_NAV){
                val checked=buyRepository.confirmNav(t.id)
                if(checked.problem==BuyProblem.AUTH_EXPIRED)repository.refresh()
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){val problem=(e as? BuyFailure)?.problem?:BuyProblem.INPUT;if(problem==BuyProblem.AUTH_EXPIRED)repository.refresh();publish(buildJsonObject {put("requestId",requestId);put("ok",false);put("message",message(problem))})}
    }
    private suspend fun processSell(action:String) {
        var requestId=""
        try {
            val request=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
            requestId=request["requestId"]!!.jsonPrimitive.content
            if(!Regex("[0-9a-fA-F-]{36}").matches(requestId))throw BuyFailure(BuyProblem.INPUT)
            val t=if(action.startsWith("sell-create:")) {
                if(!creations.accepts("sell-create:",request))throw BuyFailure(BuyProblem.INPUT)
                if(buyStorage.read().any {it.id==requestId})throw BuyFailure(BuyProblem.INPUT)
                val uiId=request["fundId"]!!.jsonPrimitive.content
                val mapped=readPayload?.get("data")?.jsonObject?.get("holdings")?.jsonArray?.singleOrNull {it.jsonObject["id"]?.jsonPrimitive?.content==uiId}?.jsonObject?:throw BuyFailure(BuyProblem.INPUT)
                val parts=uiId.split(':');if(parts.size!=3||parts[0]!="yjb")throw BuyFailure(BuyProblem.INPUT)
                val fund=BuyFund(parts[1],parts[2],mapped["fundCode"]!!.jsonPrimitive.content,mapped["name"]!!.jsonPrimitive.content)
                if(undo.blocked(fund))throw UndoFailure("该基金撤销尚未完成，请先核对撤销结果")
                sellRepository.create(SellInput(fund,amount(request,"sharesInput"),amount(request,"sellRateInput").movePointLeft(2),LocalDate.parse(request["date"]!!.jsonPrimitive.content),cutoff(request),request["note"]?.jsonPrimitive?.content?:""),requestId,autoSync=true)
            }else {
                val id=request["id"]!!.jsonPrimitive.content
                val saved=sellStorage.read().singleOrNull {it.id==id}?:throw BuyFailure(BuyProblem.INPUT)
                val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}.orEmpty()
                if(saved.input.fund.accountId !in accounts)throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
                if(undo.blocked(saved.input.fund))throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
                when(request["action"]!!.jsonPrimitive.content) {
                    "check-nav"->sellRepository.confirmNav(id)
                    "sync"->{syncQueued(id);sellStorage.read().single {it.id==id}}
                    "recover-write"->{recoverWriteWithinGate(id);sellStorage.read().single {it.id==id}}
                    "cancel"->sellRepository.cancel(id)
                    "edit"->{val d=request["draft"]!!.jsonObject;sellRepository.edit(id,amount(d,"amountInput"),amount(d,"feeRate").movePointLeft(2),LocalDate.parse(d["dateKey"]!!.jsonPrimitive.content),cutoff(d),d["note"]?.jsonPrimitive?.content?:"")}
                    else->throw BuyFailure(BuyProblem.INPUT)
                }
            }
            if(t.problem==BuyProblem.AUTH_EXPIRED||t.status==SellStatus.SYNCED)repository.refresh()
            publish(buildJsonObject {put("requestId",requestId);put("ok",t.problem==null);put("transactionId",t.id);put("status",t.status.name)
                put("message",if(t.problem==null)when(t.status){SellStatus.WAITING_NAV->"已保存本地卖出，等待正式净值。";SellStatus.READY_TO_SYNC->"正式净值已确认，待更新本地持仓。";SellStatus.SYNCED->"已记账，剩余持仓已更新。";SellStatus.EXTERNAL_CHANGED->"持仓已发生变化，未继续写入。";SellStatus.CANCELLED->"交易已撤销。";else->"状态已更新。"}else message(t.problem))})
            if(action.startsWith("sell-create:")&&t.status==SellStatus.WAITING_NAV) {
                val checked=sellRepository.confirmNav(t.id)
                if(checked.problem==BuyProblem.AUTH_EXPIRED)repository.refresh()
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){val problem=(e as? BuyFailure)?.problem?:BuyProblem.INPUT;if(problem==BuyProblem.AUTH_EXPIRED)repository.refresh();publish(buildJsonObject {put("requestId",requestId);put("ok",false);put("message",if(problem==BuyProblem.INPUT)"请检查卖出份额和费率；份额不能超过当前本地持仓，也不能因精度处理意外清仓或改变单位成本。"else message(problem))})}
    }
    private fun message(problem:BuyProblem?)=when(problem){
        BuyProblem.CALENDAR_UNAVAILABLE->"该基金或年份的交易日期规则尚未核验，暂不确认净值或同步持仓。"
        BuyProblem.WRITE_UNVERIFIED->"暂时无法保存持仓。"
        BuyProblem.NAV_OUTSIDE_WINDOW->"目标日期超出已验证的历史净值查询范围，未使用其它日期替代。"
        BuyProblem.NAV_DATE_UNRESOLVED->"候选日期暂无正式净值，尚不能确认是否休市，继续等待。"
        BuyProblem.AUTH_EXPIRED->"登录已失效，请重新登录。"
        BuyProblem.WRITE_PENDING->"写入结果尚在核对，仅重新读取，不会重复提交持仓。"
        BuyProblem.CATCHUP_REVIEW->"定投积压期次需确认，请查看当前基金的计划管理。"
        BuyProblem.RATE_LIMITED->"净值请求频繁，已暂停请求，请稍后重新读取核对。"
        BuyProblem.STORAGE->"本地交易未能安全保存，请重试。"
        BuyProblem.ACCOUNT_MISMATCH->"当前账户或基金与交易不匹配，请重新同步核对。"
        BuyProblem.INPUT->"请检查金额、费率、基金和日期。"
        else->"操作未完成，请重新读取并核对交易状态。"
    }
    private suspend fun deleteTransaction(action:String) {
        var requestId=""
        try {
            val request=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
            requestId=request["requestId"]!!.jsonPrimitive.content
            require(Regex("[0-9a-fA-F-]{36}").matches(requestId))
            val id=request["id"]!!.jsonPrimitive.content
            val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}?.toSet().orEmpty()
            var done=undo.reconcile(accounts,id)
            if(!done){
                val quote=undo.quote(accounts,id)
                val confirmed=suspendCancellableCoroutine<Boolean> {c->
                    val message=if(quote.restore==null)"撤销此本地记录并从列表删除。未写入的交易不会继续执行，不修改本地持仓。" else
                        "${quote.fund.fundName}\n\n当前份额：${quote.before!!.shares.toPlainString()}\n当前单位成本：${quote.before!!.unitCost.toPlainString()}\n\n回退后份额：${quote.restore!!.shares.toPlainString()}\n回退后单位成本：${quote.restore!!.unitCost.toPlainString()}\n\n将修改本地持仓记账；不会撤销实际购买平台的申购。回读验证成功后删除记录。保留后续 ${quote.pendingBuys.size} 笔未发送买入，并更新其计算基准；金额、日期、费率不变。首次建仓回退后保留零份额基金。"
                    val prompt=AlertDialog.Builder(activity).setTitle("撤销并删除记录").setMessage(message)
                        .setNegativeButton("取消"){_,_->if(c.isActive)c.resume(false)}
                        .setPositiveButton("确认撤销"){_,_->if(c.isActive)c.resume(true)}.create()
                    prompt.setOnDismissListener {if(c.isActive)c.resume(false)}
                    c.invokeOnCancellation {activity.runOnUiThread {prompt.dismiss()}};prompt.show()
                }
                if(confirmed)done=undo.confirm(accounts,quote)
            }
            buyRows=buyStorage.read();sellRows=sellStorage.read()
            if(done)repository.refresh()
            publish(buildJsonObject {put("requestId",requestId);put("ok",done);put("undoDeleted",done);put("message",if(done)"已撤销并删除记录"else "未删除记录；若已提交回退，请重新核对撤销结果，勿重复交易")})
        }catch(e:CancellationException){throw e}
        catch(e:Exception){publish(buildJsonObject {put("requestId",requestId);put("ok",false);put("message",(e as? UndoFailure)?.reason?:"撤销未完成，请核对记录和持仓；未删除原记录")})}
    }
    @JavascriptInterface fun post(action:String) {
        if(!booted&&action !in setOf("boot","refresh"))return
        if(action.startsWith("record-delete:")&&action.length<=8192){scope.launch {commandGate.withLock {deleteTransaction(action)}};return}
        if(action.startsWith("initial-contributions:")) {
            if(action.length>1_048_576)return
            scope.launch {commandGate.withLock {
                var id="";var requestId=""
                try {
                    val request=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
                    id=request["id"]!!.jsonPrimitive.content;requestId=request["requestId"]!!.jsonPrimitive.content
                    require(Regex("[0-9a-fA-F-]{36}").matches(requestId))
                    repository.editInitialContributions(id,request["entries"]!!.jsonArray)
                    publish(buildJsonObject {put("contributionResult",buildJsonObject {put("id",id);put("requestId",requestId);put("ok",true);put("message","")})})
                }catch(e:CancellationException){throw e}
                catch(_:Exception){render(buildJsonObject {put("contributionResult",buildJsonObject {put("id",id);put("requestId",requestId);put("ok",false);put("message","明细保存失败，请检查日期、金额或稍后重试。")})})}
            }};return
        }
        if(action.length>8192)return
        if(action.startsWith("fund-search:")||action.startsWith("fund-add:")||action.startsWith("fund-remove:")){scope.launch {commandGate.withLock {processFundAddition(action)}};return}
        if(action=="recover-create"||action.startsWith("ack-create:")) {
            scope.launch {commandGate.withLock {try{if(action=="recover-create")recoverCreation()else creations.acknowledge(action.removePrefix("ack-create:"))}catch(_:Exception){render(buildJsonObject {put("localError","提交结果暂无法核对，请稍后重试。")})}}};return
        }
        if(action.startsWith("plan-action:")){scope.launch {val resume=commandGate.withLock {processPlan(action)};if(resume)NativeDcaAutomation.run(activity.applicationContext)};return}
        if(action.startsWith("buy-create:")||action.startsWith("buy-action:")) {
            scope.launch {commandGate.withLock {processBuy(action)};if(action.startsWith("buy-create:"))NativeDcaAutomation.run(activity.applicationContext)};return
        }
        if(action.startsWith("sell-create:")||action.startsWith("sell-action:")) {
            scope.launch {commandGate.withLock {processSell(action)};if(action.startsWith("sell-create:"))NativeDcaAutomation.run(activity.applicationContext)};return
        }
        if(action.startsWith("holding-start:")) {
            scope.launch {commandGate.withLock {
                try{val request=kotlinx.serialization.json.Json.parseToJsonElement(action.removePrefix("holding-start:")).jsonObject
                    repository.editHoldingStartDate(request["id"]!!.jsonPrimitive.content,request["date"]!!.jsonPrimitive.content)
                }catch(_:Exception){render(buildJsonObject {put("localError","日期保存失败，请检查日期或稍后重试。")})}
            }};return
        }
        if(action !in setOf("boot","refresh"))return
        scope.launch {commandGate.withLock {
            when(action) {
                "boot"->if(!booted){
                    try{migration.run(buyGateway)}catch(e:CancellationException){throw e}catch(e:Exception){
                        val message=e.message?.takeIf {it.startsWith("旧")} ?: "旧数据迁移未完成，原数据已保留。"
                        android.util.Log.w("NdxMigration",message)
                        render(buildJsonObject {put("accountStatus","CONNECTED");put("holdingsStatus","ERROR");put("errorMessage","暂时无法打开本地持仓，请重试。")})
                        return@withLock
                    };booted=true;try{buyRows=buyRepository.restore();sellRows=sellRepository.restore()}catch(_:BuyFailure){render(buildJsonObject {put("localError","本地交易历史无法读取，已阻止新交易保存。")})};try{planState=plans.restore()}catch(_:Exception){render(buildJsonObject {put("localError","本地定投计划无法恢复，未创建任何交易。")})};repository.restore();refreshLocalNavs();publish()}
                "refresh"->if(booted)refreshAll() else post("boot")
            }
            if(action=="boot")try{recoverCreation()}catch(e:CancellationException){throw e}catch(_:Exception){render(buildJsonObject {put("localError","上一笔提交结果暂无法安全核对，请勿重复提交。")})}
        };if(action=="boot")NativeDcaAutomation.run(activity.applicationContext)}
    }
    private suspend fun refreshLocalNavs() {
        val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {it.jsonObject["accountId"]!!.jsonPrimitive.content}.orEmpty()
        for(e in localFunds.read()) {
            val r=e.jsonObject
            if(r["kind"]?.jsonPrimitive?.content!="PLACEHOLDER"||r["accountId"]!!.jsonPrimitive.content !in accounts)continue
            val f=BuyFund(r["accountId"]!!.jsonPrimitive.content,r["fundId"]!!.jsonPrimitive.content,r["fundCode"]!!.jsonPrimitive.content,r["name"]!!.jsonPrimitive.content)
            try {buyGateway.officialHistory(f).maxByOrNull {it.date}?.let {localNavs[f.fundId]=it;additions.rememberNav(f.fundId,it)}}catch(e:CancellationException){throw e}catch(_:Exception){}
        }
    }
    private suspend fun processFundAddition(action:String) {
        var requestId=""
        try {
            val o=Json.parseToJsonElement(action.substringAfter(':')).jsonObject
            requestId=o["requestId"]!!.jsonPrimitive.content
            require(Regex("[0-9a-fA-F-]{36}").matches(requestId))
            if(action.startsWith("fund-search:")) {
                val rows=buyGateway.search(o["keyword"]!!.jsonPrimitive.content)
                searchedFunds=rows.associate {it.jsonObject["fundId"]!!.jsonPrimitive.content to it.jsonObject}
                val accounts=buyGateway.latestSnapshot?.get("groups")?.jsonArray?.map {g->buildJsonObject {put("id",g.jsonObject["accountId"]!!);put("name",g.jsonObject["accountTitle"]?:JsonPrimitive("本地账户"))}}.orEmpty()
                render(buildJsonObject {put("fundAddResult",buildJsonObject {put("requestId",requestId);put("action","search");put("ok",true);put("rows",rows);put("accounts",JsonArray(accounts))})});return
            }
            if(action.startsWith("fund-remove:")) {
                val id=o["id"]!!.jsonPrimitive.content;val f=mappedFund(id)
                val pending=buyStorage.read().any {it.input.fund.uiId==id&&it.status !in setOf(BuyStatus.CANCELLED,BuyStatus.SYNCED)}||sellStorage.read().any {it.input.fund.uiId==id&&it.status !in setOf(SellStatus.CANCELLED,SellStatus.SYNCED)}
                if(buyGateway.current(f).exists)throw BuyFailure(BuyProblem.EXTERNAL_CHANGED)
                additions.remove(id,pending)
            }else {
                val r=searchedFunds[o["fundId"]!!.jsonPrimitive.content]?:throw BuyFailure(BuyProblem.INPUT)
                val f=BuyFund(o["accountId"]!!.jsonPrimitive.content,r["fundId"]!!.jsonPrimitive.content,r["code"]!!.jsonPrimitive.content,r["name"]!!.jsonPrimitive.content)
                if(buyGateway.latestSnapshot?.get("groups")?.jsonArray?.any { group ->
                    val account=group.jsonObject
                    account["accountId"]?.jsonPrimitive?.content==f.accountId && account["funds"]?.jsonArray?.any {
                        it.jsonObject["code"]?.jsonPrimitive?.content==f.fundCode &&
                            it.jsonObject["fund_id"]?.jsonPrimitive?.content!=f.fundId
                    }==true
                }==true)throw BuyFailure(BuyProblem.EXTERNAL_CHANGED)
                if(buyStorage.read().any {it.input.fund.uiId==f.uiId&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}||sellStorage.read().any {it.input.fund.uiId==f.uiId&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)})throw BuyFailure(BuyProblem.PENDING_TRANSACTION)
                additions.add(f,amount(o,"shares"),amount(o,"unitCost"))
            }
            repository.refresh();refreshLocalNavs();publish()
            render(buildJsonObject {put("fundAddResult",buildJsonObject {put("requestId",requestId);put("ok",true);put("action",if(action.startsWith("fund-remove:"))"remove" else "add");put("message","已完成")})})
        }catch(e:CancellationException){throw e}
        catch(e:Exception) {
            val problem=(e as? BuyFailure)?.problem?:when((e as? YjbFailure)?.kind){ReadError.AUTH_EXPIRED->BuyProblem.AUTH_EXPIRED;ReadError.RATE_LIMITED->BuyProblem.RATE_LIMITED;else->null}
            if(problem==BuyProblem.AUTH_EXPIRED)repository.refresh()
            try {publish()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){}
            val message=when(problem){BuyProblem.AUTH_EXPIRED->"本地持仓暂不可用，请刷新。";BuyProblem.EXTERNAL_CHANGED->"该基金已有持仓或结果发生变化，请刷新核对；未覆盖持仓。";BuyProblem.WRITE_PENDING->"写入结果尚未确定，请保持原输入重新核对；不会重复发送。";BuyProblem.RATE_LIMITED->"请求频繁，请稍后重试。";BuyProblem.PENDING_TRANSACTION->"该基金有未完成交易，请先处理。";BuyProblem.WRITE_UNVERIFIED->"写入配置暂不可用，未发送请求。";else->"操作未完成，请检查输入或网络后重试。"}
            render(buildJsonObject {put("fundAddResult",buildJsonObject {put("requestId",requestId);put("ok",false);put("message",message)})})
        }
    }
    fun close(){scope.cancel()}
}
