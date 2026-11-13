package com.ndxinvest.ledger.persistence

import com.ndxinvest.database.LedgerEntryEntity
import com.ndxinvest.database.TrackingBaselineEntity
import com.ndxinvest.ledger.calculation.LedgerHistorySnapshot
import com.ndxinvest.ledger.calculation.TrackingBaseline
import com.ndxinvest.ledger.model.*
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

/** Strict versioned evidence; missing/null facts and decimal scale survive without numeric coercion. */
internal object TrackingBaselineCodec {
    fun encode(baseline: TrackingBaseline): TrackingBaselineEntity = TrackingBaselineEntity(baseline.fundId.value, json(baseline).toString())

    fun decode(entity: TrackingBaselineEntity): TrackingBaseline {
        val root = Json.parseToJsonElement(entity.payloadJson).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1) { "Unsupported baseline evidence version" }
        val history = root.getValue("history").jsonObject
        val snapshot = LedgerHistorySnapshot(FundId(history.text("fundId")),
            history.getValue("transactions").jsonArray.map { it.jsonObject.transaction() },
            history.getValue("realLots").jsonArray.map { it.jsonObject.toLot() },
            history.getValue("legacyLots").jsonArray.map { it.jsonObject.toLegacyLot() },
            history.getValue("entries").jsonArray.map { element ->
                val entry = element.jsonObject
                LedgerEntryCodec.decode(LedgerEntryEntity(entry.text("id"), entry.getValue("sequence").jsonPrimitive.long,
                    entry.text("fundId"), entry.text("kind"), entry.text("payloadJson")))
            })
        val configured = root.getValue("precision").jsonObject
        val precision = FundPrecision(configured.getValue("moneyScale").jsonPrimitive.int, configured.getValue("shareScale").jsonPrimitive.int,
            configured.getValue("navScale").jsonPrimitive.int, RoundingMode.valueOf(configured.text("roundingMode")))
        val nav = root.getValue("officialNav").jsonObject.toNavWitness()
        val baseline = TrackingBaseline.prepare(snapshot, nav, precision, Instant.parse(root.text("recordedAt")), nav.navDate)
        require(entity.fundId == baseline.fundId.value && json(baseline) == root) { "Altered, missing or unknown baseline evidence" }
        return baseline
    }

    private fun json(baseline: TrackingBaseline): JsonObject = buildJsonObject {
        put("version", 1)
        put("recordedAt", baseline.recordedAt.toString())
        put("startingShares", baseline.startingShares.toPlainString())
        put("startingValue", baseline.startingValue.toPlainString())
        put("officialNav", baseline.officialNav.toWitnessJson())
        put("precision", buildJsonObject {
            put("moneyScale", baseline.precision.moneyScale); put("shareScale", baseline.precision.shareScale)
            put("navScale", baseline.precision.navScale); put("roundingMode", baseline.precision.roundingMode.name)
        })
        put("history", buildJsonObject {
            put("fundId", baseline.fundId.value)
            put("transactions", JsonArray(baseline.history.transactions.map { it.json() }))
            put("realLots", JsonArray(baseline.history.realLots.map { it.toJson() }))
            put("legacyLots", JsonArray(baseline.history.legacyLots.map { it.toJson() }))
            put("entries", JsonArray(baseline.history.entries.map { event ->
                val row = LedgerEntryCodec.encode(event)
                buildJsonObject { put("id", row.id); put("sequence", row.sequence); put("fundId", row.fundId)
                    put("kind", row.entryType); put("payloadJson", row.payloadJson) }
            }))
        })
    }

    private fun LedgerTransaction.json(): JsonObject = buildJsonObject {
        put("id", id.value); put("fundId", fundId.value); put("type", type.name); put("status", status.name)
        put("origin", origin.name); put("authority", authority.name); put("ruleVersionId", ruleVersionId.value)
        put("applicationTime", applicationTime.toString()); put("tradeDate", tradeDate.toString())
        put("navDate", navDate?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("confirmationDate", confirmationDate?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("settlementDate", settlementDate?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("cashFlowDate", cashFlowDate?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", createdAt.toString()); put("updatedAt", updatedAt.toString()); put("isManualCorrected", isManualCorrected)
        mapOf("requestedAmount" to requestedAmount, "grossAmount" to grossAmount, "netAmount" to netAmount,
            "shares" to shares, "unitNav" to unitNav, "purchaseFee" to purchaseFee, "redemptionFee" to redemptionFee,
            "actualCashFlow" to actualCashFlow).forEach { (key, value) -> put(key, value?.toPlainString()?.let(::JsonPrimitive) ?: JsonNull) }
    }

    private fun JsonObject.transaction(): LedgerTransaction = LedgerTransaction(
        id = TransactionId(text("id")), fundId = FundId(text("fundId")), type = enumValueOf(text("type")),
        status = enumValueOf(text("status")), origin = enumValueOf(text("origin")), authority = enumValueOf(text("authority")),
        applicationTime = Instant.parse(text("applicationTime")), tradeDate = LocalDate.parse(text("tradeDate")),
        navDate = nullable("navDate")?.let(LocalDate::parse), confirmationDate = nullable("confirmationDate")?.let(LocalDate::parse),
        settlementDate = nullable("settlementDate")?.let(LocalDate::parse), cashFlowDate = nullable("cashFlowDate")?.let(LocalDate::parse),
        ruleVersionId = RuleVersionId(text("ruleVersionId")), requestedAmount = decimal("requestedAmount"), grossAmount = decimal("grossAmount"),
        netAmount = decimal("netAmount"), shares = decimal("shares"), unitNav = decimal("unitNav"), purchaseFee = decimal("purchaseFee"),
        redemptionFee = decimal("redemptionFee"), actualCashFlow = decimal("actualCashFlow"),
        isManualCorrected = getValue("isManualCorrected").jsonPrimitive.boolean,
        createdAt = Instant.parse(text("createdAt")), updatedAt = Instant.parse(text("updatedAt")),
    )
    private fun JsonObject.text(key: String): String = requireNotNull(getValue(key).jsonPrimitive.contentOrNull)
    private fun JsonObject.nullable(key: String): String? = getValue(key).jsonPrimitive.contentOrNull
    private fun JsonObject.decimal(key: String): java.math.BigDecimal? = nullable(key)?.let(DecimalStorageCodec::decode)
}
