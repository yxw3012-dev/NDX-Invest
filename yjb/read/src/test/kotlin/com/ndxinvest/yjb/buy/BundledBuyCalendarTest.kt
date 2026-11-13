package com.ndxinvest.yjb.buy

import kotlin.test.*
import java.time.LocalDate

class BundledBuyCalendarTest {
    @Test fun allFundCodesShareChinaUsDatesWithoutGuessingOtherYears() {
        for(code in listOf("160213","040046","270042","000001","123456","987654")) {
            val c=BundledBuyCalendar.forCode(code)
            assertEquals(code,c.fundCode)
            assertEquals(LocalDate.parse("2026-09-30"),c.expected(LocalDate.parse("2026-09-30"),CutoffType.BEFORE_1500))
            assertEquals(LocalDate.parse("2026-10-08"),c.expected(LocalDate.parse("2026-09-30"),CutoffType.AFTER_1500))
            assertFalse(c.isOpen(LocalDate.parse("2026-10-05")))
            assertFalse(c.isOpen(LocalDate.parse("2026-09-07")),"US holiday")
            assertTrue(c.isOpen(LocalDate.parse("2026-11-27")),"early close is still an open day")
            assertFailsWith<BuyFailure>{c.expected(LocalDate.parse("2027-01-01"),CutoffType.BEFORE_1500)}
        }
        assertEquals("shared-cn-us-2026",BundledBuyCalendar.forCode("123456").source)
    }
    @Test fun partial2027EvidenceNeverMakesUnknownDomesticDatesOpen() {
        val calendar=BundledBuyCalendar.forCode("123456")
        for(date in listOf("2027-01-01","2027-01-18","2027-02-15","2027-03-26","2027-05-31","2027-06-18","2027-07-05","2027-09-06","2027-11-25","2027-12-24")) {
            assertTrue(BuyNavDates.knownClosed(LocalDate.parse(date),calendar))
        }
        val unknown=LocalDate.parse("2027-02-08")
        assertFalse(calendar.covers(unknown))
        assertFalse(calendar.isClosed(unknown),"No guessed domestic closure")
        assertFailsWith<BuyFailure>{calendar.isOpen(unknown)}
        assertFailsWith<BuyFailure>{calendar.expected(unknown,CutoffType.BEFORE_1500)}
        assertFalse(calendar.isClosed(LocalDate.parse("2027-11-26")),"US early close is not a full closure")
        assertTrue(BuyNavDates.knownClosed(LocalDate.parse("2027-01-02"),calendar))
    }}


