package earth.worldwind.layer.mvt

/**
 * Predicate over an MVT feature's inflated property map. Sealed so the matching set is
 * known up front. Filters are pure data evaluated once per feature off the render thread
 * (in [MvtVectorLayer.toRenderables]); construct via the companion factory methods or the
 * DSL infix helpers (`"kind" eq "motorway"`).
 *
 * Supported: literal equality, set membership, numeric comparison, `has`, boolean combinators.
 * Not supported: expression operators (`case`, `match`, arithmetic), geometry-type filters.
 *
 * Property values arrive as `Any?` because MVT's wire format types them per value:
 *   string → String,  bool → Boolean,  int64/uint64 → Long,  sint64 → Long (zigzagged),
 *   float → Float,    double → Double,  absent → null.
 * Numeric comparisons go through [Number.toDouble] so int/float/double mix correctly.
 */
sealed class MvtFilter {

    /** Evaluate this filter against a feature's inflated property map. */
    abstract fun matches(properties: Map<String, Any?>): Boolean

    /** True when `properties[key] == value`. Type-equality follows Kotlin's `==`. */
    class Eq(val key: String, val value: Any?) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = properties[key] == value
    }

    /** True when `properties[key]` is in [values]. */
    class In(val key: String, val values: Set<Any?>) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = properties[key] in values
    }

    /** Negation of [Eq]. */
    class NotEq(val key: String, val value: Any?) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = properties[key] != value
    }

    /** True when [key] is present in the map (regardless of value, including null). */
    class Has(val key: String) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = key in properties
    }

    /** True when `properties[key].toDouble()` ≤ [threshold]. Non-numeric / null = false. */
    class NumericLte(val key: String, val threshold: Double) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean {
            val v = (properties[key] as? Number)?.toDouble() ?: return false
            return v <= threshold
        }
    }

    /** True when `properties[key].toDouble()` ≥ [threshold]. Non-numeric / null = false. */
    class NumericGte(val key: String, val threshold: Double) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean {
            val v = (properties[key] as? Number)?.toDouble() ?: return false
            return v >= threshold
        }
    }

    /** True when `properties[key].toDouble()` < [threshold]. Non-numeric / null = false. */
    class NumericLt(val key: String, val threshold: Double) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean {
            val v = (properties[key] as? Number)?.toDouble() ?: return false
            return v < threshold
        }
    }

    /** True when `properties[key].toDouble()` > [threshold]. Non-numeric / null = false. */
    class NumericGt(val key: String, val threshold: Double) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean {
            val v = (properties[key] as? Number)?.toDouble() ?: return false
            return v > threshold
        }
    }

    /** Short-circuit conjunction. Empty children = always true. */
    class AllOf(val children: List<MvtFilter>) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean =
            children.all { it.matches(properties) }
    }

    /**
     * Short-circuit disjunction. Empty = always false. Named [AnyOf] because plain `Any`
     * shadows [kotlin.Any] inside the class body and breaks every `Any?` reference. [AllOf]
     * mirrors the naming.
     */
    class AnyOf(val children: List<MvtFilter>) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean =
            children.any { it.matches(properties) }
    }

    /** Negation. */
    class Not(val child: MvtFilter) : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = !child.matches(properties)
    }

    /** Always-true filter. Useful as a placeholder when a rule needs no filter. */
    object Always : MvtFilter() {
        override fun matches(properties: Map<String, Any?>): Boolean = true
    }

    companion object {
        fun eq(key: String, value: Any?): MvtFilter = Eq(key, value)
        fun notEq(key: String, value: Any?): MvtFilter = NotEq(key, value)
        fun `in`(key: String, vararg values: Any?): MvtFilter = In(key, values.toSet())
        fun has(key: String): MvtFilter = Has(key)
        fun numericLte(key: String, threshold: Double): MvtFilter = NumericLte(key, threshold)
        fun numericGte(key: String, threshold: Double): MvtFilter = NumericGte(key, threshold)
        fun numericLt(key: String, threshold: Double): MvtFilter = NumericLt(key, threshold)
        fun numericGt(key: String, threshold: Double): MvtFilter = NumericGt(key, threshold)
        fun all(vararg children: MvtFilter): MvtFilter = AllOf(children.toList())
        fun any(vararg children: MvtFilter): MvtFilter = AnyOf(children.toList())
        fun not(child: MvtFilter): MvtFilter = Not(child)
    }
}

// DSL infix helpers for style files: `"kind" eq "motorway"`, `"kind" isIn setOf(...)`, etc.
infix fun String.eq(value: Any?): MvtFilter = MvtFilter.Eq(this, value)
infix fun String.notEq(value: Any?): MvtFilter = MvtFilter.NotEq(this, value)
infix fun String.isIn(values: Set<Any?>): MvtFilter = MvtFilter.In(this, values)
