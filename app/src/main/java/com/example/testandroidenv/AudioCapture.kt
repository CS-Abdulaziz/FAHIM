package com.example.testandroidenv

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE_HZ = 16_000

/** Captures microphone audio in exactly the format accepted by Whisper: 16 kHz mono PCM-16. */
class AudioCapture {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private var samples = ShortArray(SAMPLE_RATE_HZ * 10)
    private var sampleCount = 0

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onError: (Throwable) -> Unit) {
        check(audioRecord == null) { "Recording is already active" }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferBytes > 0) { "The device does not support 16 kHz PCM microphone capture" }

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferBytes * 2
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone" }

        audioRecord = record
        running.set(true)
        record.startRecording()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(minBufferBytes)
            try {
                while (isActive && running.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> append(buffer, read)
                        read == 0 -> Unit
                        running.get() -> error("Microphone read failed ($read)")
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            }
        }
    }

    suspend fun stop(): FloatArray = withContext(Dispatchers.IO) {
        running.set(false)
        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        readJob?.join()
        readJob = null
        audioRecord?.release()
        audioRecord = null

        FloatArray(sampleCount) { index -> samples[index] / 32768.0f }
    }

    private fun append(buffer: ShortArray, count: Int) {
        val required = sampleCount + count
        if (required > samples.size) {
            samples = samples.copyOf(maxOf(required, samples.size * 2))
        }
        buffer.copyInto(samples, sampleCount, 0, count)
        sampleCount += count
    }
}
