package org.impulsegraph.vm;

import java.util.Arrays;

/**
 * Typed nullable vector value for ImpulseVM v0.11.0 ({@code V0..V15}, constant
 * pool, params, and terminal {@code OP_COLLECT} outputs).
 *
 * <p>
 * Element tags match {@code V11_types.vec_elem_tag} in {@code impulse-ocaml}:
 * {@code I64=0, U64=1, F64=4, BOOL_3VL=6, STR=7, EMB=8, LANE_STATE=9}.
 */
public final class ImpulseVector {

	public static final int TAG_I64 = 0;
	public static final int TAG_U64 = 1;
	public static final int TAG_F64 = 4;
	public static final int TAG_BOOL_3VL = 6;
	public static final int TAG_STR = 7;
	public static final int TAG_EMB = 8;
	public static final int TAG_LANE_STATE = 9;

	public int elemTag;
	public int length;

	// Primitive value storage (interpreted per elemTag)
	public long[] i64Data; // TAG_I64, TAG_U64
	public double[] f64Data; // TAG_F64
	public long[] validBits; // 1 bit per element (null => all valid)
	public long[] boolDataBits; // TAG_BOOL_3VL (invariant: data & ~valid == 0)
	public String[] strData; // TAG_STR (null element => Kleene NULL)
	public int embDim; // TAG_EMB
	public double[][] embData; // TAG_EMB (null row => Kleene NULL)

	// TAG_LANE_STATE (MITra): flat contiguous buffers (row-major [u * lsK + l]) +
	// optional legacy 2D
	public int lsDom;
	public int lsK;
	public double[] lsValsFlat;
	public byte[] lsValidFlat;
	public long[] lsWitnessFlat;
	public byte[] lsWitnessValidFlat;
	public double[][] lsVals; // [node][lane], NaN => NULL when lsValid is null
	public boolean[][] lsValid; // [node][lane]
	public long[][] lsWitness; // optional predecessor [node][lane]
	public boolean[][] lsWitnessValid;

	public ImpulseVector() {
	}

	public void reset() {
		elemTag = 0;
		length = 0;
		i64Data = null;
		f64Data = null;
		validBits = null;
		boolDataBits = null;
		strData = null;
		embDim = 0;
		embData = null;
		lsDom = 0;
		lsK = 0;
		lsValsFlat = null;
		lsValidFlat = null;
		lsWitnessFlat = null;
		lsWitnessValidFlat = null;
		lsVals = null;
		lsValid = null;
		lsWitness = null;
		lsWitnessValid = null;
	}

	public void copyFieldsFrom(ImpulseVector src) {
		this.elemTag = src.elemTag;
		this.length = src.length;
		this.i64Data = src.i64Data;
		this.f64Data = src.f64Data;
		this.validBits = src.validBits;
		this.boolDataBits = src.boolDataBits;
		this.strData = src.strData;
		this.embDim = src.embDim;
		this.embData = src.embData;
		this.lsDom = src.lsDom;
		this.lsK = src.lsK;
		this.lsValsFlat = src.lsValsFlat;
		this.lsValidFlat = src.lsValidFlat;
		this.lsWitnessFlat = src.lsWitnessFlat;
		this.lsWitnessValidFlat = src.lsWitnessValidFlat;
		this.lsVals = src.lsVals;
		this.lsValid = src.lsValid;
		this.lsWitness = src.lsWitness;
		this.lsWitnessValid = src.lsWitnessValid;
	}

	public boolean lsIsValid(int u, int l) {
		if (lsValidFlat != null)
			return lsValidFlat[u * lsK + l] != 0;
		return lsValid != null && lsValid[u][l];
	}

	public double lsGetVal(int u, int l) {
		if (lsValsFlat != null)
			return lsValsFlat[u * lsK + l];
		return lsVals[u][l];
	}

	public boolean lsHasWitness() {
		return lsWitnessFlat != null || lsWitness != null;
	}

	public boolean lsIsWitnessValid(int u, int l) {
		if (lsWitnessValidFlat != null)
			return lsWitnessValidFlat[u * lsK + l] != 0;
		return lsWitnessValid != null && lsWitnessValid[u][l];
	}

	public long lsGetWitness(int u, int l) {
		if (lsWitnessFlat != null)
			return lsWitnessFlat[u * lsK + l];
		return lsWitness[u][l];
	}

	public static ImpulseVector ofI64(long[] values, long[] validBits) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_I64;
		v.length = values.length;
		v.i64Data = values;
		v.validBits = validBits;
		return v;
	}

	public static ImpulseVector ofU64(long[] values, long[] validBits) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_U64;
		v.length = values.length;
		v.i64Data = values;
		v.validBits = validBits;
		return v;
	}

	public static ImpulseVector ofF64(double[] values, long[] validBits) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_F64;
		v.length = values.length;
		v.f64Data = values;
		v.validBits = validBits;
		return v;
	}

	public static ImpulseVector ofBool3vl(int len, long[] validBits, long[] dataBits) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_BOOL_3VL;
		v.length = len;
		v.validBits = validBits;
		v.boolDataBits = dataBits;
		return v;
	}

	public static ImpulseVector ofStr(String[] values) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_STR;
		v.length = values.length;
		v.strData = values;
		return v;
	}

	public static ImpulseVector ofEmb(int dim, double[][] rows) {
		ImpulseVector v = new ImpulseVector();
		v.elemTag = TAG_EMB;
		v.length = rows.length;
		v.embDim = dim;
		v.embData = rows;
		return v;
	}

	public boolean isValidAt(int idx) {
		if (elemTag == TAG_STR) {
			return strData != null && strData[idx] != null;
		}
		if (elemTag == TAG_EMB) {
			return embData != null && embData[idx] != null;
		}
		if (validBits == null) {
			return true;
		}
		return ((validBits[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
	}

	public boolean boolDataAt(int idx) {
		return ((boolDataBits[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
	}

	/** Matches {@code V11_eval_p1.vec_matches_expected_tag}. */
	public boolean matchesExpectedParamTag(int tag) {
		if (tag == 0xFFFF) {
			return true;
		}
		return switch (elemTag) {
			case TAG_I64 -> tag == 1;
			case TAG_U64 -> tag == 2;
			case TAG_F64 -> tag == 3 || tag == 6;
			case TAG_BOOL_3VL -> tag == 4;
			case TAG_STR -> tag == 5;
			default -> false;
		};
	}

	public ImpulseVector shallowCopy() {
		ImpulseVector c = new ImpulseVector();
		c.copyFieldsFrom(this);
		return c;
	}

	public static long[] allocValidAllTrue(int len) {
		int words = (len + 63) >>> 6;
		long[] bits = new long[words];
		Arrays.fill(bits, -1L);
		int rem = len & 63;
		if (rem != 0 && words > 0) {
			bits[words - 1] = (1L << rem) - 1L;
		}
		return bits;
	}
}
