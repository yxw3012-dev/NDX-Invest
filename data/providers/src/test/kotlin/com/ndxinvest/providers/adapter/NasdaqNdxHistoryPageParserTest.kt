package com.ndxinvest.providers.adapter

import com.ndxinvest.market.model.MarketSourceId
import com.ndxinvest.providers.contract.ProviderFailureKind
import com.ndxinvest.providers.contract.ProviderPayloadException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NasdaqNdxHistoryPageParserTest {
    @Test
    fun `official Nasdaq page maps exact grouped decimals and sorts dates`() {
        val page = parser().parse(fixture("nasdaq_ndx_history_page_0.json"), FETCHED_AT)

        assertEquals(4, page.totalRecords)
        assertEquals(listOf(LocalDate.parse("2025-09-10"), LocalDate.parse("2025-09-11")), page.records.map { it.date })
        assertEquals(BigDecimal("23931.32"), page.records.first().open)
        assertEquals(BigDecimal("24016.53"), page.records.last().high)
        assertEquals(BigDecimal("23992.56"), page.records.last().close)
        assertEquals(SOURCE, page.records.last().source)
    }

    @Test
    fun `official Nasdaq auxiliary OHLC anomalies are preserved without rewriting valid closes`() {
        val page = NasdaqNdxHistoryPageParser(
            source = SOURCE,
            fromDate = LocalDate.parse("2015-12-25"),
            toDate = LocalDate.parse("2024-01-30"),
        ).parse(fixture("nasdaq_ndx_history_auxiliary_anomalies.json"), FETCHED_AT)

        assertEquals(7804, page.totalRecords)
        assertEquals(listOf(LocalDate.parse("2015-12-25"), LocalDate.parse("2024-01-30")), page.records.map { it.date })
        assertEquals(BigDecimal("0.00"), page.records.first().open)
        assertEquals(BigDecimal("4622.77"), page.records.first().close)
        assertEquals(BigDecimal("17596.27"), page.records.last().open)
        assertEquals(BigDecimal("17578.46"), page.records.last().high)
        assertEquals(BigDecimal("17476.71"), page.records.last().close)
    }

    @Test
    fun `Nasdaq page rejects wrong identity status malformed grouping and out of range date`() {
        val fixture = fixture("nasdaq_ndx_history_page_0.json")
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace("\"symbol\":\"NDX\"", "\"symbol\":\"COMP\""), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace("\"rCode\":200", "\"rCode\":400"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace("23,992.56", "2,3992.56"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace("09/11/2025", "09/12/2025"), FETCHED_AT)
        }
    }

    @Test
    fun `Nasdaq page rejects missing rows duplicate dates and unsafe record counts`() {
        val fixture = fixture("nasdaq_ndx_history_page_0.json")
        payloadFailure(ProviderFailureKind.MISSING_FIELD) {
            parser().parse(fixture.replace("\"rows\":", "\"renamedRows\":"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.CONFLICTING_DUPLICATE) {
            parser().parse(fixture.replace("09/10/2025", "09/11/2025"), FETCHED_AT)
        }
        payloadFailure(ProviderFailureKind.INVALID_VALUE) {
            parser().parse(fixture.replace("\"totalRecords\":4", "\"totalRecords\":50001"), FETCHED_AT)
        }
    }

    private fun parser(): NasdaqNdxHistoryPageParser = NasdaqNdxHistoryPageParser(SOURCE, FROM, TO)

    private fun payloadFailure(kind: ProviderFailureKind, block: () -> Unit): ProviderPayloadException {
        val error = assertIs<ProviderPayloadException>(runCatching(block).exceptionOrNull())
        assertEquals(kind, error.kind)
        return error
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    private companion object {
        val SOURCE: MarketSourceId = MarketSourceId("nasdaq")
        val FROM: LocalDate = LocalDate.parse("2025-09-08")
        val TO: LocalDate = LocalDate.parse("2025-09-11")
        val FETCHED_AT: Instant = Instant.parse("2026-09-13T18:44:33Z")
    }
}
