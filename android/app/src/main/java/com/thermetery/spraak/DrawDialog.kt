// DrawDialog.kt — Objects-window "Draw" preview dialog for the Spraak derivative.
// GPL-3.0-or-later.
//
// [Android port] iOS renders the selected object to a PNG file via Praat's Quartz Graphics
// (praatios_drawSelectedToPNG) and previews it in a sheet. Android instead pulls the raw
// Graphics opcode recording (PraatEngine.drawSelectedRecord) and replays it locally with
// PraatPicture.render. "Save PNG" goes to MediaStore.Images under Pictures/Spraak — local
// only, no sharing intents.

package com.thermetery.spraak

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The C side records at these extents (must match PraatEngine.drawSelectedRecord below). */
private const val DRAW_WIDTH_INCHES = 6.0
private const val DRAW_HEIGHT_INCHES = 4.5

/** Render at 2x the recording's 100-dpi device extents so pinch-zoom stays crisp. */
private const val RENDER_WIDTH_PX = 1200
private const val RENDER_HEIGHT_PX = 900

@Composable
fun DrawDialog(vm: PraatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Title like the iOS sheet: "Draw: Sound vowel" (best effort from the cached object list).
    val selected = vm.objects.firstOrNull { it.selected }
    val title = if (selected != null) "Draw: ${selected.className} ${selected.name}" else "Draw"

    LaunchedEffect(Unit) {
        // every engine call goes through vm.engine (single-threaded, blocking engine)
        val record = vm.engine { PraatEngine.drawSelectedRecord(DRAW_WIDTH_INCHES, DRAW_HEIGHT_INCHES) }
        if (record == null) {
            val why = vm.engine { PraatEngine.drawError() }
            error = why.ifBlank { "Draw failed." }
        } else {
            // replaying the recording is pure Kotlin — keep it off the engine thread
            bitmap = withContext(Dispatchers.Default) {
                PraatPicture.render(record, RENDER_WIDTH_PX, RENDER_HEIGHT_PX,
                    DRAW_WIDTH_INCHES, DRAW_HEIGHT_INCHES)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),   // wide: the picture is landscape
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio((DRAW_WIDTH_INCHES / DRAW_HEIGHT_INCHES).toFloat())
                        .background(Color.White)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    val err = error
                    when {
                        err != null -> Text(
                            err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp),
                        )
                        bmp == null -> CircularProgressIndicator()
                        else -> ZoomableBitmap(bmp)
                    }
                }
                status?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = bitmap != null && !saving,
                        onClick = {
                            val bmp = bitmap ?: return@TextButton
                            saving = true
                            scope.launch {
                                status = withContext(Dispatchers.IO) { savePng(context, bmp) }
                                saving = false
                            }
                        },
                    ) { Text(if (saving) "Saving…" else "Save PNG") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** Pinch-to-zoom + pan, double-tap to reset (port of the iOS preview's zoomable image). */
@Composable
private fun ZoomableBitmap(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Praat drawing",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    // loose clamp: keep the content within reach at the current zoom
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = Offset(
                        (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
                        (offset.y + pan.y * scale).coerceIn(-maxY, maxY),
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** Write the bitmap as PNG into MediaStore.Images (Pictures/Spraak); returns a status line. */
private fun savePng(context: Context, bitmap: Bitmap): String {
    val name = "spraak-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            // [Android port] RELATIVE_PATH/IS_PENDING exist from API 29; on 26..28 the image
            // lands in the default Pictures bucket instead of Pictures/Spraak (and needs the
            // legacy WRITE_EXTERNAL_STORAGE grant) — we report whatever actually happened.
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Spraak")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    return try {
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "Could not create the image entry."
        val ok = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        if (!ok) {
            resolver.delete(uri, null, null)
            "Saving the PNG failed."
        } else if (Build.VERSION.SDK_INT >= 29) {
            "Saved to Pictures/Spraak/$name"
        } else {
            "Saved to Pictures/$name"
        }
    } catch (e: Exception) {
        "Save failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
