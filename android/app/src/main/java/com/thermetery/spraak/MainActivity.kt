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
        // [Android port] Praat's UNIX paths (preferences, PID file, ~) need a writable
        // HOME; iOS got this from the OS. Must run before the view model inits the engine.
        PraatEngine.setEnv("HOME", filesDir.absolutePath)
        PraatEngine.setEnv("TMPDIR", cacheDir.absolutePath)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { SpraakApp(viewModel) }
    }
}
