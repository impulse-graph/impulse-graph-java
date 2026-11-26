package org.impulsegraph.vm.v11;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dual {@code ICTX v2} Wire Serializer & Canonical XXH3-64 Digest Engine for
 * ImpulseVM v0.11.0.
 *
 * <p>
 * Bit-identical to {@code V11_ictx_v2.compute_hashes} in {@code impulse-ocaml}:
 * <ul>
 * <li>{@code logicalHash}: tier-invariant XXH3-64 (seed
 * {@code 0x494D505630313130L} = {@code "IMPV0110"}).</li>
 * <li>{@code physicalHash}: tier-variant XXH3-64 (seed
 * {@code 0x5048595330313130L} = {@code "PHYS0110"}).</li>
 * </ul>
 */
public final class V11ContextHash {

	public static final long LOGICAL_SEED = 0x494D505630313130L; // "IMPV0110"
	public static final long PHYSICAL_SEED = 0x5048595330313130L; // "PHYS0110"

	private static final byte[] MAGIC = {'I', 'C', 'T', 'X', 'v', '2', 'L', 0};

	public record Digest(long logicalHash, long physicalHash) {
		public String logicalHex() {
			return String.format("0x%016x", logicalHash);
		}

		public String physicalHex() {
			return String.format("0x%016x", physicalHash);
		}
	}

	private V11ContextHash() {
	}

	public static Digest compute(V11Workspace ws) {
		ByteWriter bl = new ByteWriter(512);
		ByteWriter bp = new ByteWriter(512);

		// 1. Header (24 B)
		bl.addBytes(MAGIC);
		bp.addBytes(MAGIC);
		bl.addU32Le(ws.status);
		bp.addU32Le(ws.status);
		bl.addU32Le(ws.pc);
		bp.addU32Le(ws.pc);
		int flagsRaw = ws.vmFlagsRaw();
		bl.addU16Le(flagsRaw);
		bp.addU16Le(flagsRaw);
		int validMask = ws.rValidMask & 0xFFFF;
		bl.addU16Le(validMask);
		bp.addU16Le(validMask);
		long rTypeWord = ws.computeCanonicalRTypeWord();
		bl.addU64Le(rTypeWord);
		bp.addU64Le(rTypeWord);

		// 2. Scalar Bank R0..R15
		for (int i = 0; i < 16; i++) {
			if (((validMask >>> i) & 1) == 0) {
				bl.addU8(0);
				bp.addU8(0);
			} else {
				bl.addU8(1);
				bp.addU8(1);
				int ty = ws.readRType(i);
				if (ty == V11IsaTables.SCALAR_STR_HANDLE) {
					String s = ws.resolveScalarStr(ws.rRegs[i]);
					if (s == null)
						s = "";
					bl.addString(s);
					bp.addString(s);
				} else {
					bl.addU64Le(ws.rRegs[i]);
					bp.addU64Le(ws.rRegs[i]);
				}
			}
		}

		// 3. Frontier Bank F0..F15
		for (int i = 0; i < 16; i++) {
			if (!ws.isFInitialized(i)) {
				bl.addU8(0);
				bp.addU8(0);
			} else {
				V11Frontier f = ws.fRegs[i];
				bl.addU8(1);
				bp.addU8(1);
				bl.addU16Le(f.domainId);
				bp.addU16Le(f.domainId);
				if (!f.multiLane) {
					int[] nodes = f.toSortedArray();
					bl.addU8(1);
					bp.addU8(1);
					bl.addU8(1);
					bp.addU8(1);
					bl.addU64Le(nodes.length);
					bp.addU64Le(nodes.length);
					for (int u : nodes) {
						long u64 = u & 0xFFFFFFFFL;
						bl.addU64Le(u64);
						bp.addU64Le(u64);
					}
					bp.addU8(f.tier);
					bp.addU8(1);
					int dp = (f.tier == V11Frontier.TIER1_PAGED) ? countDirtyPages(nodes, ws.geometry.pageShift()) : 0;
					bp.addU32Le(dp);
					bp.addU8(f.cachedCard >= 0 ? 1 : 0);
				} else {
					bl.addU8(64);
					bp.addU8(64);
					bl.addU8(f.activeLaneCount);
					bp.addU8(f.activeLaneCount);
					long[] mlNodes = f.mlSortedNodes();
					long[] mlMasks = f.mlSortedMasks();
					bl.addU64Le(mlNodes.length);
					bp.addU64Le(mlNodes.length);
					for (int k = 0; k < mlNodes.length; k++) {
						bl.addU64Le(mlNodes[k]);
						bp.addU64Le(mlNodes[k]);
						bl.addU64Le(mlMasks[k]);
						bp.addU64Le(mlMasks[k]);
					}
					bp.addU8(f.tier);
					bp.addU8(f.allocWidth);
					bp.addU8(f.cachedCard >= 0 ? 1 : 0);
				}
			}
		}

		// 4. Vector Bank V0..V15
		for (int i = 0; i < 16; i++) {
			V11Vector v = ws.vVectors[i];
			if (v == null) {
				bl.addU8(0);
				bp.addU8(0);
			} else {
				bl.addU8(1);
				bp.addU8(1);
				serializeVec(bl, v);
				serializeVec(bp, v);
			}
		}

		// 5. Terminal COLLECT Outputs (sorted by slot)
		int[] slots = ws.sortedOutputSlots();
		bl.addU32Le(slots.length);
		bp.addU32Le(slots.length);
		for (int slot : slots) {
			V11Vector out = ws.outputs.get(slot);
			bl.addU16Le(slot);
			bp.addU16Le(slot);
			serializeVec(bl, out);
			serializeVec(bp, out);
		}

		long logical = V11Xxh3.hash64WithSeed(bl.buf, 0, bl.len, LOGICAL_SEED);
		long physical = V11Xxh3.hash64WithSeed(bp.buf, 0, bp.len, PHYSICAL_SEED);
		return new Digest(logical, physical);
	}

	private static int countDirtyPages(int[] sortedNodes, int pageShift) {
		if (sortedNodes.length == 0) {
			return 0;
		}
		int pages = 1;
		int prevPage = sortedNodes[0] >>> pageShift;
		for (int i = 1; i < sortedNodes.length; i++) {
			int p = sortedNodes[i] >>> pageShift;
			if (p != prevPage) {
				pages++;
				prevPage = p;
			}
		}
		return pages;
	}

	private static void serializeVec(ByteWriter b, V11Vector v) {
		b.addU8(v.elemTag);
		b.addU64Le(v.length);
		switch (v.elemTag) {
			case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
				for (int i = 0; i < v.length; i++) {
					if (!v.isValidAt(i)) {
						b.addU8(0);
					} else {
						b.addU8(1);
						b.addU64Le(v.i64Data[i]);
					}
				}
			}
			case V11Vector.TAG_F64 -> {
				for (int i = 0; i < v.length; i++) {
					if (!v.isValidAt(i)) {
						b.addU8(0);
					} else {
						b.addU8(1);
						b.addU64Le(Double.doubleToRawLongBits(v.f64Data[i]));
					}
				}
			}
			case V11Vector.TAG_BOOL_3VL -> {
				for (int i = 0; i < v.length; i++) {
					if (!v.isValidAt(i)) {
						b.addU8(0);
					} else {
						b.addU8(1);
						b.addU8(v.boolDataAt(i) ? 1 : 0);
					}
				}
			}
			case V11Vector.TAG_STR -> {
				for (int i = 0; i < v.length; i++) {
					String s = v.strData[i];
					if (s == null) {
						b.addU8(0);
					} else {
						b.addU8(1);
						b.addString(s);
					}
				}
			}
			case V11Vector.TAG_EMB -> {
				b.addU32Le(v.embDim);
				for (int i = 0; i < v.length; i++) {
					double[] row = v.embData[i];
					if (row == null) {
						b.addU8(0);
					} else {
						b.addU8(1);
						for (double f : row) {
							b.addU64Le(Double.doubleToRawLongBits(f));
						}
					}
				}
			}
			case V11Vector.TAG_LANE_STATE -> {
				b.addU16Le(v.lsDom);
				b.addU16Le(v.lsK);
				for (int u = 0; u < v.length; u++) {
					for (int l = 0; l < v.lsK; l++) {
						if (!v.lsIsValid(u, l)) {
							b.addU8(0);
						} else {
							b.addU8(1);
							b.addU64Le(Double.doubleToRawLongBits(v.lsGetVal(u, l)));
						}
					}
				}
			}
			default -> throw new IllegalStateException("Unknown vector elemTag: " + v.elemTag);
		}
	}

	private static final class ByteWriter {
		byte[] buf;
		int len;

		ByteWriter(int initialCap) {
			this.buf = new byte[initialCap];
		}

		private void ensure(int extra) {
			if (len + extra > buf.length) {
				int next = Math.max(buf.length << 1, len + extra);
				buf = Arrays.copyOf(buf, next);
			}
		}

		void addU8(int v) {
			ensure(1);
			buf[len++] = (byte) v;
		}

		void addU16Le(int v) {
			ensure(2);
			buf[len++] = (byte) v;
			buf[len++] = (byte) (v >>> 8);
		}

		void addU32Le(int v) {
			ensure(4);
			buf[len++] = (byte) v;
			buf[len++] = (byte) (v >>> 8);
			buf[len++] = (byte) (v >>> 16);
			buf[len++] = (byte) (v >>> 24);
		}

		void addU64Le(long v) {
			ensure(8);
			buf[len++] = (byte) v;
			buf[len++] = (byte) (v >>> 8);
			buf[len++] = (byte) (v >>> 16);
			buf[len++] = (byte) (v >>> 24);
			buf[len++] = (byte) (v >>> 32);
			buf[len++] = (byte) (v >>> 40);
			buf[len++] = (byte) (v >>> 48);
			buf[len++] = (byte) (v >>> 56);
		}

		void addBytes(byte[] src) {
			ensure(src.length);
			System.arraycopy(src, 0, buf, len, src.length);
			len += src.length;
		}

		void addString(String s) {
			byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
			addU32Le(utf8.length);
			addBytes(utf8);
		}
	}
}
