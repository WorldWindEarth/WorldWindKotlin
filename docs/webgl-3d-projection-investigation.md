# 3D camera-frustum projection on JS / WebGL — investigation log

**Date**: 2026-04-29
**Affected feature**: `ProjectedMediaSurface.imageProjection` (the 3D camera-frustum projection path used to drape drone video onto terrain via a per-fragment perspective transform)
**Resolution**: feature works on JVM and Android; on JS the path renders incorrectly. The toggle is hidden in `HtmlVideoOnTerrainTutorial`; users are documented to leave `imageProjection = null` on JS and rely on the 2D-homography path. Tracked as task #72.

This file exists so the next engineer who revisits the JS side doesn't have to retrace nine ruled-out hypotheses.

---

## 1. What the feature does

`ProjectedMediaSurface` has two interior render paths:

1. **2D-homography path** (default, `imageProjection = null`). Builds a `mat3` ground-to-image homography from the four ground-corner KLV tags (26-33 / 82-89). Per-fragment `mat3 * vec3` gives image UV. Cheap, exact on a flat ground, slightly off on relief.
2. **3D camera-frustum path** (opt-in, set `imageProjection = matrix4`). Per-fragment `mat4 * vec4(world_position, 1)` then `xy / w` for image UV. Mathematically exact regardless of terrain relief. Drives `Surface3DProjectionShaderProgram` via `DrawableProjectedSurface`.

The 3D path is the one that breaks on JS.

## 2. Symptom

With `imageProjection` set to a typical `bias × perspective × lookAt(eye, target, up)` matrix derived from KLV pose (this is what `CameraPose.setFromPlatformAndSensorPose` produces):

- **JVM and Android**: the drone footage drapes onto terrain inside the camera frustum exactly as expected. The yellow 4-corner outline (drawn separately by the homography path's outline shader) sits flush around the projected texture.
- **JS / WebGL**: the texture renders in a tiny patch — sometimes a few pixels, sometimes not at all, depending on the matrix at that playback moment. The yellow outline is correct (it uses different shader / data). The pattern is consistent across Chrome (ANGLE), Firefox, and even SwiftShader software rasterizer.

## 3. Verified facts

These were each established with diagnostic shaders that the user ran in both JVM and JS at the same playback moment and matrix inputs:

- **`CameraPose` matrix output is bit-identical** between JS and JVM at fp64 precision (logged via `[CameraPose-DIAG2]` ad-hoc instrumentation; confirmed identical to the last digit). So the CPU-side matrix construction is platform-invariant.
- **Vertex attribute `pointA.xyz` is identical** at 1m precision (logged via a "rainbow probe" fragment shader outputting `fract(pointA * scale)` as RGB). The terrain mesh feeds the same per-vertex coordinates to both platforms.
- **All 16 matrix uniform components** read identically in the fragment shader on both platforms (logged via per-component color probes).
- **A hardcoded matrix in shader source** produces identical visual output on both platforms. So the basic vertex / fragment / interpolation pipeline works correctly on JS.
- **WebGL 2 context with `gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT)` reports `precision: 23, rangeMin: 127, rangeMax: 127`** — full IEEE-754 single precision, the same as JVM. So `highp` in fragment shader on the user's WebGL 2 implementation is real fp32, not the spec-minimum.

So with bit-identical inputs and reported fp32 precision, JS still produces wrong per-fragment results. Conclusion: **the bug is downstream of every measurable input**, in the GPU shader compiler / driver / hardware path, and we cannot reach it from shader code.

## 4. What we tried

All of these were verified and **none fixed the JS rendering**. They are listed for completeness so they don't get re-tried:

### 4.1 Relative-To-Eye source-shifting (RTE)
Following the deck.gl / Cesium / Mapbox pattern. Pre-multiplies the matrix's translation column by the camera eye position so the matrix's col 3 collapses from raw ECEF (millions of metres) to ~`(0, 0, -1, 0)`. Per-tile a small `tileRelEye = tileOrigin - eyeWorld` offset is added to `pointA` shader-side, so the GPU-side mat-vec multiply only sees near-unit-magnitude operands.

**Why it didn't help**: this technique solves the magnitude-cancellation problem deck.gl/Cesium have because their **vertex buffers** carry raw ECEF (or Mercator-projected) positions of millions of metres. The CPU can't easily cancel that per-vertex; RTE shifts the cancellation to a per-tile matrix prep instead. **Our vertex buffer already carries tile-local coordinates** (`points` in `TerrainTile.kt` is `(world_pos - tile.origin).toFloat()`), and the per-tile CPU `multiplyByTranslation(tileOrigin)` cancels the matrix's translation column at fp64 precision before upload. By the time the GPU sees the matrix, every entry is already in the unit-to-hundreds range. RTE on top of this is mathematically equivalent — same final matrix on the GPU, just a different code arrangement to get there.

The RTE work landed as commit 992d8258 and was reverted as cleanup; lessons are preserved in this doc.

### 4.2 Shader-multiply variants
- `mat4 * vec4` directly.
- 4 separate row `vec4` uniforms, accumulated via `dot()`.
- mat3 + vec3 + vec3 + float decomposition (the rotation block, translation column, bottom-row rotation, and bottom-right scalar each as separate uniforms).
- `pre-divide uv at vertex stage` and pass uv as a varying. Skips the per-fragment divide, but linear interpolation of perspective-divided UV is geometrically wrong on slanted terrain (and JS still mis-rendered).
- `textureProj()` for the divide via the GPU's hardware texture-coordinate path. Boundary check via comparison only (`0 ≤ x ≤ w` instead of `0 ≤ x/w ≤ 1`), no manual divide. Even with both, JS still wrong.

### 4.3 fp64 emulation in fragment shader
Knuth/Dekker compensated arithmetic (`two-prod`, `two-sum`, `quick-two-sum`, full Dekker-Schewchuk add) implemented per deck.gl's project64 reference. Each scalar carries `(hi, lo)` such that `value = hi + lo` exactly. Matrix-vector multiply implemented via 4-component dot products in df64; perspective divide via Newton-step df64 division.

The Veltkamp-split constant `SPLITTER = 4097` (2^12 + 1) was uploaded as a uniform rather than a `const` to defeat algebraic simplification of `ta - (ta - a)` back to `a` by the shader compiler.

**Why it didn't help**: still produced the same tiny-region rendering on JS. The implementation runs the df64 chain at the same internal precision as the original `mat4 * vec4`, so emulating fp64 in fp32 doesn't help when the underlying fp32 is itself producing wrong results.

### 4.4 WebGL 2 / GLSL ES 3.00 migration
Override `glslVersion(dc) = dc.gl.glslVersion3` so the shader compiles as `#version 300 es` (or `#version 330 core` on JVM). Migrated `attribute` → `in`, `varying` → `out`/`in`, `gl_FragColor` → explicit `out vec4 fragColor`, `texture2D()` → `texture()`, `precision highp float;` declared explicitly in fragment shader.

WebGL 2's GLSL ES 3.00 mandates `highp` in the fragment shader (it was optional in WebGL 1's GLSL ES 1.00). The hope was that an implementation could downgrade `highp` to mediump silently in WebGL 1 but couldn't in WebGL 2.

**Why it didn't help**: shader compiles successfully (no console errors), reported precision is fp32 (verified via `getShaderPrecisionFormat`), yet the rendering is identical to before — still tiny region. The migration was reverted because it broke Android GLES 2 support and provided no JS benefit.

### 4.5 Other things ruled out
- Cache invalidation in `loadImageProjectionLocal` (matrix being re-uploaded incorrectly): tried always-upload, no change.
- WebGL warning at `DrawableSurfaceQuad` (vertex attributes 1/2/3 enabled without buffers bound): real warning, fixed in commit `1d026e4d` via try/finally disable-then-restore. Not the cause of the JS render bug — fix in place but the bug persists.
- KLV-vs-video PTS skew (could the JS player be sampling KLV at a different time than JVM?): yes there is real PTS skew in this codebase (task #64) but with hardcoded KLV inputs in the tutorial JVM and JS produced bit-identical CPU matrices and *still* differed visually. Skew rules out as the cause.
- WorldWind viewer's `dc.modelviewProjection` precision (could `gl_Position` be wrong, breaking varying interpolation?): geometry renders correctly on JS, so `gl_Position` and varying interpolation work. Only the `imageClip` varying (or its per-fragment recomputation) produces wrong values.

## 5. Where the code currently stands

- The 3D-projection shader is the original GLSL ES 1.00 form: `varying highp vec4 imageClip = imageProjectionLocal * vec4(pointA.xyz, 1.0)` in vertex, `imageClip.xy / imageClip.w` divide in fragment. It works on JVM and Android.
- `DrawableProjectedSurface.draw()` does CPU-side `imageProjectionLocal = imageProjection * Translate(tileOrigin)` per tile (the standard tile-local approach for fp32 per-vertex math). No RTE.
- `ProjectedMediaSurface.imageProjection` doc-comment flags the JS limitation and points readers here (commit `a97382e5`).
- `HtmlVideoOnTerrainTutorial.actions` is `arrayListOf<String>()` instead of `arrayListOf(VideoOnTerrainTutorial.ACTION_TOGGLE_3D)` — JS tutorial host hides the 3D toggle button. Inline comment in that file explains the one-line revert path when WebGL gets fixed.
- `ACTION_TOGGLE_3D` is still listed in `VideoOnTerrainTutorial.actions` (the inner cross-platform tutorial), so JVM and Android hosts still expose the toggle.

## 6. Future investigation paths

If JS 3D-projection becomes a hard requirement, the things still worth trying — in order of expected payoff:

### 6.1 Minimal repro + ANGLE / Chromium bug report
File a minimal HTML page that reproduces the bug with a hardcoded matrix and a few fixed vertex positions, no WorldWind machinery. If the bug persists in isolation, file at <https://issues.chromium.org/issues?q=componentid:1456277> (Internals>GPU>ANGLE) and Mozilla. Browser vendors are responsive to minimal repros for shader-precision regressions. Worth ~half a day to extract from this codebase.

### 6.2 RenderDoc or browser GPU trace
Capture the actual draw call on JS via Chrome's WebGL debugging extensions or Spector.js. Compare the per-vertex output of the vertex shader between JVM and JS (varying values entering the rasterizer). If the per-vertex `imageClip` is the same on both platforms and it's the rasterizer/interpolator producing wrong per-fragment values, that's a different class of bug than vertex-shader precision.

### 6.3 Dump uniform state via `gl.getUniform()`
After upload, read each matrix entry back via `gl.getUniform(program, location)` to confirm what the GPU actually has, bit-by-bit. We probed via shader output (mode-5 colour probes) but never did the JS-side `getUniform` readback.

### 6.4 Try fragment-shader `precise` keyword (WebGL 2 / GLSL ES 3.00)
GLSL ES 3.00 introduced `precise` for IEEE-754-strict arithmetic. Mark `imageClip` and the divide-result as `precise vec4 imageClip;` and `precise vec2 uv = ...`. Some implementations use this to disable fast-math optimisations. Single-line experiment.

### 6.5 Pre-project on CPU per visible tile
Heaviest workaround. Per tile: take the tile's vertex array, project each vertex through the matrix on CPU, store the resulting UV alongside the vertex position in a parallel buffer, upload as a 2D vertex attribute. The shader just reads the UV — no per-fragment matrix multiply. Loses perspective-correct interpolation across triangles (linear interpolation of pre-divided UV warps on slanted terrain) but that may be acceptable for sub-km drone footprints. Substantial refactor — last resort.

## 7. References

- WebGL Fundamentals — Precision Issues: <https://webglfundamentals.org/webgl/lessons/webgl-precision-issues.html>. Authoritative explanation that GLSL ES `highp` precision qualifiers are *minimums* not guarantees.
- WebGL2 Fundamentals — Perspective Correct Texture Mapping: <https://webgl2fundamentals.org/webgl/lessons/webgl-3d-perspective-correct-texturemapping.html>. Why dividing at vertex stage breaks perspective correctness.
- deck.gl `project64` GLSL: <https://github.com/visgl/deck.gl/blob/master/modules/core/src/shaderlib/project/project64-glsl.ts> (current path; rename history covered in their RFCs). The reference fp64-in-fp32 emulation we adapted.
- deck.gl coordinate-spaces RFC: <https://github.com/visgl/deck.gl/blob/master/dev-docs/RFCs/v8.0/coordinate-spaces-rfc.md>. The dynamic-translation pattern that pairs with `project64`.
- Cesium issue #817: <https://github.com/CesiumGS/cesium/issues/817>. Relative-to-Center (RTC) discussion, same problem class for terrain rendering.
- GLSL ES 1.00 spec §4.5.2: <http://learnwebgl.brown37.net/12_shader_language/documents/_GLSL_ES_Specification_1.0.17.pdf>. Precision-qualifier minimums table — the formal source of the WebGL-vs-OpenGL precision divergence.
- GLSL ES 3.00 spec table 4.5.4: <https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf>. Updated minimums for WebGL 2 — `highp` floor is `2^-16` relative precision (still less than IEEE-754 single, even though in practice every shipping driver provides fp32).
- Stack Overflow / forum threads collected during research: see task #72 description for the full annotated link list.
