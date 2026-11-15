package org.impulsegraph.compiler.impk;

import org.impulsegraph.compiler.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImpKCompilerMcdcTest {

	@Test
	void testTokenizerCommentsOperatorsAndAssignments() {
		String script = """
				# hash comment
				// slash comment
				n = 42;
				y = A @ x;
				z : v + w;
				u = unknown_fn(1, "abc", 'def');
				""";
		List<ImpKStatement> stmts = ImpKCompiler.parse(script);
		assertEquals(2, stmts.size());
		assertEquals(ImpKOpType.MATRIX_VECTOR_MUL, stmts.get(0).opType());
		assertEquals("A", stmts.get(0).matrixVar());
		assertEquals("x", stmts.get(0).vectorVar1());

		assertEquals(ImpKOpType.VECTOR_ADD, stmts.get(1).opType());
		assertEquals("v", stmts.get(1).vectorVar1());
		assertEquals("w", stmts.get(1).vectorVar2());
	}

	@Test
	void testCoreGraphBlasAndVectorCalls() {
		String script = """
				p = pagerank("knows", x0, 0.9);
				c = afforest(knows);
				d = degree(knows);
				t = topk(p, 5);
				a1 = ann_search([0.5, -1.25], 8, 2, true);
				a2 = vector_ann_search(qvec, 4);
				r1 = rerank(cands, [1.0, 2.0], "cosine");
				r2 = rerank_dot(cands, qvec, 3, "l2");
				r3 = vector_rerank_dot(cands, qvec, 1, 3);
				""";
		List<ImpKStatement> stmts = ImpKCompiler.parse(script);
		assertEquals(9, stmts.size());

		assertEquals(ImpKOpType.PAGERANK_STEP, stmts.get(0).opType());
		assertEquals(0.9, stmts.get(0).scalarParam(), 1e-9);
		assertEquals(ImpKOpType.CONNECTED_COMPONENTS, stmts.get(1).opType());
		assertEquals(ImpKOpType.DEGREE_NORM, stmts.get(2).opType());
		assertEquals(ImpKOpType.TOP_K, stmts.get(3).opType());
		assertEquals(5, stmts.get(3).topK());

		ImpKStatement a1 = stmts.get(4);
		assertEquals(ImpKOpType.VECTOR_ANN_SEARCH, a1.opType());
		assertEquals(List.of(0.5f, -1.25f), a1.floatList());
		assertEquals(8, a1.topK());
		assertEquals(2, a1.intParam1());
		assertTrue(a1.boolParam());

		ImpKStatement a2 = stmts.get(5);
		assertEquals("qvec", a2.vectorVar1());
		assertFalse(a2.boolParam());

		assertEquals(2, stmts.get(6).intParam2()); // cosine
		assertEquals(3, stmts.get(7).intParam1());
		assertEquals(1, stmts.get(7).intParam2()); // l2
		assertEquals(3, stmts.get(8).intParam2()); // numeric metric 3
	}

	@Test
	void testLane64FunctionsAndOverloads() {
		String script = """
				s1 = lane_seeds([10, 20, 30]);
				s2 = lane_init_seeds(1, 2, 3);
				s3 = lane_seeds();
				g = lane_groups([0, 1, 2, 3], 4);
				em = lane_edge_mask("follows", [1, 2]);
				w1 = lane_spmm("follows", s1, fixpoint);
				w2 = lane_walk_csr("follows", 1);
				w3 = lane_spmm_csc("follows", w1, true);
				w4 = lane_walk_csc("follows", 0);
				p1 = lane_prune(w1, 2, 5, 1);
				q1 = lane_quorum(p1, 3);
				ra = lane_reduce_and(q1);
				rt = lane_reduce_attr(ra, 7, 2);
				sc1 = lane_set_cover(ra, 5);
				sc2 = lane_set_cover(ra, 4, 15, 1);
				""";
		List<ImpKStatement> stmts = ImpKCompiler.parse(script);
		assertEquals(15, stmts.size());

		assertEquals(List.of(10L, 20L, 30L), stmts.get(0).intList());
		assertEquals(List.of(1L, 2L, 3L), stmts.get(1).intList());
		assertTrue(stmts.get(2).intList().isEmpty());
		assertEquals(4, stmts.get(3).intParam1());
		assertEquals(ImpKOpType.LANE_SET_EDGE_MASK, stmts.get(4).opType());
		assertTrue(stmts.get(5).boolParam());
		assertTrue(stmts.get(6).boolParam());
		assertTrue(stmts.get(7).boolParam());
		assertFalse(stmts.get(8).boolParam());
		assertEquals(1L, stmts.get(9).intList().get(0));
		assertEquals(3, stmts.get(10).topK());
		assertEquals(ImpKOpType.LANE_REDUCE_AND, stmts.get(11).opType());
		assertEquals(2, stmts.get(12).intParam2());
		assertEquals(5, stmts.get(13).intParam1());
		assertEquals(4, stmts.get(14).topK());
		assertEquals(15, stmts.get(14).intParam1());
		assertEquals(1, stmts.get(14).intParam2());
	}

	@Test
	void testLoweringBranchesAndErrors() {
		// Empty script lowers to default edge walk + collect bitset
		ScmProgram emptyProg = ImpKCompiler.compile("");
		assertEquals(2, emptyProg.steps().size());
		assertInstanceOf(ScmWalk.class, emptyProg.steps().get(0));
		assertInstanceOf(ScmCollect.class, emptyProg.steps().get(1));

		// Unfused ANN search lowers to collect vector
		ScmProgram vecProg = ImpKCompiler.compile("a = ann_search(q, 5);");
		ScmCollect col = (ScmCollect) vecProg.steps().get(vecProg.steps().size() - 1);
		assertEquals(ScmCollect.vector(), col);

		// Terminal reduce / rerank / lane_reduce_attr skips extra ScmCollect
		ScmProgram reduceProg = ImpKCompiler.compile("t = topk(v, 3);");
		assertEquals(1, reduceProg.steps().size());
		assertInstanceOf(ScmReduce.class, reduceProg.steps().get(0));

		ScmProgram rerankProg = ImpKCompiler.compile("r = rerank(c, [1.0], \"hamming\");");
		assertEquals(1, rerankProg.steps().size());
		assertInstanceOf(ScmVectorRerankDot.class, rerankProg.steps().get(0));

		ScmProgram laneReduceProg = ImpKCompiler.compile("r = lane_reduce_attr(f, 1);");
		assertEquals(1, laneReduceProg.steps().size());
		assertInstanceOf(ScmLaneReduceAttr.class, laneReduceProg.steps().get(0));

		// Lowering register-backed seeds and lane_prune_mask with empty intList
		List<ImpKStatement> manual = List.of(
				new ImpKStatement("s", ImpKOpType.LANE_INIT_SEEDS, "", "", "", 0.0, 0, 0, 0, false, List.of(),
						List.of()),
				new ImpKStatement("p", ImpKOpType.LANE_PRUNE_MASK, "", "s", "", 0.0, 0, 1, 2, false, List.of(),
						List.of()),
				new ImpKStatement("v", ImpKOpType.VECTOR_ADD, "", "a", "b", 0.0, 0, 0, 0, false, List.of(), List.of()));
		assertNotNull(ImpKCompiler.toImpScheme(manual));

		// Malformed syntax throws IllegalArgumentException
		assertThrows(IllegalArgumentException.class, () -> ImpKCompiler.parse("a = degree(edge"));
		assertThrows(IllegalArgumentException.class, () -> ImpKCompiler.parse("a = lane_seeds([1, 2"));
	}
}
