// noinspection JSUnnecessarySemicolon
;(function(config) {
    // THREE.js ships basis_transcoder.wasm at examples/jsm/libs/basis/. basis_transcoder.js
    // is an Emscripten module-output that exposes its factory on `globalThis.BASIS` rather
    // than via module.exports — so we emit BOTH the .js and the .wasm as static assets,
    // then load the .js via dynamic <script> injection (see NativeKtx2.createBasisModule).
    // The require() call in Kotlin returns the asset URL string thanks to asset/resource.
    //
    // Same prepend-with-unshift rationale as draco-wasm-asset.js — webpack's default .wasm
    // handling (whether or not the asyncWebAssembly experiment is on) would otherwise wrap
    // the file as an ES module and skip our type override.
    config.module = config.module || {};
    config.module.rules = config.module.rules || [];
    config.module.rules.unshift({
        test: /three[\\/]examples[\\/]jsm[\\/]libs[\\/]basis[\\/]basis_transcoder\.(js|wasm)$/,
        type: 'asset/resource',
        generator: { filename: '[name][ext]' },
    });
})(config);
