package dev.sathyajith.clipsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ScreenOnOffReceiver(private val multicastLink: MulticastLink): BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action.equals(Intent.ACTION_SCREEN_OFF)) {
            multicastLink.dispose()
            Log.i(CST, "Disposed Multicast Link")
        } else if (intent?.action.equals(Intent.ACTION_SCREEN_ON)) {
            multicastLink.create()
            Log.i(CST, "Created Multicast Link")
        }
    }

}