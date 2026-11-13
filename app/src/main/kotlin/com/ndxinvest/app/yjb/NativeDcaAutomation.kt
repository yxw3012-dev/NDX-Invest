package com.ndxinvest.app.yjb

import android.content.Context
import androidx.work.*
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.yjb.buy.*
import com.ndxinvest.yjb.dca.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/** One process-wide gate: WebViews, manual trades, session changes and workers share it. */
internal object NativeTransactionGate {val mutex=Mutex()}

internal object NativeDcaAutomation {
    data class CheckResult(val passed:Boolean=true,val holdingsChanged:Boolean=false,val error:String="")
    const val UNIQUE_WORK="ndx-dca-official-nav-v1"
    val changes=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    fun periodicRequest()=PeriodicWorkRequestBuilder<DcaNavWorker>(1,TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
    fun schedule(context:Context) {
        if(BuildConfig.FLAVOR!="production")return
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK,ExistingPeriodicWorkPolicy.KEEP,periodicRequest())
    }
    /** No token, account identifiers or server error bodies are logged or returned to JavaScript. */
    suspend fun run(context:Context):Boolean=NativeTransactionGate.mutex.withLock {
        checkWithinGate(context).passed
    }
    /** Manual refresh already owns the shared gate; suppress its intermediate notification. */
    suspend fun checkWithinGate(context:Context,notify:Boolean=true):CheckResult=withContext(Dispatchers.IO) {
        if(BuildConfig.FLAVOR!="production")return@withContext CheckResult()
        try {
            if(!LegacyPortfolioMigration(context).complete())return@withContext CheckResult()
            val gateway=LocalPortfolio(context)
            val accounts=gateway.accounts()
            val planStore=AndroidDcaStorage(context,local=true)
            val buyStore=AndroidBuyStorage(context,local=true);val sellStore=AndroidSellStorage(context,local=true)
            val hasPlans=planStore.read().plans.any {it.enabled&&!it.deleted&&it.fund.accountId in accounts}
            val hasTrades=buyStore.read().any {(it.autoSync||it.originPlanId!=null)&&it.input.fund.accountId in accounts&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}||sellStore.read().any {it.autoSync&&it.input.fund.accountId in accounts&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)}
            if(!hasPlans&&!hasTrades)return@withContext CheckResult()
            observeReminders(context,buyStore,sellStore)
            val buys=SingleBuyRepository(buyStore,gateway,BundledBuyCalendar::forCode,pendingOther={fund->sellStore.read().any {it.input.fund.accountId==fund.accountId&&it.input.fund.fundId==fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(SellStatus.SYNCED,SellStatus.CANCELLED)}})
            // An interrupted POST is reconciled through the ordinary BUY's durable sending marker.
            buys.restore()
            val sells=SingleSellRepository(sellStore,gateway,pendingOther={fund->buyStore.read().any {it.input.fund.accountId==fund.accountId&&it.input.fund.fundId==fund.fundId&&it.postMayHaveBeenSent&&it.status !in setOf(BuyStatus.SYNCED,BuyStatus.CANCELLED)}})
            sells.restore()
            val plans=DcaRepository(planStore,buyStore,buys,rejectCollision={id->if(sellStore.read().any {it.id==id})throw DcaFailure(DcaProblem.STORAGE)})
            val undo=TransactionUndo(buyStore,sellStore,AndroidUndoStorage(context,local=true),gateway)
            val engine=DcaNavEngine(planStore,buyStore,plans,buys,gateway,pendingSell=undo::blocked)
            val report=engine.materializeDue(accounts)
            val ordinary=if(report.problems.any {it in setOf(BuyProblem.RATE_LIMITED,BuyProblem.AUTH_EXPIRED,BuyProblem.STORAGE)})OrdinaryTradeAutomation.Report(0,emptySet()) else
                OrdinaryTradeAutomation(buyStore,sellStore,buys,sells,engine::reservedBefore,undo::blocked).run(accounts)
            if(notify)changes.tryEmit(Unit)
            val failures=(report.problems+ordinary.problems)-filterOfNormalWaits
            val error=if(failures.isEmpty())"" else when {
                BuyProblem.AUTH_EXPIRED in failures->"暂时无法读取交易数据"
                BuyProblem.CATCHUP_REVIEW in failures->"定投积压期次需确认，请查看当前基金的计划管理。"
                BuyProblem.RATE_LIMITED in failures->"净值请求频繁，已暂停请求，请稍后重试。"
                BuyProblem.EXTERNAL_CHANGED in failures->"交易检查发现持仓变化，请核对"
                BuyProblem.STORAGE in failures->"交易记录未能安全保存"
                else->"交易检查或同步未完成，请查看记录与计划状态"
            }
            val rows=observeReminders(context,buyStore,sellStore)
            if(BuyProblem.AUTH_EXPIRED in failures)com.ndxinvest.app.notifications.NdxReminders.observeRead(context,"AUTH_EXPIRED","AUTH_EXPIRED")
            // A failed transaction already has its own alert; don't issue a second aggregate alert.
            if(rows?.none {it.jsonObject["transactionStatus"]?.jsonPrimitive?.content in setOf("SYNC_FAILED","EXTERNAL_CHANGED")} == true && BuyProblem.AUTH_EXPIRED !in failures)
                com.ndxinvest.app.notifications.NdxReminders.observeDcaCheck(context,error)
            CheckResult(failures.isEmpty(),(report.synced+ordinary.synced)>0,error)
        }catch(e:CancellationException){throw e}
        catch(_:Exception){com.ndxinvest.app.notifications.NdxReminders.observeDcaCheck(context,"交易检查失败，请稍后重试");if(notify)changes.tryEmit(Unit);CheckResult(false,error="交易检查失败，请稍后重试")}
    }
    private val filterOfNormalWaits=setOf(BuyProblem.NAV_DATE_UNRESOLVED,BuyProblem.WRITE_PENDING,BuyProblem.PENDING_TRANSACTION)
    private suspend fun observeReminders(context:Context,buyStore:AndroidBuyStorage,sellStore:AndroidSellStorage):JsonArray? = try {
        com.ndxinvest.app.notifications.NdxReminders.events(buyStore.read(),sellStore.read()).also {com.ndxinvest.app.notifications.NdxReminders.observeTransactions(context,it)}
    } catch(e:CancellationException){throw e} catch(_:Exception){null}
}

internal class DcaNavWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=if(NativeDcaAutomation.run(applicationContext))Result.success()else Result.retry()
}
