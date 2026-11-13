package com.ndxinvest.providers.json

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class StrictJsonReaderTest {
    @Test
    fun `JSON Pointer reads literal dotted keys arrays and escaped member names`() {
        val reader = StrictJsonReader.parse(
            """
            {
              "data": [{
                "metric.long.term.value": 0.6093189964157706,
                "a/b": "slash",
                "til~de": "tilde"
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            BigDecimal("0.6093189964157706"),
            reader.requiredDecimal("/data/0/metric.long.term.value"),
        )
        assertEquals("slash", reader.requiredString("/data/0/a~1b"))
        assertEquals("tilde", reader.requiredString("/data/0/til~0de"))
    }

    @Test
    fun `offset timestamp keeps the source market calendar date`() {
        val reader = StrictJsonReader.parse("""{"date":"2026-09-01T00:00:00-04:00"}""")

        assertEquals(LocalDate.parse("2026-09-01"), reader.requiredOffsetDate("/date"))
    }
}
