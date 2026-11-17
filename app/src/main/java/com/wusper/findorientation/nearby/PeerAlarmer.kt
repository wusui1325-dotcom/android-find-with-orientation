package com.wusper.findorientation.nearby

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PeerAlarmer(context: Context) {
    private val app = context.applicationContext
    private var ringtone: Ringtone? = null
    private var stopJob: Job? = null
    private var tone: ToneGenerator? = null

    val active: Boolean get() = ringtone?.isPlaying == true || stopJob?.isActive == true

    fun start(scope: CoroutineScope, timeoutMs: Long, onTimeout: () -> Unit) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = runCatching { RingtoneManager.getRingtone(app, uri) }.getOrNull()?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            runCatching { play() }
        }
        vibrateAlarm(repeat = true)
        stopJob = scope.launch {
            delay(timeoutMs)
            stop()
            onTimeout()
        }
    }

    fun stop() {
        stopJob?.cancel()
        stopJob = null
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator()?.cancel()
    }

    fun pulse(amplitude: Int, durationMs: Long, tick: Boolean) {
        val effect = VibrationEffect.createOneShot(
            durationMs.coerceIn(20L, 200L),
            amplitude.coerceIn(1, 255)
        )
        runCatching { vibrator()?.vibrate(effect) }
        if (tick) {
            runCatching {
                val gen = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40).also { tone = it }
                gen.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            }
        }
    }

    fun release() {
        stop()
        runCatching { tone?.release() }
        tone = null
    }

    private fun vibrateAlarm(repeat: Boolean) {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
        val effect = VibrationEffect.createWaveform(pattern, if (repeat) 0 else -1)
        runCatching { vibrator()?.vibrate(effect) }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            app.getSystemService(Vibrator::class.java)
        }
    }
}
