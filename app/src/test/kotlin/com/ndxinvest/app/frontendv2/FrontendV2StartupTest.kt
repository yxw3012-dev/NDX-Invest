package com.ndxinvest.app.frontendv2

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.ndxinvest.app.MainActivity
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.app.NdxInvestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrontendV2StartupTest {
    @Test fun launcherLoadsFrozenV2WithoutOpeningOrRewritingBusinessDatabase() {
        val application = ApplicationProvider.getApplicationContext<NdxInvestApplication>()
        val databaseFile = application.getDatabasePath("ndx-invest.db")
        assertFalse("Application startup must not create a ledger DB", databaseFile.exists())
        databaseFile.parentFile!!.mkdirs()
        val existingUserData = "sentinel representing an unopened existing user database".toByteArray()
        databaseFile.writeBytes(existingUserData)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as FrameLayout
        val webView = root.getChildAt(0) as WebView
        val loaded = shadowOf(webView).lastLoadDataWithBaseURL.data
        val expected = application.assets.open("frontend-v2/index.html").use { it.readBytes() }
        assertEquals("398b29d45cac2681c60c0acaaae987b61232e9e84bfee51ae6088ca48d964613",
            MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) })
        val adapter=application.assets.open("frontend-v2/production-read.js").bufferedReader().use { it.readText() }
        val market=application.assets.open("frontend-v2/production-market.js").bufferedReader().use {it.readText()}
        val canonical=expected.toString(Charsets.UTF_8)
        assertEquals(if(BuildConfig.FLAVOR=="production")canonical.replace("<script>window.V2Pages=","<script>"+adapter+"</script>\n<script>"+market+"</script>\n<script>window.V2Pages=") else canonical, loaded)
        assertFalse(loaded.contains("window.V2RecordPreview=true;"))
        assertEquals(BuildConfig.FLAVOR=="production",loaded.contains("window.YjbReadApply ="))
        assertEquals(BuildConfig.FLAVOR=="production",loaded.contains("window.MarketReadApply ="))
        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        controller.pause().stop().destroy()
        assertArrayEquals(existingUserData, databaseFile.readBytes())
        databaseFile.delete() // Only this test's sentinel in Robolectric's isolated temporary directory.
    }
}
