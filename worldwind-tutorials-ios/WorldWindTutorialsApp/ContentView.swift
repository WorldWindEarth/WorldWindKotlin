//
//  ContentView.swift
//  Tutorial-picker UI + globe pane.
//
//  The Kotlin framework exposes:
//    - `MainKt.createWorldWindow()` — a configured UIView (Kotlin's WorldWindow class is
//      a UIView subclass; K/N can't re-export Kotlin subclasses of Obj-C types as Swift
//      types, so we receive plain UIView and use helpers below to reach the engine).
//    - `MainKt.engineOf(window:)` / `MainKt.requestRedrawOn(window:)` — cast back to the
//      Kotlin WorldWindow on the Kotlin side and expose engine / redraw to Swift.
//    - `Tutorials.shared.all` — `[TutorialEntry]` for the picker rows.
//    - `Tutorials.shared.apply(engine:id:)` — switch tutorials on the running engine.
//    - `Tutorials.shared.stopCurrent()` — clear the active tutorial.
//

import SwiftUI
import WorldWindTutorials

struct ContentView: View {
    /// One WorldWindow lives for the lifetime of this view. Switching tutorials runs against
    /// its existing engine — we don't recreate the view because that would reset the GL state.
    @StateObject private var globe = GlobeStore()

    /// Currently selected tutorial id. `nil` means "show the basic globe with default layers".
    @State private var selectedId: String? = nil

    var body: some View {
        NavigationSplitView {
            TutorialPicker(selectedId: $selectedId)
                .navigationTitle("Tutorials")
        } detail: {
            ZStack {
                GlobeView(window: globe.window)
                    .ignoresSafeArea()
                    // Floor to non-zero on both axes. NavigationSplitView animates the
                    // detail pane through transient (W × 0) and (0 × H) layouts during
                    // sidebar push/pop on iPhone; CoreAnimation tries to allocate an
                    // IOSurface for the CAEAGLLayer at those sizes and floods the console
                    // with "Failed to create <W>x0 image slot" warnings. A 1pt min-frame
                    // is invisible but keeps the layer's backing surface allocatable.
                    .frame(minWidth: 1, minHeight: 1)
                if let title = selectedTitle {
                    VStack {
                        Spacer()
                        Text(title)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(.ultraThinMaterial, in: Capsule())
                            .padding(.bottom, 16)
                    }
                }
            }
        }
        // Force the detail pane to remain visible at full size on all devices. The default
        // `.automatic` style on iPhone-sized layouts collapses the detail to (0 × H) during
        // sidebar push/pop, which makes CoreAnimation try to allocate IOSurfaces of that
        // size for the embedded CAEAGLLayer and floods the console with
        // "Failed to create <W>x0 image slot" warnings.
        .navigationSplitViewStyle(.prominentDetail)
        .onChange(of: selectedId) { newValue in
            if let id = newValue {
                Tutorials.shared.apply(engine: MainKt.engineOf(window: globe.window), id: id)
            } else {
                Tutorials.shared.stopCurrent()
            }
            MainKt.requestRedrawOn(window: globe.window)
        }
    }

    private var selectedTitle: String? {
        guard let id = selectedId else { return nil }
        return Tutorials.shared.all.first(where: { $0.id == id })?.title
    }
}

/// Owns the long-lived WorldWindow (a UIView Kotlin gives us). Held in a class so SwiftUI's
/// value-typed `View` re-renders don't keep allocating fresh windows.
final class GlobeStore: ObservableObject {
    let window: UIView = MainKt.createWorldWindow()
}
