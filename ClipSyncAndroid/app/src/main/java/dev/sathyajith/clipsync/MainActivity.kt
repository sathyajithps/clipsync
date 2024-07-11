package dev.sathyajith.clipsync

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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

class MainActivity : ComponentActivity() {
    private lateinit var mClipSync: ClipSyncService
    private var mBound: Boolean = false
    private val connection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as ClipSyncService.LocalBinder
            mClipSync = binder.getService()
            mBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            mBound = false
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (intent.action == COPY_FROM_CB) {
            intent.action = null
            if (hasFocus && mBound && mClipSync.multicastLink != null) {
                val clipboardManager = applicationContext.getSystemService<ClipboardManager>()
                val txt = clipboardManager?.primaryClip?.getItemAt(0)?.text
                if (txt != null) {
                    mClipSync.multicastLink!!.sendData(txt.toString())
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

    override fun onStart() {
        super.onStart()
        Intent(this, ClipSyncService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
        mBound = false
    }

    private fun startServices(action: ServiceActions) {
        Intent(this, ClipSyncService::class.java).also {
            it.action = action.name
            startForegroundService(it)
        }

        moveTaskToBack(true)
    }
}
