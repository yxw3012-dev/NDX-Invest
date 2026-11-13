package com.ndxinvest.market.calculation

import com.ndxinvest.market.model.DrawdownBand
import com.ndxinvest.market.model.DrawdownBandId
import java.math.BigDecimal

public data class DrawdownBandTransition public constructor(
    public val activeBandIds: Set<DrawdownBandId>,
    public val enteredBand: DrawdownBand?,
)

public object DrawdownBandTransitionEvaluator {
    public fun evaluate(
        bands: List<DrawdownBand>,
        previousActiveBandIds: Set<DrawdownBandId>,
        currentDrawdownRate: BigDecimal,
    ): DrawdownBandTransition {
        require(currentDrawdownRate >= BigDecimal.ONE.negate() && currentDrawdownRate <= BigDecimal.ZERO) {
            "Current drawdown rate must be between -1 and 0"
        }
        require(bands.map(DrawdownBand::id).distinct().size == bands.size) {
            "Drawdown band ids must be unique"
        }
        require(bands.map(DrawdownBand::thresholdRate).distinct().size == bands.size) {
            "Drawdown band thresholds must be unique"
        }

        val active = bands.filter { currentDrawdownRate <= it.thresholdRate }
        val activeIds = active.mapTo(linkedSetOf(), DrawdownBand::id)
        val newlyEntered = active.filter { it.id !in previousActiveBandIds }
        return DrawdownBandTransition(
            activeBandIds = activeIds,
            enteredBand = newlyEntered.minByOrNull(DrawdownBand::thresholdRate),
        )
    }
}
