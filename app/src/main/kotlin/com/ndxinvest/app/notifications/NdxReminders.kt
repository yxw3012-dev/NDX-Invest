package com.ndxinvest.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ndxinvest.app.MainActivity
import com.ndxinvest.app.R
import com.ndxinvest.yjb.buy.BuyTransaction
import com.ndxinvest.yjb.buy.SellTransaction
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

/** Delivery observes durable outcomes only. It cannot create, retry or change any transaction. */
internal object NdxReminders {
    private val categories = linkedMapOf("drawdown" to "回撤提醒", "dca" to "定投提醒", "exception" to "异常提醒", "test" to "测试通知")
    private fun prefs(c: Context) = c.getSharedPreferences("ndx-reminders-v1", Context.MODE_PRIVATE)
    private fun channel(key: String) = "ndx_reminder_$key"
    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = c.getSystemService(NotificationManager::class.java)
            categories.forEach { (key, label) -> manager.createNotificationChannel(NotificationChannel(channel(key), label, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = if (key == "test") "由用户主动发送，用于检查通知显示" else "NDX Invest 的$label"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }) }
        }
    }
    fun allowed(c: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(c).areNotificationsEnabled()
    private fun channelAllowed(c: Context, key: String): Boolean = Build.VERSION.SDK_INT < 26 || c.getSystemService(NotificationManager::class.java).getNotificationChannel(channel(key))?.importance != NotificationManager.IMPORTANCE_NONE
    fun info(c: Context): JSONObject = JSONObject().apply {
        put("notificationsAllowed", allowed(c))
        put("notificationDeliveryImplemented", true)
        put("reminders", JSONObject().apply { categories.filterKeys { it != "test" }.forEach { (key, _) -> put(key, JSONObject().put("enabled", prefs(c).getBoolean("enabled:$key", true)).put("channelAllowed", channelAllowed(c, key))) } })
    }
    @Synchronized fun setEnabled(c: Context, key: String, enabled: Boolean) {
        if (key !in categories || key == "test") return
        prefs(c).edit().putBoolean("enabled:$key", enabled).commit()
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun send(c: Context, category: String, event: String, title: String, text: String): Boolean {
        if (category != "test" && !prefs(c).getBoolean("enabled:$category", true)) return false
        if (!allowed(c) || !channelAllowed(c, category)) return false
        return try {
            val intent = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = PendingIntent.getActivity(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(c, channel(category))
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(tap)
                .setAutoCancel(true).setOnlyAlertOnce(category != "test").setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(c, channel(category)).setSmallIcon(R.drawable.ic_notification).setContentTitle("NDX Invest").setContentText("有一条新的提醒").build())
                .build()
            NotificationManagerCompat.from(c).notify(hash(event), 1, notification)
            true
        } catch (_: Exception) { false } // Delivery must never interrupt a successful trade/cache update.
    }
    @Synchronized fun test(c: Context): String = when {
        !allowed(c) -> "请先允许系统通知。"
        !channelAllowed(c, "test") -> "测试通知通道已关闭，请到系统通知设置开启。"
        send(c, "test", "user-test", "NDX Invest 测试通知", "通知发送已接入。请确认手机是否显示这条通知。") -> "已提交测试通知，请查看通知栏。"
        else -> "通知未能发送，请检查系统通知设置。"
    }
    /** Small validated local strategy copy for reminders; no fund binding or trading capability. */
    @Synchronized fun configureStrategy(c: Context, text: String) {
        try {
            require(text.length <= 16000)
            val input = JSONArray(text); require(input.length() in 1..50)
            val thresholds = (0 until input.length()).map { i ->
                val tier = input.getJSONObject(i)
                val thresholdText = tier.getString("drawdownThreshold")
                val amountText = tier.getString("plannedAmount")
                require(listOf(thresholdText, amountText).all { it.length <= 128 && Regex("[0-9]+(?:\\.[0-9]+)?").matches(it) })
                val threshold = thresholdText.toBigDecimal()
                val amount = amountText.toBigDecimal()
                require(threshold > BigDecimal.ZERO && threshold <= BigDecimal("100") && amount > BigDecimal.ZERO)
                threshold.stripTrailingZeros().toPlainString()
            }.sortedBy { it.toBigDecimal() }
            require(thresholds.distinct().size == thresholds.size)
            prefs(c).edit().putString("thresholds", JSONArray(thresholds).toString()).commit()
        } catch (_: Exception) { /* Invalid UI input cannot replace saved valid thresholds. */ }
    }
    /** Called only after a fresh valid index update, with a real historical drawdown. */
    @Synchronized fun observeMarket(c: Context, rate: BigDecimal, quoteDate: LocalDate) {
        try {
            // Old cached/outdated quotes must not manufacture a new alert after an app restart.
            val today = LocalDate.now(ZoneId.of("America/New_York"))
            if (quoteDate < today.minusDays(4) || quoteDate > today) return
            val p = prefs(c); val tiers = JSONArray(p.getString("thresholds", "[]"))
            val reached = (0 until tiers.length()).map { tiers.getString(it).toBigDecimal() }.filter { rate.negate().movePointRight(2) >= it }.maxOrNull()
            if (reached == null) return
            val value = reached.stripTrailingZeros().toPlainString()
            val event = "$quoteDate:$value"
            val sent = JSONArray(p.getString("drawdownEvents", "[]"))
            val events = (0 until sent.length()).map { sent.getString(it) }
            if (event in events) return
            if (send(c, "drawdown", "drawdown", "NDX100 回撤提醒", "当前回撤已达到 -$value% 策略档位。可进入买入页选择基金并确认。")) p.edit().putString("drawdownEvents", JSONArray((events + event).takeLast(128)).toString()).commit()
        } catch (_: Exception) { }
    }
    /** Seed existing history once; subsequent status transitions persist across app/worker restarts. */
    fun events(buys: List<BuyTransaction>, sells: List<SellTransaction>): JsonArray = buildJsonArray {
        buys.forEach { t -> add(buildJsonObject { put("id",t.id);put("transactionStatus",t.status.name);put("time",t.createdAt.toString());put("updatedAt",t.updatedAt.toString());t.originPlanId?.let {put("originPlanId",it)} }) }
        sells.forEach { t -> add(buildJsonObject { put("id",t.id);put("transactionStatus",t.status.name);put("time",t.createdAt.toString());put("updatedAt",t.updatedAt.toString()) }) }
    }
    @Synchronized fun observeTransactions(c: Context, rows: JsonArray) {
        try {
            val p = prefs(c)
            val old = JSONObject(p.getString("transactionStates", "{}")!!)
            val seeded = p.getBoolean("historySeeded", false)
            val next = JSONObject(old.toString())
            // Bounded metadata only. Original account/fund IDs, amounts and credentials are not saved here.
            rows.sortedByDescending { it.jsonObject["time"]?.jsonPrimitive?.content.orEmpty() }.take(2048).forEach { element ->
                val row = element.jsonObject; val id = hash(row["id"]!!.jsonPrimitive.content)
                val status = row["transactionStatus"]!!.jsonPrimitive.content
                val prior = old.optJSONObject(id)
                val previous = prior?.optString("status").orEmpty()
                val updated = row["updatedAt"]!!.jsonPrimitive.content
                // A queued foreground projection may arrive after a newer worker result.
                if (prior != null && Instant.parse(prior.getString("updatedAt")) > Instant.parse(updated)) { next.put(id,prior);return@forEach }
                next.put(id, JSONObject().put("status",status).put("updatedAt",updated))
                if (!seeded || previous == status) return@forEach
                val dca = row["originPlanId"] != null
                when (status) {
                    "SYNCED" -> if (dca) send(c, "dca", "trade:$id", "定投已完成", "一笔定投已确认正式净值，并更新本地持仓。请在记录页查看。")
                    "SYNC_FAILED" -> send(c, "exception", "trade:$id", "交易同步未完成", "请在记录页查看失败原因，再决定是否重新同步。")
                    "EXTERNAL_CHANGED" -> send(c, "exception", "trade:$id", "持仓发生变化", "检测到本地持仓与交易创建时不同，已停止写入。请在交易详情中核对。")
                }
            }
            val bounded = JSONObject()
            next.keys().asSequence().toList().sortedByDescending { next.getJSONObject(it).getString("updatedAt") }.take(2048).forEach { bounded.put(it,next.getJSONObject(it)) }
            if (!seeded || bounded.toString() != old.toString()) p.edit().putString("transactionStates", bounded.toString()).putBoolean("historySeeded", true).commit()
        } catch (_: Exception) { }
    }
    @Synchronized fun observeRead(c: Context, account: String?, holding: String?) {
        try {
            val p = prefs(c)
            val expired = account == "AUTH_EXPIRED" || holding == "AUTH_EXPIRED"
            if (expired && !p.getBoolean("authAlert", false)) {
                if (send(c, "exception", "auth", "养基宝登录已过期", "请在我的 → 养基宝账户中重新登录。")) p.edit().putBoolean("authAlert", true).commit()
            } else if (account in setOf("CONNECTED", "NOT_CONNECTED") && !expired) p.edit().putBoolean("authAlert", false).commit()
            if (holding in setOf("ERROR", "OFFLINE_CACHE")) {
                if (!p.getBoolean("readAlert", false) && send(c, "exception", "read", "持仓同步未完成", "已保留可用持仓数据，请稍后刷新或到数据状态页查看。")) p.edit().putBoolean("readAlert", true).commit()
            } else if (holding in setOf("READY", "EMPTY", "NOT_CONNECTED")) p.edit().putBoolean("readAlert", false).commit()
        } catch (_: Exception) { }
    }
    @Synchronized fun observeDcaCheck(c: Context, error: String) {
        try {
            val p = prefs(c)
            if (error.isEmpty()) { p.edit().remove("dcaError").commit(); return }
            val key = hash(error)
            if (p.getString("dcaError", null) != key && send(c, "exception", "dca-check", "定投检查未完成", error)) p.edit().putString("dcaError", key).commit()
        } catch (_: Exception) { }
    }
}
