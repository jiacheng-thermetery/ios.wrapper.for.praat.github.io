// ContentView.swift — minimal phonetics console for the Spraak derivative.
// GPL-3.0-or-later.
import SwiftUI

private let defaultScript = """
# Praat runs on iOS! Edit this script and tap Run.
writeInfoLine: "Spraak — self test"
sound = Create Sound from formula: "tone", 1, 0.0, 0.5, 16000,
    ... "0.6*sin(2*pi*220*x) + 0.3*sin(2*pi*440*x)"
selectObject: sound
pitch = To Pitch: 0.0, 75, 600
selectObject: pitch
f0 = Get mean: 0.0, 0.0, "Hertz"
appendInfoLine: "Mean F0: ", fixed$ (f0, 2), " Hz   (expected ~220)"
selectObject: sound
spectrum = To Spectrum: "yes"
selectObject: spectrum
cog = Get centre of gravity: 2.0
appendInfoLine: "Spectral CoG: ", fixed$ (cog, 1), " Hz"
appendInfoLine: "Engine: Praat ", praatVersion$
"""

struct ContentView: View {
    @State private var script = defaultScript
    @State private var output = "Tap Run to execute the script."
    @State private var running = false
    @State private var showAbout = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Spraak").font(.headline)
                    Text("unofficial GPL-3.0 build").font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                Button { showAbout = true } label: { Image(systemName: "info.circle") }
                    .padding(.trailing, 4)
                Button(action: run) {
                    Label(running ? "Running…" : "Run", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(running)
            }
            .sheet(isPresented: $showAbout) { AboutView() }

            Text("Script").font(.caption).foregroundStyle(.secondary)
            TextEditor(text: $script)
                .font(.system(.callout, design: .monospaced))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .frame(maxHeight: 260)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))

            Text("Output").font(.caption).foregroundStyle(.secondary)
            ScrollView {
                Text(output)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(8)
            .background(Color(white: 0.96))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(.gray.opacity(0.4)))
        }
        .padding()
        .onAppear { run() }   // auto-run once so the demo shows live output
    }

    private func run() {
        running = true
        let src = script
        DispatchQueue.global(qos: .userInitiated).async {
            let result = String(cString: praatios_run(src))
            DispatchQueue.main.async {
                output = result
                running = false
            }
        }
    }
}

struct AboutView: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Spraak").font(.title2).bold()
                    Text("An unofficial iOS port of Praat. NOT produced or endorsed by the original authors.")
                        .font(.subheadline).foregroundStyle(.secondary)

                    Group {
                        Text("Based on Praat — doing phonetics by computer").bold()
                        Text("© 1992–2026 Paul Boersma & David Weenink, University of Amsterdam, and contributors. https://praat.org")
                    }

                    Group {
                        Text("License").bold()
                        Text("This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY. See the GNU GPL for details (bundled gpl-3.0.txt).")
                    }

                    Group {
                        Text("Corresponding source").bold()
                        Text("You have the right to the complete corresponding source for this app under GPL §6. It is the praat-ios repository at the commit this build was made from, including the ios/ folder. Bundled components (gsl, glpk, espeak, …) are GPL-compatible; see ios/LICENSING.md.")
                    }

                    Text("Note: GPL software cannot be distributed via the Apple App Store. This build is for source distribution and sideloading only.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("About")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
