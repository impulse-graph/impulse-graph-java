package org.impulsegraph.compiler.ast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.RelationStatistics.Multiplicity;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature.IntervalBound;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature.MorphismClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ImpScm AST Sealed Nodes & Algebraic Signature Test Suite")
class ImpScmAstTest {

	@Test
	@DisplayName("Test ScmProgram creation, mutation, serialization, and visitor")
	void testScmProgram() {
		ScmSymbol s1 = ScmSymbol.of("start");
		ScmSymbol s2 = ScmSymbol.of("stop");

		ScmProgram p1 = ScmProgram.of(s1);
		assertThat(p1.steps()).containsExactly(s1);

		ScmProgram p2 = ScmProgram.ofList(List.of(s1, s2));
		assertThat(p2.steps()).containsExactly(s1, s2);

		ScmProgram p3 = p1.withSteps(List.of(s2));
		assertThat(p3.steps()).containsExactly(s2);

		ScmProgram empty = new ScmProgram(null);
		assertThat(empty.steps()).isEmpty();
		assertThat(empty.toScmString()).isEqualTo("(program)");

		String scm = p2.toScmString();
		assertThat(scm).contains("(program").contains("start").contains("stop");

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(p2.accept(visitor)).isEqualTo("program");
	}

	@Test
	@DisplayName("Test ScmWalk directions, shaders, sub-steps, and serialization")
	void testScmWalk() {
		ScmWalk w1 = ScmWalk.forward("likes");
		assertThat(w1.relationName()).isEqualTo("likes");
		assertThat(w1.relationId()).isEqualTo(-1);
		assertThat(w1.direction()).isEqualTo(ScmWalk.Direction.FORWARD_CSR);
		assertThat(w1.shaderSteps()).isEmpty();
		assertThat(w1.subSteps()).isEmpty();
		assertThat(w1.toScmString()).isEqualTo("(csr-walk \"likes\")");

		ScmWalk w1WithFilter = ScmWalk.forward("likes", ScmSymbol.of("f1"));
		assertThat(w1WithFilter.shaderSteps()).hasSize(1);
		assertThat(w1WithFilter.toScmString()).isEqualTo("(csr-walk \"likes\" (shader f1))");

		ScmWalk w2 = ScmWalk.reverse("follows");
		assertThat(w2.direction()).isEqualTo(ScmWalk.Direction.REVERSE_CSC);
		assertThat(w2.toScmString()).isEqualTo("(csc-walk \"follows\")");

		ScmWalk w2WithFilter = ScmWalk.reverse("follows", ScmSymbol.of("f2"));
		assertThat(w2WithFilter.shaderSteps()).hasSize(1);
		assertThat(w2WithFilter.toScmString()).isEqualTo("(csc-walk \"follows\" (shader f2))");

		ScmWalk w3 = ScmWalk.auto("knows");
		assertThat(w3.direction()).isEqualTo(ScmWalk.Direction.AUTO);
		assertThat(w3.toScmString()).isEqualTo("(walk \"knows\")");

		ScmWalk modified = w1.withRelationId(42).withDirection(ScmWalk.Direction.REVERSE_CSC)
				.withShaderSteps(List.of(ScmSymbol.of("sh1"))).withSubSteps(List.of(ScmSymbol.of("sub1")));

		assertThat(modified.relationId()).isEqualTo(42);
		assertThat(modified.direction()).isEqualTo(ScmWalk.Direction.REVERSE_CSC);
		assertThat(modified.shaderSteps()).hasSize(1);
		assertThat(modified.subSteps()).hasSize(1);
		assertThat(modified.toScmString()).isEqualTo("(csc-walk 42 (shader sh1) sub1)");

		// Null safety in constructor
		ScmWalk safe = new ScmWalk(null, -1, null, null, null);
		assertThat(safe.relationName()).isEmpty();
		assertThat(safe.direction()).isEqualTo(ScmWalk.Direction.AUTO);
		assertThat(safe.shaderSteps()).isEmpty();
		assertThat(safe.subSteps()).isEmpty();

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(w1.accept(visitor)).isEqualTo("walk");
	}

	@Test
	@DisplayName("Test ScmWalk2Hop 2-hop traversal node")
	void testScmWalk2Hop() {
		ScmWalk2Hop hop = ScmWalk2Hop.of("likes", "knows");
		assertThat(hop.relation1Name()).isEqualTo("likes");
		assertThat(hop.relation2Name()).isEqualTo("knows");
		assertThat(hop.relation1Id()).isEqualTo(-1);
		assertThat(hop.relation2Id()).isEqualTo(-1);
		assertThat(hop.toScmString()).isEqualTo("(csr-walk-2hop \"likes\" \"knows\")");

		ScmWalk2Hop physical = hop.withPhysicalIds(10, 20);
		assertThat(physical.relation1Id()).isEqualTo(10);
		assertThat(physical.relation2Id()).isEqualTo(20);
		assertThat(physical.toScmString()).isEqualTo("(csr-walk-2hop 10 20)");

		// Null safety in constructor
		ScmWalk2Hop safe = new ScmWalk2Hop(null, -1, null, -1);
		assertThat(safe.relation1Name()).isEmpty();
		assertThat(safe.relation2Name()).isEmpty();

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(hop.accept(visitor)).isEqualTo("walk2hop");
	}

	@Test
	@DisplayName("Test ScmReduce operations and serialization")
	void testScmReduce() {
		assertThat(ScmReduce.sum().op()).isEqualTo(ScmReduce.Op.SUM);
		assertThat(ScmReduce.sum().toScmString()).isEqualTo("(reduce-sum)");

		assertThat(ScmReduce.first().op()).isEqualTo(ScmReduce.Op.FIRST);
		assertThat(ScmReduce.first().toScmString()).isEqualTo("(reduce-first)");

		assertThat(ScmReduce.count().op()).isEqualTo(ScmReduce.Op.COUNT);
		assertThat(ScmReduce.count().toScmString()).isEqualTo("(reduce-count)");

		assertThat(ScmReduce.argmin().op()).isEqualTo(ScmReduce.Op.ARGMIN);
		assertThat(ScmReduce.argmin().toScmString()).isEqualTo("(reduce-argmin)");

		assertThat(ScmReduce.argmax().op()).isEqualTo(ScmReduce.Op.ARGMAX);
		assertThat(ScmReduce.argmax().toScmString()).isEqualTo("(reduce-argmax)");

		ScmReduce min = new ScmReduce(ScmReduce.Op.MIN);
		assertThat(min.toScmString()).isEqualTo("(reduce-min)");

		ScmReduce max = new ScmReduce(ScmReduce.Op.MAX);
		assertThat(max.toScmString()).isEqualTo("(reduce-max)");

		assertThatThrownBy(() -> new ScmReduce(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(ScmReduce.sum().accept(visitor)).isEqualTo("reduce");
	}

	@Test
	@DisplayName("Test ScmCollect formats and serialization")
	void testScmCollect() {
		assertThat(ScmCollect.bitset().format()).isEqualTo(ScmCollect.Format.BITSET);
		assertThat(ScmCollect.bitset().toScmString()).isEqualTo("(collect-bitset)");

		assertThat(ScmCollect.vector().format()).isEqualTo(ScmCollect.Format.VECTOR);
		assertThat(ScmCollect.vector().toScmString()).isEqualTo("(collect-vector)");

		assertThat(ScmCollect.list().format()).isEqualTo(ScmCollect.Format.LIST);
		assertThat(ScmCollect.list().toScmString()).isEqualTo("(collect-list)");

		assertThat(ScmCollect.scalar().format()).isEqualTo(ScmCollect.Format.SCALAR);
		assertThat(ScmCollect.scalar().toScmString()).isEqualTo("(collect-scalar)");

		assertThat(ScmCollect.distinct().format()).isEqualTo(ScmCollect.Format.DISTINCT);
		assertThat(ScmCollect.distinct().toScmString()).isEqualTo("(collect-distinct)");

		assertThatThrownBy(() -> new ScmCollect(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(ScmCollect.bitset().accept(visitor)).isEqualTo("collect");
	}

	@Test
	@DisplayName("Test ScmVectorFilter node and serialization")
	void testScmVectorFilter() {
		ScmSymbol pred = ScmSymbol.of("active");
		ScmVectorFilter filter = ScmVectorFilter.of(pred);

		assertThat(filter.predicate()).isEqualTo(pred);
		assertThat(filter.toScmString()).isEqualTo("(vector-filter active)");

		assertThatThrownBy(() -> new ScmVectorFilter(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(filter.accept(visitor)).isEqualTo("vectorFilter");
	}

	@Test
	@DisplayName("Test ScmStreamFilter and ScmStreamProject nodes")
	void testStreamFilterAndProject() {
		ScmSymbol pred = ScmSymbol.of("streamPred");
		ScmStreamFilter sf = new ScmStreamFilter(pred);
		assertThat(sf.predicate()).isEqualTo(pred);
		assertThat(sf.toScmString()).isEqualTo("(stream-filter streamPred)");

		ScmSymbol expr = ScmSymbol.of("streamExpr");
		ScmStreamProject sp = new ScmStreamProject(expr);
		assertThat(sp.expr()).isEqualTo(expr);
		assertThat(sp.toScmString()).isEqualTo("(stream-project streamExpr)");

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(sf.accept(visitor)).isNull();
		assertThat(sp.accept(visitor)).isNull();
	}

	@Test
	@DisplayName("Test ScmCelExpr with raw text and AST")
	void testScmCelExpr() {
		ScmCelExpr cel1 = ScmCelExpr.of("node.age > 21");
		assertThat(cel1.rawText()).isEqualTo("node.age > 21");
		assertThat(cel1.celAst()).isNull();
		assertThat(cel1.toScmString()).isEqualTo("(cel-expr \"node.age > 21\")");

		ScmCelExpr cel2 = ScmCelExpr.of("node.age > 21", "PARSED_AST_OBJ");
		assertThat(cel2.celAst()).isEqualTo("PARSED_AST_OBJ");
		assertThat(cel2.toScmString()).isEqualTo("(cel-expr PARSED_AST_OBJ)");

		assertThatThrownBy(() -> new ScmCelExpr(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(cel1.accept(visitor)).isEqualTo("celExpr");
	}

	@Test
	@DisplayName("Test ScmLiteral Int, Float, Bool, and String representations")
	void testScmLiteral() {
		// ScmInt
		ScmLiteral.ScmInt scmInt = ScmLiteral.ofInt(42L);
		assertThat(scmInt.value()).isEqualTo(42L);
		assertThat(scmInt.toScmString()).isEqualTo("42");

		// ScmFloat
		ScmLiteral.ScmFloat scmFloat1 = ScmLiteral.ofFloat(3.14);
		assertThat(scmFloat1.value()).isEqualTo(3.14);
		assertThat(scmFloat1.toScmString()).isEqualTo("3.14");

		ScmLiteral.ScmFloat scmFloat2 = ScmLiteral.ofFloat(5.0);
		assertThat(scmFloat2.toScmString()).isEqualTo("5.0");

		ScmLiteral.ScmFloat scmFloat3 = ScmLiteral.ofFloat(1e5);
		assertThat(scmFloat3.toScmString()).contains("100000.0");

		// ScmBool
		ScmLiteral.ScmBool boolTrue = ScmLiteral.ofBool(true);
		assertThat(boolTrue.value()).isTrue();
		assertThat(boolTrue.toScmString()).isEqualTo("#t");

		ScmLiteral.ScmBool boolFalse = ScmLiteral.ofBool(false);
		assertThat(boolFalse.value()).isFalse();
		assertThat(boolFalse.toScmString()).isEqualTo("#f");

		// ScmString
		ScmLiteral.ScmString str1 = ScmLiteral.ofString("hello");
		assertThat(str1.value()).isEqualTo("hello");
		assertThat(str1.toScmString()).isEqualTo("\"hello\"");

		ScmLiteral.ScmString str2 = ScmLiteral.ofStr("world");
		assertThat(str2.value()).isEqualTo("world");

		assertThatThrownBy(() -> new ScmLiteral.ScmString(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(scmInt.accept(visitor)).isEqualTo("literal");
		assertThat(scmFloat1.accept(visitor)).isEqualTo("literal");
		assertThat(boolTrue.accept(visitor)).isEqualTo("literal");
		assertThat(str1.accept(visitor)).isEqualTo("literal");
	}

	@Test
	@DisplayName("Test ScmSymbol node")
	void testScmSymbol() {
		ScmSymbol sym = ScmSymbol.of(":age");
		assertThat(sym.name()).isEqualTo(":age");
		assertThat(sym.toScmString()).isEqualTo(":age");

		assertThatThrownBy(() -> new ScmSymbol(null)).isInstanceOf(NullPointerException.class);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(sym.accept(visitor)).isEqualTo("symbol");
	}

	@Test
	@DisplayName("Test ScmList generic S-expression representation")
	void testScmList() {
		ScmList empty = new ScmList(null);
		assertThat(empty.elements()).isEmpty();
		assertThat(empty.toScmString()).isEqualTo("()");

		ScmList single = ScmList.of(ScmSymbol.of("fn"));
		assertThat(single.toScmString()).isEqualTo("(fn)");

		ScmList multi = ScmList.of(ScmSymbol.of("+"), ScmLiteral.ofInt(1), ScmLiteral.ofInt(2));
		assertThat(multi.toScmString()).isEqualTo("(+ 1 2)");

		ScmList fromList = ScmList.ofList(List.of(ScmSymbol.of("a"), ScmSymbol.of("b")));
		assertThat(fromList.elements()).hasSize(2);

		TestCountingVisitor visitor = new TestCountingVisitor();
		assertThat(multi.accept(visitor)).isEqualTo("list");
	}

	@Test
	@DisplayName("Test AlgebraicSignature constants, intervals, morphisms, and lattice axioms")
	void testAlgebraicSignature() {
		AlgebraicSignature def = AlgebraicSignature.defaultGeneral();
		assertThat(def.isPure()).isTrue();
		assertThat(def.isConstantKnown()).isFalse();
		assertThat(def.morphism()).isEqualTo(MorphismClass.GENERAL);
		assertThat(def.interval()).isEqualTo(IntervalBound.UNBOUNDED);

		AlgebraicSignature boolConst = AlgebraicSignature.ofConstantBool(true);
		assertThat(boolConst.isConstantKnown()).isTrue();
		assertThat(boolConst.constantBoolVal()).isTrue();
		assertThat(boolConst.constantIntVal()).isEqualTo(1L);
		assertThat(boolConst.constantFloatVal()).isEqualTo(1.0);
		assertThat(boolConst.monotonicity()).isEqualTo(Monotonicity.MONO_CONSTANT);

		AlgebraicSignature intConst = AlgebraicSignature.ofConstantInt(42L);
		assertThat(intConst.isConstantKnown()).isTrue();
		assertThat(intConst.constantIntVal()).isEqualTo(42L);
		assertThat(intConst.constantFloatVal()).isEqualTo(42.0);
		assertThat(intConst.constantBoolVal()).isTrue();
		assertThat(intConst.commutesWithMax()).isTrue();
		assertThat(intConst.commutesWithMin()).isTrue();

		AlgebraicSignature floatConst = AlgebraicSignature.ofConstantFloat(3.14);
		assertThat(floatConst.isConstantKnown()).isTrue();
		assertThat(floatConst.constantFloatVal()).isEqualTo(3.14);
		assertThat(floatConst.constantIntVal()).isEqualTo(3L);
		assertThat(floatConst.constantBoolVal()).isTrue();

		// Morphism classifications
		assertThat(AlgebraicSignature.ofMorphism(Multiplicity.ONE_TO_ONE).morphism())
				.isEqualTo(MorphismClass.BIJECTIVE);
		assertThat(AlgebraicSignature.ofMorphism(Multiplicity.MANY_TO_ONE).morphism())
				.isEqualTo(MorphismClass.FUNCTIONAL);
		assertThat(AlgebraicSignature.ofMorphism(Multiplicity.ONE_TO_MANY).morphism())
				.isEqualTo(MorphismClass.INJECTIVE);
		assertThat(AlgebraicSignature.ofMorphism(Multiplicity.MANY_TO_MANY).morphism())
				.isEqualTo(MorphismClass.GENERAL);

		// Algebra flags
		AlgebraicSignature ringSig = new AlgebraicSignature(IntervalBound.UNBOUNDED, MorphismClass.GENERAL,
				AlgebraicSignature.ALG_IDEMPOTENT | AlgebraicSignature.ALG_COMMUTATIVE
						| AlgebraicSignature.ALG_SEMIGROUP,
				Monotonicity.MONO_NONE,
				AlgebraicSignature.HOMO_COMMUTES_WITH_MAX | AlgebraicSignature.HOMO_COMMUTES_WITH_MIN, true, false,
				false, 0, 0.0);

		assertThat(ringSig.isIdempotent()).isTrue();
		assertThat(ringSig.isCommutative()).isTrue();
		assertThat(ringSig.isAssociative()).isTrue();
		assertThat(ringSig.commutesWithMax()).isTrue();
		assertThat(ringSig.commutesWithMin()).isTrue();

		// Monoid flag also satisfies isAssociative
		AlgebraicSignature monoidSig = new AlgebraicSignature(IntervalBound.UNBOUNDED, MorphismClass.GENERAL,
				AlgebraicSignature.ALG_MONOID, Monotonicity.MONO_NONE, AlgebraicSignature.HOMO_NONE, true, false, false,
				0, 0.0);
		assertThat(monoidSig.isAssociative()).isTrue();

		// IntervalBound factories
		IntervalBound bInt = IntervalBound.ofInt(10, 50);
		assertThat(bInt.minInt()).isEqualTo(10L);
		assertThat(bInt.maxInt()).isEqualTo(50L);
		assertThat(bInt.isBounded()).isTrue();

		IntervalBound bFloat = IntervalBound.ofFloat(1.5, 9.5);
		assertThat(bFloat.minFloat()).isEqualTo(1.5);
		assertThat(bFloat.maxFloat()).isEqualTo(9.5);
		assertThat(bFloat.isBounded()).isTrue();

		IntervalBound cFloat = IntervalBound.ofConstantFloat(2.71);
		assertThat(cFloat.minFloat()).isEqualTo(2.71);
		assertThat(cFloat.maxFloat()).isEqualTo(2.71);
	}

	@Test
	@DisplayName("EqualsVerifier contract tests for records and sealed nodes")
	void testEqualsAndHashCodeContracts() {
		EqualsVerifier.forClass(ScmReduce.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(ScmCollect.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(ScmSymbol.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(ScmLiteral.ScmInt.class).verify();
		EqualsVerifier.forClass(ScmLiteral.ScmFloat.class).verify();
		EqualsVerifier.forClass(ScmLiteral.ScmBool.class).verify();
		EqualsVerifier.forClass(ScmLiteral.ScmString.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(ScmWalk2Hop.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(IntervalBound.class).suppress(Warning.NULL_FIELDS).verify();
		EqualsVerifier.forClass(AlgebraicSignature.class).suppress(Warning.NULL_FIELDS).verify();

		EqualsVerifier.forClass(ScmCelExpr.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(Object.class, "obj1", "obj2").verify();

		EqualsVerifier.forClass(ScmVectorFilter.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();

		EqualsVerifier.forClass(ScmStreamFilter.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();

		EqualsVerifier.forClass(ScmStreamProject.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();

		EqualsVerifier.forClass(ScmProgram.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();

		EqualsVerifier.forClass(ScmList.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();

		EqualsVerifier.forClass(ScmWalk.class).suppress(Warning.NULL_FIELDS)
				.withPrefabValues(ImpScmNode.class, ScmSymbol.of("a"), ScmSymbol.of("b")).verify();
	}

	private static class TestCountingVisitor implements ImpScmVisitor<String> {
		@Override
		public String visitProgram(ScmProgram node) {
			return "program";
		}
		@Override
		public String visitWalk(ScmWalk node) {
			return "walk";
		}
		@Override
		public String visitWalk2Hop(ScmWalk2Hop node) {
			return "walk2hop";
		}
		@Override
		public String visitVectorFilter(ScmVectorFilter node) {
			return "vectorFilter";
		}
		@Override
		public String visitCelExpr(ScmCelExpr node) {
			return "celExpr";
		}
		@Override
		public String visitReduce(ScmReduce node) {
			return "reduce";
		}
		@Override
		public String visitCollect(ScmCollect node) {
			return "collect";
		}
		@Override
		public String visitLiteral(ScmLiteral node) {
			return "literal";
		}
		@Override
		public String visitSymbol(ScmSymbol node) {
			return "symbol";
		}
		@Override
		public String visitList(ScmList node) {
			return "list";
		}
	}
}
