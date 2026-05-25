// AudioEngine.swift — microphone recording + playback via AVAudioEngine.
// Part of the Spraak derivative. GPL-3.0-or-later.
import AVFoundation

@MainActor
final class AudioEngine: ObservableObject {
    @Published var isRecording = false
    @Published var permissionDenied = false

    private let engine = AVAudioEngine()
    private var captured: [Float] = []
    private(set) var sampleRate: Double = 44100

    /// Request mic permission, then start recording. `onStop` receives (samples, rate).
    func toggleRecording(onStop: @escaping ([Float], Double) -> Void) {
        if isRecording { stop(onStop: onStop); return }
        requestPermission { granted in
            Task { @MainActor in
                if granted { self.start() } else { self.permissionDenied = true }
            }
        }
    }

    private func requestPermission(_ done: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission { done($0) }
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission { done($0) }
        }
    }

    private func start() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        sampleRate = format.sampleRate
        captured.removeAll(keepingCapacity: true)

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buf, _ in
            guard let self, let ch = buf.floatChannelData else { return }
            let n = Int(buf.frameLength)
            let slice = UnsafeBufferPointer(start: ch[0], count: n)
            Task { @MainActor in self.captured.append(contentsOf: slice) }
        }
        do { try engine.start(); isRecording = true }
        catch { isRecording = false }
    }

    private func stop(onStop: @escaping ([Float], Double) -> Void) {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        onStop(captured, sampleRate)
    }

    /// Play a mono Float buffer at the given sample rate.
    func play(_ samples: [Float], rate: Double) {
        guard !samples.isEmpty,
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate,
                                         channels: 1, interleaved: false) else { return }
        let player = AVAudioPlayerNode()
        let playEngine = AVAudioEngine()
        playEngine.attach(player)
        playEngine.connect(player, to: playEngine.mainMixerNode, format: format)
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        buf.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { memcpy(buf.floatChannelData![0], $0.baseAddress, samples.count * MemoryLayout<Float>.size) }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        do {
            try playEngine.start()
            player.scheduleBuffer(buf, completionHandler: nil)
            player.play()
            // keep the engine alive for the duration
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(samples.count) / rate + 0.3) {
                playEngine.stop()
            }
        } catch { }
    }
}

/// A synthesized "vowel-like" sound (sum of damped formant resonances modulated at f0)
/// so the spectrogram/analyses can be demonstrated without microphone input.
enum DemoSound {
    static func vowel(durations: Double = 1.2, rate: Double = 16000) -> ([Float], Double) {
        let n = Int(durations * rate)
        var s = [Float](repeating: 0, count: n)
        // a slow pitch glide 120→160 Hz; formants sweeping /a/→/i/-ish
        let f1s = (700.0, 300.0), f2s = (1200.0, 2300.0), f3 = 2900.0
        for i in 0..<n {
            let t = Double(i) / rate
            let frac = t / durations
            let f0 = 120.0 + 40.0 * frac
            let f1 = f1s.0 + (f1s.1 - f1s.0) * frac
            let f2 = f2s.0 + (f2s.1 - f2s.0) * frac
            // glottal-ish source: sum of harmonics with 1/h rolloff, shaped by formant gains
            var v = 0.0
            var h = 1
            while Double(h) * f0 < rate / 2 {
                let fh = Double(h) * f0
                let g = formantGain(fh, f1, 80) + 0.7 * formantGain(fh, f2, 100) + 0.4 * formantGain(fh, f3, 150)
                v += (g / Double(h)) * sin(2 * .pi * fh * t)
                h += 1
            }
            s[i] = Float(0.25 * v)
        }
        return (s, rate)
    }
    private static func formantGain(_ f: Double, _ center: Double, _ bw: Double) -> Double {
        let x = (f - center) / (bw / 2)
        return 1.0 / (1.0 + x * x)   // Lorentzian resonance
    }
}
