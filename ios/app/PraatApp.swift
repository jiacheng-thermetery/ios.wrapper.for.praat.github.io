// PraatApp.swift — SwiftUI entry point for the Spraak derivative.
// GPL-3.0-or-later. This is an UNOFFICIAL modified version of Praat,
// not produced or endorsed by the original Praat authors.
import SwiftUI

@main
struct PraatApp: App {
    init() { praatios_init() }
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
