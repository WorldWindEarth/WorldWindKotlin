// noinspection JSUnnecessarySemicolon
;(function(config) {
    // draco3d 1.5.7's main entry statically requires draco_decoder_nodejs.js, which itself
    // imports the Node-only `fs` and `path` modules. The browser runtime guards those
    // requires with `typeof window === 'undefined'`, so the Node code never executes — but
    // webpack 5 still resolves both branches at bundle time and fails on the missing core
    // modules. Stubbing them as `false` lets webpack produce the bundle; the dead-branch
    // requires return empty modules that are never invoked.
    //
    // `a` is a defensive stub for Emscripten's `require(condition ? 'a' : 'b')` dynamic-
    // require pattern in draco3d's wasm-loader glue — webpack tries every literal branch.
    //
    // Mutate `config.resolve.fallback` in place rather than reassigning it with
    // Object.assign({}, ...) — anything downstream in the Kotlin/JS pipeline that resets
    // `config.resolve` would otherwise wipe our fallback.
    config.resolve = config.resolve || {};
    config.resolve.fallback = config.resolve.fallback || {};
    config.resolve.fallback.fs = false;
    config.resolve.fallback.path = false;
    config.resolve.fallback.a = false;
})(config);
