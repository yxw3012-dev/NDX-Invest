package com.ndxinvest.app.frontendv2

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.ByteArrayInputStream
import com.ndxinvest.app.BuildConfig
import com.ndxinvest.app.yjb.YjbReadController
import com.ndxinvest.app.market.MarketReadController
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Frozen presentation, with a closed native data/single-BUY adapter in Production only. */
internal class FrontendV2Host(private val activity: ComponentActivity) {
    private val root = FrameLayout(activity)
    private val webView = WebView(activity)
    private var loaded = false
    private var closed = false
    private var backPending = false
    private var lastBackAt = 0L
    private var pending: JsonObject? = null
    private val localUi = FrontendLocalUi(activity, { event, data ->
        if (!closed) {
            val script = if (event == "settings-info") "window.V2SettingsInfo?.receive(" + data.toString() + ");"
                else "window.V2Wallpaper?.receive(" + JSONObject.quote(event) + "," + data.toString() + ");"
            webView.evaluateJavascript(script, null)
        }
    }, { dark, palette ->
        val color = FrontendAppearance.background(dark, palette)
        root.setBackgroundColor(color)
        webView.setBackgroundColor(color)
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = color
        if (android.os.Build.VERSION.SDK_INT >= 29) activity.window.isNavigationBarContrastEnforced = false
        androidx.core.view.WindowInsetsControllerCompat(activity.window, root).isAppearanceLightNavigationBars = !dark
    })
    private val readController = if(BuildConfig.FLAVOR == "production")YjbReadController(activity,::renderReadState) else null
    private var pendingMarket:JsonObject?=null
    private val marketController=if(BuildConfig.FLAVOR=="production")MarketReadController(activity,::renderMarketState) else null

    private fun renderMarketState(payload:JsonObject) {
        if(closed)return
        pendingMarket=payload
        if(loaded)webView.evaluateJavascript("window.MarketReadApply(JSON.parse("+
            JSONObject.quote(payload.toString()).replace("\u2028","\\u2028").replace("\u2029","\\u2029")+"));",null)
    }

    private fun renderReadState(payload:JsonObject) {
        if(closed)return
        pending=payload
        if(loaded)webView.evaluateJavascript("window.YjbReadApply(JSON.parse("+
            JSONObject.quote(payload.toString()).replace("\u2028","\\u2028").replace("\u2029","\\u2029")+"));",null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(): FrameLayout {
        root.setBackgroundColor(Color.rgb(245, 246, 248))
        webView.setBackgroundColor(Color.rgb(245, 246, 248))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG && BuildConfig.FLAVOR == "prototype")
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.addJavascriptInterface(localUi, "NDXUiLocal")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // The frozen frontend only persists its theme preference.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun onPageFinished(view: WebView, url: String) {
                if (!closed) {loaded=true;pending?.let(::renderReadState);pendingMarket?.let(::renderMarketState);readController?.post("boot");marketController?.post("boot")}
            }
        }
        var html = activity.assets.open("frontend-v2/index.html").bufferedReader().use { it.readText() }
        readController?.let {
            webView.addJavascriptInterface(it,"NDXYjbRead")
            val adapter="window.NDX_LOCAL_PORTFOLIO=true;\n"+activity.assets.open("frontend-v2/production-read.js").bufferedReader().use { input->input.readText() }
            val marker="<script>window.V2Pages="
            check(html.indexOf(marker)>=0) { "Frozen V2 boot marker missing" }
            html=html.replace(marker,"<script>"+adapter+"</script>\n"+marker)
        }
        marketController?.let {
            webView.addJavascriptInterface(it,"NDXMarketRead")
            val adapter=activity.assets.open("frontend-v2/production-market.js").bufferedReader().use {input->input.readText()}
            html=html.replace("<script>window.V2Pages=","<script>"+adapter+"</script>\n<script>window.V2Pages=")
        }
        webView.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
        return root
    }

    fun navigateBack(onAtRoot: () -> Unit) {
        if (closed) return
        if (!loaded || backPending) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackAt < 600) return
        lastBackAt = now
        backPending = true
        val script = activity.assets.open("frontend-v2/android-back.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript(script) { handled ->
            backPending = false
            // Exit only on an explicit, successfully evaluated root result.
            if (!closed && handled == "false") onAtRoot()
        }
    }

    fun close() {
        closed = true
        loaded = false
        readController?.close()
        marketController?.close()
        localUi.close()
        webView.removeJavascriptInterface("NDXUiLocal")
        webView.removeJavascriptInterface("NDXYjbRead")
        webView.removeJavascriptInterface("NDXMarketRead")
        webView.stopLoading()
        root.removeView(webView)
        webView.destroy()
    }
}
