// AudioEngine.kt — microphone recording + playback for the Spraak derivative.
// GPL-3.0-or-later.
// [Android port] AVAudioEngine -> AudioRecord/AudioTrack. Mono float capture at 44100 Hz with a
// live level meter, and FloatArray playback at arbitrary sample rate, mirroring AudioEngine.swift.
package com.thermetery.spraak

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class AudioEngine {

    // ---- observable state (Compose snapshot state; safe to write from worker threads) ----
    var isRecording by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var permissionDenied by mutableStateOf(false)
    var recordSeconds by mutableDoubleStateOf(0.0); private set   // elapsed recording time
    var recordLevel by mutableFloatStateOf(0f); private set       // input RMS (0…1) for the meter

    /** Capture sample rate, fixed (iOS used the hardware rate; Android guarantees 44100 works). */
    val sampleRate: Double get() = RECORD_RATE.toDouble()

    // ---- recording ----
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var recording = false
    private val captured = ArrayList<FloatArray>()   // chunks; only touched by the record thread,
                                                     // read on the caller's thread after join()

    /** Start recording, or stop and hand the captured (samples, rate) to `onStop` — like iOS. */
    fun toggleRecording(onStop: (FloatArray, Double) -> Unit) {
        if (isRecording) stopRecording(onStop) else startRecording()
    }

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            RECORD_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            // [Android port] VOICE_RECOGNITION ≈ AVAudioSession .measurement: no AGC/effects.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, RECORD_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                max(minBuf, READ_CHUNK * 4 * 4))
        } catch (_: SecurityException) {
            permissionDenied = true; return
        } catch (_: IllegalArgumentException) {
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            permissionDenied = true   // most common cause: RECORD_AUDIO not granted
            return
        }
        captured.clear()
        recordSeconds = 0.0
        recordLevel = 0f
        audioRecord = rec
        recording = true
        rec.startRecording()
        isRecording = true
        recordThread = thread(name = "spraak-record") {
            val buf = FloatArray(READ_CHUNK)
            var total = 0L
            while (recording) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) break          // ERROR_* — bail out, stopRecording() cleans up
                if (n == 0) continue
                captured.add(buf.copyOf(n))
                total += n
                var sum = 0f
                for (i in 0 until n) sum += buf[i] * buf[i]
                val rms = sqrt(sum / n)
                recordSeconds = total / sampleRate
                recordLevel = max(rms, recordLevel * 0.85f)   // fast attack, slow decay
            }
        }
    }

    private fun stopRecording(onStop: (FloatArray, Double) -> Unit) {
        recording = false
        recordThread?.join()               // blocks at most one read chunk (~93 ms)
        recordThread = null
        audioRecord?.let { r ->
            try { r.stop() } catch (_: IllegalStateException) {}
            r.release()
        }
        audioRecord = null
        isRecording = false
        val all = FloatArray(captured.sumOf { it.size })
        var off = 0
        for (c in captured) { c.copyInto(all, off); off += c.size }
        captured.clear()
        onStop(all, sampleRate)
    }

    // ---- playback ----
    private val playLock = Any()
    private var audioTrack: AudioTrack? = null
    @Volatile private var playGeneration = 0

    /**
     * Play the mono Float buffer over [from, to] seconds. Replaces any current playback.
     * `to == Double.POSITIVE_INFINITY` plays to the end (like the iOS default).
     */
    fun play(samples: FloatArray, rate: Double, from: Double = 0.0, to: Double = Double.POSITIVE_INFINITY) {
        stopPlayback()
        if (rate <= 0) return
        val n = samples.size
        val i0 = max(0, (from * rate).toInt())
        val i1 = min(n, if (to.isFinite()) (to * rate).toInt() else n)
        if (i1 <= i0) return
        val sr = rate.roundToInt()
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, 16384))
                .build()
        } catch (_: Exception) {
            return
        }
        val gen: Int
        synchronized(playLock) {
            gen = ++playGeneration
            audioTrack = track
        }
        isPlaying = true
        track.play()
        thread(name = "spraak-play") {
            var off = i0
            try {
                while (off < i1 && playGeneration == gen) {
                    val written = track.write(samples, off, min(WRITE_CHUNK, i1 - off),
                        AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) break
                    off += written
                }
                // drain: wait until everything written has actually been played
                val totalFrames = off - i0
                while (playGeneration == gen && track.playbackHeadPosition < totalFrames) {
                    Thread.sleep(20)
                }
            } catch (_: Exception) {
                // track released under us by stopPlayback() — fine
            }
            synchronized(playLock) {
                if (playGeneration == gen) {
                    try { track.stop() } catch (_: IllegalStateException) {}
                    track.release()
                    if (audioTrack === track) audioTrack = null
                    isPlaying = false
                }
            }
        }
    }

    fun stopPlayback() {
        synchronized(playLock) {
            playGeneration++   // invalidates the writer/drainer thread
            audioTrack?.let { t ->
                try { t.pause(); t.flush() } catch (_: IllegalStateException) {}
                t.release()
            }
            audioTrack = null
        }
        isPlaying = false
    }

    /** Stop everything; called from PraatViewModel.onCleared(). */
    fun release() {
        if (isRecording) stopRecording { _, _ -> }
        stopPlayback()
    }

    companion object {
        const val RECORD_RATE = 44100
        private const val READ_CHUNK = 4096
        private const val WRITE_CHUNK = 8192

        /**
         * Write mono Float PCM to a WAV file (IEEE float, format 3) — port of writeWav.
         * Praat's `Read from file:` accepts the minimal 16-byte fmt chunk we emit.
         */
        fun writeWav(samples: FloatArray, rate: Double, file: File): Boolean {
            return try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { o ->
                    fun le16(v: Int) { o.write(v); o.write(v shr 8) }
                    fun le32(v: Int) { o.write(v); o.write(v shr 8); o.write(v shr 16); o.write(v shr 24) }
                    val dataBytes = samples.size * 4
                    val sr = rate.roundToInt()
                    o.writeBytes("RIFF"); le32(36 + dataBytes); o.writeBytes("WAVE")
                    o.writeBytes("fmt "); le32(16)
                    le16(3); le16(1)              // IEEE float, mono
                    le32(sr); le32(sr * 4)        // sample rate, byte rate
                    le16(4); le16(32)             // block align, bits per sample
                    o.writeBytes("data"); le32(dataBytes)
                    val bb = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (s in samples) bb.putFloat(s)
                    o.write(bb.array())
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        // [Android port] AudioEngine.decode() (AVAudioFile) has no cheap framework equivalent for
        // floats; opening audio files goes through the engine instead — PraatViewModel.openWavViaScript
        // uses Praat's own `Read from file:` (WAV/FLAC/MP3/…), which is what the iOS bridge comments
        // recommend anyway.
    }
}

/**
 * A synthesized "vowel-like" sound (sum of damped formant resonances modulated at f0) so the
 * spectrogram/analyses can be demonstrated without microphone input. Port of DemoSound.
 */
object DemoSound {
    fun vowel(duration: Double = 1.2, rate: Double = 16000.0): Pair<FloatArray, Double> {
        val n = (duration * rate).toInt()
        val s = FloatArray(n)
        // a slow pitch glide 120→160 Hz; formants sweeping /a/→/i/-ish
        val f1lo = 700.0; val f1hi = 300.0
        val f2lo = 1200.0; val f2hi = 2300.0
        val f3 = 2900.0
        for (i in 0 until n) {
            val t = i / rate
            val frac = t / duration
            val f0 = 120.0 + 40.0 * frac
            val f1 = f1lo + (f1hi - f1lo) * frac
            val f2 = f2lo + (f2hi - f2lo) * frac
            // glottal-ish source: sum of harmonics with 1/h rolloff, shaped by formant gains
            var v = 0.0
            var h = 1
            while (h * f0 < rate / 2) {
                val fh = h * f0
                val g = formantGain(fh, f1, 80.0) +
                        0.7 * formantGain(fh, f2, 100.0) +
                        0.4 * formantGain(fh, f3, 150.0)
                v += (g / h) * sin(2 * PI * fh * t)
                h++
            }
            s[i] = (0.25 * v).toFloat()
        }
        return Pair(s, rate)
    }

    private fun formantGain(f: Double, center: Double, bw: Double): Double {
        val x = (f - center) / (bw / 2)
        return 1.0 / (1.0 + x * x)   // Lorentzian resonance
    }
}
