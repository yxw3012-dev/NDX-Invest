package com.ndxinvest.ledger.persistence

import com.ndxinvest.database.toLedgerEntity
import com.ndxinvest.database.toLedgerDomain

import com.ndxinvest.database.FundEntity
import com.ndxinvest.database.FundRuleVersionEntity
import com.ndxinvest.database.LedgerEntryEntity
import com.ndxinvest.database.LedgerTransactionEntity
import com.ndxinvest.database.LegacyLotEntity
import com.ndxinvest.database.LotEntity
import com.ndxinvest.database.NavRecordEntity
import com.ndxinvest.database.RecurringExecutionEntity
import com.ndxinvest.database.RecurringPlanEntity
import com.ndxinvest.database.TransactionCorrectionEntity
import com.ndxinvest.ledger.calculation.AssumedPurchaseReversed
import com.ndxinvest.ledger.calculation.LedgerEntry
import com.ndxinvest.ledger.calculation.LegacyLotOpened
import com.ndxinvest.ledger.calculation.LegacyLotRedeemed
import com.ndxinvest.ledger.calculation.LotConsumption
import com.ndxinvest.ledger.calculation.LotsRedeemed
import com.ndxinvest.ledger.calculation.RealLotOpened
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseLotCorrected
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseAmountsCorrected
import com.ndxinvest.ledger.calculation.UnconsumedPurchaseNavCorrected
import com.ndxinvest.ledger.model.CorrectionId
import com.ndxinvest.ledger.model.CorrectionSource
import com.ndxinvest.ledger.model.CorrectionValue
import com.ndxinvest.ledger.model.DataAuthority
import com.ndxinvest.ledger.model.DataSourceId
import com.ndxinvest.ledger.model.DecimalStorageCodec
import com.ndxinvest.ledger.model.Fund
import com.ndxinvest.ledger.model.FundId
import com.ndxinvest.ledger.model.FundPrecision
import com.ndxinvest.ledger.model.FundRuleField
import com.ndxinvest.ledger.model.FundRuleVersion
import com.ndxinvest.ledger.model.HoldingPeriodBoundary
import com.ndxinvest.ledger.model.HoldingPeriodUnit
import com.ndxinvest.ledger.model.LedgerEntryId
import com.ndxinvest.ledger.model.LedgerTransaction
import com.ndxinvest.ledger.model.LegacyLot
import com.ndxinvest.ledger.model.Lot
import com.ndxinvest.ledger.model.LotAllocationPolicy
import com.ndxinvest.ledger.model.LotId
import com.ndxinvest.ledger.model.LotType
import com.ndxinvest.ledger.model.NavRecord
import com.ndxinvest.ledger.model.PurchaseFeeRule
import com.ndxinvest.ledger.model.PurchaseFeeTier
import com.ndxinvest.ledger.model.RecurringExecutionKey
import com.ndxinvest.ledger.model.RecurringPlan
import com.ndxinvest.ledger.model.RecurringPlanId
import com.ndxinvest.ledger.model.RedemptionFeeSchedule
import com.ndxinvest.ledger.model.RedemptionFeeTier
import com.ndxinvest.ledger.model.RuleExpression
import com.ndxinvest.ledger.model.RuleVersionId
import com.ndxinvest.ledger.model.TransactionCorrection
import com.ndxinvest.ledger.model.TransactionCorrectionField
import com.ndxinvest.ledger.model.TransactionId
import com.ndxinvest.ledger.model.TransactionOrigin
import com.ndxinvest.ledger.model.TransactionStatus
import com.ndxinvest.ledger.model.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun Fund.toEntity(): FundEntity = toLedgerEntity()

internal fun FundEntity.toDomain(): Fund = toLedgerDomain()

internal fun FundRuleVersion.toEntity(): FundRuleVersionEntity = FundRuleVersionEntity(
    id = id.value,
    fundId = fundId.value,
    effectiveFrom = effectiveFrom.toString(),
    effectiveTo = effectiveTo?.toString(),
    cutoffTime = cutoffTime.toString(),
    purchaseConfirmationRule = purchaseConfirmationRule.value,
    redemptionConfirmationRule = redemptionConfirmationRule.value,
    openDayRule = openDayRule.value,
    purchaseFeeRuleJson = purchaseFeeRule.toJson().toString(),
    redemptionFeeScheduleJson = redemptionFeeSchedule.toJson().toString(),
    allocationPolicy = allocationPolicy.name,
    authority = authority.name,
    source = source.value,
    fetchedAt = fetchedAt.toString(),
    manualOverrideFieldsJson = JsonArray(
        manualOverrideFields.map { it.name }.sorted().map(::JsonPrimitive),
    ).toString(),
)

internal fun FundRuleVersionEntity.toDomain(): FundRuleVersion = FundRuleVersion(
    id = RuleVersionId(id),
    fundId = FundId(fundId),
    effectiveFrom = LocalDate.parse(effectiveFrom),
    effectiveTo = effectiveTo?.let(LocalDate::parse),
    cutoffTime = LocalTime.parse(cutoffTime),
    purchaseConfirmationRule = RuleExpression(purchaseConfirmationRule),
    redemptionConfirmationRule = RuleExpression(redemptionConfirmationRule),
    openDayRule = RuleExpression(openDayRule),
    purchaseFeeRule = purchaseFeeRuleFromJson(purchaseFeeRuleJson),
    redemptionFeeSchedule = redemptionFeeScheduleFromJson(redemptionFeeScheduleJson),
    allocationPolicy = enumValueOf(allocationPolicy),
    authority = enumValueOf(authority),
    source = DataSourceId(source),
    fetchedAt = Instant.parse(fetchedAt),
    manualOverrideFields = LEDGER_JSON.parseToJsonElement(manualOverrideFieldsJson).jsonArray
        .map { enumValueOf<FundRuleField>(it.jsonPrimitive.content) }
        .toSet(),
)

internal fun NavRecord.toEntity(): NavRecordEntity = NavRecordEntity(
    fundId = fundId.value,
    navDate = navDate.toString(),
    unitNavDecimal = DecimalStorageCodec.encode(unitNav),
    accumulatedNavDecimal = accumulatedNav.encodeOrNull(),
    authority = authority.name,
    source = source.value,
    fetchedAt = fetchedAt.toString(),
)

internal fun NavRecordEntity.toDomain(): NavRecord = NavRecord(
    fundId = FundId(fundId),
    navDate = LocalDate.parse(navDate),
    unitNav = DecimalStorageCodec.decode(unitNavDecimal),
    accumulatedNav = accumulatedNavDecimal.decodeOrNull(),
    authority = enumValueOf(authority),
    source = DataSourceId(source),
    fetchedAt = Instant.parse(fetchedAt),
)

internal fun RecurringPlan.toEntity(): RecurringPlanEntity = RecurringPlanEntity(
    id = id.value,
    fundId = fundId.value,
    amountDecimal = DecimalStorageCodec.encode(amount),
    frequency = frequency,
    scheduleRule = scheduleRule,
    nonOpenDayPolicy = nonOpenDayPolicy,
    enabled = enabled,
    lastGeneratedDate = lastGeneratedDate?.toString(),
)

internal fun RecurringPlanEntity.toDomain(): RecurringPlan = RecurringPlan(
    id = RecurringPlanId(id),
    fundId = FundId(fundId),
    amount = DecimalStorageCodec.decode(amountDecimal),
    frequency = frequency,
    scheduleRule = scheduleRule,
    nonOpenDayPolicy = nonOpenDayPolicy,
    enabled = enabled,
    lastGeneratedDate = lastGeneratedDate?.let(LocalDate::parse),
)

internal fun LedgerTransaction.toEntity(): LedgerTransactionEntity = toLedgerEntity()

internal fun LedgerTransactionEntity.toDomain(): LedgerTransaction = toLedgerDomain()

internal fun Lot.toEntity(): LotEntity = toLedgerEntity()

internal fun LotEntity.toDomain(): Lot = toLedgerDomain()

internal fun LegacyLot.toEntity(sourceTransactionId: TransactionId): LegacyLotEntity = toLedgerEntity(sourceTransactionId)

internal fun LegacyLotEntity.toDomain(): LegacyLot = toLedgerDomain()

internal fun RecurringExecutionKey.toEntity(transactionId: TransactionId): RecurringExecutionEntity =
    RecurringExecutionEntity(planId.value, scheduledTradeDate.toString(), transactionId.value)

internal fun TransactionCorrection.toEntity(): TransactionCorrectionEntity = toLedgerEntity()

internal fun TransactionCorrectionEntity.toDomain(): TransactionCorrection = toLedgerDomain()

public object LedgerEntryCodec {
    public fun encode(entry: LedgerEntry): LedgerEntryEntity = com.ndxinvest.database.LedgerEventStorageCodec.encode(entry)
    public fun decode(entity: LedgerEntryEntity): LedgerEntry = com.ndxinvest.database.LedgerEventStorageCodec.decode(entity)
}

internal fun NavRecord.toWitnessJson(): JsonObject = buildJsonObject {
    put("fundId", fundId.value)
    put("navDate", navDate.toString())
    put("unitNav", DecimalStorageCodec.encode(unitNav))
    put("accumulatedNav", accumulatedNav?.let { JsonPrimitive(DecimalStorageCodec.encode(it)) } ?: JsonNull)
    put("authority", authority.name)
    put("source", source.value)
    put("fetchedAt", fetchedAt.toString())
}

internal fun JsonObject.toNavWitness(): NavRecord = NavRecord(
    FundId(requiredString("fundId")), LocalDate.parse(requiredString("navDate")), requiredDecimal("unitNav"),
    get("accumulatedNav")?.jsonPrimitive?.contentOrNull?.let(DecimalStorageCodec::decode),
    enumValueOf(requiredString("authority")), DataSourceId(requiredString("source")), Instant.parse(requiredString("fetchedAt")),
)

internal fun Lot.toJson(): JsonObject = buildJsonObject {
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

internal fun JsonObject.toLot(): Lot = Lot(
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

internal fun LegacyLot.toJson(): JsonObject = buildJsonObject {
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

internal fun JsonObject.toLegacyLot(): LegacyLot = LegacyLot(
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

private fun PurchaseFeeRule.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        PurchaseFeeRule.None -> put("type", "NONE")
        is PurchaseFeeRule.Rate -> {
            put("type", "RATE")
            put("value", DecimalStorageCodec.encode(rate))
        }
        is PurchaseFeeRule.Fixed -> {
            put("type", "FIXED")
            put("value", DecimalStorageCodec.encode(amount))
        }
        is PurchaseFeeRule.Tiered -> {
            put("type", "TIERED")
            put(
                "tiers",
                buildJsonArray {
                    tiers.forEach { tier ->
                        add(
                            buildJsonObject {
                                put(
                                    "minGrossAmountInclusive",
                                    DecimalStorageCodec.encode(tier.minGrossAmountInclusive),
                                )
                                val maxGrossAmountExclusive = tier.maxGrossAmountExclusive
                                if (maxGrossAmountExclusive == null) {
                                    put("maxGrossAmountExclusive", JsonNull)
                                } else {
                                    put(
                                        "maxGrossAmountExclusive",
                                        DecimalStorageCodec.encode(maxGrossAmountExclusive),
                                    )
                                }
                                put("feeRule", tier.feeRule.toJson())
                            },
                        )
                    }
                },
            )
        }
    }
}

private fun purchaseFeeRuleFromJson(value: String): PurchaseFeeRule {
    val json = LEDGER_JSON.parseToJsonElement(value).jsonObject
    return when (json.requiredString("type")) {
        "NONE" -> PurchaseFeeRule.None
        "RATE" -> PurchaseFeeRule.Rate(json.requiredDecimal("value"))
        "FIXED" -> PurchaseFeeRule.Fixed(json.requiredDecimal("value"))
        "TIERED" -> PurchaseFeeRule.Tiered(
            json.getValue("tiers").jsonArray.map { element ->
                val tier = element.jsonObject
                PurchaseFeeTier(
                    minGrossAmountInclusive = tier.requiredDecimal("minGrossAmountInclusive"),
                    maxGrossAmountExclusive = tier["maxGrossAmountExclusive"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.content
                        ?.let(DecimalStorageCodec::decode),
                    feeRule = purchaseFeeRuleFromJson(tier.getValue("feeRule").toString()),
                )
            },
        )
        else -> error("Unsupported purchase fee rule")
    }
}

private fun RedemptionFeeSchedule.toJson(): JsonObject = buildJsonObject {
    put(
        "tiers",
        buildJsonArray {
            tiers.forEach { tier ->
                add(
                    buildJsonObject {
                        val boundary = tier.maxHoldingPeriodExclusive
                        if (boundary == null) {
                            put("maxHoldingPeriodExclusive", JsonNull)
                        } else {
                            put(
                                "maxHoldingPeriodExclusive",
                                buildJsonObject {
                                    put("value", boundary.value)
                                    put("unit", boundary.unit.name)
                                },
                            )
                        }
                        put("rate", DecimalStorageCodec.encode(tier.rate))
                    },
                )
            }
        },
    )
}

private fun redemptionFeeScheduleFromJson(value: String): RedemptionFeeSchedule {
    val tiers = LEDGER_JSON.parseToJsonElement(value).jsonObject.requiredArray("tiers").map { element ->
        val tier = element.jsonObject
        RedemptionFeeTier(
            maxHoldingPeriodExclusive = when {
                "maxHoldingPeriodExclusive" in tier -> tier["maxHoldingPeriodExclusive"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonObject
                    ?.let { boundary ->
                        HoldingPeriodBoundary(
                            value = boundary.requiredString("value").toLong(),
                            unit = enumValueOf<HoldingPeriodUnit>(boundary.requiredString("unit")),
                        )
                    }
                "maxHoldingDaysExclusive" in tier -> tier["maxHoldingDaysExclusive"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toLong()
                    ?.let { HoldingPeriodBoundary(it, HoldingPeriodUnit.DAYS) }
                else -> error("Missing redemption fee tier boundary")
            },
            rate = tier.requiredDecimal("rate"),
        )
    }
    return RedemptionFeeSchedule(tiers)
}

private fun CorrectionValue.toStorage(): Pair<String, String?> = when (this) {
    CorrectionValue.Missing -> "MISSING" to null
    is CorrectionValue.Decimal -> "DECIMAL" to DecimalStorageCodec.encode(value)
    is CorrectionValue.Date -> "DATE" to value.toString()
}

private fun correctionValue(type: String, value: String?): CorrectionValue = when (type) {
    "MISSING" -> CorrectionValue.Missing.also { require(value == null) { "Missing correction must not carry a value" } }
    "DECIMAL" -> CorrectionValue.Decimal(DecimalStorageCodec.decode(requireNotNull(value)))
    "DATE" -> CorrectionValue.Date(LocalDate.parse(requireNotNull(value)))
    else -> error("Unsupported correction value type: $type")
}

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(get(name)?.jsonPrimitive?.contentOrNull) { "Missing JSON field: $name" }

private fun JsonObject.requiredDecimal(name: String): BigDecimal = DecimalStorageCodec.decode(requiredString(name))

private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()

private fun JsonObject.requiredObject(name: String): JsonObject = requireNotNull(get(name)) { "Missing JSON field: $name" }.jsonObject

private fun JsonObject.requiredArray(name: String): JsonArray = requireNotNull(get(name)) { "Missing JSON field: $name" }.jsonArray

private fun BigDecimal?.encodeOrNull(): String? = this?.let(DecimalStorageCodec::encode)

private fun String?.decodeOrNull(): BigDecimal? = this?.let(DecimalStorageCodec::decode)

private val LEDGER_JSON: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
