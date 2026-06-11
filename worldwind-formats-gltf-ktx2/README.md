# worldwind-formats-gltf-ktx2

KHR_texture_basisu (KTX2 / Basis-Universal) texture decoder for WorldWind's glTF + 3D
Tiles pipeline.

Backs `installKtx2Decoder()` across JVM (native libktx JNI), Android (same JNI through
`assets/`), iOS (Kotlin/Native cinterop, opt-in `-Pworldwind.ktx2.buildIosNative=true`),
and web (THREE.js's bundled basis_transcoder WASM via npm).

## Kotlin/JS + Kotlin/Wasm consumers: required webpack config

The module's web actual `require()`s the basis transcoder from
`three/examples/jsm/libs/basis/basis_transcoder.{js,wasm}`. The npm `three` package
gets pulled transitively. Two webpack snippets are required in your app module's
`webpack.config.d/`:

**`basis-node-stubs.js`**

```js
;(function(config) {
    config.resolve = config.resolve || {};
    config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
        fs: false,
        path: false,
        crypto: false,
    });
})(config);
```

**`basis-wasm-asset.js`**

```js
;(function(config) {
    config.module = config.module || {};
    config.module.rules = (config.module.rules || []).concat([
        {
            test: /three[\\/]examples[\\/]jsm[\\/]libs[\\/]basis[\\/].*\.wasm$/,
            type: 'asset/resource',
            generator: { filename: '[name][ext]' },
        },
    ]);
})(config);
```

The first stubs the dead Node-branch requires (THREE's basis transcoder is
Emscripten-generated and statically references `fs` / `path` / `crypto`). The second
makes webpack emit `basis_transcoder.wasm` to the bundle output where Emscripten's
`locateFile` hook (driven by this module's `NativeKtx2`) can fetch it.

If you also use [worldwind-formats-gltf-draco](../worldwind-formats-gltf-draco/), keep
both module's snippet pairs side-by-side — webpack merges the fallbacks.

JVM / Android / iOS consumers do not need any extra build configuration.

## Bundle size note

Webpack tree-shakes THREE.js: only `basis_transcoder.js` and `basis_transcoder.wasm`
end up in the bundle (~250 KiB total), not the THREE rendering engine. The `three`
dep is declared at build-time only and contributes nothing at runtime outside the
two transcoder files.
