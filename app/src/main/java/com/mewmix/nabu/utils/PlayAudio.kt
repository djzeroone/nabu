package com.mewmix.nabu.utils

import android.media.AudioFormat
import android.media.AudioFormat.CHANNEL_OUT_MONO
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object SpeechPlaybackCoordinator {
    private var generation = 0L
    private var activeOwner: Any? = null
    private var activeCancel: (() -> Unit)? = null

    fun begin(owner: Any, cancel: () -> Unit): Long {
        val previous = synchronized(this) {
            generation += 1
            val old = activeCancel
            activeOwner = owner
            activeCancel = cancel
            old to generation
        }
        previous.first?.invoke()
        return previous.second
    }

    @Synchronized
    fun isCurrent(owner: Any, token: Long): Boolean = activeOwner === owner && generation == token

    @Synchronized
    fun finish(owner: Any, token: Long) {
        if (activeOwner === owner && generation == token) {
            activeOwner = null
            activeCancel = null
        }
    }

    fun cancel(owner: Any) {
        val callback = synchronized(this) {
            if (activeOwner !== owner) return
            generation += 1
            activeOwner = null
            activeCancel.also { activeCancel = null }
        }
        callback?.invoke()
    }
}

private val manualPlaybackOwner = Any()

fun playAudio(audioData: FloatArray, sampleRate: Int, scope: CoroutineScope, onComplete: () -> Unit) {
    var audioTrack: AudioTrack? = null
    val token = SpeechPlaybackCoordinator.begin(manualPlaybackOwner) {
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
    scope.launch(Dispatchers.IO) {
        val channelConfig = CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()

        for (sample in audioData) {
            val safeSample = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            val pcmValue = (safeSample * Short.MAX_VALUE).toInt().toShort()
            shortBuffer.put(pcmValue)
        }

        val track = audioTrack ?: return@launch
        track.play()

        val pcmBytes = byteBuffer.array()
        val chunkSize = 4096
        var pos = 0
        while (pos < pcmBytes.size && SpeechPlaybackCoordinator.isCurrent(manualPlaybackOwner, token)) {
            val remaining = pcmBytes.size - pos
            val toWrite = min(chunkSize, remaining)
            val floatStart = pos / 2
            val written = track.write(pcmBytes, pos, toWrite)
            if (written > 0) {
                PcmTap.pushFloats(audioData, floatStart, written / 2)
                pos += written
            } else {
                break
            }
        }

        runCatching { track.stop() }
        runCatching { track.release() }
        audioTrack = null
        SpeechPlaybackCoordinator.finish(manualPlaybackOwner, token)

        withContext(Dispatchers.Main) {
            onComplete()
        }
    }
}
