package org.impulsegraph.compiler.cypher;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.impulsegraph.compiler.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated unit tests for the openCypher compiler suite: CypherCompiler,
 * CypherLexer, CypherToken, CypherTokenType, and CypherParser.
 */
@DisplayName("Cypher Lexer, Parser, and Compiler Test Suite")
public class CypherLexerAndParserTest {

	// =========================================================================
	// 1. CypherTokenType
	// =========================================================================
	@Nested
	@DisplayName("1. CypherTokenType Tests")
	class CypherTokenTypeTests {

		@Test
		@DisplayName("Enum: Verifies all token types exist and match valueOf")
		void testAllEnumConstants() {
			for (CypherTokenType type : CypherTokenType.values()) {
				assertNotNull(type);
				assertEquals(type, CypherTokenType.valueOf(type.name()));
			}
			assertThat(CypherTokenType.values()).contains(CypherTokenType.KW_MATCH, CypherTokenType.KW_WHERE,
					CypherTokenType.KW_RETURN, CypherTokenType.KW_AND, CypherTokenType.KW_OR, CypherTokenType.KW_NOT,
					CypherTokenType.KW_COUNT, CypherTokenType.LPAREN, CypherTokenType.RPAREN, CypherTokenType.LBRACKET,
					CypherTokenType.RBRACKET, CypherTokenType.COLON, CypherTokenType.COMMA, CypherTokenType.DOT,
					CypherTokenType.PIPE, CypherTokenType.DASH, CypherTokenType.ARROW_RIGHT, CypherTokenType.ARROW_LEFT,
					CypherTokenType.STAR, CypherTokenType.EQ, CypherTokenType.EQ_EQ, CypherTokenType.NEQ,
					CypherTokenType.LT, CypherTokenType.LTE, CypherTokenType.GT, CypherTokenType.GTE,
					CypherTokenType.IDENTIFIER, CypherTokenType.PARAM, CypherTokenType.STRING_LITERAL,
					CypherTokenType.NUMBER_LITERAL, CypherTokenType.EOF, CypherTokenType.ERROR);
		}
	}

	// =========================================================================
	// 2. CypherToken
	// =========================================================================
	@Nested
	@DisplayName("2. CypherToken Tests")
	class CypherTokenTests {

		@Test
		@DisplayName("Record Contract: Verifies EqualsVerifier contract")
		void testEqualsVerifier() {
			EqualsVerifier.forClass(CypherToken.class).verify();
		}

		@Test
		@DisplayName("Factory & Accessors: Verifies of() constructor and field getters")
		void testFactoryAndGetters() {
			CypherToken token = CypherToken.of(CypherTokenType.KW_MATCH, "MATCH", 0, 1, 1);
			assertEquals(CypherTokenType.KW_MATCH, token.type());
			assertEquals("MATCH", token.lexeme());
			assertEquals(0, token.startPos());
			assertEquals(1, token.line());
			assertEquals(1, token.column());
			assertThat(token.toString()).contains("MATCH");
		}

		@Test
		@DisplayName("Equality: Value equality and hashCode behavior")
		void testEquality() {
			CypherToken t1 = new CypherToken(CypherTokenType.IDENTIFIER, "user", 5, 1, 6);
			CypherToken t2 = new CypherToken(CypherTokenType.IDENTIFIER, "user", 5, 1, 6);
			CypherToken t3 = new CypherToken(CypherTokenType.IDENTIFIER, "group", 5, 1, 6);

			assertThat(t1).isEqualTo(t2).hasSameHashCodeAs(t2);
			assertThat(t1).isNotEqualTo(t3);
			assertThat(t1).isNotEqualTo(null);
			assertThat(t1).isNotEqualTo("other");
		}
	}

	// =========================================================================
	// 3. CypherLexer
	// =========================================================================
	@Nested
	@DisplayName("3. CypherLexer Tests")
	class CypherLexerTests {

		@Test
		@DisplayName("Validation: Null source query throws NullPointerException")
		void testNullSourceThrows() {
			assertThatThrownBy(() -> new CypherLexer(null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("source must not be null");
		}

		@Test
		@DisplayName("Keywords: Case-insensitive scanning for MATCH, WHERE, RETURN, AND, OR, NOT, COUNT")
		void testKeywords() {
			String src = "MATCH match Where WHERE RETURN return AND and OR or NOT not COUNT count";
			CypherLexer lexer = new CypherLexer(src);

			assertEquals(CypherTokenType.KW_MATCH, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_MATCH, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_WHERE, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_WHERE, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_RETURN, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_RETURN, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_AND, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_AND, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_OR, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_OR, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_NOT, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_NOT, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_COUNT, lexer.nextToken().type());
			assertEquals(CypherTokenType.KW_COUNT, lexer.nextToken().type());
			assertEquals(CypherTokenType.EOF, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Punctuation & Arrows: Lexes brackets, colons, stars, and directed arrows")
		void testPunctuationAndArrows() {
			String src = "( ) [ ] : , . | * -> <- -";
			CypherLexer lexer = new CypherLexer(src);

			assertEquals(CypherTokenType.LPAREN, lexer.nextToken().type());
			assertEquals(CypherTokenType.RPAREN, lexer.nextToken().type());
			assertEquals(CypherTokenType.LBRACKET, lexer.nextToken().type());
			assertEquals(CypherTokenType.RBRACKET, lexer.nextToken().type());
			assertEquals(CypherTokenType.COLON, lexer.nextToken().type());
			assertEquals(CypherTokenType.COMMA, lexer.nextToken().type());
			assertEquals(CypherTokenType.DOT, lexer.nextToken().type());
			assertEquals(CypherTokenType.PIPE, lexer.nextToken().type());
			assertEquals(CypherTokenType.STAR, lexer.nextToken().type());
			assertEquals(CypherTokenType.ARROW_RIGHT, lexer.nextToken().type());
			assertEquals(CypherTokenType.ARROW_LEFT, lexer.nextToken().type());
			assertEquals(CypherTokenType.DASH, lexer.nextToken().type());
			assertEquals(CypherTokenType.EOF, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Operators: Lexes comparison operators =, ==, !=, <>, <, <=, >, >=")
		void testOperators() {
			String src = "= == != <> < <= > >=";
			CypherLexer lexer = new CypherLexer(src);

			assertEquals(CypherTokenType.EQ, lexer.nextToken().type());
			assertEquals(CypherTokenType.EQ_EQ, lexer.nextToken().type());
			assertEquals(CypherTokenType.NEQ, lexer.nextToken().type());
			assertEquals(CypherTokenType.NEQ, lexer.nextToken().type());
			assertEquals(CypherTokenType.LT, lexer.nextToken().type());
			assertEquals(CypherTokenType.LTE, lexer.nextToken().type());
			assertEquals(CypherTokenType.GT, lexer.nextToken().type());
			assertEquals(CypherTokenType.GTE, lexer.nextToken().type());
			assertEquals(CypherTokenType.EOF, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Literals: Lexes parameters, numbers, strings, and backticked identifiers")
		void testLiteralsAndIdentifiers() {
			String src = "$seed $param1 42 3.14 'single' \"double\" `escaped label` regularIdent";
			CypherLexer lexer = new CypherLexer(src);

			CypherToken p1 = lexer.nextToken();
			assertEquals(CypherTokenType.PARAM, p1.type());
			assertEquals("$seed", p1.lexeme());

			CypherToken p2 = lexer.nextToken();
			assertEquals(CypherTokenType.PARAM, p2.type());
			assertEquals("$param1", p2.lexeme());

			CypherToken numInt = lexer.nextToken();
			assertEquals(CypherTokenType.NUMBER_LITERAL, numInt.type());
			assertEquals("42", numInt.lexeme());

			CypherToken numFloat = lexer.nextToken();
			assertEquals(CypherTokenType.NUMBER_LITERAL, numFloat.type());
			assertEquals("3.14", numFloat.lexeme());

			CypherToken str1 = lexer.nextToken();
			assertEquals(CypherTokenType.STRING_LITERAL, str1.type());
			assertEquals("single", str1.lexeme());

			CypherToken str2 = lexer.nextToken();
			assertEquals(CypherTokenType.STRING_LITERAL, str2.type());
			assertEquals("double", str2.lexeme());

			CypherToken backtick = lexer.nextToken();
			assertEquals(CypherTokenType.IDENTIFIER, backtick.type());
			assertEquals("escaped label", backtick.lexeme());

			CypherToken ident = lexer.nextToken();
			assertEquals(CypherTokenType.IDENTIFIER, ident.type());
			assertEquals("regularIdent", ident.lexeme());

			assertEquals(CypherTokenType.EOF, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Errors: Handles unterminated string/backtick and unexpected characters")
		void testLexerErrorHandling() {
			// Unterminated string
			CypherLexer lex1 = new CypherLexer("'unterminated");
			assertEquals(CypherTokenType.ERROR, lex1.nextToken().type());

			// Unterminated backtick
			CypherLexer lex2 = new CypherLexer("`unterminated");
			assertEquals(CypherTokenType.ERROR, lex2.nextToken().type());

			// Unexpected '!' without '='
			CypherLexer lex3 = new CypherLexer("! ");
			assertEquals(CypherTokenType.ERROR, lex3.nextToken().type());

			// Unknown character
			CypherLexer lex4 = new CypherLexer("@unknown");
			assertEquals(CypherTokenType.ERROR, lex4.nextToken().type());
		}

		@Test
		@DisplayName("Line/Col: Accurately tracks lines, columns, and whitespace comments")
		void testLineAndColumnTracking() {
			String src = "// line 1 comment\n MATCH \n  (u)";
			CypherLexer lexer = new CypherLexer(src);

			CypherToken matchTok = lexer.nextToken();
			assertEquals(CypherTokenType.KW_MATCH, matchTok.type());
			assertEquals(2, matchTok.line());

			CypherToken lparen = lexer.nextToken();
			assertEquals(CypherTokenType.LPAREN, lparen.type());
			assertEquals(3, lparen.line());
			assertEquals(4, lparen.column());
		}
	}

	// =========================================================================
	// 4. CypherParser
	// =========================================================================
	@Nested
	@DisplayName("4. CypherParser Tests")
	class CypherParserTests {

		@Test
		@DisplayName("Basic Pattern: Parses 1-hop outgoing relationship query")
		void testBasicOutgoingPattern() {
			String query = "MATCH (u:User)-[:FRIEND]->(f:User) RETURN f";
			CypherParser.CypherQuery q = CypherParser.parse(query);

			assertNotNull(q);
			assertEquals("u", q.path().startNode().variable());
			assertEquals("User", q.path().startNode().label());

			assertEquals(1, q.path().steps().size());
			CypherParser.PathStep step = q.path().steps().get(0);
			assertTrue(step.edge().isForward());
			assertEquals("FRIEND", step.edge().relationName());
			assertEquals(1, step.edge().minHops());
			assertEquals(1, step.edge().maxHops());

			assertEquals("f", step.targetNode().variable());
			assertEquals("User", step.targetNode().label());

			assertFalse(q.projection().isCount());
			assertEquals("f", q.projection().variable());
		}

		@Test
		@DisplayName("Incoming Pattern: Parses 1-hop incoming relationship query")
		void testIncomingPattern() {
			String query = "MATCH (u:User)<-[:FOLLOWS]-(f:User) RETURN count(f)";
			CypherParser.CypherQuery q = CypherParser.parse(query);

			assertEquals(1, q.path().steps().size());
			CypherParser.PathStep step = q.path().steps().get(0);
			assertFalse(step.edge().isForward());
			assertEquals("FOLLOWS", step.edge().relationName());

			assertTrue(q.projection().isCount());
			assertEquals("f", q.projection().variable());
		}

		@Test
		@DisplayName("Variable-Length Hops: Parses bounded hop expressions *N, *min..max, *..max")
		void testVariableLengthHops() {
			// Exact hop *2
			CypherParser.CypherQuery q1 = CypherParser.parse("MATCH (u)-[:KNOWS*2]->(v) RETURN v");
			assertEquals(2, q1.path().steps().get(0).edge().minHops());
			assertEquals(2, q1.path().steps().get(0).edge().maxHops());

			// Range *1..4
			CypherParser.CypherQuery q2 = CypherParser.parse("MATCH (u)-[:KNOWS*1..4]->(v) RETURN v");
			assertEquals(1, q2.path().steps().get(0).edge().minHops());
			assertEquals(4, q2.path().steps().get(0).edge().maxHops());

			// Upper-bound only *..5
			CypherParser.CypherQuery q3 = CypherParser.parse("MATCH (u)-[:KNOWS*..5]->(v) RETURN v");
			assertEquals(1, q3.path().steps().get(0).edge().minHops());
			assertEquals(5, q3.path().steps().get(0).edge().maxHops());
		}

		@Test
		@DisplayName("WHERE Clause: Parses single and compound WHERE predicates")
		void testWhereClauseParsing() {
			String query = "MATCH (u:User)-[r:RATED]->(m:Movie) WHERE u.id == $seed AND r.stars >= 4 AND m.year < 2020 RETURN m";
			CypherParser.CypherQuery q = CypherParser.parse(query);

			assertEquals(3, q.wherePredicates().size());

			CypherParser.WherePredicate p1 = q.wherePredicates().get(0);
			assertEquals("u", p1.targetVar());
			assertEquals("id", p1.field());
			assertEquals("==", p1.op());
			assertEquals("$seed", p1.valueOrParam());

			CypherParser.WherePredicate p2 = q.wherePredicates().get(1);
			assertEquals("r", p2.targetVar());
			assertEquals("stars", p2.field());
			assertEquals(">=", p2.op());
			assertEquals("4", p2.valueOrParam());

			CypherParser.WherePredicate p3 = q.wherePredicates().get(2);
			assertEquals("m", p3.targetVar());
			assertEquals("year", p3.field());
			assertEquals("<", p3.op());
			assertEquals("2020", p3.valueOrParam());
		}

		@Test
		@DisplayName("Enforcement: Enforces Typed Edge Walk Mandate and Unbounded Traversal Mandate")
		void testParserMandateEnforcements() {
			// 1. Missing MATCH keyword
			assertThatThrownBy(() -> CypherParser.parse("(u)-[:REL]->(v) RETURN v"))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Expected 'MATCH' keyword");

			// 2. Missing relationship type (Typed Edge Walk Mandate)
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[]->(v) RETURN v"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Typed Edge Walk Mandate: Relationship type is mandatory");

			// 3. Unbounded wildcard * without upper bound (Unbounded Traversal Mandate)
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[:KNOWS*]->(v) RETURN v"))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(
							"Unbounded Traversal Mandate: Unbounded wildcard '*' without upper bound is not permitted");

			// 4. Incomplete variable length *1.. without upper bound
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[:KNOWS*1..]->(v) RETURN v"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Unbounded Traversal Mandate: Upper bound is required");

			// 5. Incomplete variable length *.. without upper bound
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[:KNOWS*..]->(v) RETURN v"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Unbounded Traversal Mandate: Upper bound is required");

			// 6. Missing RETURN clause
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[:REL]->(v)"))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Expected 'RETURN' clause");

			// 7. Invalid return target
			assertThatThrownBy(() -> CypherParser.parse("MATCH (u)-[:REL]->(v) RETURN"))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Expected return target variable or count(var)");
		}

		@Test
		@DisplayName("Records: Verifies data record components")
		void testRecordComponents() {
			CypherParser.NodePattern np = new CypherParser.NodePattern("u", "User");
			assertEquals("u", np.variable());
			assertEquals("User", np.label());

			CypherParser.EdgePattern ep = new CypherParser.EdgePattern("r", "REL", true, 1, 3, null);
			assertEquals("r", ep.variable());
			assertEquals("REL", ep.relationName());
			assertTrue(ep.isForward());
			assertEquals(1, ep.minHops());
			assertEquals(3, ep.maxHops());

			CypherParser.PathStep ps = new CypherParser.PathStep(ep, np);
			assertEquals(ep, ps.edge());
			assertEquals(np, ps.targetNode());
		}
	}

	// =========================================================================
	// 5. CypherCompiler
	// =========================================================================
	@Nested
	@DisplayName("5. CypherCompiler Tests")
	class CypherCompilerTests {

		@Test
		@DisplayName("Validation: Null query string throws NullPointerException")
		void testNullQueryThrows() {
			assertThatThrownBy(() -> CypherCompiler.compile((String) null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("cypherQuery must not be null");
		}

		@Test
		@DisplayName("Compilation: Lowers basic MATCH to ScmProgram with ScmWalk and ScmCollect")
		void testBasicCompilation() {
			String cypher = "MATCH (u:User)-[:FRIEND]->(f:User) RETURN f";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			assertNotNull(result);
			assertEquals("u", result.seedVariable());
			assertNull(result.seedParameterOrValue());

			ScmProgram prog = result.ast();
			assertNotNull(prog);
			assertEquals(2, prog.steps().size());

			assertThat(prog.steps().get(0)).isInstanceOf(ScmWalk.class);
			ScmWalk walk = (ScmWalk) prog.steps().get(0);
			assertEquals("FRIEND", walk.relationName());
			assertEquals(ScmWalk.Direction.FORWARD_CSR, walk.direction());

			assertThat(prog.steps().get(1)).isInstanceOf(ScmCollect.class);
			assertEquals(ScmCollect.Format.BITSET, ((ScmCollect) prog.steps().get(1)).format());
		}

		@Test
		@DisplayName("Seed Binding: Extracts seed node parameter from WHERE clause")
		void testSeedParameterExtraction() {
			String cypher = "MATCH (u:User)-[:FRIEND]->(f:User) WHERE u.id == $startNode RETURN f";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			assertEquals("u", result.seedVariable());
			assertEquals("$startNode", result.seedParameterOrValue());
		}

		@Test
		@DisplayName("Edge Predicates: Attaches edge filter expressions into ScmWalk shader")
		void testEdgePredicateLowering() {
			String cypher = "MATCH (u:User)-[r:RATED]->(m:Movie) WHERE r.stars >= 4 RETURN m";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			ScmProgram prog = result.ast();
			ScmWalk walk = (ScmWalk) prog.steps().get(0);

			assertEquals("RATED", walk.relationName());
			assertEquals(1, walk.shaderSteps().size());
			assertThat(walk.shaderSteps().get(0)).isInstanceOf(ScmCelExpr.class);
			ScmCelExpr cel = (ScmCelExpr) walk.shaderSteps().get(0);
			assertThat(cel.rawText()).contains("edge.stars >= 4");
		}

		@Test
		@DisplayName("Multi-Hop Expansion: Unrolls variable-length hops into multiple ScmWalk steps")
		void testMultiHopExpansion() {
			String cypher = "MATCH (u:User)-[:KNOWS*3]->(f:User) RETURN f";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			ScmProgram prog = result.ast();
			// 3 walk steps + 1 collect step = 4 steps
			assertEquals(4, prog.steps().size());
			for (int i = 0; i < 3; i++) {
				assertThat(prog.steps().get(i)).isInstanceOf(ScmWalk.class);
				assertEquals("KNOWS", ((ScmWalk) prog.steps().get(i)).relationName());
			}
			assertThat(prog.steps().get(3)).isInstanceOf(ScmCollect.class);
		}

		@Test
		@DisplayName("Reverse Walks: Compiles incoming relationships into REVERSE_CSC walks")
		void testReverseWalkCompilation() {
			String cypher = "MATCH (u:User)<-[:FOLLOWS]-(f:User) RETURN f";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			ScmProgram prog = result.ast();
			ScmWalk walk = (ScmWalk) prog.steps().get(0);
			assertEquals("FOLLOWS", walk.relationName());
			assertEquals(ScmWalk.Direction.REVERSE_CSC, walk.direction());
		}

		@Test
		@DisplayName("Projections: RETURN count(v) lowers to SCALAR collection")
		void testCountProjection() {
			String cypher = "MATCH (u:User)-[:FRIEND]->(f:User) RETURN count(f)";
			CypherCompiler.CompilationResult result = CypherCompiler.compile(cypher);

			ScmProgram prog = result.ast();
			ScmCollect col = (ScmCollect) prog.steps().get(1);
			assertEquals(ScmCollect.Format.SCALAR, col.format());
		}
	}
}
