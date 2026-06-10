// SpraakApp.kt — root UI for the Spraak derivative (port of ContentView's TabView + AboutView).
// GPL-3.0-or-later. UNOFFICIAL modified version of Praat.
package com.thermetery.spraak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private class SpraakTab(val label: String, val icon: ImageVector)

// [Android port] SF Symbols -> Material icons in spirit: waveform→GraphicEq,
// list.bullet→FormatListBulleted, mouth→RecordVoiceOver, slider.vertical.3→Tune,
// checklist→Checklist, terminal→Terminal.
private val TABS = listOf(
    SpraakTab("Analyze", Icons.Filled.GraphicEq),
    SpraakTab("Objects", Icons.AutoMirrored.Filled.FormatListBulleted),
    SpraakTab("Vowel", Icons.Filled.RecordVoiceOver),
    SpraakTab("Manip.", Icons.Filled.Tune),
    SpraakTab("Experiment", Icons.Filled.Checklist),
    SpraakTab("Script", Icons.Filled.Terminal),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpraakApp(vm: PraatViewModel = viewModel()) {
    SpraakTheme {
        var showAbout by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                // [Android port] iOS floats an info button over the TabView; Android gets a
                // proper top app bar with the About action.
                CenterAlignedTopAppBar(
                    title = { Text("Spraak") },
                    actions = {
                        IconButton(onClick = { showAbout = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = "About")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, tab ->
                        NavigationBarItem(
                            selected = vm.selectedTab == i,
                            onClick = { vm.selectedTab = i },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, maxLines = 1) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // [Android port] The tab index lives in the shared view model (iOS AppStore.tab)
                // so e.g. the Objects screen can switch to Analyze programmatically. A plain
                // `when` replaces TabView; screen-local state lives in the view model, so losing
                // composition state of hidden tabs is fine.
                when (vm.selectedTab) {
                    0 -> AnalyzeScreen(vm)
                    1 -> ObjectsScreen(vm)
                    2 -> VowelScreen(vm)
                    3 -> ManipulationScreen(vm)
                    4 -> ExperimentScreen(vm)
                    5 -> ScriptScreen(vm)
                }
            }
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }
}

/** Port of AboutView (a sheet on iOS) as a Material3 AlertDialog. */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Spraak") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "An unofficial Android port of Praat. NOT produced or endorsed by the original authors.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Based on Praat — doing phonetics by computer", fontWeight = FontWeight.Bold)
                Text(
                    "© 1992–2026 Paul Boersma & David Weenink, University of Amsterdam, " +
                        "and contributors. https://praat.org",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("License", fontWeight = FontWeight.Bold)
                Text(
                    "Free software under the GNU General Public License, version 3 or later. " +
                        "Distributed WITHOUT ANY WARRANTY. See the bundled gpl-3.0.txt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Corresponding source", fontWeight = FontWeight.Bold)
                Text(
                    "You have the right to the complete corresponding source under GPL §6: " +
                        "this repository at this build's commit, including the android/ folder. " +
                        "See ios/LICENSING.md.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // [Android port] the iOS Apple-App-Store sentence dropped; still a
                // source-distribution/sideload build.
                Text(
                    "This build is intended for source distribution and sideloading.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}
