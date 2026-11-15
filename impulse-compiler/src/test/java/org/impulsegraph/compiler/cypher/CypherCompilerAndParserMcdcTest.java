package org.impulsegraph.compiler.cypher;

import org.impulsegraph.compiler.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CypherCompilerAndParserMcdcTest {

	@Test
	void testSingleLanePathsPredicatesUnboundedAndCount() {
		// Unbounded forward with edge predicate -> ScmFixpoint + ScmCollect.bitset()
		var resUnbounded = CypherCompiler
				.compile("MATCH (a:User)-[e:KNOWS*]->(b:User) WHERE a.id = 1 AND e.weight > 0.5 RETURN b");
		assertEquals("a", resUnbounded.seedVariable());
		assertEquals("1", resUnbounded.seedParameterOrValue());
		assertTrue(resUnbounded.ast().steps().stream().anyMatch(s -> s instanceof ScmFixpoint));
		assertInstanceOf(ScmCollect.class, resUnbounded.ast().steps().get(resUnbounded.ast().steps().size() - 1));

		// Unbounded reverse
		var resUnboundedRev = CypherCompiler.compile("MATCH (a:User)<-[:FOLLOWS*]-(b:User) RETURN b");
		assertTrue(resUnboundedRev.ast().steps().stream()
				.anyMatch(s -> s instanceof ScmFixpoint fp && fp.direction() == ScmWalk.Direction.REVERSE_CSC));

		// Bounded 3-hop + count(b) without ScmSetOp -> ScmCollect.scalar()
		var resBounded = CypherCompiler.compile("MATCH (a:User)-[:KNOWS*1..3]->(b:User) RETURN count(b)");
		assertEquals(4, resBounded.ast().steps().size());
		ScmCollect scalarCollect = (ScmCollect) resBounded.ast().steps().get(3);
		assertEquals(ScmCollect.Format.SCALAR, scalarCollect.format());

		// Undirected union + count(b) -> ScmSetOp + ScmCollect.count()
		var resUnion = CypherCompiler.compile("MATCH (a:User)-[:KNOWS|FOLLOWS]-(b:User) RETURN count(b)");
		assertTrue(resUnion.ast().steps().stream().anyMatch(s -> s instanceof ScmSetOp));
		ScmCollect countCollect = (ScmCollect) resUnion.ast().steps().get(resUnion.ast().steps().size() - 1);
		assertEquals(ScmCollect.Format.COUNT, countCollect.format());
	}

	@Test
	void testMultiLaneModesAndHopValidationErrors() {
		// Multi-lane unbounded + undirected union + quorum
		var resMultiUnion = CypherCompiler
				.compile("MATCH (a:User)-[:R1|R2*]-(b:User) WHERE a.id IN [0, 1] AND quorum >= 2 RETURN b");
		assertInstanceOf(ScmLaneInitSeeds.class, resMultiUnion.ast().steps().get(0));
		assertTrue(resMultiUnion.ast().steps().stream().anyMatch(s -> s instanceof ScmLaneWalk w && w.fixedPoint()));
		assertTrue(resMultiUnion.ast().steps().stream().anyMatch(s -> s instanceof ScmLaneFilterQuorum));

		// Multi-lane reverse bounded 2-hop
		var resMultiRev = CypherCompiler
				.compile("MATCH (a:User)<-[:FOLLOWS*1..2]-(b:User) WHERE a.id IN [3, 4] RETURN b");
		long laneWalkCount = resMultiRev.ast().steps().stream().filter(
				s -> s instanceof ScmLaneWalk w && w.direction() == ScmWalk.Direction.REVERSE_CSC && !w.fixedPoint())
				.count();
		assertEquals(2L, laneWalkCount);

		// Vector ANN + single-lane (no quorum)
		var resAnnSingle = CypherCompiler
				.compile("MATCH (a:User)-[:KNOWS]->(b:User) WHERE vector_ann(a.emb, [1.0, 0.0], 5) RETURN b");
		assertInstanceOf(ScmVectorAnnSearch.class, resAnnSingle.ast().steps().get(0));

		// Invalid hop bounds
		CypherParser.NodePattern n = new CypherParser.NodePattern("a", "User");
		CypherParser.NodePattern m = new CypherParser.NodePattern("b", "User");
		CypherParser.ReturnProjection ret = new CypherParser.ReturnProjection("b", false);

		CypherParser.CypherQuery negMin = new CypherParser.CypherQuery(new CypherParser.PathPattern(n,
				List.of(new CypherParser.PathStep(
						new CypherParser.EdgePattern("e", "R", true, -1, 2, null, List.of("R"), false, false), m))),
				List.of(), ret, null);
		assertThrows(IllegalArgumentException.class, () -> CypherCompiler.compile(negMin));

		CypherParser.CypherQuery maxLtMin = new CypherParser.CypherQuery(new CypherParser.PathPattern(n,
				List.of(new CypherParser.PathStep(
						new CypherParser.EdgePattern("e", "R", true, 3, 1, null, List.of("R"), false, false), m))),
				List.of(), ret, null);
		assertThrows(IllegalArgumentException.class, () -> CypherCompiler.compile(maxLtMin));

		CypherParser.CypherQuery maxTooBig = new CypherParser.CypherQuery(new CypherParser.PathPattern(n,
				List.of(new CypherParser.PathStep(
						new CypherParser.EdgePattern("e", "R", true, 1, 65, null, List.of("R"), false, false), m))),
				List.of(), ret, null);
		assertThrows(IllegalArgumentException.class, () -> CypherCompiler.compile(maxTooBig));
	}
}
