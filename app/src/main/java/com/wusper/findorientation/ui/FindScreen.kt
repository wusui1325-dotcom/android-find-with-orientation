package com.wusper.findorientation.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wusper.findorientation.model.BearingSource
import com.wusper.findorientation.model.HardwareSupport
import com.wusper.findorientation.model.LastSeenPeer
import com.wusper.findorientation.model.PeerSighting
import com.wusper.findorientation.nearby.FindRepository
import kotlin.math.cos
import kotlin.math.sin

private val RadarBg = Color(0xFF0A171E)
private val UwbAmber = Color(0xFFFFB020)
private val GeoGreen = Color(0xFFB6FF6A)
private val SpinBlue = Color(0xFF7FD0FF)
private val RingTeal = Color(0xFF1E8F78)
private val CardBg = Color(0xFF10232C)
private val White = Color(0xFFF5FFFB)
private val Muted = Color(0xFFB7D0C8)

@Composable
fun FindScreen(
    repository: FindRepository,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onStartFinding: () -> Unit,
    onStopFinding: () -> Unit,
    onResidentChanged: (Boolean) -> Unit,
    batteryRestricted: Boolean,
    onRequestBattery: () -> Unit
) {
    val state by repository.state.collectAsState()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val incoming = state.incomingPing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text("方向尋找", style = MaterialTheme.typography.headlineMedium, color = White)
                Text(
                    "本機 ${state.displayName} · ${state.shortId}",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(onClick = { settingsOpen = !settingsOpen }) {
                Text(if (settingsOpen) "收起設定" else "設定", color = MaterialTheme.colorScheme.primary)
            }
        }

        if (incoming != null) {
            IncomingPingBanner(name = incoming.fromName, onStop = { repository.stopIncomingPing() })
        }

        if (missingPermissions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FirstRunCard(onRequestPermissions)
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        RadarHost(repository, state.running && state.seeking)
        Spacer(Modifier.height(8.dp))
        Legend()
        Spacer(Modifier.height(6.dp))
        Text(state.status, color = White, fontSize = 13.sp, lineHeight = 18.sp)
        Text(
            if (state.peers.isEmpty()) "10 米內還沒有其他裝置"
            else "10 米內 ${state.peers.size} 台",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.seeking && state.running) {
                OutlinedButton(onClick = onStopFinding, modifier = Modifier.weight(1f)) { Text("停止尋找") }
            } else {
                Button(onClick = onStartFinding, modifier = Modifier.weight(1f)) { Text("開始尋找") }
            }
        }

        Spacer(Modifier.height(8.dp))
        ToggleRow("可被發現", state.visible, enabled = !state.resident || state.visible) {
            repository.setVisible(it)
        }
        ToggleRow("尋找其他裝置", state.seeking && state.running) { on ->
            if (on) onStartFinding() else onStopFinding()
        }
        ToggleRow("常駐被發現", state.resident, onChange = onResidentChanged)
        if (state.resident && batteryRestricted) {
            Text(
                "建議允許忽略電池優化，否則部分手機在關屏後會停掉廣播。",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            OutlinedButton(onClick = onRequestBattery, modifier = Modifier.fillMaxWidth()) {
                Text("允許背景運行")
            }
        }

        if (!state.running && state.peers.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "兩部手機都安裝這個 App 後，按「開始尋找」，即可在約 10 米內看見對方方向。資料不會上網。",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        AnimatedVisibility(visible = settingsOpen) {
            SettingsPanel(
                repository = repository,
                name = state.displayName,
                headingCue = state.headingCue,
                geoFallback = state.geoFallback,
                hardware = state.hardware,
                uwbHardware = state.uwbHardware
            )
        }

        if (state.peers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("10 米內", style = MaterialTheme.typography.titleMedium, color = White)
            state.peers.forEach { peer ->
                PeerRow(
                    peer = peer,
                    selected = peer.id == state.selectedPeerId,
                    pinging = peer.id == state.outgoingPingTo,
                    onSelect = { repository.setSelectedPeer(peer.id) },
                    onPing = { repository.pingPeer(peer.id) },
                    onStopPing = { repository.stopOutgoingPing(peer.id) }
                )
            }
        } else if (state.running && state.seeking) {
            Spacer(Modifier.height(16.dp))
            Text(
                "雷達沒有目標。請確認兩邊都已開始、授予權限，並打開「可被發現」。",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        if (state.lastSeen.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("剛才見過", style = MaterialTheme.typography.titleMedium, color = White)
            state.lastSeen.forEach { item -> LastSeenRow(item) }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "手機頂端是前方。琥珀色是 UWB，綠色是融合定位（並顯示誤差），藍色是轉身後的旋轉估計。超過 10 米不會畫上雷達。常駐被發現會在開機或更新後恢復廣播，但強制停止後不會自己起來。",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun FirstRunCard(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(16.dp)
    ) {
        Text("開始之前", style = MaterialTheme.typography.titleMedium, color = White)
        Spacer(Modifier.height(8.dp))
        Text(
            "兩部手機都安裝「方向尋找」後，就能在約 10 米內看見對方的方向。資料只留在兩部手機之間，不會上網，也沒有帳號。",
            color = White,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(12.dp))
        Text("使用前請允許：", color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Bullet("藍牙：用來尋找附近同樣裝了這個 App 的手機")
        Bullet("精確位置：部分系統沒有位置權限，就不會回傳藍牙結果。這不是把你的位置傳到網上。")
        Bullet("測距與通知：有 UWB 時用來測角度；通知用來顯示常駐狀態與呼叫。")
        Spacer(Modifier.height(14.dp))
        Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("授予權限")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "授權後按「開始尋找」即可互相看見。若希望關著畫面也能被找到，再開「常駐被發現」，並允許忽略電池優化。",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•  ", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        Text(text, color = White, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun IncomingPingBanner(name: String, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A2208))
            .border(1.dp, UwbAmber, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("有人正在呼叫這部手機", color = White, fontWeight = FontWeight.Medium)
        Text(if (name.isBlank()) "請按停止鈴聲" else "$name 正在呼叫", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("停止鈴聲") }
    }
}

@Composable
private fun SettingsPanel(
    repository: FindRepository,
    name: String,
    headingCue: Boolean,
    geoFallback: Boolean,
    hardware: HardwareSupport,
    uwbHardware: Boolean
) {
    var draft by rememberSaveable(name) { mutableStateOf(name) }
    var capabilitiesOpen by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .padding(14.dp)
    ) {
        Text("本機設定", style = MaterialTheme.typography.titleMedium, color = White)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(24) },
            label = { Text("顯示名稱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                repository.setName(draft)
                focus.clearFocus()
            }),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                repository.setName(draft)
                focus.clearFocus()
            }) { Text("儲存名稱") }
        }
        ToggleRow("指向目標時震動提示", headingCue) { repository.setHeadingCue(it) }
        Text(
            "手機頂端對準對方時會震動，並可伴隨短促提示音。離得越近、對得越準，節奏越密。預設關閉。",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        ToggleRow("融合定位後備", geoFallback) { repository.setGeoFallback(it) }
        Text(
            "室外、誤差夠小時，用系統定位估算方向並標上誤差。室內衛星定位並不可靠，請不要依賴。",
            color = Muted,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { capabilitiesOpen = !capabilitiesOpen }) {
            Text(if (capabilitiesOpen) "收起本機能力" else "本機能力", color = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(visible = capabilitiesOpen) {
            CapabilityList(hardware, uwbHardware)
        }
    }
}

@Composable
private fun CapabilityList(hw: HardwareSupport, uwbHardware: Boolean) {
    Column(Modifier.padding(bottom = 4.dp)) {
        CapabilityLine("UWB", hw.uwb || uwbHardware, if (hw.uwb || uwbHardware) "用於測距與方位角" else "此機沒有")
        CapabilityLine("GPS／網絡定位", hw.gps, if (hw.gps) "用作融合定位後備（室外較準）" else "此機目前不可用")
        CapabilityLine("旋轉感測", hw.rotationSensor, if (hw.rotationSensor) "用於轉身估計方向" else "此機沒有")
        CapabilityLine("Wi-Fi RTT", hw.wifiRtt, if (hw.wifiRtt) "此機有硬體，本版尚未用於測距" else "此機沒有")
        CapabilityLine("藍牙通道探測", hw.bleChannelSounding, if (hw.bleChannelSounding) "此機有硬體，本版尚未用於測距" else "此機沒有或系統過舊")
        CapabilityLine("紅外發射", hw.irBlaster, if (hw.irBlaster) "此機有硬體，本版尚未用於測距" else "此機沒有")
        CapabilityLine("麥克風／喇叭", hw.microphone || hw.speaker, "本版尚未用超聲波測距")
        CapabilityLine("相機", hw.camera, if (hw.camera) "僅作為能力列出，本版不使用" else "此機沒有")
    }
}

@Composable
private fun CapabilityLine(title: String, present: Boolean, note: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(if (present) "有" else "無", color = if (present) GeoGreen else Muted, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        Column {
            Text(title, color = White, fontSize = 13.sp)
            Text(note, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (enabled) White else Muted, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Legend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendDot(UwbAmber, "UWB")
        LegendDot(GeoGreen, "融合定位")
        LegendDot(SpinBlue, "旋轉估計")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = White, fontSize = 12.sp)
    }
}

@Composable
private fun RadarHost(repository: FindRepository, active: Boolean) {
    var peers by remember { mutableStateOf(repository.latestRadarPeers()) }
    LaunchedEffect(active) {
        if (!active) {
            peers = repository.latestRadarPeers()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                peers = repository.latestRadarPeers()
            }
        }
    }
    Radar(peers)
}

@Composable
private fun Radar(peers: List<PeerSighting>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RadarBg)
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.40f
        drawCircle(RingTeal, radius, c, style = Stroke(2f))
        drawCircle(RingTeal.copy(alpha = 0.7f), radius * 0.5f, c, style = Stroke(2f))
        drawLine(RingTeal, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.5f)
        drawLine(RingTeal, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.5f)
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians((deg - 90).toDouble())
            val outer = Offset(c.x + (cos(rad) * radius).toFloat(), c.y + (sin(rad) * radius).toFloat())
            val inner = Offset(c.x + (cos(rad) * (radius - 12f)).toFloat(), c.y + (sin(rad) * (radius - 12f)).toFloat())
            drawLine(White.copy(alpha = 0.6f), inner, outer, 1.5f)
        }
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
                    UwbAmber,
                    r,
                    c,
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                )
            } else {
                val rad = Math.toRadians(deg.toDouble() - 90.0)
                val at = Offset(c.x + (cos(rad) * r).toFloat(), c.y + (sin(rad) * r).toFloat())
                val color = when (peer.bearingSource) {
                    BearingSource.UWB -> UwbAmber
                    BearingSource.GEO -> GeoGreen
                    else -> SpinBlue
                }
                drawCircle(color, 9f, at)
                drawLine(color, c, at, 2f)
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: PeerSighting,
    selected: Boolean,
    pinging: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onStopPing: () -> Unit
) {
    val meters = peer.meters
    val angle = peer.azimuthDeg?.let { "${it.toInt()}°" } ?: "角度未鎖定"
    val source = sourceLabel(peer.bearingSource, peer.geoAccuracy)
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(if (selected) Color(0xFF163038) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, RingTeal, shape) else Modifier)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(peer.name, fontSize = 16.sp, color = White, fontWeight = FontWeight.Medium)
        Text(
            "${"%.1f".format(meters ?: 0f)} 米 · $angle · $source · ${peer.rssi} dBm",
            color = White,
            fontSize = 13.sp
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pinging) {
                OutlinedButton(onClick = onStopPing) { Text("停止呼叫") }
            } else {
                Button(onClick = onPing) { Text("呼叫對方") }
            }
        }
    }
}

@Composable
private fun LastSeenRow(item: LastSeenPeer) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val ago = ((now - item.lastSeenElapsedMs) / 1000L).coerceAtLeast(0)
    val agoText = when {
        ago < 5 -> "剛剛"
        ago < 60 -> "${ago} 秒前"
        else -> "${ago / 60} 分鐘前"
    }
    val dist = item.lastMeters?.let { "%.1f 米".format(it) } ?: "距離未知"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(item.name, fontSize = 15.sp, color = White)
        Text(
            "$dist · ${sourceLabel(item.scheme, null)} · $agoText",
            color = Muted,
            fontSize = 13.sp
        )
    }
}

private fun sourceLabel(source: BearingSource, geoAccuracy: Float?): String = when (source) {
    BearingSource.UWB -> "UWB"
    BearingSource.GEO -> "融合定位 ±${geoAccuracy?.toInt() ?: "?"} 米"
    BearingSource.SPIN -> "旋轉估計"
    BearingSource.NONE -> "只有距離"
    BearingSource.WIFI_RTT -> "Wi-Fi RTT"
    BearingSource.BLE_CS -> "藍牙通道探測"
    BearingSource.ULTRASONIC -> "超聲波"
    BearingSource.IR -> "紅外"
}
