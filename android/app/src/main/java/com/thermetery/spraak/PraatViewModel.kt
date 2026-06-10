// PraatViewModel.kt — Kotlin port of PraatModel.swift (plus AppStore from ContentView.swift)
// for the Spraak derivative. GPL-3.0-or-later.
//
// THE shared view model: every screen receives this one instance. It is also the only legal
// route to the engine — PraatEngine's object table is global and unsynchronised, so EVERY
// engine call goes through `engine { ... }`, which hops to one dedicated single-threaded
// dispatcher. Never call PraatEngine.* directly from a screen; use the wrappers below or
// `vm.engine { PraatEngine.whatever(...) }` for anything not wrapped yet.
package com.thermetery.spraak

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** One analysis curve sampled over the visible window; NaN where undefined. */
data class AnalysisCurve(
    val values: FloatArray = FloatArray(0),
    val lo: Double = 0.0,
    val hi: Double = 1.0,
)

/** Port of AnalysisSettings (immutable; replace the whole value via applySettings). */
data class AnalysisSettings(
    val spectrogramMaxFreq: Double = 5000.0,    // view range (Hz)
    val spectrogramWindow: Double = 0.005,      // window length (s) — broadband
    val spectrogramDynamicRange: Double = 70.0, // dB
    val pitchFloor: Double = 75.0,
    val pitchCeiling: Double = 600.0,
    val formantMaxFreq: Double = 5500.0,
    val formantCount: Int = 5,
    val formantWindow: Double = 0.025,
    val intensityMin: Double = 50.0,            // view range (dB)
    val intensityMax: Double = 100.0,
)

/** One row of the Objects window, parsed from PraatEngine.objectInfo "id|className|name|selected". */
data class PraatObject(val id: Int, val className: String, val name: String, val selected: Boolean)

/** Analysis values at the cursor (null where undefined/unvoiced). Port of PraatModel.CursorValues. */
data class CursorValues(
    val f0: Double?,               // pitch, Hz
    val formants: List<Double?>,   // F1..F4, Hz
    val intensity: Double?,        // dB
)

/** Spectral slice at a time point. Port of PraatModel.Slice. */
data class SpectrumSlice(val db: FloatArray, val fmax: Double, val dbMin: Double, val dbMax: Double)

class PraatViewModel : ViewModel() {

    // ---- the engine funnel -------------------------------------------------------------

    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "praat-engine") }
    private val engineDispatcher = engineExecutor.asCoroutineDispatcher()

    /** Run a block of PraatEngine calls on the single engine thread. The ONLY way in. */
    suspend fun <T> engine(block: () -> T): T = withContext(engineDispatcher) { block() }

    /** True once PraatEngine.init() has completed; screens may gate on it (usually instant). */
    var engineReady by mutableStateOf(false); private set

    /** Shared audio I/O (record + play). Screens may use it directly: vm.audio.isRecording etc. */
    val audio = AudioEngine()

    /** [Android port] Non-null if engine init threw; AboutDialog/screens may show it.
     * Without this, a throw in the launch{} would kill the process before any UI. */
    var engineInitError by mutableStateOf<String?>(null)
        private set

    // NOTE: the engine-init block lives at the BOTTOM of this class, after every
    // property declaration. viewModelScope launches on Dispatchers.Main.immediate,
    // so the coroutine body runs synchronously inside the constructor up to its
    // first suspension — an init {} placed here would read properties (settings,
    // …) whose mutableStateOf delegates are not constructed yet. That NPE was the
    // OnePlus launch crash (PraatViewModel.kt:81 in the 0.1.x builds).

    override fun onCleared() {
        audio.release()
        engineDispatcher.close()
    }

    // ---- app-wide UI state (port of AppStore + the AnalyzeView state other tabs need) ----

    /** Selected bottom tab: 0 Analyze, 1 Objects, 2 Vowel, 3 Manip., 4 Experiment, 5 Script. */
    var selectedTab by mutableIntStateOf(0)

    // ---- current sound + analyses (port of PraatModel published state) -------------------

    var hasSound by mutableStateOf(false); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var sampleRate by mutableDoubleStateOf(0.0); private set
    var soundName by mutableStateOf("sound"); private set

    /** Raw mono PCM of the Analyze sound, for playback/export (iOS kept this in AnalyzeView). */
    var soundSamples: FloatArray = FloatArray(0); private set

    // visible time window (zoom/scroll state); everything renders against this
    var viewStart by mutableDoubleStateOf(0.0); private set
    var viewEnd by mutableDoubleStateOf(0.0); private set
    val viewSpan: Double get() = max(viewEnd - viewStart, 1e-6)

    // [Android port] CGImage -> ImageBitmap (grayscale spectrogram, dark = high power)
    var spectrogram by mutableStateOf<ImageBitmap?>(null); private set
    var fmax by mutableDoubleStateOf(5000.0); private set
    var dbMin by mutableDoubleStateOf(0.0); private set
    var dbMax by mutableDoubleStateOf(0.0); private set

    var waveMin by mutableStateOf(FloatArray(0)); private set
    var waveMax by mutableStateOf(FloatArray(0)); private set

    var pitch by mutableStateOf(AnalysisCurve(lo = 75.0, hi = 600.0)); private set
    var intensity by mutableStateOf(AnalysisCurve(lo = 50.0, hi = 100.0)); private set
    var formants by mutableStateOf<List<AnalysisCurve>>(emptyList()); private set   // F1..F4

    var settings by mutableStateOf(AnalysisSettings()); private set

    // analysis overlay toggles (iOS had these as AnalyzeView @State; app-wide here)
    var showPitch by mutableStateOf(true)
    var showFormants by mutableStateOf(true)
    var showIntensity by mutableStateOf(true)

    // cursor + selection inside the Analyze view (seconds); shared so Play/zoom helpers see them
    var cursorTime by mutableStateOf<Double?>(null)
    var selection by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)

    /** Objects window rows; call refreshObjects() after anything that mutates the object list. */
    var objects by mutableStateOf<List<PraatObject>>(emptyList()); private set

    // ---- loading sounds ------------------------------------------------------------------

    /** Fire-and-forget variant of [setSoundFromPCMAwait] for plain UI callbacks. */
    fun setSoundFromPCM(samples: FloatArray, rate: Double, name: String = "sound",
                        addToObjects: Boolean = false) {
        viewModelScope.launch { setSoundFromPCMAwait(samples, rate, name, addToObjects) }
    }

    /**
     * Load PCM as the Analyze sound (port of PraatModel.setSamples + AnalyzeView.setAnalysisSound).
     * If `addToObjects`, also add it to the engine object list so it appears in the Objects window.
     */
    suspend fun setSoundFromPCMAwait(samples: FloatArray, rate: Double, name: String = "sound",
                                     addToObjects: Boolean = false) {
        if (samples.isEmpty()) return
        val info: Pair<Double, Double>? = engine {
            if (PraatEngine.setSound(samples, rate) == 0) null
            else {
                if (addToObjects) PraatEngine.addSoundObject(samples, rate, name)
                Pair(PraatEngine.soundDuration(), PraatEngine.soundSampleRate())
            }
        }
        if (info == null) return
        soundSamples = samples
        soundName = name
        duration = info.first
        sampleRate = info.second
        hasSound = true
        viewStart = 0.0
        viewEnd = duration
        cursorTime = null
        selection = null
        zoomHistory.clear()
        recomputeAwait()
        if (addToObjects) refreshObjects()
    }

    /** Load the synthesized demo vowel (port of AnalyzeView.loadDemo). */
    fun loadDemo() {
        val (s, r) = DemoSound.vowel()
        setSoundFromPCM(s, r, "vowel", addToObjects = false)
    }

    /** Toggle mic recording; on stop the take becomes the Analyze sound and an engine object. */
    fun toggleRecording() {
        audio.toggleRecording { samples, rate ->
            if (samples.isNotEmpty()) setSoundFromPCM(samples, rate, "recording", addToObjects = true)
        }
    }

    /**
     * Open an audio file through the engine (`Read from file:` — WAV/FLAC/MP3/…), add it to the
     * object list, and optionally hand it to the Analyze tab. Returns the script output/error text.
     * [Android port] replaces AVAudioFile decode + fileImporter; copy the content URI to a real
     * file (e.g. cacheDir) first, Praat needs a filesystem path.
     */
    suspend fun openWavViaScript(path: String, name: String? = null,
                                 sendToAnalyze: Boolean = true): String {
        val safePath = path.replace("\"", "\"\"")
        var script = "Read from file: \"$safePath\""
        if (name != null) script += "\nRename: \"${name.replace("\"", "\"\"")}\""
        val out = runScript(script)
        refreshObjects()
        if (sendToAnalyze) selectedSoundToAnalyze(name ?: "sound")
        return out
    }

    /** Port of AppStore.sendToAnalyze: load PCM into Analyze and switch to that tab. */
    fun sendToAnalyze(samples: FloatArray, rate: Double, name: String) {
        viewModelScope.launch {
            setSoundFromPCMAwait(samples, rate, name, addToObjects = false)
            selectedTab = 0
        }
    }

    /** Copy the first selected Sound object into the Analyze tab ("Analyze" in the Objects window). */
    suspend fun selectedSoundToAnalyze(name: String = "selected"): Boolean {
        val (s, r) = selectedSoundPCM() ?: return false
        setSoundFromPCMAwait(s, r, name, addToObjects = false)
        selectedTab = 0
        return true
    }

    /** PCM (mixed to mono) + rate of the first selected Sound object, or null. */
    suspend fun selectedSoundPCM(): Pair<FloatArray, Double>? = engine {
        // size the buffer by asking the engine first (errors → non-numeric info text → null)
        val info = PraatEngine.runScript("n = Get number of samples\nwriteInfoLine: n")
        val n = info.trim().substringBefore("\n").trim().toDoubleOrNull()?.toInt()
        if (n == null || n <= 0) return@engine null
        val out = FloatArray(n)
        val rate = DoubleArray(1)
        val got = PraatEngine.selectedSoundPCM(out, rate)
        if (got <= 0) null else Pair(if (got == n) out else out.copyOf(got), rate[0])
    }

    // ---- view window / zoom (port of PraatModel.setView + AnalyzeView zoom helpers) -------

    private val zoomHistory = ArrayDeque<Pair<Double, Double>>()

    /** Set the visible window (clamped) and re-analyse it. */
    fun setView(a: Double, b: Double) { viewModelScope.launch { setViewAwait(a, b) } }

    suspend fun setViewAwait(a: Double, b: Double) {
        if (!hasSound || duration <= 0) return
        val minSpan = 3.0 * settings.spectrogramWindow
        var lo = max(0.0, min(a, duration))
        val hi = min(duration, max(b, lo + minSpan))
        if (hi - lo < minSpan) lo = max(0.0, hi - minSpan)
        if (lo == viewStart && hi == viewEnd) return
        viewStart = lo
        viewEnd = hi
        recomputeAwait()
    }

    private fun zoomCenter(): Double {
        val t = cursorTime
        if (t != null && t >= viewStart && t <= viewEnd) return t
        return (viewStart + viewEnd) / 2
    }

    private fun pushZoom() = zoomHistory.addLast(Pair(viewStart, viewEnd))

    fun showAll() { pushZoom(); setView(0.0, duration) }
    fun zoomIn() { pushZoom(); val c = zoomCenter(); val s = viewSpan / 2; setView(c - s / 2, c + s / 2) }
    fun zoomOut() { pushZoom(); val c = zoomCenter(); val s = viewSpan * 2; setView(c - s / 2, c + s / 2) }
    fun zoomTo(a: Double, b: Double) { pushZoom(); setView(a, b) }
    fun zoomToSelection() { selection?.let { pushZoom(); setView(it.start, it.endInclusive) } }
    fun zoomBack() { zoomHistory.removeLastOrNull()?.let { setView(it.first, it.second) } }
    val canZoomBack: Boolean get() = zoomHistory.isNotEmpty()

    /** Scroll by 80 % of a page; dir = -1 back, +1 forward. */
    fun scrollPage(dir: Double) {
        val span = viewSpan
        var s = viewStart + span * 0.8 * dir
        s = max(0.0, min(s, duration - span))
        setView(s, s + span)
    }

    // ---- analyses --------------------------------------------------------------------------

    /** Apply new analysis settings: push the computational ones to the engine and redraw. */
    fun applySettings(s: AnalysisSettings) {
        settings = s
        viewModelScope.launch {
            engine {
                PraatEngine.setPitchRange(s.pitchFloor, s.pitchCeiling)
                PraatEngine.setFormantParams(s.formantMaxFreq, s.formantCount, s.formantWindow)
            }
            if (hasSound) recomputeAwait()
        }
    }

    fun recompute() { viewModelScope.launch { recomputeAwait() } }

    private class Analyses(
        val waveMin: FloatArray, val waveMax: FloatArray,
        val spec: FloatArray?, val nx: Int, val ny: Int,
        val fmax: Double, val dbMin: Double, val dbMax: Double,
        val pitch: FloatArray?, val intensity: FloatArray?, val formants: List<FloatArray?>,
    )

    /** Re-run all visible analyses over [viewStart, viewEnd] (port of PraatModel.recompute). */
    suspend fun recomputeAwait() {
        if (!hasSound || viewEnd <= viewStart) return
        val t0 = viewStart
        val t1 = viewEnd
        val s = settings
        val r = engine {
            val wmin = FloatArray(WAVE_SAMPLES)
            val wmax = FloatArray(WAVE_SAMPLES)
            PraatEngine.waveform(t0, t1, WAVE_SAMPLES, wmin, wmax)
            val dims = IntArray(2)
            val meta = DoubleArray(5)   // [tmin, tmax, fmax, dbMin, dbMax]
            val spec = PraatEngine.spectrogram(t0, t1, s.spectrogramMaxFreq,
                s.spectrogramWindow, s.spectrogramDynamicRange, dims, meta)
            Analyses(
                wmin, wmax, spec, dims[0], dims[1], meta[2], meta[3], meta[4],
                PraatEngine.curve(KIND_PITCH, t0, t1, CURVE_SAMPLES),
                PraatEngine.curve(KIND_INTENSITY, t0, t1, CURVE_SAMPLES),
                (KIND_FORMANT1..KIND_FORMANT1 + 3).map { PraatEngine.curve(it, t0, t1, CURVE_SAMPLES) },
            )
        }
        waveMin = r.waveMin
        waveMax = r.waveMax
        if (r.spec != null && r.nx > 0 && r.ny > 0) {
            fmax = r.fmax
            dbMin = r.dbMin
            dbMax = r.dbMax
            spectrogram = withContext(Dispatchers.Default) {
                makeGrayImage(r.spec, r.nx, r.ny, r.dbMin, r.dbMax)
            }
        }
        pitch = AnalysisCurve(r.pitch ?: nanCurve(), s.pitchFloor, s.pitchCeiling)
        intensity = AnalysisCurve(r.intensity ?: nanCurve(), s.intensityMin, s.intensityMax)
        formants = r.formants.map { AnalysisCurve(it ?: nanCurve(), 0.0, s.formantMaxFreq) }
    }

    /** Analysis values at time t (port of PraatModel.valuesAt). */
    suspend fun valuesAt(t: Double): CursorValues = engine {
        fun v(kind: Int): Double? {
            val x = PraatEngine.valueAt(kind, t)
            return if (x.isFinite()) x else null
        }
        CursorValues(v(KIND_PITCH), (KIND_FORMANT1..KIND_FORMANT1 + 3).map { v(it) }, v(KIND_INTENSITY))
    }

    /** Spectral slice at time t (port of PraatModel.spectrumSlice). */
    suspend fun spectrumSlice(t: Double, windowDur: Double = 0.005): SpectrumSlice? = engine {
        val meta = DoubleArray(3)   // [fmax, dbMin, dbMax]
        val db = PraatEngine.spectrumSlice(t, windowDur, meta)
        if (db == null || db.isEmpty()) null else SpectrumSlice(db, meta[0], meta[1], meta[2])
    }

    // ---- scripting + Objects window ----------------------------------------------------------

    /** Run a Praat script on the engine thread; returns the Info window text (or the error). */
    suspend fun runScript(script: String): String = engine { PraatEngine.runScript(script) }

    /** Non-suspend convenience for screens without a handy coroutine scope. */
    fun runScriptAsync(script: String, onResult: (String) -> Unit = {}) {
        viewModelScope.launch { onResult(runScript(script)) }
    }

    /** Re-read the engine object list into [objects] (and return it). */
    suspend fun refreshObjects(): List<PraatObject> {
        val rows = engine { (1..PraatEngine.objectCount()).map { PraatEngine.objectInfo(it) } }
        val parsed = rows.mapNotNull { row ->
            val p = row.split("|")
            if (p.size < 4) return@mapNotNull null
            val id = p[0].trim().toIntOrNull() ?: return@mapNotNull null
            val sel = p[3].trim().let { it == "1" || it.equals("yes", true) || it.equals("true", true) }
            PraatObject(id, p[1], p[2], sel)
        }
        objects = parsed
        return parsed
    }

    /** Select exactly these object ids (empty list deselects all), then refresh [objects]. */
    suspend fun selectObjects(ids: List<Int>): String {
        val out = runScript(if (ids.isEmpty()) "selectObject()"
                            else "selectObject: " + ids.joinToString(", "))
        refreshObjects()
        return out
    }

    /** Remove these objects from the engine, then refresh [objects]. */
    suspend fun removeObjects(ids: List<Int>): String {
        if (ids.isEmpty()) return ""
        val out = runScript("removeObject: " + ids.joinToString(", "))
        refreshObjects()
        return out
    }

    /**
     * [Android port] Mirror the Analyze tab's TextGrid annotation into the engine object
     * list (one shared engine, like addSoundObject does for sounds): write the grid text
     * to a temp file, Read it, Rename it, replacing any previous synced grid of the same
     * name. Selection is preserved so a sync never disturbs what the user selected.
     */
    suspend fun syncTextGridToObjects(gridText: String, objName: String, cacheDir: java.io.File) {
        runCatching {
            val file = java.io.File(cacheDir, "$objName.TextGrid")
            withContext(Dispatchers.IO) { file.writeText(gridText) }
            val before = refreshObjects()
            val selectedIds = before.filter { it.selected }.map { it.id }
            val stale = before.filter {
                it.className == "TextGrid" && it.name.substringAfter(' ') == objName
            }.map { it.id }
            if (stale.isNotEmpty()) runScript("removeObject: " + stale.joinToString(", "))
            runScript(
                "Read from file: \"${file.absolutePath.replace("\\", "/")}\"\n" +
                "Rename: \"$objName\""
            )
            selectObjects(selectedIds.filterNot { it in stale })
        }
    }

    /**
     * Synthesize speech with eSpeak and load it into the Analyze tab (port of AnalyzeView.speak).
     * [Android port] PCM is pulled straight out of the engine object instead of a temp WAV.
     */
    suspend fun speak(text: String, language: String = "English (Great Britain)",
                      voice: String = "Female1"): Boolean {
        val safe = text.replace("\"", "\"\"").replace("\n", " ")
        runScript(
            """
            synth = Create SpeechSynthesizer: "$language", "$voice"
            selectObject: synth
            sound = To Sound: "$safe", "no"
            removeObject: synth
            selectObject: sound
            Rename: "speech"
            """.trimIndent()
        )
        refreshObjects()
        return selectedSoundToAnalyze("speech")
    }

    // ---- playback convenience ------------------------------------------------------------

    /** Play the Analyze sound over [from, to] seconds (defaults: everything). */
    fun play(from: Double = 0.0, to: Double = Double.POSITIVE_INFINITY) {
        if (hasSound) audio.play(soundSamples, sampleRate, from, to)
    }

    fun playWindow() = play(viewStart, viewEnd)
    fun playSelection() = selection?.let { play(it.start, it.endInclusive) }
    fun playSamples(samples: FloatArray, rate: Double) = audio.play(samples, rate)
    fun stopPlayback() = audio.stopPlayback()

    // ---- Manipulation (PSOLA) ---------------------------------------------------------------

    /** Build a Manipulation from the current sound; returns its pitch points (times, values) or null. */
    suspend fun manipulationStart(maxPoints: Int = 4000): Pair<DoubleArray, DoubleArray>? = engine {
        val times = DoubleArray(maxPoints)
        val values = DoubleArray(maxPoints)
        val n = PraatEngine.manipulationStart(times, values)
        if (n <= 0) null else Pair(times.copyOf(n), values.copyOf(n))
    }

    /** Resynthesize with an edited pitch tier; returns (samples, rate) or null. */
    suspend fun manipulationResynth(times: DoubleArray, values: DoubleArray): Pair<FloatArray, Double>? {
        val cap = max(soundSamples.size * 2, 1 shl 16)   // PSOLA keeps duration; 2x is generous
        return engine {
            val out = FloatArray(cap)
            val rate = DoubleArray(1)
            val n = PraatEngine.manipulationResynth(times, values, out, rate)
            if (n <= 0) null else Pair(out.copyOf(n), rate[0])
        }
    }

    // ---- ExperimentMFC ------------------------------------------------------------------------

    /** Rendered PCM of an MFC trial's stimulus, or null (other mfc* calls are cheap: use engine {}). */
    suspend fun mfcStimulusSound(trial1based: Int, maxSeconds: Double = 30.0): Pair<FloatArray, Double>? =
        engine {
            val out = FloatArray((48000 * maxSeconds).toInt())
            val rate = DoubleArray(1)
            val n = PraatEngine.mfcStimulusSound(trial1based, out, rate)
            if (n <= 0) null else Pair(out.copyOf(n), rate[0])
        }

    // ---- Objects-window Draw -------------------------------------------------------------------

    /** Record Praat Graphics opcodes for the first selected object (replayed by PraatPicture.kt). */
    suspend fun drawSelectedRecord(widthInches: Double = 6.0, heightInches: Double = 4.5): DoubleArray? =
        engine { PraatEngine.drawSelectedRecord(widthInches, heightInches) }

    /** Why the last drawSelectedRecord returned null. */
    suspend fun drawError(): String = engine { PraatEngine.drawError() }

    // ---- internals -------------------------------------------------------------------------------

    private fun nanCurve() = FloatArray(CURVE_SAMPLES) { Float.NaN }

    companion object {
        const val CURVE_SAMPLES = 500
        const val WAVE_SAMPLES = 1000

        // PraatEngine.curve/valueAt kinds
        const val KIND_PITCH = 0
        const val KIND_INTENSITY = 1
        const val KIND_FORMANT1 = 2   // formant N is KIND_FORMANT1 + (N - 1), N = 1..5

        /**
         * Grayscale spectrogram image from the dB matrix (row-major, iy=0 = lowest frequency).
         * High power → dark, like Praat. Output row 0 = top = highest frequency.
         */
        fun makeGrayImage(db: FloatArray, nx: Int, ny: Int, dbMin: Double, dbMax: Double): ImageBitmap {
            val pixels = IntArray(nx * ny)
            val range = max(dbMax - dbMin, 1e-6)
            for (iy in 0 until ny) {
                val srcRow = (ny - 1 - iy) * nx   // flip: image top = high freq
                val dstRow = iy * nx
                for (ix in 0 until nx) {
                    val t = ((db[srcRow + ix] - dbMin) / range).coerceIn(0.0, 1.0)
                    val g = (255.0 - t * 255.0).toInt()
                    pixels[dstRow + ix] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            return Bitmap.createBitmap(pixels, nx, ny, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }

    // Engine init. Keep this AFTER all property declarations — see the note near
    // engineInitError: Main.immediate runs this synchronously during construction.
    init {
        viewModelScope.launch {
            runCatching {
                val s = settings
                engine {
                    PraatEngine.init()
                    // push the defaults so engine and UI agree from the start
                    PraatEngine.setPitchRange(s.pitchFloor, s.pitchCeiling)
                    PraatEngine.setFormantParams(s.formantMaxFreq, s.formantCount, s.formantWindow)
                }
                engineReady = true
                refreshObjects()
            }.onFailure { t ->
                engineInitError = t.stackTraceToString()
            }
        }
    }
}
