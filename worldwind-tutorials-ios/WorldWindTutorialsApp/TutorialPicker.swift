//
//  TutorialPicker.swift
//  A simple List-driven picker over `Tutorials.shared.all`. Selecting a row updates the
//  parent's `selectedId` binding; the parent applies the tutorial to the engine.
//

import SwiftUI
import WorldWindTutorials

struct TutorialPicker: View {
    @Binding var selectedId: String?

    var body: some View {
        List(selection: $selectedId) {
            ForEach(Tutorials.shared.all, id: \.id) { entry in
                Text(entry.title).tag(Optional(entry.id))
            }
        }
        .listStyle(.sidebar)
    }
}
