// VowelScreen.kt — Kotlin/Compose port of ios/app/VowelView.swift for the Spraak derivative.
// A Vowel editor: drag in the F1×F2 vowel space to hear a synthesized vowel, with the
// reference vowels drawn behind it. GPL-3.0-or-later.
//
// [Android port] Additionally overlays the current sound's measured F1/F2 track (fetched from
// the engine's formant analysis) and offers a record toggle: AudioEngine has no streaming tap,
// so the track refreshes when a take finishes (the recording becomes the current sound) rather
// than sample-by-sample while recording.
package com.thermetery.spraak

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Simple formant synthesis: a glottal-source harmonic series shaped by Lorentzian formant
 *  resonances. Port of VowelSynth from VowelView.swift. Pure math — no engine call needed. */
object VowelSynth {
    fun synth(f0: Double, f1: Double, f2: Double, f3: Double = 2700.0,
              dur: Double = 0.5, rate: Double = 16000.0): FloatArray {
        val n = (dur * rate).toInt()
        val s = FloatArray(n)
        fun gain(f: Double, c: Double, bw: Double): Double {
            val x = (f - c) / (bw / 2); return 1.0 / (1.0 + x * x)
        }
        for (i in 0 until n) {
            val t = i / rate
            var v = 0.0
            var h = 1
            while (h * f0 < rate / 2) {
                val fh = h * f0
                val g = gain(fh, f1, 90.0) + 0.8 * gain(fh, f2, 110.0) + 0.4 * gain(fh, f3, 160.0)
                v += (g / h) * sin(2 * PI * fh * t)
                h++
            }
            // gentle fade in/out to avoid clicks
            val env = min(1.0, min(i.toDouble(), (n - i).toDouble()) / (0.02 * rate))
            s[i] = (0.22 * env * v).toFloat()
        }
        return s
    }
}

// axis ranges (Hz) — same as iOS
private const val F1_LO = 250.0; private const val F1_HI = 900.0   // vertical: close (top) → open (bottom)
private const val F2_LO = 700.0; private const val F2_HI = 2500.0  // horizontal: back (right) → front (left)

// reference vowels (approx. male formants): (label, F1, F2) — same as iOS
private val REF_VOWELS = listOf(
    Triple("i", 280.0, 2250.0), Triple("e", 400.0, 2100.0), Triple("ɛ", 550.0, 1900.0),
    Triple("a", 700.0, 1500.0), Triple("ɑ", 750.0, 1100.0), Triple("ɔ", 550.0, 900.0),
    Triple("o", 450.0, 800.0), Triple("u", 320.0, 850.0), Triple("ə", 500.0, 1500.0),
)

@Composable
fun VowelScreen(vm: PraatViewModel) {
    var f1 by remember { mutableDoubleStateOf(500.0) }
    var f2 by remember { mutableDoubleStateOf(1500.0) }
    var f0 by remember { mutableDoubleStateOf(120.0) }
    var showTrack by remember { mutableStateOf(true) }
    // measured (F1, F2) pairs of the current sound, sampled over its whole duration
    var track by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<Job?>(null) }
    val textMeasurer = rememberTextMeasurer()

    fun play() {
        playJob?.cancel()
        // [Android port] synthesis off the UI thread; iOS did it synchronously in the gesture.
        playJob = scope.launch {
            val (pf0, pf1, pf2) = Triple(f0, f1, f2)
            val s = withContext(Dispatchers.Default) { VowelSynth.synth(pf0, pf1, pf2) }
            vm.audio.play(s, 16000.0)
        }
    }

    // refresh the F1/F2 track whenever the current sound changes (incl. after a recording stops)
    LaunchedEffect(vm.hasSound, vm.duration, vm.soundName, vm.audio.isRecording, showTrack) {
        track = if (!showTrack || !vm.hasSound || vm.audio.isRecording) emptyList() else {
            val n = 300
            val dur = vm.duration
            // Fetch pitch too: Burg returns in-range garbage F1/F2 for unvoiced frames and
            // silence, so only voiced frames belong in the vowel space (Praat speckles
            // formants for the same reason).
            val (c1, c2, pitch) = vm.engine {
                Triple(PraatEngine.curve(PraatViewModel.KIND_FORMANT1, 0.0, dur, n),
                       PraatEngine.curve(PraatViewModel.KIND_FORMANT1 + 1, 0.0, dur, n),
                       PraatEngine.curve(PraatViewModel.KIND_PITCH, 0.0, dur, n))
            }
            if (c1 == null || c2 == null) emptyList()
            else (0 until minOf(c1.size, c2.size)).mapNotNull { i ->
                val voiced = pitch != null && i < pitch.size && pitch[i].isFinite()
                if (voiced) Pair(c1[i].toDouble(), c2[i].toDouble()) else null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Vowel editor", style = MaterialTheme.typography.titleMedium)
        Text("Drag in the vowel space to hear it (formant synthesis).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF7F7F7))
                .border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            fun update(p: Offset) {
                                f2 = (F2_HI - (p.x / w) * (F2_HI - F2_LO)).coerceIn(F2_LO, F2_HI)
                                f1 = (F1_LO + (p.y / h) * (F1_HI - F1_LO)).coerceIn(F1_LO, F1_HI)
                            }
                            val down = awaitFirstDown()
                            update(down.position)
                            drag(down.id) { change -> update(change.position); change.consume() }
                            play()   // like iOS: play when the drag/tap ends
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                fun pos(vf1: Double, vf2: Double) = Offset(
                    (w * (F2_HI - vf2) / (F2_HI - F2_LO)).toFloat(),
                    (h * (vf1 - F1_LO) / (F1_HI - F1_LO)).toFloat(),
                )
                // measured F1/F2 of the current sound, voiced frames only, drawn as
                // speckles like Praat — connecting lines turned silences/transitions
                // into spaghetti across the whole vowel space.
                for ((tf1, tf2) in track) {
                    val ok = tf1.isFinite() && tf2.isFinite() &&
                        tf1 in F1_LO..F1_HI && tf2 in F2_LO..F2_HI
                    if (!ok) continue
                    drawCircle(Color(0xFF1E66C8).copy(alpha = 0.55f),
                        radius = 2.5.dp.toPx(), center = pos(tf1, tf2))
                }
                // reference vowels
                val refStyle = TextStyle(fontSize = 15.sp, color = Color.Gray)
                for ((name, rf1, rf2) in REF_VOWELS) {
                    val p = pos(rf1, rf2)
                    val layout = textMeasurer.measure(AnnotatedString(name), refStyle)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f,
                                                      p.y - layout.size.height / 2f))
                }
                // current vowel dot
                drawCircle(Color.Red, radius = 8.dp.toPx(), center = pos(f1, f2))
            }
            // axis labels
            val axisStyle = MaterialTheme.typography.labelSmall
            val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
            Text("F2 → front", style = axisStyle, color = axisColor,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 4.dp))
            Text("F1 → open", style = axisStyle, color = axisColor,
                modifier = Modifier.align(Alignment.CenterStart).rotate(-90f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("F1 %.0f  F2 %.0f Hz", f1, f2),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { play() }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Play")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("F0", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = f0.toFloat(),
                onValueChange = { f0 = it.toDouble() },
                valueRange = 80f..250f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("${f0.toInt()} Hz", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }

        // [Android port] live-input row (not on iOS): record a take, see its F1/F2 path above.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showTrack, onCheckedChange = { showTrack = it })
            Text("F1/F2 track of current sound", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (vm.audio.isRecording) {
                Text(String.format("%.1f s", vm.audio.recordSeconds),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { vm.audio.recordLevel.coerceIn(0f, 1f) },
                    modifier = Modifier.width(60.dp).padding(horizontal = 8.dp),
                )
            }
            IconButton(onClick = { vm.toggleRecording() }) {
                Icon(
                    if (vm.audio.isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (vm.audio.isRecording) "Stop recording" else "Record",
                    tint = if (vm.audio.isRecording) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}
