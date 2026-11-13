package com.ndxinvest.database

import com.ndxinvest.ledger.model.*
import com.ndxinvest.ledger.calculation.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

public object LedgerEventStorageCodec {
    public fun encode(entry: LedgerEntry): LedgerEntryEntity = LedgerEntryEntity(
        id = entry.id.value,
        sequence = entry.sequence,
        fundId = entry.fundId.value,
        entryType = entry.entryType(),
        payloadJson = entry.payload().toString(),
    )

    public fun decode(entity: LedgerEntryEntity): LedgerEntry {
        val payload = LEDGER_JSON.parseToJsonElement(entity.payloadJson).jsonObject
        require(payload.requiredInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported ledger entry schema" }
        val id = LedgerEntryId(entity.id)
        val fundId = FundId(entity.fundId)
        return when (entity.entryType) {
            REAL_LOT_OPENED -> RealLotOpened(id, entity.sequence, payload.requiredObject("lot").toLot())
            LEGACY_LOT_OPENED -> LegacyLotOpened(id, entity.sequence, payload.requiredObject("lot").toLegacyLot())
            LOTS_REDEEMED -> LotsRedeemed(
                id,
                entity.sequence,
                fundId,
                payload.requiredArray("consumptions").map { it.jsonObject.toConsumption() },
                payload["sourceTransactionId"]?.jsonPrimitive?.contentOrNull?.let(::TransactionId),
            )
            LEGACY_LOT_REDEEMED -> LegacyLotRedeemed(
                id,
                entity.sequence,
                fundId,
                payload.requiredObject("consumption").toConsumption(),
                payload["sourceTransactionId"]?.jsonPrimitive?.contentOrNull?.let(::TransactionId),
            )
            ASSUMED_PURCHASE_REVERSED -> AssumedPurchaseReversed(
                id,
                entity.sequence,
                fundId,
                TransactionId(payload.requiredString("sourceTransactionId")),
            )
            UNCONSUMED_PURCHASE_LOT_CORRECTED -> UnconsumedPurchaseLotCorrected(
                id, entity.sequence,
                payload.requiredObject("expectedLot").toLot(),
                payload.requiredObject("correctedLot").toLot(),
            )
            UNCONSUMED_PURCHASE_AMOUNTS_CORRECTED -> UnconsumedPurchaseAmountsCorrected(
                id, entity.sequence, payload.requiredObject("expectedLot").toLot(),
                payload.requiredObject("correctedLot").toLot(),
            )
            UNCONSUMED_PURCHASE_NAV_CORRECTED -> UnconsumedPurchaseNavCorrected(
                id, entity.sequence, payload.requiredObject("expectedLot").toLot(),
                payload.requiredObject("correctedLot").toLot(), payload.requiredObject("officialNav").toNavWitness(),
            )
            CONSUMED_PURCHASE_COST_CORRECTED, CONSUMED_PURCHASE_SHARES_CORRECTED -> {
                val precision = payload.requiredObject("precision")
                val expected = payload.requiredObject("expectedLot").toLot()
                val corrected = payload.requiredObject("correctedLot").toLot()
                val allocations = payload.requiredArray("allocations").map { element ->
                        val revision = element.jsonObject
                        RedemptionCostRevision(LedgerEntryId(revision.requiredString("entryId")), TransactionId(revision.requiredString("transactionId")),
                            revision.requiredObject("expected").toConsumption(), revision.requiredObject("corrected").toConsumption())
                    }
                val savedPrecision = FundPrecision(precision.requiredInt("moneyScale"), precision.requiredInt("shareScale"), precision.requiredInt("navScale"),
                    RoundingMode.valueOf(precision.requiredString("roundingMode")))
                val recordedAt = Instant.parse(payload.requiredString("recordedAt"))
                if (entity.entryType == CONSUMED_PURCHASE_COST_CORRECTED)
                    ConsumedPurchaseCostCorrected(id, entity.sequence, expected, corrected, allocations, savedPrecision, recordedAt)
                else ConsumedPurchaseSharesCorrected(id, entity.sequence, expected, corrected, allocations, savedPrecision, recordedAt)
            }
            else -> error("Unsupported ledger entry type: ${entity.entryType}")
        }.also {
            require(it.fundId == fundId) { "Ledger payload fund does not match its envelope" }
            if (it is ConsumedPurchaseCostCorrected) require(it.payload() == payload) { "Missing, altered or unknown cost correction evidence" }
            if (it is ConsumedPurchaseSharesCorrected) require(it.payload() == payload) { "Missing, altered or unknown share correction evidence" }
        }
    }

    private fun LedgerEntry.entryType(): String = when (this) {
        is RealLotOpened -> REAL_LOT_OPENED
        is LegacyLotOpened -> LEGACY_LOT_OPENED
        is LotsRedeemed -> LOTS_REDEEMED
        is LegacyLotRedeemed -> LEGACY_LOT_REDEEMED
        is AssumedPurchaseReversed -> ASSUMED_PURCHASE_REVERSED
        is UnconsumedPurchaseLotCorrected -> UNCONSUMED_PURCHASE_LOT_CORRECTED
        is UnconsumedPurchaseAmountsCorrected -> UNCONSUMED_PURCHASE_AMOUNTS_CORRECTED
        is UnconsumedPurchaseNavCorrected -> UNCONSUMED_PURCHASE_NAV_CORRECTED
        is ConsumedPurchaseCostCorrected -> CONSUMED_PURCHASE_COST_CORRECTED
        is ConsumedPurchaseSharesCorrected -> CONSUMED_PURCHASE_SHARES_CORRECTED
    }

    private fun LedgerEntry.payload(): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        when (this@payload) {
            is RealLotOpened -> put("lot", lot.toJson())
            is LegacyLotOpened -> put("lot", lot.toJson())
            is LotsRedeemed -> {
                put("consumptions", buildJsonArray { consumptions.forEach { add(it.toJson()) } })
                sourceTransactionId?.let { put("sourceTransactionId", it.value) }
            }
            is LegacyLotRedeemed -> {
                put("consumption", consumption.toJson())
                sourceTransactionId?.let { put("sourceTransactionId", it.value) }
            }
            is AssumedPurchaseReversed -> put("sourceTransactionId", sourceTransactionId.value)
            is UnconsumedPurchaseLotCorrected -> {
                put("expectedLot", expectedLot.toJson())
                put("correctedLot", correctedLot.toJson())
            }
            is UnconsumedPurchaseAmountsCorrected -> {
                put("expectedLot", expectedLot.toJson())
                put("correctedLot", correctedLot.toJson())
            }
            is UnconsumedPurchaseNavCorrected -> {
                put("expectedLot", expectedLot.toJson())
                put("correctedLot", correctedLot.toJson())
                put("officialNav", officialNav.toWitnessJson())
            }
            is ConsumedPurchaseCostCorrected -> {
                put("expectedLot", expectedLot.toJson()); put("correctedLot", correctedLot.toJson())
                put("recordedAt", recordedAt.toString())
                put("precision", buildJsonObject {
                    put("moneyScale", precision.moneyScale); put("shareScale", precision.shareScale)
                    put("navScale", precision.navScale); put("roundingMode", precision.roundingMode.name)
                })
                put("allocations", buildJsonArray { allocations.forEach { revision -> add(buildJsonObject {
                    put("entryId", revision.entryId.value); put("transactionId", revision.transactionId.value)
                    put("expected", revision.expected.toJson()); put("corrected", revision.corrected.toJson())
                }) } })
            }
            is ConsumedPurchaseSharesCorrected -> {
                put("expectedLot", expectedLot.toJson()); put("correctedLot", correctedLot.toJson())
                put("recordedAt", recordedAt.toString())
                put("precision", buildJsonObject {
                    put("moneyScale", precision.moneyScale); put("shareScale", precision.shareScale)
                    put("navScale", precision.navScale); put("roundingMode", precision.roundingMode.name)
                })
                put("allocations", buildJsonArray { allocations.forEach { revision -> add(buildJsonObject {
                    put("entryId", revision.entryId.value); put("transactionId", revision.transactionId.value)
                    put("expected", revision.expected.toJson()); put("corrected", revision.corrected.toJson())
                }) } })
            }
        }
    }

    private const val SCHEMA_VERSION: Int = 1
    private const val REAL_LOT_OPENED: String = "REAL_LOT_OPENED"
    private const val LEGACY_LOT_OPENED: String = "LEGACY_LOT_OPENED"
    private const val LOTS_REDEEMED: String = "LOTS_REDEEMED"
    private const val LEGACY_LOT_REDEEMED: String = "LEGACY_LOT_REDEEMED"
    private const val ASSUMED_PURCHASE_REVERSED: String = "ASSUMED_PURCHASE_REVERSED"
    private const val UNCONSUMED_PURCHASE_LOT_CORRECTED: String = "UNCONSUMED_PURCHASE_LOT_CORRECTED"
    private const val UNCONSUMED_PURCHASE_AMOUNTS_CORRECTED: String = "UNCONSUMED_PURCHASE_AMOUNTS_CORRECTED"
    private const val UNCONSUMED_PURCHASE_NAV_CORRECTED: String = "UNCONSUMED_PURCHASE_NAV_CORRECTED"
    private const val CONSUMED_PURCHASE_COST_CORRECTED: String = "CONSUMED_PURCHASE_COST_CORRECTED"
    private const val CONSUMED_PURCHASE_SHARES_CORRECTED: String = "CONSUMED_PURCHASE_SHARES_CORRECTED"
}

private fun NavRecord.toWitnessJson(): JsonObject = buildJsonObject {
    put("fundId", fundId.value)
    put("navDate", navDate.toString())
    put("unitNav", DecimalStorageCodec.encode(unitNav))
    put("accumulatedNav", accumulatedNav?.let { JsonPrimitive(DecimalStorageCodec.encode(it)) } ?: JsonNull)
    put("authority", authority.name)
    put("source", source.value)
    put("fetchedAt", fetchedAt.toString())
}

private fun JsonObject.toNavWitness(): NavRecord = NavRecord(
    FundId(requiredString("fundId")), LocalDate.parse(requiredString("navDate")), requiredDecimal("unitNav"),
    get("accumulatedNav")?.jsonPrimitive?.contentOrNull?.let(DecimalStorageCodec::decode),
    enumValueOf(requiredString("authority")), DataSourceId(requiredString("source")), Instant.parse(requiredString("fetchedAt")),
)

private fun Lot.toJson(): JsonObject = buildJsonObject {
    put("id", id.value)
    put("fundId", fundId.value)
    put("sourceTransactionId", sourceTransactionId.value)
    put("type", type.name)
    put("confirmationDate", confirmationDate.toString())
    put("originalShares", DecimalStorageCodec.encode(originalShares))
    put("remainingShares", DecimalStorageCodec.encode(remainingShares))
    put("grossCashOut", DecimalStorageCodec.encode(grossCashOut))
    put("netSubscriptionAmount", DecimalStorageCodec.encode(netSubscriptionAmount))
    put("purchaseFee", DecimalStorageCodec.encode(purchaseFee))
    put("costBasis", DecimalStorageCodec.encode(costBasis))
    put("unitNav", DecimalStorageCodec.encode(unitNav))
}

private fun JsonObject.toLot(): Lot = Lot(
    id = LotId(requiredString("id")),
    fundId = FundId(requiredString("fundId")),
    sourceTransactionId = TransactionId(requiredString("sourceTransactionId")),
    type = enumValueOf(requiredString("type")),
    confirmationDate = LocalDate.parse(requiredString("confirmationDate")),
    originalShares = requiredDecimal("originalShares"),
    remainingShares = requiredDecimal("remainingShares"),
    grossCashOut = requiredDecimal("grossCashOut"),
    netSubscriptionAmount = requiredDecimal("netSubscriptionAmount"),
    purchaseFee = requiredDecimal("purchaseFee"),
    costBasis = requiredDecimal("costBasis"),
    unitNav = requiredDecimal("unitNav"),
)

private fun LegacyLot.toJson(): JsonObject = buildJsonObject {
    put("id", id.value)
    put("fundId", fundId.value)
    put("remainingShares", DecimalStorageCodec.encode(remainingShares))
    put("costBasis", DecimalStorageCodec.encode(costBasis))
    if (trackingStartDate == null) {
        put("trackingStartDate", JsonNull)
    } else {
        put("trackingStartDate", trackingStartDate.toString())
    }
}

private fun JsonObject.toLegacyLot(): LegacyLot = LegacyLot(
    id = LotId(requiredString("id")),
    fundId = FundId(requiredString("fundId")),
    remainingShares = requiredDecimal("remainingShares"),
    costBasis = requiredDecimal("costBasis"),
    trackingStartDate = get("trackingStartDate")?.jsonPrimitive?.contentOrNull?.let(LocalDate::parse),
)

private fun LotConsumption.toJson(): JsonObject = buildJsonObject {
    put("lotId", lotId.value)
    put("shares", DecimalStorageCodec.encode(shares))
    put("allocatedCostBasis", DecimalStorageCodec.encode(allocatedCostBasis))
}

private fun JsonObject.toConsumption(): LotConsumption = LotConsumption(
    lotId = LotId(requiredString("lotId")),
    shares = requiredDecimal("shares"),
    allocatedCostBasis = requiredDecimal("allocatedCostBasis"),
)

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(get(name)?.jsonPrimitive?.contentOrNull) { "Missing JSON field: $name" }

private fun JsonObject.requiredDecimal(name: String): BigDecimal = DecimalStorageCodec.decode(requiredString(name))

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()

private fun JsonObject.requiredObject(name: String): JsonObject = requireNotNull(get(name)) { "Missing JSON field: $name" }.jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = requireNotNull(get(name)) { "Missing JSON field: $name" }.jsonArray

private val LEDGER_JSON: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
