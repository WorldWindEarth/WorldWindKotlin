package earth.worldwind.layer.mvt

import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import earth.worldwind.render.Color

class MvtTextWrapTest {

    private fun font(size: Int = 14): Font = Font("SansSerif", FontWeight.BOLD, size)

    @Test fun shortTextDoesntWrap() {
        // "Vienna" at size=14 should be < 200 px on any reasonable font.
        val wrapped = MvtStyleRule.PaintSpec.wrapText("Vienna", font(), maxWidthPx = 200f)
        assertEquals("Vienna", wrapped)
    }

    @Test fun longTextWrapsAtWordBoundary() {
        // Force wrapping with a narrow max-width. Use a long city name.
        val wrapped = MvtStyleRule.PaintSpec.wrapText(
            "San Francisco Bay Area",
            font(),
            maxWidthPx = 60f,
        )
        // Result must contain at least one newline. Individual lines may exceed maxWidth
        // when a single word is wider than the budget (Mapbox doesn't split words either).
        assertTrue('\n' in wrapped, "expected wrap to introduce a newline, got '$wrapped'")
        // Multi-word lines must respect the limit. We check by looking for any line that
        // contains a space and exceeding the limit, which would indicate the wrapper failed
        // to break at the available boundary.
        for (line in wrapped.split('\n')) {
            if (' ' in line) {
                assertTrue(
                    font().measureText(line) <= 60f + 1f,
                    "multi-word line '$line' exceeds maxWidth (${font().measureText(line)} > 60)",
                )
            }
        }
    }

    @Test fun singleLongWordStaysOnOneLine() {
        // Greedy word wrap doesn't break words. A 20-char word at narrow max-width
        // stays on its own line even though it overflows.
        val wrapped = MvtStyleRule.PaintSpec.wrapText("Unsplittablewordhere", font(), 30f)
        assertEquals("Unsplittablewordhere", wrapped) // no newline inserted
    }

    @Test fun zeroOrNegativeMaxWidthIsIdentity() {
        val text = "Some text with spaces"
        assertEquals(text, MvtStyleRule.PaintSpec.wrapText(text, font(), 0f))
        assertEquals(text, MvtStyleRule.PaintSpec.wrapText(text, font(), -10f))
    }

    @Test fun emptyTextStaysEmpty() {
        assertEquals("", MvtStyleRule.PaintSpec.wrapText("", font(), 100f))
    }

    @Test fun buildTextWrapsWhenTextMaxWidthIsSet() {
        val paint = MvtStyleRule.PaintSpec(
            textField = "name",
            textColor = MvtExpression.Literal(Color(1f, 1f, 1f)),
            textSize = MvtExpression.Literal(14f),
            textMaxWidth = MvtExpression.Literal(3f),  // 3 em × 14 px = 42 px
            fontWeight = FontWeight.BOLD,
        )
        val spec = paint.buildText(zoom = 12, mapOf("name" to "San Francisco Bay Area"))!!
        assertTrue('\n' in spec.text, "expected multi-line text, got '${spec.text}'")
    }
}
