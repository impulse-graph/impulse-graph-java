package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.impulsegraph.api.bitset.ImpulseBitSet;

/**
 * Deterministic VM Execution State & Context Hash (ICTX v1) for cross-language
 * polyglot differential testing. Aligned bit-for-bit with impulse_vm_state_hash
 * in C++.
 */
public final class VmContextHash {

	private VmContextHash() {
	}

	private static final class HashStream {
		private final MessageDigest md;
		private final byte[] buf8 = new byte[8];

		HashStream() {
			try {
				this.md = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException(e);
			}
		}

		void writeBytes(byte[] b, int off, int len) {
			if (b != null && len > 0) {
				md.update(b, off, len);
			}
		}

		void writeU8(int val) {
			md.update((byte) (val & 0xFF));
		}

		void writeU16LE(int val) {
			buf8[0] = (byte) (val & 0xFF);
			buf8[1] = (byte) ((val >>> 8) & 0xFF);
			md.update(buf8, 0, 2);
		}

		void writeU32LE(long val) {
			buf8[0] = (byte) (val & 0xFF);
			buf8[1] = (byte) ((val >>> 8) & 0xFF);
			buf8[2] = (byte) ((val >>> 16) & 0xFF);
			buf8[3] = (byte) ((val >>> 24) & 0xFF);
			md.update(buf8, 0, 4);
		}

		void writeI32LE(int val) {
			writeU32LE(val & 0xFFFFFFFFL);
		}

		void writeU64LE(long val) {
			buf8[0] = (byte) (val & 0xFF);
			buf8[1] = (byte) ((val >>> 8) & 0xFF);
			buf8[2] = (byte) ((val >>> 16) & 0xFF);
			buf8[3] = (byte) ((val >>> 24) & 0xFF);
			buf8[4] = (byte) ((val >>> 32) & 0xFF);
			buf8[5] = (byte) ((val >>> 40) & 0xFF);
			buf8[6] = (byte) ((val >>> 48) & 0xFF);
			buf8[7] = (byte) ((val >>> 56) & 0xFF);
			md.update(buf8, 0, 8);
		}

		void writeF32LE(float val) {
			int bits;
			if (Float.isNaN(val)) {
				bits = 0x7FC00000;
			} else if (val == 0.0f) {
				bits = 0x00000000;
			} else {
				bits = Float.floatToRawIntBits(val);
			}
			writeU32LE(bits & 0xFFFFFFFFL);
		}

		void writeF64LE(double val) {
			long bits;
			if (Double.isNaN(val)) {
				bits = 0x7FF8000000000000L;
			} else if (val == 0.0) {
				bits = 0x0000000000000000L;
			} else {
				bits = Double.doubleToRawLongBits(val);
			}
			writeU64LE(bits);
		}

		byte[] finish() {
			return md.digest();
		}
	}

	public static int statusCodeFromString(String status) {
		if (status == null)
			return 0;
		return switch (status) {
			case "IMPULSE_VM_OK" -> 0;
			case "IMPULSE_VM_ERR_INVALID_OPCODE" -> 1;
			case "IMPULSE_VM_ERR_OUT_OF_BOUNDS" -> 2;
			case "IMPULSE_VM_ERR_NULL_SNAPSHOT" -> 3;
			case "IMPULSE_VM_ERR_STACK_OVERFLOW" -> 4;
			case "IMPULSE_VM_ERR_STACK_UNDERFLOW" -> 5;
			case "IMPULSE_VM_ERR_INVALID_REGISTER" -> 6;
			case "IMPULSE_VM_ERR_USER_THROW" -> 7;
			case "IMPULSE_VM_ERR_ASSERTION_FAILED" -> 8;
			case "IMPULSE_VM_ERR_TRAP" -> 9;
			case "IMPULSE_VM_ERR_RESERVED_OPCODE" -> 10;
			case "IMPULSE_VM_ERR_BUFFER_OVERFLOW" -> 11;
			case "IMPULSE_VM_ERR_FLOATING_POINT" -> 12;
			case "IMPULSE_VM_ERR_GAS_EXHAUSTED" -> 13;
			default -> 8;
		};
	}

	public static byte[] computeStateHash(MemorySegment state, int statusCode, VmQueryContext ctx) {
		HashStream hs = new HashStream();

		// 1. Header: Magic "ICTX", Version 1 (uint16_t), Reserved 0 (uint16_t)
		hs.writeU8('I');
		hs.writeU8('C');
		hs.writeU8('T');
		hs.writeU8('X');
		hs.writeU16LE(1);
		hs.writeU16LE(0);

		// 2. State metadata: status (int32_t), pc (uint32_t), flags (uint64_t)
		hs.writeI32LE(statusCode);
		int pc = (state != null) ? (int) VmStateLayout.PC_HANDLE.get(state, 0L) : 0;
		hs.writeU32LE(Integer.toUnsignedLong(pc));
		long flags = (state != null) ? (long) VmStateLayout.FLAGS_HANDLE.get(state, 0L) : 0L;
		hs.writeU64LE(flags);

		// 3. Subroutine call stack: call_stack_depth (uint32_t), 8 slots with inactive
		// zeroed
		int depth = (state != null) ? (int) VmStateLayout.CALL_STACK_DEPTH_HANDLE.get(state, 0L) : 0;
		hs.writeU32LE(Integer.toUnsignedLong(depth));
		for (long i = 0; i < 8; i++) {
			int cs = (state != null && i < depth) ? (int) VmStateLayout.CALL_STACK_ELEMENT_HANDLE.get(state, 0L, i) : 0;
			hs.writeU32LE(Integer.toUnsignedLong(cs));
		}

		// 4. Architectural registers: 64 x (type uint8_t, value uint64_t LE)
		for (long r = 0; r < 64; r++) {
			byte type = (state != null) ? (byte) VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, r) : 0;
			long val = (state != null) ? (long) VmStateLayout.REGISTER_ELEMENT_HANDLE.get(state, 0L, r) : 0L;
			hs.writeU8(type & 0xFF);
			hs.writeU64LE(val);
		}

		// 5. Dereference active handle registers
		for (long r = 0; r < 64; r++) {
			byte type = (state != null) ? (byte) VmStateLayout.REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, r) : 0;
			long val = (state != null) ? (long) VmStateLayout.REGISTER_ELEMENT_HANDLE.get(state, 0L, r) : 0L;

			switch (type) {
				case VmRegisterType.TYPE_BITSET_HANDLE -> {
					int handle = (int) val;
					ImpulseBitSet bs = (ctx != null) ? ctx.getBitset(handle) : null;
					if (bs != null) {
						long card = bs.cardinality();
						hs.writeU64LE(card);
						for (int bit = bs.nextSetBit(0); bit >= 0; bit = bs.nextSetBit(bit + 1)) {
							hs.writeU64LE(Integer.toUnsignedLong(bit));
						}
					} else {
						hs.writeU64LE(0L);
					}
				}
				case VmRegisterType.TYPE_FLOAT_VECTOR -> {
					int handle = (int) val;
					float[] fvec = (ctx != null) ? ctx.getFloatVector(handle) : null;
					if (fvec != null) {
						hs.writeU32LE(Integer.toUnsignedLong(fvec.length));
						for (float f : fvec) {
							hs.writeF32LE(f);
						}
					} else {
						hs.writeU32LE(0L);
					}
				}
				case VmRegisterType.TYPE_DOUBLE_VECTOR -> {
					int handle = (int) val;
					double[] dvec = (ctx != null) ? ctx.getDoubleVector(handle) : null;
					if (dvec != null) {
						hs.writeU32LE(Integer.toUnsignedLong(dvec.length));
						for (double d : dvec) {
							hs.writeF64LE(d);
						}
					} else {
						hs.writeU32LE(0L);
					}
				}
				case VmRegisterType.TYPE_NODE_VECTOR -> {
					int handle = (int) val;
					int[] nvec = (ctx != null) ? ctx.getNodeVector(handle) : null;
					if (nvec != null) {
						hs.writeU32LE(Integer.toUnsignedLong(nvec.length));
						for (int n : nvec) {
							hs.writeU64LE(Integer.toUnsignedLong(n));
						}
					} else {
						hs.writeU32LE(0L);
					}
				}
				case VmRegisterType.TYPE_UINT64_VECTOR -> {
					int handle = (int) val;
					long[] lvec = (ctx != null) ? ctx.getLongVector(handle) : null;
					if (lvec != null) {
						hs.writeU32LE(Integer.toUnsignedLong(lvec.length));
						for (long l : lvec) {
							hs.writeU64LE(l);
						}
					} else {
						int[] nvec = (ctx != null) ? ctx.getNodeVector(handle) : null;
						if (nvec != null) {
							hs.writeU32LE(Integer.toUnsignedLong(nvec.length));
							for (int n : nvec) {
								hs.writeU64LE(Integer.toUnsignedLong(n));
							}
						} else {
							hs.writeU32LE(0L);
						}
					}
				}
				case VmRegisterType.TYPE_LANE_FRONTIER -> {
					int handle = (int) val;
					VmLaneFrontier lf = (ctx != null) ? ctx.getLaneFrontier(handle) : null;
					if (lf == null && ctx != null && ctx.getFinalResult() instanceof VmLaneFrontier f) {
						lf = f;
					}
					if (lf != null) {
						hs.writeU32LE(Integer.toUnsignedLong(lf.numLanes()));
						hs.writeU32LE(Integer.toUnsignedLong(lf.width().maxLanes()));
						hs.writeU64LE(lf.activeMask());
						hs.writeU32LE(Integer.toUnsignedLong(lf.nodeCount()));
						long[] lanes = lf.lanes();
						if (lanes != null) {
							for (int n = 0; n < lf.nodeCount(); n++) {
								hs.writeU64LE(lanes[n]);
							}
						}
					} else {
						hs.writeU32LE(0L);
						hs.writeU32LE(0L);
						hs.writeU64LE(0L);
						hs.writeU32LE(0L);
					}
				}
				case VmRegisterType.TYPE_STRING_VECTOR -> {
					int handle = (int) val;
					String[] svec = (ctx != null) ? ctx.getStringVector(handle) : null;
					if (svec != null) {
						hs.writeU32LE(Integer.toUnsignedLong(svec.length));
						for (String s : svec) {
							byte[] utf8 = (s != null) ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
							hs.writeU32LE(Integer.toUnsignedLong(utf8.length));
							hs.writeBytes(utf8, 0, utf8.length);
						}
					} else {
						hs.writeU32LE(0L);
					}
				}
				case VmRegisterType.TYPE_VALUE_MAP -> {
					int handle = (int) val;
					java.util.Map<Integer, Object> vmap = (ctx != null) ? ctx.getValueMap(handle) : null;
					if (vmap != null) {
						hs.writeU32LE(Integer.toUnsignedLong(vmap.size()));
						for (java.util.Map.Entry<Integer, Object> entry : vmap.entrySet()) {
							String k = String.valueOf(entry.getKey());
							byte[] kbytes = k.getBytes(StandardCharsets.UTF_8);
							hs.writeU32LE(Integer.toUnsignedLong(kbytes.length));
							hs.writeBytes(kbytes, 0, kbytes.length);
							float fval = (entry.getValue() instanceof Number num) ? num.floatValue() : 0.0f;
							hs.writeF32LE(fval);
						}
					} else {
						hs.writeU32LE(0L);
					}
				}
				default -> {
				}
			}
		}

		return hs.finish();
	}

	public static String computeStateHashHex(MemorySegment state, int statusCode, VmQueryContext ctx) {
		byte[] hash = computeStateHash(state, statusCode, ctx);
		StringBuilder sb = new StringBuilder(64);
		for (byte b : hash) {
			sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
			sb.append(Character.forDigit(b & 0x0F, 16));
		}
		return sb.toString();
	}
}
