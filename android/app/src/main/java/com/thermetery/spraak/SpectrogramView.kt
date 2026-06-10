// SpectrogramView.kt — port of SpectrogramView.swift plus AnalyzeView's waveform strip:
// grayscale spectrogram bitmap (paper-white → black, rendered by PraatViewModel.makeGrayImage
// from the PraatEngine.spectrogram dB matrix), pitch/intensity/formant overlays, time cursor,
// selection with draggable edge handles, long-press-to-select, and pinch zoom/pan over time.
// Part of the Spraak derivative. GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
package com.thermetery.spraak

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// [Android port] One shared palette for the editor and the readout chips. The colors follow
// Praat's own editor (pitch blue, intensity yellow-green, formants red speckles) rather than
// the iOS app's cyan/yellow/red, per the port spec.
val PaperColor = Color(0xFFF7F7F4)          // Praat "paper" background; theme-independent
val PitchColor = Color(0xFF2962FF)
val IntensityColor = Color(0xFFC0CA33)
val FormantColor = Color(0xFFE53935)
val CursorColor = Color(0xFFD50000)
val SelectionColor = Color(0xFFEC407A)

private val PanelShape = RoundedCornerShape(4.dp)
private val PanelBorder = Color.Gray.copy(alpha = 0.4f)

/** Port of SpectrogramView.swift; the waveform strip iOS kept in AnalyzeView lives here too. */
@Composable
fun SpectrogramView(vm: PraatViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WaveformStrip(vm, Modifier.fillMaxWidth().height(52.dp))
        SpectrogramCanvas(vm, Modifier.fillMaxWidth().height(230.dp))
    }
}

// ---- waveform strip (port of AnalyzeView.waveform) -------------------------------------------

@Composable
private fun WaveformStrip(vm: PraatViewModel, modifier: Modifier) {
    Canvas(
        modifier
            .clip(PanelShape)
            .background(PaperColor)   // [Android port] paper stays light in dark theme, like Praat
            .border(1.dp, PanelBorder, PanelShape),
    ) {
        val n = vm.waveMax.size
        if (n == 0 || !vm.hasSound) return@Canvas
        val w = size.width
        val h = size.height
        val midY = h / 2f
        vm.selection?.let { s ->
            val x0 = (w * (s.start - vm.viewStart) / vm.viewSpan).toFloat()
            val x1 = (w * (s.endInclusive - vm.viewStart) / vm.viewSpan).toFloat()
            drawRect(SelectionColor.copy(alpha = 0.25f), Offset(x0, 0f), Size(x1 - x0, h))
        }
        var amp = 1e-4f
        for (i in 0 until n) amp = max(amp, max(abs(vm.waveMin[i]), abs(vm.waveMax[i])))
        val path = Path()
        for (i in 0 until n) {
            val x = w * i / n
            path.moveTo(x, midY - midY * vm.waveMax[i] / amp)
            path.lineTo(x, midY - midY * vm.waveMin[i] / amp)
        }
        drawPath(path, Color.Black, style = Stroke(0.5.dp.toPx()))
        vm.cursorTime?.let { t ->
            if (t >= vm.viewStart && t <= vm.viewEnd) {
                val x = (w * (t - vm.viewStart) / vm.viewSpan).toFloat()
                drawLine(CursorColor, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            }
        }
    }
}

// ---- spectrogram + overlays + gestures --------------------------------------------------------

private enum class DragMode { None, MoveCursor, ResizeLo, ResizeHi, NewSelection }

@Composable
private fun SpectrogramCanvas(vm: PraatViewModel, modifier: Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current

    // live (uncommitted) pinch transform, port of pinchScale/pinchAnchor in SwiftUI
    var pinchScale by remember { mutableFloatStateOf(1f) }
    var pinchPan by remember { mutableFloatStateOf(0f) }
    var pinchAnchor by remember { mutableFloatStateOf(0.5f) }

    Box(
        modifier
            .clip(PanelShape)               // also clips the scaled layer, like iOS .clipped()
            .background(PaperColor)
            .border(1.dp, PanelBorder, PanelShape)
            .pointerInput(Unit) {           // gesture surface (never scaled)
                spectrogramGestures(
                    vm, haptics,
                    onPinchLive = { scale, pan, anchor ->
                        pinchScale = scale; pinchPan = pan; pinchAnchor = anchor
                    },
                    onPinchReset = { pinchScale = 1f; pinchPan = 0f },
                )
            },
    ) {
        // visual layer (scaled live during a pinch; the engine re-analyses only on commit)
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = pinchScale
                translationX = pinchPan
                transformOrigin = TransformOrigin(pinchAnchor, 0.5f)
            },
        ) {
            val img = vm.spectrogram
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = "Spectrogram",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    filterQuality = FilterQuality.Low,   // iOS .interpolation(.low)
                )
            } else {
                Text(
                    "Record or load a sound",
                    Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
            Canvas(Modifier.fillMaxSize()) { drawOverlays(vm, textMeasurer) }
        }
    }
}

/** Port of SpectrogramView.draw: selection, frequency grid, curves, formant speckles, cursor. */
private fun DrawScope.drawOverlays(vm: PraatViewModel, textMeasurer: TextMeasurer) {
    if (!vm.hasSound || vm.viewEnd <= vm.viewStart) return
    val w = size.width
    val h = size.height
    fun xFor(t: Double): Float = (w * (t - vm.viewStart) / vm.viewSpan).toFloat()

    // selection highlight + draggable edge handles (under the overlays)
    vm.selection?.let { s ->
        val x0 = xFor(s.start)
        val x1 = xFor(s.endInclusive)
        drawRect(SelectionColor.copy(alpha = 0.22f), Offset(x0, 0f), Size(x1 - x0, h))
        for (x in listOf(x0, x1)) {
            drawLine(SelectionColor, Offset(x, 0f), Offset(x, h), 1.5.dp.toPx())
            drawRoundRect(
                SelectionColor,
                Offset(x - 5.dp.toPx(), 2.dp.toPx()), Size(10.dp.toPx(), 18.dp.toPx()),
                CornerRadius(3.dp.toPx()),
            )
        }
    }

    // frequency gridlines + labels (every 1000 Hz)
    var f = 1000.0
    while (f < vm.fmax) {
        val y = (h * (1 - f / vm.fmax)).toFloat()
        drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(w, y), 0.5.dp.toPx())
        drawText(
            textMeasurer, "${f.toInt()}",
            topLeft = Offset(6.dp.toPx(), max(y - 12.sp.toPx(), 0f)),
            style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp),
        )
        f += 1000.0
    }

    if (vm.showIntensity) drawCurve(vm.intensity, IntensityColor, 2.dp.toPx())
    if (vm.showPitch) drawCurve(vm.pitch, PitchColor, 2.5.dp.toPx())
    if (vm.showFormants) {
        for (fc in vm.formants) {
            val n = fc.values.size
            for (i in 0 until n) {
                val v = fc.values[i]
                if (!v.isFinite() || v > vm.fmax) continue
                val x = w * (i + 0.5f) / n
                val y = (h * (1 - v / vm.fmax)).toFloat()
                drawCircle(FormantColor, 1.3.dp.toPx(), Offset(x, y))
            }
        }
    }

    // time cursor + grab handle
    vm.cursorTime?.let { t ->
        if (t >= vm.viewStart && t <= vm.viewEnd) {
            val x = xFor(t)
            drawLine(CursorColor, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
            drawRoundRect(
                CursorColor,
                Offset(x - 4.dp.toPx(), 2.dp.toPx()), Size(8.dp.toPx(), 14.dp.toPx()),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
}

/** Port of SpectrogramView.drawCurve: a poly-line scaled into [lo, hi], broken at NaN gaps. */
private fun DrawScope.drawCurve(c: AnalysisCurve, color: Color, width: Float) {
    val n = c.values.size
    if (n < 2 || c.hi <= c.lo) return
    val path = Path()
    var started = false
    for (i in 0 until n) {
        val v = c.values[i]
        if (!v.isFinite()) { started = false; continue }
        val x = size.width * (i + 0.5f) / n
        val y = (size.height * (1 - (v - c.lo) / (c.hi - c.lo))).toFloat()
        if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
    }
    drawPath(path, color, style = Stroke(width))
}

// ---- gestures ----------------------------------------------------------------------------------

/**
 * One unified pointer handler, port of the iOS gesture stack:
 *  - tap = place cursor (clears selection);
 *  - quick drag starting on the cursor or a selection edge = move/resize immediately;
 *  - long-press (haptic tick) then drag = new selection — a hold ALWAYS begins a selection;
 *  - two fingers = pinch zoom + pan over time; the visual layer scales live, and on release
 *    the new window is committed via vm.zoomTo, which re-queries the engine (like iOS onZoom).
 */
private suspend fun PointerInputScope.spectrogramGestures(
    vm: PraatViewModel,
    haptics: HapticFeedback,
    onPinchLive: (scale: Float, pan: Float, anchor: Float) -> Unit,
    onPinchReset: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!vm.hasSound) return@awaitEachGesture
        val w = size.width.toFloat().coerceAtLeast(1f)
        val grab = 18.dp.toPx()
        val slop = viewConfiguration.touchSlop
        // [Android port] platform long-press timeout instead of the iOS hard-coded 0.3 s
        val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis

        fun timeAt(x: Float): Double =
            (vm.viewStart + (x.toDouble() / w) * vm.viewSpan).coerceIn(vm.viewStart, vm.viewEnd)
        fun xFor(t: Double): Float = (w * (t - vm.viewStart) / vm.viewSpan).toFloat()
        fun sel(a: Double, b: Double) = min(a, b)..max(a, b)

        val downX = down.position.x
        // port of decideMode: grabbing the cursor or a selection edge manipulates immediately
        var mode = run {
            val s = vm.selection
            val c = vm.cursorTime
            when {
                s != null && abs(downX - xFor(s.start)) < grab -> DragMode.ResizeLo
                s != null && abs(downX - xFor(s.endInclusive)) < grab -> DragMode.ResizeHi
                c != null && c >= vm.viewStart && c <= vm.viewEnd &&
                    abs(downX - xFor(c)) < grab -> DragMode.MoveCursor
                else -> DragMode.NewSelection
            }
        }
        val fixedEdge: Double? = when (mode) {   // the selection edge that stays put while resizing
            DragMode.ResizeLo -> vm.selection?.endInclusive
            DragMode.ResizeHi -> vm.selection?.start
            else -> null
        }
        var armed = false        // long press fired → dragging now selects
        var moved = false
        var pinching = false
        var zoomAccum = 1f
        var panAccum = 0f
        var anchorFrac = 0.5f
        var anchorSet = false
        var lastX = downX

        while (true) {
            val event =
                if (!armed && !moved && !pinching) {
                    val remaining = longPressDeadline - SystemClock.uptimeMillis()
                    if (remaining <= 0) null
                    else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }

            if (event == null) {
                // long press fired: a hold always begins a selection, even over a handle (iOS)
                armed = true
                mode = DragMode.NewSelection
                val t = timeAt(downX)
                vm.selection = t..t          // show the anchor immediately
                vm.cursorTime = null
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                continue
            }

            val pressed = event.changes.filter { it.pressed }

            if (pressed.size >= 2) {
                pinching = true
                armed = false
                mode = DragMode.None
                if (!anchorSet) {
                    anchorFrac = (event.calculateCentroid().x / w).coerceIn(0f, 1f)
                    anchorSet = true
                }
                zoomAccum = (zoomAccum * event.calculateZoom()).coerceIn(0.05f, 50f)
                panAccum += event.calculatePan().x
                onPinchLive(zoomAccum, panAccum, anchorFrac)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pressed.size == 1 && !pinching) {
                val change = pressed.first()
                val x = change.position.x
                if (abs(x - downX) > slop) moved = true
                when {
                    mode == DragMode.MoveCursor -> {
                        vm.cursorTime = timeAt(x)
                        change.consume()
                    }
                    mode == DragMode.ResizeLo || mode == DragMode.ResizeHi -> {
                        fixedEdge?.let { vm.selection = sel(timeAt(x), it) }
                        change.consume()
                    }
                    mode == DragMode.NewSelection && armed -> {
                        vm.selection = sel(timeAt(downX), timeAt(x))
                        vm.cursorTime = null
                        change.consume()
                    }
                    // an un-held swipe does nothing: selection requires a hold (iOS behaviour)
                }
                lastX = x
            }

            if (event.changes.none { it.pressed }) {
                val upX = event.changes.firstOrNull()?.position?.x ?: lastX
                if (pinching) {
                    onPinchReset()
                    // commit: keep the anchored time under the fingers' (possibly panned) anchor
                    val anchorTime = vm.viewStart + anchorFrac * vm.viewSpan
                    val newSpan = vm.viewSpan / zoomAccum
                    val newFrac = ((anchorFrac * w + panAccum) / w).toDouble()
                    val lo = anchorTime - newFrac * newSpan
                    vm.zoomTo(lo, lo + newSpan)   // pushes zoom history + re-analyses the window
                } else if (mode == DragMode.NewSelection) {
                    if (armed) {
                        // held but never dragged: drop the zero-width selection, place the cursor
                        val s = vm.selection
                        if (s != null && s.endInclusive - s.start < 0.002 * vm.viewSpan) {
                            vm.selection = null
                            vm.cursorTime = timeAt(upX)
                        }
                    } else if (!moved) {
                        vm.cursorTime = timeAt(upX)   // quick tap = cursor
                        vm.selection = null
                    }
                }
                break
            }
        }
        onPinchReset()
    }
}
