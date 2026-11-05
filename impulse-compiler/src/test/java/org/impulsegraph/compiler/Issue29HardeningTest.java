package org.impulsegraph.compiler;

import java.util.ArrayList;
import java.util.List;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.cypher.CypherCompiler;
import org.impulsegraph.compiler.cypher.CypherParser;
import org.impulsegraph.compiler.datalog.StratificationValidator;
import org.impulsegraph.compiler.datalog.StratificationValidator.DependencyEdge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Issue #29: Compiler Parsers Depth, Cypher Unrolling Cap & Stratification Memoization")
class Issue29HardeningTest {

	@Test
	@DisplayName("ImpScmParser enforces MAX_PARSE_DEPTH = 256")
	void testImpScmParserDepthLimit() {
		// 1. Valid nesting depth <= 256
		StringBuilder valid = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			valid.append("(list ");
		}
		valid.append("1");
		for (int i = 0; i < 50; i++) {
			valid.append(")");
		}
		ImpScmNode node = ImpScmParser.parse(valid.toString());
		assertNotNull(node);

		// 2. Excessive nesting depth > 256
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 260; i++) {
			deep.append("(list ");
		}
		deep.append("1");
		for (int i = 0; i < 260; i++) {
			deep.append(")");
		}
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
			ImpScmParser.parse(deep.toString());
		});
		assertTrue(ex.getMessage().contains("ImpScheme max nesting depth (256) exceeded"));
	}

	@Test
	@DisplayName("CelParser enforces MAX_PARSE_DEPTH = 256")
	void testCelParserDepthLimit() {
		// 1. Valid nesting depth <= 256
		StringBuilder valid = new StringBuilder();
		for (int i = 0; i < 50; i++) {
			valid.append("(");
		}
		valid.append("x + 1");
		for (int i = 0; i < 50; i++) {
			valid.append(")");
		}
		CelAstNode ast = CelParser.parse(valid.toString());
		assertNotNull(ast);

		// 2. Excessive nesting depth > 256
		StringBuilder deep = new StringBuilder();
		for (int i = 0; i < 260; i++) {
			deep.append("(");
		}
		deep.append("x + 1");
		for (int i = 0; i < 260; i++) {
			deep.append(")");
		}
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
			CelParser.parse(deep.toString());
		});
		assertTrue(ex.getMessage().contains("CEL max nesting depth (256) exceeded"));
	}

	@Test
	@DisplayName("CypherParser enforces MAX_PARSE_DEPTH = 256 in WHERE expressions")
	void testCypherWhereDepthLimit() {
		// 1. Valid nesting depth <= 256
		StringBuilder valid = new StringBuilder("MATCH (n:Person) WHERE ");
		for (int i = 0; i < 20; i++) {
			valid.append("(");
		}
		valid.append("n.age > 21");
		for (int i = 0; i < 20; i++) {
			valid.append(")");
		}
		valid.append(" RETURN n");
		assertNotNull(CypherParser.parse(valid.toString()));

		// 2. Excessive nesting depth > 256
		StringBuilder deep = new StringBuilder("MATCH (n:Person) WHERE ");
		for (int i = 0; i < 260; i++) {
			deep.append("(");
		}
		deep.append("n.age > 21");
		for (int i = 0; i < 260; i++) {
			deep.append(")");
		}
		deep.append(" RETURN n");
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
			CypherParser.parse(deep.toString());
		});
		assertTrue(ex.getMessage().contains("Cypher WHERE max nesting depth (256) exceeded"));
	}

	@Test
	@DisplayName("Cypher variable-length path unrolling bounds enforcement")
	void testCypherPathHopsLimit() {
		// 1. Valid hops within limit (maxHops = 64)
		String validQuery = "MATCH (a:Person)-[:FRIEND*1..64]->(b:Person) RETURN b";
		CypherParser.CypherQuery q = CypherParser.parse(validQuery);
		assertNotNull(q);
		assertEquals(64, q.path().steps().get(0).edge().maxHops());
		assertNotNull(CypherCompiler.compile(q));

		// 2. Excessive maxHops > 64 in parser
		String excessiveQuery = "MATCH (a:Person)-[:FRIEND*1..65]->(b:Person) RETURN b";
		IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> {
			CypherParser.parse(excessiveQuery);
		});
		assertTrue(ex1.getMessage().contains("exceeds limit (64)"));

		// 3. Negative minHops in compiler
		CypherParser.EdgePattern negEdge = new CypherParser.EdgePattern("e", "FRIEND", true, -1, 10, null);
		CypherParser.CypherQuery negQuery = new CypherParser.CypherQuery(
				new CypherParser.PathPattern(new CypherParser.NodePattern("a", "Person"),
						List.of(new CypherParser.PathStep(negEdge, new CypherParser.NodePattern("b", "Person")))),
				List.of(), new CypherParser.ReturnProjection("b", false));
		assertThrows(IllegalArgumentException.class, () -> {
			CypherCompiler.compile(negQuery);
		});

		// 4. maxHops < minHops
		String invertedHops = "MATCH (a:Person)-[:FRIEND*5..3]->(b:Person) RETURN b";
		assertThrows(IllegalArgumentException.class, () -> {
			CypherParser.parse(invertedHops);
		});
	}

	@Test
	@DisplayName("StratificationValidator memoizes diamond DAG without exponential blowup")
	void testStratificationDiamondDagMemoization() {
		// Construct 30-level diamond DAG (2^30 paths would timeout without memoization)
		List<DependencyEdge> edges = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			edges.add(new DependencyEdge("D" + i, "A" + i, false));
			edges.add(new DependencyEdge("D" + i, "B" + i, false));
			edges.add(new DependencyEdge("A" + i, "D" + (i + 1), false));
			edges.add(new DependencyEdge("B" + i, "D" + (i + 1), false));
		}

		long start = System.currentTimeMillis();
		assertDoesNotThrow(() -> StratificationValidator.validate(edges));
		long elapsed = System.currentTimeMillis() - start;
		assertTrue(elapsed < 2000L, "Validation must finish in <2s (was " + elapsed + "ms)");

		// Negative cycle must still be detected and thrown
		edges.add(new DependencyEdge("D30", "D0", true)); // Negated back-edge forms negative cycle
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
			StratificationValidator.validate(edges);
		});
		assertTrue(ex.getMessage().contains("Non-stratifiable negation cycle"));
	}
}
