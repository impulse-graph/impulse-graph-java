package org.impulsegraph.storage.csr;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * Off-heap {@link MemorySegment} hex-level inspection and mutation utility
 * modeled on SQLite's {@code src/test_hexio.c} ({@code hexio_read},
 * {@code hexio_write}).
 *
 * <p>
 * Operates strictly on in-memory {@link Arena} buffers without writing corrupt
 * {@code .imps} files to disk (AGENTS.md Rules 3.6 and 3.17).
 */
public final class MemorySegmentHexPatcher {

	private static final HexFormat HEX = HexFormat.of().withUpperCase();

	private MemorySegmentHexPatcher() {
	}

	/**
	 * Allocate a mutable off-heap copy of {@code srcBytes} inside {@code arena}.
	 */
	public static MemorySegment cloneBytes(Arena arena, byte[] srcBytes) {
		MemorySegment seg = arena.allocate(srcBytes.length, 128);
		MemorySegment.copy(srcBytes, 0, seg, ValueLayout.JAVA_BYTE, 0, srcBytes.length);
		return seg;
	}

	/**
	 * Allocate a mutable off-heap copy of {@code srcSeg} inside {@code arena}.
	 */
	public static MemorySegment cloneSegment(Arena arena, MemorySegment srcSeg) {
		MemorySegment seg = arena.allocate(srcSeg.byteSize(), 128);
		seg.copyFrom(srcSeg);
		return seg;
	}

	/**
	 * Read {@code length} bytes starting at {@code offset} as an uppercase hex
	 * string (equivalent to SQLite's {@code hexio_read}).
	 */
	public static String readHex(MemorySegment seg, long offset, int length) {
		byte[] bytes = new byte[length];
		MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, offset, bytes, 0, length);
		return HEX.formatHex(bytes);
	}

	/**
	 * Overwrite bytes starting at {@code offset} with the decoded {@code hexBytes}
	 * (whitespace-insensitive, equivalent to SQLite's {@code hexio_write}).
	 */
	public static int writeHex(MemorySegment seg, long offset, String hexBytes) {
		String compact = hexBytes.replaceAll("\\s+", "");
		byte[] raw = HEX.parseHex(compact);
		MemorySegment.copy(raw, 0, seg, ValueLayout.JAVA_BYTE, offset, raw.length);
		return raw.length;
	}

	/**
	 * Patch a 16-bit little-endian integer at {@code offset}.
	 */
	public static void writeU16Le(MemorySegment seg, long offset, int value) {
		seg.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), offset, (short) value);
	}

	/**
	 * Patch a 32-bit little-endian integer at {@code offset}.
	 */
	public static void writeU32Le(MemorySegment seg, long offset, int value) {
		seg.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), offset, value);
	}

	/**
	 * Patch a 64-bit little-endian integer at {@code offset}.
	 */
	public static void writeU64Le(MemorySegment seg, long offset, long value) {
		seg.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), offset, value);
	}

	/**
	 * Flip bit {@code bitIndex} ({@code 0..7}) at {@code byteOffset}.
	 */
	public static void flipBit(MemorySegment seg, long byteOffset, int bitIndex) {
		byte b = seg.get(ValueLayout.JAVA_BYTE, byteOffset);
		seg.set(ValueLayout.JAVA_BYTE, byteOffset, (byte) (b ^ (1 << (bitIndex & 7))));
	}
}
