package earth.worldwind.util

import org.w3c.dom.events.Event

/**
 * Shared by both browser targets so the event pipeline uses one model: a plain `(Event) -> Unit`
 * function rather than the external [org.w3c.dom.events.EventListener]. Kotlin/Wasm can't implement
 * or SAM-construct that interface, and kotlinx-browser exposes a `(Event) -> Unit` overload of
 * `addEventListener` on both js and wasm. This typed identity helper lets call sites read like the
 * old `EventListener { ... }` form — including the `return@eventListener` label and a stable lambda
 * identity for `removeEventListener`.
 */
internal fun eventListener(block: (Event) -> Unit): (Event) -> Unit = block
