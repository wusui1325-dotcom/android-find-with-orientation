package com.wusper.findorientation.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wusper.findorientation.model.IdentityStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!IdentityStore(context).resident) return
        ContextCompat.startForegroundService(context, Intent(context, FindService::class.java))
    }
}
