package org.impulsegraph.compiler.passes;

import java.util.Map;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.util.ImpulseAssert;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;

/**
 * Contextual state passed across compiler transformation passes.
 */
public final class CompilerContext {
	private final ImpulseGraphSnapshot snapshot;
	private final CompilerOptions options;
	private final PassTracer tracer;

	public CompilerContext(ImpulseGraphSnapshot snapshot, CompilerOptions options, PassTracer tracer) {
		this.snapshot = snapshot;
		this.options = options != null ? options : CompilerOptions.DEFAULT;
		this.tracer = tracer != null ? tracer : new PassTracer(this.options);
	}

	public ImpulseGraphSnapshot snapshot() {
		return snapshot;
	}

	public CompilerOptions options() {
		return options;
	}

	public PassTracer tracer() {
		return tracer;
	}

	public Map<String, Object> parameters() {
		return options.parameters();
	}

	private static int passBitForName(String name) {
		if (name == null) {
			return 0;
		}
		return switch (name) {
			case "AstNormalizationPass" -> CompilerOptions.OPT_AST_NORMALIZATION;
			case "ConstantFoldingPass" -> CompilerOptions.OPT_CONSTANT_FOLDING;
			case "CelPredicateFlatteningPass" -> CompilerOptions.OPT_CEL_PREDICATE_FLATTENING;
			case "MonotonicHomomorphismPass" -> CompilerOptions.OPT_MONOTONIC_HOMOMORPHISM;
			case "ZoneMapPruningPass" -> CompilerOptions.OPT_ZONE_MAP_PRUNING;
			case "AlgebraicTypeInferencePass" -> CompilerOptions.OPT_ALGEBRAIC_TYPE_INFERENCE;
			case "DirectionSelectionPass" -> CompilerOptions.OPT_DIRECTION_SELECTION;
			case "FilterPushdownPass" -> CompilerOptions.OPT_FILTER_PUSHDOWN;
			case "KernelFusionPass" -> CompilerOptions.OPT_KERNEL_FUSION_2HOP;
			case "InjectiveDeduplicationBypassPass" -> CompilerOptions.OPT_INJECTIVE_DEDUP_BYPASS;
			case "VirtualRelationDecompositionPass" -> CompilerOptions.OPT_VIRTUAL_REL_DECOMP;
			default -> 0;
		};
	}

	public ImpScmNode executePass(CompilerPass pass, ImpScmNode input) {
		int bit = passBitForName(pass.name());
		if (bit != 0) {
			boolean disabled = options.isOptimizationDisabled(bit);
			ImpulseAssert.testcase(500 + Integer.numberOfTrailingZeros(bit), disabled);
			if (disabled) {
				return input;
			}
		}
		long start = System.nanoTime();
		ImpScmNode output = pass.transform(input, this);
		long duration = System.nanoTime() - start;
		tracer.record(pass.name(), input, output, duration);
		return output;
	}
}
