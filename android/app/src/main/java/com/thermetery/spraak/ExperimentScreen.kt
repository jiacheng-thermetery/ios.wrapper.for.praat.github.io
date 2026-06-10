// ExperimentScreen.kt — Kotlin/Compose port of ios/app/ExperimentMFCView.swift for the Spraak
// derivative. Praat ExperimentMFC perception-experiment runner: plays each trial's stimulus,
// records the multiple-forced-choice response (+ reaction time), then exports the results as
// CSV. GPL-3.0-or-later.
package com.thermetery.spraak

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class MfcPhase { Intro, Running, Paused, Done }

/** One response button: 1-based engine index, label, and its 0..1 rect (Praat coords:
 *  left/right fractions of the width, bottom/top fractions of the height, origin bottom-left). */
private data class MfcButton(
    val index: Int, val label: String,
    val l: Double, val r: Double, val b: Double, val t: Double,
)

@Composable
fun ExperimentScreen(vm: PraatViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var phase by remember { mutableStateOf(MfcPhase.Intro) }
    var nTrials by remember { mutableIntStateOf(0) }
    var trial by remember { mutableIntStateOf(1) }
    var buttons by remember { mutableStateOf<List<MfcButton>>(emptyList()) }
    // start / run / pause / end texts (mfcText 0..3)
    var texts by remember { mutableStateOf(List(4) { "" }) }
    var stimText by remember { mutableStateOf("") }
    var stimIndex by remember { mutableIntStateOf(0) }      // expected response for the demo score
    var stimPlayedAt by remember { mutableLongStateOf(0L) } // elapsedRealtime ms at playback start
    var correct by remember { mutableIntStateOf(0) }
    var answered by remember { mutableIntStateOf(0) }
    var resultsCSV by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("demo") }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }          // gates taps during engine work

    /** Texts + response-button geometry of the loaded experiment, in one engine hop. */
    suspend fun loadCommon() {
        val (t, btns) = vm.engine {
            val tt = (0..3).map { PraatEngine.mfcText(it) }
            val count = PraatEngine.mfcResponseCount()
            val bb = (1..count).mapNotNull { i ->
                val p = PraatEngine.mfcResponseInfo(i).split("|")
                if (p.size < 5) null else MfcButton(
                    i, p[0],
                    p[1].toDoubleOrNull() ?: 0.0, p[2].toDoubleOrNull() ?: 1.0,
                    p[3].toDoubleOrNull() ?: 0.0, p[4].toDoubleOrNull() ?: 1.0,
                )
            }
            Pair(tt, bb)
        }
        texts = t
        buttons = btns
    }

    fun loadDemo() = scope.launch {
        busy = true
        nTrials = vm.engine { PraatEngine.mfcCreateDemo() }
        loadCommon()
        phase = MfcPhase.Intro
        busy = false
    }

    fun loadSelected() = scope.launch {
        busy = true
        val n = vm.engine { PraatEngine.mfcUseSelected() }
        if (n > 0) {
            nTrials = n
            loadCommon()
            phase = MfcPhase.Intro
        } else {
            nTrials = 0
            resultsCSV = "No ExperimentMFC object is selected.\n" +
                "Open a .MFCexperiment in the Objects tab first."
            phase = MfcPhase.Done
        }
        busy = false
    }

    suspend fun playStimulus() {
        val (txt, idx) = vm.engine {
            Pair(PraatEngine.mfcStimulusText(trial), PraatEngine.mfcStimulusForTrial(trial))
        }
        stimText = txt
        stimIndex = idx
        vm.mfcStimulusSound(trial)?.let { (samples, rate) -> vm.audio.play(samples, rate) }
        // [Android port] reaction time measured from playback start, like iOS (stimPlayedAt
        // was set right after audio.play returned there too).
        stimPlayedAt = SystemClock.elapsedRealtime()
    }

    fun start() = scope.launch {
        busy = true
        trial = 1; correct = 0; answered = 0
        phase = MfcPhase.Running
        playStimulus()
        busy = false
    }

    suspend fun finish() {
        resultsCSV = vm.engine { PraatEngine.mfcResultsCSV() }
        phase = MfcPhase.Done
    }

    fun respond(iresp: Int) {
        if (busy || phase != MfcPhase.Running) return
        busy = true
        val rt = (SystemClock.elapsedRealtime() - stimPlayedAt) / 1000.0
        scope.launch {
            val answeredTrial = trial
            vm.engine { PraatEngine.mfcRecordResponse(answeredTrial, iresp, 0.0, rt) }
            answered++
            if (stimIndex == iresp) correct++   // demo: stimulus i ↔ response i
            trial++
            if (trial > nTrials) finish() else playStimulus()
            busy = false
        }
    }

    // [Android port] no FileProvider is declared in the manifest, so the CSV is shared as the
    // text payload of an ACTION_SEND chooser instead of a file URL (same share-sheet UX).
    fun exportCSV() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "mfc_results.csv")
            putExtra(Intent.EXTRA_TEXT, resultsCSV)
        }
        context.startActivity(Intent.createChooser(send, "Export results CSV"))
    }

    LaunchedEffect(vm.engineReady) {
        if (vm.engineReady && nTrials == 0) loadDemo()      // port of onAppear
    }

    Column(Modifier.fillMaxSize()) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ExperimentMFC", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.Science, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Experiment")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Demo: tone height") },
                        onClick = { menuOpen = false; source = "demo"; loadDemo() })
                    DropdownMenuItem(
                        text = { Text("Run selected (from file)") },
                        onClick = { menuOpen = false; source = "file"; loadSelected() })
                }
            }
        }
        HorizontalDivider()

        when (phase) {
            MfcPhase.Intro -> CenterCard(text = texts[0], button = "Begin") { start() }
            // [Android port] explicit pause phase (mfcText 2); iOS never showed it.
            MfcPhase.Paused -> CenterCard(text = texts[2].ifEmpty { "Paused." },
                button = "Continue") {
                scope.launch { phase = MfcPhase.Running; playStimulus() }   // replay on resume
            }
            MfcPhase.Done -> DoneView(
                endText = texts[3],
                score = if (source == "demo" && answered > 0) "Score: $correct / $answered correct" else null,
                resultsCSV = resultsCSV,
                onExport = { exportCSV() },
                onRunAgain = { start() },
            )
            MfcPhase.Running -> Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Trial $trial / $nTrials", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.audio.stopPlayback(); phase = MfcPhase.Paused }) {
                        Icon(Icons.Filled.Pause, contentDescription = "Pause")
                    }
                    TextButton(onClick = { scope.launch { playStimulus() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Replay")
                    }
                }
                Text(texts[1], style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                if (stimText.isNotEmpty()) {
                    Text(stimText, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                // response buttons positioned by their ExperimentMFC 0..1 rects
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(4.dp)) {
                    val w = maxWidth
                    val h = maxHeight
                    for (b in buttons) {
                        Surface(
                            onClick = { respond(b.index) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                // Praat rect origin is bottom-left; Compose's is top-left
                                .offset(x = w * b.l.toFloat(), y = h * (1.0 - b.t).toFloat())
                                .size(w * (b.r - b.l).toFloat(), h * (b.t - b.b).toFloat()),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(b.label, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Centered text + one prominent button (intro/pause cards). Port of ExperimentMFCView.card. */
@Composable
private fun CenterCard(text: String, button: String, action: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.padding(10.dp))
        Button(onClick = action) { Text(button) }
    }
}

/** End screen: end text, optional demo score, the CSV, export + run-again. */
@Composable
private fun DoneView(
    endText: String,
    score: String?,
    resultsCSV: String,
    onExport: () -> Unit,
    onRunAgain: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(endText, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
        if (score != null) Text(score, style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            Text(resultsCSV, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExport) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Export CSV")
            }
            OutlinedButton(onClick = onRunAgain) { Text("Run again") }
        }
    }
}
