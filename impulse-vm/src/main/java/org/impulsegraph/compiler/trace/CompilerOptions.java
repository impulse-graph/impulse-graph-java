package org.impulsegraph.compiler.trace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.impulsegraph.api.util.ImpulseTestControl;

/**
 * Configuration options, 14-pass optimization bitmask (SQLite
 * SQLITE_TESTCTRL_OPTIMIZATIONS pattern), and parameter bindings for the
 * ImpScheme compiler pipeline.
 */
public record CompilerOptions(boolean enableTracing, boolean enableStage1Optimization, boolean enableStage2Optimization,
		boolean enableConstantFolding, boolean enableDirectionSelection, boolean enableFilterPushdown,
		boolean enableExperimental2HopFusion, int disabledPassesMask, PassTraceListener traceListener,
		Map<String, Object> parameters) {

	// 14-Pass Optimization Bitmask Constants (SQLite db->dbOptFlags pattern)
	public static final int OPT_AST_NORMALIZATION = 1 << 0;
	public static final int OPT_CONSTANT_FOLDING = 1 << 1;
	public static final int OPT_CEL_PREDICATE_FLATTENING = 1 << 2;
	public static final int OPT_MONOTONIC_HOMOMORPHISM = 1 << 3;
	public static final int OPT_ZONE_MAP_PRUNING = 1 << 4;
	public static final int OPT_ALGEBRAIC_TYPE_INFERENCE = 1 << 5;
	public static final int OPT_DIRECTION_SELECTION = 1 << 6;
	public static final int OPT_FILTER_PUSHDOWN = 1 << 7;
	public static final int OPT_KERNEL_FUSION_2HOP = 1 << 8;
	public static final int OPT_INJECTIVE_DEDUP_BYPASS = 1 << 9;
	public static final int OPT_VIRTUAL_REL_DECOMP = 1 << 10;
	public static final int OPT_CEL_AST_OPTIMIZER = 1 << 11;
	public static final int OPT_METHOD_HANDLE_JIT = 1 << 12;
	public static final int OPT_SIMD_VECTOR_SPECIES = 1 << 13;

	public static final int OPT_ALL_ENABLED = 0x0000;
	public static final int OPT_ALL_DISABLED = 0x3FFF;

	public static final CompilerOptions DEFAULT = new CompilerOptions(Boolean.getBoolean("impulse.compiler.trace"),
			true, true, true, true, true, org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION,
			0, PassTraceListener.SYSTEM_OUT, Map.of());

	public CompilerOptions(boolean enableTracing, boolean enableStage1Optimization, boolean enableStage2Optimization,
			boolean enableConstantFolding, boolean enableDirectionSelection, boolean enableFilterPushdown,
			boolean enableExperimental2HopFusion, PassTraceListener traceListener, Map<String, Object> parameters) {
		this(enableTracing, enableStage1Optimization, enableStage2Optimization, enableConstantFolding,
				enableDirectionSelection, enableFilterPushdown, enableExperimental2HopFusion, 0, traceListener,
				parameters);
	}

	public CompilerOptions(boolean enableTracing, boolean enableStage1Optimization, boolean enableStage2Optimization,
			boolean enableConstantFolding, boolean enableDirectionSelection, boolean enableFilterPushdown,
			PassTraceListener traceListener) {
		this(enableTracing, enableStage1Optimization, enableStage2Optimization, enableConstantFolding,
				enableDirectionSelection, enableFilterPushdown,
				org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION, 0, traceListener,
				Map.of());
	}

	/**
	 * Effective bitmask combining this instance's {@link #disabledPassesMask} with
	 * the global {@link ImpulseTestControl#getDisabledOptimizationsMask()}.
	 */
	public int effectiveDisabledPassesMask() {
		int mask = disabledPassesMask | ImpulseTestControl.getDisabledOptimizationsMask();
		if (!enableStage1Optimization) {
			mask |= OPT_AST_NORMALIZATION | OPT_CONSTANT_FOLDING | OPT_CEL_PREDICATE_FLATTENING
					| OPT_MONOTONIC_HOMOMORPHISM | OPT_ZONE_MAP_PRUNING | OPT_ALGEBRAIC_TYPE_INFERENCE;
		}
		if (!enableStage2Optimization) {
			mask |= OPT_DIRECTION_SELECTION | OPT_FILTER_PUSHDOWN | OPT_KERNEL_FUSION_2HOP | OPT_INJECTIVE_DEDUP_BYPASS
					| OPT_VIRTUAL_REL_DECOMP;
		}
		if (!enableConstantFolding) {
			mask |= OPT_CONSTANT_FOLDING;
		}
		if (!enableDirectionSelection) {
			mask |= OPT_DIRECTION_SELECTION;
		}
		if (!enableFilterPushdown) {
			mask |= OPT_FILTER_PUSHDOWN;
		}
		if (!enableExperimental2HopFusion) {
			mask |= OPT_KERNEL_FUSION_2HOP;
		}
		return mask;
	}

	/**
	 * SQLite {@code OptimizationDisabled(db, mask)} equivalent.
	 */
	public boolean isOptimizationDisabled(int optFlag) {
		return (effectiveDisabledPassesMask() & optFlag) != 0;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private boolean enableTracing = Boolean.getBoolean("impulse.compiler.trace");
		private boolean enableStage1Optimization = true;
		private boolean enableStage2Optimization = true;
		private boolean enableConstantFolding = true;
		private boolean enableDirectionSelection = true;
		private boolean enableFilterPushdown = true;
		private boolean enableExperimental2HopFusion = org.impulsegraph.api.config.OptimizerConfig.ENABLE_EXPERIMENTAL_2HOP_FUSION;
		private int disabledPassesMask = 0;
		private PassTraceListener traceListener = PassTraceListener.SYSTEM_OUT;
		private final Map<String, Object> parameters = new HashMap<>();

		public Builder withTracing(boolean enable) {
			this.enableTracing = enable;
			return this;
		}

		public Builder withExperimental2HopFusion(boolean enable) {
			this.enableExperimental2HopFusion = enable;
			return this;
		}

		public Builder withStage1Optimization(boolean enable) {
			this.enableStage1Optimization = enable;
			return this;
		}

		public Builder withStage2Optimization(boolean enable) {
			this.enableStage2Optimization = enable;
			return this;
		}

		public Builder withConstantFolding(boolean enable) {
			this.enableConstantFolding = enable;
			return this;
		}

		public Builder withDirectionSelection(boolean enable) {
			this.enableDirectionSelection = enable;
			return this;
		}

		public Builder withFilterPushdown(boolean enable) {
			this.enableFilterPushdown = enable;
			return this;
		}

		public Builder withDisabledPassesMask(int mask) {
			this.disabledPassesMask = mask;
			if ((mask & OPT_CONSTANT_FOLDING) != 0) {
				this.enableConstantFolding = false;
			}
			if ((mask & OPT_DIRECTION_SELECTION) != 0) {
				this.enableDirectionSelection = false;
			}
			if ((mask & OPT_FILTER_PUSHDOWN) != 0) {
				this.enableFilterPushdown = false;
			}
			if ((mask & OPT_KERNEL_FUSION_2HOP) != 0) {
				this.enableExperimental2HopFusion = false;
			}
			return this;
		}

		public Builder withTraceListener(PassTraceListener listener) {
			this.traceListener = listener != null ? listener : PassTraceListener.SYSTEM_OUT;
			return this;
		}

		public Builder withParameter(String name, Object value) {
			if (name != null) {
				this.parameters.put(name, value);
			}
			return this;
		}

		public Builder withParameters(Map<String, Object> map) {
			if (map != null) {
				this.parameters.putAll(map);
			}
			return this;
		}

		public CompilerOptions build() {
			return new CompilerOptions(enableTracing, enableStage1Optimization, enableStage2Optimization,
					enableConstantFolding, enableDirectionSelection, enableFilterPushdown, enableExperimental2HopFusion,
					disabledPassesMask, traceListener, Collections.unmodifiableMap(new HashMap<>(parameters)));
		}
	}
}
