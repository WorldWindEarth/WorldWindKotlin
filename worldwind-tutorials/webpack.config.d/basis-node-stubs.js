// noinspection JSUnnecessarySemicolon
;(function(config) {
    // THREE's basis_transcoder.js is Emscripten-generated and statically references the
    // Node-only `fs`, `path`, and `crypto` modules. The runtime branch is guarded by
    // `typeof window`, so the Node requires are dead in the browser, but webpack 5
    // doesn't polyfill them. `a` covers the same dynamic-require pattern Emscripten emits
    // for the .wasm loader glue (see draco-node-stubs.js).
    //
    // Mutate in place — see draco-node-stubs.js for the rationale.
    config.resolve = config.resolve || {};
    config.resolve.fallback = config.resolve.fallback || {};
    config.resolve.fallback.fs = false;
    config.resolve.fallback.path = false;
    config.resolve.fallback.crypto = false;
    config.resolve.fallback.a = false;
})(config);
