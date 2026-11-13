package com.ndxinvest.database

import com.ndxinvest.ledger.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.*

public fun Fund.toLedgerEntity(): FundEntity = FundEntity(
    id = id.value,
    code = code,
    name = name,
    shortName = shortName,
    fundType = fundType,
    trackingIndex = trackingIndex,
    providerMetadataJson = JsonObject(providerMetadata.toSortedMap().mapValues { JsonPrimitive(it.value) }).toString(),
    cardStyle = cardStyle,
    moneyScale = precision?.moneyScale,
    shareScale = precision?.shareScale,
    navScale = precision?.navScale,
    roundingMode = precision?.roundingMode?.name,
)

public fun FundEntity.toLedgerDomain(): Fund {
    val precisionParts = listOf(moneyScale, shareScale, navScale, roundingMode)
    require(precisionParts.all { it == null } || precisionParts.none { it == null }) {
        "Fund precision must be either fully configured or fully absent"
    }
    return Fund(
        id = FundId(id),
        code = code,
        name = name,
        shortName = shortName,
        fundType = fundType,
        trackingIndex = trackingIndex,
        providerMetadata = LEDGER_JSON.parseToJsonElement(providerMetadataJson).jsonObject
            .mapValues { it.value.jsonPrimitive.content },
        cardStyle = cardStyle,
        precision = moneyScale?.let {
            FundPrecision(
                moneyScale = it,
                shareScale = requireNotNull(shareScale),
                navScale = requireNotNull(navScale),
                roundingMode = RoundingMode.valueOf(requireNotNull(roundingMode)),
            )
        },
    )
}


public fun LedgerTransaction.toLedgerEntity(): LedgerTransactionEntity = LedgerTransactionEntity(
    cashFlowDate = cashFlowDate?.toString(),
    id = id.value,
    fundId = fundId.value,
    type = type.name,
    status = status.name,
    origin = origin.name,
    authority = authority.name,
    applicationTime = applicationTime.toString(),
    tradeDate = tradeDate.toString(),
    navDate = navDate?.toString(),
    confirmationDate = confirmationDate?.toString(),
    settlementDate = settlementDate?.toString(),
    ruleVersionId = ruleVersionId.value,
    requestedAmountDecimal = requestedAmount.encodeOrNull(),
    grossAmountDecimal = grossAmount.encodeOrNull(),
    netAmountDecimal = netAmount.encodeOrNull(),
    sharesDecimal = shares.encodeOrNull(),
    unitNavDecimal = unitNav.encodeOrNull(),
    purchaseFeeDecimal = purchaseFee.encodeOrNull(),
    redemptionFeeDecimal = redemptionFee.encodeOrNull(),
    actualCashFlowDecimal = actualCashFlow.encodeOrNull(),
    isManualCorrected = isManualCorrected,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

public fun LedgerTransactionEntity.toLedgerDomain(): LedgerTransaction = LedgerTransaction(
    cashFlowDate = cashFlowDate?.let(LocalDate::parse),
    id = TransactionId(id),
    fundId = FundId(fundId),
    type = enumValueOf(type),
    status = enumValueOf(status),
    origin = enumValueOf(origin),
    authority = enumValueOf(authority),
    applicationTime = Instant.parse(applicationTime),
    tradeDate = LocalDate.parse(tradeDate),
    navDate = navDate?.let(LocalDate::parse),
    confirmationDate = confirmationDate?.let(LocalDate::parse),
    settlementDate = settlementDate?.let(LocalDate::parse),
    ruleVersionId = RuleVersionId(ruleVersionId),
    requestedAmount = requestedAmountDecimal.decodeOrNull(),
    grossAmount = grossAmountDecimal.decodeOrNull(),
    netAmount = netAmountDecimal.decodeOrNull(),
    shares = sharesDecimal.decodeOrNull(),
    unitNav = unitNavDecimal.decodeOrNull(),
    purchaseFee = purchaseFeeDecimal.decodeOrNull(),
    redemptionFee = redemptionFeeDecimal.decodeOrNull(),
    actualCashFlow = actualCashFlowDecimal.decodeOrNull(),
    isManualCorrected = isManualCorrected,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

public fun Lot.toLedgerEntity(): LotEntity = LotEntity(
    id = id.value,
    fundId = fundId.value,
    sourceTransactionId = sourceTransactionId.value,
    type = type.name,
    confirmationDate = confirmationDate.toString(),
    originalSharesDecimal = DecimalStorageCodec.encode(originalShares),
    remainingSharesDecimal = DecimalStorageCodec.encode(remainingShares),
    grossCashOutDecimal = DecimalStorageCodec.encode(grossCashOut),
    netSubscriptionAmountDecimal = DecimalStorageCodec.encode(netSubscriptionAmount),
    purchaseFeeDecimal = DecimalStorageCodec.encode(purchaseFee),
    costBasisDecimal = DecimalStorageCodec.encode(costBasis),
    unitNavDecimal = DecimalStorageCodec.encode(unitNav),
)

public fun LotEntity.toLedgerDomain(): Lot = Lot(
    id = LotId(id),
    fundId = FundId(fundId),
    sourceTransactionId = TransactionId(sourceTransactionId),
    type = enumValueOf(type),
    confirmationDate = LocalDate.parse(confirmationDate),
    originalShares = DecimalStorageCodec.decode(originalSharesDecimal),
    remainingShares = DecimalStorageCodec.decode(remainingSharesDecimal),
    grossCashOut = DecimalStorageCodec.decode(
        requireNotNull(grossCashOutDecimal) { "Migrated lot $id needs an audited gross-cash correction" },
    ),
    netSubscriptionAmount = DecimalStorageCodec.decode(
        requireNotNull(netSubscriptionAmountDecimal) { "Migrated lot $id needs an audited net-subscription correction" },
    ),
    purchaseFee = DecimalStorageCodec.decode(purchaseFeeDecimal),
    costBasis = DecimalStorageCodec.decode(costBasisDecimal),
    unitNav = DecimalStorageCodec.decode(unitNavDecimal),
)

public fun LegacyLot.toLedgerEntity(sourceTransactionId: TransactionId): LegacyLotEntity = LegacyLotEntity(
    id = id.value,
    fundId = fundId.value,
    sourceTransactionId = sourceTransactionId.value,
    remainingSharesDecimal = DecimalStorageCodec.encode(remainingShares),
    costBasisDecimal = DecimalStorageCodec.encode(costBasis),
    trackingStartDate = trackingStartDate?.toString(),
)

public fun LegacyLotEntity.toLedgerDomain(): LegacyLot = LegacyLot(
    id = LotId(id),
    fundId = FundId(fundId),
    remainingShares = DecimalStorageCodec.decode(remainingSharesDecimal),
    costBasis = DecimalStorageCodec.decode(costBasisDecimal),
    trackingStartDate = trackingStartDate?.let(LocalDate::parse),
)


public fun TransactionCorrection.toLedgerEntity(): TransactionCorrectionEntity {
    val (originalType, original) = originalValue.toStorage()
    val (correctedType, corrected) = correctedValue.toStorage()
    return TransactionCorrectionEntity(
        id = id.value,
        transactionId = transactionId.value,
        field = field.name,
        originalValueType = originalType,
        originalValue = original,
        correctedValueType = correctedType,
        correctedValue = corrected,
        source = source.name,
        correctedAt = correctedAt.toString(),
    )
}

public fun TransactionCorrectionEntity.toLedgerDomain(): TransactionCorrection = TransactionCorrection(
    id = CorrectionId(id),
    transactionId = TransactionId(transactionId),
    field = enumValueOf<TransactionCorrectionField>(field),
    originalValue = correctionValue(originalValueType, originalValue),
    correctedValue = correctionValue(correctedValueType, correctedValue),
    source = enumValueOf<CorrectionSource>(source),
    correctedAt = Instant.parse(correctedAt),
)

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

private fun BigDecimal?.encodeOrNull(): String? = this?.let(DecimalStorageCodec::encode)

private fun String?.decodeOrNull(): BigDecimal? = this?.let(DecimalStorageCodec::decode)

private val LEDGER_JSON: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
