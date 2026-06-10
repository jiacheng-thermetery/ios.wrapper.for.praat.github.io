// SettingsSheet.kt — port of SettingsView.swift (AnalysisSettingsView): the analysis-settings
// sheet mirroring Praat's spectrogram/pitch/formant/intensity settings dialogs.
// Part of the Spraak derivative. GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
package com.thermetery.spraak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Analysis-settings sheet. Edits a copy of [PraatViewModel.settings] and commits it through
 * [PraatViewModel.applySettings] (which pushes pitch/formant params to the engine and redraws).
 * [Android port] the iOS Form sheet becomes a Material3 modal bottom sheet; numbers are edited
 * as text (decimal keyboard) and fields that fail to parse keep their previous value on Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: PraatViewModel, onDismiss: () -> Unit) {
    val current = remember { vm.settings }

    var specMaxFreq by remember { mutableStateOf(num(current.spectrogramMaxFreq)) }
    var specWindow by remember { mutableStateOf(num(current.spectrogramWindow)) }
    var specRange by remember { mutableStateOf(num(current.spectrogramDynamicRange)) }
    var pitchFloor by remember { mutableStateOf(num(current.pitchFloor)) }
    var pitchCeiling by remember { mutableStateOf(num(current.pitchCeiling)) }
    var formantMaxFreq by remember { mutableStateOf(num(current.formantMaxFreq)) }
    var formantCount by remember { mutableIntStateOf(current.formantCount) }
    var formantWindow by remember { mutableStateOf(num(current.formantWindow)) }
    var intensityMin by remember { mutableStateOf(num(current.intensityMin)) }
    var intensityMax by remember { mutableStateOf(num(current.intensityMax)) }

    fun resetToDefaults() {
        val d = AnalysisSettings()
        specMaxFreq = num(d.spectrogramMaxFreq)
        specWindow = num(d.spectrogramWindow)
        specRange = num(d.spectrogramDynamicRange)
        pitchFloor = num(d.pitchFloor)
        pitchCeiling = num(d.pitchCeiling)
        formantMaxFreq = num(d.formantMaxFreq)
        formantCount = d.formantCount
        formantWindow = num(d.formantWindow)
        intensityMin = num(d.intensityMin)
        intensityMax = num(d.intensityMax)
    }

    fun apply() {
        vm.applySettings(
            AnalysisSettings(
                spectrogramMaxFreq = specMaxFreq.toDoubleOrNull() ?: current.spectrogramMaxFreq,
                spectrogramWindow = specWindow.toDoubleOrNull() ?: current.spectrogramWindow,
                spectrogramDynamicRange = specRange.toDoubleOrNull()
                    ?: current.spectrogramDynamicRange,
                pitchFloor = pitchFloor.toDoubleOrNull() ?: current.pitchFloor,
                pitchCeiling = pitchCeiling.toDoubleOrNull() ?: current.pitchCeiling,
                formantMaxFreq = formantMaxFreq.toDoubleOrNull() ?: current.formantMaxFreq,
                formantCount = formantCount.coerceIn(1, 5),
                formantWindow = formantWindow.toDoubleOrNull() ?: current.formantWindow,
                intensityMin = intensityMin.toDoubleOrNull() ?: current.intensityMin,
                intensityMax = intensityMax.toDoubleOrNull() ?: current.intensityMax,
            ),
        )
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Analysis settings", style = MaterialTheme.typography.titleLarge)

            SectionHeader("Spectrogram")
            NumberField("View range (Hz)", specMaxFreq) { specMaxFreq = it }
            NumberField("Window length (s)", specWindow) { specWindow = it }
            NumberField("Dynamic range (dB)", specRange) { specRange = it }

            SectionHeader("Pitch")
            NumberField("Floor (Hz)", pitchFloor) { pitchFloor = it }
            NumberField("Ceiling (Hz)", pitchCeiling) { pitchCeiling = it }

            SectionHeader("Formant")
            NumberField("Max frequency (Hz)", formantMaxFreq) { formantMaxFreq = it }
            // [Android port] iOS Stepper -> minus/plus icon buttons (Material3 has no stepper)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Number of formants: $formantCount",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { formantCount-- }, enabled = formantCount > 1) {
                    Icon(Icons.Filled.Remove, contentDescription = "Fewer formants")
                }
                IconButton(onClick = { formantCount++ }, enabled = formantCount < 5) {
                    Icon(Icons.Filled.Add, contentDescription = "More formants")
                }
            }
            NumberField("Window length (s)", formantWindow) { formantWindow = it }

            SectionHeader("Intensity")
            NumberField("View min (dB)", intensityMin) { intensityMin = it }
            NumberField("View max (dB)", intensityMax) { intensityMax = it }

            TextButton(onClick = { resetToDefaults() }) { Text("Reset to defaults") }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { apply() }) { Text("Apply") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.width(130.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End),
        )
    }
}

/** Plain decimal rendering without scientific notation or trailing ".0" (e.g. 5000, 0.005). */
private fun num(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else java.math.BigDecimal(v).stripTrailingZeros().toPlainString()
