//
//  WorldWindTutorialsApp.swift
//  Top-level SwiftUI App entry. Shows a tutorial picker on the leading side and
//  the WorldWind globe on the trailing side (or stacked on iPhone-class widths).
//

import SwiftUI
import WorldWindTutorials  // Kotlin/Native framework produced by `:worldwind-tutorials`

@main
struct WorldWindTutorialsApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(edges: .bottom)
        }
    }
}
