package dev.sathyajith.clipsync

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.getSystemService
import dev.sathyajith.clipsync.ui.theme.ClipSyncTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (intent.action == COPY_FROM_CB) {
            intent.action = null
            if (laptopIp != null && hasFocus) {
                val clipboardManager = applicationContext.getSystemService<ClipboardManager>()
                val txt = clipboardManager?.primaryClip?.getItemAt(0)?.text
                if (txt != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val response = khttp.post("http://$laptopIp/", data = txt.toString())
                            Log.i(CST, "Clipboard data sent. Response code: ${response.statusCode}")
                        } catch (e: Exception) {
                            // TODO: set ip to null if the server is unreachable
                            Log.e(CST, "Error while sending clipboard data: $e")
                        }
                    }
                }
            }
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Button(onClick = {
                            startServices(ServiceActions.START)
                        }) {
                            Text(text = "Start Service")
                        }
                        Button(onClick = {
                            startServices(ServiceActions.STOP)
                        }) {
                            Text(text = "Stop Service")
                        }
                    }
                }
            }
        }
    }

    private fun startServices(action: ServiceActions) {
        if (getServiceState(this) == ServiceState.STOPPED && action == ServiceActions.STOP) return
        Intent(this, DeviceDiscoveryService::class.java).also {
            it.action = action.name
            startForegroundService(it)
        }

        moveTaskToBack(true)
    }
}
