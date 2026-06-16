# worldwind-formats-gltf-draco

Draco compressed-mesh / point-cloud decoder for WorldWind's glTF + 3D Tiles pipeline.

Backs `installDracoDecoder()` across JVM (native libdraco_bridge JNI), Android (same
JNI through `assets/`), iOS (Kotlin/Native cinterop), and web (npm `draco3d` package
with `draco_decoder.wasm`).

## Kotlin/JS + Kotlin/Wasm consumers: required webpack config

The npm `draco3d` package ships an Emscripten-generated JS glue file that

1. statically references the Node-only `fs` and `path` modules, which webpack 5 no
   longer polyfills, and
2. fetches `draco_decoder.wasm` from a hard-coded relative URL at runtime, which
   webpack doesn't include in the bundle unless told to.

Both points break the bundle. Drop these two snippets into your app module's
`webpack.config.d/`:

**`draco-node-stubs.js`**

```js
;(function(config) {
    config.resolve = config.resolve || {};
    config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
        fs: false,
        path: false,
    });
})(config);
```

**`draco-wasm-asset.js`**

```js
;(function(config) {
    config.module = config.module || {};
    config.module.rules = (config.module.rules || []).concat([
        {
            test: /draco3d[\\/].*\.wasm$/,
            type: 'asset/resource',
            generator: { filename: '[name][ext]' },
        },
    ]);
})(config);
```

The first stubs the dead Node-branch requires; the second makes webpack emit
`draco_decoder.wasm` to the bundle output where Emscripten's `locateFile` hook
(driven by this module's `NativeDraco`) can fetch it.

JVM / Android / iOS consumers do not need any extra build configuration.
