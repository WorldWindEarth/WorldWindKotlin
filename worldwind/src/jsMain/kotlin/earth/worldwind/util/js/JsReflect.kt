package earth.worldwind.util.js

/**
 * Typed view of a JS object's `constructor.name` for cross-Realm class-identity checks.
 *
 * The canonical `instanceof` check fails when the class symbol lives in a different Realm —
 * e.g. a popout window's `WebGL2RenderingContext` is not `instanceof` the main window's
 * class. Comparing the constructor's `name` string is Realm-agnostic, which is what every
 * cross-Realm GL-context probe in this project relies on.
 */
internal external interface JsObjectWithConstructorName {
    val constructor: JsConstructorRef
}

internal external interface JsConstructorRef {
    val name: String
}
