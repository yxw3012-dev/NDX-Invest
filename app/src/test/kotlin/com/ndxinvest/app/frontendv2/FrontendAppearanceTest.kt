package com.ndxinvest.app.frontendv2

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontendAppearanceTest {
    @Test fun paletteAndBrightnessAreIndependent() {
        assertEquals(0xfff5f6f8.toInt(), FrontendAppearance.background(false, "classic"))
        assertEquals(0xff0f1115.toInt(), FrontendAppearance.background(true, "classic"))
        assertEquals(0xfff5f5f5.toInt(), FrontendAppearance.background(false, "pink"))
        assertEquals(0xff141518.toInt(), FrontendAppearance.background(true, "pink"))
    }
    @Test fun unknownPaletteKeepsClassicColors() {
        assertEquals(FrontendAppearance.background(true, "classic"), FrontendAppearance.background(true, "unknown"))
        assertEquals(FrontendAppearance.background(false, "classic"), FrontendAppearance.background(false, "unknown"))
    }
}
