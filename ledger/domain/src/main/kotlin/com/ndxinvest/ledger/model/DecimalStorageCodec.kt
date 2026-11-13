package com.ndxinvest.ledger.model

import java.math.BigDecimal

public object DecimalStorageCodec {
    public fun encode(value: BigDecimal): String = value.toPlainString()

    public fun decode(value: String): BigDecimal {
        require(value.isNotBlank()) { "Stored decimal must not be blank" }
        return value.toBigDecimal()
    }
}
