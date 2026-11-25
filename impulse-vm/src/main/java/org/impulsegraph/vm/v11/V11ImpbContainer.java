package org.impulsegraph.vm.v11;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Canonical {@code .impb} v0.11.0 bytecode container encoder/decoder matching
 * {@code impulse_impb_header_v11_t} and {@code decode_impb_const_pool} in C++20 byte-for-byte.
 */
public final class V11ImpbContainer {

    public static final int MAGIC = 0x494D5042; // 'IMPB'
    public static final int ISA_VERSION = 0x000B; // 11
    public static final int HEADER_BYTES = 32;
    public static final int FLAG_HAS_CONST_POOL = 0x01;

    public static final int CP_KIND_VEC = 1;
    public static final int CP_KIND_FRONTIER = 2;
    public static final int CP_KIND_STR = 3;

    public final int maxCallDepth;
    public final int entryPc;
    public final int requiredDomains;
    public final long[] codeWords;
    public final List<ConstPoolEntry> constPool;
    public final byte[] rawBytes;

    public record ConstPoolEntry(int id, int kind, V11Vector vec, long[] frontierIds, String str) {
        public static ConstPoolEntry ofStr(int id, String s) {
            return new ConstPoolEntry(id, CP_KIND_STR, null, null, s);
        }
        public static ConstPoolEntry ofFrontier(int id, long[] ids) {
            return new ConstPoolEntry(id, CP_KIND_FRONTIER, null, ids, null);
        }
        public static ConstPoolEntry ofVec(int id, V11Vector vec) {
            return new ConstPoolEntry(id, CP_KIND_VEC, vec, null, null);
        }
    }

    private V11ImpbContainer(int maxCallDepth, int entryPc, int requiredDomains,
                             long[] codeWords, List<ConstPoolEntry> constPool, byte[] rawBytes) {
        this.maxCallDepth = maxCallDepth;
        this.entryPc = entryPc;
        this.requiredDomains = requiredDomains;
        this.codeWords = codeWords;
        this.constPool = constPool;
        this.rawBytes = rawBytes;
    }

    /** Encodes instruction words and optional constant pool into a checksummed {@code .impb} v0.11 container. */
    public static V11ImpbContainer encode(long[] codeWords,
                                          List<ConstPoolEntry> constPool,
                                          int maxCallDepth,
                                          int entryPc,
                                          int requiredDomains) {
        byte[] cpBytes = encodeConstPool(constPool);
        int codeBytesLen = codeWords.length * 8;
        int payloadLen = codeBytesLen + cpBytes.length;
        byte[] out = new byte[HEADER_BYTES + payloadLen];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);

        // Write payload first so we can compute XXH3-64 over code + const_pool
        buf.position(HEADER_BYTES);
        for (long w : codeWords) {
            buf.putLong(w);
        }
        if (cpBytes.length > 0) {
            buf.put(cpBytes);
        }

        long checksum = V11Xxh3.hash64(Arrays.copyOfRange(out, HEADER_BYTES, HEADER_BYTES + payloadLen));

        buf.position(0);
        buf.putInt(MAGIC);
        buf.putShort((short) ISA_VERSION);
        buf.put((byte) (cpBytes.length > 0 ? FLAG_HAS_CONST_POOL : 0));
        buf.put((byte) (maxCallDepth & 0xFF));
        buf.putInt(codeWords.length);
        buf.putInt(cpBytes.length);
        buf.putInt(entryPc);
        buf.putShort((short) requiredDomains);
        buf.putShort((short) 0); // reserved MBZ
        buf.putLong(checksum);

        return new V11ImpbContainer(maxCallDepth, entryPc, requiredDomains,
                codeWords.clone(), constPool != null ? List.copyOf(constPool) : List.of(), out);
    }

    /** Decodes and verifies checksum of a {@code .impb} v0.11 container. */
    public static V11ImpbContainer decode(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("Truncated .impb container");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        int ver = Short.toUnsignedInt(buf.getShort());
        int hdrFlags = Byte.toUnsignedInt(buf.get());
        int maxCallDepth = Byte.toUnsignedInt(buf.get());
        int codeWordCount = buf.getInt();
        int constPoolBytes = buf.getInt();
        int entryPc = buf.getInt();
        int requiredDomains = Short.toUnsignedInt(buf.getShort());
        int reserved = Short.toUnsignedInt(buf.getShort());
        long checksum = buf.getLong();

        if (magic != MAGIC || ver != ISA_VERSION || (hdrFlags & 0xFE) != 0 || reserved != 0 || codeWordCount <= 0) {
            throw new IllegalArgumentException("Invalid .impb v0.11 header");
        }
        long codeBytesLen = (long) codeWordCount * 8L;
        long payloadLen = codeBytesLen + Integer.toUnsignedLong(constPoolBytes);
        if (HEADER_BYTES + payloadLen > bytes.length) {
            throw new IllegalArgumentException("Truncated .impb payload");
        }
        if (checksum != 0L) {
            long actual = V11Xxh3.hash64(Arrays.copyOfRange(bytes, HEADER_BYTES, HEADER_BYTES + (int) payloadLen));
            if (actual != checksum) {
                throw new IllegalArgumentException("Invalid .impb XXH3-64 checksum");
            }
        }
        long[] words = new long[codeWordCount];
        for (int i = 0; i < codeWordCount; i++) {
            words[i] = buf.getLong();
        }
        List<ConstPoolEntry> cp = ((hdrFlags & FLAG_HAS_CONST_POOL) != 0 && constPoolBytes > 0)
                ? decodeConstPool(bytes, HEADER_BYTES + (int) codeBytesLen, constPoolBytes)
                : List.of();
        return new V11ImpbContainer(maxCallDepth, entryPc, requiredDomains, words, cp, bytes.clone());
    }

    /** Loads this container's constant pool entries into {@code ws.constPool}. */
    public void loadIntoWorkspace(V11Workspace ws) {
        ws.constPool.clear();
        for (ConstPoolEntry e : constPool) {
            switch (e.kind()) {
                case CP_KIND_STR -> ws.constPool.put(e.id(), new V11Workspace.PoolStr(e.str()));
                case CP_KIND_FRONTIER -> ws.constPool.put(e.id(), new V11Workspace.PoolFrontier(e.frontierIds()));
                case CP_KIND_VEC -> ws.constPool.put(e.id(), new V11Workspace.PoolVec(e.vec()));
                default -> throw new IllegalStateException("Unknown const pool kind: " + e.kind());
            }
        }
    }

    private static byte[] encodeConstPool(List<ConstPoolEntry> entries) {
        if (entries == null || entries.isEmpty()) return new byte[0];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        putIntLE(baos, entries.size());
        putIntLE(baos, 0); // reserved
        for (ConstPoolEntry e : entries) {
            putIntLE(baos, e.id());
            baos.write(e.kind() & 0xFF);
            if (e.kind() == CP_KIND_STR) {
                byte[] utf8 = e.str().getBytes(StandardCharsets.UTF_8);
                baos.write(0); // elem_tag
                putShortLE(baos, 0); // flags
                putIntLE(baos, utf8.length);
                putIntLE(baos, 0); // extra
                baos.writeBytes(utf8);
                pad8(baos);
            } else if (e.kind() == CP_KIND_FRONTIER) {
                long[] ids = e.frontierIds() != null ? e.frontierIds() : new long[0];
                baos.write(0); // elem_tag
                putShortLE(baos, 0);
                putIntLE(baos, ids.length);
                putIntLE(baos, 0);
                for (long id : ids) putLongLE(baos, id);
            } else if (e.kind() == CP_KIND_VEC) {
                V11Vector v = e.vec();
                boolean hasValid = v.validBits != null && v.length > 0;
                baos.write(v.elemTag & 0xFF);
                putShortLE(baos, hasValid ? 1 : 0);
                putIntLE(baos, v.length);
                putIntLE(baos, v.embDim);
                int nWords = v.length > 0 ? ((v.length + 63) >>> 6) : 0;
                if (hasValid) {
                    for (int i = 0; i < nWords; i++) putLongLE(baos, v.validBits[i]);
                }
                if (v.elemTag == V11Vector.TAG_I64 || v.elemTag == V11Vector.TAG_U64) {
                    for (int i = 0; i < v.length; i++) putLongLE(baos, v.i64Data[i]);
                } else if (v.elemTag == V11Vector.TAG_F64) {
                    for (int i = 0; i < v.length; i++) putLongLE(baos, Double.doubleToRawLongBits(v.f64Data[i]));
                } else if (v.elemTag == V11Vector.TAG_BOOL_3VL) {
                    for (int i = 0; i < nWords; i++) putLongLE(baos, v.boolDataBits[i]);
                } else if (v.elemTag == V11Vector.TAG_STR) {
                    for (int i = 0; i < v.length; i++) {
                        String s = v.strData[i];
                        if (s == null) {
                            putIntLE(baos, -1);
                        } else {
                            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                            putIntLE(baos, utf8.length);
                            baos.writeBytes(utf8);
                        }
                    }
                    pad8(baos);
                } else {
                    throw new IllegalArgumentException("Unsupported const_pool vec elemTag: " + v.elemTag);
                }
            }
        }
        return baos.toByteArray();
    }

    private static List<ConstPoolEntry> decodeConstPool(byte[] bytes, int baseOff, int len) {
        if (len < 8) throw new IllegalArgumentException("Truncated const_pool header");
        ByteBuffer buf = ByteBuffer.wrap(bytes, baseOff, len).order(ByteOrder.LITTLE_ENDIAN);
        int count = buf.getInt();
        int reserved = buf.getInt();
        if (reserved != 0) throw new IllegalArgumentException("Non-zero const_pool reserved field");
        List<ConstPoolEntry> out = new ArrayList<>(count);
        int off = 8;
        for (int i = 0; i < count; i++) {
            buf.position(off);
            int id = buf.getInt();
            int kind = Byte.toUnsignedInt(buf.get());
            int elemTag = Byte.toUnsignedInt(buf.get());
            int flags = Short.toUnsignedInt(buf.getShort());
            int elemCount = buf.getInt();
            int extra = buf.getInt();
            off += 16;
            if (kind == CP_KIND_STR) {
                String s = new String(bytes, baseOff + off, elemCount, StandardCharsets.UTF_8);
                out.add(ConstPoolEntry.ofStr(id, s));
                off = (off + elemCount + 7) & ~7;
            } else if (kind == CP_KIND_FRONTIER) {
                long[] ids = new long[elemCount];
                buf.position(off);
                for (int k = 0; k < elemCount; k++) ids[k] = buf.getLong();
                out.add(ConstPoolEntry.ofFrontier(id, ids));
                off += elemCount * 8;
            } else if (kind == CP_KIND_VEC) {
                int nWords = elemCount > 0 ? ((elemCount + 63) >>> 6) : 0;
                long[] validBits = null;
                buf.position(off);
                if ((flags & 1) != 0 && nWords > 0) {
                    validBits = new long[nWords];
                    for (int k = 0; k < nWords; k++) validBits[k] = buf.getLong();
                    off += nWords * 8;
                }
                V11Vector vec;
                if (elemTag == V11Vector.TAG_I64 || elemTag == V11Vector.TAG_U64) {
                    long[] d = new long[elemCount];
                    for (int k = 0; k < elemCount; k++) d[k] = buf.getLong();
                    vec = (elemTag == V11Vector.TAG_U64) ? V11Vector.ofU64(d, validBits) : V11Vector.ofI64(d, validBits);
                    off += elemCount * 8;
                } else if (elemTag == V11Vector.TAG_F64) {
                    double[] d = new double[elemCount];
                    for (int k = 0; k < elemCount; k++) d[k] = buf.getDouble();
                    vec = V11Vector.ofF64(d, validBits);
                    off += elemCount * 8;
                } else if (elemTag == V11Vector.TAG_BOOL_3VL) {
                    long[] d = new long[nWords];
                    for (int k = 0; k < nWords; k++) d[k] = buf.getLong();
                    vec = V11Vector.ofBool3vl(elemCount, validBits, d);
                    off += nWords * 8;
                } else if (elemTag == V11Vector.TAG_STR) {
                    String[] d = new String[elemCount];
                    for (int k = 0; k < elemCount; k++) {
                        int slen = buf.getInt();
                        off += 4;
                        if (slen == -1) {
                            d[k] = null;
                        } else {
                            d[k] = new String(bytes, baseOff + off, slen, StandardCharsets.UTF_8);
                            off += slen;
                            buf.position(off);
                        }
                    }
                    vec = V11Vector.ofStr(d);
                    off = (off + 7) & ~7;
                } else {
                    throw new IllegalArgumentException("Unsupported const_pool vec elemTag: " + elemTag);
                }
                out.add(ConstPoolEntry.ofVec(id, vec));
            } else {
                throw new IllegalArgumentException("Unsupported const_pool kind: " + kind);
            }
        }
        return out;
    }

    private static void putShortLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void putIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void putLongLE(ByteArrayOutputStream out, long v) {
        for (int i = 0; i < 8; i++) out.write((int) ((v >>> (i * 8)) & 0xFF));
    }

    private static void pad8(ByteArrayOutputStream out) {
        while ((out.size() & 7) != 0) out.write(0);
    }
}
