// AnalyzeScreen.kt — port of AnalyzeView in ContentView.swift: the spectrogram editor tab
// (record/speak/demo/open, time-window controls, playback, cursor readouts, spectral slice,
// TextGrid annotation entry).
// Part of the Spraak derivative. GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
package com.thermetery.spraak

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val SPEAK_LANGUAGES = listOf(
    "English (Great Britain)", "English (America)", "French (France)", "German",
    "Spanish (Spain)", "Italian", "Dutch", "Russian", "Mandarin Chinese", "Japanese",
)
private val SPEAK_VOICES = listOf("Female1", "Male1", "default")

@Composable
fun AnalyzeScreen(vm: PraatViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var cursorValues by remember { mutableStateOf<CursorValues?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showSpeak by remember { mutableStateOf(false) }
    var showSlice by remember { mutableStateOf(false) }
    var zoomDialog by remember { mutableStateOf<Pair<String, String>?>(null) }   // (from, to)
    var playDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var speakText by remember { mutableStateOf("Frogs are cute. I love frogs. Frogs!") }
    var speakLang by remember { mutableStateOf(SPEAK_LANGUAGES.first()) }
    var speakVoice by remember { mutableStateOf(SPEAK_VOICES.first()) }

    // [Android port] iOS used .fileImporter + AVAudioFile decode; here the document is copied
    // out of the content provider into cacheDir (Praat needs a real path) and the engine itself
    // opens it via `Read from file:` (WAV/FLAC/MP3/...), then hands it to the Analyze tab —
    // see PraatViewModel.openWavViaScript and the rationale note in AudioEngine.kt.
    val openLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) scope.launch {
                val copied = withContext(Dispatchers.IO) { copyUriToCache(context, uri) }
                if (copied != null) vm.openWavViaScript(copied.first.absolutePath, copied.second)
            }
        }

    // port of .onAppear: load the demo vowel once and park the cursor mid-sound
    LaunchedEffect(Unit) {
        if (!vm.hasSound) {
            val (s, r) = DemoSound.vowel()
            vm.setSoundFromPCMAwait(s, r, "vowel")
            vm.cursorTime = vm.duration * 0.5
        }
    }

    // port of .onChange(of: cursorTime): the readout chips track the cursor
    val cursor = vm.cursorTime
    LaunchedEffect(cursor, vm.hasSound, vm.settings) {
        cursorValues = if (cursor != null && vm.hasSound) vm.valuesAt(cursor) else null
    }

    // quick Play: the selection if any, else cursor (or window start) → window end; tap again stops
    fun quickPlay() {
        if (vm.audio.isPlaying) { vm.stopPlayback(); return }
        val s = vm.selection
        if (s != null) {
            vm.play(s.start, s.endInclusive)
        } else {
            val from = max(vm.viewStart, min(vm.cursorTime ?: vm.viewStart, vm.viewEnd))
            vm.play(from, vm.viewEnd)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- top controls (ordered by actual use, like iOS) --------------------------------
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AudioMenuButton(vm) {
                val r = vm.selection
                playDialog =
                    if (r != null) Pair(fmt(r.start), fmt(r.endInclusive))
                    else Pair(fmt(vm.viewStart), fmt(vm.viewEnd))
            }
            OutlinedButton(onClick = { vm.toggleRecording() }) {
                Icon(
                    if (vm.audio.isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (vm.audio.isRecording) MaterialTheme.colorScheme.error
                    else LocalContentColor.current,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (vm.audio.isRecording) "Stop" else "Record")
            }
            TimeMenuButton(vm) { zoomDialog = Pair(fmt(vm.viewStart), fmt(vm.viewEnd)) }
            OutlinedButton(onClick = { showSpeak = true }) {
                Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Speak")
            }
            OutlinedButton(onClick = { vm.loadDemo() }) {
                Icon(Icons.Filled.SmartDisplay, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Demo")
            }
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("Open")
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Analysis settings")
            }
            if (vm.audio.permissionDenied) {
                Text("Mic denied", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }

        // [Android port] iOS overlaid the banner on top of the view; a banner row in the column
        // can never cover the Record/Stop toggle and still carries its own Stop button.
        AnimatedVisibility(visible = vm.audio.isRecording) { RecordingBanner(vm) }

        // ---- overlay toggles + quick Play ---------------------------------------------------
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = vm.showPitch, onClick = { vm.showPitch = !vm.showPitch },
                label = { Text("Pitch", color = PitchColor) })
            FilterChip(selected = vm.showFormants, onClick = { vm.showFormants = !vm.showFormants },
                label = { Text("Formants", color = FormantColor) })
            FilterChip(selected = vm.showIntensity, onClick = { vm.showIntensity = !vm.showIntensity },
                label = { Text("Intensity", color = IntensityColor) })
            Button(onClick = { quickPlay() }, enabled = vm.hasSound) {
                Icon(if (vm.audio.isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (vm.audio.isPlaying) "Stop" else "Play")
            }
        }

        if (vm.hasSound) {
            SpectrogramView(vm, Modifier.fillMaxWidth())
            Text(
                "Long-press then drag to select · pinch to zoom · tap to place cursor",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TimeAxis(vm)
            cursorValues?.let { CursorReadout(it, onSlice = { showSlice = true }) }
            // [Android port] iOS kept the tier controls + TextGridTiersView inline here; on
            // Android the TextGrid editor composable owns all annotation UI.
            TextGridEditor(vm)
        } else {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tap ● Record (or ▶ Demo) to analyse a sound.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // ---- dialogs & sheets -------------------------------------------------------------------
    zoomDialog?.let { (f, t) ->
        TimeRangeDialog("Zoom", "Zoom", f, t, onDismiss = { zoomDialog = null }) { a, b ->
            vm.zoomTo(a, b)
        }
    }
    playDialog?.let { (f, t) ->
        TimeRangeDialog("Play", "Play", f, t, onDismiss = { playDialog = null }) { a, b ->
            vm.play(a, b)
        }
    }
    if (showSettings) SettingsSheet(vm, onDismiss = { showSettings = false })
    if (showSpeak) {
        SpeakSheet(
            text = speakText, onText = { speakText = it },
            language = speakLang, onLanguage = { speakLang = it },
            voice = speakVoice, onVoice = { speakVoice = it },
            onDismiss = { showSpeak = false },
            onSpeak = { scope.launch { vm.speak(speakText, speakLang, speakVoice) } },
        )
    }
    if (showSlice) SliceSheet(vm, onDismiss = { showSlice = false })
}

// ---- menus -------------------------------------------------------------------------------------

/** Port of the iOS audioMenu (Play… / Play window / Play selection / Interrupt). */
@Composable
private fun AudioMenuButton(vm: PraatViewModel, onPlayDialog: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(
                if (vm.audio.isPlaying) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                null, Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp)); Text("Audio")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play…") },
                onClick = { open = false; onPlayDialog() })
            DropdownMenuItem(text = { Text(if (vm.audio.isPlaying) "Stop" else "Play window") },
                onClick = {
                    open = false
                    if (vm.audio.isPlaying) vm.stopPlayback() else vm.playWindow()
                })
            DropdownMenuItem(text = { Text("Play selection") },
                onClick = { open = false; vm.playSelection() },
                enabled = vm.selection != null)
            DropdownMenuItem(text = { Text("Interrupt playing") },
                onClick = { open = false; vm.stopPlayback() },
                enabled = vm.audio.isPlaying)
        }
    }
}

/** Port of the iOS timeMenu (Zoom… / Show all / in / out / to selection / back / scroll). */
@Composable
private fun TimeMenuButton(vm: PraatViewModel, onZoomDialog: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Filled.SwapHoriz, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp)); Text("Time")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Zoom…") }, onClick = { open = false; onZoomDialog() })
            DropdownMenuItem(text = { Text("Show all") }, onClick = { open = false; vm.showAll() })
            DropdownMenuItem(text = { Text("Zoom in") }, onClick = { open = false; vm.zoomIn() })
            DropdownMenuItem(text = { Text("Zoom out") }, onClick = { open = false; vm.zoomOut() })
            DropdownMenuItem(text = { Text("Zoom to selection") },
                onClick = { open = false; vm.zoomToSelection() },
                enabled = vm.selection != null)
            DropdownMenuItem(text = { Text("Zoom back") },
                onClick = { open = false; vm.zoomBack() },
                enabled = vm.canZoomBack)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Scroll page back") },
                onClick = { open = false; vm.scrollPage(-1.0) })
            DropdownMenuItem(text = { Text("Scroll page forward") },
                onClick = { open = false; vm.scrollPage(1.0) })
        }
    }
}

// ---- small panels ------------------------------------------------------------------------------

/** Port of RecordingBanner: elapsed time, input level meter, Stop. */
@Composable
private fun RecordingBanner(vm: PraatViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error)
            Text(String.format(Locale.US, "%.1f s", vm.audio.recordSeconds),
                style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { vm.audio.recordLevel.coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.toggleRecording() }) {
                Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("Stop")
            }
        }
    }
}

/** Port of the iOS timeAxis: window edges + selection/cursor/total readout. */
@Composable
private fun TimeAxis(vm: PraatViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelSmall
        Text(fmt(vm.viewStart), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val s = vm.selection
        val c = vm.cursorTime
        when {
            s != null -> Text(
                String.format(Locale.US, "sel %.3f–%.3f s (%.3f)",
                    s.start, s.endInclusive, s.endInclusive - s.start),
                style = style, color = SelectionColor,
            )
            c != null -> Text(String.format(Locale.US, "cursor %.3f s", c),
                style = style, color = CursorColor)
            else -> Text(String.format(Locale.US, "%.3f s total", vm.duration),
                style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(fmt(vm.viewEnd), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Port of cursorReadout: F0 / F1–F4 / intensity chips at the cursor, plus the slice entry. */
@Composable
private fun CursorReadout(v: CursorValues, onSlice: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ValueChip("F0", v.f0, "Hz", PitchColor)
        v.formants.forEachIndexed { i, f -> ValueChip("F${i + 1}", f, "", FormantColor) }
        ValueChip("Int", v.intensity, "dB", IntensityColor)
        // [Android port] iOS showed the slice inline whenever the cursor was set; here it opens
        // as a bottom sheet on demand.
        TextButton(onClick = onSlice) { Text("Slice") }
    }
}

@Composable
private fun ValueChip(name: String, value: Double?, unit: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, color = color, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(
            value?.let { String.format(Locale.US, "%.0f", it) + if (unit.isEmpty()) "" else " $unit" }
                ?: "—",
            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
        )
    }
}

// ---- dialogs & sheets ----------------------------------------------------------------------------

/** Port of TimeRangeDialog (Zoom… / Play…). */
@Composable
private fun TimeRangeDialog(
    title: String,
    actionLabel: String,
    initFrom: String,
    initTo: String,
    onDismiss: () -> Unit,
    onCommit: (Double, Double) -> Unit,
) {
    var from by remember { mutableStateOf(initFrom) }
    var to by remember { mutableStateOf(initTo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("From (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(to, { to = it }, label = { Text("To (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = from.toDoubleOrNull()
                val b = to.toDoubleOrNull()
                if (a != null && b != null && b > a) onCommit(a, b)
                onDismiss()
            }) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Port of SpeakView: text + eSpeak language/voice, synthesized through the engine script path. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakSheet(
    text: String, onText: (String) -> Unit,
    language: String, onLanguage: (String) -> Unit,
    voice: String, onVoice: (String) -> Unit,
    onDismiss: () -> Unit,
    onSpeak: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Speak (eSpeak)", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(text, onText, Modifier.fillMaxWidth(),
                label = { Text("Text to speak") }, minLines = 2, maxLines = 5)
            DropdownField("Language", SPEAK_LANGUAGES, language, onLanguage)
            DropdownField("Voice", SPEAK_VOICES, voice, onVoice)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSpeak(); onDismiss() }) { Text("Speak") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A labeled value with a dropdown — replaces the iOS Form Picker rows. */
@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    value: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth()) {
            Text("$label: $value", maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

/** Port of SpectrumSliceView, presented as a bottom sheet; recomputed at the current cursor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliceSheet(vm: PraatViewModel, onDismiss: () -> Unit) {
    val t = vm.cursorTime
    var slice by remember { mutableStateOf<SpectrumSlice?>(null) }
    LaunchedEffect(t) { slice = t?.let { vm.spectrumSlice(it) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                t?.let { String.format(Locale.US, "Spectral slice at %.3f s", it) }
                    ?: "Spectral slice",
                style = MaterialTheme.typography.titleMedium,
            )
            val s = slice
            if (s == null) {
                Text("Tap the spectrogram to place the cursor first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Canvas(
                    Modifier.fillMaxWidth().height(160.dp)
                        .background(PaperColor, RoundedCornerShape(4.dp)),
                ) {
                    val n = s.db.size
                    if (n > 1 && s.dbMax > s.dbMin) {
                        val path = Path()
                        for (i in 0 until n) {
                            val x = size.width * i / (n - 1)
                            val frac = ((s.db[i] - s.dbMin) / (s.dbMax - s.dbMin)).coerceIn(0.0, 1.0)
                            val y = (size.height * (1 - frac)).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, Color.Black, style = Stroke(1.dp.toPx()))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val style = MaterialTheme.typography.labelSmall
                    val dim = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("0", style = style, color = dim)
                    Text(String.format(Locale.US, "%.0f–%.0f dB", s.dbMin, s.dbMax),
                        style = style, color = dim)
                    Text("${s.fmax.toInt()} Hz", style = style, color = dim)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---- helpers -----------------------------------------------------------------------------------

private fun fmt(x: Double): String = String.format(Locale.US, "%.3f", x)

/**
 * Copy a SAF document into cacheDir (the engine needs a filesystem path) and return
 * (file, object name without extension), or null on failure. Runs on Dispatchers.IO.
 */
private fun copyUriToCache(context: Context, uri: Uri): Pair<File, String>? = try {
    var display = "audio.wav"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) display = c.getString(0) }
    val safe = display.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val file = File(context.cacheDir, "open_$safe")
    val input = context.contentResolver.openInputStream(uri)
    if (input == null) null
    else {
        input.use { ins -> file.outputStream().use { ins.copyTo(it) } }
        Pair(file, safe.substringBeforeLast('.'))
    }
} catch (_: Exception) {
    null
}
