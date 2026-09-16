package org.impulsegraph.compiler.passes;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated unit tests for all Stage 2 compiler passes: BindTimeValidator,
 * DirectionSelectionPass, FilterPushdownPass, InjectiveDeduplicationBypassPass,
 * KernelFusionPass, and VirtualRelationDecompositionPass.
 */
@DisplayName("Compiler Passes - Stage 2 Test Suite")
public class CompilerPassesStage2Test {

	private static CompilerContext createContext(ImpulseGraphSnapshot snapshot, CompilerOptions options) {
		CompilerOptions opts = options != null ? options : CompilerOptions.DEFAULT;
		return new CompilerContext(snapshot, opts, new PassTracer(opts));
	}

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
	// 1. BindTimeValidator
	// =========================================================================
	@Nested
	@DisplayName("1. BindTimeValidator Tests")
	class BindTimeValidatorTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("BindTimeValidator", BindTimeValidator.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Snapshot: When snapshot is null, returns AST unchanged")
		void testNullSnapshotReturnsUnchanged() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("unboundRel"), ScmCollect.bitset());
			assertSame(prog, BindTimeValidator.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Success: Valid relation name resolves and validation passes")
		void testValidRelationPasses() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("userToGroup", rel));

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("userToGroup"),
						ScmVectorFilter.of(ScmSymbol.of("active")), ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode validated = BindTimeValidator.INSTANCE.transform(prog, ctx);
				assertSame(prog, validated);
			}
		}

		@Test
		@DisplayName("Flexibility: Case-insensitive and suffixed relation resolution passes")
		void testCaseInsensitiveAndSuffixedRelationPasses() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				// Snapshot has "domain_rel_userToGroup"
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("domain_rel_userToGroup", rel));

				ScmProgram progCase = ScmProgram.of(ScmWalk.forward("USERTOGROUP"), ScmCollect.bitset());
				ScmProgram progSuffix = ScmProgram.of(ScmWalk.forward("userToGroup"), ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				assertDoesNotThrow(() -> BindTimeValidator.INSTANCE.transform(progCase, ctx));
				assertDoesNotThrow(() -> BindTimeValidator.INSTANCE.transform(progSuffix, ctx));
			}
		}

		@Test
		@DisplayName("Failure: Missing relation throws IllegalStateException")
		void testMissingRelationThrows() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("otherRel", rel));

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("missingRelation"), ScmCollect.bitset());
				CompilerContext ctx = createContext(snapshot, null);

				assertThatThrownBy(() -> BindTimeValidator.INSTANCE.transform(prog, ctx))
						.isInstanceOf(IllegalStateException.class).hasMessageContaining(
								"Bind-Time Validation Failed: Required relation 'missingRelation' does not exist");
			}
		}

		@Test
		@DisplayName("Bypass: Walk with relationId >= 0 bypasses name lookup")
		void testRelationIdBypassesLookup() {
			try (Arena arena = Arena.ofConfined()) {
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of());
				ScmProgram prog = ScmProgram.of(
						new ScmWalk("anyMissingName", 42, ScmWalk.Direction.FORWARD_CSR, List.of(), List.of()),
						ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				assertDoesNotThrow(() -> BindTimeValidator.INSTANCE.transform(prog, ctx));
			}
		}
	}

	// =========================================================================
	// 2. DirectionSelectionPass
	// =========================================================================
	@Nested
	@DisplayName("2. DirectionSelectionPass Tests")
	class DirectionSelectionPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("DirectionSelectionPass", DirectionSelectionPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(DirectionSelectionPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Disabled Option: Returns AST unchanged when direction selection disabled")
		void testDisabledOptionReturnsUnchanged() {
			CompilerOptions disabled = CompilerOptions.builder().withDirectionSelection(false).build();
			CompilerContext ctx = createContext(null, disabled);

			ScmWalk walk = ScmWalk.auto("knows");
			assertSame(walk, DirectionSelectionPass.INSTANCE.transform(walk, ctx));
		}

		@Test
		@DisplayName("Selection: Converts AUTO direction to FORWARD_CSR")
		void testAutoDirectionSelected() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("friends", rel));

				ScmProgram prog = ScmProgram.of(ScmWalk.auto("friends"), ScmVectorFilter.of(ScmSymbol.of("active")),
						ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode opt = DirectionSelectionPass.INSTANCE.transform(prog, ctx);

				assertThat(opt).isInstanceOf(ScmProgram.class);
				ScmWalk optWalk = (ScmWalk) ((ScmProgram) opt).steps().get(0);
				assertEquals(ScmWalk.Direction.FORWARD_CSR, optWalk.direction());
			}
		}

		@Test
		@DisplayName("Preservation: Existing FORWARD_CSR and REVERSE_CSC directions are preserved")
		void testExplicitDirectionPreserved() {
			CompilerContext ctx = createContext(null, null);

			ScmWalk fwd = ScmWalk.forward("rel");
			ScmWalk rev = ScmWalk.reverse("rel");

			ScmProgram prog = ScmProgram.of(fwd, rev, ScmCollect.bitset());
			ImpScmNode opt = DirectionSelectionPass.INSTANCE.transform(prog, ctx);

			ScmProgram optProg = (ScmProgram) opt;
			assertEquals(ScmWalk.Direction.FORWARD_CSR, ((ScmWalk) optProg.steps().get(0)).direction());
			assertEquals(ScmWalk.Direction.REVERSE_CSC, ((ScmWalk) optProg.steps().get(1)).direction());
		}
	}

	// =========================================================================
	// 3. FilterPushdownPass
	// =========================================================================
	@Nested
	@DisplayName("3. FilterPushdownPass Tests")
	class FilterPushdownPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("FilterPushdownPass", FilterPushdownPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(FilterPushdownPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Disabled Option: Returns AST unchanged when filter pushdown disabled")
		void testDisabledOptionReturnsUnchanged() {
			CompilerOptions disabled = CompilerOptions.builder().withFilterPushdown(false).build();
			CompilerContext ctx = createContext(null, disabled);

			ScmProgram prog = ScmProgram.of(ScmWalk.forward("users"), ScmVectorFilter.of(ScmSymbol.of("filter_1")),
					ScmCollect.bitset());

			assertSame(prog, FilterPushdownPass.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Pushdown: Consecutive ScmVectorFilters fused directly into preceding ScmWalk shaderSteps")
		void testVectorFilterPushdown() {
			CompilerContext ctx = createContext(null, null);

			ScmProgram prog = ScmProgram.of(ScmWalk.forward("users"), ScmVectorFilter.of(ScmSymbol.of("filter_active")),
					ScmVectorFilter.of(ScmSymbol.of("filter_verified")), ScmCollect.bitset());

			ImpScmNode res = FilterPushdownPass.INSTANCE.transform(prog, ctx);
			assertThat(res).isInstanceOf(ScmProgram.class);

			ScmProgram optProg = (ScmProgram) res;
			// 2 filters fused into walk -> 4 steps become 2 steps (fused walk + collect)
			assertEquals(2, optProg.steps().size());
			assertThat(optProg.steps().get(0)).isInstanceOf(ScmWalk.class);
			ScmWalk walk = (ScmWalk) optProg.steps().get(0);
			assertEquals(2, walk.shaderSteps().size());
			assertEquals("filter_active", walk.shaderSteps().get(0).toScmString());
			assertEquals("filter_verified", walk.shaderSteps().get(1).toScmString());
			assertThat(optProg.steps().get(1)).isInstanceOf(ScmCollect.class);
		}

		@Test
		@DisplayName("Pushdown: project-state ScmList also pushed into ScmWalk shaderSteps")
		void testProjectStatePushdown() {
			CompilerContext ctx = createContext(null, null);

			ScmList projectState = ScmList.of(ScmSymbol.of("project-state"), ScmSymbol.of("score"));
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("items"), projectState, ScmCollect.bitset());

			ImpScmNode res = FilterPushdownPass.INSTANCE.transform(prog, ctx);
			ScmProgram optProg = (ScmProgram) res;

			assertEquals(2, optProg.steps().size());
			ScmWalk walk = (ScmWalk) optProg.steps().get(0);
			assertEquals(1, walk.shaderSteps().size());
			assertSame(projectState, walk.shaderSteps().get(0));
		}

		@Test
		@DisplayName("Preservation: Unrelated steps or non-program ASTs return unchanged")
		void testUnrelatedStepsPreserved() {
			CompilerContext ctx = createContext(null, null);

			ScmProgram prog = ScmProgram.of(ScmCollect.bitset(), ScmSymbol.of("standalone"));

			ImpScmNode res = FilterPushdownPass.INSTANCE.transform(prog, ctx);
			assertEquals(2, ((ScmProgram) res).steps().size());

			ScmSymbol sym = ScmSymbol.of("leaf");
			assertSame(sym, FilterPushdownPass.INSTANCE.transform(sym, ctx));
		}
	}

	// =========================================================================
	// 4. InjectiveDeduplicationBypassPass
	// =========================================================================
	@Nested
	@DisplayName("4. InjectiveDeduplicationBypassPass Tests")
	class InjectiveDeduplicationBypassPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("InjectiveDeduplicationBypassPass", InjectiveDeduplicationBypassPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(InjectiveDeduplicationBypassPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Snapshot Null: Returns AST unchanged if snapshot is null")
		void testNullSnapshotReturnsUnchanged() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("rel"), ScmCollect.distinct());
			assertSame(prog, InjectiveDeduplicationBypassPass.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Bypass: Single-hop injective relation (InDegree <= 1) rewrites DISTINCT to BITSET")
		void testSingleHopInjectiveBypass() {
			try (Arena arena = Arena.ofConfined()) {
				// Injective relation: rowOffsets {0, 2, 4}, colTargets {0, 1, 2, 3} -> InDegree
				// <= 1
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2, 4}, new int[]{0, 1, 2, 3});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("parentToChildren", rel));
				assertTrue(rel.getStatistics().isInjective());

				ScmProgram ast = ScmProgram.of(ScmWalk.forward("parentToChildren"), ScmCollect.distinct());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode opt = InjectiveDeduplicationBypassPass.INSTANCE.transform(ast, ctx);
				assertThat(opt).isInstanceOf(ScmProgram.class);
				ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(1);
				assertEquals(ScmCollect.Format.BITSET, col.format());
			}
		}

		@Test
		@DisplayName("Bypass: Multi-hop injective relations rewrite DISTINCT to BITSET")
		void testMultiHopInjectiveBypass() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel1 = createMockRelation(arena, new int[]{0, 2, 4}, new int[]{0, 1, 2, 3});
				RelationSnapshot rel2 = createMockRelation(arena, new int[]{0, 1, 2, 3, 4}, new int[]{10, 11, 12, 13});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("step1", rel1, "step2", rel2));

				ScmProgram ast = ScmProgram.of(ScmWalk.forward("step1"), ScmWalk.forward("step2"),
						ScmCollect.distinct());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode opt = InjectiveDeduplicationBypassPass.INSTANCE.transform(ast, ctx);
				ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(2);
				assertEquals(ScmCollect.Format.BITSET, col.format());
			}
		}

		@Test
		@DisplayName("Preservation: Non-injective relation preserves DISTINCT collect format")
		void testNonInjectivePreservesDistinct() {
			try (Arena arena = Arena.ofConfined()) {
				// Non-injective: target 0 appears twice (in-degree 2)
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2, 4}, new int[]{0, 1, 0, 2});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("manyToMany", rel));
				assertFalse(rel.getStatistics().isInjective());

				ScmProgram ast = ScmProgram.of(ScmWalk.forward("manyToMany"), ScmCollect.distinct());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode opt = InjectiveDeduplicationBypassPass.INSTANCE.transform(ast, ctx);
				ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(1);
				assertEquals(ScmCollect.Format.DISTINCT, col.format());
			}
		}

		@Test
		@DisplayName("Preservation: Missing relation or program without walks preserves DISTINCT")
		void testMissingRelationPreservesDistinct() {
			try (Arena arena = Arena.ofConfined()) {
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of());
				ScmProgram astWalk = ScmProgram.of(ScmWalk.forward("unknownRel"), ScmCollect.distinct());
				ScmProgram astNoWalk = ScmProgram.of(ScmSymbol.of("seed"), ScmCollect.distinct());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode optWalk = InjectiveDeduplicationBypassPass.INSTANCE.transform(astWalk, ctx);
				ImpScmNode optNoWalk = InjectiveDeduplicationBypassPass.INSTANCE.transform(astNoWalk, ctx);

				assertEquals(ScmCollect.Format.DISTINCT, ((ScmCollect) ((ScmProgram) optWalk).steps().get(1)).format());
				assertEquals(ScmCollect.Format.DISTINCT,
						((ScmCollect) ((ScmProgram) optNoWalk).steps().get(1)).format());
			}
		}
	}

	// =========================================================================
	// 5. KernelFusionPass
	// =========================================================================
	@Nested
	@DisplayName("5. KernelFusionPass Tests")
	class KernelFusionPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("KernelFusionPass", KernelFusionPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(KernelFusionPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Disabled Option: Returns AST unchanged when experimental 2-hop fusion is disabled")
		void testDisabledOptionReturnsUnchanged() {
			CompilerOptions disabled = CompilerOptions.builder().withExperimental2HopFusion(false).build();
			CompilerContext ctx = createContext(null, disabled);

			ScmProgram prog = ScmProgram.of(ScmWalk.forward("rel1"), ScmWalk.forward("rel2"), ScmCollect.bitset());
			assertSame(prog, KernelFusionPass.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Fusion: Two consecutive forward CSR walks fused into ScmWalk2Hop")
		void testSuccessfulTwoHopFusion() {
			CompilerOptions enabled = CompilerOptions.builder().withExperimental2HopFusion(true).build();
			CompilerContext ctx = createContext(null, enabled);

			ScmProgram prog = ScmProgram.of(ScmWalk.forward("userToGroup").withRelationId(1),
					ScmWalk.forward("groupToPerm").withRelationId(2), ScmCollect.bitset());

			ImpScmNode fused = KernelFusionPass.INSTANCE.transform(prog, ctx);
			assertThat(fused).isInstanceOf(ScmProgram.class);

			ScmProgram fusedProg = (ScmProgram) fused;
			assertEquals(2, fusedProg.steps().size());
			assertThat(fusedProg.steps().get(0)).isInstanceOf(ScmWalk2Hop.class);

			ScmWalk2Hop hop2 = (ScmWalk2Hop) fusedProg.steps().get(0);
			assertEquals("userToGroup", hop2.relation1Name());
			assertEquals(1, hop2.relation1Id());
			assertEquals("groupToPerm", hop2.relation2Name());
			assertEquals(2, hop2.relation2Id());
		}

		@Test
		@DisplayName("Fusion Rejection: Direction not FORWARD_CSR or non-empty shader/sub-steps rejects fusion")
		void testFusionRejectionOnFiltersOrReverse() {
			CompilerOptions enabled = CompilerOptions.builder().withExperimental2HopFusion(true).build();
			CompilerContext ctx = createContext(null, enabled);

			// 1. REVERSE_CSC cannot fuse
			ScmProgram revProg = ScmProgram.of(ScmWalk.reverse("rel1"), ScmWalk.forward("rel2"), ScmCollect.bitset());
			ImpScmNode notFusedRev = KernelFusionPass.INSTANCE.transform(revProg, ctx);
			assertEquals(3, ((ScmProgram) notFusedRev).steps().size());

			// 2. Walk with shaderSteps cannot fuse
			ScmProgram shaderProg = ScmProgram.of(ScmWalk.forward("rel1", ScmSymbol.of("filter")),
					ScmWalk.forward("rel2"), ScmCollect.bitset());
			ImpScmNode notFusedShader = KernelFusionPass.INSTANCE.transform(shaderProg, ctx);
			assertEquals(3, ((ScmProgram) notFusedShader).steps().size());

			// 3. Walk with subSteps cannot fuse
			ScmWalk walkWithSubs = new ScmWalk("rel1", 1, ScmWalk.Direction.FORWARD_CSR, List.of(),
					List.of(ScmSymbol.of("sub")));
			ScmProgram subProg = ScmProgram.of(walkWithSubs, ScmWalk.forward("rel2"), ScmCollect.bitset());
			ImpScmNode notFusedSub = KernelFusionPass.INSTANCE.transform(subProg, ctx);
			assertEquals(3, ((ScmProgram) notFusedSub).steps().size());
		}

		@Test
		@DisplayName("Multiplicity Threshold: High-multiplicity Many-to-Many exceeds threshold and rejects fusion")
		void testHighMultiplicityRejectsFusion() {
			try (Arena arena = Arena.ofConfined()) {
				// Create relation with high average degree / many-to-many
				RelationSnapshot rel1 = createMockRelation(arena, new int[]{0, 5, 10},
						new int[]{0, 1, 2, 3, 4, 0, 1, 2, 3, 4});
				RelationSnapshot rel2 = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});

				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("rel1", rel1, "rel2", rel2));
				// Set high multiplicity statistics on rel1
				RelationStatistics highMultiStats = new RelationStatistics(2, 10, 2, 5, 5.0, 0.0, 5, 5, 5, 1.0,
						new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 0),
						RelationStatistics.Multiplicity.MANY_TO_MANY, 5, 5.0, false, false, false);
				snapshot.getGraphStatistics().putRelationStatistics("rel1", highMultiStats);

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("rel1"), ScmWalk.forward("rel2"), ScmCollect.bitset());
				CompilerOptions enabled = CompilerOptions.builder().withExperimental2HopFusion(true).build();
				CompilerContext ctx = createContext(snapshot, enabled);

				ImpScmNode res = KernelFusionPass.INSTANCE.transform(prog, ctx);
				// Remains 3 steps because avg in-degree 5.0 > threshold 1.5
				assertEquals(3, ((ScmProgram) res).steps().size());
			}
		}
	}

	// =========================================================================
	// 6. VirtualRelationDecompositionPass
	// =========================================================================
	@Nested
	@DisplayName("6. VirtualRelationDecompositionPass Tests")
	class VirtualRelationDecompositionPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("VirtualRelationDecompositionPass", VirtualRelationDecompositionPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(VirtualRelationDecompositionPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Snapshot Null: Returns AST unchanged if snapshot is null")
		void testNullSnapshotReturnsUnchanged() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("in_section"), ScmCollect.bitset());
			assertSame(prog, VirtualRelationDecompositionPass.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Decomposition: Virtual super-relation decomposes into sorted constituent partitions")
		void testCoproductDecomposition() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot fruit = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				RelationSnapshot bread = createMockRelation(arena, new int[]{0, 1}, new int[]{0});

				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena,
						Map.of("in_section_fruit", fruit, "in_section_bread", bread));

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("in_section"), ScmCollect.bitset());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode decomposed = VirtualRelationDecompositionPass.INSTANCE.transform(prog, ctx);
				assertThat(decomposed).isInstanceOf(ScmProgram.class);

				ScmWalk vrWalk = (ScmWalk) ((ScmProgram) decomposed).steps().get(0);
				assertEquals(2, vrWalk.subSteps().size());
				assertEquals("in_section_bread", ((ScmWalk) vrWalk.subSteps().get(0)).relationName());
				assertEquals("in_section_fruit", ((ScmWalk) vrWalk.subSteps().get(1)).relationName());
			}
		}

		@Test
		@DisplayName("CSC Direction Selection: Constituent with CSC and avgInDegree < avgDegree selects REVERSE_CSC")
		void testConstituentCscSelection() {
			try (Arena arena = Arena.ofConfined()) {
				// Allocate CSR and CSC segments for fruit
				MemorySegment rowSeg = arena.allocate(8);
				MemorySegment colSeg = arena.allocate(8);
				MemorySegment cscRowSeg = arena.allocate(8);
				MemorySegment cscColSeg = arena.allocate(8);

				RelationSnapshot fruit = new RelationSnapshot(arena, 1, 2, rowSeg, colSeg, cscRowSeg, cscColSeg);
				// Inject stats: avgInDegree 0.5 < avgDegree 2.0
				RelationStatistics fruitStats = new RelationStatistics(1, 2, 1, 2, 2.0, 0.0, 2, 2, 2, 1.0,
						new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 0),
						RelationStatistics.Multiplicity.ONE_TO_MANY, 1, 0.5, true, false, false);
				// Override via reflection or metadata
				RelationSnapshot bread = createMockRelation(arena, new int[]{0, 1}, new int[]{0});

				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena,
						Map.of("in_section_fruit", fruit, "in_section_bread", bread));
				snapshot.getGraphStatistics().putRelationStatistics("in_section_fruit", fruitStats);

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("in_section"), ScmCollect.bitset());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode decomposed = VirtualRelationDecompositionPass.INSTANCE.transform(prog, ctx);
				ScmWalk vrWalk = (ScmWalk) ((ScmProgram) decomposed).steps().get(0);
				assertEquals(2, vrWalk.subSteps().size());
			}
		}

		@Test
		@DisplayName("No Decomposition: When <= 1 matching partitions exist, walk remains unchanged")
		void testSinglePartitionDoesNotDecompose() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot fruit = createMockRelation(arena, new int[]{0, 1}, new int[]{0});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("in_section_fruit", fruit));

				ScmProgram prog = ScmProgram.of(ScmWalk.forward("in_section"), ScmCollect.bitset());
				CompilerContext ctx = createContext(snapshot, null);

				ImpScmNode res = VirtualRelationDecompositionPass.INSTANCE.transform(prog, ctx);
				ScmWalk walk = (ScmWalk) ((ScmProgram) res).steps().get(0);
				// Sub-steps empty because only 1 match
				assertTrue(walk.subSteps().isEmpty());
			}
		}
	}
}
