package com.bitzer.rmainspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bitzer.rmainspector.ui.RmaInspectorApp
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RmaInspectorTheme {
                RmaInspectorApp()
            }
        }
    }
}
