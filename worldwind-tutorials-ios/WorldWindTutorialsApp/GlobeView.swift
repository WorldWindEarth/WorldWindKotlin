//
//  GlobeView.swift
//  Bridges the Kotlin WorldWindow (a UIView subclass) into SwiftUI via UIViewRepresentable.
//  K/N cannot re-export Kotlin subclasses of Obj-C types into the framework header, so the
//  view arrives in Swift as a plain `UIView`. The Kotlin WorldWindow still manages its EAGL
//  context, framebuffer, CADisplayLink-driven render loop, and UITouch dispatch internally
//  — SwiftUI just sizes it, places it, and forwards taps into the Kotlin tutorial registry
//  so picker-driven tutorials (Collada / GLTF / TriangleMeshes / GeographicMeshes / Paths /
//  Polygons / Ellipses) can react to clicks.
//
//  We deliberately avoid recreating the view across re-renders: makeUIView is called once
//  for the lifetime of the host, and updateUIView is a no-op because all updates go through
//  engine mutations + requestRedraw (see ContentView for the helpers).
//

import SwiftUI
import UIKit
import WorldWindTutorials

struct GlobeView: UIViewRepresentable {
    let window: UIView

    func makeUIView(context: Context) -> UIView {
        // Install a tap recognizer that routes single taps through the Kotlin
        // `Tutorials.shared.screenTap` so picker-driven tutorials see them. The pan/pinch
        // gesture system inside WorldWindow continues to consume drags directly through
        // its own UITouch overrides — UITapGestureRecognizer only fires on a quick tap
        // with no drag, so the two coexist without conflict.
        let tap = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        tap.cancelsTouchesInView = false
        window.addGestureRecognizer(tap)
        context.coordinator.window = window
        return window
    }

    func updateUIView(_ uiView: UIView, context: Context) { }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject {
        weak var window: UIView?

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let window = window else { return }
            // UITapGestureRecognizer reports the tap in points, in the recognizer's view
            // coordinate space. Tutorials.screenTap re-multiplies by contentScaleFactor
            // to convert to viewport pixels, so we pass points through directly.
            let point = recognizer.location(in: window)
            Tutorials.shared.screenTap(
                wwd: window,
                xPoints: Float(point.x),
                yPoints: Float(point.y)
            )
        }
    }
}
