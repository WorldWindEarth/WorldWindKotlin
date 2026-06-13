// noinspection JSUnnecessarySemicolon
;(function(config) {
    // THREE's basis_transcoder.js is Emscripten-generated and statically references the
    // Node-only `fs`, `path`, and `crypto` modules. The runtime branch is guarded by
    // `typeof window`, so the Node requires are dead in the browser, but webpack 5
    // doesn't polyfill them. Stubbing as `false` makes the dead-branch requires return
    // empty modules that are never invoked.
    //
    // Mutate in place — see draco-node-stubs.js for the rationale, and the note there about
    // why `a` is NOT in this list.
    config.resolve = config.resolve || {};
    config.resolve.fallback = config.resolve.fallback || {};
    config.resolve.fallback.fs = false;
    config.resolve.fallback.path = false;
    config.resolve.fallback.crypto = false;
})(config);
