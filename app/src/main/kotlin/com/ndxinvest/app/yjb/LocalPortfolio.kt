package com.ndxinvest.app.yjb

import android.content.Context
import com.ndxinvest.security.AndroidKeystoreCredentialKeyProvider
import com.ndxinvest.yjb.buy.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** The foreground and worker serialize mutations through NativeTransactionGate. */
internal class LocalPortfolio(context: Context) : BuyGateway {
    companion object {
        const val ACCOUNT = "90000000000000000001"
        private val codePattern = Regex("[0-9]{6}")
        private val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    private val positions = AndroidEncryptedImports(context, AndroidKeystoreCredentialKeyProvider("ndx_positions_v1"),
        "ndx-positions-v1.enc", "ndx-positions-v1")
    private val legacyAccounts = AndroidEncryptedImports(context, AndroidKeystoreCredentialKeyProvider("ndx_accounts_v1"),
        "ndx-accounts-v1.enc", "ndx-accounts-v1")

    suspend fun accounts(): Set<String> = legacyAccounts.read().map { it.jsonPrimitive.content }
        .toSet().ifEmpty { setOf(ACCOUNT) }

    suspend fun importPositions(snapshot: JsonObject) {
        val quotes = snapshot.getValue("quotes").jsonArray.map { it.jsonObject }
            .associateBy { it["fund_id"]?.jsonPrimitive?.content }
        val groups = snapshot.getValue("groups").jsonArray.map { it.jsonObject }
        val ids = groups.map { it.getValue("accountId").jsonPrimitive.content }
        require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[0-9]{1,20}")) })
        val rows = groups.flatMap { group ->
            val account = group.getValue("accountId").jsonPrimitive.content
            val funds = group.getValue("funds").jsonArray.map { it.jsonObject }
            require(funds.map { it.getValue("fund_id") }.distinct().size == funds.size)
            funds.map { holding ->
                val id = holding.getValue("fund_id").jsonPrimitive.content
                val quote = quotes[id]
                val code = (quote?.get("code") ?: holding.getValue("code")).jsonPrimitive.content
                val name = (quote?.get("short_name") ?: holding.getValue("short_name")).jsonPrimitive.content
                val shares = holding.getValue("hold_share").jsonPrimitive.content.toBigDecimal()
                val unitCost = holding.getValue("hold_cost").jsonPrimitive.content.toBigDecimal()
                require(id.matches(Regex("[0-9]{1,20}")) && codePattern.matches(code) && name.isNotBlank())
                BuyBaseline(shares, unitCost)
                buildJsonObject {
                    put("accountId", account)
                    put("fund_id", id)
                    put("code", code)
                    put("short_name", name)
                    put("hold_share", shares.toPlainString())
                    put("hold_cost", unitCost.toPlainString())
                }
            }
        }
        val importedPositions = JsonArray(rows)
        val importedAccounts = JsonArray(ids.map(::JsonPrimitive))
        check(positions.write(importedPositions) && positions.read() == importedPositions)
        check(legacyAccounts.write(importedAccounts) && legacyAccounts.read() == importedAccounts)
    }

    private fun accountOf(row: JsonObject) = row["accountId"]?.jsonPrimitive?.content ?: ACCOUNT

    private val navStorage = AndroidFormalNavStorage(context,local=true)
    private val cache = FormalNavCache(navStorage)
    var latestSnapshot: JsonObject? = null
        private set

    private suspend fun download(url: String, limit: Long, referer: String? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { referer?.let { header("Referer", it) } }.build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BuyFailure(BuyProblem.NETWORK)
                val source = response.body.source()
                if (source.request(limit + 1)) throw BuyFailure(BuyProblem.NAV_PROTOCOL)
                source.readUtf8()
            }
        } catch (_: java.io.IOException) {
            throw BuyFailure(BuyProblem.NETWORK)
        }
    }

    private var catalog: JsonArray? = null
    suspend fun search(keyword: String): JsonArray {
        val query = keyword.trim()
        require(query.length in 1..64)
        val funds = catalog ?: run {
            val script = download("https://fund.eastmoney.com/js/fundcode_search.js", 4_000_000)
            val data = script.trim().removePrefix("\uFEFF")
            require(Regex("""^var\s+r\s*=""").containsMatchIn(data))
            Json.parseToJsonElement(data.substringAfter('=').trim().removeSuffix(";")).jsonArray.also { catalog = it }
        }
        return JsonArray(funds.asSequence().map { it.jsonArray }
            .filter { it.size >= 3 && (it[0].jsonPrimitive.content.contains(query) || it[2].jsonPrimitive.content.contains(query, true)) }
            .filter { codePattern.matches(it[0].jsonPrimitive.content) }
            .distinctBy { it[0] }.take(50).map { row ->
                buildJsonObject { put("fundId", row[0]); put("code", row[0]); put("name", row[2]) }
            }.toList())
    }

    private suspend fun checkFund(fund: BuyFund) {
        if (fund.accountId !in accounts() || !fund.fundId.matches(Regex("[0-9]{1,20}")) || !codePattern.matches(fund.fundCode))
            throw BuyFailure(BuyProblem.ACCOUNT_MISMATCH)
    }

    override suspend fun current(fund: BuyFund): BuyBaseline {
        checkFund(fund)
        val row = positions.read().map { it.jsonObject }.singleOrNull { accountOf(it) == fund.accountId && it["fund_id"]?.jsonPrimitive?.content == fund.fundId }
            ?: return BuyBaseline(BigDecimal.ZERO, BigDecimal.ZERO, false)
        return BuyBaseline(row.getValue("hold_share").jsonPrimitive.content.toBigDecimal(),
            row.getValue("hold_cost").jsonPrimitive.content.toBigDecimal())
    }

    override fun writeAvailable() = true
    override fun normalizeTarget(target: BuyBaseline) = target.copy(
        unitCost = if (target.unitCost.scale() > 24) target.unitCost.setScale(24, RoundingMode.HALF_UP) else target.unitCost
    )
    override fun matches(actual: BuyBaseline, target: BuyBaseline) = actual.equivalent(target)

    override suspend fun postFinal(fund: BuyFund, target: BuyBaseline) {
        checkFund(fund)
        require(target.exists && target.shares.signum() >= 0 && target.unitCost.signum() >= 0)
        val rows = positions.read().filterNot {
            val row = it.jsonObject
            accountOf(row) == fund.accountId && row["fund_id"]?.jsonPrimitive?.content == fund.fundId
        }
        val row = buildJsonObject {
            put("accountId", fund.accountId)
            put("fund_id", fund.fundId)
            put("code", fund.fundCode)
            put("short_name", fund.fundName)
            put("hold_share", target.shares.toPlainString())
            put("hold_cost", target.unitCost.toPlainString())
        }
        if (!positions.write(JsonArray(rows + row))) throw BuyFailure(BuyProblem.STORAGE)
    }

    override suspend fun officialHistory(fund: BuyFund): List<OfficialBuyNav> {
        checkFund(fund)
        return cache.history(fund.fundCode) {
            val code = fund.fundCode
            val text = download("https://api.fund.eastmoney.com/f10/lsjz?fundCode=$code&pageIndex=1&pageSize=20", 256_000,
                "https://fundf10.eastmoney.com/jjjz_$code.html")
            try {
                val payload = Json.parseToJsonElement(text).jsonObject
                require(payload["ErrCode"]?.jsonPrimitive?.intOrNull == 0)
                val rows = payload.getValue("Data").jsonObject.getValue("LSJZList").jsonArray.map {
                    val row = it.jsonObject
                    LocalDate.parse(row.getValue("FSRQ").jsonPrimitive.content) to
                        row.getValue("DWJZ").jsonPrimitive.content.toBigDecimal()
                }
                val today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                require(rows.all { (date, nav) -> date <= today && nav.signum() > 0 })
                require(rows.map { it.first }.distinct().size == rows.size)
                rows.toMap()
            } catch (_: Exception) {
                throw BuyFailure(BuyProblem.NAV_PROTOCOL)
            }
        }.map { OfficialBuyNav(it.key, it.value) }.sortedBy { it.date }
    }

    override suspend fun officialNav(fund: BuyFund, targetDate: LocalDate): BigDecimal? =
        resolveOfficialNav(fund, targetDate) { false }?.value

    override suspend fun resolveOfficialNav(fund: BuyFund, startDate: LocalDate, knownClosed: (LocalDate) -> Boolean): OfficialBuyNav? {
        val history = officialHistory(fund)
        val dates = history.associateBy { it.date }
        var date = startDate
        while (date !in dates && knownClosed(date)) date = date.plusDays(1)
        if (history.firstOrNull()?.date?.let { date < it } == true) throw BuyFailure(BuyProblem.NAV_OUTSIDE_WINDOW)
        return dates[date]
    }

    suspend fun snapshot(): JsonObject {
        val rows = positions.read().map { it.jsonObject }
        val funds = rows.map { row ->
            val code = row.getValue("code").jsonPrimitive.content
            val fund = BuyFund(accountOf(row), row.getValue("fund_id").jsonPrimitive.content, code, row.getValue("short_name").jsonPrimitive.content)
            val nav = try {
                officialHistory(fund).lastOrNull()
            } catch (e: BuyFailure) {
                if (e.problem !in setOf(BuyProblem.NETWORK, BuyProblem.NAV_PROTOCOL)) throw e
                navStorage.read().map { it.jsonObject }.singleOrNull { it["fundId"]?.jsonPrimitive?.content == code }
                    ?.get("navs")?.jsonArray?.lastOrNull()?.jsonObject?.let {
                        OfficialBuyNav(LocalDate.parse(it.getValue("day").jsonPrimitive.content),
                            it.getValue("unit_net").jsonPrimitive.content.toBigDecimal())
                    }
            }
            val shares = row.getValue("hold_share").jsonPrimitive.content.toBigDecimal()
            val cost = shares.multiply(row.getValue("hold_cost").jsonPrimitive.content.toBigDecimal())
            buildJsonObject {
                row.forEach { (key, value) -> put(key, value) }
                put("holding_cost", cost.toPlainString())
                if (nav != null) {
                    val value = shares.multiply(nav.value)
                    put("last_net", nav.value.toPlainString())
                    put("net_day", nav.date.toString())
                    put("money", value.toPlainString())
                    put("hold_earn", value.subtract(cost).toPlainString())
                }
            }
        }
        val accountIds = accounts()
        return buildJsonObject {
            put("displayName", "本地持仓")
            put("maskedId", "")
            put("lastSyncAt", Instant.now().toString())
            put("groups", buildJsonArray {
                accountIds.forEachIndexed { index, account ->
                    add(buildJsonObject {
                        put("accountId", account)
                        put("accountTitle", if (accountIds.size == 1) "本地持仓" else "本地持仓 ${index + 1}")
                        put("funds", JsonArray(funds.filter { accountOf(it) == account }))
                    })
                }
            })
            put("quotes", JsonArray(emptyList()))
        }.also { latestSnapshot = it }
    }
}
