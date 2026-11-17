package com.wusper.findorientation.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wusper.findorientation.model.IdentityStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!IdentityStore(context).resident) return
        ContextCompat.startForegroundService(context, Intent(context, FindService::class.java))
    }
}
