package org.impulsegraph.vm;

/**
 * Closed algebraic enumeration of all 17 ImpulseVM register type tags (0x00 ..
 * 0x10). Enables Java 21+ compiler-verified exhaustiveness in switch
 * expressions without default catch-alls.
 */
public enum VmRegType {
	T_NULL((byte) 0x00), T_INT64((byte) 0x01), T_NODE_ID((byte) 0x02), T_RELATION_ID((byte) 0x03), T_BITSET_HANDLE(
			(byte) 0x04), T_NODE_VECTOR((byte) 0x05), T_CSR_SPAN((byte) 0x06), T_BOOLEAN((byte) 0x07), T_FLOAT(
					(byte) 0x08), T_DOUBLE((byte) 0x09), T_VALUE_MAP((byte) 0x0A), T_STRING_VECTOR(
							(byte) 0x0B), T_FLOAT_VECTOR((byte) 0x0C), T_DOUBLE_VECTOR((byte) 0x0D), T_UINT64_VECTOR(
									(byte) 0x0E), T_FRONTIER_STATE((byte) 0x0F), T_LANE_FRONTIER((byte) 0x10);

	private static final VmRegType[] BY_TAG = values();

	private final byte tag;

	VmRegType(byte tag) {
		this.tag = tag;
	}

	public byte tag() {
		return tag;
	}

	public int intTag() {
		return Byte.toUnsignedInt(tag);
	}

	/**
	 * Total bijection from raw byte tag to {@link VmRegType}, or {@code null} if
	 * out of [0x00..0x10].
	 */
	public static VmRegType fromTagOrNull(int rawTag) {
		if (rawTag < 0 || rawTag >= BY_TAG.length) {
			return null;
		}
		return BY_TAG[rawTag];
	}

	/**
	 * Total bijection from raw byte tag to {@link VmRegType}; throws
	 * {@code IMPULSE_VM_ERR_INVALID_REGISTER} on unknown/corrupt tags outside
	 * [0x00..0x10].
	 */
	public static VmRegType fromTag(byte rawTag) {
		int u = Byte.toUnsignedInt(rawTag);
		if (u >= BY_TAG.length) {
			throw new IllegalStateException(
					"IMPULSE_VM_ERR_INVALID_REGISTER: unknown register type tag 0x" + Integer.toHexString(u));
		}
		return BY_TAG[u];
	}

	/**
	 * Exhaustive compiler-checked predicate for 1-bit single-lane node source
	 * registers. Intentionally omits {@code default} so adding a 18th enum constant
	 * forces a compile error.
	 */
	public boolean isValidNodeSource() {
		return switch (this) {
			case T_NULL, T_INT64, T_NODE_ID, T_BITSET_HANDLE, T_NODE_VECTOR, T_UINT64_VECTOR -> true;
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				false;
		};
	}

	/**
	 * Exhaustive compiler-checked predicate for 1-bit set algebra registers
	 * (0x30..0x33).
	 */
	public boolean isValidBitsetOperand() {
		return switch (this) {
			case T_NULL, T_INT64, T_NODE_ID, T_BITSET_HANDLE, T_NODE_VECTOR, T_UINT64_VECTOR -> true;
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				false;
		};
	}

	/**
	 * Exhaustive compiler-checked predicate for multi-lane bit-sliced frontier
	 * registers.
	 */
	public boolean isValidLaneFrontier() {
		return switch (this) {
			case T_LANE_FRONTIER -> true;
			case T_NULL, T_INT64, T_NODE_ID, T_RELATION_ID, T_BITSET_HANDLE, T_NODE_VECTOR, T_CSR_SPAN, T_BOOLEAN,
					T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR, T_DOUBLE_VECTOR, T_UINT64_VECTOR,
					T_FRONTIER_STATE ->
				false;
		};
	}

	/**
	 * Safe 64-bit active lane mask generator avoiding Java's {@code 1L << 64 == 1L}
	 * shift-count wrap.
	 */
	public static long makeActiveMaskU64(int numLanes) {
		if (numLanes <= 0) {
			return 0L;
		}
		if (numLanes >= 64) {
			return -1L; // 0xFFFFFFFF_FFFFFFFFL
		}
		return (1L << numLanes) - 1L;
	}
}
