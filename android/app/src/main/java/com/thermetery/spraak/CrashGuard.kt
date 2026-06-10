// CrashGuard.kt — startup diagnostics for the Spraak derivative. GPL-3.0-or-later.
// [Android port] Debug tooling: records any uncaught exception to filesDir/last-crash.txt
// and shows it (copyable) on the next launch, so a remote user can report crashes
// without adb/logcat. A native SIGSEGV before/inside dlopen bypasses this by nature —
// if the app dies instantly with no report on relaunch, the crash is native-side.
package com.thermetery.spraak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import java.io.File

object CrashGuard {
    private const val FILE_NAME = "last-crash.txt"

    /** Install the recorder and return the previous launch's crash report, if any. */
    fun installAndReadPrevious(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        val previous = runCatching {
            if (file.exists()) file.readText().also { file.delete() } else null
        }.getOrNull()
        val older = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                file.writeText(
                    "Spraak crash on thread ${thread.name}\n" +
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                    "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n" +
                    throwable.stackTraceToString()
                )
            }
            older?.uncaughtException(thread, throwable)
        }
        return previous
    }
}

@Composable
fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Previous launch crashed") },
        text = {
            Text(
                report,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Spraak crash", report))
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
