package com.ndxinvest.app

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Device smoke is available for either flavor; it never clears app data or creates transactions in Room. */
class FrontendV2InstrumentedTest {
    @Test fun launcherNavigatesAndSubmitsOnlyAnInMemoryMockTrade() {
        // This legacy smoke submits a Mock amount. Never run it against the real BUY adapter.
        org.junit.Assume.assumeTrue(BuildConfig.FLAVOR == "prototype")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: WebView
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                view = (content.getChildAt(0) as FrameLayout).getChildAt(0) as WebView
            }
            fun evaluate(script: String): String {
                val done = CountDownLatch(1)
                var result = "null"
                scenario.onActivity { view.evaluateJavascript(script) { result = it; done.countDown() } }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                return result
            }
            val ready = "Boolean(window.V2Store && document.getElementById('framePortfolio').contentWindow.V2Portfolio)"
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (evaluate(ready) != "true" && System.nanoTime() < deadline) Thread.sleep(100)
            assertEquals("true", evaluate(ready))
            assertEquals("true", evaluate("!window.NDXAndroid && !window.NDXNativeStore && !window.NDXMock"))
            evaluate("document.querySelector('[data-page=\"持仓\"]').click()")
            Thread.sleep(400)
            assertEquals("true", evaluate("document.getElementById('framePortfolio').classList.contains('is-active')"))
            evaluate("document.getElementById('framePortfolio').contentDocument.querySelector('.action-button.buy').click()")
            Thread.sleep(400)
            assertEquals("true", evaluate("document.getElementById('buyPage').classList.contains('is-open')"))
            evaluate("window.__smokeHolding = JSON.stringify(V2Store.getState().holdings); window.__smokeCount = V2Store.getState().transactions.length; document.getElementById('buyAmount').value='100'; document.getElementById('buySubmit').click()")
            assertEquals("true", evaluate("V2Store.getState().transactions.length === __smokeCount + 1 && JSON.stringify(V2Store.getState().holdings) === __smokeHolding"))
            evaluate("document.getElementById('buyBack').click()")
            Thread.sleep(400)
            assertEquals("true", evaluate("!document.getElementById('buyPage').classList.contains('is-open')"))
        }
    }
}
