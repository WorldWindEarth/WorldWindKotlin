// noinspection JSUnnecessarySemicolon
;(function(config) {
    // draco3d 1.5.7's main entry statically requires draco_decoder_nodejs.js, which itself
    // imports the Node-only `fs` and `path` modules. The browser runtime guards those
    // requires with `typeof window === 'undefined'`, so the Node code never executes — but
    // webpack 5 still resolves both branches at bundle time and fails on the missing core
    // modules. Stubbing them as `false` lets webpack produce the bundle; the dead-branch
    // requires return empty modules that are never invoked.
    //
    // Mutate `config.resolve.fallback` in place rather than reassigning with
    // Object.assign({}, ...) — anything downstream in the Kotlin/JS pipeline that resets
    // `config.resolve` would otherwise wipe our fallback.
    //
    // Do NOT add `a: false` here even though webpack may report "Can't resolve 'a'" on
    // draco3d's wasm imports: `a` is Emscripten's minified imports-module name and the wasm
    // expects the JS glue to populate it with real callables. Stubbing it produces an empty
    // imports object → runtime `WebAssembly.instantiate` LinkError. The asset/resource rule
    // in draco-wasm-asset.js is the right fix: it stops webpack from trying to bundle the
    // .wasm as a module at all, so the 'a' import never enters the dependency graph.
    config.resolve = config.resolve || {};
    config.resolve.fallback = config.resolve.fallback || {};
    config.resolve.fallback.fs = false;
    config.resolve.fallback.path = false;
})(config);
