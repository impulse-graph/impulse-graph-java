package org.impulsegraph.compiler;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.util.ImpulseTestControl;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.registry.QueryCompilerEngine;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite {@code sqlite3TestControl(SQLITE_TESTCTRL_OPTIMIZATIONS, mask)} style
 * 14-Pass Differential Optimizer &amp; MethodHandle JIT vs Interpreter
 * Verification Suite.
 */
class OptimizerDifferentialFuzzTest {

	private Arena arena;
	private ImpulseGraphSnapshot snapshot;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		arena = Arena.ofConfined();
		snapshot = buildTestSnapshot(arena);
	}

	@AfterEach
	void tearDown() {
		if (arena != null && arena.scope().isAlive()) {
			arena.close();
		}
		ImpulseTestControl.resetAll();
	}

	private static ImpulseGraphSnapshot buildTestSnapshot(Arena arena) {
		// rel0 (6 nodes): 0->[1,2], 1->[2,3], 2->[3,4], 3->[4,5], 4->[5], 5->[0]
		int[] r0Off = {0, 2, 4, 6, 8, 9, 10};
		int[] r0Col = {1, 2, 2, 3, 3, 4, 4, 5, 5, 0};
		// CSC for rel0: 0<-[5], 1<-[0], 2<-[0,1], 3<-[1,2], 4<-[2,3], 5<-[3,4]
		int[] r0CscOff = {0, 1, 2, 4, 6, 8, 10};
		int[] r0CscSrc = {5, 0, 0, 1, 1, 2, 2, 3, 3, 4};

		RelationSnapshot rel0 = createRelationWithCsc(arena, 6, r0Off, r0Col, r0CscOff, r0CscSrc);

		// rel1 (6 nodes, injective): 0->[1], 1->[2], 2->[3], 3->[4], 4->[5], 5->[0]
		int[] r1Off = {0, 1, 2, 3, 4, 5, 6};
		int[] r1Col = {1, 2, 3, 4, 5, 0};
		int[] r1CscOff = {0, 1, 2, 3, 4, 5, 6};
		int[] r1CscSrc = {5, 0, 1, 2, 3, 4};

		RelationSnapshot rel1 = createRelationWithCsc(arena, 6, r1Off, r1Col, r1CscOff, r1CscSrc);

		ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel0", rel0, "rel1", rel1));
		AttributeStatistics ageStats = new AttributeStatistics("age", 18, 114, 18.0, 114.0, "", "", 0, 96,
				AttributeStatistics.Monotonicity.MONO_NONE, false);
		snap.getGraphStatistics().putAttributeStatistics("age", ageStats);
		return snap;
	}

	private static RelationSnapshot createRelationWithCsc(Arena arena, int nodeCount, int[] csrOff, int[] csrCol,
			int[] cscOff, int[] cscSrc) {
		MemorySegment rowSeg = arena.allocate((long) csrOff.length * 4L, 4);
		for (int i = 0; i < csrOff.length; i++) {
			rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, csrOff[i]);
		}
		MemorySegment colSeg = arena.allocate((long) csrCol.length * 4L, 4);
		for (int i = 0; i < csrCol.length; i++) {
			colSeg.setAtIndex(ValueLayout.JAVA_INT, i, csrCol[i]);
		}
		MemorySegment cscOffSeg = arena.allocate((long) cscOff.length * 4L, 4);
		for (int i = 0; i < cscOff.length; i++) {
			cscOffSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscOff[i]);
		}
		MemorySegment cscSrcSeg = arena.allocate((long) cscSrc.length * 4L, 4);
		for (int i = 0; i < cscSrc.length; i++) {
			cscSrcSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscSrc[i]);
		}
		return new RelationSnapshot(arena, nodeCount, csrCol.length, rowSeg, colSeg, cscOffSeg, cscSrcSeg);
	}

	private ImpScmNode runFull14PassPipeline(ScmProgram rawAst, int disabledMask) {
		CompilerOptions opts = CompilerOptions.builder().withDisabledPassesMask(disabledMask).build();
		PassTracer tracer = new PassTracer(opts);
		CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

		ImpScmNode ast = rawAst;
		// Mandatory Pre-Bind Validator
		ast = ctx.executePass(PreBindValidator.INSTANCE, ast);
		// Optional Stage 1 & Stage 2 Passes (Bits 0..13)
		ast = ctx.executePass(AstNormalizationPass.INSTANCE, ast);
		ast = ctx.executePass(ConstantFoldingPass.INSTANCE, ast);
		ast = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
		ast = ctx.executePass(CelPredicateFlatteningPass.INSTANCE, ast);
		ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
		ast = ctx.executePass(MonotonicHomomorphismPass.INSTANCE, ast);
		ast = ctx.executePass(ZoneMapPruningPass.INSTANCE, ast);
		// Mandatory Bind-Time Validator
		ast = ctx.executePass(BindTimeValidator.INSTANCE, ast);
		// Optional Stage 2 Passes
		ast = ctx.executePass(DirectionSelectionPass.INSTANCE, ast);
		ast = ctx.executePass(FilterPushdownPass.INSTANCE, ast);
		ast = ctx.executePass(KernelFusionPass.INSTANCE, ast);
		ast = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);
		ast = ctx.executePass(VirtualRelationDecompositionPass.INSTANCE, ast);
		// Mandatory Physical Binding & Register Allocation
		ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);
		ast = ctx.executePass(RegisterAllocationPass.INSTANCE, ast);
		return ast;
	}

	private BitSet executeAstToBitSet(ImpScmNode compiledAst, int seedNode) {
		var emitted = ImpOpsBytecodeEmitter.emit(compiledAst, snapshot, arena);
		Object res = ImpulseVmInterpreter.execute(emitted.programSegment(), emitted.instructionCount(), snapshot,
				seedNode, arena);
		BitSet out = new BitSet();
		if (res instanceof ImpulseBitSet ibs) {
			for (int i = 0; i < 6; i++) {
				if (ibs.get(i)) {
					out.set(i);
				}
			}
		}
		return out;
	}

	@Test
	@DisplayName("14-Pass Bitmask Contract: 14 distinct OPT_* bit flags (0x0001..0x2000) and 0x3FFF mask")
	void testPassFlagBitmaskContract() {
		int[] flags = {CompilerOptions.OPT_AST_NORMALIZATION, CompilerOptions.OPT_CONSTANT_FOLDING,
				CompilerOptions.OPT_CEL_PREDICATE_FLATTENING, CompilerOptions.OPT_MONOTONIC_HOMOMORPHISM,
				CompilerOptions.OPT_ZONE_MAP_PRUNING, CompilerOptions.OPT_ALGEBRAIC_TYPE_INFERENCE,
				CompilerOptions.OPT_DIRECTION_SELECTION, CompilerOptions.OPT_FILTER_PUSHDOWN,
				CompilerOptions.OPT_KERNEL_FUSION_2HOP, CompilerOptions.OPT_INJECTIVE_DEDUP_BYPASS,
				CompilerOptions.OPT_VIRTUAL_REL_DECOMP, CompilerOptions.OPT_CEL_AST_OPTIMIZER,
				CompilerOptions.OPT_METHOD_HANDLE_JIT, CompilerOptions.OPT_SIMD_VECTOR_SPECIES};
		assertEquals(14, flags.length);
		int combined = 0;
		for (int i = 0; i < flags.length; i++) {
			assertEquals(1 << i, flags[i]);
			combined |= flags[i];
		}
		assertEquals(CompilerOptions.OPT_ALL_DISABLED, combined);
		assertEquals(0x3FFF, combined);

		ImpulseTestControl.setDisabledOptimizationsMask(0x1555);
		assertTrue((CompilerOptions.DEFAULT.effectiveDisabledPassesMask() & 0x1555) == 0x1555);
		ImpulseTestControl.setDisabledOptimizationsMask(0);
	}

	@Test
	@DisplayName("Differential Optimizer Oracle: 0x3FFF (all OFF) vs 0x0000 (all ON) vs 28 single-bit flips & random masks")
	void testDifferentialOptimizerParityAcrossPassBitmasks() {
		List<ScmProgram> queries = List.of(
				// Q1: 1-hop forward walk
				ScmProgram.of(ScmWalk.forward("rel0"), ScmCollect.bitset()),
				// Q2: 2-hop forward walk (triggers KernelFusionPass when enabled)
				ScmProgram.of(ScmWalk.forward("rel0"), ScmWalk.forward("rel0"), ScmCollect.bitset()),
				// Q3: 2-hop mixed relation walk (rel0 -> rel1)
				ScmProgram.of(ScmWalk.forward("rel0"), ScmWalk.forward("rel1"), ScmCollect.bitset()),
				// Q4: Forward then reverse walk (rel0 -> rev(rel1))
				ScmProgram.of(ScmWalk.forward("rel0"), ScmWalk.reverse("rel1"), ScmCollect.bitset()),
				// Q5: Injective walk with distinct collect (triggers
				// InjectiveDeduplicationBypassPass)
				ScmProgram.of(ScmWalk.forward("rel1"), ScmCollect.distinct()),
				// Q6: 3-hop chain (rel0 -> rel1 -> rel0)
				ScmProgram.of(ScmWalk.forward("rel0"), ScmWalk.forward("rel1"), ScmWalk.forward("rel0"),
						ScmCollect.bitset()));

		int[] masksToTest = new int[2 + 14 + 14 + 16];
		int idx = 0;
		masksToTest[idx++] = CompilerOptions.OPT_ALL_DISABLED; // 0x3FFF (all OFF)
		masksToTest[idx++] = CompilerOptions.OPT_ALL_ENABLED; // 0x0000 (all ON)
		for (int bit = 0; bit < 14; bit++) {
			masksToTest[idx++] = (1 << bit); // single pass disabled
			masksToTest[idx++] = CompilerOptions.OPT_ALL_DISABLED ^ (1 << bit); // single pass enabled
		}
		Random rng = new Random(0x5117E_2026L);
		for (int r = 0; r < 16; r++) {
			masksToTest[idx++] = rng.nextInt(CompilerOptions.OPT_ALL_DISABLED + 1);
		}

		for (int qIdx = 0; qIdx < queries.size(); qIdx++) {
			ScmProgram query = queries.get(qIdx);
			ImpScmNode unoptAst = runFull14PassPipeline(query, CompilerOptions.OPT_ALL_DISABLED);

			for (int seed = 0; seed < 6; seed++) {
				BitSet oracleResult = executeAstToBitSet(unoptAst, seed);

				for (int mask : masksToTest) {
					ImpScmNode candidateAst = runFull14PassPipeline(query, mask);
					BitSet actualResult = executeAstToBitSet(candidateAst, seed);
					assertEquals(oracleResult, actualResult, "Query Q" + (qIdx + 1) + " seed=" + seed
							+ " diverged under pass mask 0x" + Integer.toHexString(mask));
				}
			}
		}
	}

	@Test
	@DisplayName("MethodHandle JIT vs Interpreter Differential Execution Parity (OPT_METHOD_HANDLE_JIT ON vs OFF)")
	void testMethodHandleJitVsInterpreterDifferentialParity() {
		QueryCompilerEngine engine = new QueryCompilerEngine();
		List<ScmProgram> queries = List.of(ScmProgram.of(ScmWalk.forward("rel0"), ScmCollect.bitset()),
				ScmProgram.of(ScmWalk.forward("rel0"), ScmWalk.forward("rel1"), ScmCollect.bitset()),
				ScmProgram.of(ScmWalk.forward("rel1"), ScmWalk.reverse("rel0"), ScmCollect.bitset()));

		for (int qIdx = 0; qIdx < queries.size(); qIdx++) {
			QueryObject qObj = engine.compileStage1("q_" + qIdx, "test_query_" + qIdx, queries.get(qIdx),
					CompilerOptions.DEFAULT);
			CompiledQuery compiled = engine.compileStage2(qObj, snapshot, arena, CompilerOptions.DEFAULT);

			for (int seed = 0; seed < 6; seed++) {
				// 1. Execute via MethodHandle JIT
				ImpulseTestControl.setDisabledOptimizationsMask(0);
				Object jitRes = compiled.execute(snapshot, seed, arena);

				// 2. Execute via Interpreter fallback (OPT_METHOD_HANDLE_JIT disabled)
				ImpulseTestControl.setDisabledOptimizationsMask(CompilerOptions.OPT_METHOD_HANDLE_JIT);
				Object interpRes = compiled.execute(snapshot, seed, arena);
				ImpulseTestControl.setDisabledOptimizationsMask(0);

				assertInstanceOf(ImpulseBitSet.class, jitRes);
				assertInstanceOf(ImpulseBitSet.class, interpRes);
				ImpulseBitSet bsJit = (ImpulseBitSet) jitRes;
				ImpulseBitSet bsInterp = (ImpulseBitSet) interpRes;

				for (int node = 0; node < 6; node++) {
					assertEquals(bsInterp.get(node), bsJit.get(node),
							"JIT vs Interpreter mismatch on query " + qIdx + " seed=" + seed + " node=" + node);
				}
			}
		}
	}
}
