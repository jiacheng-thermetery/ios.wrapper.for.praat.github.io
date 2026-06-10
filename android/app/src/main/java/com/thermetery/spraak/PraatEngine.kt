/* PraatEngine.kt — Kotlin face of the embedded Praat engine (libpraat.so).
 * Part of the Spraak derivative. GPL-3.0-or-later.
 *
 * [Android port] 1:1 with ios/app/PraatBridge.h (see there for semantics) plus
 * drawSelectedRecord/drawError from android/engine/PraatBridgeAndroid.h.
 * All methods that touch the engine must be called from a single thread (the
 * engine's object table is global and unsynchronised) — PraatViewModel funnels
 * every call through one dispatcher.
 */
package com.thermetery.spraak

object PraatEngine {
    init {
        System.loadLibrary("praat")
    }

    /* lifecycle / scripting console */
    external fun init()
    external fun runScript(script: String): String

    /* Objects window: "id|className|name|selected" per object */
    external fun objectCount(): Int
    external fun objectInfo(index1based: Int): String

    /* current Sound for the Analyze tab */
    external fun setSound(samples: FloatArray, sampleRate: Double): Int
    external fun soundDuration(): Double
    external fun soundSampleRate(): Double
    external fun addSoundObject(samples: FloatArray, sampleRate: Double, name: String): Int
    external fun selectedSoundPCM(out: FloatArray, outRate: DoubleArray): Int

    /* waveform min/max pairs over [t0,t1] */
    external fun waveform(t0: Double, t1: Double, n: Int, outMin: FloatArray, outMax: FloatArray): Int

    /* spectrogram dB matrix (row-major, iy*nx+ix, iy=0 lowest frequency);
     * outDims = [nx, ny]; outMeta = [tmin, tmax, fmax, dbMin, dbMax] */
    external fun spectrogram(
        t0: Double, t1: Double, maxFreq: Double, windowLength: Double,
        dynamicRange: Double, outDims: IntArray, outMeta: DoubleArray,
    ): FloatArray?

    /* analysis parameters and curves; kind: 0 pitch, 1 intensity, 2..6 formant 1..5 */
    external fun setPitchRange(floor: Double, ceiling: Double)
    external fun setFormantParams(maxFreq: Double, numFormants: Int, windowLength: Double)
    external fun curve(kind: Int, tmin: Double, tmax: Double, n: Int): FloatArray?
    external fun curveRange(kind: Int, outMinMax: DoubleArray)
    external fun valueAt(kind: Int, t: Double): Double

    /* spectral slice at time t; outMeta = [fmax, dbMin, dbMax] */
    external fun spectrumSlice(t: Double, windowDur: Double, outMeta: DoubleArray): FloatArray?

    /* Manipulation (PSOLA) */
    external fun manipulationStart(times: DoubleArray, values: DoubleArray): Int
    external fun manipulationResynth(
        times: DoubleArray, values: DoubleArray, out: FloatArray, outRate: DoubleArray,
    ): Int

    /* ExperimentMFC */
    external fun mfcCreateDemo(): Int
    external fun mfcUseSelected(): Int
    external fun mfcNumberOfTrials(): Int
    external fun mfcText(which: Int): String
    external fun mfcResponseCount(): Int
    external fun mfcResponseInfo(i1based: Int): String
    external fun mfcStimulusForTrial(trial1based: Int): Int
    external fun mfcStimulusText(trial1based: Int): String
    external fun mfcStimulusSound(trial1based: Int, out: FloatArray, outRate: DoubleArray): Int
    external fun mfcRecordResponse(trial1based: Int, iresp: Int, goodness: Double, reactionTime: Double)
    external fun mfcResultsCSV(): String

    /* Objects-window Draw: Praat Graphics opcode recording, replayed by
     * PraatPicture.kt; null on failure (drawError() then explains). */
    external fun drawSelectedRecord(widthInches: Double, heightInches: Double): DoubleArray?
    external fun drawError(): String
}
