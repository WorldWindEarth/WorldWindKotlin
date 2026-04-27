package earth.worldwind.render.program

import earth.worldwind.draw.DrawContext
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.*

/**
 * GPU kernel shader for [earth.worldwind.shape.ViewshedSightline]. Renders a fullscreen triangle
 * over an N×N off-screen FBO; each fragment performs an Amanatides–Woo grid traversal from the
 * observer to its own target cell, sampling elevations from an `R32F` texture and writing RGBA8
 * visible / occluded / transparent. The result is read back to CPU and projected via
 * [earth.worldwind.shape.SurfaceImage].
 *
 * Requires GLES 3 / WebGL 2 / GL 3.3 core: the kernel uses `texelFetch`, sized float-format
 * texture sampling, and the `out vec4` fragment output. [glslVersion] is overridden to emit
 * `#version 300 es` / `#version 330 core` rather than the legacy 1.20 directive.
 */
class ViewshedKernelShaderProgram : AbstractShaderProgram() {

    override var programSources = arrayOf(VERTEX_SOURCE, FRAGMENT_SOURCE)
    override val attribBindings = emptyArray<String>() // vertices come from gl_VertexID, no attribs

    override fun glslVersion(dc: DrawContext) = dc.gl.glslVersion3

    private var elevTexId = KglUniformLocation.NONE
    private var gridSizeId = KglUniformLocation.NONE
    private var observerCellId = KglUniformLocation.NONE
    private var observerAltId = KglUniformLocation.NONE
    private var earthRadiusId = KglUniformLocation.NONE
    private var metersPerPixelId = KglUniformLocation.NONE
    private var verticalExaggerationId = KglUniformLocation.NONE
    private var areaModeId = KglUniformLocation.NONE
    private var areaHalfPxId = KglUniformLocation.NONE
    private var visibleColorId = KglUniformLocation.NONE
    private var occludedColorId = KglUniformLocation.NONE
    private var missingValueId = KglUniformLocation.NONE

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        elevTexId = gl.getUniformLocation(program, "elevTex")
        gl.uniform1i(elevTexId, 0) // sampler bound to GL_TEXTURE0
        gridSizeId = gl.getUniformLocation(program, "gridSize")
        observerCellId = gl.getUniformLocation(program, "observerCell")
        observerAltId = gl.getUniformLocation(program, "observerAlt")
        earthRadiusId = gl.getUniformLocation(program, "earthRadius")
        metersPerPixelId = gl.getUniformLocation(program, "metersPerPixel")
        verticalExaggerationId = gl.getUniformLocation(program, "verticalExaggeration")
        areaModeId = gl.getUniformLocation(program, "areaMode")
        areaHalfPxId = gl.getUniformLocation(program, "areaHalfPx")
        visibleColorId = gl.getUniformLocation(program, "visibleColor")
        occludedColorId = gl.getUniformLocation(program, "occludedColor")
        missingValueId = gl.getUniformLocation(program, "missingValue")
    }

    fun loadGridSize(width: Int, height: Int) = gl.uniform2i(gridSizeId, width, height)
    fun loadObserverCell(x: Int, y: Int) = gl.uniform2i(observerCellId, x, y)
    fun loadObserverAltitude(meters: Float) = gl.uniform1f(observerAltId, meters)
    fun loadEarthRadius(meters: Float) = gl.uniform1f(earthRadiusId, meters)
    fun loadMetersPerPixel(x: Float, y: Float) = gl.uniform2f(metersPerPixelId, x, y)
    fun loadVerticalExaggeration(ve: Float) = gl.uniform1f(verticalExaggerationId, ve)

    /** [mode] = 0 for circular AOI, 1 for rectangular. [halfX] / [halfY] are half-extents in pixels. */
    fun loadArea(mode: Int, halfX: Float, halfY: Float) {
        gl.uniform1i(areaModeId, mode)
        gl.uniform2f(areaHalfPxId, halfX, halfY)
    }

    // WorldWind uses premultiplied-alpha blending throughout (`glBlendFunc(GL_ONE,
    // GL_ONE_MINUS_SRC_ALPHA)` in WorldWind.kt). The kernel writes its colour straight to the
    // RGBA8 FBO, which `SurfaceImage`'s drawable then samples and feeds into that blend; if we
    // pass straight (un-premultiplied) RGBA, transparency reads as over-bright over background.
    // Premultiply here to match the convention used by SightlineProgram et al.
    fun loadVisibleColor(c: Color) = gl.uniform4f(visibleColorId, c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha)
    fun loadOccludedColor(c: Color) = gl.uniform4f(occludedColorId, c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha)
    fun loadMissingValue(v: Float) = gl.uniform1f(missingValueId, v)

    companion object {
        val KEY = ViewshedKernelShaderProgram::class

        // Fullscreen triangle in NDC, emitted from gl_VertexID without a vertex buffer:
        // (-1,-1), (3,-1), (-1,3). One triangle covers the entire viewport — cheaper than a
        // quad's two triangles, no buffer-management overhead. gl_VertexID is core in GLES 3 /
        // WebGL 2 / GL 3.3 core, all of which are required by the fragment shader anyway.
        private val VERTEX_SOURCE = """
            precision highp float;

            const vec2 VERTS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));

            void main() {
                gl_Position = vec4(VERTS[gl_VertexID], 0.0, 1.0);
            }
        """.trimIndent()

        // Per-fragment Amanatides–Woo walk from the observer to the target cell, with bilerp
        // sampling at sub-pixel ray-cell crossings and an earth-curvature + atmospheric-
        // refraction correction on apparent angle. MAX_STEPS is a hard safety cap; the walk
        // normally exits via `xi == tx && yi == ty` after at most |dx|+|dy| iterations.
        private val FRAGMENT_SOURCE = """
            precision highp float;
            precision highp int;
            precision highp sampler2D;

            uniform sampler2D elevTex;
            uniform ivec2 gridSize;
            uniform ivec2 observerCell;
            uniform float observerAlt;
            uniform float earthRadius;
            uniform vec2 metersPerPixel;
            uniform float verticalExaggeration;
            uniform int areaMode;        // 0 = circle, 1 = rectangle
            uniform vec2 areaHalfPx;     // half-extents in pixels
            uniform vec4 visibleColor;
            uniform vec4 occludedColor;
            uniform float missingValue;

            out vec4 fragColor;

            const int MAX_STEPS = 4096;
            const float DROP_COEFF = 0.87;        // 1 - k where k = 0.13 atmospheric refraction
            const float BIG = 1.0e30;

            void main() {
                int tx = int(gl_FragCoord.x);
                int ty = int(gl_FragCoord.y);
                int w = gridSize.x;
                int h = gridSize.y;
                int cxi = observerCell.x;
                int cyi = observerCell.y;
                int dx = tx - cxi;
                int dy = ty - cyi;

                bool inArea;
                if (areaMode == 0) {
                    float dxf = float(dx);
                    float dyf = float(dy);
                    inArea = (dxf * dxf + dyf * dyf) <= areaHalfPx.x * areaHalfPx.x;
                } else {
                    inArea = abs(float(dx)) <= areaHalfPx.x && abs(float(dy)) <= areaHalfPx.y;
                }
                if (!inArea) { fragColor = vec4(0.0); return; }

                float targetElev = texelFetch(elevTex, ivec2(tx, ty), 0).r;
                if (targetElev == missingValue) { fragColor = vec4(0.0); return; }

                // Observer cell + 2x2 neighborhood are visible by definition. Matches the CPU
                // kernel: covers the observer's pixel for both odd and even grid sizes.
                if ((tx == cxi || tx == cxi + 1) && (ty == cyi || ty == cyi + 1)) {
                    fragColor = visibleColor;
                    return;
                }

                float ve = verticalExaggeration;
                float obsH = observerAlt * ve;
                float earthR = earthRadius;

                float rayDxM = float(dx) * metersPerPixel.x;
                float rayDyM = float(dy) * metersPerPixel.y;
                float totalLength = sqrt(rayDxM * rayDxM + rayDyM * rayDyM);

                float targetH = targetElev * ve;
                float dropTarget = DROP_COEFF * totalLength * totalLength / (2.0 * earthR);
                float targetApparent = atan(targetH - obsH - dropTarget, totalLength);

                int absDx = abs(dx);
                int absDy = abs(dy);
                int stepX = (dx > 0) ? 1 : ((dx < 0) ? -1 : 0);
                int stepY = (dy > 0) ? 1 : ((dy < 0) ? -1 : 0);
                float tDeltaX = (absDx > 0) ? 1.0 / float(absDx) : BIG;
                float tDeltaY = (absDy > 0) ? 1.0 / float(absDy) : BIG;
                float tMaxX = (absDx > 0) ? 0.5 / float(absDx) : BIG;
                float tMaxY = (absDy > 0) ? 0.5 / float(absDy) : BIG;

                int xi = cxi;
                int yi = cyi;
                bool occluded = false;
                for (int i = 0; i < MAX_STEPS; i++) {
                    float tEntry;
                    if (tMaxX < tMaxY) {
                        tEntry = tMaxX;
                        xi += stepX;
                        tMaxX += tDeltaX;
                    } else {
                        tEntry = tMaxY;
                        yi += stepY;
                        tMaxY += tDeltaY;
                    }
                    if (xi == tx && yi == ty) break;

                    float fx = float(cxi) + 0.5 + tEntry * float(dx);
                    float fy = float(cyi) + 0.5 + tEntry * float(dy);
                    int ix = clamp(int(fx), 0, w - 2);
                    int iy = clamp(int(fy), 0, h - 2);
                    float ax = fx - float(ix);
                    float ay = fy - float(iy);
                    float g00 = texelFetch(elevTex, ivec2(ix, iy), 0).r;
                    float g10 = texelFetch(elevTex, ivec2(ix + 1, iy), 0).r;
                    float g01 = texelFetch(elevTex, ivec2(ix, iy + 1), 0).r;
                    float g11 = texelFetch(elevTex, ivec2(ix + 1, iy + 1), 0).r;
                    if (g00 == missingValue || g10 == missingValue ||
                        g01 == missingValue || g11 == missingValue) continue;

                    float cellH = ((1.0 - ay) * ((1.0 - ax) * g00 + ax * g10) +
                                   ay * ((1.0 - ax) * g01 + ax * g11)) * ve;
                    float distM = tEntry * totalLength;
                    float drop = DROP_COEFF * distM * distM / (2.0 * earthR);
                    float apparent = atan(cellH - obsH - drop, distM);
                    if (apparent >= targetApparent) {
                        occluded = true;
                        break;
                    }
                }

                fragColor = occluded ? occludedColor : visibleColor;
            }
        """.trimIndent()
    }
}
