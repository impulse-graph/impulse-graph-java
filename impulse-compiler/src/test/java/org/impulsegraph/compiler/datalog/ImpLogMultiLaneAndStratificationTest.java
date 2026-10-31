package org.impulsegraph.compiler.datalog;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ScmFixpoint;
import org.impulsegraph.compiler.ast.ScmLaneInitGroups;
import org.impulsegraph.compiler.ast.ScmLaneInitSeeds;
import org.impulsegraph.compiler.ast.ScmLanePruneMask;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmSetOp;
import org.impulsegraph.compiler.datalog.StratificationValidator.DependencyEdge;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Issue #25 Item 2: ImpLog Multi-Lane Datalog, ReBAC & Stratification Tests")
public class ImpLogMultiLaneAndStratificationTest {

	private static ImpulseGraphSnapshot createDatalogSnapshot(Arena arena) {
		// 4 nodes: 0 -> {1}, 1 -> {2}, 2 -> {3}, 3 -> {}
		int[] offsets = {0, 1, 2, 3, 3};
		int[] targets = {1, 2, 3};
		RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, offsets, targets);
		return new GraphSnapshot(arena, Map.of("member_of", rel, "blocked", rel), Map.of());
	}

	@Test
	@DisplayName("ImpLogCompiler lowers multi-seed recursive Datalog rules to 0xC0 + 0xC3 (flags=0x04 fixed-point)")
	public void testMultiSeedRecursiveLoweringAndExecution() {
		String src = """
				reach(X, Y) :- member_of(X, Y).
				reach(X, Z) :- reach(X, Y), member_of(Y, Z).
				""";
		ScmProgram prog = ImpLogCompiler.compileMultiSeed(src, List.of(0L, 1L));
		assertThat(prog.steps().get(0)).isInstanceOf(ScmLaneInitSeeds.class);
		assertThat(prog.steps().get(1)).isInstanceOf(ScmLaneWalk.class);
		ScmLaneWalk walk = (ScmLaneWalk) prog.steps().get(1);
		assertThat(walk.relationName()).isEqualTo("member_of");
		assertThat(walk.fixedPoint()).isTrue();

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createDatalogSnapshot(arena);
			CompiledQuery cq = ImpLogCompiler.compileMultiSeedToExecutable(src, List.of(0L, 1L), snap, arena);
			Object res = cq.execute(snap, 0L, arena);
			assertThat(res).isInstanceOf(ImpulseBitSet.class);
			ImpulseBitSet bs = (ImpulseBitSet) res;
			assertThat(bs.get(1)).isTrue();
			assertThat(bs.get(2)).isTrue();
			assertThat(bs.get(3)).isTrue();
		}
	}

	@Test
	@DisplayName("ImpLogCompiler lowers inline .seed, .group, and .denied facts and allow/deny split (0xC5 mode 0 & mode 1)")
	public void testInlineFactsGroupsAndAllowDenySplit() {
		String srcWithDeny = """
				.seed(0, 1).
				.denied(2).
				allowed(X, Y) :- member_of(X, Y), !blocked(X, Y).
				""";
		ScmProgram prog = ImpLogCompiler.compile(srcWithDeny);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneInitSeeds);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLanePruneMask pm && pm.mode() == 0);

		String srcGroups = """
				.group(0, 0).
				.group(1, 1).
				reach(X, Y) :- member_of(X, Y).
				""";
		ScmProgram groupProg = ImpLogCompiler.compile(srcGroups);
		assertThat(groupProg.steps().get(0)).isInstanceOf(ScmLaneInitGroups.class);

		ScmProgram splitProg = ImpLogCompiler.compileAllowDenySplit("can_access(X, Y) :- member_of(X, Y).",
				List.of(0L, 1L), List.of(2L));
		assertThat(splitProg.steps())
				.anyMatch(s -> s instanceof ScmLanePruneMask pm && pm.mode() == 1 && pm.arg0() == 2);
	}

	@Test
	@DisplayName("ImpLogCompiler lowers single-seed recursive and negated rules to ScmFixpoint and ScmSetOp.diff")
	public void testSingleSeedRecursiveAndNegationLowering() {
		String recSrc = """
				reach(X, Y) :- member_of(X, Y).
				reach(X, Z) :- reach(X, Y), member_of(Y, Z).
				""";
		ScmProgram recProg = ImpLogCompiler.compile(recSrc);
		assertThat(recProg.steps().get(0)).isInstanceOf(ScmFixpoint.class);

		String negSrc = "allowed(X, Y) :- member_of(X, Y), not blocked(X, Y).";
		ScmProgram negProg = ImpLogCompiler.compile(negSrc);
		assertThat(negProg.steps().get(0)).isInstanceOf(ScmSetOp.class);
	}

	@Test
	@DisplayName("StratificationValidator rejects negative cycles (MC/DC) and MagicSetsTransformation prunes unreachable rules")
	public void testStratificationValidatorAndMagicSets() {
		// Positive cycle without negation is valid (recursive Datalog)
		StratificationValidator.validate(
				List.of(new DependencyEdge("reach", "edge", false), new DependencyEdge("reach", "reach", false)));
		assertThat(StratificationValidator.isRecursive("reach", List.of(new DependencyEdge("reach", "reach", false))))
				.isTrue();

		// Negative cycle p :- !q. q :- p. must throw IllegalArgumentException
		assertThatThrownBy(() -> ImpLogCompiler.compile("""
				p(X, Y) :- member_of(X, Y), !q(X, Y).
				q(X, Y) :- p(X, Y).
				""")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Non-stratifiable");

		// MagicSetsTransformation + DatalogParser
		var rules = DatalogParser.parse("""
				reach(X, Y) :- member_of(X, Y).
				unused(X, Y) :- other_rel(X, Y).
				""");
		assertThat(rules.steps()).isNotEmpty();
		Set<String> reachable = MagicSetsTransformation.computeReachablePredicates("reach", List
				.of(new DependencyEdge("reach", "member_of", false), new DependencyEdge("unused", "other_rel", false)));
		assertThat(reachable).containsExactlyInAnyOrder("reach", "member_of");
	}
}
