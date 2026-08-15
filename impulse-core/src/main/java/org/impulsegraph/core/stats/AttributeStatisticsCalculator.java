package org.impulsegraph.core.stats;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.Set;

/**
 * High-performance off-heap calculator for attribute column zone maps, value distributions, and monotonicity.
 */
public class AttributeStatisticsCalculator {

    public static AttributeStatistics calculateInt32(String name, MemorySegment segment, int count) {
        if (segment == null || segment.equals(MemorySegment.NULL) || count <= 0) {
            return AttributeStatistics.empty(name);
        }

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        int nullCount = 0;
        Set<Integer> distinct = new HashSet<>();
        boolean isStrictInc = true;
        boolean isWeakInc = true;
        boolean isStrictDec = true;
        boolean isWeakDec = true;
        boolean isConstant = true;

        int prev = 0;
        boolean first = true;

        for (int i = 0; i < count; i++) {
            int val = segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            if (val == Integer.MIN_VALUE) { // Sentinel null representation
                nullCount++;
                continue;
            }

            if (val < min) min = val;
            if (val > max) max = val;
            if (distinct.size() < 10_000) distinct.add(val);

            if (first) {
                prev = val;
                first = false;
            } else {
                if (val != prev) isConstant = false;
                if (val <= prev) isStrictInc = false;
                if (val < prev) isWeakInc = false;
                if (val >= prev) isStrictDec = false;
                if (val > prev) isWeakDec = false;
                prev = val;
            }
        }

        Monotonicity mono;
        if (isConstant) mono = Monotonicity.MONO_CONSTANT;
        else if (isStrictInc) mono = Monotonicity.MONO_STRICT_INC;
        else if (isWeakInc) mono = Monotonicity.MONO_WEAK_INC;
        else if (isStrictDec) mono = Monotonicity.MONO_STRICT_DEC;
        else if (isWeakDec) mono = Monotonicity.MONO_WEAK_DEC;
        else mono = Monotonicity.MONO_NONE;

        return new AttributeStatistics(
                name,
                min == Long.MAX_VALUE ? 0 : min,
                max == Long.MIN_VALUE ? 0 : max,
                min == Long.MAX_VALUE ? 0.0 : (double) min,
                max == Long.MIN_VALUE ? 0.0 : (double) max,
                "",
                "",
                nullCount,
                distinct.size(),
                mono,
                nullCount > 0
        );
    }

    public static AttributeStatistics calculateFloat64(String name, MemorySegment segment, int count) {
        if (segment == null || segment.equals(MemorySegment.NULL) || count <= 0) {
            return AttributeStatistics.empty(name);
        }

        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int nullCount = 0;
        Set<Double> distinct = new HashSet<>();
        boolean isStrictInc = true;
        boolean isWeakInc = true;
        boolean isStrictDec = true;
        boolean isWeakDec = true;
        boolean isConstant = true;

        double prev = 0.0;
        boolean first = true;

        for (int i = 0; i < count; i++) {
            double val = segment.getAtIndex(ValueLayout.JAVA_DOUBLE_UNALIGNED, i);
            if (Double.isNaN(val)) {
                nullCount++;
                continue;
            }

            if (val < min) min = val;
            if (val > max) max = val;
            if (distinct.size() < 10_000) distinct.add(val);

            if (first) {
                prev = val;
                first = false;
            } else {
                if (val != prev) isConstant = false;
                if (val <= prev) isStrictInc = false;
                if (val < prev) isWeakInc = false;
                if (val >= prev) isStrictDec = false;
                if (val > prev) isWeakDec = false;
                prev = val;
            }
        }

        Monotonicity mono;
        if (isConstant) mono = Monotonicity.MONO_CONSTANT;
        else if (isStrictInc) mono = Monotonicity.MONO_STRICT_INC;
        else if (isWeakInc) mono = Monotonicity.MONO_WEAK_INC;
        else if (isStrictDec) mono = Monotonicity.MONO_STRICT_DEC;
        else if (isWeakDec) mono = Monotonicity.MONO_WEAK_DEC;
        else mono = Monotonicity.MONO_NONE;

        return new AttributeStatistics(
                name,
                (long) (min == Double.MAX_VALUE ? 0 : min),
                (long) (max == -Double.MAX_VALUE ? 0 : max),
                min == Double.MAX_VALUE ? 0.0 : min,
                max == -Double.MAX_VALUE ? 0.0 : max,
                "",
                "",
                nullCount,
                distinct.size(),
                mono,
                nullCount > 0
        );
    }
}
