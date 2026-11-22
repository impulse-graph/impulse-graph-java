package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.vm.VmHandlers;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Binary bytecode codec for Impulse VM {@code .impb} files.
 *
 * <p>
 * Byte-compatible with {@code impulse-graph-core/impulse-cli}
 * ({@code assemble.rs} / {@code disassemble.rs}): an {@code .impb} file is a
 * flat packed sequence of 8-byte little-endian {@code impulse_instruction_t}
 * structs:
 *
 * <pre>
 *   byte 0    : u8  opcode
 *   byte 1    : u8  flags
 *   bytes 2..3: u16 dst_reg (little-endian)
 *   bytes 4..7: u32 payload (little-endian)
 * </pre>
 */
public final class ImpOpsBinaryCodec {

	/** Size in bytes of a single packed {@code impulse_instruction_t}. */
	public static final int INSTRUCTION_SIZE_BYTES = 8;

	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private ImpOpsBinaryCodec() {
	}

	/**
	 * Encodes a list of {@link InstructionWord}s into a flat little-endian
	 * {@code .impb} byte array of length {@code 8 * instructions.size()}.
	 */
	public static byte[] encode(List<InstructionWord> instructions) {
		Objects.requireNonNull(instructions, "instructions must not be null");
		byte[] bytes = new byte[instructions.size() * INSTRUCTION_SIZE_BYTES];
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (InstructionWord instr : instructions) {
			Objects.requireNonNull(instr, "instruction element must not be null");
			buf.put(instr.opcode());
			buf.put(instr.flags());
			buf.putShort(instr.dstReg());
			buf.putInt(instr.payload());
		}
		return bytes;
	}

	/**
	 * Encodes {@code instructions} and writes the raw {@code .impb} binary payload
	 * to {@code outputPath}.
	 */
	public static void writeTo(List<InstructionWord> instructions, Path outputPath) throws IOException {
		Objects.requireNonNull(outputPath, "outputPath must not be null");
		byte[] encoded = encode(instructions);
		Path parent = outputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(outputPath, encoded);
	}

	/**
	 * Decodes a flat little-endian {@code .impb} byte array into an unmodifiable
	 * list of {@link InstructionWord}s.
	 *
	 * @throws IllegalArgumentException
	 *             if {@code bytes.length % 8 != 0}
	 */
	public static List<InstructionWord> decode(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes must not be null");
		if ((bytes.length % INSTRUCTION_SIZE_BYTES) != 0) {
			throw new IllegalArgumentException("Invalid .impb bytecode length: " + bytes.length
					+ " bytes (must be a multiple of " + INSTRUCTION_SIZE_BYTES + ")");
		}
		int count = bytes.length / INSTRUCTION_SIZE_BYTES;
		List<InstructionWord> result = new ArrayList<>(count);
		ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			byte opcode = buf.get();
			byte flags = buf.get();
			short dstReg = buf.getShort();
			int payload = buf.getInt();
			result.add(new InstructionWord(opcode, flags, dstReg, payload));
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * Reads and decodes an {@code .impb} binary bytecode file from disk.
	 */
	public static List<InstructionWord> readFrom(Path inputPath) throws IOException {
		Objects.requireNonNull(inputPath, "inputPath must not be null");
		return decode(Files.readAllBytes(inputPath));
	}

	/**
	 * Allocates an off-heap {@link MemorySegment} inside {@code arena} populated
	 * with the little-endian 8-byte {@code impulse_instruction_t} sequence.
	 */
	public static MemorySegment toMemorySegment(List<InstructionWord> instructions, Arena arena) {
		Objects.requireNonNull(instructions, "instructions must not be null");
		Objects.requireNonNull(arena, "arena must not be null");
		long byteSize = (long) instructions.size() * INSTRUCTION_SIZE_BYTES;
		MemorySegment segment = arena.allocate(Math.max(byteSize, INSTRUCTION_SIZE_BYTES), INSTRUCTION_SIZE_BYTES);
		for (int i = 0; i < instructions.size(); i++) {
			InstructionWord instr = Objects.requireNonNull(instructions.get(i), "instruction element must not be null");
			long offset = (long) i * INSTRUCTION_SIZE_BYTES;
			segment.set(ValueLayout.JAVA_BYTE, offset, instr.opcode());
			segment.set(ValueLayout.JAVA_BYTE, offset + 1L, instr.flags());
			segment.set(LE_SHORT, offset + 2L, instr.dstReg());
			segment.set(LE_INT, offset + 4L, instr.payload());
		}
		return byteSize == 0 ? segment.asSlice(0, 0) : segment;
	}

	/**
	 * Converts {@link InstructionWord}s directly into
	 * {@link VmHandlers.Instruction} instances for direct execution or validation
	 * via {@link org.impulsegraph.vm.ImpulseVmValidator#validate}.
	 */
	public static VmHandlers.Instruction[] toVmInstructions(List<InstructionWord> instructions) {
		Objects.requireNonNull(instructions, "instructions must not be null");
		VmHandlers.Instruction[] out = new VmHandlers.Instruction[instructions.size()];
		for (int i = 0; i < instructions.size(); i++) {
			InstructionWord w = Objects.requireNonNull(instructions.get(i), "instruction element must not be null");
			out[i] = new VmHandlers.Instruction(w.opcode(), w.flags(), Short.toUnsignedInt(w.dstReg()), w.payload());
		}
		return out;
	}
}
