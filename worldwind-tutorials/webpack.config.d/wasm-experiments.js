// noinspection JSUnnecessarySemicolon
;(function(config) {
    // Webpack 5 disables WebAssembly by default and refuses to add .wasm files to the
    // module graph at all without an experiment flag — it errors with "module is not
    // flagged as WebAssembly module" even when an asset/resource rule explicitly matches
    // the file. Enable asyncWebAssembly so the draco-wasm-asset.js / basis-wasm-asset.js
    // rules can take over and emit the .wasm payloads as asset/resource URLs.
    config.experiments = Object.assign({}, config.experiments, {
        asyncWebAssembly: true,
    });
})(config);
