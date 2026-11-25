package org.impulsegraph.vm.v11;

import java.util.Arrays;

/**
 * Typed nullable vector value for ImpulseVM v0.11.0 ({@code V0..V15}, constant pool, params,
 * and terminal {@code OP_COLLECT} outputs).
 *
 * <p>Element tags match {@code V11_types.vec_elem_tag} in {@code impulse-ocaml}:
 * {@code I64=0, U64=1, F64=4, BOOL_3VL=6, STR=7, EMB=8, LANE_STATE=9}.
 */
public final class V11Vector {

    public static final int TAG_I64        = 0;
    public static final int TAG_U64        = 1;
    public static final int TAG_F64        = 4;
    public static final int TAG_BOOL_3VL   = 6;
    public static final int TAG_STR        = 7;
    public static final int TAG_EMB        = 8;
    public static final int TAG_LANE_STATE = 9;

    public int elemTag;
    public int length;

    // Primitive value storage (interpreted per elemTag)
    public long[] i64Data;      // TAG_I64, TAG_U64
    public double[] f64Data;    // TAG_F64
    public long[] validBits;    // 1 bit per element (null => all valid)
    public long[] boolDataBits; // TAG_BOOL_3VL (invariant: data & ~valid == 0)
    public String[] strData;    // TAG_STR (null element => Kleene NULL)
    public int embDim;          // TAG_EMB
    public double[][] embData;  // TAG_EMB (null row => Kleene NULL)

    // TAG_LANE_STATE (MITra)
    public int lsDom;
    public int lsK;
    public double[][] lsVals;   // [node][lane], NaN => NULL when lsValid is null
    public boolean[][] lsValid; // [node][lane]
    public long[][] lsWitness;  // optional predecessor [node][lane]
    public boolean[][] lsWitnessValid;

    public V11Vector() {}

    public static V11Vector ofI64(long[] values, long[] validBits) {
        V11Vector v = new V11Vector();
        v.elemTag = TAG_I64;
        v.length = values.length;
        v.i64Data = values;
        v.validBits = validBits;
        return v;
    }

    public static V11Vector ofU64(long[] values, long[] validBits) {
        V11Vector v = new V11Vector();
        v.elemTag = TAG_U64;
        v.length = values.length;
        v.i64Data = values;
        v.validBits = validBits;
        return v;
    }

    public static V11Vector ofF64(double[] values, long[] validBits) {
        V11Vector v = new V11Vector();
        v.elemTag = TAG_F64;
        v.length = values.length;
        v.f64Data = values;
        v.validBits = validBits;
        return v;
    }

    public static V11Vector ofBool3vl(int len, long[] validBits, long[] dataBits) {
        V11Vector v = new V11Vector();
        v.elemTag = TAG_BOOL_3VL;
        v.length = len;
        v.validBits = validBits;
        v.boolDataBits = dataBits;
        return v;
    }

    public static V11Vector ofStr(String[] values) {
        V11Vector v = new V11Vector();
        v.elemTag = TAG_STR;
        v.length = values.length;
        v.strData = values;
        return v;
    }

    public static V11Vector ofEmb(int dim, double[][] rows) {
        V11Vector v = new V11Vector();
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

    public V11Vector shallowCopy() {
        V11Vector c = new V11Vector();
        c.elemTag = this.elemTag;
        c.length = this.length;
        c.i64Data = this.i64Data;
        c.f64Data = this.f64Data;
        c.validBits = this.validBits;
        c.boolDataBits = this.boolDataBits;
        c.strData = this.strData;
        c.embDim = this.embDim;
        c.embData = this.embData;
        c.lsDom = this.lsDom;
        c.lsK = this.lsK;
        c.lsVals = this.lsVals;
        c.lsValid = this.lsValid;
        c.lsWitness = this.lsWitness;
        c.lsWitnessValid = this.lsWitnessValid;
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
