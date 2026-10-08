package com.wusper.findorientation.nearby

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.security.SecureRandom

data class UwbFix(
    val peerKey: String,
    val meters: Float?,
    val azimuthDeg: Float?,
    val elevationDeg: Float?
)

class UwbRanger(
    context: Context,
    private val scope: CoroutineScope,
    private val onFix: (UwbFix) -> Unit,
    private val onLog: (String) -> Unit
) {
    val hardware: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)
    private val manager: UwbManager? = if (hardware) runCatching { UwbManager.createInstance(context) }.getOrNull() else null
    private var controller: UwbControllerSessionScope? = null
    private var rangingJob: Job? = null
    var offer: Proto.SessionOffer? = null
        private set

    suspend fun openController(): Proto.SessionOffer? {
        val uwb = manager ?: return null
        return runCatching {
            rangingJob?.cancel()
            val session = uwb.controllerSessionScope()
            controller = session
            val channel = session.uwbComplexChannel
            val address = session.localAddress.address.copyOf(2)
            val built = Proto.SessionOffer(
                sessionId = SecureRandom().nextInt(0x7fffff) + 1,
                channel = channel.channel,
                preamble = channel.preambleIndex,
                controllerAddress = address,
                sessionKey = ByteArray(8).also { SecureRandom().nextBytes(it) }
            )
            offer = built
            onLog("UWB 控制端參數已備妥")
            built
        }.getOrElse {
            onLog("UWB 控制端失敗：${it.message}")
            null
        }
    }

    fun startWithPeers(peerAddresses: List<ByteArray>) {
        val session = controller ?: return
        val current = offer ?: return
        if (peerAddresses.isEmpty()) return
        rangingJob?.cancel()
        val params = params(current, peerAddresses, current.channel, current.preamble)
        rangingJob = scope.launch {
            runCatching { collect(session.prepareSession(params)) }
                .onFailure { onLog("UWB 測距中斷：${it.message}") }
        }
        onLog("UWB 正在測距，對端 ${peerAddresses.size}")
    }

    suspend fun beginControlee(incoming: Proto.SessionOffer): ByteArray? {
        val uwb = manager ?: return null
        rangingJob?.cancel()
        controller = null
        return runCatching {
            val controlee = uwb.controleeSessionScope()
            val mine = controlee.localAddress.address.copyOf(2)
            val params = params(incoming, listOf(incoming.controllerAddress), incoming.channel, incoming.preamble)
            rangingJob = scope.launch {
                runCatching { collect(controlee.prepareSession(params)) }
                    .onFailure { onLog("UWB 受控端中斷：${it.message}") }
            }
            onLog("UWB 受控端已加入測距")
            mine
        }.getOrElse {
            onLog("UWB 受控端失敗：${it.message}")
            null
        }
    }

    private fun params(
        offer: Proto.SessionOffer,
        peers: List<ByteArray>,
        channel: Int,
        preamble: Int
    ) = RangingParameters(
        uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
        sessionId = offer.sessionId,
        subSessionId = 0,
        sessionKeyInfo = offer.sessionKey.copyOf(8),
        subSessionKeyInfo = null,
        complexChannel = UwbComplexChannel(channel, preamble),
        peerDevices = peers.map { UwbDevice.createForAddress(it.copyOf(2)) },
        updateRateType = RangingParameters.RANGING_UPDATE_RATE_AUTOMATIC,
        uwbRangeDataNtfConfig = null,
        slotDurationMillis = RangingParameters.RANGING_SLOT_DURATION_2_MILLIS,
        isAoaDisabled = false
    )

    private suspend fun collect(flow: kotlinx.coroutines.flow.Flow<RangingResult>) {
        flow.collect { result ->
            when (result) {
                is RangingResult.RangingResultPosition -> {
                    val pos = result.position
                    onFix(
                        UwbFix(
                            peerKey = Proto.hex(result.device.address.address),
                            meters = pos.distance?.value,
                            azimuthDeg = pos.azimuth?.value,
                            elevationDeg = pos.elevation?.value
                        )
                    )
                }
                is RangingResult.RangingResultPeerDisconnected -> onLog("UWB 對端離開測距")
            }
        }
    }

    fun stop() {
        rangingJob?.cancel()
        rangingJob = null
        controller = null
        offer = null
    }

    fun close() {
        stop()
        runCatching { manager?.close() }
    }
}
