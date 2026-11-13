package com.ndxinvest.ledger.model

@JvmInline
public value class FundId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "FundId must not be blank" }
    }
}

@JvmInline
public value class LotId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "LotId must not be blank" }
    }
}

@JvmInline
public value class TransactionId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "TransactionId must not be blank" }
    }
}

@JvmInline
public value class RuleVersionId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "RuleVersionId must not be blank" }
    }
}

@JvmInline
public value class RecurringPlanId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "RecurringPlanId must not be blank" }
    }
}

@JvmInline
public value class LedgerEntryId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "LedgerEntryId must not be blank" }
    }
}

@JvmInline
public value class CorrectionId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "CorrectionId must not be blank" }
    }
}

@JvmInline
public value class DataSourceId public constructor(public val value: String) {
    init {
        require(value.isNotBlank()) { "DataSourceId must not be blank" }
    }
}
