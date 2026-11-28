package org.impulsegraph.compiler.emitter;

import org.impulsegraph.vm.ImpulseImpbContainer;
import org.impulsegraph.vm.ImpulseIsaTables;
import org.impulsegraph.vm.ImpulseVector;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-safe 64-bit nibble instruction builder and {@code .impb} v0.11.0
 * container serializer. Mirrors C++
 * {@code impulse::compiler::v11::ProgramBuilder} bit-for-bit.
 */
public final class ProgramBuilder {

	private final List<Long> words = new ArrayList<>();
	private final List<ImpulseImpbContainer.ConstPoolEntry> constPool = new ArrayList<>();

	public static long encodeWord(int opcode, int subop, int flags, int dst, int s1, int s2, int s3, int imm32) {
		return (opcode & 0xFFL) | ((subop & 0x0FL) << 8) | ((flags & 0x0FL) << 12) | ((s1 & 0x0FL) << 16)
				| ((dst & 0x0FL) << 20) | ((s3 & 0x0FL) << 24) | ((s2 & 0x0FL) << 28)
				| (Integer.toUnsignedLong(imm32) << 32);
	}

	public static long encodeWordU16(int opcode, int subop, int flags, int dst, int s1, int s2, int s3, int immLo,
			int immHi) {
		int imm32 = (immLo & 0xFFFF) | ((immHi & 0xFFFF) << 16);
		return encodeWord(opcode, subop, flags, dst, s1, s2, s3, imm32);
	}

	public int pc() {
		return words.size();
	}

	public int emit(int opcode, int subop, int flags, int dst, int s1, int s2, int s3, int imm32) {
		int pos = words.size();
		words.add(encodeWord(opcode, subop, flags, dst, s1, s2, s3, imm32));
		return pos;
	}

	public int emitU16(int opcode, int subop, int flags, int dst, int s1, int s2, int s3, int immLo, int immHi) {
		int pos = words.size();
		words.add(encodeWordU16(opcode, subop, flags, dst, s1, s2, s3, immLo, immHi));
		return pos;
	}

	public void patchImm32(int wordIdx, int imm32) {
		long w = words.get(wordIdx);
		words.set(wordIdx, (w & 0x00000000FFFFFFFFL) | (Integer.toUnsignedLong(imm32) << 32));
	}

	public void patchImm16Hi(int wordIdx, int immHi) {
		long w = words.get(wordIdx);
		words.set(wordIdx, (w & 0x0000FFFFFFFFFFFFL) | ((immHi & 0xFFFFL) << 48));
	}

	public void emitLoadConstI64(int dst, long val) {
		if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_I32, 0, dst, 0, 0, 0, (int) val);
		} else {
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_I64, ImpulseIsaTables.FLAG_EXTENDED,
					dst, 0, 0, 0, (int) (val & 0xFFFFFFFFL));
			emit(ImpulseIsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (val >>> 32));
		}
	}

	public void emitLoadConstU64(int dst, long val) {
		if (Long.compareUnsigned(val, 0xFFFFFFFFL) <= 0) {
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_U32, 0, dst, 0, 0, 0, (int) val);
		} else {
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_U64, ImpulseIsaTables.FLAG_EXTENDED,
					dst, 0, 0, 0, (int) (val & 0xFFFFFFFFL));
			emit(ImpulseIsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (val >>> 32));
		}
	}

	public void emitLoadConstF64(int dst, double val) {
		float f32 = (float) val;
		if ((double) f32 == val) {
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_F32, 0, dst, 0, 0, 0,
					Float.floatToRawIntBits(f32));
		} else {
			long dbits = Double.doubleToRawLongBits(val);
			emit(ImpulseIsaTables.OP_LOAD_CONST, ImpulseIsaTables.SUBOP_LOAD_CONST_F64, ImpulseIsaTables.FLAG_EXTENDED,
					dst, 0, 0, 0, (int) (dbits & 0xFFFFFFFFL));
			emit(ImpulseIsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (dbits >>> 32));
		}
	}

	public int addConstPoolStr(String s) {
		int id = constPool.size();
		constPool.add(ImpulseImpbContainer.ConstPoolEntry.ofStr(id, s));
		return id;
	}

	public int addConstPoolFrontier(long[] ids) {
		int id = constPool.size();
		constPool.add(ImpulseImpbContainer.ConstPoolEntry.ofFrontier(id, ids));
		return id;
	}

	public int addConstPoolVec(ImpulseVector vec) {
		int id = constPool.size();
		constPool.add(ImpulseImpbContainer.ConstPoolEntry.ofVec(id, vec));
		return id;
	}

	// --- 0x15 OP_SCALAR_DECIMAL_ALU helpers ---

	public int emitScalarDecAddChk(int dst, int s1, int s2) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_ADD_CHK, 0, dst,
				s1, s2, 0, 0);
	}

	public int emitScalarDecSubChk(int dst, int s1, int s2) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_SUB_CHK, 0, dst,
				s1, s2, 0, 0);
	}

	public int emitScalarDecMulChk(int dst, int s1, int s2) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_MUL_CHK, 0, dst,
				s1, s2, 0, 0);
	}

	public int emitScalarDecMulDivRound(int dst, int s1, int s2, int s3) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_MUL_DIV_ROUND, 0,
				dst, s1, s2, s3, 0);
	}

	public int emitScalarDecDivRound(int dst, int s1, int s2) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_DIV_ROUND, 0, dst,
				s1, s2, 0, 0);
	}

	public int emitScalarDecDivSafe(int dst, int s1, int s2) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_DIV_SAFE, 0, dst,
				s1, s2, 0, 0);
	}

	public int emitScalarDecRescale(int dst, int s1, int deltaScale, boolean down) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_RESCALE, 0,
				dst, s1, 0, 0, deltaScale, down ? 1 : 0);
	}

	public int emitScalarDecBoundChk(int dst, int s1, int precision) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_BOUND_CHK, 0,
				dst, s1, 0, 0, precision, 0);
	}

	public int emitScalarDecAbsChk(int dst, int s1) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_ABS_CHK, 0, dst,
				s1, 0, 0, 0);
	}

	public int emitScalarDecNegChk(int dst, int s1) {
		return emit(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_NEG_CHK, 0, dst,
				s1, 0, 0, 0);
	}

	public int emitScalarDecF64ToDec(int dst, int s1, int scale, int precision) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_F64_TO_DEC, 0,
				dst, s1, 0, 0, scale, precision);
	}

	public int emitScalarDecToF64(int dst, int s1, int scale) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_DEC_TO_F64, 0,
				dst, s1, 0, 0, scale, 0);
	}

	public int emitScalarDecRoundScale(int dst, int s1, int deltaScale) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_ROUND_SCALE, 0,
				dst, s1, 0, 0, deltaScale, 0);
	}

	public int emitScalarDecTruncScale(int dst, int s1, int deltaScale) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_TRUNC_SCALE, 0,
				dst, s1, 0, 0, deltaScale, 0);
	}

	public int emitScalarDecFloorScale(int dst, int s1, int deltaScale) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_FLOOR_SCALE, 0,
				dst, s1, 0, 0, deltaScale, 0);
	}

	public int emitScalarDecCeilScale(int dst, int s1, int deltaScale) {
		return emitU16(ImpulseIsaTables.OP_SCALAR_DECIMAL_ALU, ImpulseIsaTables.SUBOP_SCALAR_DECIMAL_ALU_CEIL_SCALE, 0,
				dst, s1, 0, 0, deltaScale, 0);
	}

	// --- 0x58 OP_VEC_DECIMAL_ALU helpers ---

	public int emitVecDecAddChk(int dst, int s1, int s2, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_ADD_CHK, flags, dst, s1,
				s2, maskF, 0);
	}

	public int emitVecDecSubChk(int dst, int s1, int s2, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_SUB_CHK, flags, dst, s1,
				s2, maskF, 0);
	}

	public int emitVecDecMulChk(int dst, int s1, int s2, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_MUL_CHK, flags, dst, s1,
				s2, maskF, 0);
	}

	public int emitVecDecMulDivR(int dst, int s1, int s2, int divR, int flags) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_MUL_DIV_R, flags, dst,
				s1, s2, divR, 0);
	}

	public int emitVecDecMulDivV(int dst, int s1, int s2, int divV, int flags) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_MUL_DIV_V, flags, dst,
				s1, s2, divV, 0);
	}

	public int emitVecDecMulDivSafeR(int dst, int s1, int s2, int divR, int flags) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_MUL_DIV_SAFE_R, flags,
				dst, s1, s2, divR, 0);
	}

	public int emitVecDecDivRound(int dst, int s1, int s2, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_DIV_ROUND, flags, dst,
				s1, s2, maskF, 0);
	}

	public int emitVecDecDivSafe(int dst, int s1, int s2, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_DIV_SAFE, flags, dst,
				s1, s2, maskF, 0);
	}

	public int emitVecDecRescale(int dst, int s1, int deltaScale, boolean down, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_RESCALE, flags, dst,
				s1, 0, maskF, deltaScale, down ? 1 : 0);
	}

	public int emitVecDecBoundChk(int dst, int s1, int precision, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_BOUND_CHK, flags,
				dst, s1, 0, maskF, precision, 0);
	}

	public int emitVecDecAbsChk(int dst, int s1, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_ABS_CHK, flags, dst, s1,
				0, maskF, 0);
	}

	public int emitVecDecNegChk(int dst, int s1, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_NEG_CHK, flags, dst, s1,
				0, maskF, 0);
	}

	public int emitVecDecF64ToDec(int dst, int s1, int scale, int precision, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_F64_TO_DEC, flags,
				dst, s1, 0, maskF, scale, precision);
	}

	public int emitVecDecToF64(int dst, int s1, int scale, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_DEC_TO_F64, flags,
				dst, s1, 0, maskF, scale, 0);
	}

	public int emitVecDecRoundScale(int dst, int s1, int deltaScale, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_ROUND_SCALE, flags,
				dst, s1, 0, maskF, deltaScale, 0);
	}

	public int emitVecDecTruncScale(int dst, int s1, int deltaScale, int flags, int maskF) {
		return emitU16(ImpulseIsaTables.OP_VEC_DECIMAL_ALU, ImpulseIsaTables.SUBOP_VEC_DECIMAL_ALU_TRUNC_SCALE, flags,
				dst, s1, 0, maskF, deltaScale, 0);
	}

	// --- 0x52.sum_i64_chk & 0x37.scatter_add_chk helpers ---

	public int emitVecReduceSumI64Chk(int dstR, int srcV, int flags, int maskF) {
		return emit(ImpulseIsaTables.OP_VEC_REDUCE, ImpulseIsaTables.SUBOP_VEC_REDUCE_SUM_I64_CHK, flags, dstR, srcV, 0,
				maskF, 0);
	}

	public int emitMorselCommitScatterAddChk(int dstV, int idxV, int valV, int flags, int predV) {
		return emit(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_SCATTER_ADD_CHK, flags,
				dstV, idxV, valV, predV, 0);
	}

	public long[] toWordArray() {
		long[] out = new long[words.size()];
		for (int i = 0; i < words.size(); i++)
			out[i] = words.get(i);
		return out;
	}

	public ImpulseImpbContainer buildImpb(int maxCallDepth, int entryPc, int requiredDomains) {
		return ImpulseImpbContainer.encode(toWordArray(), constPool, maxCallDepth, entryPc, requiredDomains);
	}
}
