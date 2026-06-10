// MainActivity.kt — Compose entry point for the Spraak derivative. GPL-3.0-or-later.
// UNOFFICIAL modified version of Praat, not produced or endorsed by the original authors.
package com.thermetery.spraak

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    // [Android port] Activity-scoped so it is the same instance viewModel() resolves to inside
    // SpraakApp; engine init happens in the view model (PraatApp.swift did it in App.init()).
    private val viewModel: PraatViewModel by viewModels()

    // [Android port] iOS asks for mic permission lazily inside AVAudioEngine; on Android the
    // runtime permission dialog is an Activity concern, so we ask up front and just record the
    // outcome where the iOS code kept it (AudioEngine.permissionDenied).
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.audio.permissionDenied = !granted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [Android port] Diagnostics first: record uncaught exceptions to a file and
        // surface the previous launch's report (copyable) — see CrashGuard.kt.
        val previousCrash = CrashGuard.installAndReadPrevious(this)

        // A failed System.loadLibrary must not take the process down before any UI:
        // show the loader error full-screen instead of touching the engine/view model.
        val loadError = PraatEngine.loadError
        if (loadError != null) {
            setContent {
                Surface {
                    Text(
                        "Spraak could not load its native engine (libpraat.so):\n\n" +
                            loadError.stackTraceToString(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            return
        }

        // [Android port] Praat's UNIX paths (preferences, PID file, ~) need a writable
        // HOME; iOS got this from the OS. Must run before the view model inits the engine.
        // Guarded: a JNI mismatch here (UnsatisfiedLinkError — exactly what a stale
        // per-ABI libpraat.so produces) must land on screen, not kill onCreate.
        val envResult = runCatching {
            PraatEngine.setEnv("HOME", filesDir.absolutePath)
            PraatEngine.setEnv("TMPDIR", cacheDir.absolutePath)
        }

        // Construct the view model HERE, not lazily inside composition: if its
        // constructor throws, composition never starts and no crash UI could show
        // (that masked the 0.1.x init-order NPE). Failing here still records via
        // CrashGuard, and we can show the report plus the new error on screen.
        val vmResult = envResult.mapCatching { viewModel }
        if (vmResult.isFailure) {
            val detail = (previousCrash?.let { "$it\n\n" } ?: "") +
                "Startup failed:\n" + vmResult.exceptionOrNull()!!.stackTraceToString()
            setContent {
                Surface {
                    Text(
                        detail,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent {
            var crashReport by androidx.compose.runtime.remember { mutableStateOf(previousCrash) }
            var initErrorDismissed by androidx.compose.runtime.remember { mutableStateOf(false) }
            SpraakApp(viewModel)
            crashReport?.let { CrashReportDialog(it) { crashReport = null } }
            viewModel.engineInitError?.takeUnless { initErrorDismissed }?.let {
                CrashReportDialog("Engine init failed:\n\n$it") { initErrorDismissed = true }
            }
        }
    }
}
