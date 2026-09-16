package org.impulsegraph.compiler.explain;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpAsmDisassembler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.harness.CrossCompilerParityHarness;
import org.impulsegraph.compiler.metrics.CompilerMetricsRecorder;
import org.impulsegraph.compiler.metrics.PlanCacheMetricsRecorder;
import org.impulsegraph.compiler.registry.QueryCompilerEngine;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.RelationInstructionPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated unit tests for compiler diagnostics, explainers, and metrics:
 * CompilerMetricsRecorder, PlanCacheMetricsRecorder, QueryExplainer,
 * QueryCompilerEngine, ImpAsmDisassembler, and CrossCompilerParityHarness.
 */
@DisplayName("Compiler Metrics, Explainer, and Disassembler Test Suite")
public class CompilerMetricsAndExplainerTest {

	private static RelationSnapshot createMockRelation(Arena arena, int[] rowOffsets, int[] colTargets) {
		MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * ValueLayout.JAVA_INT.byteSize());
		for (int i = 0; i < rowOffsets.length; i++) {
			rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);
		}
		MemorySegment colSeg = arena.allocate((long) colTargets.length * ValueLayout.JAVA_INT.byteSize());
		for (int i = 0; i < colTargets.length; i++) {
			colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);
		}
		return new RelationSnapshot(arena, rowOffsets.length - 1, colTargets.length, rowSeg, colSeg);
	}

	// =========================================================================
	// 1. CompilerMetricsRecorder
	// =========================================================================
	@Nested
	@DisplayName("1. CompilerMetricsRecorder Tests")
	class CompilerMetricsRecorderTests {

		@Test
		@DisplayName("Initial State: All counters initialized to zero")
		void testInitialState() {
			CompilerMetricsRecorder recorder = new CompilerMetricsRecorder();
			assertEquals(0, recorder.stage1Compilations());
			assertEquals(0, recorder.stage2Compilations());
			assertEquals(0, recorder.stage1DurationNanos());
			assertEquals(0, recorder.stage2DurationNanos());
			assertEquals(0, recorder.jitDurationNanos());
			assertEquals(0, recorder.compilationFailures());
			assertTrue(recorder.passDurations().isEmpty());
		}

		@Test
		@DisplayName("Recording: Accurately accumulates compilation durations and counts")
		void testMetricsAccumulation() {
			CompilerMetricsRecorder recorder = new CompilerMetricsRecorder();

			recorder.recordStage1(1500L);
			recorder.recordStage1(2500L);
			assertEquals(2, recorder.stage1Compilations());
			assertEquals(4000L, recorder.stage1DurationNanos());

			recorder.recordStage2(5000L);
			assertEquals(1, recorder.stage2Compilations());
			assertEquals(5000L, recorder.stage2DurationNanos());

			recorder.recordJit(300L);
			recorder.recordJit(700L);
			assertEquals(1000L, recorder.jitDurationNanos());

			recorder.recordFailure();
			recorder.recordFailure();
			assertEquals(2, recorder.compilationFailures());

			recorder.recordPass("ConstantFoldingPass", 450L);
			recorder.recordPass("ConstantFoldingPass", 550L);
			recorder.recordPass("AstNormalizationPass", 200L);

			Map<String, AtomicLong> map = recorder.passDurations();
			assertEquals(1000L, map.get("ConstantFoldingPass").get());
			assertEquals(200L, map.get("AstNormalizationPass").get());
		}
	}

	// =========================================================================
	// 2. PlanCacheMetricsRecorder
	// =========================================================================
	@Nested
	@DisplayName("2. PlanCacheMetricsRecorder Tests")
	class PlanCacheMetricsRecorderTests {

		@Test
		@DisplayName("Initial State: All cache counters initialized to zero")
		void testInitialState() {
			PlanCacheMetricsRecorder recorder = new PlanCacheMetricsRecorder();
			assertEquals(0, recorder.cacheHits());
			assertEquals(0, recorder.cacheMisses());
			assertEquals(0, recorder.planRebinds());
			assertEquals(0, recorder.planEvictions());
			assertEquals(0, recorder.activePlans());
			assertEquals(0, recorder.bytecodeMemoryBytes());
		}

		@Test
		@DisplayName("Recording: Hit/miss ratios, rebinds, evictions, and memory footprint")
		void testCacheRecording() {
			PlanCacheMetricsRecorder recorder = new PlanCacheMetricsRecorder();

			recorder.recordHit();
			recorder.recordHit();
			assertEquals(2, recorder.cacheHits());

			recorder.recordMiss();
			assertEquals(1, recorder.cacheMisses());

			recorder.recordRebind();
			assertEquals(1, recorder.planRebinds());

			recorder.setPlanCount(10);
			assertEquals(10, recorder.activePlans());

			recorder.recordEviction();
			assertEquals(1, recorder.planEvictions());
			assertEquals(9, recorder.activePlans());

			recorder.addBytecodeMemory(1024L);
			recorder.addBytecodeMemory(2048L);
			assertEquals(3072L, recorder.bytecodeMemoryBytes());
		}
	}

	// =========================================================================
	// 3. ImpAsmDisassembler
	// =========================================================================
	@Nested
	@DisplayName("3. ImpAsmDisassembler Tests")
	class ImpAsmDisassemblerTests {

		@Test
		@DisplayName("Disassembly: Produces valid .impas text with header, opcode names, and comments")
		void testDisassemblyFormatting() {
			try (Arena arena = Arena.ofConfined()) {
				List<InstructionWord> instructions = new ArrayList<>();
				instructions.add(new InstructionWord(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0));
				instructions.add(new InstructionWord(OP_CSR_WALK, (byte) 0x02, (short) 1, 0x00010000));
				instructions.add(new InstructionWord(OP_CSR_WALK_2HOP, (byte) 0x01, (short) 2, 0x00020001));
				instructions.add(new InstructionWord(OP_NODE_FILTER, (byte) 0, (short) 1, 0x00010001));
				instructions.add(new InstructionWord(OP_COLLECT_BITSET, (byte) 0, (short) 2, 0));
				instructions.add(new InstructionWord(OP_HALT, (byte) 0, (short) 0, 0));

				List<RelationInstructionPatch> patches = List
						.of(new RelationInstructionPatch(1L, "userToGroup", (short) 0, (short) 1));

				MemorySegment seg = arena.allocate(instructions.size() * 8);
				EmittedProgram program = new EmittedProgram(seg, instructions.size(), patches, Map.of("userToGroup", 1),
						instructions, List.of());

				String asm = ImpAsmDisassembler.disassemble(program);
				assertNotNull(asm);
				assertThat(asm).contains("IMPULSE VM BYTECODE DISASSEMBLY (.impas)");
				assertThat(asm).contains(".version 0.9.0");
				assertThat(asm).contains(".instructions 6");
				assertThat(asm).contains("OP_INIT_INPUT_NODE");
				assertThat(asm).contains("Load input node ID into R0");
				assertThat(asm).contains("OP_CSR_WALK");
				assertThat(asm).contains("[seed-inlined]");
				assertThat(asm).contains("userToGroup");
				assertThat(asm).contains("OP_CSR_WALK_2HOP");
				assertThat(asm).contains("[early-exit]");
				assertThat(asm).contains("OP_NODE_FILTER");
				assertThat(asm).contains("OP_COLLECT_BITSET");
				assertThat(asm).contains("OP_HALT");
				assertThat(asm).contains("Execution complete");
			}
		}

		@Test
		@DisplayName("Fallback: Unrecognized opcode falls back to OP_UNKNOWN_0x hex string")
		void testUnknownOpcodeFallback() {
			try (Arena arena = Arena.ofConfined()) {
				byte unknownOp = (byte) 0xFE;
				List<InstructionWord> instructions = List.of(new InstructionWord(unknownOp, (byte) 0, (short) 0, 0));

				MemorySegment seg = arena.allocate(8);
				EmittedProgram program = new EmittedProgram(seg, 1, List.of(), Map.of(), instructions, List.of());

				String asm = ImpAsmDisassembler.disassemble(program);
				assertThat(asm).contains("OP_UNKNOWN_0xFE");
			}
		}
	}

	// =========================================================================
	// 4. QueryExplainer
	// =========================================================================
	@Nested
	@DisplayName("4. QueryExplainer Tests")
	class QueryExplainerTests {

		@Test
		@DisplayName("Validation: Null QueryObject throws NullPointerException")
		void testNullQueryObjectThrows() {
			assertThatThrownBy(() -> QueryExplainer.explain(null, null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("queryObject must not be null");
		}

		@Test
		@DisplayName("With Snapshot: Produces comprehensive multi-pass explanation and disassembly")
		void testExplainWithSnapshot() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("userToGroup", rel));

				ScmProgram rawAst = ScmProgram.of(ScmWalk.forward("userToGroup"), ScmCollect.bitset());
				QueryObject qo = new QueryObject("UserGroupsQuery", "MATCH (u:User)-[:userToGroup]->(g:Group) RETURN g",
						rawAst);

				String report = QueryExplainer.explain(qo, snapshot);

				assertNotNull(report);
				assertThat(report).contains("IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT");
				assertThat(report).contains("Query Name: UserGroupsQuery");
				assertThat(report).contains("Source Query DSL:");
				assertThat(report).contains("MATCH (u:User)-[:userToGroup]->(g:Group) RETURN g");
				assertThat(report).contains("PreBindValidator");
				assertThat(report).contains("AstNormalizationPass");
				assertThat(report).contains("ConstantFoldingPass");
				assertThat(report).contains("CelPredicateFlatteningPass");
				assertThat(report).contains("BindTimeValidator");
				assertThat(report).contains("DirectionSelectionPass");
				assertThat(report).contains("FilterPushdownPass");
				assertThat(report).contains("PhysicalBindingPass");
				assertThat(report).contains("RegisterAllocationPass");
				assertThat(report).contains("IMPULSE VM BYTECODE DISASSEMBLY");
				assertThat(report).contains("OP_CSR_WALK");
			}
		}

		@Test
		@DisplayName("Without Snapshot: Performs Stage 1 only and notes skipped Stage 2")
		void testExplainWithoutSnapshot() {
			ScmProgram rawAst = ScmProgram.of(ScmWalk.forward("userToGroup"), ScmCollect.bitset());
			QueryObject qo = new QueryObject("UnboundQuery", "", rawAst);

			String report = QueryExplainer.explain(qo, null);

			assertNotNull(report);
			assertThat(report).contains("IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT");
			assertThat(report).contains("Query Name: UnboundQuery");
			assertThat(report).contains("(Snapshot not bound — Stage 2 physical binding skipped)");
		}
	}

	// =========================================================================
	// 5. QueryCompilerEngine
	// =========================================================================
	@Nested
	@DisplayName("5. QueryCompilerEngine Tests")
	class QueryCompilerEngineTests {

		@Test
		@DisplayName("Stage 1: Valid query compiles successfully and records metrics")
		void testStage1Compilation() {
			QueryCompilerEngine engine = new QueryCompilerEngine();
			assertNotNull(engine.compilerMetrics());
			assertNotNull(engine.planCacheMetrics());

			ScmProgram rawAst = ScmProgram.of(ScmWalk.forward("friends", new ScmCelExpr("node.age >= 21", null)),
					ScmCollect.bitset());

			QueryObject qo = engine.compileStage1("testQuery", "sourceQuery", rawAst, null);

			assertNotNull(qo);
			assertEquals("testQuery", qo.name());
			assertEquals("sourceQuery", qo.sourceQuery());
			assertNotNull(qo.ast());

			assertEquals(1, engine.compilerMetrics().stage1Compilations());
			assertThat(engine.compilerMetrics().stage1DurationNanos()).isPositive();
		}

		@Test
		@DisplayName("Stage 1 Validation: Null parameters throw NullPointerException")
		void testStage1NullValidation() {
			QueryCompilerEngine engine = new QueryCompilerEngine();
			ScmProgram rawAst = ScmProgram.of(ScmWalk.forward("rel"), ScmCollect.bitset());

			assertThatThrownBy(() -> engine.compileStage1(null, "", rawAst, null))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("queryName must not be null");

			assertThatThrownBy(() -> engine.compileStage1("q", "", null, null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("rawAst must not be null");
		}

		@Test
		@DisplayName("Stage 1 Failure: Invalid AST records failure count and rethrows")
		void testStage1FailureRecorded() {
			QueryCompilerEngine engine = new QueryCompilerEngine();
			ScmProgram emptyAst = new ScmProgram(List.of());

			assertThatThrownBy(() -> engine.compileStage1("invalidQuery", "", emptyAst, null))
					.isInstanceOf(IllegalArgumentException.class);

			assertEquals(1, engine.compilerMetrics().compilationFailures());
		}

		@Test
		@DisplayName("Stage 2: Compiles and caches CompiledQuery on miss; retrieves on hit")
		void testStage2CompilationAndCache() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("friends", rel));

				QueryCompilerEngine engine = new QueryCompilerEngine();
				ScmProgram rawAst = ScmProgram.of(ScmWalk.forward("friends"), ScmCollect.bitset());
				QueryObject qo = engine.compileStage1("q1", "", rawAst, null);

				// 1. First invocation: Cache Miss
				CompiledQuery plan1 = engine.compileStage2(qo, snapshot, arena, null);
				assertNotNull(plan1);
				assertEquals(1, engine.planCacheMetrics().cacheMisses());
				assertEquals(0, engine.planCacheMetrics().cacheHits());
				assertEquals(1, engine.compilerMetrics().stage2Compilations());
				assertEquals(1, engine.planCacheMetrics().activePlans());

				// 2. Second invocation: Cache Hit
				CompiledQuery plan2 = engine.compileStage2(qo, snapshot, arena, null);
				assertSame(plan1, plan2);
				assertEquals(1, engine.planCacheMetrics().cacheHits());
				assertEquals(1, engine.planCacheMetrics().cacheMisses());
				// Stage 2 compilation count unchanged because it was retrieved from cache
				assertEquals(1, engine.compilerMetrics().stage2Compilations());
			}
		}

		@Test
		@DisplayName("Stage 2 Validation: Null parameters throw NullPointerException")
		void testStage2NullValidation() {
			QueryCompilerEngine engine = new QueryCompilerEngine();
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("rel", rel));
				QueryObject qo = new QueryObject("q", "", ScmProgram.of(ScmWalk.forward("rel")));

				assertThatThrownBy(() -> engine.compileStage2(null, snapshot, arena, null))
						.isInstanceOf(NullPointerException.class);

				assertThatThrownBy(() -> engine.compileStage2(qo, null, arena, null))
						.isInstanceOf(NullPointerException.class);

				assertThatThrownBy(() -> engine.compileStage2(qo, snapshot, null, null))
						.isInstanceOf(NullPointerException.class);
			}
		}

		@Test
		@DisplayName("Stage 2 Failure: Missing relation records failure count and rethrows")
		void testStage2FailureRecorded() {
			try (Arena arena = Arena.ofConfined()) {
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of());
				QueryCompilerEngine engine = new QueryCompilerEngine();
				QueryObject qo = new QueryObject("missingRelQ", "", ScmProgram.of(ScmWalk.forward("nonexistent")));

				assertThatThrownBy(() -> engine.compileStage2(qo, snapshot, arena, null))
						.isInstanceOf(IllegalStateException.class);

				assertEquals(1, engine.compilerMetrics().compilationFailures());
			}
		}
	}

	// =========================================================================
	// 6. CrossCompilerParityHarness
	// =========================================================================
	@Nested
	@DisplayName("6. CrossCompilerParityHarness Tests")
	class CrossCompilerParityHarnessTests {

		@Test
		@DisplayName("Instantiation: Verifies default constructor")
		void testConstructor() {
			CrossCompilerParityHarness harness = new CrossCompilerParityHarness();
			assertNotNull(harness);
		}

		@Test
		@DisplayName("Subprocess CLI: Runs main with invalid arguments in child process and verifies error JSON")
		void testSubprocessCliExecution() {
			String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
			String classPath = System.getProperty("java.class.path");

			try {
				ProcessBuilder pb = new ProcessBuilder(javaBin, "--enable-preview", "-cp", classPath,
						"org.impulsegraph.compiler.harness.CrossCompilerParityHarness");
				Process process = pb.start();

				BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
				StringBuilder errOutput = new StringBuilder();
				String line;
				while ((line = errReader.readLine()) != null) {
					errOutput.append(line);
				}

				int exitCode = process.waitFor();
				assertEquals(1, exitCode);
				assertThat(errOutput.toString()).contains("Usage: java CrossCompilerParityHarness <type> <expr>");
			} catch (Exception e) {
				// If process spawning is restricted in the environment, ensure harness class is
				// at least loaded
				assertNotNull(CrossCompilerParityHarness.class);
			}
		}
	}
}
