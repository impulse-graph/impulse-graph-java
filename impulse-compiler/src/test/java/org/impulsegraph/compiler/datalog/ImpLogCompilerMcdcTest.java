package org.impulsegraph.compiler.datalog;

import org.impulsegraph.compiler.ast.*;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImpLogCompilerMcdcTest {

	@Test
	void testDirectivesCommentsReverseAndNegationSingleLane() {
		String src = """
				% prolog comment
				// slash comment
				# hash comment
				.decl reach(x, y)
				?- reach(x, y).
				reach(x, y) :- edge(x, y) // inline comment
				reach(x, y) :- reach(x, z), edge(z, y). % inline pct
				rev(x, y) :- knows^T(x, y), !blocked(y), not banned(y).
				rev2(x, y) :- follows_rev(x, y).
				""";
		ScmProgram prog = ImpLogCompiler.compile(src);
		assertFalse(prog.steps().isEmpty());
		assertInstanceOf(ScmCollect.class, prog.steps().get(prog.steps().size() - 1));
		String scm = prog.toScmString();
		assertTrue(scm.contains("edge"));
		assertTrue(scm.contains("knows"));
		assertTrue(scm.contains("follows_rev"));
		assertTrue(scm.contains("blocked"));
		assertTrue(scm.contains("banned"));

		// Empty source -> default walk + collect
		ScmProgram empty = ImpLogCompiler.compile("   \n// only comment\n");
		assertEquals(2, empty.steps().size());
		assertInstanceOf(ScmWalk.class, empty.steps().get(0));
		assertInstanceOf(ScmCollect.class, empty.steps().get(1));
	}

	@Test
	void testMultiLaneDirectivesGroupsAndNegation() {
		String src = """
				.seed(1, 2, bad).
				seeds(3).
				.denied(99).
				reach(x, y) :- edge(x, y).
				reach(x, y) :- reach(x, z), edge(z, y).
				""";
		ScmProgram prog = ImpLogCompiler.compile(src);
		assertInstanceOf(ScmLaneInitSeeds.class, prog.steps().get(0));
		assertTrue(prog.steps().stream().anyMatch(s -> s instanceof ScmLaneWalk));
		assertTrue(prog.steps().stream().anyMatch(s -> s instanceof ScmLanePruneMask));

		// Group directives + compileGroups + negatedPreds in multiLane
		String grpSrc = """
				.group(10, 2).
				groups(11, bad).
				access(u, r) :- member(u, r), !revoked(u).
				""";
		ScmProgram grpProg = ImpLogCompiler.compileGroups(grpSrc, List.of(0L, 1L), 2);
		assertInstanceOf(ScmLaneInitGroups.class, grpProg.steps().get(0));
		ScmLaneInitGroups init = (ScmLaneInitGroups) grpProg.steps().get(0);
		assertEquals(3, init.numGroups()); // maxGroup (2) + 1
		assertTrue(grpProg.steps().stream().anyMatch(s -> s instanceof ScmLanePruneMask));
	}

	@Test
	void testCompileAllowDenySplitAndBounds() {
		String src = "reach(x, y) :- knows(x, y).";
		ScmProgram split = ImpLogCompiler.compileAllowDenySplit(src, List.of(1L, 2L), List.of(9L));
		assertTrue(split.steps().stream().anyMatch(s -> s instanceof ScmLaneInitSeeds));
		assertTrue(split.steps().stream().anyMatch(s -> s instanceof ScmLanePruneMask));

		ScmProgram split2 = ImpLogCompiler.compileAllowDenySplit(src, List.of(1L), List.of(8L, 9L));
		assertTrue(split2.steps().stream().anyMatch(s -> s instanceof ScmLanePruneMask));

		assertThrows(IllegalArgumentException.class,
				() -> ImpLogCompiler.compileAllowDenySplit(src, List.of(), List.of()));
		List<Long> tooMany = Collections.nCopies(33, 1L);
		assertThrows(IllegalArgumentException.class,
				() -> ImpLogCompiler.compileAllowDenySplit(src, tooMany, List.of(1L)));
	}

	@Test
	void testAtomParsingEdgeCasesAndNonRecursiveDerivedRule() {
		String src = """
				helper(x) :- edge(x).
				out(x) :- helper(x), flag(), bare_pred.
				""";
		ScmProgram prog = ImpLogCompiler.compile(src);
		String scm = prog.toScmString();
		assertTrue(scm.contains("edge"));
		assertTrue(scm.contains("flag"));
		assertTrue(scm.contains("bare_pred"));
	}
}
