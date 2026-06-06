package earth.worldwind.shape.milstd2525

import kotlin.js.JsAny

// Type aliases extracted from C5Ren.kt: that file is `@file:JsModule(...)` which the Kotlin
// compiler now disallows non-external declarations in. Karakum-generated bundles emit these
// `int / float / double` aliases for the dts-bundle-generator output; keeping them in a
// non-JsModule sibling file lets the rest of C5Ren.kt stay all-external without suppressions.
// On Kotlin/Wasm the JS `Number` type is not a valid interop type, so the aliases resolve to
// `Double` (every JS number is a double); callers already coerce via toInt()/toDouble().
typealias int = Double
typealias float = Double
typealias double = Double

// Kotlin/Wasm has no `kotlin.js.collections.JsReadonlyMap`/`JsMap`; the mil-sym-ts-web bindings
// only pass a JS Map straight through to JS, so a marker external type is enough.
external interface JsMap : JsAny
