// PraatModel.swift — Swift wrapper over the Praat analysis bridge.
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI
import CoreGraphics

struct AnalysisCurve {
    var values: [Float] = []          // sampled over the view window; NaN where undefined
    var lo: Double = 0
    var hi: Double = 1
}

struct AnalysisSettings: Equatable {
    var spectrogramMaxFreq: Double = 5000     // view range (Hz)
    var spectrogramWindow: Double = 0.005     // window length (s) — broadband
    var spectrogramDynamicRange: Double = 70  // dB
    var pitchFloor: Double = 75
    var pitchCeiling: Double = 600
    var formantMaxFreq: Double = 5500
    var formantCount: Int = 5
    var formantWindow: Double = 0.025
    var intensityMin: Double = 50             // view range (dB)
    var intensityMax: Double = 100
}

@MainActor
final class PraatModel: ObservableObject {
    @Published var hasSound = false
    @Published var duration: Double = 0
    @Published var sampleRate: Double = 0

    // visible time window (zoom/scroll state); everything renders against this
    @Published var viewStart: Double = 0
    @Published var viewEnd: Double = 0
    var viewSpan: Double { max(viewEnd - viewStart, 1e-6) }

    @Published var spectrogram: CGImage?
    @Published var fmax: Double = 5000
    @Published var dbMin: Double = 0
    @Published var dbMax: Double = 0

    @Published var waveMin: [Float] = []
    @Published var waveMax: [Float] = []

    @Published var pitch = AnalysisCurve(lo: 75, hi: 600)
    @Published var intensity = AnalysisCurve(lo: 50, hi: 100)
    @Published var formants: [AnalysisCurve] = []   // F1..F4

    @Published var settings = AnalysisSettings()
    let curveSamples = 500
    let waveSamples = 1000

    /// Apply new analysis settings: push the computational ones to the engine
    /// (re-running cached analyses) and redraw.
    func applySettings(_ s: AnalysisSettings) {
        settings = s
        praatios_setPitchRange(s.pitchFloor, s.pitchCeiling)
        praatios_setFormantParams(s.formantMaxFreq, Int32(s.formantCount), s.formantWindow)
        if hasSound { recompute() }
    }

    func setSamples(_ samples: [Float], rate: Double) {
        guard !samples.isEmpty else { return }
        let ok = samples.withUnsafeBufferPointer {
            praatios_setSound($0.baseAddress, Int32(samples.count), rate)
        }
        guard ok != 0 else { return }
        duration = praatios_soundDuration()
        sampleRate = praatios_soundSampleRate()
        hasSound = true
        viewStart = 0; viewEnd = duration
        recompute()
    }

    /// Set the visible window (clamped) and re-analyse it.
    func setView(_ a: Double, _ b: Double) {
        guard hasSound, duration > 0 else { return }
        let minSpan = 3.0 * settings.spectrogramWindow
        var lo = max(0, min(a, duration))
        var hi = min(duration, max(b, lo + minSpan))
        if hi - lo < minSpan { lo = max(0, hi - minSpan) }
        if lo == viewStart && hi == viewEnd { return }
        viewStart = lo; viewEnd = hi
        recompute()
    }

    func recompute() {
        guard hasSound, viewEnd > viewStart else { return }

        var wmin = [Float](repeating: 0, count: waveSamples)
        var wmax = [Float](repeating: 0, count: waveSamples)
        wmin.withUnsafeMutableBufferPointer { lo in
            wmax.withUnsafeMutableBufferPointer { hi in
                _ = praatios_waveform(viewStart, viewEnd, Int32(waveSamples), lo.baseAddress, hi.baseAddress)
            }
        }
        waveMin = wmin; waveMax = wmax

        var nx: Int32 = 0, ny: Int32 = 0
        var t0 = 0.0, t1 = 0.0, fm = 0.0, dmin = 0.0, dmax = 0.0
        if let ptr = praatios_spectrogram(viewStart, viewEnd, settings.spectrogramMaxFreq,
                settings.spectrogramWindow, settings.spectrogramDynamicRange,
                &nx, &ny, &t0, &t1, &fm, &dmin, &dmax), nx > 0, ny > 0 {
            fmax = fm; dbMin = dmin; dbMax = dmax
            spectrogram = Self.makeGrayImage(ptr, Int(nx), Int(ny), dmin, dmax)
        }

        pitch = curve(0)
        intensity = curve(1)
        formants = (2...5).map { curve(Int32($0)) }
    }

    private func curve(_ kind: Int32) -> AnalysisCurve {
        let lo: Double, hi: Double
        switch kind {
        case 0:  lo = settings.pitchFloor;   hi = settings.pitchCeiling      // pitch (Hz)
        case 1:  lo = settings.intensityMin; hi = settings.intensityMax      // intensity (dB)
        default: lo = 0;                     hi = settings.formantMaxFreq    // formants (Hz)
        }
        var out = [Float](repeating: .nan, count: curveSamples)
        out.withUnsafeMutableBufferPointer {
            _ = praatios_curve(kind, viewStart, viewEnd, Int32(curveSamples), $0.baseAddress)
        }
        return AnalysisCurve(values: out, lo: lo, hi: hi)
    }

    struct CursorValues {
        var f0: Double?            // pitch, Hz
        var formants: [Double?]    // F1..F4, Hz
        var intensity: Double?     // dB
    }

    func valuesAt(_ t: Double) -> CursorValues {
        func v(_ k: Int32) -> Double? { let x = praatios_valueAt(k, t); return x.isFinite ? x : nil }
        return CursorValues(f0: v(0), formants: (2...5).map { v(Int32($0)) }, intensity: v(1))
    }

    struct Slice { var db: [Float]; var fmax: Double; var dbMin: Double; var dbMax: Double }

    func spectrumSlice(at t: Double, windowDur: Double = 0.005) -> Slice? {
        var n: Int32 = 0, fm = 0.0, dmin = 0.0, dmax = 0.0
        guard let ptr = praatios_spectrumSlice(t, windowDur, &n, &fm, &dmin, &dmax), n > 0 else { return nil }
        let arr = Array(UnsafeBufferPointer(start: ptr, count: Int(n)))
        return Slice(db: arr, fmax: fm, dbMin: dmin, dbMax: dmax)
    }

    /// Build a grayscale CGImage from the dB matrix (row-major, iy=0 = lowest freq).
    /// High power → dark, like Praat. Output image row 0 = top = highest frequency.
    static func makeGrayImage(_ db: UnsafePointer<Float>, _ nx: Int, _ ny: Int,
                              _ dbMin: Double, _ dbMax: Double) -> CGImage? {
        var pixels = [UInt8](repeating: 255, count: nx * ny)
        let range = max(dbMax - dbMin, 1e-6)
        for iy in 0..<ny {
            let srcY = ny - 1 - iy            // flip: image top = high freq
            let dstRow = iy * nx
            let srcRow = srcY * nx
            for ix in 0..<nx {
                let v = Double(db[srcRow + ix])
                let t = min(max((v - dbMin) / range, 0), 1)
                pixels[dstRow + ix] = UInt8(255.0 - t * 255.0)
            }
        }
        return pixels.withUnsafeMutableBytes { raw -> CGImage? in
            guard let ctx = CGContext(data: raw.baseAddress, width: nx, height: ny,
                    bitsPerComponent: 8, bytesPerRow: nx,
                    space: CGColorSpaceCreateDeviceGray(),
                    bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
            return ctx.makeImage()
        }
    }
}
