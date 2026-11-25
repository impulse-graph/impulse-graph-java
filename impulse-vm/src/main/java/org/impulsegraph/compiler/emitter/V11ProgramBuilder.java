package org.impulsegraph.compiler.emitter;

import org.impulsegraph.vm.v11.V11ImpbContainer;
import org.impulsegraph.vm.v11.V11IsaTables;
import org.impulsegraph.vm.v11.V11Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-safe 64-bit nibble instruction builder and {@code .impb} v0.11.0 container serializer.
 * Mirrors C++ {@code impulse::compiler::v11::V11ProgramBuilder} bit-for-bit.
 */
public final class V11ProgramBuilder {

    private final List<Long> words = new ArrayList<>();
    private final List<V11ImpbContainer.ConstPoolEntry> constPool = new ArrayList<>();

    public static long encodeWord(int opcode, int subop, int flags,
                                  int dst, int s1, int s2, int s3, int imm32) {
        return (opcode & 0xFFL)
                | ((subop & 0x0FL) << 8)
                | ((flags & 0x0FL) << 12)
                | ((s1 & 0x0FL) << 16)
                | ((dst & 0x0FL) << 20)
                | ((s3 & 0x0FL) << 24)
                | ((s2 & 0x0FL) << 28)
                | (Integer.toUnsignedLong(imm32) << 32);
    }

    public static long encodeWordU16(int opcode, int subop, int flags,
                                     int dst, int s1, int s2, int s3,
                                     int immLo, int immHi) {
        int imm32 = (immLo & 0xFFFF) | ((immHi & 0xFFFF) << 16);
        return encodeWord(opcode, subop, flags, dst, s1, s2, s3, imm32);
    }

    public int pc() {
        return words.size();
    }

    public int emit(int opcode, int subop, int flags,
                    int dst, int s1, int s2, int s3, int imm32) {
        int pos = words.size();
        words.add(encodeWord(opcode, subop, flags, dst, s1, s2, s3, imm32));
        return pos;
    }

    public int emitU16(int opcode, int subop, int flags,
                       int dst, int s1, int s2, int s3,
                       int immLo, int immHi) {
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
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_I32, 0,
                    dst, 0, 0, 0, (int) val);
        } else {
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_I64, V11IsaTables.FLAG_EXTENDED,
                    dst, 0, 0, 0, (int) (val & 0xFFFFFFFFL));
            emit(V11IsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (val >>> 32));
        }
    }

    public void emitLoadConstU64(int dst, long val) {
        if (Long.compareUnsigned(val, 0xFFFFFFFFL) <= 0) {
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_U32, 0,
                    dst, 0, 0, 0, (int) val);
        } else {
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_U64, V11IsaTables.FLAG_EXTENDED,
                    dst, 0, 0, 0, (int) (val & 0xFFFFFFFFL));
            emit(V11IsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (val >>> 32));
        }
    }

    public void emitLoadConstF64(int dst, double val) {
        float f32 = (float) val;
        if ((double) f32 == val) {
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_F32, 0,
                    dst, 0, 0, 0, Float.floatToRawIntBits(f32));
        } else {
            long dbits = Double.doubleToRawLongBits(val);
            emit(V11IsaTables.OP_LOAD_CONST, V11IsaTables.SUBOP_LOAD_CONST_F64, V11IsaTables.FLAG_EXTENDED,
                    dst, 0, 0, 0, (int) (dbits & 0xFFFFFFFFL));
            emit(V11IsaTables.OP_EXTENSION_PAYLOAD, 0, 0, 0, 0, 0, 0, (int) (dbits >>> 32));
        }
    }

    public int addConstPoolStr(String s) {
        int id = constPool.size();
        constPool.add(V11ImpbContainer.ConstPoolEntry.ofStr(id, s));
        return id;
    }

    public int addConstPoolFrontier(long[] ids) {
        int id = constPool.size();
        constPool.add(V11ImpbContainer.ConstPoolEntry.ofFrontier(id, ids));
        return id;
    }

    public int addConstPoolVec(V11Vector vec) {
        int id = constPool.size();
        constPool.add(V11ImpbContainer.ConstPoolEntry.ofVec(id, vec));
        return id;
    }

    public long[] toWordArray() {
        long[] out = new long[words.size()];
        for (int i = 0; i < words.size(); i++) out[i] = words.get(i);
        return out;
    }

    public V11ImpbContainer buildImpb(int maxCallDepth, int entryPc, int requiredDomains) {
        return V11ImpbContainer.encode(toWordArray(), constPool, maxCallDepth, entryPc, requiredDomains);
    }
}
