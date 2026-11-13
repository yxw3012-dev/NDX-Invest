package com.ndxinvest.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import com.ndxinvest.app.frontendv2.FrontendV2Host

class MainActivity : ComponentActivity() {
    private var frontend: FrontendV2Host? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        frontend = FrontendV2Host(this).also { setContentView(it.open()) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                frontend?.navigateBack { finish() } ?: finish()
            }
        })
    }

    override fun onDestroy() {
        frontend?.close()
        super.onDestroy()
    }

}
