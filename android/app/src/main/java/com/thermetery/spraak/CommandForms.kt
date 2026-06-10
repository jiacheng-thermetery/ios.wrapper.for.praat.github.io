// CommandForms.kt — port of ios/app/CommandForms.swift: parameter dialogs for common Praat
// commands. Part of the Spraak derivative. GPL-3.0-or-later.
//
// A field-driven form whose values assemble a Praat script line (e.g. "To Pitch: 0, 75, 600").
// Curated specs cover the high-use commands; anything else can be typed into the Objects
// screen's free script field. ObjectsScreen runs the assembled line through
// PraatViewModel.runScript (prefixed with `selectObject:` unless the spec isCreate).
package com.thermetery.spraak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Kind of one form field (port of FieldKind). */
sealed interface FieldKind {
    data object Real : FieldKind
    data object Integer : FieldKind
    data object Text : FieldKind
    data object Bool : FieldKind   // [Android port] named Bool: `Boolean` would shadow kotlin.Boolean
    data class Choice(val options: List<String>) : FieldKind
}

/** One field of a command form; [value] is the default (port of CmdField). */
data class CmdField(
    val label: String,
    val value: String,
    val kind: FieldKind = FieldKind.Real,
) {
    /** The script argument for current value [v] of this field. */
    fun argument(v: String): String = when (kind) {
        FieldKind.Real, FieldKind.Integer -> v.trim().ifEmpty { "0" }
        FieldKind.Bool -> if (v == "1" || v.equals("yes", ignoreCase = true)) "\"yes\"" else "\"no\""
        // [Android port] double inner quotes (Praat's string escape); iOS passed them through raw.
        FieldKind.Text, is FieldKind.Choice -> "\"" + v.replace("\"", "\"\"") + "\""
    }
}

/** A parameterised command (port of CmdSpec). */
data class CmdSpec(
    val title: String,           // menu label, e.g. "To Pitch…"
    val command: String,         // script command name, e.g. "To Pitch"
    val fields: List<CmdField>,
    val isCreate: Boolean = false,   // true → run without a selection (Create…/Read…)
) {
    fun scriptLine(values: List<String>): String {
        val args = fields.zip(values).map { (f, v) -> f.argument(v) }
        return if (args.isEmpty()) command else "$command: ${args.joinToString(", ")}"
    }

    companion object {
        /** Specs for the New menu (object creation). */
        val creates: List<CmdSpec> = listOf(
            CmdSpec("Sound from formula…", "Create Sound from formula", listOf(
                CmdField("Name", "sound", FieldKind.Text),
                CmdField("Channels", "1", FieldKind.Integer),
                CmdField("Start time (s)", "0"),
                CmdField("End time (s)", "1"),
                CmdField("Sampling frequency (Hz)", "44100"),
                CmdField("Formula", "0.5*sin(2*pi*440*x)", FieldKind.Text),
            ), isCreate = true),
            CmdSpec("Sound as tone complex…", "Create Sound as tone complex", listOf(
                CmdField("Name", "tones", FieldKind.Text),
                CmdField("Start time (s)", "0"),
                CmdField("End time (s)", "1"),
                CmdField("Sampling frequency (Hz)", "44100"),
                CmdField("Phase", "cosine", FieldKind.Choice(listOf("cosine", "sine"))),
                CmdField("Frequency step (Hz)", "100"),
                CmdField("First frequency (Hz)", "0"),
                CmdField("Ceiling (Hz)", "0"),
                CmdField("Number of components", "0", FieldKind.Integer),
            ), isCreate = true),
            CmdSpec("TextGrid…", "Create TextGrid", listOf(
                CmdField("Start time (s)", "0"),
                CmdField("End time (s)", "1"),
                CmdField("Tier names", "phones words", FieldKind.Text),
                CmdField("Point tiers", "", FieldKind.Text),
            ), isCreate = true),
            CmdSpec("Table with columns…", "Create Table with column names", listOf(
                CmdField("Name", "table", FieldKind.Text),
                CmdField("Number of rows", "10", FieldKind.Integer),
                CmdField("Column names", "x y", FieldKind.Text),
            ), isCreate = true),
        )

        /** Specs for object actions, keyed by class. */
        val byClass: Map<String, List<CmdSpec>> = mapOf(
            "Sound" to listOf(
                CmdSpec("To Pitch…", "To Pitch", listOf(
                    CmdField("Time step (s)", "0"),
                    CmdField("Pitch floor (Hz)", "75"),
                    CmdField("Pitch ceiling (Hz)", "600"),
                )),
                CmdSpec("To Spectrogram…", "To Spectrogram", listOf(
                    CmdField("Window length (s)", "0.005"),
                    CmdField("Max frequency (Hz)", "5000"),
                    CmdField("Time step (s)", "0.002"),
                    CmdField("Frequency step (Hz)", "20"),
                    CmdField("Window shape", "Gaussian", FieldKind.Choice(listOf(
                        "Gaussian", "Hanning", "Hamming", "Bartlett", "Welch", "square"))),
                )),
                CmdSpec("To Formant (burg)…", "To Formant (burg)", listOf(
                    CmdField("Time step (s)", "0"),
                    CmdField("Max number of formants", "5"),
                    CmdField("Max formant (Hz)", "5500"),
                    CmdField("Window length (s)", "0.025"),
                    CmdField("Pre-emphasis from (Hz)", "50"),
                )),
                CmdSpec("To Intensity…", "To Intensity", listOf(
                    CmdField("Minimum pitch (Hz)", "100"),
                    CmdField("Time step (s)", "0"),
                    CmdField("Subtract mean", "yes", FieldKind.Bool),
                )),
                CmdSpec("To Harmonicity (cc)…", "To Harmonicity (cc)", listOf(
                    CmdField("Time step (s)", "0.01"),
                    CmdField("Minimum pitch (Hz)", "75"),
                    CmdField("Silence threshold", "0.1"),
                    CmdField("Periods per window", "1"),
                )),
                CmdSpec("Extract part…", "Extract part", listOf(
                    CmdField("Start time (s)", "0"),
                    CmdField("End time (s)", "1"),
                    CmdField("Window shape", "rectangular", FieldKind.Choice(listOf(
                        "rectangular", "Hanning", "Hamming", "Gaussian1", "Gaussian2"))),
                    CmdField("Relative width", "1"),
                    CmdField("Preserve times", "no", FieldKind.Bool),
                )),
                CmdSpec("Filter (pass Hann band)…", "Filter (pass Hann band)", listOf(
                    CmdField("From frequency (Hz)", "0"),
                    CmdField("To frequency (Hz)", "5000"),
                    CmdField("Smoothing (Hz)", "100"),
                )),
                CmdSpec("Filter (stop Hann band)…", "Filter (stop Hann band)", listOf(
                    CmdField("From frequency (Hz)", "0"),
                    CmdField("To frequency (Hz)", "500"),
                    CmdField("Smoothing (Hz)", "100"),
                )),
                CmdSpec("Resample…", "Resample", listOf(
                    CmdField("New sampling frequency (Hz)", "22050"),
                    CmdField("Precision (samples)", "50", FieldKind.Integer),
                )),
                CmdSpec("Scale peak…", "Scale peak", listOf(
                    CmdField("New peak", "0.99"),
                )),
                CmdSpec("Scale intensity…", "Scale intensity", listOf(
                    CmdField("New average intensity (dB SPL)", "70"),
                )),
            ),
            "Pitch" to listOf(
                CmdSpec("Get value at time…", "Get value at time", listOf(
                    CmdField("Time (s)", "0.5"),
                    CmdField("Unit", "Hertz", FieldKind.Choice(listOf(
                        "Hertz", "mel", "semitones re 100 Hz"))),
                    CmdField("Interpolation", "linear", FieldKind.Choice(listOf("nearest", "linear"))),
                )),
                CmdSpec("Get mean…", "Get mean", listOf(
                    CmdField("From time (s)", "0"),
                    CmdField("To time (s)", "0"),
                    CmdField("Unit", "Hertz", FieldKind.Choice(listOf(
                        "Hertz", "mel", "semitones re 100 Hz"))),
                )),
            ),
            "Formant" to listOf(
                CmdSpec("Get value at time…", "Get value at time", listOf(
                    CmdField("Formant number", "1", FieldKind.Integer),
                    CmdField("Time (s)", "0.5"),
                    CmdField("Unit", "hertz", FieldKind.Choice(listOf("hertz", "bark"))),
                    CmdField("Interpolation", "linear", FieldKind.Choice(listOf("linear"))),
                )),
            ),
            "Intensity" to listOf(
                CmdSpec("Get value at time…", "Get value at time", listOf(
                    CmdField("Time (s)", "0.5"),
                    CmdField("Interpolation", "cubic", FieldKind.Choice(listOf(
                        "nearest", "linear", "cubic"))),
                )),
            ),
        )
    }
}

/**
 * Port of CommandFormView. [Android port] iOS used a sheet with a Form; Material3 gets an
 * AlertDialog with a scrollable field column. Tapping Run calls [onRun] with the assembled
 * script line and then [onDismiss]; Cancel just dismisses.
 */
@Composable
fun CommandFormDialog(spec: CmdSpec, onDismiss: () -> Unit, onRun: (String) -> Unit) {
    val values = remember(spec) { spec.fields.map { it.value }.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spec.title.removeSuffix("…")) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                spec.fields.forEachIndexed { i, field ->
                    CmdFieldRow(field, values[i]) { values[i] = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(spec.scriptLine(values)); onDismiss() }) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CmdFieldRow(field: CmdField, value: String, onChange: (String) -> Unit) {
    when (val kind = field.kind) {
        FieldKind.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = value == "yes" || value == "1",
                onCheckedChange = { onChange(if (it) "yes" else "no") },
            )
        }
        is FieldKind.Choice -> {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(field.label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    kind.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { onChange(option); expanded = false },
                        )
                    }
                }
            }
        }
        else -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            label = { Text(field.label) },
            // [Android port] iOS .numbersAndPunctuation keyboard for numeric fields
            keyboardOptions = KeyboardOptions(
                keyboardType = when (kind) {
                    FieldKind.Integer -> KeyboardType.Number
                    FieldKind.Real -> KeyboardType.Decimal
                    else -> KeyboardType.Text
                },
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
