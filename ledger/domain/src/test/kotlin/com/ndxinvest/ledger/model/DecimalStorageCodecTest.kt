package com.ndxinvest.ledger.model

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalStorageCodecTest {
    @Test
    fun `decimal storage round trip preserves value and scale without Double`() {
        val original = BigDecimal("12345678901234567890.00123000")

        val restored = DecimalStorageCodec.decode(DecimalStorageCodec.encode(original))

        assertEquals(original, restored)
        assertEquals("12345678901234567890.00123000", DecimalStorageCodec.encode(original))
    }
}
