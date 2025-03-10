@JsModule("@io.github.missioncommand/mil-sym-js/dist/sm-bc.min.js")
@JsNonModule
external val milSymJs: Any?

@Suppress("DEPRECATION", "unused")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
val stylesAndFonts = run {
    milSymJs
    js("require('@io.github.missioncommand/mil-sym-js/dist/renderer.css')")
}