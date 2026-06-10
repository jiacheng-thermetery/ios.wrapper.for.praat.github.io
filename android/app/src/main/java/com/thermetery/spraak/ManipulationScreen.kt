// ManipulationScreen.kt — Kotlin/Compose port of ios/app/ManipulationView.swift for the Spraak
// derivative. Praat Manipulation (PSOLA): drag the pitch tier, hear the resynthesis. Includes a
// reusable draggable curve-tier editor. GPL-3.0-or-later.
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One editable point of a tier (time s × value). Port of TierPoint (identity = list index). */
data class TierPoint(val time: Double, val value: Double)

/**
 * Reusable draggable tier editor: points on a time×value plane. Drag a point to move it,
 * tap empty space to add one. Calls onCommit when a drag/edit finishes.
 * Port of TierCurveView. [Android port] immutable list + onPointsChange instead of a Binding.
 */
@Composable
fun TierCurveEditor(
    points: List<TierPoint>,
    onPointsChange: (List<TierPoint>) -> Unit,
    tmin: Double, tmax: Double, vmin: Double, vmax: Double,
    color: Color = Color(0xFF1E66C8),
    modifier: Modifier = Modifier,
    onCommit: () -> Unit = {},
) {
    val span = max(tmax - tmin, 1e-6)
    val vspan = max(vmax - vmin, 1e-6)
    // latest values for the long-lived pointerInput coroutine
    val curPoints by rememberUpdatedState(points)
    val change by rememberUpdatedState(onPointsChange)
    val commit by rememberUpdatedState(onCommit)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFAFAFA))
            .border(1.dp, Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .pointerInput(tmin, tmax, vmin, vmax) {
                val grabPx = 22.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun xOf(p: TierPoint) = (w * (p.time - tmin) / span).toFloat()
                    fun pointAt(pos: Offset) = TierPoint(
                        (tmin + (pos.x / w) * span).coerceIn(tmin, tmax),
                        (vmin + (1.0 - pos.y / h) * vspan).coerceIn(vmin, vmax),
                    )
                    // [Android port] track the edit in a local list so fast drag events never
                    // race recomposition; the parent state is just a mirror.
                    var working = curPoints
                    // grab nearest point within ~22 dp, else add a new one — like iOS
                    val near = working.indices.minByOrNull { abs(xOf(working[it]) - down.position.x) }
                        ?.takeIf { abs(xOf(working[it]) - down.position.x) < grabPx }
                    val idx: Int
                    if (near == null) {
                        working = working + pointAt(down.position)
                        idx = working.lastIndex
                    } else {
                        idx = near
                        working = working.toMutableList().also { it[idx] = pointAt(down.position) }
                    }
                    change(working)
                    drag(down.id) { ch ->
                        working = working.toMutableList().also { it[idx] = pointAt(ch.position) }
                        change(working)
                        ch.consume()
                    }
                    commit()
                }
            }
    ) {
        val w = size.width
        val h = size.height
        // gridlines + value labels
        val labelStyle = TextStyle(fontSize = 9.sp, color = Color.Gray)
        var frac = 0.0
        while (frac <= 1.0) {
            val y = (h * (1.0 - frac)).toFloat()
            drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            val label = textMeasurer.measure(
                AnnotatedString("${(vmin + frac * vspan).roundToInt()}"), labelStyle)
            drawText(label, topLeft = Offset(4f, (y - label.size.height).coerceAtLeast(0f)))
            frac += 0.25
        }
        val sorted = points.sortedBy { it.time }
        fun posOf(p: TierPoint) = Offset(
            (w * (p.time - tmin) / span).toFloat(),
            (h * (1.0 - (p.value - vmin) / vspan)).toFloat(),
        )
        val line = Path()
        sorted.forEachIndexed { i, p ->
            val o = posOf(p)
            if (i == 0) line.moveTo(o.x, o.y) else line.lineTo(o.x, o.y)
        }
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        for (p in sorted) drawCircle(color, radius = 5.dp.toPx(), center = posOf(p))
    }
}

@Composable
fun ManipulationScreen(vm: PraatViewModel) {
    val scope = rememberCoroutineScope()
    var points by remember { mutableStateOf<List<TierPoint>>(emptyList()) }
    var duration by remember { mutableDoubleStateOf(0.0) }
    var rate by remember { mutableDoubleStateOf(16000.0) }
    var status by remember { mutableStateOf("Tap “Load” to manipulate a demo sound.") }
    var lastSamples by remember { mutableStateOf(FloatArray(0)) }

    /** Resynthesize from the current tier; returns true on success (updates lastSamples/rate). */
    suspend fun resynth(): Boolean {
        if (points.isEmpty()) return false
        val sorted = points.sortedBy { it.time }
        val r = vm.manipulationResynth(
            sorted.map { it.time }.toDoubleArray(),
            sorted.map { it.value }.toDoubleArray(),
        ) ?: run { lastSamples = FloatArray(0); return false }
        lastSamples = r.first
        rate = r.second
        return true
    }

    fun resynthAndPlay() = scope.launch { if (resynth()) vm.audio.play(lastSamples, rate) }

    fun load() = scope.launch {
        // [Android port] the engine (and its current sound) is shared with the Analyze tab, so
        // manipulate the current sound when there is one; iOS always loaded the demo vowel.
        if (!vm.hasSound) {
            val (s, r) = DemoSound.vowel()
            vm.setSoundFromPCMAwait(s, r, "vowel")
        }
        duration = vm.duration
        rate = vm.sampleRate
        val tier = vm.manipulationStart()
        if (tier == null) {
            points = emptyList()
            status = "Could not create a Manipulation."
        } else {
            // few, well-spaced points are draggable on a phone — like iOS
            points = downsampleTier(tier.first, tier.second, 8)
            status = ""
            resynth()
        }
    }

    LaunchedEffect(vm.engineReady) {
        if (vm.engineReady && points.isEmpty()) load()   // port of onAppear
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manipulation", style = MaterialTheme.typography.titleMedium)
            Text("(PSOLA pitch)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { load() }) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Load")
            }
            Button(onClick = { resynthAndPlay() }, enabled = points.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Play")
            }
        }

        Text("Drag the blue pitch points; tap empty space to add. Play hears the resynthesis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth())

        if (points.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TierCurveEditor(
                points = points,
                onPointsChange = { points = it },
                tmin = 0.0, tmax = max(duration, 0.001),
                vmin = 50.0, vmax = 500.0,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onCommit = { resynthAndPlay() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format("%d points · %.2f s", points.size, duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    points = points.map { it.copy(value = 200.0) }
                    resynthAndPlay()
                }) { Text("Flatten 200 Hz", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

/**
 * Reduce a dense pitch tier to at most `k` evenly-spaced points (keeping the first and last),
 * so the contour is easy to grab and drag on a touch screen. Port of ManipulationView.downsample.
 */
internal fun downsampleTier(times: DoubleArray, values: DoubleArray, k: Int): List<TierPoint> {
    val n = minOf(times.size, values.size)
    if (n <= k || k < 2) return (0 until n).map { TierPoint(times[it], values[it]) }
    return (0 until k).map { i ->
        val j = (i.toDouble() * (n - 1) / (k - 1)).roundToInt()
        TierPoint(times[j], values[j])
    }
}
