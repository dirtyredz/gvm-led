package com.dirtyredz.gvmled

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dirtyredz.gvmled.feature.lights.LightsScreen
import com.dirtyredz.gvmled.ui.theme.GvmLedTheme

/** Android entry: lifecycle and composition only. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GvmLedTheme { LightsScreen() } }
    }
}
