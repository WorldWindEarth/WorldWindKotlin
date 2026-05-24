package earth.worldwind.layer.mvt

import earth.worldwind.geom.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MvtCurvedTextPlacerTest {

    private fun line(vararg pts: Pair<Double, Double>): List<Vec2> =
        pts.map { (x, y) -> Vec2(x, y) }

    private fun uniformWidths(text: String, w: Float = 10f): FloatArray =
        FloatArray(text.length) { w }

    @Test fun emptyInputProducesEmptyOutput() {
        assertEquals(0, MvtCurvedTextPlacer.place(emptyList(), "Hi", uniformWidths("Hi"), 20f).size)
        assertEquals(0, MvtCurvedTextPlacer.place(line(0.0 to 0.0), "Hi", uniformWidths("Hi"), 20f).size)
        assertEquals(0, MvtCurvedTextPlacer.place(line(0.0 to 0.0, 100.0 to 0.0), "", FloatArray(0), 0f).size)
    }

    @Test fun shortLineSkippedWhenTextDoesntFit() {
        // Line is 30 px long; text is 100 px wide — no fit.
        val runs = MvtCurvedTextPlacer.place(
            line(0.0 to 0.0, 30.0 to 0.0),
            text = "ABCDEFGHIJ",
            charWidths = uniformWidths("ABCDEFGHIJ"),
            textWidth = 100f,
        )
        assertEquals(0, runs.size)
    }

    @Test fun straightLineProducesGlyphsAtUniformPositionsAndZeroAngle() {
        // Horizontal line, 200 px long. Text "ABC" at 30 px wide → 1 repeat centred.
        val runs = MvtCurvedTextPlacer.place(
            polyline = line(0.0 to 50.0, 200.0 to 50.0),
            text = "ABC",
            charWidths = uniformWidths("ABC"),
            textWidth = 30f,
        )
        assertEquals(1, runs.size)
        val run = runs[0]
        assertEquals(3, run.glyphs.size)
        for (g in run.glyphs) {
            assertEquals(0f, g.angleRad, absoluteTolerance = 1e-4f) // straight east
            assertEquals(50f, g.y, absoluteTolerance = 1e-4f)
        }
        // First glyph centre is at (textCentreX - textWidth/2 + glyphWidth/2):
        //   centreX = 100 (line midpoint), textWidth/2 = 15, glyphWidth/2 = 5 → 90.
        assertEquals(90f, run.glyphs[0].x, absoluteTolerance = 1e-3f)
        assertEquals(100f, run.glyphs[1].x, absoluteTolerance = 1e-3f)
        assertEquals(110f, run.glyphs[2].x, absoluteTolerance = 1e-3f)
    }

    @Test fun cornerInPolylineProducesAngleDiscontinuity() {
        // Right-angle line: horizontal then vertical. Glyph in the first half rotates
        // 0 (east); glyph past the corner rotates π/2 (south).
        val runs = MvtCurvedTextPlacer.place(
            polyline = line(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0),
            text = "AB",
            charWidths = uniformWidths("AB"),
            textWidth = 20f,
        )
        assertEquals(1, runs.size)
        val (a, b) = runs[0].glyphs
        // Both should be near zero angle if centered around the corner; we test that A is
        // east-pointing and B is south-pointing (or close to it).
        assertTrue(abs(a.angleRad) < 0.1f || abs(a.angleRad - 1.5708f) < 0.1f,
            "A angle ${a.angleRad} should be ~0 or ~π/2")
        assertTrue(abs(b.angleRad) < 0.1f || abs(b.angleRad - 1.5708f) < 0.1f,
            "B angle ${b.angleRad} should be ~0 or ~π/2")
    }

    @Test fun longLineProducesMultipleRepeats() {
        // 1000 px line; text 30 px wide; repeatGap 100 px → roughly 7-8 repeats.
        val runs = MvtCurvedTextPlacer.place(
            polyline = line(0.0 to 0.0, 1000.0 to 0.0),
            text = "ABC",
            charWidths = uniformWidths("ABC"),
            textWidth = 30f,
            repeatGapPx = 100f,
        )
        assertTrue(runs.size >= 6, "expected ≥6 repeats over 1000px with 30+100 cycle, got ${runs.size}")
        // Each run's glyphs sum to (textWidth + last-glyph-half), and subsequent runs start
        // at the previous end + 100 px. So the x-spacing between consecutive run centroids
        // should be ~130 px.
        for (i in 1 until runs.size) {
            val gap = runs[i].centerX - runs[i - 1].centerX
            assertTrue(abs(gap - 130f) < 5f, "gap[$i] = $gap, expected ~130")
        }
    }

    @Test fun rotatedGlyphCornersProducesAxisAlignedAtZeroAngle() {
        val out = FloatArray(8)
        MvtCurvedTextPlacer.rotatedGlyphCorners(
            anchorX = 100f, anchorY = 200f, angleRad = 0f,
            glyphWidth = 20f, glyphHeight = 10f, out = out,
        )
        // TL = (90, 195), TR = (110, 195), BR = (110, 205), BL = (90, 205).
        assertEquals(90f, out[0]); assertEquals(195f, out[1])
        assertEquals(110f, out[2]); assertEquals(195f, out[3])
        assertEquals(110f, out[4]); assertEquals(205f, out[5])
        assertEquals(90f, out[6]); assertEquals(205f, out[7])
    }
}
