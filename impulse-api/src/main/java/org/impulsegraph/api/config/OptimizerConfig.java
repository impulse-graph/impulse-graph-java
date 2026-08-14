package org.impulsegraph.api.config;

/**
 * Centralized Query Optimizer Configuration and Heuristic Threshold Constants.
 * <p>
 * Controls algebraic query compilation, vectorization thresholds, multi-hop kernel fusion,
 * and experimental optimization passes.
 * </p>
 */
public final class OptimizerConfig {

    private OptimizerConfig() {}

    /**
     * Minimum node out/in-degree threshold required to activate 512-bit SIMD Vector API
     * fused predicate evaluation during CSR/CSC traversal. Below this threshold, scalar
     * loop unrolling is used to avoid SIMD vector mask setup overhead.
     */
    public static final int SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD =
            Integer.getInteger("impulse.optimizer.simd.min_degree", 64);

    /**
     * Maximum intermediate relation multiplicity (InDegree / OutDegree) threshold for
     * 2-Hop Kernel Fusion ({@code OP_CSR_WALK_2HOP}). When intermediate path multiplicity is
     * below or equal to this threshold (e.g. 1.0 to 1.5), 2-hop traversal is fused directly
     * in CPU registers without intermediate bitset materialization.
     */
    public static final double FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD =
            Double.parseDouble(System.getProperty("impulse.optimizer.2hop.max_multiplicity", "1.5"));

    /**
     * Master toggle for all experimental query optimizations.
     */
    public static final boolean ENABLE_EXPERIMENTAL_OPTIMIZATIONS =
            Boolean.getBoolean("impulse.optimizer.experimental");

    /**
     * Feature toggle for 2-Hop Kernel Fusion ({@code OP_CSR_WALK_2HOP}).
     * Requires either {@code impulse.optimizer.experimental.2hop=true} or
     * {@code impulse.optimizer.experimental=true}.
     */
    public static final boolean ENABLE_EXPERIMENTAL_2HOP_FUSION =
            Boolean.getBoolean("impulse.optimizer.experimental.2hop") || ENABLE_EXPERIMENTAL_OPTIMIZATIONS;

    /**
     * Default hardware vector bit width (e.g. 512-bit AVX-512, 256-bit AVX2, 128-bit ARM Neon).
     */
    public static final int PREFERRED_VECTOR_BIT_WIDTH = 512;
}
