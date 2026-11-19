package com.bitzer.rmainspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.bitzer.rmainspector.data.ServiceLocator
import com.bitzer.rmainspector.ui.RmaInspectorApp
import com.bitzer.rmainspector.ui.theme.RmaInspectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Load the stored session before any screen makes a server call.
        val auth = ServiceLocator.auth(this)
        // Send queued uploads while the app runs: on a timer and right after every sign-in.
        val runner = ServiceLocator.uploadQueueRunner(this)
        runner.start(lifecycleScope)
        lifecycleScope.launch { auth.session.collect { if (it != null) runner.kick() } }
        setContent {
            RmaInspectorTheme {
                RmaInspectorApp()
            }
        }
    }
}
