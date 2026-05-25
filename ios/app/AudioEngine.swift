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

    @Published var isPlaying = false
    private var playEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?

    /// Play the mono Float buffer over the time range [from, to] (seconds). Replaces any
    /// current playback. `to == .infinity` plays to the end.
    func play(_ samples: [Float], rate: Double, from: Double = 0, to: Double = .infinity) {
        stopPlayback()
        let n = samples.count
        let i0 = max(0, Int(from * rate))
        let i1 = min(n, to.isFinite ? Int(to * rate) : n)
        guard i1 > i0,
              let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate,
                                         channels: 1, interleaved: false),
              let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(i1 - i0))
        else { return }
        buf.frameLength = AVAudioFrameCount(i1 - i0)
        samples.withUnsafeBufferPointer {
            memcpy(buf.floatChannelData![0], $0.baseAddress!.advanced(by: i0), (i1 - i0) * MemoryLayout<Float>.size)
        }
        let eng = AVAudioEngine(); let player = AVAudioPlayerNode()
        eng.attach(player); eng.connect(player, to: eng.mainMixerNode, format: format)
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        do { try eng.start() } catch { return }
        playEngine = eng; playerNode = player; isPlaying = true
        player.scheduleBuffer(buf) { [weak self] in
            Task { @MainActor in self?.stopPlayback() }
        }
        player.play()
    }

    func stopPlayback() {
        playerNode?.stop(); playEngine?.stop()
        playerNode = nil; playEngine = nil; isPlaying = false
    }
}

extension AudioEngine {
    /// Write mono Float PCM to a WAV file (used for export and for self-testing decode()).
    @discardableResult
    static func writeWav(_ samples: [Float], rate: Double, to url: URL) -> Bool {
        guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate,
                                      channels: 1, interleaved: false),
              let file = try? AVAudioFile(forWriting: url, settings: fmt.settings),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(samples.count))
        else { return false }
        buf.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { memcpy(buf.floatChannelData![0], $0.baseAddress, samples.count * 4) }
        return (try? file.write(from: buf)) != nil
    }

    /// Decode an audio file (WAV/AIFF/CAF/m4a/mp3/…) from storage to mono Float PCM + sample rate.
    static func decode(url: URL) -> ([Float], Double)? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let file = try? AVAudioFile(forReading: url) else { return nil }
        let fmt = file.processingFormat   // float32, deinterleaved
        guard file.length > 0,
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(file.length)),
              (try? file.read(into: buf)) != nil,
              let chans = buf.floatChannelData else { return nil }
        let frames = Int(buf.frameLength), nch = max(Int(fmt.channelCount), 1)
        var mono = [Float](repeating: 0, count: frames)
        for f in 0..<frames {
            var s: Float = 0
            for c in 0..<nch { s += chans[c][f] }
            mono[f] = s / Float(nch)
        }
        return (mono, fmt.sampleRate)
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
