// TextGridEditor.kt — port of ios/app/TextGridEditor.swift for the Spraak derivative,
// plus the waveform strip / tier controls / mark editor that AnalyzeView (ContentView.swift)
// kept around the tiers. GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
//
// Multi-tier TextGrid annotation: stacked interval/point tiers aligned to the visible window.
// Tap a tier to add a boundary/point at that time; tap an existing mark to select it (edit its
// label / delete it in the editor row); export everything as a Praat .TextGrid.
package com.thermetery.spraak

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// [Android port] Swift's `let id = UUID()` → a process-wide counter; equality/identity only.
private val tgIds = AtomicLong(1)

/** Interval tier: a boundary (the interval starting at `time` carries `label`); point tier: a point. */
data class TGMark(
    val id: Long = tgIds.getAndIncrement(),
    val time: Double,
    val label: String = "",
)

data class TGTier(
    val id: Long = tgIds.getAndIncrement(),
    val name: String,
    val isInterval: Boolean,
    val marks: List<TGMark> = emptyList(),
)

data class TGMarkRef(val tier: Long, val mark: Long)

// [Android port] iOS kept tiers/selectedMark as AnalyzeView @State, reset by resetAnalysis() on
// every new sound. Our tab switch destroys the composition (SpraakApp swaps screens with `when`),
// so the annotation lives here and is reset only when a different sound arrives.
private object TextGridState {
    var soundKey: String? = null
    var tiers by mutableStateOf(defaultTiers())
    var selected by mutableStateOf<TGMarkRef?>(null)

    fun defaultTiers() = listOf(
        TGTier(name = "phones", isInterval = true),
        TGTier(name = "words", isInterval = true),
    )

    fun resetFor(key: String) {
        if (key == soundKey) return
        soundKey = key
        tiers = defaultTiers()
        selected = null
    }
}

/**
 * The TextGrid annotation block of the Analyze screen: waveform strip, tier controls,
 * stacked tiers, and the selected-mark editor. Renders nothing without a sound.
 */
@Composable
fun TextGridEditor(vm: PraatViewModel) {
    if (!vm.hasSound) return
    val soundKey = "${vm.soundName}|${vm.duration}|${vm.sampleRate}"
    LaunchedEffect(soundKey) { TextGridState.resetFor(soundKey) }

    val tiers = TextGridState.tiers
    val selected = TextGridState.selected
    val context = LocalContext.current

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WaveformStrip(vm)
        TierControls(
            anyMarks = tiers.any { it.marks.isNotEmpty() },
            onAddInterval = {
                TextGridState.tiers = tiers + TGTier(name = "tier${tiers.size + 1}", isInterval = true)
            },
            onAddPoint = {
                TextGridState.tiers = tiers + TGTier(name = "points${tiers.size + 1}", isInterval = false)
            },
            onExport = { exportTextGrid(context, tiers, vm.duration, vm.soundName) },
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tiers.forEach { tier ->
                key(tier.id) {
                    TierRow(
                        tier = tier,
                        selected = selected,
                        viewStart = vm.viewStart,
                        viewEnd = vm.viewEnd,
                        onSelect = { TextGridState.selected = it },
                        onChange = { updated ->
                            TextGridState.tiers =
                                TextGridState.tiers.map { if (it.id == updated.id) updated else it }
                        },
                    )
                }
            }
        }
        MarkEditor(tiers, selected)
    }
}

/** Port of AnalyzeView.waveform: peak-preserving min/max columns over the visible window. */
@Composable
private fun WaveformStrip(vm: PraatViewModel) {
    val waveColor = MaterialTheme.colorScheme.onSurface
    val cursorColor = MaterialTheme.colorScheme.error
    val selColor = Color(0xFFE91E63).copy(alpha = 0.25f)            // iOS .pink.opacity(0.25)
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Canvas(
        Modifier.fillMaxWidth().height(52.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp)),
    ) {
        val wmin = vm.waveMin
        val wmax = vm.waveMax
        val n = wmax.size
        if (n == 0 || wmin.size != n) return@Canvas
        val span = vm.viewSpan
        val midY = size.height / 2f
        vm.selection?.let { s ->
            val x0 = (size.width * (s.start - vm.viewStart) / span).toFloat()
            val x1 = (size.width * (s.endInclusive - vm.viewStart) / span).toFloat()
            drawRect(selColor, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
        }
        var amp = 1e-4f
        for (i in 0 until n) amp = max(amp, max(abs(wmin[i]), abs(wmax[i])))
        val path = Path()
        for (i in 0 until n) {
            val x = size.width * i / n
            path.moveTo(x, midY - midY * wmax[i] / amp)
            path.lineTo(x, midY - midY * wmin[i] / amp)
        }
        drawPath(path, waveColor, style = Stroke(width = 1f))
        vm.cursorTime?.let { t ->
            if (t in vm.viewStart..vm.viewEnd) {
                val x = (size.width * (t - vm.viewStart) / span).toFloat()
                drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

/** Port of AnalyzeView.tierControls: add interval/point tier, export TextGrid. */
@Composable
private fun TierControls(
    anyMarks: Boolean,
    onAddInterval: () -> Unit,
    onAddPoint: () -> Unit,
    onExport: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Tiers",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAddInterval, contentPadding = pad) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text("Interval", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(onClick = onAddPoint, contentPadding = pad) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text("Point", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onExport, enabled = anyMarks, contentPadding = pad) {
            Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(2.dp))
            Text("TextGrid", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** One tier: name label + canvas. Port of TextGridTiersView.tierCanvas (tap to add/select). */
@Composable
private fun TierRow(
    tier: TGTier,
    selected: TGMarkRef?,
    viewStart: Double,
    viewEnd: Double,
    onSelect: (TGMarkRef?) -> Unit,
    onChange: (TGTier) -> Unit,
) {
    val span = max(viewEnd - viewStart, 1e-6)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    val selColor = MaterialTheme.colorScheme.primary                // iOS .blue
    val intervalColor = MaterialTheme.colorScheme.outline           // iOS .gray
    val pointColor = Color(0xFF9C27B0)                               // iOS .purple
    val bg = if (tier.isInterval) MaterialTheme.colorScheme.surface
             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    Row(
        Modifier.fillMaxWidth().height(34.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tier.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(52.dp),
        )
        Canvas(
            Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(bg)
                .border(1.dp, borderColor, RoundedCornerShape(3.dp))
                // keyed on the tier value itself so the handler never captures stale marks
                .pointerInput(tier, viewStart, viewEnd) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat()
                        if (w <= 0f) return@detectTapGestures
                        val t = (viewStart + (pos.x / w) * span).coerceIn(viewStart, viewEnd)
                        val hit = tier.marks.minByOrNull { abs(it.time - t) }
                        if (hit != null && abs(hit.time - t) / span * w < 12.dp.toPx()) {
                            onSelect(TGMarkRef(tier.id, hit.id))
                        } else {
                            val m = TGMark(time = t)
                            onChange(tier.copy(marks = tier.marks + m))
                            onSelect(TGMarkRef(tier.id, m.id))
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val marks = tier.marks.sortedBy { it.time }
            marks.forEachIndexed { i, m ->
                if (m.time < viewStart || m.time > viewEnd) return@forEachIndexed
                val x = (w * (m.time - viewStart) / span).toFloat()
                val isSel = selected == TGMarkRef(tier.id, m.id)
                drawLine(
                    color = if (isSel) selColor else if (tier.isInterval) intervalColor else pointColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = if (isSel) 2.dp.toPx() else 1.dp.toPx(),
                )
                if (tier.isInterval) {
                    // label the interval starting at this boundary, centred up to the next one
                    if (m.label.isNotEmpty()) {
                        val next = if (i + 1 < marks.size) marks[i + 1].time else viewEnd
                        val midX = (w * ((m.time + min(next, viewEnd)) / 2 - viewStart) / span).toFloat()
                        val layout = measurer.measure(AnnotatedString(m.label), labelStyle)
                        drawText(
                            layout,
                            topLeft = Offset(midX - layout.size.width / 2f, h / 2f - layout.size.height / 2f),
                        )
                    }
                } else if (m.label.isNotEmpty()) {
                    val layout = measurer.measure(AnnotatedString(m.label), labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(
                            x - layout.size.width / 2f,
                            h / 2f - 8.dp.toPx() - layout.size.height / 2f,   // iOS: y = H/2 - 8
                        ),
                    )
                }
            }
        }
    }
}

/** Port of AnalyzeView.markEditor: edit the selected mark's label or delete it. */
@Composable
private fun MarkEditor(tiers: List<TGTier>, selected: TGMarkRef?) {
    val ref = selected ?: return
    val tier = tiers.firstOrNull { it.id == ref.tier } ?: return
    val mark = tier.marks.firstOrNull { it.id == ref.mark } ?: return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${tier.name} @ ${fmt3(mark.time)} s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = mark.label,
            onValueChange = { newLabel ->
                TextGridState.tiers = tiers.map { t ->
                    if (t.id != tier.id) t
                    else t.copy(marks = t.marks.map { if (it.id == mark.id) it.copy(label = newLabel) else it })
                }
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("label") },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
        )
        IconButton(onClick = {
            TextGridState.tiers = tiers.map { t ->
                if (t.id != tier.id) t else t.copy(marks = t.marks.filterNot { it.id == mark.id })
            }
            TextGridState.selected = null
        }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete mark",
                 tint = MaterialTheme.colorScheme.error)
        }
    }
}

// [Android port] iOS wrote annotation.TextGrid to tmp and presented UIActivityViewController.
// We stay FileProvider-free: the grid goes out as plain text via ACTION_SEND (any receiver can
// save it as a .TextGrid), and a copy is written to cacheDir for adb / file-manager access.
private fun exportTextGrid(context: Context, tiers: List<TGTier>, duration: Double, name: String) {
    val text = TextGridIO.textGrid(tiers, 0.0, duration)
    runCatching { File(context.cacheDir, "$name.TextGrid").writeText(text) }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "$name.TextGrid")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Export TextGrid"))
}

private fun fmt3(t: Double): String = String.format(Locale.US, "%.3f", t)

/** Port of TextGridIO: generate Praat .TextGrid (ooTextFile) text. */
object TextGridIO {
    /**
     * Interval tiers partition [xmin,xmax] at their boundary marks (a mark at time t labels the
     * interval starting at t); point tiers list their points.
     */
    fun textGrid(tiers: List<TGTier>, xmin: Double, xmax: Double): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val out = StringBuilder()
        out.append("File type = \"ooTextFile\"\n")
        out.append("Object class = \"TextGrid\"\n\n")
        out.append("xmin = $xmin\nxmax = $xmax\ntiers? <exists>\nsize = ${tiers.size}\nitem []:\n")
        tiers.forEachIndexed { ti, tier ->
            out.append("    item [${ti + 1}]:\n")
            if (tier.isInterval) {
                // build a contiguous partition
                val bounds = tier.marks.map { it.time }.filter { it > xmin && it < xmax }.distinct().sorted()
                val points = listOf(xmin) + bounds + listOf(xmax)
                out.append("        class = \"IntervalTier\"\n        name = ${q(tier.name)}\n")
                out.append("        xmin = $xmin\n        xmax = $xmax\n")
                out.append("        intervals: size = ${points.size - 1}\n")
                for (i in 0 until points.size - 1) {
                    val start = points[i]
                    val end = points[i + 1]
                    val label = tier.marks.firstOrNull { abs(it.time - start) < 1e-9 }?.label ?: ""
                    out.append("        intervals [${i + 1}]:\n")
                    out.append("            xmin = $start\n            xmax = $end\n")
                    out.append("            text = ${q(label)}\n")
                }
            } else {
                val pts = tier.marks.sortedBy { it.time }
                out.append("        class = \"TextTier\"\n        name = ${q(tier.name)}\n")
                out.append("        xmin = $xmin\n        xmax = $xmax\n")
                out.append("        points: size = ${pts.size}\n")
                pts.forEachIndexed { i, p ->
                    out.append("        points [${i + 1}]:\n")
                    out.append("            number = ${p.time}\n            mark = ${q(p.label)}\n")
                }
            }
        }
        return out.toString()
    }
}
