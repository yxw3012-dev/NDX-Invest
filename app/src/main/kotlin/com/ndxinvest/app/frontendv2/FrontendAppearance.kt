package com.ndxinvest.app.frontendv2

/** Presentation colors shared by the WebView surround and Android navigation bar. */
internal object FrontendAppearance {
    fun background(dark: Boolean, palette: String): Int = when {
        palette == "pink" && dark -> 0xff141518.toInt()
        palette == "pink" -> 0xfff5f5f5.toInt()
        dark -> 0xff0f1115.toInt()
        else -> 0xfff5f6f8.toInt()
    }
}
