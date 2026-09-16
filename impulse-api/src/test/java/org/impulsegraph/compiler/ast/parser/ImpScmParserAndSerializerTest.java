package org.impulsegraph.compiler.ast.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.impulsegraph.compiler.ast.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ImpScm Parser and Serializer Test Suite")
class ImpScmParserAndSerializerTest {

	@Test
	@DisplayName("Test parsing null and empty inputs")
	void testParseEmptyAndNull() {
		assertThatThrownBy(() -> ImpScmParser.parse(null)).isInstanceOf(NullPointerException.class);
		assertThat(ImpScmParser.parse("")).isNull();
		assertThat(ImpScmParser.parse("   \t  \n  ")).isNull();
		assertThat(ImpScmParser.parse("; full line comment\n   ")).isNull();
	}

	@Test
	@DisplayName("Test parsing scalar numbers: integers, decimals, and scientific notation")
	void testParseNumbers() {
		ImpScmNode n1 = ImpScmParser.parse("42");
		assertThat(n1).isInstanceOf(ScmLiteral.ScmInt.class);
		assertThat(((ScmLiteral.ScmInt) n1).value()).isEqualTo(42L);

		ImpScmNode n2 = ImpScmParser.parse("-105");
		assertThat(n2).isInstanceOf(ScmLiteral.ScmInt.class);
		assertThat(((ScmLiteral.ScmInt) n2).value()).isEqualTo(-105L);

		ImpScmNode n3 = ImpScmParser.parse("3.14159");
		assertThat(n3).isInstanceOf(ScmLiteral.ScmFloat.class);
		assertThat(((ScmLiteral.ScmFloat) n3).value()).isEqualTo(3.14159);

		ImpScmNode n4 = ImpScmParser.parse("-0.005");
		assertThat(n4).isInstanceOf(ScmLiteral.ScmFloat.class);
		assertThat(((ScmLiteral.ScmFloat) n4).value()).isEqualTo(-0.005);

		ImpScmNode n5 = ImpScmParser.parse("1e5");
		assertThat(n5).isInstanceOf(ScmLiteral.ScmFloat.class);
		assertThat(((ScmLiteral.ScmFloat) n5).value()).isEqualTo(100000.0);

		ImpScmNode n6 = ImpScmParser.parse("2.5E-3");
		assertThat(n6).isInstanceOf(ScmLiteral.ScmFloat.class);
		assertThat(((ScmLiteral.ScmFloat) n6).value()).isEqualTo(0.0025);

		ImpScmNode n7 = ImpScmParser.parse("-1.2e+4");
		assertThat(n7).isInstanceOf(ScmLiteral.ScmFloat.class);
		assertThat(((ScmLiteral.ScmFloat) n7).value()).isEqualTo(-12000.0);
	}

	@Test
	@DisplayName("Test parsing boolean literals")
	void testParseBooleans() {
		ImpScmNode t1 = ImpScmParser.parse("#t");
		assertThat(t1).isInstanceOf(ScmLiteral.ScmBool.class);
		assertThat(((ScmLiteral.ScmBool) t1).value()).isTrue();

		ImpScmNode t2 = ImpScmParser.parse("#T");
		assertThat(((ScmLiteral.ScmBool) t2).value()).isTrue();

		ImpScmNode f1 = ImpScmParser.parse("#f");
		assertThat(f1).isInstanceOf(ScmLiteral.ScmBool.class);
		assertThat(((ScmLiteral.ScmBool) f1).value()).isFalse();

		ImpScmNode f2 = ImpScmParser.parse("#F");
		assertThat(((ScmLiteral.ScmBool) f2).value()).isFalse();

		ImpScmNode f3 = ImpScmParser.parse("#x");
		assertThat(((ScmLiteral.ScmBool) f3).value()).isFalse();
	}

	@Test
	@DisplayName("Test parsing strings with escape sequences")
	void testParseStrings() {
		ImpScmNode s1 = ImpScmParser.parse("\"hello world\"");
		assertThat(s1).isInstanceOf(ScmLiteral.ScmString.class);
		assertThat(((ScmLiteral.ScmString) s1).value()).isEqualTo("hello world");

		ImpScmNode s2 = ImpScmParser.parse("'single quoted'");
		assertThat(s2).isInstanceOf(ScmLiteral.ScmString.class);
		assertThat(((ScmLiteral.ScmString) s2).value()).isEqualTo("single quoted");

		ImpScmNode s3 = ImpScmParser.parse("\"line1\\nline2\\ttab\\rcarriage\\\\slash\\\"quote\\'apos\\xother\"");
		assertThat(s3).isInstanceOf(ScmLiteral.ScmString.class);
		assertThat(((ScmLiteral.ScmString) s3).value())
				.isEqualTo("line1\nline2\ttab\rcarriage\\slash\"quote'aposxother");
	}

	@Test
	@DisplayName("Test parsing symbols and identifiers")
	void testParseSymbols() {
		ImpScmNode sym1 = ImpScmParser.parse("foo-bar");
		assertThat(sym1).isInstanceOf(ScmSymbol.class);
		assertThat(((ScmSymbol) sym1).name()).isEqualTo("foo-bar");

		ImpScmNode sym2 = ImpScmParser.parse(":weight");
		assertThat(((ScmSymbol) sym2).name()).isEqualTo(":weight");
	}

	@Test
	@DisplayName("Test parsing generic and nested lists")
	void testParseLists() {
		ImpScmNode emptyList = ImpScmParser.parse("()");
		assertThat(emptyList).isInstanceOf(ScmList.class);
		assertThat(((ScmList) emptyList).elements()).isEmpty();

		ImpScmNode list = ImpScmParser.parse("(+ 1 2)");
		assertThat(list).isInstanceOf(ScmList.class);
		ScmList scmList = (ScmList) list;
		assertThat(scmList.elements()).hasSize(3);
		assertThat(scmList.elements().get(0)).isEqualTo(ScmSymbol.of("+"));
		assertThat(scmList.elements().get(1)).isEqualTo(ScmLiteral.ofInt(1));
		assertThat(scmList.elements().get(2)).isEqualTo(ScmLiteral.ofInt(2));

		assertThatThrownBy(() -> ImpScmParser.parse("(+ 1 2")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("missing ')'");
	}

	@Test
	@DisplayName("Test parsing program constructs")
	void testParseProgram() {
		String src = """
				(program
				  (csr-walk "knows")
				  (collect-bitset))
				""";
		ImpScmNode parsed = ImpScmParser.parse(src);
		assertThat(parsed).isInstanceOf(ScmProgram.class);
		ScmProgram prog = (ScmProgram) parsed;
		assertThat(prog.steps()).hasSize(2);
		assertThat(prog.steps().get(0)).isInstanceOf(ScmWalk.class);
		assertThat(prog.steps().get(1)).isInstanceOf(ScmCollect.class);
	}

	@Test
	@DisplayName("Test parsing walk steps with relations, IDs, shaders, and sub-steps")
	void testParseWalkSteps() {
		// Forward CSR with string
		ImpScmNode w1 = ImpScmParser.parse("(csr-walk \"friends\")");
		assertThat(w1).isInstanceOf(ScmWalk.class);
		ScmWalk walk1 = (ScmWalk) w1;
		assertThat(walk1.relationName()).isEqualTo("friends");
		assertThat(walk1.direction()).isEqualTo(ScmWalk.Direction.FORWARD_CSR);

		// Reverse CSC with integer relation ID
		ImpScmNode w2 = ImpScmParser.parse("(csc-walk 105)");
		assertThat(w2).isInstanceOf(ScmWalk.class);
		ScmWalk walk2 = (ScmWalk) w2;
		assertThat(walk2.relationId()).isEqualTo(105);
		assertThat(walk2.direction()).isEqualTo(ScmWalk.Direction.REVERSE_CSC);

		// Auto direction
		ImpScmNode w3 = ImpScmParser.parse("(walk \"parentOf\")");
		assertThat(((ScmWalk) w3).direction()).isEqualTo(ScmWalk.Direction.AUTO);

		// Walk with vector-filter shader
		ImpScmNode w4 = ImpScmParser.parse("(csr-walk \"follows\" (vector-filter (> :age 18)))");
		assertThat(w4).isInstanceOf(ScmWalk.class);
		ScmWalk walk4 = (ScmWalk) w4;
		assertThat(walk4.shaderSteps()).hasSize(1);

		// Walk with multi-shader block and sub-step
		ImpScmNode w5 = ImpScmParser.parse("(csr-walk \"follows\" (shader (> :age 18) (< :weight 100)) (reduce-sum))");
		assertThat(w5).isInstanceOf(ScmWalk.class);
		ScmWalk walk5 = (ScmWalk) w5;
		assertThat(walk5.shaderSteps()).hasSize(2);
		assertThat(walk5.subSteps()).hasSize(1);
		assertThat(walk5.subSteps().get(0)).isInstanceOf(ScmReduce.class);
	}

	@Test
	@DisplayName("Test parsing vector filters, reducers, and collectors")
	void testParseFiltersReducersCollectors() {
		ImpScmNode vf = ImpScmParser.parse("(vector-filter (> :score 90))");
		assertThat(vf).isInstanceOf(ScmVectorFilter.class);

		ImpScmNode rSum = ImpScmParser.parse("(reduce-sum)");
		assertThat(rSum).isEqualTo(ScmReduce.sum());

		ImpScmNode rFirst = ImpScmParser.parse("(reduce-first)");
		assertThat(rFirst).isEqualTo(ScmReduce.first());

		ImpScmNode rCount = ImpScmParser.parse("(reduce-count)");
		assertThat(rCount).isEqualTo(ScmReduce.count());

		ImpScmNode cBitset = ImpScmParser.parse("(collect-bitset)");
		assertThat(cBitset).isEqualTo(ScmCollect.bitset());

		ImpScmNode cVector = ImpScmParser.parse("(collect-vector)");
		assertThat(cVector).isEqualTo(ScmCollect.vector());
	}

	@Test
	@DisplayName("Test ImpScmSerializer formatting and null safety")
	void testSerializer() {
		assertThat(ImpScmSerializer.serialize(null)).isEqualTo("()");

		ScmSymbol sym = ScmSymbol.of("abc");
		assertThat(ImpScmSerializer.serialize(sym)).isEqualTo("abc");

		ScmReduce red = ScmReduce.sum();
		assertThat(ImpScmSerializer.serialize(red)).isEqualTo("(reduce-sum)");
	}

	@ParameterizedTest
	@ValueSource(strings = {"(csr-walk \"follows\")", "(csc-walk \"friends\")", "(vector-filter (> :age 21))",
			"(reduce-sum)", "(reduce-first)", "(reduce-count)", "(collect-bitset)", "(collect-vector)", "(+ 1 2 3)",
			"#t", "#f", "\"round-trip-string\"", "4242", "3.14"})
	@DisplayName("Test round-trip parsing and serialization identity")
	void testRoundTripSerialization(String expression) {
		ImpScmNode node1 = ImpScmParser.parse(expression);
		assertThat(node1).isNotNull();

		String serialized = ImpScmSerializer.serialize(node1);
		ImpScmNode node2 = ImpScmParser.parse(serialized);

		assertThat(node2).isEqualTo(node1);
	}
}
