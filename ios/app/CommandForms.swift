// CommandForms.swift — parameter dialogs for common Praat commands.
// Part of the Spraak derivative. GPL-3.0-or-later.
//
// A field-driven form whose values assemble a Praat script line (e.g. "To Pitch: 0, 75, 600").
// Curated specs cover the high-use commands; anything else can be typed in the free script field.
import SwiftUI

enum FieldKind: Equatable {
    case real, integer, text, boolean, choice([String])
}

struct CmdField: Identifiable {
    let id = UUID()
    let label: String
    var value: String
    var kind: FieldKind = .real
    /// the script argument for this field's current value
    var argument: String {
        switch kind {
        case .real, .integer: return value.isEmpty ? "0" : value
        case .boolean:        return "\"\(value == "1" || value.lowercased() == "yes" ? "yes" : "no")\""
        case .text, .choice:  return "\"\(value)\""
        }
    }
}

struct CmdSpec: Identifiable {
    let id = UUID()
    let title: String       // menu label, e.g. "To Pitch…"
    let command: String     // script command name, e.g. "To Pitch"
    var fields: [CmdField]
    var isCreate: Bool = false   // true → run without a selection (Create…/Read…)

    func scriptLine(_ values: [String]) -> String {
        let args = zip(fields, values).map { f, v -> String in
            var ff = f; ff.value = v; return ff.argument
        }
        return args.isEmpty ? command : "\(command): \(args.joined(separator: ", "))"
    }
}

extension CmdSpec {
    /// Specs for the New menu (object creation).
    static let creates: [CmdSpec] = [
        CmdSpec(title: "Sound from formula…", command: "Create Sound from formula", fields: [
            CmdField(label: "Name", value: "sound", kind: .text),
            CmdField(label: "Channels", value: "1", kind: .integer),
            CmdField(label: "Start time (s)", value: "0", kind: .real),
            CmdField(label: "End time (s)", value: "1", kind: .real),
            CmdField(label: "Sampling frequency (Hz)", value: "44100", kind: .real),
            CmdField(label: "Formula", value: "0.5*sin(2*pi*440*x)", kind: .text),
        ], isCreate: true),
        CmdSpec(title: "Sound as tone complex…", command: "Create Sound as tone complex", fields: [
            CmdField(label: "Name", value: "tones", kind: .text),
            CmdField(label: "Start time (s)", value: "0", kind: .real),
            CmdField(label: "End time (s)", value: "1", kind: .real),
            CmdField(label: "Sampling frequency (Hz)", value: "44100", kind: .real),
            CmdField(label: "Phase", value: "cosine", kind: .choice(["cosine", "sine"])),
            CmdField(label: "Frequency step (Hz)", value: "100", kind: .real),
            CmdField(label: "First frequency (Hz)", value: "0", kind: .real),
            CmdField(label: "Ceiling (Hz)", value: "0", kind: .real),
            CmdField(label: "Number of components", value: "0", kind: .integer),
        ], isCreate: true),
        CmdSpec(title: "TextGrid…", command: "Create TextGrid", fields: [
            CmdField(label: "Start time (s)", value: "0", kind: .real),
            CmdField(label: "End time (s)", value: "1", kind: .real),
            CmdField(label: "Tier names", value: "phones words", kind: .text),
            CmdField(label: "Point tiers", value: "", kind: .text),
        ], isCreate: true),
        CmdSpec(title: "Table with columns…", command: "Create Table with column names", fields: [
            CmdField(label: "Name", value: "table", kind: .text),
            CmdField(label: "Number of rows", value: "10", kind: .integer),
            CmdField(label: "Column names", value: "x y", kind: .text),
        ], isCreate: true),
    ]

    /// Specs for object actions, keyed by class.
    static let byClass: [String: [CmdSpec]] = [
        "Sound": [
            CmdSpec(title: "To Pitch…", command: "To Pitch", fields: [
                CmdField(label: "Time step (s)", value: "0", kind: .real),
                CmdField(label: "Pitch floor (Hz)", value: "75", kind: .real),
                CmdField(label: "Pitch ceiling (Hz)", value: "600", kind: .real),
            ]),
            CmdSpec(title: "To Spectrogram…", command: "To Spectrogram", fields: [
                CmdField(label: "Window length (s)", value: "0.005", kind: .real),
                CmdField(label: "Max frequency (Hz)", value: "5000", kind: .real),
                CmdField(label: "Time step (s)", value: "0.002", kind: .real),
                CmdField(label: "Frequency step (Hz)", value: "20", kind: .real),
                CmdField(label: "Window shape", value: "Gaussian",
                         kind: .choice(["Gaussian", "Hanning", "Hamming", "Bartlett", "Welch", "square"])),
            ]),
            CmdSpec(title: "To Formant (burg)…", command: "To Formant (burg)", fields: [
                CmdField(label: "Time step (s)", value: "0", kind: .real),
                CmdField(label: "Max number of formants", value: "5", kind: .real),
                CmdField(label: "Max formant (Hz)", value: "5500", kind: .real),
                CmdField(label: "Window length (s)", value: "0.025", kind: .real),
                CmdField(label: "Pre-emphasis from (Hz)", value: "50", kind: .real),
            ]),
            CmdSpec(title: "To Intensity…", command: "To Intensity", fields: [
                CmdField(label: "Minimum pitch (Hz)", value: "100", kind: .real),
                CmdField(label: "Time step (s)", value: "0", kind: .real),
                CmdField(label: "Subtract mean", value: "yes", kind: .boolean),
            ]),
            CmdSpec(title: "To Harmonicity (cc)…", command: "To Harmonicity (cc)", fields: [
                CmdField(label: "Time step (s)", value: "0.01", kind: .real),
                CmdField(label: "Minimum pitch (Hz)", value: "75", kind: .real),
                CmdField(label: "Silence threshold", value: "0.1", kind: .real),
                CmdField(label: "Periods per window", value: "1", kind: .real),
            ]),
            CmdSpec(title: "Extract part…", command: "Extract part", fields: [
                CmdField(label: "Start time (s)", value: "0", kind: .real),
                CmdField(label: "End time (s)", value: "1", kind: .real),
                CmdField(label: "Window shape", value: "rectangular",
                         kind: .choice(["rectangular", "Hanning", "Hamming", "Gaussian1", "Gaussian2"])),
                CmdField(label: "Relative width", value: "1", kind: .real),
                CmdField(label: "Preserve times", value: "no", kind: .boolean),
            ]),
            CmdSpec(title: "Filter (pass Hann band)…", command: "Filter (pass Hann band)", fields: [
                CmdField(label: "From frequency (Hz)", value: "0", kind: .real),
                CmdField(label: "To frequency (Hz)", value: "5000", kind: .real),
                CmdField(label: "Smoothing (Hz)", value: "100", kind: .real),
            ]),
        ],
        "Pitch": [
            CmdSpec(title: "Get value at time…", command: "Get value at time", fields: [
                CmdField(label: "Time (s)", value: "0.5", kind: .real),
                CmdField(label: "Unit", value: "Hertz", kind: .choice(["Hertz", "mel", "semitones re 100 Hz"])),
                CmdField(label: "Interpolation", value: "linear", kind: .choice(["nearest", "linear"])),
            ]),
            CmdSpec(title: "Get mean…", command: "Get mean", fields: [
                CmdField(label: "From time (s)", value: "0", kind: .real),
                CmdField(label: "To time (s)", value: "0", kind: .real),
                CmdField(label: "Unit", value: "Hertz", kind: .choice(["Hertz", "mel", "semitones re 100 Hz"])),
            ]),
        ],
        "Formant": [
            CmdSpec(title: "Get value at time…", command: "Get value at time", fields: [
                CmdField(label: "Formant number", value: "1", kind: .integer),
                CmdField(label: "Time (s)", value: "0.5", kind: .real),
                CmdField(label: "Unit", value: "hertz", kind: .choice(["hertz", "bark"])),
                CmdField(label: "Interpolation", value: "linear", kind: .choice(["linear"])),
            ]),
        ],
        "Intensity": [
            CmdSpec(title: "Get value at time…", command: "Get value at time", fields: [
                CmdField(label: "Time (s)", value: "0.5", kind: .real),
                CmdField(label: "Interpolation", value: "cubic", kind: .choice(["nearest", "linear", "cubic"])),
            ]),
        ],
    ]
}

struct CommandFormView: View {
    let spec: CmdSpec
    var onRun: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var values: [String]

    init(spec: CmdSpec, onRun: @escaping (String) -> Void) {
        self.spec = spec; self.onRun = onRun
        _values = State(initialValue: spec.fields.map { $0.value })
    }

    var body: some View {
        NavigationView {
            Form {
                ForEach(Array(spec.fields.enumerated()), id: \.offset) { i, field in
                    row(i, field)
                }
            }
            .navigationTitle(spec.title.replacingOccurrences(of: "…", with: ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Run") { onRun(spec.scriptLine(values)); dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder private func row(_ i: Int, _ field: CmdField) -> some View {
        switch field.kind {
        case .boolean:
            Toggle(field.label, isOn: Binding(
                get: { values[i] == "yes" || values[i] == "1" },
                set: { values[i] = $0 ? "yes" : "no" }))
        case .choice(let options):
            Picker(field.label, selection: $values[i]) {
                ForEach(options, id: \.self) { Text($0).tag($0) }
            }
        default:
            LabeledContent(field.label) {
                TextField(field.label, text: $values[i])
                    .keyboardType(field.kind == .text ? .default : .numbersAndPunctuation)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 160)
            }
        }
    }
}
