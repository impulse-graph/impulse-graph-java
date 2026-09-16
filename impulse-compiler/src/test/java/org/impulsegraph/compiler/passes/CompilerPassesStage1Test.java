package org.impulsegraph.compiler.passes;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature.IntervalBound;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.stage1.*;
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
 * Isolated unit tests for all 8 Stage 1 compiler optimization and validation
 * passes.
 */
@DisplayName("Compiler Passes - Stage 1 Test Suite")
public class CompilerPassesStage1Test {

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
	// 1. AlgebraicTypeInferencePass
	// =========================================================================
	@Nested
	@DisplayName("1. AlgebraicTypeInferencePass Tests")
	class AlgebraicTypeInferencePassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("AlgebraicTypeInferencePass", AlgebraicTypeInferencePass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(AlgebraicTypeInferencePass.INSTANCE.transform(null, ctx));
			assertNull(AlgebraicTypeInferencePass.INSTANCE.inferCel(null, null));
		}

		@Test
		@DisplayName("Literals: Infer constant signatures for int, float, bool")
		void testLiteralSignatures() {
			GraphStatistics stats = new GraphStatistics();

			CelAstNode iNode = CelAstNode.makeInt(42);
			CelAstNode infInt = AlgebraicTypeInferencePass.INSTANCE.inferCel(iNode, stats);
			assertNotNull(infInt.signature());
			assertTrue(infInt.signature().isConstantKnown());
			assertEquals(42, infInt.signature().constantIntVal());

			CelAstNode fNode = CelAstNode.makeFloat(3.14);
			CelAstNode infFloat = AlgebraicTypeInferencePass.INSTANCE.inferCel(fNode, stats);
			assertNotNull(infFloat.signature());
			assertTrue(infFloat.signature().isConstantKnown());
			assertEquals(3.14, infFloat.signature().constantFloatVal(), 1e-6);

			CelAstNode bNode = CelAstNode.makeBool(true);
			CelAstNode infBool = AlgebraicTypeInferencePass.INSTANCE.inferCel(bNode, stats);
			assertNotNull(infBool.signature());
			assertTrue(infBool.signature().isConstantKnown());
			assertTrue(infBool.signature().constantBoolVal());
		}

		@Test
		@DisplayName("MemberAccess & Identifiers: Bound against GraphStatistics")
		void testMemberAccessAndIdentifierInference() {
			GraphStatistics stats = new GraphStatistics();
			AttributeStatistics ageStats = new AttributeStatistics("age", 18, 90, 18.0, 90.0, "", "", 0, 72,
					Monotonicity.MONO_STRICT_INC, false);
			stats.putAttributeStatistics("age", ageStats);

			// Member access: node.age
			CelAstNode member = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "age");
			CelAstNode infMember = AlgebraicTypeInferencePass.INSTANCE.inferCel(member, stats);
			assertNotNull(infMember.signature());
			assertEquals(18, infMember.signature().interval().minInt());
			assertEquals(90, infMember.signature().interval().maxInt());
			assertEquals(Monotonicity.MONO_STRICT_INC, infMember.signature().monotonicity());

			// Direct identifier: age
			CelAstNode ident = CelAstNode.makeIdent("age");
			CelAstNode infIdent = AlgebraicTypeInferencePass.INSTANCE.inferCel(ident, stats);
			assertNotNull(infIdent.signature());
			assertEquals(18, infIdent.signature().interval().minInt());
			assertEquals(90, infIdent.signature().interval().maxInt());

			// Unknown attribute returns defaultGeneral
			CelAstNode unknown = CelAstNode.makeMember(CelAstNode.makeIdent("node"), "nonexistent");
			CelAstNode infUnknown = AlgebraicTypeInferencePass.INSTANCE.inferCel(unknown, stats);
			assertNotNull(infUnknown.signature());
			assertFalse(infUnknown.signature().interval().isBounded());

			// Null stats handles gracefully
			CelAstNode infNullStats = AlgebraicTypeInferencePass.INSTANCE.inferCel(member, null);
			assertNotNull(infNullStats.signature());
			assertFalse(infNullStats.signature().interval().isBounded());
		}

		@Test
		@DisplayName("Unary Operations: Negation and Logical NOT")
		void testUnaryOpInference() {
			GraphStatistics stats = new GraphStatistics();
			AttributeStatistics tempStats = new AttributeStatistics("temp", 10, 40, 10.0, 40.0, "", "", 0, 30,
					Monotonicity.MONO_STRICT_INC, false);
			stats.putAttributeStatistics("temp", tempStats);

			// -temp -> interval becomes [-40, -10], monotonicity flipped to STRICT_DEC
			CelAstNode negNode = CelAstNode.makeUnary("-", CelAstNode.makeIdent("temp"));
			CelAstNode infNeg = AlgebraicTypeInferencePass.INSTANCE.inferCel(negNode, stats);
			assertNotNull(infNeg.signature());
			assertEquals(-40, infNeg.signature().interval().minInt());
			assertEquals(-10, infNeg.signature().interval().maxInt());
			assertEquals(Monotonicity.MONO_STRICT_DEC, infNeg.signature().monotonicity());

			// !true -> constant known false
			CelAstNode notTrue = CelAstNode.makeUnary("!", CelAstNode.makeBool(true));
			CelAstNode infNotTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(notTrue, stats);
			assertTrue(infNotTrue.signature().isConstantKnown());
			assertFalse(infNotTrue.signature().constantBoolVal());

			// !false -> constant known true
			CelAstNode notFalse = CelAstNode.makeUnary("!", CelAstNode.makeBool(false));
			CelAstNode infNotFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(notFalse, stats);
			assertTrue(infNotFalse.signature().isConstantKnown());
			assertTrue(infNotFalse.signature().constantBoolVal());

			// Empty unary op children
			CelAstNode emptyUnary = new CelAstNode(CelAstNode.Kind.UNARY_OP, "-", 0, 0, false, "", List.of());
			CelAstNode infEmptyUnary = AlgebraicTypeInferencePass.INSTANCE.inferCel(emptyUnary, stats);
			assertNotNull(infEmptyUnary.signature());
		}

		@Test
		@DisplayName("Binary Operations: Arithmetic, Comparisons, Boolean Short-Circuits")
		void testBinaryOpInference() {
			GraphStatistics stats = new GraphStatistics();
			AttributeStatistics ageStats = new AttributeStatistics("age", 20, 50, 20.0, 50.0, "", "", 0, 30,
					Monotonicity.MONO_STRICT_INC, false);
			stats.putAttributeStatistics("age", ageStats);

			// 1. Addition: age + 5 -> [25, 55]
			CelAstNode addNode = CelAstNode.makeBinary("+", CelAstNode.makeIdent("age"), CelAstNode.makeInt(5));
			CelAstNode infAdd = AlgebraicTypeInferencePass.INSTANCE.inferCel(addNode, stats);
			assertEquals(25, infAdd.signature().interval().minInt());
			assertEquals(55, infAdd.signature().interval().maxInt());

			// 2. Comparisons evaluated against bounds:
			// age > 10 -> provably TRUE (min age 20 > 10)
			CelAstNode gtTrue = CelAstNode.makeBinary(">", CelAstNode.makeIdent("age"), CelAstNode.makeInt(10));
			CelAstNode infGtTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(gtTrue, stats);
			assertTrue(infGtTrue.signature().isConstantKnown());
			assertTrue(infGtTrue.signature().constantBoolVal());

			// age > 100 -> provably FALSE (max age 50 <= 100)
			CelAstNode gtFalse = CelAstNode.makeBinary(">", CelAstNode.makeIdent("age"), CelAstNode.makeInt(100));
			CelAstNode infGtFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(gtFalse, stats);
			assertTrue(infGtFalse.signature().isConstantKnown());
			assertFalse(infGtFalse.signature().constantBoolVal());

			// age >= 20 -> provably TRUE (min age 20 >= 20)
			CelAstNode gteTrue = CelAstNode.makeBinary(">=", CelAstNode.makeIdent("age"), CelAstNode.makeInt(20));
			CelAstNode infGteTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(gteTrue, stats);
			assertTrue(infGteTrue.signature().isConstantKnown());
			assertTrue(infGteTrue.signature().constantBoolVal());

			// age >= 60 -> provably FALSE (max age 50 < 60)
			CelAstNode gteFalse = CelAstNode.makeBinary(">=", CelAstNode.makeIdent("age"), CelAstNode.makeInt(60));
			CelAstNode infGteFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(gteFalse, stats);
			assertTrue(infGteFalse.signature().isConstantKnown());
			assertFalse(infGteFalse.signature().constantBoolVal());

			// age < 60 -> provably TRUE (max age 50 < 60)
			CelAstNode ltTrue = CelAstNode.makeBinary("<", CelAstNode.makeIdent("age"), CelAstNode.makeInt(60));
			CelAstNode infLtTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(ltTrue, stats);
			assertTrue(infLtTrue.signature().isConstantKnown());
			assertTrue(infLtTrue.signature().constantBoolVal());

			// age < 10 -> provably FALSE (min age 20 >= 10)
			CelAstNode ltFalse = CelAstNode.makeBinary("<", CelAstNode.makeIdent("age"), CelAstNode.makeInt(10));
			CelAstNode infLtFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(ltFalse, stats);
			assertTrue(infLtFalse.signature().isConstantKnown());
			assertFalse(infLtFalse.signature().constantBoolVal());

			// age <= 50 -> provably TRUE (max age 50 <= 50)
			CelAstNode lteTrue = CelAstNode.makeBinary("<=", CelAstNode.makeIdent("age"), CelAstNode.makeInt(50));
			CelAstNode infLteTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(lteTrue, stats);
			assertTrue(infLteTrue.signature().isConstantKnown());
			assertTrue(infLteTrue.signature().constantBoolVal());

			// age <= 10 -> provably FALSE (min age 20 > 10)
			CelAstNode lteFalse = CelAstNode.makeBinary("<=", CelAstNode.makeIdent("age"), CelAstNode.makeInt(10));
			CelAstNode infLteFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(lteFalse, stats);
			assertTrue(infLteFalse.signature().isConstantKnown());
			assertFalse(infLteFalse.signature().constantBoolVal());

			// age == 100 -> provably FALSE (100 > max 50)
			CelAstNode eqFalse = CelAstNode.makeBinary("==", CelAstNode.makeIdent("age"), CelAstNode.makeInt(100));
			CelAstNode infEqFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(eqFalse, stats);
			assertTrue(infEqFalse.signature().isConstantKnown());
			assertFalse(infEqFalse.signature().constantBoolVal());

			// age != 100 -> provably TRUE (100 > max 50)
			CelAstNode neqTrue = CelAstNode.makeBinary("!=", CelAstNode.makeIdent("age"), CelAstNode.makeInt(100));
			CelAstNode infNeqTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(neqTrue, stats);
			assertTrue(infNeqTrue.signature().isConstantKnown());
			assertTrue(infNeqTrue.signature().constantBoolVal());

			// 3. Boolean short-circuit laws:
			// false && x -> false
			CelAstNode andFalse = CelAstNode.makeBinary("&&", CelAstNode.makeBool(false), CelAstNode.makeIdent("x"));
			CelAstNode infAndFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(andFalse, stats);
			assertTrue(infAndFalse.signature().isConstantKnown());
			assertFalse(infAndFalse.signature().constantBoolVal());

			// true && true -> true
			CelAstNode andTrue = CelAstNode.makeBinary("&&", CelAstNode.makeBool(true), CelAstNode.makeBool(true));
			CelAstNode infAndTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(andTrue, stats);
			assertTrue(infAndTrue.signature().isConstantKnown());
			assertTrue(infAndTrue.signature().constantBoolVal());

			// true || x -> true
			CelAstNode orTrue = CelAstNode.makeBinary("||", CelAstNode.makeBool(true), CelAstNode.makeIdent("x"));
			CelAstNode infOrTrue = AlgebraicTypeInferencePass.INSTANCE.inferCel(orTrue, stats);
			assertTrue(infOrTrue.signature().isConstantKnown());
			assertTrue(infOrTrue.signature().constantBoolVal());

			// false || false -> false
			CelAstNode orFalse = CelAstNode.makeBinary("||", CelAstNode.makeBool(false), CelAstNode.makeBool(false));
			CelAstNode infOrFalse = AlgebraicTypeInferencePass.INSTANCE.inferCel(orFalse, stats);
			assertTrue(infOrFalse.signature().isConstantKnown());
			assertFalse(infOrFalse.signature().constantBoolVal());
		}

		@Test
		@DisplayName("Function Calls: Monotonic homomorphisms & Semilattices")
		void testFunctionCallInference() {
			GraphStatistics stats = new GraphStatistics();

			// Monotonic functions: log, exp, sqrt
			CelAstNode logNode = CelAstNode.makeCall("log", List.of(CelAstNode.makeIdent("v")));
			CelAstNode infLog = AlgebraicTypeInferencePass.INSTANCE.inferCel(logNode, stats);
			assertEquals(Monotonicity.MONO_STRICT_INC, infLog.signature().monotonicity());
			assertTrue(infLog.signature().commutesWithMax());
			assertTrue(infLog.signature().commutesWithMin());

			// Semilattice functions: max, min, argmin, argmax
			CelAstNode maxNode = CelAstNode.makeCall("max", List.of(CelAstNode.makeIdent("v")));
			CelAstNode infMax = AlgebraicTypeInferencePass.INSTANCE.inferCel(maxNode, stats);
			assertTrue(infMax.signature().isIdempotent());
			assertTrue(infMax.signature().isCommutative());

			// Unrecognized function call falls back to defaultGeneral
			CelAstNode customNode = CelAstNode.makeCall("custom_func", List.of(CelAstNode.makeIdent("v")));
			CelAstNode infCustom = AlgebraicTypeInferencePass.INSTANCE.inferCel(customNode, stats);
			assertNotNull(infCustom.signature());
			assertEquals(Monotonicity.MONO_NONE, infCustom.signature().monotonicity());
		}

		@Test
		@DisplayName("ScmProgram & ScmWalk: Full AST tree inference")
		void testScmProgramInference() {
			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));

				ScmProgram prog = ScmProgram.of(
						ScmWalk.forward("users", new ScmCelExpr("node.age >= 21", CelParser.parse("node.age >= 21"))),
						ScmVectorFilter.of(new ScmCelExpr("active == true", CelParser.parse("active == true"))),
						ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode inferred = AlgebraicTypeInferencePass.INSTANCE.transform(prog, ctx);

				assertThat(inferred).isInstanceOf(ScmProgram.class);
				ScmProgram infProg = (ScmProgram) inferred;
				assertThat(infProg.steps()).hasSize(3);
				assertThat(infProg.steps().get(0)).isInstanceOf(ScmWalk.class);
				assertThat(infProg.steps().get(1)).isInstanceOf(ScmVectorFilter.class);
				assertThat(infProg.steps().get(2)).isInstanceOf(ScmCollect.class);
			}
		}
	}

	// =========================================================================
	// 2. AstNormalizationPass
	// =========================================================================
	@Nested
	@DisplayName("2. AstNormalizationPass Tests")
	class AstNormalizationPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("AstNormalizationPass", AstNormalizationPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null input")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(AstNormalizationPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Structure: Normalizes ScmProgram, ScmWalk, ScmVectorFilter, ScmList")
		void testNormalizationTransform() {
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("knows", ScmVectorFilter.of(ScmSymbol.of("pred"))),
					ScmList.of(ScmSymbol.of("item1"), ScmSymbol.of("item2")), ScmCollect.bitset());

			CompilerContext ctx = createContext(null, null);
			ImpScmNode norm = AstNormalizationPass.INSTANCE.transform(prog, ctx);

			assertThat(norm).isInstanceOf(ScmProgram.class);
			ScmProgram normProg = (ScmProgram) norm;
			assertThat(normProg.steps()).hasSize(3);

			ScmWalk walk = (ScmWalk) normProg.steps().get(0);
			assertThat(walk.relationName()).isEqualTo("knows");
			assertThat(walk.shaderSteps()).hasSize(1);
			assertThat(walk.shaderSteps().get(0)).isInstanceOf(ScmVectorFilter.class);

			ScmList list = (ScmList) normProg.steps().get(1);
			assertThat(list.elements()).hasSize(2);
		}

		@Test
		@DisplayName("Leaves: Preserves leaf nodes without changes")
		void testLeafNodesPreserved() {
			CompilerContext ctx = createContext(null, null);
			ScmSymbol sym = ScmSymbol.of("testSym");
			ImpScmNode resultSym = AstNormalizationPass.INSTANCE.transform(sym, ctx);
			assertSame(sym, resultSym);

			ScmLiteral.ScmInt intLit = ScmLiteral.ofInt(100);
			assertSame(intLit, AstNormalizationPass.INSTANCE.transform(intLit, ctx));
		}
	}

	// =========================================================================
	// 3. CelPredicateFlatteningPass
	// =========================================================================
	@Nested
	@DisplayName("3. CelPredicateFlatteningPass Tests")
	class CelPredicateFlatteningPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("CelPredicateFlatteningPass", CelPredicateFlatteningPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null input")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(CelPredicateFlatteningPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Flattening: ScmCelExpr flattened to S-expression AST")
		void testFlattenScmCelExpr() {
			ScmCelExpr raw = new ScmCelExpr("node.age >= 21");
			CompilerContext ctx = createContext(null, null);
			ImpScmNode flattened = CelPredicateFlatteningPass.INSTANCE.transform(raw, ctx);

			assertNotNull(flattened);
			String scm = flattened.toScmString();
			assertThat(scm).contains("vec-cmp-gte");
			assertThat(scm).contains("21");
		}

		@Test
		@DisplayName("Stream Mode: ScmStreamFilter and ScmStreamProject flatten in stream mode")
		void testStreamModeFlattening() {
			ScmStreamFilter streamFilter = new ScmStreamFilter(new ScmCelExpr("node.active == true"));
			ScmStreamProject streamProj = new ScmStreamProject(new ScmCelExpr("node.score * 1.5"));

			CompilerContext ctx = createContext(null, null);
			ImpScmNode flatFilter = CelPredicateFlatteningPass.INSTANCE.transform(streamFilter, ctx);
			ImpScmNode flatProj = CelPredicateFlatteningPass.INSTANCE.transform(streamProj, ctx);

			assertThat(flatFilter).isInstanceOf(ScmStreamFilter.class);
			assertThat(flatFilter.toScmString()).contains("stream-cmp-eq");

			assertThat(flatProj).isInstanceOf(ScmStreamProject.class);
			assertThat(flatProj.toScmString()).contains("*");
		}

		@Test
		@DisplayName("Program Hierarchy: Flattens walks, filters, and list elements")
		void testFullProgramFlattening() {
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("users", new ScmCelExpr("node.status == 'ACTIVE'")),
					ScmVectorFilter.of(new ScmCelExpr("node.tier > 2")), ScmList.of(new ScmCelExpr("1 + 2")),
					ScmCollect.bitset());

			CompilerContext ctx = createContext(null, null);
			ImpScmNode res = CelPredicateFlatteningPass.INSTANCE.transform(prog, ctx);

			assertThat(res).isInstanceOf(ScmProgram.class);
			String scm = res.toScmString();
			assertThat(scm).contains("vec-cmp-eq");
			assertThat(scm).contains("vec-cmp-gt");
		}
	}

	// =========================================================================
	// 4. ConstantFoldingPass
	// =========================================================================
	@Nested
	@DisplayName("4. ConstantFoldingPass Tests")
	class ConstantFoldingPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("ConstantFoldingPass", ConstantFoldingPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null input")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(ConstantFoldingPass.INSTANCE.transform(null, ctx));
		}

		@Test
		@DisplayName("Option: When constant folding is disabled, returns AST unchanged")
		void testDisabledOptionReturnsUnchanged() {
			CompilerOptions disabledOpts = CompilerOptions.builder().withConstantFolding(false).build();
			CompilerContext ctx = createContext(null, disabledOpts);

			ScmCelExpr cel = new ScmCelExpr("21 + 0", CelParser.parse("21 + 0"));
			ImpScmNode res = ConstantFoldingPass.INSTANCE.transform(cel, ctx);
			assertSame(cel, res);
		}

		@Test
		@DisplayName("Folding: Integer arithmetic, double negation, and string concatenation")
		void testExpressionsFolding() {
			CompilerContext ctx = createContext(null, null);

			// 1. Arithmetic folding: 10 + 20 * 2 -> 50
			ScmCelExpr arith = new ScmCelExpr("10 + 20 * 2", CelParser.parse("10 + 20 * 2"));
			ImpScmNode foldedArith = ConstantFoldingPass.INSTANCE.transform(arith, ctx);
			assertThat(foldedArith).isInstanceOf(ScmCelExpr.class);
			CelAstNode ast = (CelAstNode) ((ScmCelExpr) foldedArith).celAst();
			assertEquals(CelAstNode.Kind.LITERAL_INT, ast.kind());
			assertEquals(50, ast.intVal());

			// 2. Double negation: !(!active) -> active
			ScmCelExpr neg = new ScmCelExpr("!(!active)", CelParser.parse("!(!active)"));
			ImpScmNode foldedNeg = ConstantFoldingPass.INSTANCE.transform(neg, ctx);
			CelAstNode astNeg = (CelAstNode) ((ScmCelExpr) foldedNeg).celAst();
			assertEquals(CelAstNode.Kind.IDENTIFIER, astNeg.kind());
			assertEquals("active", astNeg.text());

			// 3. String concatenation: 'foo' + 'bar' -> 'foobar'
			ScmCelExpr concat = new ScmCelExpr("\"foo\" + \"bar\"", CelParser.parse("\"foo\" + \"bar\""));
			ImpScmNode foldedConcat = ConstantFoldingPass.INSTANCE.transform(concat, ctx);
			CelAstNode astConcat = (CelAstNode) ((ScmCelExpr) foldedConcat).celAst();
			assertEquals(CelAstNode.Kind.LITERAL_STRING, astConcat.kind());
			assertEquals("foobar", astConcat.strVal());
		}

		@Test
		@DisplayName("Program Hierarchy: Recursively folds walk shaders and list elements")
		void testHierarchyFolding() {
			CompilerContext ctx = createContext(null, null);

			ScmProgram prog = ScmProgram.of(ScmWalk.forward("users", new ScmCelExpr("2 + 3", CelParser.parse("2 + 3"))),
					ScmVectorFilter.of(new ScmCelExpr("10 - 5", CelParser.parse("10 - 5"))),
					ScmList.of(new ScmCelExpr("4 * 5", CelParser.parse("4 * 5"))), ScmCollect.bitset());

			ImpScmNode foldedProg = ConstantFoldingPass.INSTANCE.transform(prog, ctx);
			assertThat(foldedProg).isInstanceOf(ScmProgram.class);

			ScmWalk walk = (ScmWalk) ((ScmProgram) foldedProg).steps().get(0);
			CelAstNode walkCel = (CelAstNode) ((ScmCelExpr) walk.shaderSteps().get(0)).celAst();
			assertEquals(5, walkCel.intVal());

			ScmVectorFilter filter = (ScmVectorFilter) ((ScmProgram) foldedProg).steps().get(1);
			CelAstNode filterCel = (CelAstNode) ((ScmCelExpr) filter.predicate()).celAst();
			assertEquals(5, filterCel.intVal());
		}
	}

	// =========================================================================
	// 5. MonotonicHomomorphismPass
	// =========================================================================
	@Nested
	@DisplayName("5. MonotonicHomomorphismPass Tests")
	class MonotonicHomomorphismPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("MonotonicHomomorphismPass", MonotonicHomomorphismPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null input")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(MonotonicHomomorphismPass.INSTANCE.transform(null, ctx));
			assertNull(MonotonicHomomorphismPass.INSTANCE.optimizeCel(null));
		}

		@Test
		@DisplayName("Commutation: max(log(v)) -> log(max(v)) and min(sqrt(v)) -> sqrt(min(v))")
		void testMonotonicIncreasingCommutation() {
			GraphStatistics stats = new GraphStatistics();

			// max(log(v))
			CelAstNode maxLog = CelParser.parse("max(log(v))");
			CelAstNode inferredMaxLog = AlgebraicTypeInferencePass.INSTANCE.inferCel(maxLog, stats);
			CelAstNode optMaxLog = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferredMaxLog);

			assertEquals("log", optMaxLog.text());
			assertEquals("max", optMaxLog.children().get(0).text());
			assertEquals("v", optMaxLog.children().get(0).children().get(0).text());

			// min(sqrt(v))
			CelAstNode minSqrt = CelParser.parse("min(sqrt(v))");
			CelAstNode inferredMinSqrt = AlgebraicTypeInferencePass.INSTANCE.inferCel(minSqrt, stats);
			CelAstNode optMinSqrt = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferredMinSqrt);

			assertEquals("sqrt", optMinSqrt.text());
			assertEquals("min", optMinSqrt.children().get(0).text());
			assertEquals("v", optMinSqrt.children().get(0).children().get(0).text());
		}

		@Test
		@DisplayName("Inversion: max(-v) -> -min(v) and min(-v) -> -max(v)")
		void testDecreasingMeetJoinInversion() {
			GraphStatistics stats = new GraphStatistics();

			// max(-v) -> -min(v)
			CelAstNode maxNeg = CelParser.parse("max(-v)");
			CelAstNode inferredMaxNeg = AlgebraicTypeInferencePass.INSTANCE.inferCel(maxNeg, stats);
			CelAstNode optMaxNeg = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferredMaxNeg);

			assertEquals("-", optMaxNeg.text());
			assertEquals("min", optMaxNeg.children().get(0).text());
			assertEquals("v", optMaxNeg.children().get(0).children().get(0).text());

			// min(-v) -> -max(v)
			CelAstNode minNeg = CelParser.parse("min(-v)");
			CelAstNode inferredMinNeg = AlgebraicTypeInferencePass.INSTANCE.inferCel(minNeg, stats);
			CelAstNode optMinNeg = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferredMinNeg);

			assertEquals("-", optMinNeg.text());
			assertEquals("max", optMinNeg.children().get(0).text());
			assertEquals("v", optMinNeg.children().get(0).children().get(0).text());
		}

		@Test
		@DisplayName("Program Hierarchy: Traverses ScmProgram and ScmCelExpr")
		void testProgramTraversal() {
			GraphStatistics stats = new GraphStatistics();
			CelAstNode maxLog = AlgebraicTypeInferencePass.INSTANCE.inferCel(CelParser.parse("max(log(v))"), stats);

			ScmProgram prog = ScmProgram.of(new ScmCelExpr("max(log(v))", maxLog), ScmCollect.bitset());

			CompilerContext ctx = createContext(null, null);
			ImpScmNode res = MonotonicHomomorphismPass.INSTANCE.transform(prog, ctx);

			assertThat(res).isInstanceOf(ScmProgram.class);
			ScmCelExpr celStep = (ScmCelExpr) ((ScmProgram) res).steps().get(0);
			CelAstNode optAst = (CelAstNode) celStep.celAst();
			assertEquals("log", optAst.text());
		}
	}

	// =========================================================================
	// 6. ParameterBindingPass
	// =========================================================================
	@Nested
	@DisplayName("6. ParameterBindingPass Tests")
	class ParameterBindingPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("ParameterBindingPass", ParameterBindingPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(ParameterBindingPass.INSTANCE.transform(null, ctx));
			assertNull(ParameterBindingPass.INSTANCE.bindCel(null, Map.of()));
		}

		@Test
		@DisplayName("No Parameters: Returns AST unchanged if options has no parameters")
		void testNoParametersReturnsUnchanged() {
			CompilerContext ctx = createContext(null, CompilerOptions.DEFAULT);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("rel"), ScmCollect.bitset());
			assertSame(prog, ParameterBindingPass.INSTANCE.transform(prog, ctx));
		}

		@Test
		@DisplayName("Binding Types: Injects Integer, Long, Double, Float, Boolean, String")
		void testBindingAllPrimitiveTypes() {
			CompilerOptions opts = CompilerOptions.builder().withParameter("@pInt", 42).withParameter("@pLong", 1000L)
					.withParameter("@pDouble", 3.14159).withParameter("@pFloat", 2.5f).withParameter("@pBool", true)
					.withParameter("@pString", "ACTIVE").withParameter("@pCustom", new StringBuilder("customValue"))
					.build();

			CompilerContext ctx = createContext(null, opts);

			// Test exact match, stripped '@' match, case-insensitive match
			CelAstNode refInt = CelAstNode.makeParam("@pInt");
			CelAstNode boundInt = ParameterBindingPass.INSTANCE.bindCel(refInt, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_INT, boundInt.kind());
			assertEquals(42, boundInt.intVal());

			CelAstNode refLong = CelAstNode.makeParam("pLong"); // stripped match
			CelAstNode boundLong = ParameterBindingPass.INSTANCE.bindCel(refLong, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_INT, boundLong.kind());
			assertEquals(1000L, boundLong.intVal());

			CelAstNode refDouble = CelAstNode.makeParam("@PDOUBLE"); // case-insensitive match
			CelAstNode boundDouble = ParameterBindingPass.INSTANCE.bindCel(refDouble, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_FLOAT, boundDouble.kind());
			assertEquals(3.14159, boundDouble.floatVal(), 1e-6);

			CelAstNode refFloat = CelAstNode.makeParam("@pFloat");
			CelAstNode boundFloat = ParameterBindingPass.INSTANCE.bindCel(refFloat, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_FLOAT, boundFloat.kind());
			assertEquals(2.5, boundFloat.floatVal(), 1e-6);

			CelAstNode refBool = CelAstNode.makeParam("@pBool");
			CelAstNode boundBool = ParameterBindingPass.INSTANCE.bindCel(refBool, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_BOOL, boundBool.kind());
			assertTrue(boundBool.boolVal());

			CelAstNode refString = CelAstNode.makeParam("@pString");
			CelAstNode boundString = ParameterBindingPass.INSTANCE.bindCel(refString, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_STRING, boundString.kind());
			assertEquals("ACTIVE", boundString.strVal());

			CelAstNode refCustom = CelAstNode.makeParam("@pCustom");
			CelAstNode boundCustom = ParameterBindingPass.INSTANCE.bindCel(refCustom, opts.parameters());
			assertEquals(CelAstNode.Kind.LITERAL_STRING, boundCustom.kind());
			assertEquals("customValue", boundCustom.strVal());

			// Unbound parameter remains PARAMETER_REF
			CelAstNode refUnknown = CelAstNode.makeParam("@unbound");
			CelAstNode boundUnknown = ParameterBindingPass.INSTANCE.bindCel(refUnknown, opts.parameters());
			assertEquals(CelAstNode.Kind.PARAMETER_REF, boundUnknown.kind());
		}

		@Test
		@DisplayName("Program Hierarchy: Binds parameters across walks, vector filters, and sub-steps")
		void testFullProgramParameterBinding() {
			CompilerOptions opts = CompilerOptions.builder().withParameter("@minAge", 21)
					.withParameter("@status", "VIP").build();
			CompilerContext ctx = createContext(null, opts);

			ScmProgram prog = ScmProgram.of(
					ScmWalk.forward("users", new ScmCelExpr("age >= @minAge", CelParser.parse("age >= @minAge"))),
					ScmVectorFilter.of(new ScmCelExpr("tier == @status", CelParser.parse("tier == @status"))),
					ScmCollect.bitset());

			ImpScmNode bound = ParameterBindingPass.INSTANCE.transform(prog, ctx);
			String scm = bound.toScmString();
			assertThat(scm).contains("21");
			assertThat(scm).contains("\"VIP\"");
		}
	}

	// =========================================================================
	// 7. PreBindValidator
	// =========================================================================
	@Nested
	@DisplayName("7. PreBindValidator Tests")
	class PreBindValidatorTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("PreBindValidator", PreBindValidator.INSTANCE.name());
		}

		@Test
		@DisplayName("Validation: Null AST throws IllegalArgumentException")
		void testNullAstThrows() {
			CompilerContext ctx = createContext(null, null);
			assertThatThrownBy(() -> PreBindValidator.INSTANCE.transform(null, ctx))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AST cannot be null");
		}

		@Test
		@DisplayName("Validation: Empty program steps throw IllegalArgumentException")
		void testEmptyProgramThrows() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram emptyProg = new ScmProgram(List.of());
			assertThatThrownBy(() -> PreBindValidator.INSTANCE.transform(emptyProg, ctx))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Program contains no execution steps");
		}

		@Test
		@DisplayName("Validation: Walk step with empty relation and invalid relationId throws")
		void testWalkMissingRelationThrows() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(new ScmWalk("", -1, ScmWalk.Direction.AUTO, List.of(), List.of()));
			assertThatThrownBy(() -> PreBindValidator.INSTANCE.transform(prog, ctx))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Walk step missing relation specification");
		}

		@Test
		@DisplayName("Validation: Walk with valid relationName or relationId passes")
		void testValidWalkPasses() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram p1 = ScmProgram.of(ScmWalk.forward("users"));
			ScmProgram p2 = ScmProgram.of(new ScmWalk("", 42, ScmWalk.Direction.FORWARD_CSR, List.of(), List.of()));

			assertDoesNotThrow(() -> PreBindValidator.INSTANCE.transform(p1, ctx));
			assertDoesNotThrow(() -> PreBindValidator.INSTANCE.transform(p2, ctx));
		}

		@Test
		@DisplayName("Validation: CelExpr with invalid CEL syntax throws")
		void testInvalidCelSyntaxThrows() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("users"),
					new ScmCelExpr("node.age >>> 21 ??? invalid", null));

			assertThatThrownBy(() -> PreBindValidator.INSTANCE.transform(prog, ctx))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid CEL syntax");
		}

		@Test
		@DisplayName("Validation: VectorFilter with null predicate throws")
		void testNullVectorFilterPredicateThrows() {
			CompilerContext ctx = createContext(null, null);
			assertThatThrownBy(() -> ScmVectorFilter.of(null)).isInstanceOf(NullPointerException.class);
		}

		@Test
		@DisplayName("Validation: Complete valid program passes and returns same AST")
		void testValidProgramPasses() {
			CompilerContext ctx = createContext(null, null);
			ScmProgram prog = ScmProgram.of(ScmWalk.forward("friends", new ScmCelExpr("node.age >= 18")),
					ScmVectorFilter.of(ScmSymbol.of("active_only")), ScmCollect.bitset());

			ImpScmNode validated = PreBindValidator.INSTANCE.transform(prog, ctx);
			assertSame(prog, validated);
		}
	}

	// =========================================================================
	// 8. ZoneMapPruningPass
	// =========================================================================
	@Nested
	@DisplayName("8. ZoneMapPruningPass Tests")
	class ZoneMapPruningPassTests {

		@Test
		@DisplayName("Metadata: Pass name is correct")
		void testPassName() {
			assertEquals("ZoneMapPruningPass", ZoneMapPruningPass.INSTANCE.name());
		}

		@Test
		@DisplayName("Null Handling: transform returns null for null AST")
		void testNullTransform() {
			CompilerContext ctx = createContext(null, null);
			assertNull(ZoneMapPruningPass.INSTANCE.transform(null, ctx));
			assertNull(ZoneMapPruningPass.INSTANCE.pruneCel(null));
		}

		@Test
		@DisplayName("Zone Map: Upper bound exceeded prunes dead walk branch")
		void testUpperBoundExceededPrunesBranch() {
			AttributeStatistics ageStats = new AttributeStatistics("age", 18, 100, 18.0, 100.0, "", "", 0, 82,
					Monotonicity.MONO_NONE, false);

			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
				snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

				// node.age > 200 is provably false
				ScmProgram ast = ScmProgram.of(
						ScmWalk.forward("users", new ScmCelExpr("node.age > 200", CelParser.parse("node.age > 200"))),
						ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode typed = AlgebraicTypeInferencePass.INSTANCE.transform(ast, ctx);
				ImpScmNode pruned = ZoneMapPruningPass.INSTANCE.transform(typed, ctx);

				assertThat(pruned).isInstanceOf(ScmProgram.class);
				ScmProgram prog = (ScmProgram) pruned;
				// Dead branch pruned: only ScmCollect remains
				assertEquals(1, prog.steps().size());
				assertThat(prog.steps().get(0)).isInstanceOf(ScmCollect.class);
			}
		}

		@Test
		@DisplayName("Zone Map: Universal satisfaction strips redundant filter from walk")
		void testUniversalSatisfactionStripsFilter() {
			AttributeStatistics ageStats = new AttributeStatistics("age", 25, 80, 25.0, 80.0, "", "", 0, 55,
					Monotonicity.MONO_NONE, false);

			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
				snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

				// node.age >= 21 is provably 100% true (min age is 25)
				ScmProgram ast = ScmProgram.of(
						ScmWalk.forward("users", new ScmCelExpr("node.age >= 21", CelParser.parse("node.age >= 21"))),
						ScmCollect.bitset());

				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode typed = AlgebraicTypeInferencePass.INSTANCE.transform(ast, ctx);
				ImpScmNode pruned = ZoneMapPruningPass.INSTANCE.transform(typed, ctx);

				assertThat(pruned).isInstanceOf(ScmProgram.class);
				ScmProgram prog = (ScmProgram) pruned;
				assertEquals(2, prog.steps().size());
				ScmWalk walk = (ScmWalk) prog.steps().get(0);
				// Filter stripped completely
				assertTrue(walk.shaderSteps().isEmpty());
			}
		}

		@Test
		@DisplayName("Zone Map: ScmList predicate fold against attribute statistics")
		void testScmListPredicateFolding() {
			AttributeStatistics tempStats = new AttributeStatistics("temp", 10, 50, 10.0, 50.0, "", "", 0, 40,
					Monotonicity.MONO_NONE, false);

			try (Arena arena = Arena.ofConfined()) {
				RelationSnapshot rel = createMockRelation(arena, new int[]{0, 2}, new int[]{0, 1});
				ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("sensors", rel));
				snapshot.getGraphStatistics().putAttributeStatistics("temp", tempStats);

				// 1. (> (get-attr node "temp") 100) -> provably false (100 >= max 50)
				ScmList gtList = ScmList.of(ScmSymbol.of("vec-cmp-gt"),
						ScmList.of(ScmSymbol.of("get-attr"), ScmSymbol.of("node"), ScmLiteral.ofString("temp")),
						ScmLiteral.ofInt(100));
				CompilerContext ctx = createContext(snapshot, null);
				ImpScmNode foldedGt = ZoneMapPruningPass.INSTANCE.transform(gtList, ctx);
				assertThat(foldedGt).isInstanceOf(ScmLiteral.ScmBool.class);
				assertFalse(((ScmLiteral.ScmBool) foldedGt).value());

				// 2. (< (get-attr node "temp") 5) -> provably false (5 <= min 10)
				ScmList ltList = ScmList.of(ScmSymbol.of("vec-cmp-lt"),
						ScmList.of(ScmSymbol.of("get-attr"), ScmSymbol.of("node"), ScmLiteral.ofString("temp")),
						ScmLiteral.ofInt(5));
				ImpScmNode foldedLt = ZoneMapPruningPass.INSTANCE.transform(ltList, ctx);
				assertThat(foldedLt).isInstanceOf(ScmLiteral.ScmBool.class);
				assertFalse(((ScmLiteral.ScmBool) foldedLt).value());

				// 3. (== (get-attr node "temp") 200) -> provably false (200 > max 50)
				ScmList eqList = ScmList.of(ScmSymbol.of("vec-cmp-eq"),
						ScmList.of(ScmSymbol.of("get-attr"), ScmSymbol.of("node"), ScmLiteral.ofString("temp")),
						ScmLiteral.ofInt(200));
				ImpScmNode foldedEq = ZoneMapPruningPass.INSTANCE.transform(eqList, ctx);
				assertThat(foldedEq).isInstanceOf(ScmLiteral.ScmBool.class);
				assertFalse(((ScmLiteral.ScmBool) foldedEq).value());
			}
		}

		@Test
		@DisplayName("Boolean Absorptive Laws in pruneCel")
		void testPruneCelBooleanAbsorption() {
			// false && x -> false
			CelAstNode falseAndX = CelAstNode.makeBinary("&&", CelAstNode.makeBool(false), CelAstNode.makeIdent("x"));
			CelAstNode optFalseAndX = ZoneMapPruningPass.INSTANCE.pruneCel(falseAndX);
			assertEquals(CelAstNode.Kind.LITERAL_BOOL, optFalseAndX.kind());
			assertFalse(optFalseAndX.boolVal());

			// true && x -> x
			CelAstNode trueAndX = CelAstNode.makeBinary("&&", CelAstNode.makeBool(true), CelAstNode.makeIdent("x"));
			CelAstNode optTrueAndX = ZoneMapPruningPass.INSTANCE.pruneCel(trueAndX);
			assertEquals(CelAstNode.Kind.IDENTIFIER, optTrueAndX.kind());
			assertEquals("x", optTrueAndX.text());

			// true || x -> true
			CelAstNode trueOrX = CelAstNode.makeBinary("||", CelAstNode.makeBool(true), CelAstNode.makeIdent("x"));
			CelAstNode optTrueOrX = ZoneMapPruningPass.INSTANCE.pruneCel(trueOrX);
			assertEquals(CelAstNode.Kind.LITERAL_BOOL, optTrueOrX.kind());
			assertTrue(optTrueOrX.boolVal());

			// false || x -> x
			CelAstNode falseOrX = CelAstNode.makeBinary("||", CelAstNode.makeBool(false), CelAstNode.makeIdent("x"));
			CelAstNode optFalseOrX = ZoneMapPruningPass.INSTANCE.pruneCel(falseOrX);
			assertEquals(CelAstNode.Kind.IDENTIFIER, optFalseOrX.kind());
			assertEquals("x", optFalseOrX.text());
		}

		@Test
		@DisplayName("VectorFilter Pruning: Constant true and false filters pruned to null")
		void testVectorFilterPruning() {
			CompilerContext ctx = createContext(null, null);

			ScmVectorFilter falseFilter = new ScmVectorFilter(ScmSymbol.of("constant-false"));
			ImpScmNode prunedFalse = ZoneMapPruningPass.INSTANCE.transform(falseFilter, ctx);
			assertNull(prunedFalse);

			ScmVectorFilter trueFilter = new ScmVectorFilter(ScmSymbol.of("constant-true"));
			ImpScmNode prunedTrue = ZoneMapPruningPass.INSTANCE.transform(trueFilter, ctx);
			assertNull(prunedTrue);
		}
	}
}
