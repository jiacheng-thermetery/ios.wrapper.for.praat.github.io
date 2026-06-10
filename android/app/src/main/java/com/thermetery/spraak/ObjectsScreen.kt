// ObjectsScreen.kt — port of ios/app/ObjectsView.swift: the native "Objects window" backed by
// the live Praat engine. Part of the Spraak derivative. GPL-3.0-or-later.
//
// The engine's object table is global and persists across runScript calls, so this screen IS
// the engine state: commands run by generating `selectObject: <ids>` + the command line and
// executing it through the interpreter — the same mechanism that powers thousands of Praat
// commands.
//
// [Android port] Unlike iOS (which kept the selection UI-side in ObjectsModel.selected), the
// checkmarks here mirror the engine's own selection flag (objectInfo "...|selected") and taps
// write it back via selectObject: — so after e.g. "To Pitch" the newly created object shows up
// selected, exactly like desktop Praat.
package com.thermetery.spraak

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

// A small curated command palette per class (everything else: forms menu or the script field).
// [Android port] the iOS Sound palette's "Play" entry is a dedicated toolbar button instead:
// Praat's own `Play` command would block the engine thread waiting on an audio device, so we
// route the PCM through the app's AudioEngine.
private val PALETTE: Map<String, List<Pair<String, String>>> = mapOf(
    "Sound" to listOf(
        "To Pitch" to "To Pitch: 0, 75, 600",
        "To Spectrogram" to "To Spectrogram: 0.005, 5000, 0.002, 20, \"Gaussian\"",
        "To Formant" to "To Formant (burg): 0, 5, 5500, 0.025, 50",
        "To Intensity" to "To Intensity: 100, 0, \"yes\"",
        "To Spectrum" to "To Spectrum: \"yes\"",
        "To Harmonicity" to "To Harmonicity (cc): 0.01, 75, 0.1, 1",
        "To mono" to "Convert to mono",
        "To stereo" to "Convert to stereo",
    ),
    "Pitch" to listOf(
        "Get mean" to "Get mean: 0, 0, \"Hertz\"",
        "Get min" to "Get minimum: 0, 0, \"Hertz\", \"parabolic\"",
        "Get max" to "Get maximum: 0, 0, \"Hertz\", \"parabolic\"",
    ),
    "Formant" to listOf(
        "Mean F1" to "Get mean: 1, 0, 0, \"hertz\"",
        "Mean F2" to "Get mean: 2, 0, 0, \"hertz\"",
    ),
    "Intensity" to listOf(
        "Get mean" to "Get mean: 0, 0, \"energy\"",
        "Get max" to "Get maximum: 0, 0, \"parabolic\"",
    ),
    "Spectrum" to listOf(
        "Centre of gravity" to "Get centre of gravity: 2",
        "Std dev" to "Get standard deviation: 2",
    ),
    "Harmonicity" to listOf("Get mean" to "Get mean: 0, 0"),
    "Table" to listOf("List" to "List: \"no\"", "# rows" to "Get number of rows"),
    "TextGrid" to listOf("# tiers" to "Get number of tiers"),
)

// New-menu one-shot creations (no form needed); CmdSpec.creates supplies the parameterised ones.
private val QUICK_CREATES = listOf(
    "Sound (440 Hz tone)" to "Create Sound from formula: \"tone\", 1, 0, 1, 44100, ~ 0.5*sin(2*pi*440*x)",
    "Sound as tone complex" to "Create Sound as tone complex: \"tones\", 0, 1, 44100, \"cosine\", 100, 0, 0, 0",
    "TextGrid" to "Create TextGrid: 0, 1, \"phones words\", \"\"",
    "Table (10 rows)" to "Create Table with column names: \"table\", 10, \"x y\"",
    "Strings (tokens)" to "Create Strings as tokens: \"the quick brown fox\", \" \"",
    "Matrix (10×10)" to "Create simple Matrix: \"m\", 10, 10, ~ row + col",
    "KlattGrid example" to "Create KlattGrid example",
)

private fun praatQuote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

@Composable
fun ObjectsScreen(vm: PraatViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var output by remember { mutableStateOf("") }
    var scriptField by remember { mutableStateOf("") }
    var activeSpec by remember { mutableStateOf<CmdSpec?>(null) }
    var showDraw by remember { mutableStateOf(false) }
    var nameAction by remember { mutableStateOf<String?>(null) }   // "Rename" | "Copy"
    var nameText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val objects = vm.objects
    val selectedIds = objects.filter { it.selected }.map { it.id }
    val selectedClasses = objects.filter { it.selected }.map { it.className }.distinct()
    val selectedSoundCount = objects.count { it.selected && it.className == "Sound" }
    val specsForSelection = selectedClasses.firstOrNull()?.let { CmdSpec.byClass[it] } ?: emptyList()

    // [Android port] SpraakApp swaps screens with a `when`, so entering the tab re-runs this
    // effect — replaces iOS's onAppear + onChange(store.tab) refresh (picks up sounds recorded
    // or scripted in other tabs).
    LaunchedEffect(Unit) { vm.refreshObjects() }

    /** Run a command on the current selection (prepends `selectObject:` for the selected ids). */
    fun runOnSelection(command: String) {
        scope.launch {
            val sel = vm.objects.filter { it.selected }.map { it.id }
            val script = if (sel.isEmpty()) command
                         else "selectObject: " + sel.joinToString(", ") + "\n" + command
            output = vm.runScript(script)
            vm.refreshObjects()
        }
    }

    /** Run a raw script (e.g. a Create… / Read from file…). */
    fun runRaw(script: String) {
        scope.launch {
            output = vm.runScript(script)
            vm.refreshObjects()
        }
    }

    /** Toggle one row; the engine's selection is the source of truth. */
    fun toggleSelection(id: Int) {
        scope.launch {
            val ids = vm.objects.filter { it.selected }.map { it.id }.toMutableList()
            if (!ids.remove(id)) ids.add(id)
            vm.selectObjects(ids.sorted())
        }
    }

    /** Start/stop a recording; on stop add the take to the object list (New ▸ Record mono Sound…). */
    fun recordSound() {
        vm.audio.toggleRecording { samples, rate ->
            if (samples.isNotEmpty()) scope.launch {
                vm.engine { PraatEngine.addSoundObject(samples, rate, "recording") }
                vm.refreshObjects()
            }
        }
    }

    /** Send the first selected Sound object to the Analyze tab (shared engine). */
    fun analyzeSelected() {
        scope.launch {
            val name = vm.objects.firstOrNull { it.selected && it.className == "Sound" }?.name ?: "sound"
            if (!vm.selectedSoundToAnalyze(name)) output = "Select a Sound object to analyze."
        }
    }

    /** Play the first selected Sound through the app's AudioEngine (see PALETTE note). */
    fun playSelected() {
        scope.launch {
            val pcm = vm.selectedSoundPCM()
            if (pcm == null) output = "Select a Sound object to play."
            else vm.playSamples(pcm.first, pcm.second)
        }
    }

    // [Android port] replaces the iOS fileImporter: Praat needs a real filesystem path, so the
    // content:// stream is copied into cacheDir first (PraatViewModel.openWavViaScript contract).
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val display = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.substringAfterLast('/') ?: "imported"
            val tmp = File(context.cacheDir, display)
            val copied = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { input.copyTo(it) }
                    } != null
                } catch (_: Exception) { false }
            }
            output = if (copied) vm.openWavViaScript(tmp.absolutePath, sendToAnalyze = false)
                     else "Could not read file."
        }
    }

    /** Copy the engine-written temp file into the document the user just created. */
    fun exportInto(uri: Uri?) {
        val file = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(file).use { it.copyTo(out) }
                    }
                } catch (_: Exception) { /* user can retry */ }
            }
        }
    }
    // [Android port] iOS shared via UIActivityViewController; Android uses SAF CreateDocument so
    // no FileProvider/manifest changes are needed. Mime is fixed per launcher, hence two of them.
    val saveWavLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-wav")) { exportInto(it) }
    val saveTxtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { exportInto(it) }

    /** Write the selected object to a temp file (WAV for Sound, Praat text otherwise) and export. */
    fun saveSelected() {
        val obj = objects.firstOrNull { it.selected } ?: return
        scope.launch {
            val isSound = obj.className == "Sound"
            val base = obj.name.ifEmpty { obj.className }.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val file = File(context.cacheDir, base + if (isSound) ".wav" else ".txt")
            withContext(Dispatchers.IO) { file.delete() }
            val cmd = (if (isSound) "Save as WAV file: " else "Save as text file: ") +
                praatQuote(file.absolutePath)
            val result = vm.runScript("selectObject: ${obj.id}\n$cmd")
            vm.refreshObjects()
            if (withContext(Dispatchers.IO) { file.exists() }) {
                pendingExport = file
                if (isSound) saveWavLauncher.launch(file.name) else saveTxtLauncher.launch(file.name)
            } else {
                output = result
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ---- toolbar -------------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObjToolbarMenu("New", Icons.Filled.Add) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Record mono Sound…") },
                        leadingIcon = { Icon(Icons.Filled.FiberManualRecord, null) },
                        onClick = { dismiss(); recordSound() },
                    )
                    HorizontalDivider()
                    QUICK_CREATES.forEach { (label, script) ->
                        DropdownMenuItem(text = { Text(label) },
                            onClick = { dismiss(); runRaw(script) })
                    }
                    HorizontalDivider()
                    CmdSpec.creates.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.title) },
                            onClick = { dismiss(); activeSpec = spec })
                    }
                }
                ObjToolButton("Open", Icons.Filled.FolderOpen) { openLauncher.launch(arrayOf("*/*")) }
                ObjToolButton("Analyze", Icons.Filled.GraphicEq, enabled = selectedSoundCount > 0) {
                    analyzeSelected()
                }
                ObjToolButton("Play", Icons.Filled.PlayArrow, enabled = selectedSoundCount > 0) {
                    playSelected()
                }
                ObjToolbarMenu("Commands", Icons.Filled.Tune,
                    enabled = specsForSelection.isNotEmpty()) { dismiss ->
                    specsForSelection.forEach { spec ->
                        DropdownMenuItem(text = { Text(spec.title) },
                            onClick = { dismiss(); activeSpec = spec })
                    }
                }
                ObjToolbarMenu("Combine", Icons.Filled.Layers,
                    enabled = selectedSoundCount >= 2) { dismiss ->
                    DropdownMenuItem(text = { Text("Combine to stereo") },
                        onClick = { dismiss(); runOnSelection("Combine to stereo") })
                    DropdownMenuItem(text = { Text("Concatenate") },
                        onClick = { dismiss(); runOnSelection("Concatenate") })
                }
                ObjToolButton("Rename", Icons.Filled.Edit, enabled = selectedIds.size == 1) {
                    nameText = ""; nameAction = "Rename"
                }
                ObjToolButton("Copy", Icons.Filled.ContentCopy, enabled = selectedIds.size == 1) {
                    nameText = objects.firstOrNull { it.selected }?.name ?: ""
                    nameAction = "Copy"
                }
                // [Android port] "Inspect" = Praat's universal Info command, shown in the output box.
                ObjToolButton("Inspect", Icons.Filled.Search, enabled = selectedIds.size == 1) {
                    runOnSelection("Info")
                }
                ObjToolButton("Remove", Icons.Filled.Delete, enabled = selectedIds.isNotEmpty()) {
                    scope.launch { output = vm.removeObjects(selectedIds) }
                }
                ObjToolButton("Draw", Icons.Filled.Image, enabled = selectedIds.isNotEmpty()) {
                    showDraw = true
                }
                ObjToolButton("Save", Icons.Filled.Save, enabled = selectedIds.size == 1) {
                    saveSelected()
                }
                ObjToolButton("Refresh", Icons.Filled.Refresh) {
                    scope.launch { vm.refreshObjects() }
                }
            }

            // ---- object list ----------------------------------------------------------------
            OutlinedCard(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(objects, key = { it.id }) { obj ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { toggleSelection(obj.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (obj.selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = if (obj.selected) "Selected" else "Not selected",
                                tint = if (obj.selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "${obj.id}.",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(obj.className,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold)
                            Text(
                                obj.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (objects.isEmpty()) {
                        item {
                            Text(
                                "No objects. Use New or Open, or create one in the Script tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }

            // ---- per-class quick command palette ---------------------------------------------
            if (selectedIds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    selectedClasses.forEach { cls ->
                        (PALETTE[cls] ?: emptyList()).forEach { (label, cmd) ->
                            FilledTonalButton(
                                onClick = { runOnSelection(cmd) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // ---- free script field ------------------------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = scriptField,
                    onValueChange = { scriptField = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = {
                        Text("command on selection… (e.g. To Pitch: 0, 75, 600)",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
                Button(onClick = { if (scriptField.isNotBlank()) runOnSelection(scriptField) }) {
                    Text("Run")
                }
            }

            // ---- output -------------------------------------------------------------------------
            Text(
                "Output",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedCard(
                Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                SelectionContainer {
                    Text(
                        output,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            }
        }

        // ---- recording banner (overlay, like the iOS .overlay(alignment: .top)) -----------------
        if (vm.audio.isRecording) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 4.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = "Recording",
                        tint = MaterialTheme.colorScheme.error)
                    Text(
                        String.format(Locale.US, "%.1f s", vm.audio.recordSeconds),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    LinearProgressIndicator(
                        progress = { vm.audio.recordLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.width(64.dp),
                    )
                    FilledTonalButton(
                        onClick = { recordSound() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------------------------
    if (showDraw) DrawDialog(vm = vm, onDismiss = { showDraw = false })

    activeSpec?.let { spec ->
        CommandFormDialog(
            spec = spec,
            onDismiss = { activeSpec = null },
            onRun = { line -> if (spec.isCreate) runRaw(line) else runOnSelection(line) },
        )
    }

    nameAction?.let { action ->
        AlertDialog(
            onDismissRequest = { nameAction = null },
            title = { Text(if (action == "Rename") "Rename object" else "Copy object") },
            text = {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    singleLine = true,
                    label = { Text("name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Praat: `Rename: "name"` / `Copy: "name"` act on the selected object(s)
                    if (nameText.isNotEmpty()) runOnSelection("$action: ${praatQuote(nameText)}")
                    nameAction = null
                }) { Text(action) }
            },
            dismissButton = { TextButton(onClick = { nameAction = null }) { Text("Cancel") } },
        )
    }
}

// ---- small toolbar building blocks (private to avoid clashes with other screens) ----------------

@Composable
private fun ObjToolButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun ObjToolbarMenu(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ObjToolButton(label, icon, enabled) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}
