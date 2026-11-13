package com.ndxinvest.market.calculation

import com.ndxinvest.market.model.DrawdownBand
import com.ndxinvest.market.model.DrawdownBandId
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DrawdownBandTransitionEvaluatorTest {
    private val bands = listOf(
        band("first", "-0.10", "1000"),
        band("second", "-0.20", "2000"),
        band("third", "-0.30", "4000"),
    )

    @Test
    fun `first entry triggers once and staying in the band does not repeat`() {
        val entered = DrawdownBandTransitionEvaluator.evaluate(bands, emptySet(), decimal("-0.102"))
        assertEquals("first", entered.enteredBand?.id?.value)

        val repeated = DrawdownBandTransitionEvaluator.evaluate(
            bands,
            entered.activeBandIds,
            decimal("-0.136"),
        )
        assertNull(repeated.enteredBand)
        assertEquals(setOf(DrawdownBandId("first")), repeated.activeBandIds)
    }

    @Test
    fun `leaving a band rearms it and entering it again triggers`() {
        val inside = DrawdownBandTransitionEvaluator.evaluate(bands, emptySet(), decimal("-0.12"))
        val left = DrawdownBandTransitionEvaluator.evaluate(bands, inside.activeBandIds, decimal("-0.08"))
        assertNull(left.enteredBand)
        assertEquals(emptySet(), left.activeBandIds)

        val reentered = DrawdownBandTransitionEvaluator.evaluate(bands, left.activeBandIds, decimal("-0.11"))
        assertEquals("first", reentered.enteredBand?.id?.value)
    }

    @Test
    fun `jumping across several thresholds emits only the deepest newly entered band`() {
        val result = DrawdownBandTransitionEvaluator.evaluate(bands, emptySet(), decimal("-0.35"))
        assertEquals("third", result.enteredBand?.id?.value)
        assertEquals(setOf("first", "second", "third"), result.activeBandIds.map { it.value }.toSet())
    }

    @Test
    fun `rebound from a deeper band does not emit a shallower alert`() {
        val result = DrawdownBandTransitionEvaluator.evaluate(
            bands,
            setOf(DrawdownBandId("first"), DrawdownBandId("second")),
            decimal("-0.15"),
        )
        assertNull(result.enteredBand)
        assertEquals(setOf(DrawdownBandId("first")), result.activeBandIds)
    }

    @Test
    fun `duplicate thresholds and positive thresholds fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            DrawdownBandTransitionEvaluator.evaluate(
                listOf(band("a", "-0.10", "1"), band("b", "-0.10", "2")),
                emptySet(),
                decimal("-0.11"),
            )
        }
        assertFailsWith<IllegalArgumentException> { band("bad", "0.10", "1") }
    }

    private fun band(id: String, threshold: String, amount: String): DrawdownBand = DrawdownBand(
        id = DrawdownBandId(id),
        thresholdRate = decimal(threshold),
        plannedAmount = decimal(amount),
    )

    private fun decimal(value: String): BigDecimal = BigDecimal(value)
}
