package org.impulsegraph.compiler.cel;

import nl.jqno.equalsverifier.EqualsVerifier;
import org.impulsegraph.api.traversal.Reducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolated unit tests for CEL compiler suite: CelCompiler, CelLexer, CelToken,
 * CelTokenType, CelMathFunctions, ProjectionValidator, and ProjectionAstNode.
 */
@DisplayName("CEL Lexer, Compiler, and Math Functions Test Suite")
public class CelLexerAndMathTest {

	// =========================================================================
	// 1. CelTokenType
	// =========================================================================
	@Nested
	@DisplayName("1. CelTokenType Tests")
	class CelTokenTypeTests {

		@Test
		@DisplayName("Enum: Verifies all token types exist and can be resolved by name")
		void testAllEnumConstants() {
			for (CelTokenType type : CelTokenType.values()) {
				assertNotNull(type);
				assertEquals(type, CelTokenType.valueOf(type.name()));
			}
			assertThat(CelTokenType.values()).contains(CelTokenType.END_OF_FILE, CelTokenType.IDENTIFIER,
					CelTokenType.PARAMETER_REF, CelTokenType.INT_LITERAL, CelTokenType.FLOAT_LITERAL,
					CelTokenType.STRING_LITERAL, CelTokenType.BOOL_LITERAL, CelTokenType.PLUS, CelTokenType.MINUS,
					CelTokenType.STAR, CelTokenType.SLASH, CelTokenType.PERCENT, CelTokenType.EQ_EQ,
					CelTokenType.BANG_EQ, CelTokenType.LT, CelTokenType.LT_EQ, CelTokenType.GT, CelTokenType.GT_EQ,
					CelTokenType.AMP_AMP, CelTokenType.PIPE_PIPE, CelTokenType.BANG, CelTokenType.QUESTION,
					CelTokenType.COLON, CelTokenType.DOT, CelTokenType.COMMA, CelTokenType.LPAREN, CelTokenType.RPAREN,
					CelTokenType.LBRACKET, CelTokenType.RBRACKET, CelTokenType.LBRACE, CelTokenType.RBRACE,
					CelTokenType.KW_IN, CelTokenType.KW_AS);
		}
	}

	// =========================================================================
	// 2. CelToken
	// =========================================================================
	@Nested
	@DisplayName("2. CelToken Tests")
	class CelTokenTests {

		@Test
		@DisplayName("Record Contract: Verifies EqualsVerifier contract")
		void testEqualsVerifier() {
			EqualsVerifier.forClass(CelToken.class).verify();
		}

		@Test
		@DisplayName("Factories & Accessors: Verifies static constructors and getters")
		void testTokenFactoriesAndGetters() {
			CelToken tGeneral = CelToken.of(CelTokenType.PLUS, "+", 5);
			assertEquals(CelTokenType.PLUS, tGeneral.type());
			assertEquals("+", tGeneral.text());
			assertEquals(0, tGeneral.intVal());
			assertEquals(0.0, tGeneral.floatVal());
			assertFalse(tGeneral.boolVal());
			assertEquals(5, tGeneral.pos());

			CelToken tInt = CelToken.ofInt(42, "42", 10);
			assertEquals(CelTokenType.INT_LITERAL, tInt.type());
			assertEquals("42", tInt.text());
			assertEquals(42, tInt.intVal());
			assertEquals(10, tInt.pos());

			CelToken tFloat = CelToken.ofFloat(3.14, "3.14", 15);
			assertEquals(CelTokenType.FLOAT_LITERAL, tFloat.type());
			assertEquals("3.14", tFloat.text());
			assertEquals(3.14, tFloat.floatVal(), 1e-6);

			CelToken tBool = CelToken.ofBool(true, "true", 20);
			assertEquals(CelTokenType.BOOL_LITERAL, tBool.type());
			assertEquals("true", tBool.text());
			assertTrue(tBool.boolVal());

			CelToken tString = CelToken.ofString("hello", 25);
			assertEquals(CelTokenType.STRING_LITERAL, tString.type());
			assertEquals("hello", tString.text());
		}

		@Test
		@DisplayName("Equality: Value equality and hashCode behavior")
		void testTokenEquality() {
			CelToken t1 = CelToken.ofInt(10, "10", 0);
			CelToken t2 = CelToken.ofInt(10, "10", 0);
			CelToken t3 = CelToken.ofInt(20, "20", 0);

			assertThat(t1).isEqualTo(t2).hasSameHashCodeAs(t2);
			assertThat(t1).isNotEqualTo(t3);
			assertThat(t1).isNotEqualTo(null);
			assertThat(t1).isNotEqualTo("otherString");
			assertThat(t1.toString()).contains("10");
		}
	}

	// =========================================================================
	// 3. CelLexer
	// =========================================================================
	@Nested
	@DisplayName("3. CelLexer Tests")
	class CelLexerTests {

		@Test
		@DisplayName("Validation: Null source throws NullPointerException")
		void testNullSourceThrows() {
			assertThatThrownBy(() -> new CelLexer(null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("source must not be null");
		}

		@Test
		@DisplayName("Operators: Lexes all single-char punctuation and operators")
		void testSingleCharOperators() {
			String src = "+ - * / % ? : . , ( ) [ ] { }";
			CelLexer lexer = new CelLexer(src);

			assertEquals(CelTokenType.PLUS, lexer.nextToken().type());
			assertEquals(CelTokenType.MINUS, lexer.nextToken().type());
			assertEquals(CelTokenType.STAR, lexer.nextToken().type());
			assertEquals(CelTokenType.SLASH, lexer.nextToken().type());
			assertEquals(CelTokenType.PERCENT, lexer.nextToken().type());
			assertEquals(CelTokenType.QUESTION, lexer.nextToken().type());
			assertEquals(CelTokenType.COLON, lexer.nextToken().type());
			assertEquals(CelTokenType.DOT, lexer.nextToken().type());
			assertEquals(CelTokenType.COMMA, lexer.nextToken().type());
			assertEquals(CelTokenType.LPAREN, lexer.nextToken().type());
			assertEquals(CelTokenType.RPAREN, lexer.nextToken().type());
			assertEquals(CelTokenType.LBRACKET, lexer.nextToken().type());
			assertEquals(CelTokenType.RBRACKET, lexer.nextToken().type());
			assertEquals(CelTokenType.LBRACE, lexer.nextToken().type());
			assertEquals(CelTokenType.RBRACE, lexer.nextToken().type());
			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Operators: Two-character comparisons and boolean operators")
		void testTwoCharOperators() {
			String src = "== != <= < >= > && || ! = & |";
			CelLexer lexer = new CelLexer(src);

			assertEquals(CelTokenType.EQ_EQ, lexer.nextToken().type());
			assertEquals(CelTokenType.BANG_EQ, lexer.nextToken().type());
			assertEquals(CelTokenType.LT_EQ, lexer.nextToken().type());
			assertEquals(CelTokenType.LT, lexer.nextToken().type());
			assertEquals(CelTokenType.GT_EQ, lexer.nextToken().type());
			assertEquals(CelTokenType.GT, lexer.nextToken().type());
			assertEquals(CelTokenType.AMP_AMP, lexer.nextToken().type());
			assertEquals(CelTokenType.PIPE_PIPE, lexer.nextToken().type());
			assertEquals(CelTokenType.BANG, lexer.nextToken().type());
			// Single = or & or | yields END_OF_FILE fallback token with the character as
			// text
			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Parameters: Lexes parameter references starting with @")
		void testParameterReferences() {
			String src = "@p1 @threshold @min_voltage_99";
			CelLexer lexer = new CelLexer(src);

			CelToken t1 = lexer.nextToken();
			assertEquals(CelTokenType.PARAMETER_REF, t1.type());
			assertEquals("@p1", t1.text());

			CelToken t2 = lexer.nextToken();
			assertEquals(CelTokenType.PARAMETER_REF, t2.type());
			assertEquals("@threshold", t2.text());

			CelToken t3 = lexer.nextToken();
			assertEquals(CelTokenType.PARAMETER_REF, t3.type());
			assertEquals("@min_voltage_99", t3.text());

			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("String Literals: Handles single/double quotes and escape sequences")
		void testStringLiteralsAndEscapes() {
			String src = "\"hello world\" 'single quote' \"with\\nnewline\\ttab\\\\backslash\\\"quote\\\'single\\zcustom\"";
			CelLexer lexer = new CelLexer(src);

			CelToken s1 = lexer.nextToken();
			assertEquals(CelTokenType.STRING_LITERAL, s1.type());
			assertEquals("hello world", s1.text());

			CelToken s2 = lexer.nextToken();
			assertEquals(CelTokenType.STRING_LITERAL, s2.type());
			assertEquals("single quote", s2.text());

			CelToken s3 = lexer.nextToken();
			assertEquals(CelTokenType.STRING_LITERAL, s3.type());
			assertEquals("with\nnewline\ttab\\backslash\"quote'singlezcustom", s3.text());

			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Numbers: Lexes integers, floating point decimals, and scientific notation")
		void testNumberLiterals() {
			String src = "0 42 1000000 3.14 0.5 1e5 2.5E-3 4e+2";
			CelLexer lexer = new CelLexer(src);

			CelToken i0 = lexer.nextToken();
			assertEquals(CelTokenType.INT_LITERAL, i0.type());
			assertEquals(0, i0.intVal());

			CelToken i42 = lexer.nextToken();
			assertEquals(CelTokenType.INT_LITERAL, i42.type());
			assertEquals(42, i42.intVal());

			CelToken iMil = lexer.nextToken();
			assertEquals(CelTokenType.INT_LITERAL, iMil.type());
			assertEquals(1000000, iMil.intVal());

			CelToken fPi = lexer.nextToken();
			assertEquals(CelTokenType.FLOAT_LITERAL, fPi.type());
			assertEquals(3.14, fPi.floatVal(), 1e-6);

			CelToken fHalf = lexer.nextToken();
			assertEquals(CelTokenType.FLOAT_LITERAL, fHalf.type());
			assertEquals(0.5, fHalf.floatVal(), 1e-6);

			CelToken fExp1 = lexer.nextToken();
			assertEquals(CelTokenType.FLOAT_LITERAL, fExp1.type());
			assertEquals(100000.0, fExp1.floatVal(), 1e-6);

			CelToken fExp2 = lexer.nextToken();
			assertEquals(CelTokenType.FLOAT_LITERAL, fExp2.type());
			assertEquals(0.0025, fExp2.floatVal(), 1e-6);

			CelToken fExp3 = lexer.nextToken();
			assertEquals(CelTokenType.FLOAT_LITERAL, fExp3.type());
			assertEquals(400.0, fExp3.floatVal(), 1e-6);

			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Keywords & Identifiers: Lexes booleans, in, as, and general identifiers")
		void testKeywordsAndIdentifiers() {
			String src = "true false in as node user_name _hiddenId";
			CelLexer lexer = new CelLexer(src);

			CelToken bTrue = lexer.nextToken();
			assertEquals(CelTokenType.BOOL_LITERAL, bTrue.type());
			assertTrue(bTrue.boolVal());

			CelToken bFalse = lexer.nextToken();
			assertEquals(CelTokenType.BOOL_LITERAL, bFalse.type());
			assertFalse(bFalse.boolVal());

			CelToken kwIn = lexer.nextToken();
			assertEquals(CelTokenType.KW_IN, kwIn.type());

			CelToken kwAs = lexer.nextToken();
			assertEquals(CelTokenType.KW_AS, kwAs.type());

			CelToken id1 = lexer.nextToken();
			assertEquals(CelTokenType.IDENTIFIER, id1.type());
			assertEquals("node", id1.text());

			CelToken id2 = lexer.nextToken();
			assertEquals(CelTokenType.IDENTIFIER, id2.type());
			assertEquals("user_name", id2.text());

			CelToken id3 = lexer.nextToken();
			assertEquals(CelTokenType.IDENTIFIER, id3.type());
			assertEquals("_hiddenId", id3.text());

			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}

		@Test
		@DisplayName("Whitespace & Comments: Skips comments and multiple lines of whitespace")
		void testWhitespaceAndComments() {
			String src = "  \t \n // this is a line comment\n 42 // another comment\n 99";
			CelLexer lexer = new CelLexer(src);

			CelToken t1 = lexer.nextToken();
			assertEquals(CelTokenType.INT_LITERAL, t1.type());
			assertEquals(42, t1.intVal());

			CelToken t2 = lexer.nextToken();
			assertEquals(CelTokenType.INT_LITERAL, t2.type());
			assertEquals(99, t2.intVal());

			assertEquals(CelTokenType.END_OF_FILE, lexer.nextToken().type());
		}
	}

	// =========================================================================
	// 4. CelCompiler
	// =========================================================================
	@Nested
	@DisplayName("4. CelCompiler Tests")
	class CelCompilerTests {

		@Test
		@DisplayName("Edge Cases: Null, empty, and whitespace strings return '()'")
		void testEmptyAndNullSource() {
			assertEquals("()", CelCompiler.compileToImpScheme(null));
			assertEquals("()", CelCompiler.compileToImpScheme(""));
			assertEquals("()", CelCompiler.compileToImpScheme("   "));
			assertEquals("()", CelCompiler.toImpScheme(null));
		}

		@Test
		@DisplayName("Literals: Lowers int, float, bool, string, and parameters")
		void testLiteralLowering() {
			assertEquals("42", CelCompiler.toImpScheme(CelAstNode.makeInt(42)));
			assertEquals("3.14", CelCompiler.toImpScheme(CelAstNode.makeFloat(3.14)));
			assertEquals("10.0", CelCompiler.toImpScheme(CelAstNode.makeFloat(10.0)));
			assertEquals("#t", CelCompiler.toImpScheme(CelAstNode.makeBool(true)));
			assertEquals("#f", CelCompiler.toImpScheme(CelAstNode.makeBool(false)));
			assertEquals("\"testString\"", CelCompiler.toImpScheme(CelAstNode.makeString("testString")));
			assertEquals("myIdent", CelCompiler.toImpScheme(CelAstNode.makeIdent("myIdent")));
			assertEquals("@p1", CelCompiler.toImpScheme(CelAstNode.makeParam("@p1")));
		}

		@Test
		@DisplayName("MemberAccess: Differentiates id vs regular attribute in normal and stream modes")
		void testMemberAccessLowering() {
			CelAstNode idNode = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "id");
			CelAstNode altIdNode = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "_id");
			CelAstNode attrNode = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "age");

			// Normal mode
			assertEquals("(get-dense-id node)", CelCompiler.toImpScheme(idNode, false));
			assertEquals("(get-dense-id node)", CelCompiler.toImpScheme(altIdNode, false));
			assertEquals("(get-attr node \"age\")", CelCompiler.toImpScheme(attrNode, false));

			// Stream mode
			assertEquals("(stream-load-id node)", CelCompiler.toImpScheme(idNode, true));
			assertEquals("(stream-load-id node)", CelCompiler.toImpScheme(altIdNode, true));
			assertEquals("(stream-load-attr node \"age\")", CelCompiler.toImpScheme(attrNode, true));
		}

		@Test
		@DisplayName("Unary Operations: Lowers negation and logical NOT in normal and stream modes")
		void testUnaryLowering() {
			CelAstNode notNode = CelAstNode.makeUnary("!", CelAstNode.makeIdent("active"));
			CelAstNode negNode = CelAstNode.makeUnary("-", CelAstNode.makeIdent("score"));

			assertEquals("(mask-not active)", CelCompiler.toImpScheme(notNode, false));
			assertEquals("(- 0 score)", CelCompiler.toImpScheme(negNode, false));

			assertEquals("(stream-logic-not active)", CelCompiler.toImpScheme(notNode, true));
			assertEquals("(- 0 score)", CelCompiler.toImpScheme(negNode, true));
		}

		@Test
		@DisplayName("Binary Operations: Lowers comparisons, logic, and arithmetic in normal and stream modes")
		void testBinaryLowering() {
			CelAstNode lhs = CelAstNode.makeIdent("a");
			CelAstNode rhs = CelAstNode.makeIdent("b");

			// && and ||
			assertEquals("(mask-and a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("&&", lhs, rhs), false));
			assertEquals("(stream-logic-and a b)",
					CelCompiler.toImpScheme(CelAstNode.makeBinary("&&", lhs, rhs), true));

			assertEquals("(mask-or a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("||", lhs, rhs), false));
			assertEquals("(stream-logic-or a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("||", lhs, rhs), true));

			// Comparisons
			assertEquals("(vec-cmp-gt a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary(">", lhs, rhs), false));
			assertEquals("(stream-cmp-gt a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary(">", lhs, rhs), true));

			assertEquals("(vec-cmp-lt a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("<", lhs, rhs), false));
			assertEquals("(stream-cmp-lt a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("<", lhs, rhs), true));

			assertEquals("(vec-cmp-gte a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary(">=", lhs, rhs), false));
			assertEquals("(stream-cmp-gte a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary(">=", lhs, rhs), true));

			assertEquals("(<= a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("<=", lhs, rhs), false));
			assertEquals("(<= a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("<=", lhs, rhs), true));

			assertEquals("(vec-cmp-eq a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("==", lhs, rhs), false));
			assertEquals("(stream-cmp-eq a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("==", lhs, rhs), true));

			assertEquals("(mask-not (vec-cmp-eq a b))",
					CelCompiler.toImpScheme(CelAstNode.makeBinary("!=", lhs, rhs), false));
			assertEquals("(stream-cmp-neq a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("!=", lhs, rhs), true));

			// Arithmetic fallback
			assertEquals("(+ a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("+", lhs, rhs), false));
			assertEquals("(* a b)", CelCompiler.toImpScheme(CelAstNode.makeBinary("*", lhs, rhs), true));
		}

		@Test
		@DisplayName("Complex Structures: Ternary blend, function calls, and list literals")
		void testComplexStructuresLowering() {
			// Ternary
			CelAstNode ternary = CelAstNode.makeTernary(CelAstNode.makeIdent("cond"), CelAstNode.makeInt(1),
					CelAstNode.makeInt(0));
			assertEquals("(vec-blend cond 1 0)", CelCompiler.toImpScheme(ternary));

			// Function Call
			CelAstNode call = CelAstNode.makeCall("sin", List.of(CelAstNode.makeIdent("angle")));
			assertEquals("(sin angle)", CelCompiler.toImpScheme(call));

			// List Literal
			CelAstNode list = CelAstNode
					.makeList(List.of(CelAstNode.makeInt(1), CelAstNode.makeInt(2), CelAstNode.makeInt(3)));
			assertEquals("(list 1 2 3)", CelCompiler.toImpScheme(list));

			// Full compilation from raw CEL source
			String scm = CelCompiler.compileToImpScheme("node.age >= 21 && node.active == true");
			assertThat(scm).contains("vec-cmp-gte");
			assertThat(scm).contains("vec-cmp-eq");
			assertThat(scm).contains("mask-and");
		}
	}

	// =========================================================================
	// 5. CelMathFunctions
	// =========================================================================
	@Nested
	@DisplayName("5. CelMathFunctions Tests")
	class CelMathFunctionsTests {

		@Test
		@DisplayName("Constants: All 54 math functions have distinct positive integer codes")
		void testAllMathFunctionConstants() {
			assertEquals(1, CelMathFunctions.MATH_FUNC_ABS);
			assertEquals(2, CelMathFunctions.MATH_FUNC_SQRT);
			assertEquals(3, CelMathFunctions.MATH_FUNC_RSQRT);
			assertEquals(4, CelMathFunctions.MATH_FUNC_CBRT);
			assertEquals(5, CelMathFunctions.MATH_FUNC_POW);
			assertEquals(6, CelMathFunctions.MATH_FUNC_HYPOT);
			assertEquals(7, CelMathFunctions.MATH_FUNC_LERP);
			assertEquals(8, CelMathFunctions.MATH_FUNC_EXP);
			assertEquals(9, CelMathFunctions.MATH_FUNC_EXP2);
			assertEquals(10, CelMathFunctions.MATH_FUNC_EXP10);
			assertEquals(11, CelMathFunctions.MATH_FUNC_EXPM1);
			assertEquals(12, CelMathFunctions.MATH_FUNC_LOG);
			assertEquals(13, CelMathFunctions.MATH_FUNC_LOG2);
			assertEquals(14, CelMathFunctions.MATH_FUNC_LOG10);
			assertEquals(15, CelMathFunctions.MATH_FUNC_LOG1P);
			assertEquals(16, CelMathFunctions.MATH_FUNC_SIN);
			assertEquals(17, CelMathFunctions.MATH_FUNC_COS);
			assertEquals(18, CelMathFunctions.MATH_FUNC_TAN);
			assertEquals(19, CelMathFunctions.MATH_FUNC_ASIN);
			assertEquals(20, CelMathFunctions.MATH_FUNC_ACOS);
			assertEquals(21, CelMathFunctions.MATH_FUNC_ATAN);
			assertEquals(22, CelMathFunctions.MATH_FUNC_ATAN2);
			assertEquals(23, CelMathFunctions.MATH_FUNC_SINC);
			assertEquals(24, CelMathFunctions.MATH_FUNC_SINH);
			assertEquals(25, CelMathFunctions.MATH_FUNC_COSH);
			assertEquals(26, CelMathFunctions.MATH_FUNC_TANH);
			assertEquals(27, CelMathFunctions.MATH_FUNC_ASINH);
			assertEquals(28, CelMathFunctions.MATH_FUNC_ACOSH);
			assertEquals(29, CelMathFunctions.MATH_FUNC_ATANH);
			assertEquals(30, CelMathFunctions.MATH_FUNC_FLOOR);
			assertEquals(31, CelMathFunctions.MATH_FUNC_CEIL);
			assertEquals(32, CelMathFunctions.MATH_FUNC_TRUNC);
			assertEquals(33, CelMathFunctions.MATH_FUNC_ROUND);
			assertEquals(34, CelMathFunctions.MATH_FUNC_CLAMP);
			assertEquals(35, CelMathFunctions.MATH_FUNC_COPYSIGN);
			assertEquals(36, CelMathFunctions.MATH_FUNC_FMOD);
			assertEquals(37, CelMathFunctions.MATH_FUNC_RELU);
			assertEquals(38, CelMathFunctions.MATH_FUNC_LEAKY_RELU);
			assertEquals(39, CelMathFunctions.MATH_FUNC_SIGMOID);
			assertEquals(40, CelMathFunctions.MATH_FUNC_GELU);
			assertEquals(41, CelMathFunctions.MATH_FUNC_SILU);
			assertEquals(42, CelMathFunctions.MATH_FUNC_SOFTPLUS);
			assertEquals(43, CelMathFunctions.MATH_FUNC_ERF);
			assertEquals(44, CelMathFunctions.MATH_FUNC_ERFC);
			assertEquals(45, CelMathFunctions.MATH_FUNC_LGAMMA);
			assertEquals(46, CelMathFunctions.MATH_FUNC_POPCOUNT);
			assertEquals(47, CelMathFunctions.MATH_FUNC_CLZ);
			assertEquals(48, CelMathFunctions.MATH_FUNC_CTZ);
			assertEquals(49, CelMathFunctions.MATH_FUNC_ROTL);
			assertEquals(50, CelMathFunctions.MATH_FUNC_ROTR);
			assertEquals(51, CelMathFunctions.MATH_FUNC_SAFE_DIV);
			assertEquals(52, CelMathFunctions.MATH_FUNC_ISNAN);
			assertEquals(53, CelMathFunctions.MATH_FUNC_ISINF);
			assertEquals(54, CelMathFunctions.MATH_FUNC_ISFINITE);
		}

		@Test
		@DisplayName("Resolution: Resolves function aliases and edge cases")
		void testFunctionResolution() {
			assertEquals(-1, CelMathFunctions.resolveMathFunc(null));
			assertEquals(-1, CelMathFunctions.resolveMathFunc("nonexistent_math_func"));

			assertFalse(CelMathFunctions.isMathFunc(null));
			assertFalse(CelMathFunctions.isMathFunc("nonexistent_math_func"));

			// Check aliases
			assertEquals(CelMathFunctions.MATH_FUNC_SAFE_DIV, CelMathFunctions.resolveMathFunc("safeDiv"));
			assertEquals(CelMathFunctions.MATH_FUNC_SAFE_DIV, CelMathFunctions.resolveMathFunc("safe_div"));

			assertEquals(CelMathFunctions.MATH_FUNC_ISNAN, CelMathFunctions.resolveMathFunc("isNan"));
			assertEquals(CelMathFunctions.MATH_FUNC_ISNAN, CelMathFunctions.resolveMathFunc("isnan"));

			assertEquals(CelMathFunctions.MATH_FUNC_ISINF, CelMathFunctions.resolveMathFunc("isInf"));
			assertEquals(CelMathFunctions.MATH_FUNC_ISINF, CelMathFunctions.resolveMathFunc("isinf"));

			assertEquals(CelMathFunctions.MATH_FUNC_ISFINITE, CelMathFunctions.resolveMathFunc("isFinite"));
			assertEquals(CelMathFunctions.MATH_FUNC_ISFINITE, CelMathFunctions.resolveMathFunc("isfinite"));

			assertTrue(CelMathFunctions.isMathFunc("sin"));
			assertTrue(CelMathFunctions.isMathFunc("sigmoid"));
		}
	}

	// =========================================================================
	// 6. ProjectionValidator & ProjectionAstNode
	// =========================================================================
	@Nested
	@DisplayName("6. ProjectionValidator & ProjectionAstNode Tests")
	class ProjectionValidatorAndAstNodeTests {

		@Test
		@DisplayName("Validation: Non-ID expressions allow all reducers")
		void testNonIdExpressionAllowsAllReducers() {
			CelAstNode nonId = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "voltage");

			for (Reducer r : Reducer.values()) {
				ProjectionAstNode node = new ProjectionAstNode("voltage", r, null, nonId);
				assertDoesNotThrow(() -> ProjectionValidator.validate(node));
			}
		}

		@Test
		@DisplayName("Validation: Semantic IDs reject math/logic reducers SUM, AVG, COUNT, OR, AND")
		void testSemanticIdRejectsMathLogicReducers() {
			CelAstNode idMember = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "id");
			CelAstNode altIdMember = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "_id");
			CelAstNode idIdent = CelAstNode.makeIdent("id");
			CelAstNode altIdIdent = CelAstNode.makeIdent("_id");

			List<CelAstNode> idNodes = List.of(idMember, altIdMember, idIdent, altIdIdent);
			List<Reducer> invalidReducers = List.of(Reducer.SUM, Reducer.AVG, Reducer.COUNT, Reducer.OR, Reducer.AND);

			for (CelAstNode idNode : idNodes) {
				for (Reducer r : invalidReducers) {
					ProjectionAstNode node = new ProjectionAstNode("target", r, null, idNode);
					assertThatThrownBy(() -> ProjectionValidator.validate(node))
							.isInstanceOf(IllegalArgumentException.class)
							.hasMessageContaining("Semantic Error: Cannot apply math/logic reducer '" + r.name()
									+ "' to semantic ID expression");
				}
			}
		}

		@Test
		@DisplayName("Validation: Semantic IDs allow witness and determinism reducers MIN, MAX, ANY, ARGMIN, ARGMAX")
		void testSemanticIdAllowsWitnessReducers() {
			CelAstNode idNode = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "id");
			List<Reducer> validReducers = List.of(Reducer.MIN, Reducer.MAX, Reducer.ANY, Reducer.ARGMIN,
					Reducer.ARGMAX);

			for (Reducer r : validReducers) {
				ProjectionAstNode node = new ProjectionAstNode("target", r, null, idNode);
				assertDoesNotThrow(() -> ProjectionValidator.validate(node));
			}
		}

		@Test
		@DisplayName("AstNode: Constructor rejects null fields and verifies getters")
		void testProjectionAstNodeContract() {
			CelAstNode expr = CelAstNode.makeInt(1);

			assertThatThrownBy(() -> new ProjectionAstNode(null, Reducer.SUM, null, expr))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("targetStateField must not be null");

			assertThatThrownBy(() -> new ProjectionAstNode("f", null, null, expr))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("reducer must not be null");

			assertThatThrownBy(() -> new ProjectionAstNode("f", Reducer.SUM, null, null))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("rhsExpression must not be null");

			ProjectionAstNode node = new ProjectionAstNode("score", Reducer.MAX, "param", expr);
			assertEquals("score", node.targetStateField());
			assertEquals(Reducer.MAX, node.reducer());
			assertEquals("param", node.coReducerParam());
			assertEquals(expr, node.rhsExpression());

			ProjectionAstNode node2 = new ProjectionAstNode("score", Reducer.MAX, "param", expr);
			assertThat(node).isEqualTo(node2).hasSameHashCodeAs(node2);
		}
	}
}
