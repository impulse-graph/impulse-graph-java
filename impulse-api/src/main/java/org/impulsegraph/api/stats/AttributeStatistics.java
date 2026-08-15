package org.impulsegraph.api.stats;

/**
 * Immutable statistics and zone map metadata for a single node or edge attribute column.
 */
public record AttributeStatistics(
        String name,
        long minIntVal,
        long maxIntVal,
        double minFloatVal,
        double maxFloatVal,
        String minStrVal,
        String maxStrVal,
        int nullCount,
        int distinctCount,
        Monotonicity monotonicity,
        boolean hasNulls
) {
    public enum Monotonicity {
        MONO_NONE,
        MONO_STRICT_INC,
        MONO_WEAK_INC,
        MONO_STRICT_DEC,
        MONO_WEAK_DEC,
        MONO_CONSTANT
    }

    public static AttributeStatistics empty(String name) {
        return new AttributeStatistics(
                name,
                Long.MAX_VALUE,
                Long.MIN_VALUE,
                Double.MAX_VALUE,
                -Double.MAX_VALUE,
                "",
                "",
                0,
                0,
                Monotonicity.MONO_NONE,
                false
        );
    }
}
