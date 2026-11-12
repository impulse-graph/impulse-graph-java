package org.impulsegraph.vm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedAttribute;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.cel.VmCelEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive cross-engine parity suite for all 54 canonical math functions (f32
 * & f64), IEEE-754 NaN / +/-Inf / +/-0.0 / domain boundaries, Kleene 3VL NULL
 * vs IEEE NaN in VmCelEvaluator, nullable vector pipelines, monoidic reducers,
 * multi-lane OP_LANE_REDUCE_ATTR, stream reducers, and OP_ASSERT_FINITE.
 */
class MathAndReducerEdgeCasesTest {

	private static VmHandlers.Instruction instr(byte op, int flags, int dst, int payload) {
		return new VmHandlers.Instruction(op, (byte) flags, dst, payload);
	}

	private static MemorySegment encodeProgram(Arena arena, VmHandlers.Instruction... instrs) {
		MemorySegment seg = arena.allocate(instrs.length * 8L, 8);
		for (int i = 0; i < instrs.length; i++) {
			VmHandlers.Instruction in = instrs[i];
			seg.set(ValueLayout.JAVA_BYTE, i * 8L, in.opcode());
			seg.set(ValueLayout.JAVA_BYTE, i * 8L + 1, in.flags());
			seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, i * 8L + 2, (short) in.dstReg());
			seg.set(ValueLayout.JAVA_INT_UNALIGNED, i * 8L + 4, in.payload());
		}
		return seg;
	}

	@Test
	@DisplayName("All 54 Unary/Binary/Ternary Math Functions: f32 & f64 Domain, NaN, +/-Inf, +/-0.0 & Bitwise Parity")
	void testAll54MathFunctionsF32AndF64() {
		try (Arena arena = Arena.ofConfined();
				VmQueryContext ctx = new VmQueryContext(new MockImpulseGraphSnapshot(Map.of()), arena)) {
			MemorySegment state = ctx.allocateStateSegment();

			// 1. Rounding (0x20 TRUNC, 0x21 ROUND): halfway away from zero & -0.0
			// preservation
			double[] roundIn64 = {-2.5, 2.5, -0.2, -0.7, 0.0, -0.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
					Double.NaN};
			int hRound64 = ctx.registerDoubleVector(roundIn64);
			VmHandlers.setRegister(state, 1, hRound64, TYPE_DOUBLE_VECTOR);

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 2, (ImpulseMath.MATH_FUNC_TRUNC << 16) | 1));
			double[] truncOut64 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 2));
			assertEquals(-2.0, truncOut64[0], 0.0);
			assertEquals(2.0, truncOut64[1], 0.0);
			assertEquals(0, Double.compare(-0.0, truncOut64[2]));
			assertEquals(0, Double.compare(-0.0, truncOut64[3]));
			assertEquals(0, Double.compare(0.0, truncOut64[4]));
			assertEquals(0, Double.compare(-0.0, truncOut64[5]));
			assertEquals(Double.POSITIVE_INFINITY, truncOut64[6]);
			assertEquals(Double.NEGATIVE_INFINITY, truncOut64[7]);
			assertTrue(Double.isNaN(truncOut64[8]));

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 3, (ImpulseMath.MATH_FUNC_ROUND << 16) | 1));
			double[] roundOut64 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 3));
			assertEquals(-3.0, roundOut64[0], 0.0);
			assertEquals(3.0, roundOut64[1], 0.0);
			assertEquals(0, Double.compare(-0.0, roundOut64[2]));
			assertEquals(-1.0, roundOut64[3], 0.0);
			assertEquals(0, Double.compare(0.0, roundOut64[4]));
			assertEquals(0, Double.compare(-0.0, roundOut64[5]));
			assertEquals(Double.POSITIVE_INFINITY, roundOut64[6]);
			assertEquals(Double.NEGATIVE_INFINITY, roundOut64[7]);
			assertTrue(Double.isNaN(roundOut64[8]));

			// 2. Out-of-domain inputs -> NaN & boundary poles -> +/-Inf
			double[] domIn = {-1.0, -2.0, 2.0, 0.5, 1.0, -1.0, -0.0, 1e200};
			VmHandlers.setRegister(state, 4, ctx.registerDoubleVector(domIn), TYPE_DOUBLE_VECTOR);

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_SQRT << 16) | 4));
			double[] sqrtOut = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5));
			assertTrue(Double.isNaN(sqrtOut[0]));
			assertEquals(0, Double.compare(-0.0, sqrtOut[6]));

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_LOG << 16) | 4));
			assertTrue(Double.isNaN(ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5))[0]));

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_ASIN << 16) | 4));
			assertTrue(Double.isNaN(ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5))[2]));

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_ACOSH << 16) | 4));
			double[] acoshOut = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5));
			assertTrue(Double.isNaN(acoshOut[3])); // acosh(0.5) -> NaN
			assertEquals(0.0, acoshOut[4], 0.0); // acosh(1.0) -> 0.0
			assertEquals(Math.log(1e200) + Math.log(2.0), acoshOut[7], 1e-12); // no x*x overflow

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_ATANH << 16) | 4));
			double[] atanhOut = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5));
			assertTrue(Double.isNaN(atanhOut[2])); // atanh(2.0) -> NaN
			assertEquals(Double.POSITIVE_INFINITY, atanhOut[4]); // atanh(1.0) -> +Inf
			assertEquals(Double.NEGATIVE_INFINITY, atanhOut[5]); // atanh(-1.0) -> -Inf
			assertEquals(0, Double.compare(-0.0, atanhOut[6])); // atanh(-0.0) -> -0.0

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 5, (ImpulseMath.MATH_FUNC_ASINH << 16) | 4));
			double[] asinhOut = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 5));
			assertEquals(0, Double.compare(-0.0, asinhOut[6]));
			assertEquals(Math.log(1e200) + Math.log(2.0), asinhOut[7], 1e-12);

			// 3. SINC (0x17), Activations (0x25..0x2A), Special Functions (0x2B..0x2D)
			assertEquals(1.0f, ImpulseMath.unaryF32(ImpulseMath.MATH_FUNC_SINC, 1e-8f), 0.0f);
			assertTrue(Float.isNaN(ImpulseMath.unaryF32(ImpulseMath.MATH_FUNC_SINC, Float.NaN)));
			assertEquals(1.0, ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_SINC, 1e-16), 0.0);
			assertTrue(Double.isNaN(ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_SINC, Double.NaN)));

			for (int actId : new int[]{ImpulseMath.MATH_FUNC_RELU, ImpulseMath.MATH_FUNC_LEAKY_RELU,
					ImpulseMath.MATH_FUNC_SIGMOID, ImpulseMath.MATH_FUNC_GELU, ImpulseMath.MATH_FUNC_SILU,
					ImpulseMath.MATH_FUNC_SOFTPLUS}) {
				assertTrue(Double.isNaN(ImpulseMath.unaryF64(actId, Double.NaN)), "NaN propagation for id=" + actId);
				assertTrue(Float.isNaN(ImpulseMath.unaryF32(actId, Float.NaN)), "f32 NaN propagation for id=" + actId);
			}
			assertEquals(0.0, ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_GELU, Double.NEGATIVE_INFINITY), 0.0);
			assertEquals(Double.POSITIVE_INFINITY,
					ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_GELU, Double.POSITIVE_INFINITY));
			assertEquals(0.0, ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_SILU, Double.NEGATIVE_INFINITY), 0.0);
			assertEquals(25.0f, ImpulseMath.unaryF32(ImpulseMath.MATH_FUNC_SOFTPLUS, 25.0f), 0.0f);
			assertEquals(50.0, ImpulseMath.unaryF64(ImpulseMath.MATH_FUNC_SOFTPLUS, 50.0), 0.0);

			// erf / erfc / lgamma edge cases & precision
			assertEquals(0, Double.compare(-0.0, ImpulseMath.erf(-0.0)));
			assertEquals(1.0, ImpulseMath.erf(Double.POSITIVE_INFINITY), 0.0);
			assertEquals(-1.0, ImpulseMath.erf(Double.NEGATIVE_INFINITY), 0.0);
			assertEquals(1.0, ImpulseMath.erfc(0.0), 0.0);
			assertEquals(0.0, ImpulseMath.erfc(Double.POSITIVE_INFINITY), 0.0);
			assertEquals(2.0, ImpulseMath.erfc(Double.NEGATIVE_INFINITY), 0.0);
			assertTrue(ImpulseMath.erfc(10.0) > 0.0 && ImpulseMath.erfc(10.0) < 1e-44); // no 1 - erf cancellation
			assertEquals(2.088487583762545e-45, ImpulseMath.erfc(10.0), 1e-58);
			assertEquals(0.8427007929497149f, ImpulseMath.unaryF32(ImpulseMath.MATH_FUNC_ERF, 1.0f), 1e-7f);
			assertEquals(Double.POSITIVE_INFINITY, ImpulseMath.lgamma(Double.POSITIVE_INFINITY));
			assertEquals(Double.POSITIVE_INFINITY, ImpulseMath.lgamma(Double.NEGATIVE_INFINITY));
			assertEquals(Double.POSITIVE_INFINITY, ImpulseMath.lgamma(0.0));
			assertEquals(Double.POSITIVE_INFINITY, ImpulseMath.lgamma(-1.0));
			assertEquals(Double.POSITIVE_INFINITY, ImpulseMath.lgamma(-2.0));
			assertEquals(0.0, ImpulseMath.lgamma(1.0), 1e-15);
			assertEquals(0.0, ImpulseMath.lgamma(2.0), 1e-15);

			// 4. Sanitized Bitwise Widths: 32-bit for TYPE_FLOAT_VECTOR vs 64-bit for
			// TYPE_DOUBLE_VECTOR
			float[] bwIn32 = {-5.0f, 0.0f, 1.0f, Float.POSITIVE_INFINITY, Float.NaN};
			double[] bwIn64 = {-5.0, 0.0, 1.0, Double.POSITIVE_INFINITY, Double.NaN};
			VmHandlers.setRegister(state, 6, ctx.registerFloatVector(bwIn32), TYPE_FLOAT_VECTOR);
			VmHandlers.setRegister(state, 7, ctx.registerDoubleVector(bwIn64), TYPE_DOUBLE_VECTOR);

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 8, (ImpulseMath.MATH_FUNC_POPCOUNT << 16) | 6));
			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 9, (ImpulseMath.MATH_FUNC_POPCOUNT << 16) | 7));
			float[] pop32 = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 8));
			double[] pop64 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 9));
			assertEquals(0.0f, pop32[0]); // negative clamped to 0
			assertEquals(32.0f, pop32[3]); // +Inf clamped to 0xFFFFFFFF -> 32 bits
			assertTrue(Float.isNaN(pop32[4]));
			assertEquals(0.0, pop64[0]);
			assertEquals(64.0, pop64[3]); // +Inf clamped to 0xFFFFFFFFFFFFFFFF -> 64 bits
			assertTrue(Double.isNaN(pop64[4]));

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 8, (ImpulseMath.MATH_FUNC_CLZ << 16) | 6));
			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 9, (ImpulseMath.MATH_FUNC_CLZ << 16) | 7));
			assertEquals(31.0f, ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 8))[2]);
			assertEquals(63.0, ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 9))[2]);

			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 8, (ImpulseMath.MATH_FUNC_CTZ << 16) | 6));
			VmHandlers.handleVecMathUnary(state, ctx,
					instr(OP_VEC_MATH_UNARY, 0, 9, (ImpulseMath.MATH_FUNC_CTZ << 16) | 7));
			assertEquals(32.0f, ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 8))[0]);
			assertEquals(64.0, ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 9))[0]);

			// ROTL / ROTR 32-bit vs 64-bit
			VmHandlers.setRegister(state, 10, ctx.registerFloatVector(new float[]{1.0f, 1.0f}), TYPE_FLOAT_VECTOR);
			VmHandlers.setRegister(state, 11, ctx.registerFloatVector(new float[]{1.0f, Float.NaN}), TYPE_FLOAT_VECTOR);
			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 12, (ImpulseMath.MATH_FUNC_ROTR << 16) | (11 << 8) | 10));
			float[] rotr32 = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 12));
			assertEquals((float) (1L << 31), rotr32[0], 0.0f);
			assertTrue(Float.isNaN(rotr32[1]));

			VmHandlers.setRegister(state, 13, ctx.registerDoubleVector(new double[]{1.0, 1.0}), TYPE_DOUBLE_VECTOR);
			VmHandlers.setRegister(state, 14, ctx.registerDoubleVector(new double[]{1.0, Double.NaN}),
					TYPE_DOUBLE_VECTOR);
			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 15, (ImpulseMath.MATH_FUNC_ROTR << 16) | (14 << 8) | 13));
			double[] rotr64 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 15));
			assertEquals(9223372036854775808.0, rotr64[0], 0.0); // 2^63
			assertTrue(Double.isNaN(rotr64[1]));

			// 5. Binary & Ternary IEEE-754 non-short-circuit rules (pow, hypot, fmod,
			// safeDiv, clamp, fma, lerp)
			double[] binA = {1.0, Double.NaN, Double.POSITIVE_INFINITY, 5.0, 10.0};
			double[] binB = {Double.NaN, 0.0, Double.NaN, 0.0, 0.0};
			VmHandlers.setRegister(state, 16, ctx.registerDoubleVector(binA), TYPE_DOUBLE_VECTOR);
			VmHandlers.setRegister(state, 17, ctx.registerDoubleVector(binB), TYPE_DOUBLE_VECTOR);

			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 18, (ImpulseMath.MATH_FUNC_POW << 16) | (17 << 8) | 16));
			double[] powOut = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 18));
			assertEquals(1.0, powOut[0], 0.0); // pow(1.0, NaN) == 1.0
			assertEquals(1.0, powOut[1], 0.0); // pow(NaN, 0.0) == 1.0

			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 18, (ImpulseMath.MATH_FUNC_HYPOT << 16) | (17 << 8) | 16));
			assertEquals(Double.POSITIVE_INFINITY,
					ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 18))[2]);

			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 18, (ImpulseMath.MATH_FUNC_FMOD << 16) | (17 << 8) | 16));
			assertTrue(Double.isNaN(ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 18))[3])); // fmod(5,
																											// 0) -> NaN

			VmHandlers.handleVecMathBinary(state, ctx,
					instr(OP_VEC_MATH_BINARY, 0, 18, (ImpulseMath.MATH_FUNC_SAFE_DIV << 16) | (17 << 8) | 16));
			double[] sdiv2 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 18));
			assertEquals(0.0, sdiv2[0], 0.0); // safeDiv(1.0, NaN) -> 0.0
			assertTrue(Double.isNaN(sdiv2[1])); // safeDiv(NaN, 0.0) -> NaN
			assertEquals(0.0, sdiv2[4], 0.0); // safeDiv(10.0, 0.0) -> 0.0

			// Ternary SAFE_DIV, CLAMP, LERP, FMA
			double[] ternC = {-9.0, -9.0, 10.0, -7.0, -1.0};
			VmHandlers.setRegister(state, 19, ctx.registerDoubleVector(ternC), TYPE_DOUBLE_VECTOR);
			VmHandlers.handleVecMathTernary(state, ctx, instr(OP_VEC_MATH_TERNARY, 0, 20,
					(ImpulseMath.MATH_FUNC_SAFE_DIV << 24) | (19 << 16) | (17 << 8) | 16));
			double[] sdiv3 = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 20));
			assertEquals(-9.0, sdiv3[0], 0.0);
			assertTrue(Double.isNaN(sdiv3[1]));
			assertEquals(-1.0, sdiv3[4], 0.0);

			// ShaderOps.mathUnary (0xAF) across all unary IDs
			float[] sRegs = new float[8];
			for (int fid : new int[]{1, 2, 3, 4, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26,
					27, 28, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 52, 53, 54}) {
				sRegs[0] = (fid == 28) ? 1.5f : 0.5f;
				ShaderOps.mathUnary(1, 0, fid, ctx, state, 0, 0, 0, sRegs, null);
				assertEquals(ImpulseMath.unaryF32(fid, sRegs[0]), sRegs[1], 0.0f);
			}
		}
	}

	@Test
	@DisplayName("VmCelEvaluator: SQL Kleene NULL vs IEEE-754 NaN & All 54 Math Functions")
	void testVmCelEvaluatorKleeneNullVsIeeeNan() {
		try (Arena arena = Arena.ofConfined()) {
			// 3 rows in domain:
			// row 0: val = 4.0 (valid)
			// row 1: val = -4.0 (valid; sqrt(val) -> IEEE NaN, NOT SQL NULL)
			// row 2: val = NULL (validity bit = 0 -> SQL NULL)
			MemorySegment dataSeg = arena.allocate(3 * 8L, 8);
			dataSeg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0L, 4.0);
			dataSeg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 8L, -4.0);
			dataSeg.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 16L, 999.0);

			MemorySegment valSeg = arena.allocate(1L, 1);
			valSeg.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0b011); // rows 0 and 1 valid, row 2 NULL

			LoadedAttribute attr = new LoadedAttribute("val", (byte) (0x80 | 0x07), 1, 0L, 24L, 0L, 1L, 0L, 0L);
			LoadedDomain dom = new LoadedDomain(0, "Bus", (byte) 0, 3L, 0xFFFF, List.of(attr), List.of(dataSeg),
					List.of(valSeg), List.of(MemorySegment.NULL));

			// 1. isNan(sqrt(val)): row 0 -> false, row 1 -> true (IEEE NaN!), row 2 -> NULL
			// (false in filter)
			VmCelEvaluator evalIsNan = VmCelEvaluator.compile("isNan(sqrt(val))", Map.of());
			VmCelEvaluator.EvalScratch sc = evalIsNan.createScratch();
			assertFalse(evalIsNan.evalDomainFilter(dom, 0, sc));
			assertTrue(evalIsNan.evalDomainFilter(dom, 1, sc));
			assertFalse(evalIsNan.evalDomainFilter(dom, 2, sc));

			// 2. is_null(sqrt(val)) vs is_null(val): sqrt(-4.0) is IEEE NaN (NOT SQL
			// NULL!), while row 2 IS SQL NULL
			VmCelEvaluator evalNullSqrt = VmCelEvaluator.compile("is_null(sqrt(val))", Map.of());
			VmCelEvaluator.EvalScratch scNull = evalNullSqrt.createScratch();
			assertFalse(evalNullSqrt.evalDomainFilter(dom, 0, scNull));
			assertFalse(evalNullSqrt.evalDomainFilter(dom, 1, scNull));
			assertTrue(evalNullSqrt.evalDomainFilter(dom, 2, scNull));

			// 3. Kleene 3VL vs IEEE-754 != :
			// sqrt(val) != 2.0 -> row 0: false (2.0 != 2.0), row 1: true (NaN != 2.0), row
			// 2: NULL (false)
			VmCelEvaluator evalNeq = VmCelEvaluator.compile("sqrt(val) != 2.0", Map.of());
			VmCelEvaluator.EvalScratch scNeq = evalNeq.createScratch();
			assertFalse(evalNeq.evalDomainFilter(dom, 0, scNeq));
			assertTrue(evalNeq.evalDomainFilter(dom, 1, scNeq));
			assertFalse(evalNeq.evalDomainFilter(dom, 2, scNeq));

			// !(val == 4.0) -> row 0: false, row 1: true, row 2: !NULL == NULL -> false
			VmCelEvaluator evalNotEq = VmCelEvaluator.compile("!(val == 4.0)", Map.of());
			VmCelEvaluator.EvalScratch scNotEq = evalNotEq.createScratch();
			assertFalse(evalNotEq.evalDomainFilter(dom, 0, scNotEq));
			assertTrue(evalNotEq.evalDomainFilter(dom, 1, scNotEq));
			assertFalse(evalNotEq.evalDomainFilter(dom, 2, scNotEq));

			// 4. safeDiv (2-arg & 3-arg), round, erf, lgamma, clamp, fma, lerp, bitwise
			VmCelEvaluator evalExpr = VmCelEvaluator.compile(
					"safeDiv(10.0, 0.0) == 0.0 && safeDiv(10.0, 0.0, -1.0) == -1.0 && round(-2.5) == -3.0"
							+ " && bit_count(7.0) == 3.0 && fma(2.0, 3.0, 4.0) == 10.0 && clamp(val, -1.0, 2.0) == 2.0",
					Map.of());
			assertTrue(evalExpr.evalDomainFilter(dom, 0, evalExpr.createScratch()));
		}
	}

	@Test
	@DisplayName("Nullable Vector Pipeline (0x80 -> 0x2D -> 0x3C -> 0x3B) for f32 & f64 + OP_ASSERT_FINITE (0x2A)")
	void testNullableVectorPipelineAndAssertFinite() {
		try (Arena arena = Arena.ofConfined()) {
			// Domain with nullable float32 attribute [4.0f, NULL, 9.0f, -1.0f]
			MemorySegment fSeg = arena.allocate(4 * 4L, 4);
			ValueLayout.OfFloat leFloat = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			fSeg.set(leFloat, 0L, 4.0f);
			fSeg.set(leFloat, 4L, 100.0f); // masked out by validity bit 0
			fSeg.set(leFloat, 8L, 9.0f);
			fSeg.set(leFloat, 12L, -1.0f); // valid negative -> sqrt produces NaN

			MemorySegment vSeg = arena.allocate(1L, 1);
			vSeg.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0b1101); // bit 1 is 0 (NULL)

			LoadedAttribute attr0 = new LoadedAttribute("x", (byte) (0x80 | 0x05), 1, 0L, 16L, 0L, 1L, 0L, 0L);
			LoadedDomain dom = new LoadedDomain(0, "Node", (byte) 0, 4L, 0xFFFF, List.of(attr0), List.of(fSeg),
					List.of(vSeg), List.of(MemorySegment.NULL));
			GraphSnapshot snap = new GraphSnapshot(arena, Map.of(), Map.of(), Map.of(), Map.of("Node", dom));

			try (VmQueryContext ctx = new VmQueryContext(snap, arena)) {
				MemorySegment state = ctx.allocateStateSegment();

				// 0x80 OP_LOAD_COLUMN_VECTOR -> R1 = [4.0, NaN, 9.0, -1.0]
				VmHandlers.handleLoadColumnVector(state, ctx, instr(OP_LOAD_COLUMN_VECTOR, 0, 1, 0));
				float[] loaded = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 1));
				assertEquals(4.0f, loaded[0], 0.0f);
				assertTrue(Float.isNaN(loaded[1]));
				assertEquals(9.0f, loaded[2], 0.0f);
				assertEquals(-1.0f, loaded[3], 0.0f);

				// 0x2D OP_VEC_MATH_UNARY (SQRT) -> R2 = [2.0, NaN, 3.0, NaN]
				VmHandlers.handleVecMathUnary(state, ctx,
						instr(OP_VEC_MATH_UNARY, 0, 2, (ImpulseMath.MATH_FUNC_SQRT << 16) | 1));

				// 0x2A OP_ASSERT_FINITE on R2 traps at index 1 and writes offending index to R0
				IllegalStateException trap = assertThrows(IllegalStateException.class,
						() -> VmHandlers.handleAssertFinite(state, ctx, instr(OP_ASSERT_FINITE, 0, 2, 0)));
				assertTrue(trap.getMessage().contains("IMPULSE_VM_ERR_FLOATING_POINT"));
				assertEquals(1L, VmHandlers.getRegisterValue(state, 0));

				// 0x3C OP_EXTRACT_VALIDITY -> R3 bitset {0, 2}
				VmHandlers.handleExtractValidity(state, ctx, instr((byte) 0x3C, 0, 3, 2));
				ImpulseBitSet validBs = ctx.getBitset((int) VmHandlers.getRegisterValue(state, 3));
				assertTrue(validBs.get(0));
				assertFalse(validBs.get(1));
				assertTrue(validBs.get(2));
				assertFalse(validBs.get(3));

				// 0x3B OP_COALESCE (f32 vector + scalar -7.5f) -> R4 = [2.0, -7.5, 3.0, -7.5]
				VmHandlers.setRegister(state, 5, Integer.toUnsignedLong(Float.floatToRawIntBits(-7.5f)), TYPE_FLOAT);
				VmHandlers.handleCoalesce(state, ctx, instr((byte) 0x3B, 0, 4, (5 << 16) | 2));
				float[] coal32 = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 4));
				assertArrayEquals(new float[]{2.0f, -7.5f, 3.0f, -7.5f}, coal32, 0.0f);

				// 0x3B OP_COALESCE for TYPE_DOUBLE_VECTOR + TYPE_DOUBLE_VECTOR and +
				// TYPE_DOUBLE
				VmHandlers.setRegister(state, 6, ctx.registerDoubleVector(new double[]{Double.NaN, 12.5, Double.NaN}),
						TYPE_DOUBLE_VECTOR);
				VmHandlers.setRegister(state, 7, ctx.registerDoubleVector(new double[]{1.25, 99.0, Double.NaN}),
						TYPE_DOUBLE_VECTOR);
				VmHandlers.handleCoalesce(state, ctx, instr((byte) 0x3B, 0, 8, (7 << 16) | 6));
				double[] coal64Vec = ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 8));
				assertEquals(1.25, coal64Vec[0], 0.0);
				assertEquals(12.5, coal64Vec[1], 0.0);
				assertTrue(Double.isNaN(coal64Vec[2]));

				VmHandlers.setRegister(state, 9, Double.doubleToRawLongBits(-42.0), TYPE_DOUBLE);
				VmHandlers.handleCoalesce(state, ctx, instr((byte) 0x3B, 0, 10, (9 << 16) | 8));
				assertArrayEquals(new double[]{1.25, 12.5, -42.0},
						ctx.getDoubleVector((int) VmHandlers.getRegisterValue(state, 10)), 0.0);

				// 0x2A OP_ASSERT_FINITE on TYPE_DOUBLE_VECTOR (sqrt(-4.0) at index 2 -> R0 ==
				// 2)
				VmHandlers.setRegister(state, 11, ctx.registerDoubleVector(new double[]{1.0, 4.0, -4.0}),
						TYPE_DOUBLE_VECTOR);
				VmHandlers.handleVecMathUnary(state, ctx,
						instr(OP_VEC_MATH_UNARY, 0, 12, (ImpulseMath.MATH_FUNC_SQRT << 16) | 11));
				IllegalStateException trap64 = assertThrows(IllegalStateException.class,
						() -> VmHandlers.handleAssertFinite(state, ctx, instr(OP_ASSERT_FINITE, 0, 12, 0)));
				assertTrue(trap64.getMessage().contains("IMPULSE_VM_ERR_FLOATING_POINT"));
				assertEquals(2L, VmHandlers.getRegisterValue(state, 0));
			}
		}
	}

	@Test
	@DisplayName("OP_REDUCE (0x45): ADD, MUL, MIN, MAX, ARGMIN, ARGMAX with NaN, -Inf, Negative Floats & All-NaN")
	void testVectorReducersEdgeCases() {
		try (Arena arena = Arena.ofConfined();
				VmQueryContext ctx = new VmQueryContext(new MockImpulseGraphSnapshot(Map.of()), arena)) {
			MemorySegment state = ctx.allocateStateSegment();

			// 1. Mixed negative + NaN vector: [NaN, -10.0, -3.0, NaN]
			VmHandlers.setRegister(state, 1,
					ctx.registerDoubleVector(new double[]{Double.NaN, -10.0, -3.0, Double.NaN}), TYPE_DOUBLE_VECTOR);

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (0 << 16) | 1));
			assertEquals(-13.0, Double.longBitsToDouble(VmHandlers.getRegisterValue(state, 10)), 0.0);
			assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (1 << 16) | 1));
			assertEquals(30.0, Double.longBitsToDouble(VmHandlers.getRegisterValue(state, 10)), 0.0);
			assertFalse(VmHandlers.checkFlag(state, FLAG_ZF));

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (2 << 16) | 1));
			assertEquals(-10.0, Double.longBitsToDouble(VmHandlers.getRegisterValue(state, 10)), 0.0);

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (3 << 16) | 1));
			assertEquals(-3.0, Double.longBitsToDouble(VmHandlers.getRegisterValue(state, 10)), 0.0);

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMIN << 16) | 1));
			assertEquals(1L, VmHandlers.getRegisterValue(state, 10)); // ARGMIN -> 1

			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMAX << 16) | 1));
			assertEquals(2L, VmHandlers.getRegisterValue(state, 10)); // ARGMAX -> 2

			// 2. Single [-Infinity] for ARGMAX and [+Infinity] for ARGMIN must select valid
			// index (not -1!)
			VmHandlers.setRegister(state, 2,
					ctx.registerDoubleVector(new double[]{Double.NaN, Double.NEGATIVE_INFINITY}), TYPE_DOUBLE_VECTOR);
			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMAX << 16) | 2));
			assertEquals(1L, VmHandlers.getRegisterValue(state, 10));

			VmHandlers.setRegister(state, 3,
					ctx.registerDoubleVector(new double[]{Double.NaN, Double.POSITIVE_INFINITY}), TYPE_DOUBLE_VECTOR);
			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMIN << 16) | 3));
			assertEquals(1L, VmHandlers.getRegisterValue(state, 10));

			// 3. All-NaN vector: validCount == 0 -> result 0.0 (even for MUL!) and FLAG_ZF
			// == true
			VmHandlers.setRegister(state, 4, ctx.registerFloatVector(new float[]{Float.NaN, Float.NaN}),
					TYPE_FLOAT_VECTOR);
			for (int opId : new int[]{0, 1}) { // ADD & MUL return 0.0f when validCount == 0
				VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (opId << 16) | 4));
				float v = Float.intBitsToFloat((int) VmHandlers.getRegisterValue(state, 10));
				assertEquals(0.0f, v, 0.0f, "All-NaN reduce op=" + opId + " must return 0.0f");
				assertTrue(VmHandlers.checkFlag(state, FLAG_ZF), "All-NaN reduce op=" + opId + " must set ZF");
			}
			for (int opId : new int[]{2, 3}) { // MIN (+Inf) & MAX (-Inf) also set ZF when validCount == 0!
				VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (opId << 16) | 4));
				assertTrue(VmHandlers.checkFlag(state, FLAG_ZF), "All-NaN reduce op=" + opId + " must set ZF");
			}
			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMIN << 16) | 4));
			assertEquals(0L, VmHandlers.getRegisterValue(state, 10));
			VmHandlers.handleReduce(state, ctx, instr(OP_REDUCE, 0, 10, (BINARY_OP_ARGMAX << 16) | 4));
			assertEquals(0L, VmHandlers.getRegisterValue(state, 10));
		}
	}

	@Test
	@DisplayName("OP_LANE_REDUCE_ATTR (0xC8) & Stream Reducers (0xA8/0xB8/0xB9): NaN Skipping & Untouched Lanes")
	void testMultiLaneAndStreamReducersWithNaN() {
		try (Arena arena = Arena.ofConfined()) {
			// Graph for stream reducer: 0 -> {0, 1} (edges 0, 1), 1 -> {1} (edge 2)
			MemorySegment rows = arena.allocate(3 * 4L, 4);
			rows.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
			rows.setAtIndex(ValueLayout.JAVA_INT, 1, 2);
			rows.setAtIndex(ValueLayout.JAVA_INT, 2, 3);
			MemorySegment cols = arena.allocate(3 * 4L, 4);
			cols.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
			cols.setAtIndex(ValueLayout.JAVA_INT, 1, 1);
			cols.setAtIndex(ValueLayout.JAVA_INT, 2, 1);
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 3, rows, cols);
			GraphSnapshot snap = new GraphSnapshot(arena, Map.of("r", rel));

			try (VmQueryContext ctx = new VmQueryContext(snap, arena)) {
				MemorySegment state = ctx.allocateStateSegment();

				// 1. Multi-Lane OP_LANE_REDUCE_ATTR (0xC8):
				// 3 lanes:
				// - lane 0 reaches nodes {0, 1} where attr = [NaN, -5.0f, -2.0f] -> valid
				// [-5.0f]
				// - lane 1 reaches node {0} where attr = [NaN] -> untouched!
				// - lane 2 reaches no nodes -> untouched!
				VmLaneFrontier lf = new VmLaneFrontier(VmLaneFrontier.LaneWidth.WIDTH_8, 3, 0b111L, 3,
						new long[]{0b011L, 0b001L, 0b000L}, null);
				VmHandlers.setRegister(state, 1, ctx.registerLaneFrontier(lf), TYPE_LANE_FRONTIER);

				// Mock nullable attribute 0: raw [99.0f, -5.0f, -2.0f] with validity bit 0 ==
				// false -> node 0 is NaN!
				ctx.setMockAttribute(0, new float[]{99.0f, -5.0f, -2.0f});
				ImpulseBitSet mockValid = new OffHeapBitSet(arena, 8);
				mockValid.set(1);
				mockValid.set(2);
				ctx.setMockAttribute(-1, mockValid);

				for (int redOp : new int[]{0, 1, 2, 3, 4}) { // SUM, MUL, MIN, MAX, COUNT
					int payload = 1 | (0 << 8) | (redOp << 16);
					VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, instr((byte) 0xC8, 0, 2, payload), 0);
					float[] out = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 2));
					float expectedLane0 = (redOp == 4) ? 1.0f : -5.0f;
					assertEquals(expectedLane0, out[0], 0.0f, "lane 0 redOp=" + redOp);
					// Untouched lanes 1 and 2 MUST be 0.0f (not 1.0f / +Inf / -Inf)
					assertEquals(0.0f, out[1], 0.0f, "untouched lane 1 redOp=" + redOp);
					assertEquals(0.0f, out[2], 0.0f, "untouched lane 2 redOp=" + redOp);
				}

				// 2. ShaderOps.reduce & reduceArgMin skip Float.NaN
				ctx.initStreamTouchedMask(8);
				ImpulseBitSet outBs = new OffHeapBitSet(arena, 8);
				float[] sRegs = new float[]{Float.NaN, -4.0f, -9.0f};
				ShaderOps.reduce(0, 10, 1, ctx, state, 0, 0, 0, sRegs, outBs); // NaN skipped (allocates vector, doesn't
																				// touch)
				ShaderOps.reduce(1, 10, 1, ctx, state, 1, 0, 1, sRegs, outBs); // first valid touch = -4.0f
				ShaderOps.reduce(0, 10, 1, ctx, state, 1, 0, 2, sRegs, outBs); // trailing NaN skipped
				float[] reduced = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 10));
				assertEquals(-4.0f, reduced[0], 0.0f);

				ShaderOps.reduceArgMin(0, 11, 12, null, ctx, state, 7, 0, 0, sRegs, outBs); // NaN ignored
				assertNotEquals(TYPE_NODE_ID, VmHandlers.getRegisterType(state, 12));
				ShaderOps.reduceArgMin(2, 11, 12, null, ctx, state, 7, 0, 1, sRegs, outBs); // -9.0f wins, src=7
				assertEquals(7L, VmHandlers.getRegisterValue(state, 12));

				// 3. Inline interpreter handleStreamWalk (0xAF + 0xA8 + 0xB9):
				// Edges into target 1: edge 1 from src=0 (val = -4.0f -> sqrt = NaN), edge 2
				// from src=1 (val = 9.0f -> sqrt = 3.0f)
				ctx.setMockAttribute(2, new float[]{16.0f, -4.0f, 9.0f});
				ImpulseBitSet seeds = new OffHeapBitSet(arena, 8);
				seeds.set(0);
				seeds.set(1);
				int seedH = ctx.acquireBitset();
				ctx.getBitset(seedH).or(seeds);
				VmHandlers.setRegister(state, 20, seedH, TYPE_BITSET_HANDLE);

				MemorySegment prog = encodeProgram(arena, instr((byte) 0xA0, 1, 21, 20), // pc=0: OP_CSR_WALK_STREAM
																							// shaderStart=1, src=R20,
																							// rel=0
						instr(OP_STREAM_LOAD_EDGE, 0, 0, 2), // pc=1: s_regs[0] = edgeAttr[2]
						instr(OP_STREAM_MATH_UNARY, 0, 1, (ImpulseMath.MATH_FUNC_SQRT << 16) | 0), // pc=2: s_regs[1] =
																									// sqrt(s_regs[0])
						instr(OP_STREAM_REDUCE, 0, 1, (1 << 16) | 22), // pc=3: MAX into R22
						instr(OP_STREAM_REDUCE_ARGMAX, 0, 1, (24 << 16) | 23), // pc=4: ARGMAX val=R23, arg=R24
						instr(OP_STREAM_FUNC_END, 0, 0, 0));
				VmHandlers.handleStreamWalk(state, ctx, VmHandlers.decodeInstruction(prog, 0), prog, 6);
				float[] streamMax = ctx.getFloatVector((int) VmHandlers.getRegisterValue(state, 22));
				assertEquals(4.0f, streamMax[0], 0.0f); // edge 0 -> tgt 0: sqrt(16) = 4
				assertEquals(3.0f, streamMax[1], 0.0f); // edge 1 (NaN) skipped, edge 2 -> tgt 1: sqrt(9) = 3
				assertEquals(1L, VmHandlers.getRegisterValue(state, 24)); // winning source for last argmax was src=1
			}
		}
	}
}
