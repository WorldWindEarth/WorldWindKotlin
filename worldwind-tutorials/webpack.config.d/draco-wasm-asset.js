// noinspection JSUnnecessarySemicolon
;(function(config) {
    // draco3d ships `.wasm` files that the Emscripten JS loader fetches at runtime. Webpack
    // must NOT bundle them as ES async-WebAssembly modules — that strips the Emscripten glue
    // and webpack's auto-generated `WebAssembly.instantiate` call fails at runtime because
    // the wasm's `(import "a" "a" ...)` expects callable imports the glue would normally
    // provide. Treat them as `asset/resource` so webpack emits the `.wasm` file as-is with
    // its original name and NativeDraco's `locateFile()` override resolves the runtime URL
    // via `require('draco3d/draco_decoder.wasm')`.
    //
    // Prepend (not append) the rule with `unshift` so it wins against webpack's default
    // `.wasm` handling — otherwise the experiment-driven WebAssembly module type takes
    // precedence even when our type override should be in force.
    config.module = config.module || {};
    config.module.rules = config.module.rules || [];
    config.module.rules.unshift({
        test: /draco3d[\\/].*\.wasm$/,
        type: 'asset/resource',
        generator: { filename: '[name][ext]' },
    });
})(config);
