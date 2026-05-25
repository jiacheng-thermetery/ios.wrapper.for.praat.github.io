// PraatModel.swift — Swift wrapper over the Praat analysis bridge.
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI
import CoreGraphics

struct AnalysisCurve {
    var values: [Float] = []          // sampled over [0, duration]; NaN where undefined
    var lo: Double = 0
    var hi: Double = 1
}

@MainActor
final class PraatModel: ObservableObject {
    @Published var hasSound = false
    @Published var duration: Double = 0
    @Published var sampleRate: Double = 0

    @Published var spectrogram: CGImage?
    @Published var fmax: Double = 5000
    @Published var dbMin: Double = 0
    @Published var dbMax: Double = 0

    @Published var waveMin: [Float] = []
    @Published var waveMax: [Float] = []

    @Published var pitch = AnalysisCurve(lo: 75, hi: 600)
    @Published var intensity = AnalysisCurve(lo: 50, hi: 100)
    @Published var formants: [AnalysisCurve] = []   // F1..F4

    // analysis settings (Praat-like)
    var maxFreqSetting: Double = 5000
    var windowLength: Double = 0.005     // broadband
    let curveSamples = 500
    let waveSamples = 1000

    func setSamples(_ samples: [Float], rate: Double) {
        guard !samples.isEmpty else { return }
        let ok = samples.withUnsafeBufferPointer {
            praatios_setSound($0.baseAddress, Int32(samples.count), rate)
        }
        guard ok != 0 else { return }
        duration = praatios_soundDuration()
        sampleRate = praatios_soundSampleRate()
        hasSound = true
        recompute()
    }

    func recompute() {
        guard hasSound else { return }

        var wmin = [Float](repeating: 0, count: waveSamples)
        var wmax = [Float](repeating: 0, count: waveSamples)
        wmin.withUnsafeMutableBufferPointer { lo in
            wmax.withUnsafeMutableBufferPointer { hi in
                _ = praatios_waveform(Int32(waveSamples), lo.baseAddress, hi.baseAddress)
            }
        }
        waveMin = wmin; waveMax = wmax

        var nx: Int32 = 0, ny: Int32 = 0
        var t0 = 0.0, t1 = 0.0, fm = 0.0, dmin = 0.0, dmax = 0.0
        if let ptr = praatios_spectrogram(maxFreqSetting, windowLength,
                &nx, &ny, &t0, &t1, &fm, &dmin, &dmax), nx > 0, ny > 0 {
            fmax = fm; dbMin = dmin; dbMax = dmax
            spectrogram = Self.makeGrayImage(ptr, Int(nx), Int(ny), dmin, dmax)
        }

        pitch = curve(0)
        intensity = curve(1)
        formants = (2...5).map { curve(Int32($0)) }
    }

    private func curve(_ kind: Int32) -> AnalysisCurve {
        var lo = 0.0, hi = 1.0
        praatios_curveRange(kind, &lo, &hi)
        var out = [Float](repeating: .nan, count: curveSamples)
        out.withUnsafeMutableBufferPointer {
            _ = praatios_curve(kind, 0.0, max(duration, 1e-6), Int32(curveSamples), $0.baseAddress)
        }
        return AnalysisCurve(values: out, lo: lo, hi: hi)
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
