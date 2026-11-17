package com.wusper.findorientation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.wusper.findorientation.nearby.FindService
import com.wusper.findorientation.ui.FindScreen
import com.wusper.findorientation.ui.FindTheme

class MainActivity : ComponentActivity() {
    // Must be lazy: Activity context (and packageManager) is null during <init>.
    private val permissions: List<String> by lazy { buildPermissionList() }

    private fun buildPermissionList(): List<String> = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)) {
            add(Manifest.permission.UWB_RANGING)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 36) add("android.permission.RANGING")
    }

    private var missing by mutableStateOf<List<String>>(emptyList())
    private var batteryRestricted by mutableStateOf(true)

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        refreshBattery()
        lifecycleScope.launch {
            (application as FindApp).repository.state.collect { snap ->
                if (snap.running && snap.seeking) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        setContent {
            FindTheme {
                FindScreen(
                    repository = (application as FindApp).repository,
                    missingPermissions = missing,
                    onRequestPermissions = { request.launch(permissions.toTypedArray()) },
                    onStartFinding = {
                        val repo = (application as FindApp).repository
                        repo.setVisible(true)
                        repo.setSeeking(true)
                        startForegroundService(Intent(this, FindService::class.java))
                    },
                    onStopFinding = {
                        val repo = (application as FindApp).repository
                        repo.setSeeking(false)
                        if (!repo.state.value.resident) {
                            stopService(Intent(this, FindService::class.java))
                        }
                    },
                    onResidentChanged = { enabled ->
                        val repo = (application as FindApp).repository
                        repo.setResident(enabled)
                        if (enabled) {
                            startForegroundService(Intent(this, FindService::class.java))
                        } else if (!repo.state.value.seeking) {
                            stopService(Intent(this, FindService::class.java))
                        }
                    },
                    batteryRestricted = batteryRestricted,
                    onRequestBattery = {
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshBattery()
        (application as FindApp).repository.setRadarActive(true)
        updateKeepScreenOn()
    }

    override fun onPause() {
        (application as FindApp).repository.setRadarActive(false)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun updateKeepScreenOn() {
        val repo = (application as FindApp).repository
        val snap = repo.state.value
        if (snap.running && snap.seeking) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun refreshPermissions() {
        missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun refreshBattery() {
        batteryRestricted = !getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }
}
