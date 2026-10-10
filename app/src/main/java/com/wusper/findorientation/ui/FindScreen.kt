package com.wusper.findorientation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.PeerSighting
import com.wusper.findorientation.nearby.FindRepository
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun FindScreen(
    repository: FindRepository,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    batteryRestricted: Boolean,
    onRequestBattery: () -> Unit
) {
    val state by repository.state.collectAsState()
    var name by rememberSaveable { mutableStateOf(state.displayName) }
    val visiblePeers = state.peers.filter { it.withinTenMeters }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("方向尋找", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("10 米內、已安裝本客戶端的裝置", color = Color.White, fontSize = 14.sp)
        Text("本機 ${state.shortId} · ${if (state.uwbHardware) "UWB 可用" else "僅藍牙估算"}", color = Color.White, fontSize = 13.sp)
        val hw = state.hardware
        Text(
            "硬體支援：UWB ${if (hw.uwb) "✓" else "✗"} · WiFi RTT ${if (hw.wifiRtt) "✓" else "✗"} · BLE CS ${if (hw.bleChannelSounding) "✓" else "✗"} · GPS ${if (hw.gps) "✓" else "✗"} · 旋轉感測 ${if (hw.rotationSensor) "✓" else "✗"} · IR ${if (hw.irBlaster) "✓" else "✗"} · 超聲波 ✓ · 相機 ${if (hw.camera) "✓" else "✗"}",
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(12.dp))
        Radar(visiblePeers)
        Spacer(Modifier.height(8.dp))
        Text(state.status, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (missingPermissions.isNotEmpty()) {
            Text("尚未取得足夠權限。需要藍牙掃描、廣播、連接、精確位置、UWB 測距與通知。", fontSize = 14.sp)
            Button(onClick = onRequestPermissions, modifier = Modifier.padding(top = 8.dp)) { Text("授予權限") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("開始") }
                Button(onClick = onStop) { Text("停止") }
            }
        }
        ToggleRow("可被發現", state.visible) { repository.setVisible(it) }
        ToggleRow("常駐被發現", state.resident) { repository.setResident(it) }
        ToggleRow("尋找其他裝置", state.seeking) { repository.setSeeking(it) }
        ToggleRow("融合定位後備", state.geoFallback) { repository.setGeoFallback(it) }
        if (state.resident && batteryRestricted) {
            Button(onClick = onRequestBattery) { Text("允許背景運行") }
        }
        Text("顯示名稱", fontSize = 13.sp, color = Color.White)
        BasicTextField(
            value = name,
            onValueChange = {
                name = it.take(24)
                repository.setName(name)
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White, fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(Color(0xFF10232C), RoundedCornerShape(8.dp))
                .padding(12.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("10 米內客戶端 ${visiblePeers.size}", style = MaterialTheme.typography.titleMedium)
        if (visiblePeers.isEmpty()) {
            Text("雷達沒有目標。兩台裝置都要開啟客戶端、授予權限，並保持可被發現。", color = Color.White, fontSize = 14.sp)
        }
        visiblePeers.forEach { peer -> PeerRow(peer) }
        Spacer(Modifier.height(18.dp))
        Text(
            "手機頂端為前方（輪盤已加 30° 刻度）。琥珀色 UWB、綠色融合定位、藍色旋轉估計。若硬體支援，可進一步使用 WiFi RTT、BLE Channel Sounding、超聲波（人耳不可聞）或紅外。常駐被發現會在開機後恢復廣播，但強制停止或廠商省電仍可能把它殺掉。超過 10 米不會畫出。方向每秒至少更新 30 次。",
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Radar(peers: List<PeerSighting>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(Color(0xFF0A171E), RoundedCornerShape(16.dp))
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.42f
        val ring = Color(0xFF1E8F78)
        drawCircle(ring, radius, c, style = Stroke(2f))
        drawCircle(ring.copy(alpha = 0.7f), radius * 0.5f, c, style = Stroke(2f))
        drawLine(ring, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.5f)
        drawLine(ring, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.5f)
        // Degree scales / ticks every 30°
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val outer = Offset(c.x + (cos(rad) * radius).toFloat(), c.y + (sin(rad) * radius).toFloat())
            val inner = Offset(c.x + (cos(rad) * (radius - 12f)).toFloat(), c.y + (sin(rad) * (radius - 12f)).toFloat())
            drawLine(Color.White.copy(alpha = 0.6f), inner, outer, 1.5f)
        }
        // Cardinal labels via native canvas
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 28f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawText("前", c.x, c.y - radius - 8f, paint)
            drawText("右", c.x + radius + 16f, c.y + 8f, paint)
            drawText("後", c.x, c.y + radius + 28f, paint)
            drawText("左", c.x - radius - 16f, c.y + 8f, paint)
        }
        drawCircle(Color(0xFF39F3C3), 6f, c)
        peers.forEach { peer ->
            val meters = (peer.meters ?: 10f).coerceIn(0.4f, 10f)
            val r = radius * (meters / 10f)
            val deg = peer.azimuthDeg
            if (deg == null) {
                drawCircle(
                    Color(0xFFFFB020),
                    r,
                    c,
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
            } else {
                val rad = Math.toRadians(deg.toDouble() - 90.0)
                val at = Offset(c.x + (cos(rad) * r).toFloat(), c.y + (sin(rad) * r).toFloat())
                val color = when (peer.bearingSource) {
                    BearingSource.UWB -> Color(0xFFFFB020)
                    BearingSource.GEO -> Color(0xFFB6FF6A)
                    else -> Color(0xFF7FD0FF)
                }
                drawCircle(color, 9f, at)
                drawLine(color, c, at, 2f)
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerSighting) {
    val meters = peer.meters
    val angle = peer.azimuthDeg?.let { "${it.toInt()}°" } ?: "角度未鎖定"
    val source = when (peer.bearingSource) {
        BearingSource.UWB -> "UWB"
        BearingSource.GEO -> "融合定位 ±${peer.geoAccuracy?.toInt() ?: "?"} 米"
        BearingSource.SPIN -> "旋轉估計"
        BearingSource.NONE -> "只有距離"
        BearingSource.WIFI_RTT -> "WiFi RTT"
        BearingSource.BLE_CS -> "BLE Channel Sounding"
        BearingSource.ULTRASONIC -> "超聲波"
        BearingSource.IR -> "紅外"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(peer.name, fontSize = 16.sp)
        Text(
            "${"%.1f".format(meters ?: 0f)} 米 · $angle · $source · ${peer.rssi} dBm",
            color = Color.White,
            fontSize = 13.sp
        )
    }
}
