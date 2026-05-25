// SettingsView.swift — analysis settings sheet (mirrors Praat's settings dialogs).
// Part of the Spraak derivative. GPL-3.0-or-later.
import SwiftUI

struct AnalysisSettingsView: View {
    @Binding var settings: AnalysisSettings
    var onApply: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section("Spectrogram") {
                    num("View range (Hz)", $settings.spectrogramMaxFreq)
                    num("Window length (s)", $settings.spectrogramWindow)
                    num("Dynamic range (dB)", $settings.spectrogramDynamicRange)
                }
                Section("Pitch") {
                    num("Floor (Hz)", $settings.pitchFloor)
                    num("Ceiling (Hz)", $settings.pitchCeiling)
                }
                Section("Formant") {
                    num("Max frequency (Hz)", $settings.formantMaxFreq)
                    Stepper("Number of formants: \(settings.formantCount)",
                            value: $settings.formantCount, in: 1...5)
                    num("Window length (s)", $settings.formantWindow)
                }
                Section("Intensity") {
                    num("View min (dB)", $settings.intensityMin)
                    num("View max (dB)", $settings.intensityMax)
                }
                Section {
                    Button("Reset to defaults") { settings = AnalysisSettings() }
                }
            }
            .navigationTitle("Analysis settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Apply") { onApply(); dismiss() } }
            }
        }
    }

    private func num(_ label: String, _ value: Binding<Double>) -> some View {
        LabeledContent(label) {
            TextField(label, value: value, format: .number)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 120)
        }
    }
}
