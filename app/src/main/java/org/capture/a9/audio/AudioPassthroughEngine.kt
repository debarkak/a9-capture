package org.capture.a9.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioPassthroughEngine(private val context: Context) {
    private val TAG = "A9AudioEngine"
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val isRunning = AtomicBoolean(false)
    private var job: Job? = null

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRunning.get()) return

        try {
            val minRecordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
            val minTrackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
            
            // Ultra-low latency small buffer
            val bufferSize = maxOf(minRecordBufferSize, minTrackBufferSize, 1024)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord or AudioTrack failed to initialize")
                stop()
                return
            }

            audioRecord?.startRecording()
            audioTrack?.play()
            isRunning.set(true)

            job = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isRunning.get() && isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        audioTrack?.write(buffer, 0, read)
                    }
                }
            }
            Log.i(TAG, "Audio Passthrough Engine active at 48kHz Low-Latency")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Audio Passthrough Engine: ${e.message}")
            stop()
        }
    }

    fun stop() {
        isRunning.set(false)
        job?.cancel()
        job = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack: ${e.message}")
        }
        audioTrack = null
        Log.i(TAG, "Audio Passthrough Engine stopped")
    }

    fun isPlaying(): Boolean = isRunning.get()
}
