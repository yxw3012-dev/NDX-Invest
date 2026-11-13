package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.BuyStatus
import com.ndxinvest.yjb.buy.SellStatus
import com.ndxinvest.yjb.dca.*
import kotlinx.serialization.json.*
import java.io.File

/** Runs under the native transaction gate before the foreground can edit the new stores. */
internal class LegacyPortfolioMigration(private val context: Context) {
    private val marker = AndroidEncryptedImports(context, AndroidKeystoreCredentialKeyProvider("ndx_migration_v1"),
        "ndx-migration-v1.enc", "ndx-migration-v1")

    suspend fun complete(): Boolean = marker.read().isNotEmpty()

    suspend fun run(portfolio: LocalPortfolio) {
        if (complete()) return
        val old = AndroidYjbStorage(context)
        val buys = AndroidBuyStorage(context).read()
        val sells = AndroidSellStorage(context).read()
        val plans = AndroidDcaStorage(context).read()
        val placeholders = AndroidLocalFunds(context).read()
        val undo = AndroidUndoStorage(context).read()
        val snapshotFile = File(context.noBackupFilesDir, "yjb-read-snapshot.enc")
        val snapshot = old.snapshot()
        if (snapshot == null && (snapshotFile.exists() || File(snapshotFile.path + ".bak").exists() ||
                buys.isNotEmpty() || sells.isNotEmpty() || plans.plans.isNotEmpty() || placeholders.isNotEmpty())) {
            error("旧版持仓快照不可读取，已保留原数据。")
        }
        if (snapshot != null) {
            val accounts = snapshot.getValue("groups").jsonArray.map {
                it.jsonObject.getValue("accountId").jsonPrimitive.content
            }.toSet()
            // The snapshot already contains all remotely applied changes. Old pending
            // operations remain available in the original files and cannot replay locally.
            val localBuys = buys.filter { it.input.fund.accountId in accounts }.map {
                if (it.status == BuyStatus.SYNCED || it.status == BuyStatus.CANCELLED) it
                else it.copy(status = BuyStatus.CANCELLED, problem = null)
            }
            val localSells = sells.filter { it.input.fund.accountId in accounts }.map {
                if (it.status == SellStatus.SYNCED || it.status == SellStatus.CANCELLED) it
                else it.copy(status = SellStatus.CANCELLED, problem = null)
            }
            val now = java.time.Instant.now()
            val today = now.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()
            val localPlans = plans.plans.filter { it.fund.accountId in accounts }.map { plan ->
                if (!plan.enabled || plan.deleted || plan.nextDate > today) plan
                else {
                    val next = DcaSchedule.firstAfter(today, plan.config)
                    val revision = plan.revision + 1
                    plan.copy(nextDate = next, updatedAt = now, revision = revision,
                        history = plan.history + DcaVersion(now, plan.config, true, next, revision))
                }
            }
            val planIds = localPlans.map { it.id }.toSet()
            val localPlanState = DcaState(localPlans, plans.occurrences.filter {
                it.planId in planIds && it.status == OccurrenceStatus.CREATED
            })
            val imports = old.imports()
            portfolio.importPositions(snapshot)
            check(LocalInitialPositions(context).saveImports(imports))
            AndroidBuyStorage(context, local = true).write(localBuys)
            AndroidSellStorage(context, local = true).write(localSells)
            AndroidDcaStorage(context, local = true).write(localPlanState)
            AndroidUndoStorage(context, local = true).write(JsonArray(undo.filter { it.jsonObject["phase"]?.jsonPrimitive?.content == "DONE" }))
            check(AndroidLocalFunds(context, local = true).write(JsonArray(placeholders.filter {
                it.jsonObject["kind"]?.jsonPrimitive?.content == "PLACEHOLDER" &&
                    it.jsonObject["accountId"]?.jsonPrimitive?.content in accounts
            })))
        }
        check(marker.write(JsonArray(listOf(JsonPrimitive(1)))))
    }
}
