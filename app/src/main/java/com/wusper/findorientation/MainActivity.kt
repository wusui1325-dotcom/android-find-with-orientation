package com.wusper.findorientation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.wusper.findorientation.nearby.FindService
import com.wusper.findorientation.ui.FindScreen
import com.wusper.findorientation.ui.FindTheme

class MainActivity : ComponentActivity() {
    private val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.UWB_RANGING)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.RANGING")
    }

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        setContent {
            FindTheme {
                FindScreen(
                    repository = (application as FindApp).repository,
                    missingPermissions = missing,
                    onRequestPermissions = { request.launch(permissions.toTypedArray()) },
                    onStart = {
                        startForegroundService(Intent(this, FindService::class.java))
                    },
                    onStop = { stopService(Intent(this, FindService::class.java)) }
                )
            }
        }
    }
}
