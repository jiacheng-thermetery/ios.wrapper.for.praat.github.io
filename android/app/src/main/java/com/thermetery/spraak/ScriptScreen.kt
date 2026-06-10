// ScriptScreen.kt — port of ScriptConsoleView (ios/app/ContentView.swift) for the Spraak
// derivative. GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
//
// Monospace Praat-script editor + Run + scrollable Info-window output. The script runs through
// vm.engine { } (the single engine thread), exactly like iOS ran it off the main queue.
package com.thermetery.spraak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** The script the console opens with — same starter as the iOS ScriptConsoleView. */
private val STARTER_SCRIPT = """
    writeInfoLine: "Spraak"
    appendInfoLine: "Engine: Praat ", praatVersion${'$'}
""".trimIndent()

// [Android port] A few self-contained snippets reachable from the history menu (iOS only
// preloads the starter; here the starter stays restorable after the user edits it).
private val SNIPPETS = listOf(
    "Version info (starter)" to STARTER_SCRIPT,
    "Pure tone → mean pitch" to """
        Create Sound as pure tone: "tone", 1, 0, 0.4, 44100, 440, 0.2, 0.01, 0.01
        To Pitch: 0, 75, 600
        f = Get mean: 0, 0, "Hertz"
        writeInfoLine: "Mean pitch: ", f, " Hz"
    """.trimIndent(),
    "Speech synthesis (eSpeak)" to """
        synth = Create SpeechSynthesizer: "English (Great Britain)", "Female1"
        selectObject: synth
        sound = To Sound: "Hello from Spraak", "no"
        removeObject: synth
        selectObject: sound
        writeInfoLine: "Synthesized ", selected${'$'} ()
    """.trimIndent(),
    "List objects" to """
        select all
        n = numberOfSelected ()
        writeInfoLine: n, " object(s):"
        for i to n
            appendInfoLine: i, ". ", selected${'$'} (i)
        endfor
    """.trimIndent(),
)

// [Android port] iOS's TabView keeps a hidden tab's @State alive; SpraakApp swaps screens with a
// plain `when`, which destroys the composition. The console text/output/run-state therefore live
// in this process-wide holder so they survive tab switches (like every other screen does via the
// view model).
private object ScriptConsoleState {
    var script by mutableStateOf(STARTER_SCRIPT)
    var output by mutableStateOf("Tap Run to execute the script.")
    var running by mutableStateOf(false)
    val history = mutableStateListOf<String>()

    fun push(src: String) {
        history.remove(src)
        history.add(0, src)
        while (history.size > 8) history.removeAt(history.size - 1)
    }
}

/** Port of ScriptConsoleView (tab 5). */
@Composable
fun ScriptScreen(vm: PraatViewModel) {
    var menuOpen by remember { mutableStateOf(false) }

    val onRun: () -> Unit = onRun@{
        if (ScriptConsoleState.running) return@onRun
        ScriptConsoleState.running = true
        val src = ScriptConsoleState.script
        // [Android port] viewModelScope, not rememberCoroutineScope: switching tabs mid-run must
        // not cancel the run (the engine call is blocking and the `running` flag must clear).
        vm.viewModelScope.launch {
            val result = vm.engine { PraatEngine.runScript(src) }
            ScriptConsoleState.output = result
            ScriptConsoleState.push(src)
            ScriptConsoleState.running = false
            vm.refreshObjects()   // [Android port] scripts may create/remove objects; keep the Objects tab in sync
        }
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Praat script", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.History, contentDescription = "Snippets and history")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    SNIPPETS.forEach { (name, code) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { ScriptConsoleState.script = code; menuOpen = false },
                        )
                    }
                    if (ScriptConsoleState.history.isNotEmpty()) {
                        HorizontalDivider()
                        ScriptConsoleState.history.forEach { src ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        src.lineSequence().first().take(40),
                                        maxLines = 1,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                onClick = { ScriptConsoleState.script = src; menuOpen = false },
                            )
                        }
                    }
                }
            }
            Button(onClick = onRun, enabled = !ScriptConsoleState.running && vm.engineReady) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (ScriptConsoleState.running) "Running…" else "Run")
            }
        }

        OutlinedTextField(
            value = ScriptConsoleState.script,
            onValueChange = { ScriptConsoleState.script = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,   // iOS: autocorrectionDisabled + no autocapitalization
            ),
        )

        // Output pane: the Info-window (or error) text, scrollable and selectable like on iOS.
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        ScriptConsoleState.output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
