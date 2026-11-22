package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmLaneWalk;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmStreamFilter;
import org.impulsegraph.compiler.ast.ScmStreamProject;
import org.impulsegraph.compiler.ast.ScmVectorAnnSearch;
import org.impulsegraph.compiler.ast.ScmVectorFilter;
import org.impulsegraph.compiler.ast.ScmVectorRerankDot;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.ast.ScmWalk2Hop;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SchemaBindValidatorPass Stage 1 Build-Time Validation Tests")
class SchemaBindValidatorPassTest {

	private static SchemaView sampleSchema() {
		return SchemaView.builder()
				.domain("User", Map.of("age", "int32", "name", "string", "active", "bool", "embedding", "float32[]"))
				.domain("Wine",
						Map.of("vintage", "int32", "varietal", "string", "price", "float32", "embedding", "float32[]"))
				.domain("Cheese", Map.of("origin", "string", "aged_months", "int32"))
				.relation("PURCHASED", "User", "Wine", true, true, Map.of("rating", "float32", "qty", "int32"))
				.relation("PAIRED_WITH", "Wine", "Cheese", true, false, Map.of("score", "float32"))
				.relation("FOLLOWS", "User", "User", true, true, Map.of("since", "int64"))
				.virtualRelation("ANY_WINE_LINK", List.of("PURCHASED")).build();
	}

	@Test
	@DisplayName("Positive: multi-hop cross-domain traversal, CEL filters, edge shaders, projections, and vector search")
	void validMultiHopTraversalAndExpressions() {
		SchemaView schema = sampleSchema();

		ScmProgram program = ScmProgram.of(ScmVectorFilter.of(ScmCelExpr.of("node.age > 21 && node.active == true")),
				new ScmWalk("PURCHASED", -1, ScmWalk.Direction.FORWARD_CSR,
						List.of(new ScmStreamFilter(ScmCelExpr.of("edge.rating >= 4.5")),
								new ScmStreamProject(ScmCelExpr.of("state.total:SUM = src.age + edge.qty"))),
						List.of()),
				new ScmVectorRerankDot(new float[]{0.1f, 0.2f}, 0, "embedding", 0), ScmWalk.forward("PAIRED_WITH"),
				ScmVectorFilter.of(ScmCelExpr.of("node.aged_months >= 12 && node.origin == \"France\"")),
				ScmCollect.bitset());

		assertDoesNotThrow(() -> SchemaBindValidatorPass.validate(program, schema, "User"));

		// Also verify execution as a CompilerPass via CompilerContext (snapshot-free!)
		SchemaBindValidatorPass pass = new SchemaBindValidatorPass(schema, "User");
		CompilerContext ctx = new CompilerContext(null, CompilerOptions.DEFAULT, null);
		assertSame(program, ctx.executePass(pass, program));
	}

	@Test
	@DisplayName("Positive: 2-hop fused walk, reverse CSC walk on bidirectional relation, virtual relation, and GraphManifest adapter")
	void valid2HopReverseVirtualAndManifestAdapter() {
		SchemaView schema = sampleSchema();

		ScmProgram fusedAndReverse = ScmProgram.of(ScmWalk2Hop.of("PURCHASED", "PAIRED_WITH"),
				ScmVectorFilter.of(ScmCelExpr.of("node.aged_months > 6")));
		assertDoesNotThrow(() -> SchemaBindValidatorPass.validate(fusedAndReverse, schema, "User"));

		ScmProgram reverseFromWine = ScmProgram.of(ScmWalk.reverse("PURCHASED"), ScmLaneWalk.forward("FOLLOWS", false),
				new ScmVectorAnnSearch(new float[]{1.0f, 0.0f}, 10, 0, "embedding", false),
				ScmWalk.forward("ANY_WINE_LINK"));
		assertDoesNotThrow(() -> SchemaBindValidatorPass.validate(reverseFromWine, schema, "Wine"));

		// Verify SchemaView.fromGraphManifest works with existing GraphManifest record
		GraphManifest manifest = new GraphManifest("wine_graph", "1.0", Map.of(),
				Map.of("User", new GraphManifest.DomainDef("main", Map.of("age", "int32")), "Wine",
						new GraphManifest.DomainDef("main", Map.of("price", "float32"))),
				Map.of("PURCHASED", new GraphManifest.RelationDef("User", "Wine", "main", Map.of("rating", "float32"))),
				Map.of("ALL_PURCHASES", new GraphManifest.VirtualRelationDef(List.of("PURCHASED"))));

		SchemaView fromManifest = SchemaView.fromGraphManifest(manifest);
		ScmProgram manifestQuery = ScmProgram.of(ScmVectorFilter.of(ScmCelExpr.of("node.age >= 18")),
				ScmWalk.forward("ALL_PURCHASES"), ScmVectorFilter.of(ScmCelExpr.of("node.price < 100.0")));
		assertDoesNotThrow(() -> SchemaBindValidatorPass.validate(manifestQuery, fromManifest, "User"));
	}

	@Test
	@DisplayName("Negative: unknown anchor domain and unknown relation")
	void rejectUnknownDomainAndUnknownRelation() {
		SchemaView schema = sampleSchema();

		ScmProgram prog = ScmProgram.of(ScmWalk.forward("NON_EXISTENT_REL"));
		SchemaValidationException ex = assertThrows(SchemaValidationException.class,
				() -> SchemaBindValidatorPass.validate(prog, schema, "GhostDomain"));

		assertEquals(2, ex.violations().size());
		assertTrue(ex.violations().get(0).contains("GhostDomain"));
		assertTrue(ex.violations().get(1).contains("NON_EXISTENT_REL"));
	}

	@Test
	@DisplayName("Negative: wrong source domain after domain transition")
	void rejectWrongSourceDomainAfterTransition() {
		SchemaView schema = sampleSchema();

		// User -> PURCHASED -> Wine, then attempting FOLLOWS (which requires User)
		ScmProgram prog = ScmProgram.of(ScmWalk.forward("PURCHASED"), ScmWalk.forward("FOLLOWS"));

		SchemaValidationException ex = assertThrows(SchemaValidationException.class,
				() -> SchemaBindValidatorPass.validate(prog, schema, "User"));

		assertEquals(1, ex.violations().size());
		assertTrue(ex.violations().get(0).contains("FOLLOWS"));
		assertTrue(ex.violations().get(0).contains("User"));
		assertTrue(ex.violations().get(0).contains("Wine"));
	}

	@Test
	@DisplayName("Negative: reverse (CSC) walk on [out]-only relation")
	void rejectReverseWalkWithoutCscSupport() {
		SchemaView schema = sampleSchema();

		// PAIRED_WITH has hasInDirection = false
		ScmProgram prog = ScmProgram.of(ScmWalk.reverse("PAIRED_WITH"));

		SchemaValidationException ex = assertThrows(SchemaValidationException.class,
				() -> SchemaBindValidatorPass.validate(prog, schema, "Cheese"));

		assertEquals(1, ex.violations().size());
		assertTrue(ex.violations().get(0).contains("PAIRED_WITH"));
		assertTrue(ex.violations().get(0).contains("reverse"));
	}

	@Test
	@DisplayName("Negative: unknown node.* and edge.* attributes, type mismatches, and multi-violation collection")
	void collectAllAttributeAndTypeViolations() {
		SchemaView schema = sampleSchema();

		ScmProgram prog = ScmProgram.of(
				// 1. Unknown node attribute 'salary' on User
				// 2. Type mismatch: numeric comparison on string 'node.name > 5'
				ScmVectorFilter.of(ScmCelExpr.of("node.salary > 100 && node.name > 5")),
				// 3. Vector search on non-vector string column 'name'
				new ScmVectorAnnSearch(new float[]{0.5f, 0.5f}, 5, 0, "name", false),
				// 4. Unknown edge attribute 'discount' on PURCHASED
				new ScmWalk("PURCHASED", -1, ScmWalk.Direction.FORWARD_CSR,
						List.of(new ScmStreamFilter(ScmCelExpr.of("edge.discount > 0.1"))), List.of()),
				// 5. edge.* referenced outside walk context
				ScmVectorFilter.of(ScmCelExpr.of("edge.rating > 3.0")));

		SchemaValidationException ex = assertThrows(SchemaValidationException.class,
				() -> SchemaBindValidatorPass.validate(prog, schema, "User"));

		assertEquals(5, ex.violations().size(), () -> "Expected 5 violations, got: " + ex.violations());
		assertTrue(ex.violations().stream().anyMatch(v -> v.contains("node.salary")));
		assertTrue(ex.violations().stream().anyMatch(v -> v.contains("Type mismatch") && v.contains(">")));
		assertTrue(ex.violations().stream().anyMatch(v -> v.contains("float vector")));
		assertTrue(ex.violations().stream().anyMatch(v -> v.contains("edge.discount")));
		assertTrue(ex.violations().stream().anyMatch(v -> v.contains("outside of a relation walk")));
	}
}
