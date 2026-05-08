# WorldWind Tutorials — iOS (SwiftUI)

The SwiftUI shell that hosts the Kotlin/Native `:worldwind-tutorials` framework. It
shows a sidebar tutorial picker and a full-bleed globe pane.

## Layout

```
worldwind-tutorials-ios/
├── WorldWindTutorials.xcodeproj/      Xcode project — open this
├── WorldWindTutorialsApp/
│   ├── WorldWindTutorialsApp.swift   App entry, NavigationSplitView host
│   ├── ContentView.swift              Picker + globe orchestration
│   ├── TutorialPicker.swift           List<TutorialEntry>
│   ├── GlobeView.swift                UIViewRepresentable around WorldWindow
│   └── Info.plist
└── README.md
```

## Running it

1. Make sure full Xcode is selected (not just Command Line Tools):
   ```
   sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
   ```
2. Open the project:
   ```
   open worldwind-tutorials-ios/WorldWindTutorials.xcodeproj
   ```
3. Pick an iOS Simulator destination (e.g. iPhone 17 Pro). ⌘R.

The first build runs `./gradlew :worldwind-tutorials:linkDebugFrameworkIos…` via a
Run Script Build Phase to produce the K/N framework — that step takes ~20 s on a clean
build, then is up-to-date on subsequent builds. Once the framework exists Xcode compiles
the SwiftUI shell against it and launches in the simulator.

## How the project is wired

- **Module name** — Both the framework and the consuming app are called
  `WorldWindTutorials`, but Xcode can't have two modules with the same name in one build
  graph. The app target sets `PRODUCT_MODULE_NAME = WorldWindTutorialsApp` so its Swift
  module is `WorldWindTutorialsApp` while the bundle is still `WorldWindTutorials.app`.
- **No `DEBUG` macro** — the K/N framework header has `@property int32_t DEBUG` on
  Logger. If clang sees `DEBUG=1` in `GCC_PREPROCESSOR_DEFINITIONS` while parsing the
  module map it expands the property to `int32_t 1` and the build fails with
  "expected member name". The project deliberately omits `DEBUG=1`. SwiftUI/Swift code
  uses `SWIFT_ACTIVE_COMPILATION_CONDITIONS = "DEBUG …"` (a Swift-only flag) so legacy
  `#if DEBUG` Swift checks still work.
- **Framework search paths** — selected per `[sdk=…][arch=…]` so debug/release ×
  device/sim/x86 each pick the right K/N output directory under
  `worldwind-tutorials/build/bin/<konanTarget>/<configuration>Framework`.
- **System frameworks** — `OTHER_LDFLAGS` explicitly passes `-framework OpenGLES
  AVFoundation CoreVideo QuartzCore CoreText CoreGraphics ImageIO Security`, since the
  K/N runtime references those internally and a static framework can't propagate
  `LC_LINKER_OPTION` records to the consumer.
- **No EAGL framework** — there's no separate EAGL.framework on iOS Simulator (the
  `EAGLContext` symbols live in OpenGLES.framework). Only `-framework OpenGLES` is
  needed.
- **Resources** — `EGM96.dat` plus `drone_motion.{mp4,json}` are referenced by relative
  path from the host module's `moko-resources/assets` directories and copied into the
  `.app` at build time.
- **moko stub bundles** — `MokoStubBundles/earth.worldwind.main.bundle/` and
  `MokoStubBundles/earth.worldwind.tutorials.main.bundle/` (each with one `Info.plist`)
  satisfy moko-resources' `NSBundle.loadableBundle(...)` lazy lookup at runtime. The
  generated `MR.kt` for `:worldwind` and `:worldwind-tutorials` looks up bundles named
  `earth.worldwind.main` and `earth.worldwind.tutorials.main`. We strip the moko pack
  action at compile time (the `:` in the original bundle name breaks Windows builds),
  so the .app must ship its own stubs. The stubs are empty — `EGM96.dat` is loaded
  from the main bundle directly by the iOS `EGM96Geoid`.
- **WorldWindow type erasure** — Kotlin's `WorldWindow` (a UIView subclass) cannot be
  re-imported as a Swift type (Kotlin/Native limitation). The Obj-C header marks it
  `unavailable("Kotlin subclass of Objective-C class can't be imported")`. Swift code
  receives instances as plain `UIView` and uses two top-level helpers in `MainKt` —
  `engineOf(window:)` and `requestRedrawOn(window:)` — to reach the engine and request
  redraws. Tap dispatch already accepts `UIView` (via the `Tutorials.screenTap` API
  that erases its `wwd` parameter to UIView in the Obj-C header).
- **Static framework** — set in `worldwind-tutorials/build.gradle.kts` via
  `isStatic = true`. The SwiftUI app links it as a static framework with `Embed = Do
  Not Embed`. To re-export `:worldwind` types (so Swift sees `WorldWind`,
  `BasicWorldWindowController`, etc., instead of stripped names like
  `WorldwindWorldWind`), the iOS framework block uses `export(project(":worldwind"))`,
  and the iosMain source set declares `:worldwind` as `api` rather than the
  `commonMain` `implementation`.

## Tutorials NOT exposed in this shell

- **MIL-STD-2525**: not compiled for iOS — the symbol-rendering code lives in the
  engine's `nonIosMain` source set (the upstream `mil-sym` libraries are JVM/Android/JS
  only). The iOS framework simply doesn't include those types.
- **GeoPackage**: not implemented on iOS — the GeoPackage cache stack is in
  `jvmCommonMain` (Android + JVM). Apps needing offline tile caching on iOS should wire
  a custom `CacheTileFactory` against their preferred storage layer.

## Picker-driven tutorials

`Collada`, `GLTF`, `Triangle meshes`, `Geographic meshes`, `Paths`, `Polygons`, and
`Ellipses` all react to taps on the globe. `GlobeView.swift` installs a
`UITapGestureRecognizer` that routes the tap location through
`Tutorials.shared.screenTap(wwd:xPoints:yPoints:)`. The Kotlin side dispatches to either
a depth-pick (for placemark/path/polygon/GLTF picker overlays) or a ray-pick (for
mesh/Collada hit-testing) depending on the current tutorial.

## Troubleshooting

- **"Cannot find 'WorldWindTutorialsApp' in scope" / "No such module 'WorldWindTutorials'"**
  — gradle hasn't built the framework yet. Build the scheme once (⌘B) so the Run Script
  phase produces the framework; subsequent builds index it correctly.
- **"expected member name or ';' after declaration specifiers" inside
  `WorldWindTutorials.h` line ~23890** — `DEBUG=1` got reintroduced into project
  Debug `GCC_PREPROCESSOR_DEFINITIONS`. Remove it; the K/N framework's `Logger.DEBUG`
  property collides with the macro.
- **Black screen, no globe** — check the Xcode console for "WorldWindow" log entries.
  Most often this means `EGM96.dat` isn't in the bundle (verify `Build Phases → Copy
  Bundle Resources` includes it).
- **Crash at launch: `kotlin.IllegalArgumentException: bundle with identifier
  earth.worldwind.main not found`** — `MokoStubBundles/earth.worldwind.main.bundle` (or
  `…tutorials.main.bundle`) is missing or wasn't copied into the `.app`. moko-resources'
  generated `MR.bundle` lazily calls `NSBundle.loadableBundle(...)` the first time any
  `MR.assets.<X>` is touched (e.g. `EGM96Geoid()` default arg → `MR.assets.EGM96_dat`)
  and crashes if no bundle with the matching `CFBundleIdentifier` exists. Both stubs
  must be in `Build Phases → Copy Bundle Resources`.
- **"ld: framework 'EAGL' not found"** — remove `-framework EAGL` from `OTHER_LDFLAGS`.
  EAGL isn't a separate framework on iOS Simulator — it's part of OpenGLES.framework.
- **Tutorial picker rows show but globe doesn't react** — verify the build script
  rebuilt the framework after a Kotlin code change. Xcode caches the linked framework
  even when source files update.
- **AVPlayer video stays black for ~½ second** — expected. AVFoundation decodes
  asynchronously and `hasNewPixelBufferForItemTime` returns false until the first frame
  lands. The CADisplayLink keeps firing so the texture catches up on the next tick.
- **`pick(x, y)` returns an empty list** — iOS picking is async (resolved on the next
  CADisplayLink tick). Use `pickAsync(x, y).await()` from a coroutine instead.

## First-build gotcha on macOS: moko-resources `:` in path

`worldwind/build.gradle.kts` strips moko-resources' `PackAppleResourcesToKLibAction`
before compileKotlinIos* tasks because the action writes a directory named
`<group>:<artifact>.bundle`. The `:` is illegal on Windows NTFS, and some Xcode framework
copy phases on macOS reject it too. The strip is unconditional now (the iOS framework
bundles no moko resources either way; apps include `EGM96.dat` + optional
`drone_motion.{mp4,json}` directly in their `.app`, as this project does).
