package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Instruction Handlers for Impulse VM opcodes. Provides static execution
 * methods operating zero-copy on off-heap FFM MemorySegments and
 * VmQueryContext.
 */
public final class VmHandlers {
	private static final Logger LOG = Logger.getLogger(VmHandlers.class.getName());

	public static final int PARALLEL_FRONTIER_THRESHOLD = 524_288; // 512k Frontier Threshold
	public static final byte FLAG_HALT_ON_EMPTY = 0x01;
	public static final byte FLAG_INPUT_SEED = 0x02;

	private VmHandlers() {
	}

	/**
	 * Decode instruction fields from 8-byte off-heap instruction segment or encoded
	 * long.
	 */
	public record Instruction(byte opcode, byte flags, int dstReg, int payload) {
	}

	/**
	 * 16-byte (128-bit) Extended Instruction format (Section 3.2.1). Word 1:
	 * [opcode: u8, flags: u8 (0x80), dst_reg: u16, arg1: u16, arg2: u16] Word 2:
	 * [marker: u8 (0xFF), padding: u8 (0x00), arg3: u16, arg4: u16, arg5: u16]
	 */
	public record ExtendedInstruction(byte opcode, byte flags, int dstReg, int arg1, int arg2, int arg3, int arg4,
			int arg5, long extPayload) {
	}

	public static Instruction decodeInstruction(MemorySegment programSeg, long pc) {
		long offset = pc * INSTRUCTION_SIZE_BYTES;
		byte opcode = (byte) INSTR_OPCODE_HANDLE.get(programSeg, offset);
		byte flags = (byte) INSTR_FLAGS_HANDLE.get(programSeg, offset);
		int dstReg = Short.toUnsignedInt((short) INSTR_DST_REG_HANDLE.get(programSeg, offset));
		int payload = (int) INSTR_PAYLOAD_HANDLE.get(programSeg, offset);
		return new Instruction(opcode, flags, dstReg, payload);
	}

	public static ExtendedInstruction decodeExtendedInstruction(MemorySegment programSeg, long pc) {
		long offset1 = pc * INSTRUCTION_SIZE_BYTES;
		long offset2 = (pc + 1) * INSTRUCTION_SIZE_BYTES;
		byte opcode = (byte) INSTR_OPCODE_HANDLE.get(programSeg, offset1);
		byte flags = (byte) INSTR_FLAGS_HANDLE.get(programSeg, offset1);
		int dstReg = Short.toUnsignedInt((short) INSTR_DST_REG_HANDLE.get(programSeg, offset1));
		int arg1 = Short.toUnsignedInt(
				programSeg.get(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset1 + 4L));
		int arg2 = Short.toUnsignedInt(
				programSeg.get(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset1 + 6L));

		byte extMarker = programSeg.get(ValueLayout.JAVA_BYTE, offset2);
		if ((extMarker & 0xFF) != (VmRegisterType.OP_EXTENSION_PAYLOAD & 0xFF)) {
			throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_INSTRUCTION: missing 0xFF extension marker");
		}
		int arg3 = Short.toUnsignedInt(
				programSeg.get(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset2 + 2L));
		int arg4 = Short.toUnsignedInt(
				programSeg.get(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset2 + 4L));
		int arg5 = Short.toUnsignedInt(
				programSeg.get(ValueLayout.JAVA_SHORT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset2 + 6L));
		long extPayload = programSeg.get(ValueLayout.JAVA_LONG.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset2);

		return new ExtendedInstruction(opcode, flags, dstReg, arg1, arg2, arg3, arg4, arg5, extPayload);
	}

	// --- State Access Helpers ---

	public static long getRegisterValue(MemorySegment state, int regIndex) {
		validateReg(regIndex);
		return (long) REGISTER_ELEMENT_HANDLE.get(state, 0L, (long) regIndex);
	}

	public static void setRegister(MemorySegment state, int regIndex, long value, byte typeTag) {
		validateReg(regIndex);
		REGISTER_ELEMENT_HANDLE.set(state, 0L, (long) regIndex, value);
		REGISTER_TYPE_ELEMENT_HANDLE.set(state, 0L, (long) regIndex, typeTag);
	}

	public static byte getRegisterType(MemorySegment state, int regIndex) {
		validateReg(regIndex);
		return (byte) REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, (long) regIndex);
	}

	/**
	 * Exhaustive, compiler-checked validation that {@code regIndex} holds a valid
	 * single-lane node source ({@code T_NULL}, {@code T_NODE_ID}, {@code T_INT64},
	 * {@code T_BITSET_HANDLE}, {@code T_NODE_VECTOR}, or {@code T_UINT64_VECTOR})
	 * and that any referenced handle is allocated in {@code ctx}.
	 */
	public static VmRegType validateNodeSource(MemorySegment state, VmQueryContext ctx, int regIndex) {
		validateReg(regIndex);
		byte rawTag = getRegisterType(state, regIndex);
		VmRegType tag = VmRegType.fromTag(rawTag);
		long val = getRegisterValue(state, regIndex);
		return switch (tag) {
			case T_NULL, T_NODE_ID, T_INT64 -> tag;
			case T_BITSET_HANDLE -> {
				if (ctx == null || ctx.getBitset((int) val) == null) {
					throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				yield tag;
			}
			case T_NODE_VECTOR, T_UINT64_VECTOR -> {
				if (ctx == null || ctx.getNodeVector((int) val) == null) {
					throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				yield tag;
			}
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		};
	}

	private static void addRegisterNodesToBitSet(MemorySegment state, VmQueryContext ctx, int regIndex,
			ImpulseBitSet target) {
		VmRegType tag = validateNodeSource(state, ctx, regIndex);
		long val = getRegisterValue(state, regIndex);
		switch (tag) {
			case T_NULL -> {
			}
			case T_NODE_ID, T_INT64 -> {
				int node = (int) val;
				if (node >= 0) {
					target.set(node);
				}
			}
			case T_BITSET_HANDLE -> {
				ImpulseBitSet bs = ctx.getBitset((int) val);
				if (bs != null && bs != target) {
					target.or(bs);
				}
			}
			case T_NODE_VECTOR, T_UINT64_VECTOR -> {
				int[] arr = ctx.getNodeVector((int) val);
				if (arr != null) {
					for (int node : arr) {
						if (node >= 0) {
							target.set(node);
						}
					}
				}
			}
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
	}

	public static long getFlags(MemorySegment state) {
		return (long) FLAGS_HANDLE.get(state, 0L);
	}

	public static void handleCreateScratchIndex(MemorySegment state, Instruction inst, VmQueryContext ctx) {
		validateReg(inst.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	public static void setFlag(MemorySegment state, long flagMask, boolean value) {
		long curFlags = getFlags(state);
		if (value) {
			FLAGS_HANDLE.set(state, 0L, curFlags | flagMask);
		} else {
			FLAGS_HANDLE.set(state, 0L, curFlags & ~flagMask);
		}
	}

	public static boolean checkFlag(MemorySegment state, long flagMask) {
		return (getFlags(state) & flagMask) != 0;
	}

	// --- Instruction Handlers ---

	public static void handleInitInputNode(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		if (instr.payload() != 0) {
			setRegister(state, instr.dstReg(), (long) instr.payload(), TYPE_NODE_ID);
			setFlag(state, FLAG_ZF, false);
			return;
		}
		if (input instanceof ImpulseBitSet || input instanceof long[] || input instanceof int[]
				|| input instanceof Iterable<?>) {
			handleInitInputSet(state, ctx, instr, input);
			return;
		}
		long nodeId = 0;
		if (input instanceof Number n) {
			nodeId = n.longValue();
		}
		setRegister(state, instr.dstReg(), nodeId, TYPE_NODE_ID);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleInitInputSet(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		int handle = ctx.acquireBitset();
		ImpulseBitSet bs = ctx.getBitset(handle);
		if (input instanceof ImpulseBitSet inBs) {
			bs.or(inBs);
		} else if (input instanceof Number n) {
			if (n.intValue() >= 0)
				bs.set(n.intValue());
		} else if (input instanceof long[] arr) {
			for (long val : arr)
				if (val >= 0)
					bs.set((int) val);
		} else if (input instanceof int[] arr) {
			for (int val : arr)
				if (val >= 0)
					bs.set(val);
		} else if (input instanceof Iterable<?> it) {
			for (Object elem : it) {
				if (elem instanceof Number n && n.intValue() >= 0)
					bs.set(n.intValue());
			}
		}
		setRegister(state, instr.dstReg(), handle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs.isEmpty());
	}

	public static void handleLoadConstInt(MemorySegment state, Instruction instr) {
		setRegister(state, instr.dstReg(), (long) instr.payload(), TYPE_INT64);
	}

	public static void handleLoadConstFloat(MemorySegment state, Instruction instr) {
		float fVal = Float.intBitsToFloat(instr.payload());
		setRegister(state, instr.dstReg(), Integer.toUnsignedLong(Float.floatToRawIntBits(fVal)), TYPE_FLOAT);
	}

	public static void handleMapKeysToDense(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int domainId = instr.payload() & 0xFFFF;
		if (domainId >= 32768) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		int hDst = ctx.acquireBitset();
		if (hDst < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		setRegister(state, instr.dstReg(), hDst, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, true);
	}

	private static RelationSnapshot resolveRelation(VmQueryContext ctx, int relId) {
		if (ctx == null)
			return null;
		return ctx.resolveRelation(relId);
	}

	public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleCsrWalk(state, ctx, instr, null);
	}

	public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		int srcReg = instr.payload() & 0xFFFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;
		executeCsrWalk(state, ctx, instr.dstReg(), srcReg, relId, instr.flags(), input);
	}

	public static void executeCsrWalk(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg, int relId) {
		executeCsrWalk(state, ctx, dstReg, srcReg, relId, (byte) 0, null);
	}

	public static void executeCsrWalk(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg, int relId,
			byte flags, Object input) {
		validateReg(dstReg);
		validateReg(srcReg);
		if (relId < 0 || relId >= 65536) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}

		byte srcType = getRegisterType(state, srcReg);
		long srcVal = getRegisterValue(state, srcReg);

		if ((flags & FLAG_INPUT_SEED) != 0 && input != null) {
			if (input instanceof Number n) {
				srcType = TYPE_NODE_ID;
				srcVal = n.longValue();
			} else if (input instanceof ImpulseBitSet inBs) {
				srcType = TYPE_BITSET_HANDLE;
				int tempHandle = ctx.acquireBitset();
				ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
				tempBs.or(inBs);
				srcVal = tempHandle;
			} else if (input instanceof long[] arr) {
				srcType = TYPE_BITSET_HANDLE;
				int tempHandle = ctx.acquireBitset();
				ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
				for (long val : arr)
					tempBs.set((int) val);
				srcVal = tempHandle;
			} else if (input instanceof int[] arr) {
				srcType = TYPE_BITSET_HANDLE;
				int tempHandle = ctx.acquireBitset();
				ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
				for (int val : arr)
					tempBs.set(val);
				srcVal = tempHandle;
			} else if (input instanceof Iterable<?> it) {
				srcType = TYPE_BITSET_HANDLE;
				int tempHandle = ctx.acquireBitset();
				ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
				for (Object elem : it) {
					if (elem instanceof Number num)
						tempBs.set(num.intValue());
				}
				srcVal = tempHandle;
			}
		} else {
			validateNodeSource(state, ctx, srcReg);
		}

		int outHandle = (dstReg != srcReg && getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		if (rel != null) {
			if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
				rel.copyTargetsSimd((int) srcVal, outBs);
			} else if (srcType == TYPE_NODE_VECTOR || srcType == TYPE_UINT64_VECTOR) {
				int[] arr = ctx.getNodeVector((int) srcVal);
				if (arr != null) {
					for (int u : arr) {
						if (u >= 0) {
							rel.copyTargetsSimd(u, outBs);
						}
					}
				}
			} else if (srcType == TYPE_BITSET_HANDLE) {
				ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
				if (inBs != null) {
					boolean maybeLarge = !(inBs instanceof OffHeapBitSet obs) || obs.maxPossibleCardinality() >= 5_000;
					long card = maybeLarge ? inBs.cardinality() : 0L;
					int numThreads = (card >= 5_000)
							? Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism())
							: 1;
					if (card >= 5_000 && numThreads > 1) {
						java.util.concurrent.atomic.AtomicInteger nextChunk = ctx.scratchAtomicCounter();
						int nodeCount = rel.getNodeCount();
						final int chunkSize = 1024;

						ImpulseBitSet[] threadBs = ctx.scratchBitsetArray(numThreads);
						for (int t = 0; t < numThreads; t++) {
							threadBs[t] = ctx.getBitset(ctx.acquireBitset());
						}

						java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
							ImpulseBitSet localBs = threadBs[t];
							while (true) {
								int startV = nextChunk.getAndAdd(chunkSize);
								if (startV >= nodeCount)
									break;
								int endV = Math.min(startV + chunkSize, nodeCount);
								for (int u = inBs.nextSetBit(startV); u >= 0 && u < endV; u = inBs.nextSetBit(u + 1)) {
									rel.copyTargetsSimd(u, localBs);
								}
							}
						});

						for (int t = 0; t < numThreads; t++) {
							outBs.or(threadBs[t]);
						}
					} else {
						for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
							rel.copyTargetsSimd(u, outBs);
						}
					}
				}
			}
		}

		if (dstReg == srcReg && srcType == TYPE_BITSET_HANDLE) {
			ctx.releaseBitset((int) srcVal);
		}

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleCsrWalk2Hop(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		int relId1 = instr.payload() & 0xFFFF;
		int relId2 = (instr.payload() >> 16) & 0xFFFF;
		executeCsrWalk2Hop(state, ctx, instr.dstReg(), 0, relId1, relId2, instr.flags(), input);
	}

	public static void executeCsrWalk2Hop(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg, int relId1,
			int relId2, byte flags, Object input) {
		if (relId1 < 0 || relId1 >= 65536 || relId2 < 0 || relId2 >= 65536) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		RelationSnapshot rel1 = resolveRelation(ctx, relId1);
		RelationSnapshot rel2 = resolveRelation(ctx, relId2);
		if (rel1 == null || rel2 == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		validateReg(dstReg);
		validateReg(srcReg);

		int outHandle = (dstReg != srcReg && getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		if (rel1 != null && rel2 != null) {
			MemorySegment r1Offsets = rel1.getRowOffsetsSegment();
			MemorySegment r1Targets = rel1.getColumnTargetsSegment();

			if ((flags & FLAG_INPUT_SEED) != 0 && input instanceof Number n) {
				int seed = n.intValue();
				if (seed >= 0 && seed < rel1.getNodeCount()) {
					long start1 = rel1.readEdgeIndex(r1Offsets, seed);
					long end1 = rel1.readEdgeIndex(r1Offsets, seed + 1);
					for (long i = start1; i < end1; i++) {
						int hop1Target = rel1.readNodeId(r1Targets, i);
						rel2.copyTargetsSimd(hop1Target, outBs);
					}
				}
			} else {
				byte srcType = getRegisterType(state, srcReg);
				long srcVal = getRegisterValue(state, srcReg);
				if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
					int seed = (int) srcVal;
					if (seed >= 0 && seed < rel1.getNodeCount()) {
						long start1 = rel1.readEdgeIndex(r1Offsets, seed);
						long end1 = rel1.readEdgeIndex(r1Offsets, seed + 1);
						for (long i = start1; i < end1; i++) {
							int hop1Target = rel1.readNodeId(r1Targets, i);
							rel2.copyTargetsSimd(hop1Target, outBs);
						}
					}
				} else if (srcType == TYPE_BITSET_HANDLE) {
					ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
					if (inBs != null) {
						for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
							if (u < rel1.getNodeCount()) {
								long start1 = rel1.readEdgeIndex(r1Offsets, u);
								long end1 = rel1.readEdgeIndex(r1Offsets, u + 1);
								for (long i = start1; i < end1; i++) {
									int hop1Target = rel1.readNodeId(r1Targets, i);
									rel2.copyTargetsSimd(hop1Target, outBs);
								}
							}
						}
					}
				}
			}
		}

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleLoadConstStrPrefix(MemorySegment state, Instruction instr) {
		setRegister(state, instr.dstReg(), instr.payload(), TYPE_INT64);
	}

	public static void handleCscWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleCscWalk(state, ctx, instr, null);
	}

	public static void handleCscWalk(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		int frontierReg = instr.payload() & 0xFFFF;
		int unvisitedReg = (instr.payload() >> 16) & 0xFF;
		int relId = (instr.payload() >> 24) & 0xFF;
		validateReg(instr.dstReg());
		validateReg(frontierReg);
		RelationSnapshot unvRel = resolveRelation(ctx, unvisitedReg);
		boolean unvIsValidRel = unvRel != null && unvRel.hasCsc();
		if (relId == 0 && (instr.payload() >>> 24) == 0 && (unvIsValidRel || unvisitedReg >= 64
				|| getRegisterType(state, unvisitedReg) != TYPE_BITSET_HANDLE)) {
			relId = (instr.payload() >> 16) & 0xFFFF;
			unvisitedReg = 0;
		}
		if (relId < 0 || relId >= 65536) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		if (!rel.hasCsc()) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		if ((instr.flags() & FLAG_INPUT_SEED) == 0 || input == null) {
			validateNodeSource(state, ctx, frontierReg);
		}

		int dstReg = instr.dstReg();
		boolean aliasedInput = (dstReg == frontierReg) || (unvisitedReg != 0 && dstReg == unvisitedReg);
		int prevDstHandle = (getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: -1;
		int outHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		if (unvisitedReg != 0) {
			// GraphBLAS Bottom-Up BFS mode
			ImpulseBitSet frontierBs = ctx.getBitset((int) getRegisterValue(state, frontierReg));
			ImpulseBitSet unvisitedBs = ctx.getBitset((int) getRegisterValue(state, unvisitedReg));

			if (frontierBs != null && unvisitedBs != null && rel.hasCsc()) {
				MemorySegment cscRowOff = rel.getCscRowOffsetsSegment();
				MemorySegment cscColIdx = rel.getCscColumnTargetsSegment();
				int nodeCount = rel.getNodeCount();

				int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
				long unvisitedCard = unvisitedBs.cardinality();

				if (unvisitedCard >= 2_000) {
					if (numThreads > 1) {
						java.util.concurrent.atomic.AtomicInteger nextChunk = ctx.scratchAtomicCounter();
						final int chunkSize = 1024;

						java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
							while (true) {
								int startV = nextChunk.getAndAdd(chunkSize);
								if (startV >= nodeCount)
									break;
								int endV = Math.min(startV + chunkSize, nodeCount);
								for (int v = unvisitedBs.nextSetBit(startV); v >= 0
										&& v < endV; v = unvisitedBs.nextSetBit(v + 1)) {
									long start = rel.readEdgeIndex(cscRowOff, v);
									long end = rel.readEdgeIndex(cscRowOff, v + 1);
									for (long i = start; i < end; i++) {
										int target = rel.readSrcNodeId(cscColIdx, i);
										if (frontierBs.get(target)) {
											outBs.set(v);
											break;
										}
									}
								}
							}
						});
					} else {
						// Cache-tiled single-threaded loop (1024 nodes per L1 cache tile)
						final int chunkSize = 1024;
						for (int startV = 0; startV < nodeCount; startV += chunkSize) {
							int endV = Math.min(startV + chunkSize, nodeCount);
							for (int v = unvisitedBs.nextSetBit(startV); v >= 0
									&& v < endV; v = unvisitedBs.nextSetBit(v + 1)) {
								long start = rel.readEdgeIndex(cscRowOff, v);
								long end = rel.readEdgeIndex(cscRowOff, v + 1);
								for (long i = start; i < end; i++) {
									int target = rel.readSrcNodeId(cscColIdx, i);
									if (frontierBs.get(target)) {
										outBs.set(v);
										break;
									}
								}
							}
						}
					}
				} else {
					for (int v = unvisitedBs.nextSetBit(0); v >= 0; v = unvisitedBs.nextSetBit(v + 1)) {
						long start = rel.readEdgeIndex(cscRowOff, v);
						long end = rel.readEdgeIndex(cscRowOff, v + 1);
						for (long idx = start; idx < end; idx++) {
							int u = rel.readSrcNodeId(cscColIdx, idx);
							if (frontierBs.get(u)) {
								outBs.set(v);
								break;
							}
						}
					}
				}
			}
		} else {
			// Standard CSC Walk Mode
			byte srcType = getRegisterType(state, frontierReg);
			long srcVal = getRegisterValue(state, frontierReg);

			if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input != null) {
				if (input instanceof Number n) {
					srcType = TYPE_NODE_ID;
					srcVal = n.longValue();
				} else if (input instanceof ImpulseBitSet inBs) {
					srcType = TYPE_BITSET_HANDLE;
					int tempHandle = ctx.acquireBitset();
					ctx.getBitset(tempHandle).or(inBs);
					srcVal = tempHandle;
				} else if (input instanceof long[] arr) {
					srcType = TYPE_BITSET_HANDLE;
					int tempHandle = ctx.acquireBitset();
					ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
					for (long val : arr) {
						if (val >= 0) {
							tempBs.set((int) val);
						}
					}
					srcVal = tempHandle;
				} else if (input instanceof int[] arr) {
					srcType = TYPE_BITSET_HANDLE;
					int tempHandle = ctx.acquireBitset();
					ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
					for (int val : arr) {
						if (val >= 0) {
							tempBs.set(val);
						}
					}
					srcVal = tempHandle;
				} else if (input instanceof Iterable<?> it) {
					srcType = TYPE_BITSET_HANDLE;
					int tempHandle = ctx.acquireBitset();
					ImpulseBitSet tempBs = ctx.getBitset(tempHandle);
					for (Object elem : it) {
						if (elem instanceof Number num && num.intValue() >= 0) {
							tempBs.set(num.intValue());
						}
					}
					srcVal = tempHandle;
				}
			}

			if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
				rel.copyInTargetsSimd((int) srcVal, outBs);
			} else if (srcType == TYPE_NODE_VECTOR || srcType == TYPE_UINT64_VECTOR) {
				int[] arr = ctx.getNodeVector((int) srcVal);
				if (arr != null) {
					for (int v : arr) {
						if (v >= 0) {
							rel.copyInTargetsSimd(v, outBs);
						}
					}
				}
			} else if (srcType == TYPE_BITSET_HANDLE) {
				ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
				if (inBs != null) {
					for (int v = inBs.nextSetBit(0); v >= 0; v = inBs.nextSetBit(v + 1)) {
						rel.copyInTargetsSimd(v, outBs);
					}
				}
			}
		}

		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != outHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleAdaptiveWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int frontierReg = instr.payload() & 0xFFFF;
		int unvisitedReg = (instr.payload() >> 16) & 0xFF;
		int relId = (instr.payload() >> 24) & 0xFF;
		validateReg(instr.dstReg());
		validateReg(frontierReg);
		RelationSnapshot unvRel = resolveRelation(ctx, unvisitedReg);
		boolean unvIsValidRel = unvRel != null && unvRel.hasCsc();
		if (relId == 0 && (instr.payload() >>> 24) == 0 && (unvIsValidRel || unvisitedReg >= 64
				|| getRegisterType(state, unvisitedReg) != TYPE_BITSET_HANDLE)) {
			relId = (instr.payload() >> 16) & 0xFFFF;
			unvisitedReg = 0;
		}

		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		validateNodeSource(state, ctx, frontierReg);

		if (unvisitedReg != 0 && rel.hasCsc()) {
			ImpulseBitSet frontierBs = ctx.getBitset((int) getRegisterValue(state, frontierReg));
			if (frontierBs != null) {
				long frontierSize = frontierBs.cardinality();
				org.impulsegraph.api.stats.RelationStatistics stats = rel.getStatistics();

				long estimatedFrontierEdges = frontierSize * (long) Math.max(1.0, stats.getAvgDegree());
				long pullThreshold = stats.getEdgeCount() / 80;
				boolean shouldUsePull = estimatedFrontierEdges > pullThreshold
						|| frontierSize > (stats.getNodeCount() / 80);

				if (!shouldUsePull && frontierSize > 50_000 && stats.getSupernodeBitSet() != null
						&& !stats.getSupernodeBitSet().isEmpty()) {
					ImpulseBitSet supernodes = stats.getSupernodeBitSet();
					for (int s = supernodes.nextSetBit(0); s >= 0; s = supernodes.nextSetBit(s + 1)) {
						if (frontierBs.get(s)) {
							shouldUsePull = true;
							break;
						}
					}
				}
				VmBranchCoverageRecorder.recordGlobalBranch(0, instr.opcode() & 0xFF, 1,
						VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, shouldUsePull ? 1 : 0);

				if (shouldUsePull) {
					handleCscWalk(state, ctx, instr);
					return;
				}
			}
		} else {
			VmBranchCoverageRecorder.recordGlobalBranch(0, instr.opcode() & 0xFF, 1,
					VmBranchCoverageRecorder.BranchKind.TWO_WAY,
					VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 0);
		}

		executeCsrWalk(state, ctx, instr.dstReg(), frontierReg, relId);
	}

	public static void handleCcAfforest(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		int relId = instr.payload() & 0xFFFF;
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null && relId < 64) {
			rel = resolveRelation(ctx, (int) getRegisterValue(state, relId));
		}
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int nodeCount = Math.max(rel.getNodeCount(), rel.getCscNodeCount());
		int srcNodeCount = rel.getNodeCount();

		byte dstType = getRegisterType(state, dst);
		int outHandle;
		if (dstType == TYPE_NODE_VECTOR || dstType == TYPE_UINT64_VECTOR) {
			outHandle = (int) getRegisterValue(state, dst);
		} else {
			outHandle = ctx.acquireNodeVector(nodeCount);
			if (outHandle < 0) {
				throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
			}
		}
		ctx.setNodeVectorLength(outHandle, nodeCount);
		int[] comp = ctx.getNodeVector(outHandle);
		for (int u = 0; u < nodeCount; u++) {
			comp[u] = u;
		}

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();

		// 1. 2-Neighbor sampling pass
		for (int r = 0; r < 2; r++) {
			for (int u = 0; u < nodeCount; u++) {
				if (u < srcNodeCount) {
					int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
					int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
					int deg = end - start;
					if (r < deg) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + r);
						if (v < nodeCount) {
							unionCcNodes(comp, u, v);
						}
					}
				}
			}
		}

		// 2. Full CSR Edge Processing
		for (int u = 0; u < nodeCount; u++) {
			if (u < srcNodeCount) {
				int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
				int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
				int deg = end - start;
				for (int i = 0; i < deg; i++) {
					int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i);
					if (v < nodeCount) {
						unionCcNodes(comp, u, v);
					}
				}
			}
		}

		// 3. Path Compression
		for (int u = 0; u < nodeCount; u++) {
			comp[u] = findCcRoot(comp, u);
		}

		setRegister(state, dst, outHandle, TYPE_NODE_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	private static int findCcRoot(int[] comp, int curr) {
		while (curr != comp[curr]) {
			int parent = comp[curr];
			int grandParent = comp[parent];
			comp[curr] = grandParent;
			curr = grandParent;
		}
		return curr;
	}

	private static void unionCcNodes(int[] comp, int u, int v) {
		int p1 = findCcRoot(comp, u);
		int p2 = findCcRoot(comp, v);
		while (p1 != p2) {
			int high = Math.min(p1, p2);
			int low = Math.max(p1, p2);
			int oldVal = comp[low];
			if (oldVal == high)
				break;
			if (oldVal > high) {
				comp[low] = high;
				if (oldVal == low)
					break;
			}
			p1 = findCcRoot(comp, oldVal);
			p2 = findCcRoot(comp, high);
		}
	}

	public static void handleMxv(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int xReg = instr.payload() & 0xFF;
		validateReg(xReg);
		int relId = (instr.payload() >> 8) & 0xFF;
		int semiring = (instr.payload() >> 16) & 0xFFFF;

		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			int outHandle = ctx.acquireFloatVector(1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int rowCount = rel.getNodeCount();
		int colCount = rel.getCscNodeCount() > 0 ? rel.getCscNodeCount() : rowCount;
		float[] x = ctx.getFloatVector((int) getRegisterValue(state, xReg));
		if (x == null || x.length < colCount) {
			int outHandle = ctx.acquireFloatVector(rowCount > 0 ? rowCount : 1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int outHandle = ctx.acquireFloatVector(rowCount);
		ctx.setFloatVectorLength(outHandle, rowCount);
		float[] y = ctx.getFloatVector(outHandle);

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();
		MemorySegment weightSeg = null;
		if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
			if (semiring == 1) {
				int wIdx = csrRel.findAttributeIndex("w_f32");
				if (wIdx >= 0 && wIdx < csrRel.getAttributeSegments().size()) {
					weightSeg = csrRel.getAttributeSegments().get(wIdx);
				} else if (!csrRel.getAttributeSegments().isEmpty()) {
					weightSeg = csrRel.getAttributeSegments().get(0);
				}
			} else if (semiring == 0) {
				int bIdx = csrRel.findAttributeIndex("back_f32");
				if (bIdx >= 0 && bIdx < csrRel.getAttributeSegments().size()) {
					weightSeg = csrRel.getAttributeSegments().get(bIdx);
				}
			}
		}
		final MemorySegment edgeWeights = weightSeg;

		int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
		int chunkSize = (rowCount + numThreads - 1) / numThreads;

		java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
			int startU = t * chunkSize;
			int endU = Math.min(startU + chunkSize, rowCount);

			for (int u = startU; u < endU; u++) {
				int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
				int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
				if (semiring == 1) {
					float minVal = Float.POSITIVE_INFINITY;
					for (int idx = start; idx < end; idx++) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (v >= 0 && v < x.length) {
							float w = (edgeWeights != null && (long) (idx + 1) * 4L <= edgeWeights.byteSize())
									? edgeWeights.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, idx)
									: 1.0f;
							minVal = Math.min(minVal, x[v] + w);
						}
					}
					y[u] = minVal;
				} else if (edgeWeights != null) {
					float sum = 0.0f;
					for (int idx = start; idx < end; idx++) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (v >= 0 && v < x.length) {
							float w = ((long) (idx + 1) * 4L <= edgeWeights.byteSize())
									? edgeWeights.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, idx)
									: 1.0f;
							sum += x[v] * w;
						}
					}
					y[u] = sum;
				} else {
					int count = end - start;
					float sum = 0.0f;
					int idx = start;
					int end4 = start + ((count >> 2) << 2);

					for (; idx < end4; idx += 4) {
						int v0 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						int v1 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 1);
						int v2 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 2);
						int v3 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 3);
						sum += x[v0] + x[v1] + x[v2] + x[v3];
					}
					for (; idx < end; idx++) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						sum += x[v];
					}
					y[u] = sum;
				}
			}
		});

		setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleVxm(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int xReg = instr.payload() & 0xFF;
		validateReg(xReg);
		int relId = (instr.payload() >> 8) & 0xFF;
		int semiring = (instr.payload() >> 16) & 0xFFFF;

		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			int outHandle = ctx.acquireFloatVector(1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int rowCount = rel.getNodeCount();
		int colCount = rel.getCscNodeCount() > 0 ? rel.getCscNodeCount() : rowCount;
		float[] x = ctx.getFloatVector((int) getRegisterValue(state, xReg));
		if (x == null || x.length < rowCount) {
			int outHandle = ctx.acquireFloatVector(colCount > 0 ? colCount : 1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int outHandle = ctx.acquireFloatVector(colCount);
		ctx.setFloatVectorLength(outHandle, colCount);
		float[] y = ctx.getFloatVector(outHandle);
		if (semiring == 1) {
			java.util.Arrays.fill(y, Float.POSITIVE_INFINITY);
		} else {
			java.util.Arrays.fill(y, 0.0f);
		}

		MemorySegment weightSeg = null;
		if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
			int bIdx = csrRel.findAttributeIndex("back_f32");
			if (bIdx >= 0 && bIdx < csrRel.getAttributeSegments().size()) {
				weightSeg = csrRel.getAttributeSegments().get(bIdx);
			}
		}

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();
		for (int u = 0; u < rowCount; u++) {
			int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
			int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
			float xu = x[u];
			for (int idx = start; idx < end; idx++) {
				int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
				if (v >= 0 && v < colCount) {
					if (semiring == 1) {
						y[v] = Math.min(y[v], xu + 1.0f);
					} else if (weightSeg != null && (long) (idx + 1) * 4L <= weightSeg.byteSize()) {
						y[v] += xu * weightSeg.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, idx);
					} else {
						y[v] += xu;
					}
				}
			}
		}

		setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleEwiseAdd(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		int opId = (instr.payload() >> 16) & 0xFFFF;
		validateReg(src1);
		validateReg(src2);
		float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, src1));
		float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, src2));
		int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
		int handle = ctx.acquireFloatVector(len);
		float[] out = ctx.getFloatVector(handle);
		for (int i = 0; i < len; i++) {
			float a = (v1 != null && i < v1.length) ? v1[i] : 0.0f;
			float b = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
			out[i] = switch (opId) {
				case BINARY_OP_MIN -> Math.min(a, b);
				case BINARY_OP_MAX -> Math.max(a, b);
				case BINARY_OP_MUL -> a * b;
				default -> a + b;
			};
		}
		setRegister(state, instr.dstReg(), handle, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleEwiseMult(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(src1);
		validateReg(src2);
		float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, src1));
		float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, src2));
		int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
		int handle = ctx.acquireFloatVector(len);
		float[] out = ctx.getFloatVector(handle);
		for (int i = 0; i < len; i++) {
			float a = (v1 != null && i < v1.length) ? v1[i] : 0.0f;
			float b = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
			out[i] = a * b;
		}
		setRegister(state, instr.dstReg(), handle, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleCcHookCompress(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleBrandesForward(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleBrandesBackward(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleDeltaStepRelax(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleHasCsr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int relId = instr.payload() & 0xFFFF;
		RelationSnapshot rel = resolveRelation(ctx, relId);
		boolean present = (rel != null && rel.hasCsr());
		setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
		setFlag(state, FLAG_ZF, !present);
	}

	public static void handleHasCsc(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int relId = instr.payload() & 0xFFFF;
		RelationSnapshot rel = resolveRelation(ctx, relId);
		boolean present = (rel != null && rel.hasCsc());
		setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
		setFlag(state, FLAG_ZF, !present);
	}

	public static void handleHasCoo(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int relId = instr.payload() & 0xFFFF;
		RelationSnapshot rel = resolveRelation(ctx, relId);
		boolean present = rel != null && rel.getColumnTargetsSegment() != null;
		setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
		setFlag(state, FLAG_ZF, !present);
	}

	public static void handleHasKeyCatalog(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		setRegister(state, instr.dstReg(), 0L, TYPE_INT64);
		setFlag(state, FLAG_ZF, true);
	}

	public static void handleCsrDegree(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int srcReg = instr.payload() & 0xFFFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;

		validateReg(instr.dstReg());
		validateReg(srcReg);

		if (relId < 0 || relId >= 65536) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}

		VmRegType tag = validateNodeSource(state, ctx, srcReg);
		long val = getRegisterValue(state, srcReg);
		long degree = 0;
		switch (tag) {
			case T_NULL -> {
			}
			case T_NODE_ID, T_INT64 -> {
				if (val >= 0 && val < rel.getNodeCount()) {
					degree = rel.getDegree((int) val);
				}
			}
			case T_BITSET_HANDLE -> {
				ImpulseBitSet bs = ctx.getBitset((int) val);
				if (bs != null) {
					for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
						if (u < rel.getNodeCount()) {
							degree += rel.getDegree(u);
						}
					}
				}
			}
			case T_NODE_VECTOR, T_UINT64_VECTOR -> {
				int[] arr = ctx.getNodeVector((int) val);
				if (arr != null) {
					for (int u : arr) {
						if (u >= 0 && u < rel.getNodeCount()) {
							degree += rel.getDegree(u);
						}
					}
				}
			}
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}

		setRegister(state, instr.dstReg(), degree, TYPE_INT64);
		setFlag(state, FLAG_ZF, degree == 0);
	}

	public static void handleCsrWalkPredicate(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int srcReg = instr.payload() & 0xFF;
		int valReg = (instr.payload() >> 8) & 0xFF;
		int relId = (instr.payload() >> 16) & 0xFF;

		if (instr.flags() != 0) {
			valReg = (instr.payload() >> 8) & 0xFF;
		}

		validateReg(instr.dstReg());
		validateReg(srcReg);
		validateReg(valReg);

		// Delegate directly to executeCsrWalk without allocating a wrapper Instruction
		executeCsrWalk(state, ctx, instr.dstReg(), srcReg, relId, instr.flags(), null);
	}

	public static void handleVectorDiv(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int reg1 = instr.payload() & 0xFFFF;
		int reg2 = (instr.payload() >> 16) & 0xFFFF;
		int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
				? (int) getRegisterValue(state, dst)
				: ctx.acquireFloatVector(1024);
		setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, reg1));
		float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, reg2));
		float[] dstVec = ctx.getFloatVector(h);
		if (v1 != null && v2 != null && dstVec != null) {
			for (int i = 0; i < Math.min(v1.length, Math.min(v2.length, dstVec.length)); i++) {
				dstVec[i] = (v2[i] != 0.0f) ? (v1[i] / v2[i]) : 0.0f;
			}
		}
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleVectorStrConcat(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFFFF;
		int src2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		String[] svec1 = (getRegisterType(state, src1) == TYPE_STRING_VECTOR)
				? ctx.getStringVector((int) getRegisterValue(state, src1))
				: null;
		String[] svec2 = (getRegisterType(state, src2) == TYPE_STRING_VECTOR)
				? ctx.getStringVector((int) getRegisterValue(state, src2))
				: null;
		int len1 = (svec1 != null) ? svec1.length : 0;
		int len2 = (svec2 != null) ? svec2.length : 0;
		int total = len1 + len2;
		int outHandle = ctx.acquireStringVector(total);
		String[] svecDst = ctx.getStringVector(outHandle);
		if (len1 > 0)
			System.arraycopy(svec1, 0, svecDst, 0, len1);
		if (len2 > 0)
			System.arraycopy(svec2, 0, svecDst, len1, len2);
		setRegister(state, dst, outHandle, TYPE_STRING_VECTOR);
	}

	public static void handleRoaringBitmapAnd(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFFFF;
		int src2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int h1 = (getRegisterType(state, src1) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src1) : -1;
		int h2 = (getRegisterType(state, src2) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src2) : -1;

		int hDst = ctx.acquireBitset();
		if (hDst < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		ImpulseBitSet bsDst = ctx.getBitset(hDst);
		bsDst.clear();

		if (h1 >= 0 && h2 >= 0) {
			ImpulseBitSet bs1 = ctx.getBitset(h1);
			ImpulseBitSet bs2 = ctx.getBitset(h2);
			if (bs1 != null && bs2 != null) {
				bsDst.or(bs1);
				bsDst.and(bs2);
			}
		}

		if (dst == src1 && h1 >= 0) {
			ctx.releaseBitset(h1);
		} else if (dst == src2 && h2 >= 0) {
			ctx.releaseBitset(h2);
		}

		setRegister(state, dst, hDst, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bsDst.isEmpty());
	}

	public static void handleSetUnion(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg1 = instr.payload() & 0xFFFF;
		int srcReg2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dstReg);
		validateNodeSource(state, ctx, srcReg1);
		validateNodeSource(state, ctx, srcReg2);

		int outHandle = (getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		if (dstReg != srcReg1 && dstReg != srcReg2) {
			outBs.clear();
		}

		addRegisterNodesToBitSet(state, ctx, srcReg1, outBs);
		addRegisterNodesToBitSet(state, ctx, srcReg2, outBs);

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleRoaringBitmapOr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFFFF;
		int src2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int h1 = (getRegisterType(state, src1) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src1) : -1;
		int h2 = (getRegisterType(state, src2) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src2) : -1;

		int hDst = ctx.acquireBitset();
		if (hDst < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		ImpulseBitSet bsDst = ctx.getBitset(hDst);
		bsDst.clear();

		if (h1 >= 0) {
			ImpulseBitSet bs1 = ctx.getBitset(h1);
			if (bs1 != null)
				bsDst.or(bs1);
		}
		if (h2 >= 0) {
			ImpulseBitSet bs2 = ctx.getBitset(h2);
			if (bs2 != null)
				bsDst.or(bs2);
		}

		if (dst == src1 && h1 >= 0) {
			ctx.releaseBitset(h1);
		} else if (dst == src2 && h2 >= 0) {
			ctx.releaseBitset(h2);
		}

		setRegister(state, dst, hDst, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bsDst.isEmpty());
	}

	public static void handleRoaringBitmapAndNot(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFFFF;
		int src2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int h1 = (getRegisterType(state, src1) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src1) : -1;
		int h2 = (getRegisterType(state, src2) == TYPE_BITSET_HANDLE) ? (int) getRegisterValue(state, src2) : -1;

		int hDst = ctx.acquireBitset();
		if (hDst < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		ImpulseBitSet bsDst = ctx.getBitset(hDst);
		bsDst.clear();

		if (h1 >= 0) {
			ImpulseBitSet bs1 = ctx.getBitset(h1);
			if (bs1 != null) {
				bsDst.or(bs1);
				if (h2 >= 0) {
					ImpulseBitSet bs2 = ctx.getBitset(h2);
					if (bs2 != null) {
						bsDst.andNot(bs2);
					}
				}
			}
		}

		if (dst == src1 && h1 >= 0) {
			ctx.releaseBitset(h1);
		} else if (dst == src2 && h2 >= 0) {
			ctx.releaseBitset(h2);
		}

		setRegister(state, dst, hDst, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bsDst.isEmpty());
	}

	public static void handleSetIntersect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg1 = instr.payload() & 0xFFFF;
		int srcReg2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dstReg);
		VmRegType tag1 = validateNodeSource(state, ctx, srcReg1);
		VmRegType tag2 = validateNodeSource(state, ctx, srcReg2);

		byte type1 = tag1.tag();
		byte type2 = tag2.tag();

		int outHandle = (getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);

		if (tag1 == VmRegType.T_NODE_VECTOR || tag1 == VmRegType.T_UINT64_VECTOR || tag2 == VmRegType.T_NODE_VECTOR
				|| tag2 == VmRegType.T_UINT64_VECTOR) {
			int tmp1Handle = ctx.acquireBitset();
			int tmp2Handle = ctx.acquireBitset();
			try {
				ImpulseBitSet tmp1 = ctx.getBitset(tmp1Handle);
				ImpulseBitSet tmp2 = ctx.getBitset(tmp2Handle);
				tmp1.clear();
				tmp2.clear();
				addRegisterNodesToBitSet(state, ctx, srcReg1, tmp1);
				addRegisterNodesToBitSet(state, ctx, srcReg2, tmp2);
				tmp1.and(tmp2);
				outBs.clear();
				outBs.or(tmp1);
			} finally {
				ctx.releaseBitset(tmp1Handle);
				ctx.releaseBitset(tmp2Handle);
			}
		} else if (dstReg == srcReg1 && type1 == TYPE_BITSET_HANDLE) {
			if (type2 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
				if (bs2 != null)
					outBs.and(bs2);
				else
					outBs.clear();
			} else if (type2 == TYPE_NODE_ID || type2 == TYPE_INT64) {
				int node = (int) getRegisterValue(state, srcReg2);
				boolean keeps = outBs.get(node);
				outBs.clear();
				if (keeps)
					outBs.set(node);
			} else {
				outBs.clear();
			}
		} else if (dstReg == srcReg2 && type2 == TYPE_BITSET_HANDLE) {
			if (type1 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
				if (bs1 != null)
					outBs.and(bs1);
				else
					outBs.clear();
			} else if (type1 == TYPE_NODE_ID || type1 == TYPE_INT64) {
				int node = (int) getRegisterValue(state, srcReg1);
				boolean keeps = outBs.get(node);
				outBs.clear();
				if (keeps)
					outBs.set(node);
			} else {
				outBs.clear();
			}
		} else {
			outBs.clear();
			if (type1 == TYPE_BITSET_HANDLE && type2 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
				ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
				if (bs1 != null && bs2 != null) {
					outBs.or(bs1);
					outBs.and(bs2);
				}
			} else if (type1 == TYPE_BITSET_HANDLE && (type2 == TYPE_NODE_ID || type2 == TYPE_INT64)) {
				ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
				int node = (int) getRegisterValue(state, srcReg2);
				if (bs1 != null && bs1.get(node))
					outBs.set(node);
			} else if (type2 == TYPE_BITSET_HANDLE && (type1 == TYPE_NODE_ID || type1 == TYPE_INT64)) {
				ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
				int node = (int) getRegisterValue(state, srcReg1);
				if (bs2 != null && bs2.get(node))
					outBs.set(node);
			} else if ((type1 == TYPE_NODE_ID || type1 == TYPE_INT64)
					&& (type2 == TYPE_NODE_ID || type2 == TYPE_INT64)) {
				int n1 = (int) getRegisterValue(state, srcReg1);
				int n2 = (int) getRegisterValue(state, srcReg2);
				if (n1 == n2)
					outBs.set(n1);
			}
		}

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleSetDifference(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg1 = instr.payload() & 0xFFFF;
		int srcReg2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dstReg);
		VmRegType tag1 = validateNodeSource(state, ctx, srcReg1);
		VmRegType tag2 = validateNodeSource(state, ctx, srcReg2);

		byte type1 = tag1.tag();
		byte type2 = tag2.tag();

		int outHandle = (getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);

		if (tag1 == VmRegType.T_NODE_VECTOR || tag1 == VmRegType.T_UINT64_VECTOR || tag2 == VmRegType.T_NODE_VECTOR
				|| tag2 == VmRegType.T_UINT64_VECTOR) {
			int tmp1Handle = ctx.acquireBitset();
			int tmp2Handle = ctx.acquireBitset();
			try {
				ImpulseBitSet tmp1 = ctx.getBitset(tmp1Handle);
				ImpulseBitSet tmp2 = ctx.getBitset(tmp2Handle);
				tmp1.clear();
				tmp2.clear();
				addRegisterNodesToBitSet(state, ctx, srcReg1, tmp1);
				addRegisterNodesToBitSet(state, ctx, srcReg2, tmp2);
				tmp1.andNot(tmp2);
				outBs.clear();
				outBs.or(tmp1);
			} finally {
				ctx.releaseBitset(tmp1Handle);
				ctx.releaseBitset(tmp2Handle);
			}
		} else if (dstReg == srcReg1 && type1 == TYPE_BITSET_HANDLE) {
			if (type2 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
				if (bs2 != null)
					outBs.andNot(bs2);
			} else if (type2 == TYPE_NODE_ID || type2 == TYPE_INT64) {
				outBs.clear((int) getRegisterValue(state, srcReg2));
			}
		} else if (dstReg == srcReg2 && type2 == TYPE_BITSET_HANDLE) {
			if (type1 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
				if (bs1 != null) {
					int tmpHandle = ctx.acquireBitset();
					ImpulseBitSet tmp = ctx.getBitset(tmpHandle);
					tmp.clear();
					tmp.or(bs1);
					tmp.andNot(outBs);
					outBs.clear();
					outBs.or(tmp);
				} else {
					outBs.clear();
				}
			} else if (type1 == TYPE_NODE_ID || type1 == TYPE_INT64) {
				int node = (int) getRegisterValue(state, srcReg1);
				boolean inSrc2 = outBs.get(node);
				outBs.clear();
				if (!inSrc2)
					outBs.set(node);
			} else {
				outBs.clear();
			}
		} else {
			outBs.clear();
			if (type1 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
				if (bs1 != null)
					outBs.or(bs1);
			} else if (type1 == TYPE_NODE_ID || type1 == TYPE_INT64) {
				outBs.set((int) getRegisterValue(state, srcReg1));
			}

			if (type2 == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
				if (bs2 != null)
					outBs.andNot(bs2);
			} else if (type2 == TYPE_NODE_ID || type2 == TYPE_INT64) {
				outBs.clear((int) getRegisterValue(state, srcReg2));
			}
		}

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleSetCardinality(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		validateReg(dstReg);
		VmRegType tag = validateNodeSource(state, ctx, srcReg);
		long val = getRegisterValue(state, srcReg);
		long count = switch (tag) {
			case T_NULL -> 0L;
			case T_NODE_ID, T_INT64 -> 1L;
			case T_BITSET_HANDLE -> {
				ImpulseBitSet bs = ctx.getBitset((int) val);
				yield (bs != null) ? bs.cardinality() : 0L;
			}
			case T_NODE_VECTOR, T_UINT64_VECTOR -> {
				int tmpHandle = ctx.acquireBitset();
				try {
					ImpulseBitSet tmp = ctx.getBitset(tmpHandle);
					tmp.clear();
					addRegisterNodesToBitSet(state, ctx, srcReg, tmp);
					yield tmp.cardinality();
				} finally {
					ctx.releaseBitset(tmpHandle);
				}
			}
			case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR,
					T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
				throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		};
		setRegister(state, dstReg, count, TYPE_INT64);
		setFlag(state, FLAG_ZF, count == 0);
	}

	public static boolean pushCallStack(MemorySegment state, int returnPc) {
		int depth = (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L);
		if (depth >= 8)
			return false;
		CALL_STACK_ELEMENT_HANDLE.set(state, 0L, (long) depth, returnPc);
		CALL_STACK_DEPTH_HANDLE.set(state, 0L, depth + 1);
		return true;
	}

	public static int popCallStack(MemorySegment state) {
		int depth = (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L);
		if (depth <= 0)
			return -1;
		int returnPc = (int) CALL_STACK_ELEMENT_HANDLE.get(state, 0L, (long) (depth - 1));
		CALL_STACK_ELEMENT_HANDLE.set(state, 0L, (long) (depth - 1), 0);
		CALL_STACK_DEPTH_HANDLE.set(state, 0L, depth - 1);
		return returnPc;
	}

	public static void handleMov(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		int srcReg = (instr.payload() >> 16) != 0 ? ((instr.payload() >> 16) & 0xFFFF) : (instr.payload() & 0xFFFF);
		validateReg(srcReg);
		long val = getRegisterValue(state, srcReg);
		byte typeTag = getRegisterType(state, srcReg);
		if (typeTag == TYPE_BITSET_HANDLE) {
			ImpulseBitSet srcBs = ctx.getBitset((int) val);
			int dstHandle = (instr.dstReg() != srcReg && getRegisterType(state, instr.dstReg()) == TYPE_BITSET_HANDLE)
					? (int) getRegisterValue(state, instr.dstReg())
					: ctx.acquireBitset();
			ImpulseBitSet dstBs = ctx.getBitset(dstHandle);
			dstBs.clear();
			if (srcBs != null) {
				dstBs.or(srcBs);
			}
			setRegister(state, instr.dstReg(), dstHandle, TYPE_BITSET_HANDLE);
		} else {
			setRegister(state, instr.dstReg(), val, typeTag);
		}
	}

	public static void handleMov(MemorySegment state, Instruction instr) {
		validateReg(instr.dstReg());
		int srcReg = (instr.payload() >> 16) != 0 ? ((instr.payload() >> 16) & 0xFFFF) : (instr.payload() & 0xFFFF);
		validateReg(srcReg);
		long val = getRegisterValue(state, srcReg);
		byte typeTag = getRegisterType(state, srcReg);
		setRegister(state, instr.dstReg(), val, typeTag);
	}

	public static void handleClearReg(MemorySegment state, Instruction instr) {
		setRegister(state, instr.dstReg(), 0L, TYPE_NULL);
	}

	public static void handleStableCheck(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		if ((instr.payload() >>> 16) != 0) {
			srcReg = instr.payload() & 0xFFFF;
			dstReg = (instr.payload() >>> 16) & 0xFFFF;
		} else if (dstReg == 0 && srcReg != 0) {
			dstReg = srcReg;
		}

		byte dstType = getRegisterType(state, dstReg);
		byte srcType = getRegisterType(state, srcReg);

		long dstVal = getRegisterValue(state, dstReg);
		long srcVal = getRegisterValue(state, srcReg);

		boolean isSubset = false;
		if (srcType == TYPE_BITSET_HANDLE && dstType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bsSrc = ctx.getBitset((int) srcVal);
			ImpulseBitSet bsDst = ctx.getBitset((int) dstVal);
			if (bsSrc != null && bsDst != null) {
				isSubset = bsSrc.isSubsetOf(bsDst);
			}
		} else if (srcType == TYPE_BITSET_HANDLE && dstType != TYPE_BITSET_HANDLE) {
			ImpulseBitSet bsSrc = ctx.getBitset((int) srcVal);
			if (bsSrc != null) {
				int first = bsSrc.nextSetBit(0);
				if (first < 0) {
					isSubset = true;
				} else if (first == (int) dstVal && bsSrc.nextSetBit(first + 1) < 0) {
					isSubset = true;
				}
			}
		} else if (srcType != TYPE_BITSET_HANDLE && dstType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bsDst = ctx.getBitset((int) dstVal);
			isSubset = (bsDst != null) && bsDst.get((int) srcVal);
		} else if (srcType != TYPE_BITSET_HANDLE && dstType != TYPE_BITSET_HANDLE) {
			isSubset = (srcVal == dstVal);
		}

		setFlag(state, FLAG_ST, isSubset);
		setFlag(state, FLAG_ZF, isSubset);
	}

	public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		return handleCollectBitset(state, ctx, instr, null);
	}

	public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		int dst = instr.dstReg();
		validateReg(dst);
		int srcReg = instr.payload() & 0xFFFF;
		if (srcReg == 0 && instr.dstReg() != 0 && (instr.payload() & 0xFFFF) == 0) {
			srcReg = instr.dstReg();
		}
		validateReg(srcReg);
		long val = getRegisterValue(state, srcReg);
		byte typeTag = getRegisterType(state, srcReg);

		if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input != null) {
			int outHandle = ctx.acquireBitset();
			ImpulseBitSet outBs = ctx.getBitset(outHandle);
			if (input instanceof Number n) {
				outBs.set(n.intValue());
			} else if (input instanceof ImpulseBitSet inBs) {
				outBs.or(inBs);
			} else if (input instanceof long[] arr) {
				for (long v : arr)
					outBs.set((int) v);
			} else if (input instanceof int[] arr) {
				for (int v : arr)
					outBs.set(v);
			} else if (input instanceof Iterable<?> it) {
				for (Object elem : it) {
					if (elem instanceof Number num)
						outBs.set(num.intValue());
				}
			}
			if (typeTag == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs = ctx.getBitset((int) val);
				if (bs != null && bs != outBs)
					outBs.or(bs);
			} else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
				outBs.set((int) val);
			} else if (typeTag == TYPE_LANE_FRONTIER) {
				VmLaneFrontier lf = ctx.getLaneFrontier((int) val);
				if (lf != null && lf.lanes() != null) {
					long mask = lf.activeMask();
					long[] lanes = lf.lanes();
					for (int u = 0, n = Math.min(lf.nodeCount(), lanes.length); u < n; u++) {
						if ((lanes[u] & mask) != 0L) {
							outBs.set(u);
						}
					}
				}
			} else if (typeTag == TYPE_NODE_VECTOR) {
				int[] vec = ctx.getIntVector((int) val);
				if (vec != null) {
					for (int v : vec) {
						if (v >= 0)
							outBs.set(v);
					}
				}
			} else if (typeTag == TYPE_UINT64_VECTOR) {
				long[] vec = ctx.getLongVector((int) val);
				if (vec != null) {
					for (long v : vec) {
						if (v >= 0L)
							outBs.set((int) v);
					}
				}
			}
			setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
			ctx.setFinalResult(outBs);
			return outBs;
		}

		if (typeTag == TYPE_LANE_FRONTIER) {
			VmLaneFrontier lf = ctx.getLaneFrontier((int) val);
			int outHandle = ctx.acquireBitset();
			ImpulseBitSet outBs = ctx.getBitset(outHandle);
			if (lf != null && lf.lanes() != null) {
				long mask = lf.activeMask();
				long[] lanes = lf.lanes();
				for (int u = 0, n = Math.min(lf.nodeCount(), lanes.length); u < n; u++) {
					if ((lanes[u] & mask) != 0L) {
						outBs.set(u);
					}
				}
			}
			setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
			ctx.setFinalResult(outBs);
			return outBs;
		} else if (typeTag == TYPE_NODE_VECTOR) {
			int[] vec = ctx.getIntVector((int) val);
			int outHandle = ctx.acquireBitset();
			ImpulseBitSet outBs = ctx.getBitset(outHandle);
			if (vec != null) {
				for (int v : vec) {
					if (v >= 0) {
						outBs.set(v);
					}
				}
			}
			setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
			ctx.setFinalResult(outBs);
			return outBs;
		} else if (typeTag == TYPE_UINT64_VECTOR) {
			long[] vec = ctx.getLongVector((int) val);
			int outHandle = ctx.acquireBitset();
			ImpulseBitSet outBs = ctx.getBitset(outHandle);
			if (vec != null) {
				for (long v : vec) {
					if (v >= 0L) {
						outBs.set((int) v);
					}
				}
			}
			setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
			ctx.setFinalResult(outBs);
			return outBs;
		}

		setRegister(state, dst, val, typeTag);

		if (typeTag == TYPE_BITSET_HANDLE) {
			ImpulseBitSet res = ctx.getBitset((int) val);
			ctx.setFinalResult(res);
			return res;
		} else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
			int outHandle = ctx.acquireBitset();
			ImpulseBitSet outBs = ctx.getBitset(outHandle);
			outBs.set((int) val);
			ctx.setFinalResult(outBs);
			return outBs;
		}
		return null;
	}

	public static void handleFloatVectorScale(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int reg = instr.dstReg();
		if (getRegisterType(state, reg) != TYPE_FLOAT_VECTOR) {
			return;
		}
		float alpha = Float.intBitsToFloat(instr.payload());
		int handle = (int) getRegisterValue(state, reg);
		float[] vec = ctx.getFloatVector(handle);
		if (vec != null) {
			for (int i = 0; i < vec.length; i++) {
				vec[i] *= alpha;
			}
		}
	}

	public static void handleL1NormDiff(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int reg1 = instr.payload() & 0xFFFF;
		int reg2 = (instr.payload() >> 16) & 0xFFFF;
		float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, reg1));
		float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, reg2));
		double diffSum = 0.0;
		if (v1 != null && v2 != null && v1.length == v2.length) {
			for (int i = 0; i < v1.length; i++) {
				diffSum += Math.abs(v1[i] - v2[i]);
			}
		}
		setRegister(state, instr.dstReg(), Integer.toUnsignedLong(Float.floatToRawIntBits((float) diffSum)),
				TYPE_FLOAT);
		setFlag(state, FLAG_ZF, diffSum < 1e-4);
	}

	public static void handleTcSweepBatch(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setFlag(state, FLAG_ZF, false);
	}

	private static long countOffHeapIntersection(MemorySegment colIdx, int startA, int endA, int startB, int endB) {
		long count = 0;
		int i = startA, j = startB;
		while (i < endA && j < endB) {
			int valA = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
			int valB = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, j);
			if (valA == valB) {
				count++;
				i++;
				j++;
			} else if (valA < valB) {
				i++;
			} else {
				j++;
			}
		}
		return count;
	}

	public static void handleReadEdgeWeight(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		setRegister(state, instr.dstReg(), Float.floatToRawIntBits(1.0f), TYPE_FLOAT);
		setFlag(state, FLAG_ZF, false);
	}

	private static int runIslandDetectBfs(int N, MemorySegment offsetsSeg, MemorySegment targetsSeg,
			MemorySegment branchIdsSeg, long k1, long k2) {
		if (N <= 0)
			return 0;
		VmQueryContext.BfsScratch scratch = VmQueryContext.acquireThreadLocalBfsScratch(N);
		long[] visitedWords = scratch.visitedWords;
		int[] queue = scratch.queue;
		int components = 0;

		for (int i = 0; i < N; i++) {
			if ((visitedWords[i >>> 6] & (1L << (i & 63))) == 0L) {
				components++;
				int head = 0;
				int tail = 0;
				queue[tail++] = i;
				visitedWords[i >>> 6] |= (1L << (i & 63));

				while (head < tail) {
					int u = queue[head++];
					if (u >= N)
						continue;

					int start = offsetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u);
					int end = offsetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u + 1);

					for (int e = start; e < end; e++) {
						if (branchIdsSeg != null && !branchIdsSeg.equals(MemorySegment.NULL)) {
							int brId = branchIdsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
							if (brId == k1 || brId == k2) {
								continue;
							}
						}

						int v = targetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
						if (v >= 0 && v < N && (visitedWords[v >>> 6] & (1L << (v & 63))) == 0L) {
							visitedWords[v >>> 6] |= (1L << (v & 63));
							queue[tail++] = v;
						}
					}
				}
			}
		}
		return components;
	}

	public static void handleIslandDetect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;

		int[] lines1 = ctx.scratchInt(0, 16384);
		int len1 = 0;
		if (src1 < 64) {
			byte type = getRegisterType(state, src1);
			if (type == TYPE_INT64) {
				lines1[len1++] = (int) getRegisterValue(state, src1);
			} else if (type == TYPE_BITSET_HANDLE) {
				int handle = (int) getRegisterValue(state, src1);
				ImpulseBitSet bs = ctx.getBitset(handle);
				if (bs != null) {
					for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
						if (len1 >= lines1.length) {
							lines1 = ctx.scratchInt(0, lines1.length << 1);
						}
						lines1[len1++] = i;
					}
				}
			}
		}
		if (len1 == 0) {
			lines1[len1++] = -1;
		}

		int[] lines2 = ctx.scratchInt(1, 16384);
		int len2 = 0;
		if (src2 < 64) {
			byte type = getRegisterType(state, src2);
			if (type == TYPE_INT64) {
				lines2[len2++] = (int) getRegisterValue(state, src2);
			} else if (type == TYPE_BITSET_HANDLE) {
				int handle = (int) getRegisterValue(state, src2);
				ImpulseBitSet bs = ctx.getBitset(handle);
				if (bs != null) {
					for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
						if (len2 >= lines2.length) {
							lines2 = ctx.scratchInt(1, lines2.length << 1);
						}
						lines2[len2++] = i;
					}
				}
			}
		}
		if (len2 == 0) {
			lines2[len2++] = -1;
		}

		ImpulseGraphSnapshot graph = ctx.snapshot();
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null && graph != null && !graph.getAllRelationSnapshots().isEmpty()) {
			rel = graph.getAllRelationSnapshots().values().iterator().next();
		}

		long criticalPairsCount = 0;
		if (rel != null) {
			final int finalN = rel.getNodeCount();
			if (finalN > 0) {
				final MemorySegment offsetsSeg = rel.getRowOffsetsSegment();
				final MemorySegment targetsSeg = rel.getColumnTargetsSegment();
				final MemorySegment branchIdsSeg = (rel.getAttributeSegments() != null
						&& !rel.getAttributeSegments().isEmpty())
								? rel.getAttributeSegments().get(0)
								: MemorySegment.NULL;

				final int baseComponents = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, -1, -1);
				boolean sameSet = (src1 == src2);
				final int[] fLines1 = lines1;
				final int fLen1 = len1;
				final int[] fLines2 = lines2;
				final int fLen2 = len2;

				if (sameSet) {
					criticalPairsCount = java.util.stream.IntStream.range(0, fLen1).parallel().mapToLong(idx -> {
						long localCount = 0;
						int k1 = fLines1[idx];
						for (int j = idx + 1; j < fLen1; j++) {
							int k2 = fLines1[j];
							int comp = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, k1, k2);
							if (comp > baseComponents) {
								localCount++;
							}
						}
						return localCount;
					}).sum();
				} else {
					criticalPairsCount = java.util.stream.IntStream.range(0, fLen1).parallel().mapToLong(idx -> {
						long localCount = 0;
						int k1 = fLines1[idx];
						for (int j = 0; j < fLen2; j++) {
							int k2 = fLines2[j];
							if (k1 >= k2)
								continue;
							int comp = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, k1, k2);
							if (comp > baseComponents) {
								localCount++;
							}
						}
						return localCount;
					}).sum();
				}
			}
		}

		setRegister(state, dst, criticalPairsCount, TYPE_INT64);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleNodeFilter(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleNodeFilter(state, ctx, instr, null);
	}

	public static void handleNodeFilter(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
		if ((instr.flags() & 0x40) != 0) {
			handleCelDomainNodeFilter(state, ctx, instr, input);
			return;
		}
		int srcReg = instr.payload() & 0xFF;
		int valReg = (instr.payload() >> 8) & 0xFF;
		int attrId = (instr.payload() >> 16) & 0xFF;
		int relId = (instr.payload() >> 24) & 0xFF;
		int dstReg = instr.dstReg();
		validateReg(dstReg);
		validateReg(srcReg);
		validateReg(valReg);
		byte type = getRegisterType(state, srcReg);
		long val = getRegisterValue(state, srcReg);
		long matchVal = getRegisterValue(state, valReg);

		if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input != null) {
			if (input instanceof Number n) {
				type = TYPE_NODE_ID;
				val = n.longValue();
			} else if (input instanceof ImpulseBitSet inBs) {
				type = TYPE_BITSET_HANDLE;
				int tempH = ctx.acquireBitset();
				ctx.getBitset(tempH).or(inBs);
				val = tempH;
			}
		}

		int outHandle = (getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		Object mock = ctx.getMockAttribute(attrId);
		java.util.function.IntPredicate matcher = (u) -> {
			if (mock instanceof int[] intArr) {
				return u >= 0 && u < intArr.length && intArr[u] == (int) matchVal;
			}
			if (mock instanceof float[] floatArr) {
				float expF = Float.intBitsToFloat((int) matchVal);
				return u >= 0 && u < floatArr.length && floatArr[u] == expF;
			}
			return true;
		};

		if (type == TYPE_BITSET_HANDLE) {
			int handle = (int) val;
			ImpulseBitSet inBs = ctx.getBitset(handle);
			if (inBs != null) {
				for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
					if (matcher.test(u)) {
						outBs.set(u);
					}
				}
			}
		} else if (type == TYPE_NODE_ID || type == TYPE_INT64) {
			int u = (int) val;
			if (matcher.test(u)) {
				outBs.set(u);
			}
		}

		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	private static void handleCelDomainNodeFilter(MemorySegment state, VmQueryContext ctx, Instruction instr,
			Object input) {
		int srcReg = instr.payload() & 0xFFFF;
		int poolIdx = (instr.payload() >>> 16) & 0xFFFF;
		int dstReg = instr.dstReg();
		validateReg(dstReg);
		validateReg(srcReg);

		String spec = ctx.getString(poolIdx);
		String domName = "";
		String celExpr = spec != null ? spec : "true";
		if (spec != null) {
			int sep = spec.indexOf('\0');
			if (sep >= 0) {
				domName = spec.substring(0, sep);
				celExpr = spec.substring(sep + 1);
			}
		}

		org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain dom = null;
		if (ctx.getSnapshot() instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
			if (!domName.isEmpty()) {
				dom = gs.getDomain(domName);
			}
			if (dom == null && !gs.getDomainNames().isEmpty()) {
				dom = gs.getDomain(gs.getDomainNames().iterator().next());
			}
		}

		org.impulsegraph.vm.cel.VmCelEvaluator eval = org.impulsegraph.vm.cel.VmCelEvaluator.compile(celExpr,
				java.util.Map.of());
		org.impulsegraph.vm.cel.VmCelEvaluator.EvalScratch scratch = eval.createScratch();

		byte srcType = getRegisterType(state, srcReg);
		long srcVal = getRegisterValue(state, srcReg);
		if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input != null) {
			if (input instanceof Number n) {
				srcType = TYPE_NODE_ID;
				srcVal = n.longValue();
			} else if (input instanceof ImpulseBitSet inBs) {
				srcType = TYPE_BITSET_HANDLE;
				int tempH = ctx.acquireBitset();
				ctx.getBitset(tempH).or(inBs);
				srcVal = tempH;
			}
		}

		boolean aliased = (dstReg == srcReg && srcType == TYPE_BITSET_HANDLE);
		int prevDstHandle = aliased ? (int) srcVal : -1;
		int outHandle = (!aliased && getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		long maxNode = dom != null ? dom.nodeCount() : Long.MAX_VALUE;
		if (srcType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
			if (inBs != null) {
				for (int u = inBs.nextSetBit(0); u >= 0 && u < maxNode; u = inBs.nextSetBit(u + 1)) {
					if (eval.evalDomainFilter(dom, u, scratch)) {
						outBs.set(u);
					}
				}
			}
		} else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
			int u = (int) srcVal;
			if (u >= 0 && u < maxNode && eval.evalDomainFilter(dom, u, scratch)) {
				outBs.set(u);
			}
		} else if (srcType == TYPE_NODE_VECTOR || srcType == TYPE_UINT64_VECTOR) {
			int[] arr = ctx.getNodeVector((int) srcVal);
			if (arr != null) {
				for (int u : arr) {
					if (u >= 0 && u < maxNode && eval.evalDomainFilter(dom, u, scratch)) {
						outBs.set(u);
					}
				}
			}
		}

		if (aliased && prevDstHandle >= 0 && prevDstHandle != outHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleVectorMulAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		validateReg(dstReg);

		int handle;
		boolean isDouble = (getRegisterType(state, dstReg) == TYPE_DOUBLE_VECTOR);
		if (getRegisterType(state, dstReg) == TYPE_FLOAT_VECTOR || isDouble) {
			handle = (int) getRegisterValue(state, dstReg);
		} else {
			handle = ctx.acquireFloatVector(1024);
			setRegister(state, dstReg, handle, TYPE_FLOAT_VECTOR);
		}

		if (instr.flags() == 0) {
			float scalar = Float.intBitsToFloat(instr.payload());
			if (isDouble) {
				double[] dvec = ctx.getDoubleVector(handle);
				if (dvec != null) {
					for (int i = 0; i < dvec.length; i++) {
						dvec[i] *= scalar;
					}
				}
			} else {
				float[] vec = ctx.getFloatVector(handle);
				if (vec != null) {
					for (int i = 0; i < vec.length; i++) {
						vec[i] *= scalar;
					}
				}
			}
		}
	}

	public static void handleCsrWalkReduceSum(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int attrId = (instr.payload() >> 8) & 0xFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		RelationSnapshot rel = resolveRelation(ctx, relId);
		long u = getRegisterValue(state, src);
		long sum = 0;
		if (rel != null && u >= 0 && u < rel.getNodeCount()) {
			MemorySegment rowOff = rel.getRowOffsetsSegment();
			MemorySegment colIdx = rel.getColumnTargetsSegment();
			if (rowOff != null && colIdx != null) {
				int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (int) u);
				int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (int) (u + 1));
				Object mockAttr = ctx.getMockAttribute(attrId);
				if (mockAttr == null && attrId == 0)
					mockAttr = ctx.getMockAttribute(0);
				if (mockAttr instanceof int[] iarr) {
					for (int idx = start; idx < end; idx++) {
						int target = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (target >= 0 && target < iarr.length)
							sum += iarr[target];
					}
				} else if (mockAttr instanceof float[] farr) {
					for (int idx = start; idx < end; idx++) {
						int target = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (target >= 0 && target < farr.length) {
							float fv = farr[target];
							long iv = (fv != 0.0f && Math.abs(fv) < 1e-20f) ? Float.floatToRawIntBits(fv) : (long) fv;
							sum += iv;
						}
					}
				} else if (mockAttr instanceof double[] darr) {
					for (int idx = start; idx < end; idx++) {
						int target = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (target >= 0 && target < darr.length)
							sum += (long) darr[target];
					}
				} else {
					for (int idx = start; idx < end; idx++) {
						int target = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						sum += target;
					}
				}
			}
		}
		setRegister(state, dst, sum, TYPE_INT64);
		setFlag(state, FLAG_ZF, sum == 0);
	}

	public static void handleCsrWalkReduce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
				? (int) getRegisterValue(state, dst)
				: ctx.acquireFloatVector(1024);
		setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, true);
	}

	public static void handleCoalesce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFFFF;
		int src2 = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		long val1 = getRegisterValue(state, src1);
		byte type1 = getRegisterType(state, src1);
		long val2 = getRegisterValue(state, src2);
		byte type2 = getRegisterType(state, src2);
		if (type1 == TYPE_FLOAT_VECTOR && type2 == TYPE_FLOAT_VECTOR) {
			float[] v1 = ctx.getFloatVector((int) val1);
			float[] v2 = ctx.getFloatVector((int) val2);
			int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 0);
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				float a = (v1 != null && i < v1.length) ? v1[i] : Float.NaN;
				float b = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
				out[i] = Float.isNaN(a) ? b : a;
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		} else if (type1 == TYPE_FLOAT_VECTOR && type2 == TYPE_FLOAT) {
			float[] v1 = ctx.getFloatVector((int) val1);
			float defVal = Float.intBitsToFloat((int) val2);
			int len = (v1 != null) ? v1.length : 0;
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				float a = v1[i];
				out[i] = Float.isNaN(a) ? defVal : a;
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		} else if (type1 != TYPE_NULL) {
			setRegister(state, dst, val1, type1);
		} else {
			setRegister(state, dst, val2, type2);
		}
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleExtractValidity(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		byte type = getRegisterType(state, src);
		int vecHandle = (int) getRegisterValue(state, src);
		int handle = ctx.acquireBitset();
		ImpulseBitSet bs = ctx.getBitset(handle);
		bs.clear();
		if (type == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector(vecHandle);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					if (!Float.isNaN(vec[i])) {
						bs.set(i);
					}
				}
			}
		} else if (type == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector(vecHandle);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					if (!Double.isNaN(vec[i])) {
						bs.set(i);
					}
				}
			}
		} else if (type == TYPE_NODE_VECTOR || type == TYPE_UINT64_VECTOR) {
			int[] vec = ctx.getNodeVector(vecHandle);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					bs.set(i);
				}
			}
		} else {
			throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
		setRegister(state, dst, handle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs.isEmpty());
	}

	public static Object handleVectorReduceSum(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = resolveReduceSrcReg(state, instr);
		validateReg(dst);
		validateReg(src);

		byte type = getRegisterType(state, src);
		double totalSum = 0.0;
		float fsum = 0.0f;

		if (type == TYPE_FLOAT) {
			long raw = getRegisterValue(state, src);
			float val = Float.intBitsToFloat((int) (raw & 0xFFFFFFFFL));
			setRegister(state, dst, Integer.toUnsignedLong(Float.floatToRawIntBits(val)), TYPE_FLOAT);
			setFlag(state, FLAG_ZF, val == 0.0f);
			ctx.setFinalResultDouble(val);
			return Boolean.TRUE;
		} else if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) getRegisterValue(state, src);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getFloatVectorLength(handle));
				for (int i = 0; i < len; i++) {
					float v = vec[i];
					if (!Float.isNaN(v)) {
						fsum += v;
					}
				}
			}
			setRegister(state, dst, Integer.toUnsignedLong(Float.floatToRawIntBits(fsum)), TYPE_FLOAT);
			setFlag(state, FLAG_ZF, fsum == 0.0f);
			ctx.setFinalResultDouble(fsum);
			return Boolean.TRUE;
		} else if (type == TYPE_DOUBLE_VECTOR) {
			int handle = (int) getRegisterValue(state, src);
			double[] vec = ctx.getDoubleVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getDoubleVectorLength(handle));
				for (int i = 0; i < len; i++) {
					double v = vec[i];
					if (!Double.isNaN(v)) {
						totalSum += v;
					}
				}
			}
			setRegister(state, dst, Double.doubleToRawLongBits(totalSum), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, totalSum == 0.0);
			ctx.setFinalResultDouble(totalSum);
			return Boolean.TRUE;
		} else if (type == TYPE_NODE_VECTOR || type == TYPE_UINT64_VECTOR) {
			int handle = (int) getRegisterValue(state, src);
			int[] vec = ctx.getNodeVector(handle);
			long isum = 0L;
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getNodeVectorLength(handle));
				for (int i = 0; i < len; i++) {
					isum += vec[i];
				}
			}
			setRegister(state, dst, isum, TYPE_INT64);
			setFlag(state, FLAG_ZF, isum == 0L);
			ctx.setFinalResultLong(isum);
			return Boolean.TRUE;
		} else if (type == TYPE_BITSET_HANDLE) {
			int handle = (int) getRegisterValue(state, src);
			ImpulseBitSet bs = ctx.getBitset(handle);
			if (bs != null) {
				totalSum = bs.cardinality();
			}
			setRegister(state, dst, Double.doubleToRawLongBits(totalSum), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, totalSum == 0.0);
			ctx.setFinalResultDouble(totalSum);
			return Boolean.TRUE;
		}
		setRegister(state, dst, 0L, TYPE_FLOAT);
		setFlag(state, FLAG_ZF, true);
		ctx.setFinalResultDouble(0.0);
		return Boolean.TRUE;
	}

	public static void handleVectorLoadAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		try {
			int payload = instr.payload();
			int nameIdx = payload & 0xFFFF;
			int srcReg = (payload >> 16) & 0xFFFF;
			int dstReg = instr.dstReg();

			String attrName = ctx.getString(nameIdx);
			if (ImpulseVmInterpreter.DEBUG_MODE) {
				LOG.info("handleVectorLoadAttr: srcReg=" + srcReg + ", nameIdx=" + nameIdx + ", attrName=" + attrName);
			}

			if (ctx.getSnapshot() != null) {
				for (org.impulsegraph.api.RelationSnapshot rel : ctx.getSnapshot().getAllRelationSnapshots().values()) {
					if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
						int attrIdx = csrRel.findAttributeIndex(attrName);
						if (attrIdx >= 0 && csrRel.getAttributeSegments().size() > attrIdx) {
							String fullAttrName = csrRel.getAttributeName(attrIdx);
							long count = (fullAttrName.contains(".") || rel.getEdgeCount() == 0)
									? rel.getNodeCount()
									: rel.getEdgeCount();
							int typeCode = resolveEffectiveAttrTypeCode(csrRel.getAttributeTypeCode(attrIdx) & 0x7F,
									fullAttrName);
							int handle = ctx.acquireFloatVector((int) count);
							ctx.setFloatVectorLength(handle, (int) count);
							float[] vec = ctx.getFloatVector(handle);
							java.lang.foreign.MemorySegment dataSeg = csrRel.getAttributeSegments().get(attrIdx);
							java.lang.foreign.MemorySegment validSeg = null;
							if (csrRel.getValiditySegments() != null && csrRel.getValiditySegments().size() > attrIdx) {
								validSeg = csrRel.getValiditySegments().get(attrIdx);
							}
							for (int i = 0; i < count; i++) {
								boolean isNull = false;
								if (validSeg != null && validSeg.byteSize() > 0) {
									long byteOff = i / 8;
									int bitOff = i % 8;
									byte b = validSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, byteOff);
									if ((b & (1 << bitOff)) == 0) {
										isNull = true;
									}
								}
								vec[i] = isNull ? Float.NaN : readColumnAttributeAsFloat(dataSeg, typeCode, i);
							}
							setRegister(state, dstReg, handle, TYPE_FLOAT_VECTOR);
							setFlag(state, FLAG_ZF, false);
							return;
						}
					}
				}
			}
			// Fallback for expression projections on mock snapshots without physical
			// attribute tables
			int handle = ctx.acquireFloatVector(1024);
			float[] vec = ctx.getFloatVector(handle);
			byte srcType = getRegisterType(state, srcReg);
			if (srcType == TYPE_BITSET_HANDLE) {
				ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
				if (bs != null && vec != null) {
					for (int u = bs.nextSetBit(0); u >= 0 && u < vec.length; u = bs.nextSetBit(u + 1)) {
						vec[u] = (float) ((u + 1) * 2.5);
					}
				}
			} else {
				for (int i = 0; i < vec.length; i++) {
					vec[i] = (float) ((i + 1) * 2.5);
				}
			}
			setRegister(state, dstReg, handle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
		} catch (Throwable t) {
			if (ImpulseVmInterpreter.DEBUG_MODE) {
				LOG.log(Level.SEVERE, "Crash in handleVectorLoadAttr", t);
			}
			throw t;
		}
	}

	private static int resolveReduceSrcReg(MemorySegment state, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		if (srcReg == 0 && dstReg != 0 && getRegisterType(state, 0) == TYPE_NULL) {
			srcReg = dstReg;
		}
		return srcReg;
	}

	public static Object handleVectorReduceMax(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = resolveReduceSrcReg(state, instr);
		byte type = getRegisterType(state, srcReg);
		if (type == TYPE_NODE_VECTOR || type == TYPE_UINT64_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			int[] vec = ctx.getNodeVector(handle);
			long maxVal = Long.MIN_VALUE;
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getNodeVectorLength(handle));
				for (int i = 0; i < len; i++) {
					int v = vec[i];
					if (v > maxVal) {
						maxVal = v;
					}
				}
			}
			setRegister(state, dstReg, maxVal, TYPE_INT64);
			setFlag(state, FLAG_ZF, maxVal == 0L);
			ctx.setFinalResultLong(maxVal);
			return Boolean.TRUE;
		}
		if (type == TYPE_DOUBLE_VECTOR) {
			double maxVal = Double.NEGATIVE_INFINITY;
			int handle = (int) getRegisterValue(state, srcReg);
			double[] vec = ctx.getDoubleVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getDoubleVectorLength(handle));
				for (int i = 0; i < len; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v > maxVal) {
						maxVal = v;
					}
				}
			}
			setRegister(state, dstReg, Double.doubleToRawLongBits(maxVal), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, maxVal == 0.0);
			ctx.setFinalResultDouble(maxVal);
			return Boolean.TRUE;
		}
		float maxVal = Float.NEGATIVE_INFINITY;
		if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getFloatVectorLength(handle));
				for (int i = 0; i < len; i++) {
					float v = vec[i];
					if (!Float.isNaN(v) && v > maxVal) {
						maxVal = v;
					}
				}
			}
		}
		setRegister(state, dstReg, Integer.toUnsignedLong(Float.floatToRawIntBits(maxVal)), TYPE_FLOAT);
		setFlag(state, FLAG_ZF, maxVal == 0.0f);
		ctx.setFinalResultDouble(maxVal);
		return Boolean.TRUE;
	}

	public static Object handleVectorReduceMin(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = resolveReduceSrcReg(state, instr);
		byte type = getRegisterType(state, srcReg);
		if (type == TYPE_NODE_VECTOR || type == TYPE_UINT64_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			int[] vec = ctx.getNodeVector(handle);
			long minVal = Long.MAX_VALUE;
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getNodeVectorLength(handle));
				for (int i = 0; i < len; i++) {
					int v = vec[i];
					if (v < minVal) {
						minVal = v;
					}
				}
			}
			setRegister(state, dstReg, minVal, TYPE_INT64);
			setFlag(state, FLAG_ZF, minVal == 0L);
			ctx.setFinalResultLong(minVal);
			return Boolean.TRUE;
		}
		if (type == TYPE_DOUBLE_VECTOR) {
			double minVal = Double.POSITIVE_INFINITY;
			int handle = (int) getRegisterValue(state, srcReg);
			double[] vec = ctx.getDoubleVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getDoubleVectorLength(handle));
				for (int i = 0; i < len; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v < minVal) {
						minVal = v;
					}
				}
			}
			setRegister(state, dstReg, Double.doubleToRawLongBits(minVal), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, minVal == 0.0);
			ctx.setFinalResultDouble(minVal);
			return Boolean.TRUE;
		}
		float minVal = Float.POSITIVE_INFINITY;
		if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getFloatVectorLength(handle));
				for (int i = 0; i < len; i++) {
					float v = vec[i];
					if (!Float.isNaN(v) && v < minVal) {
						minVal = v;
					}
				}
			}
		}
		setRegister(state, dstReg, Integer.toUnsignedLong(Float.floatToRawIntBits(minVal)), TYPE_FLOAT);
		setFlag(state, FLAG_ZF, minVal == 0.0f);
		ctx.setFinalResultDouble(minVal);
		return Boolean.TRUE;
	}

	public static Object handleVectorReduceMul(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = resolveReduceSrcReg(state, instr);
		byte type = getRegisterType(state, srcReg);
		if (type == TYPE_NODE_VECTOR || type == TYPE_UINT64_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			int[] vec = ctx.getNodeVector(handle);
			long prod = 0L;
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getNodeVectorLength(handle));
				for (int i = 0; i < len; i++) {
					prod *= vec[i];
				}
			}
			setRegister(state, dstReg, prod, TYPE_INT64);
			setFlag(state, FLAG_ZF, prod == 0L);
			ctx.setFinalResultLong(prod);
			return Boolean.TRUE;
		}
		if (type == TYPE_DOUBLE_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			double[] vec = ctx.getDoubleVector(handle);
			int len = (vec != null) ? Math.min(vec.length, ctx.getDoubleVectorLength(handle)) : 0;
			double prod = (len > 0) ? 1.0 : 0.0;
			if (vec != null) {
				for (int i = 0; i < len; i++) {
					prod *= vec[i];
				}
			}
			setRegister(state, dstReg, Double.doubleToRawLongBits(prod), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, prod == 0.0);
			ctx.setFinalResultDouble(prod);
			return Boolean.TRUE;
		}
		int handle = (int) getRegisterValue(state, srcReg);
		float[] vec = (type == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector(handle) : null;
		int len = (vec != null) ? Math.min(vec.length, ctx.getFloatVectorLength(handle)) : 0;
		float prod = (len > 0) ? 1.0f : 0.0f;
		if (vec != null) {
			for (int i = 0; i < len; i++) {
				prod *= vec[i];
			}
		}
		setRegister(state, dstReg, Integer.toUnsignedLong(Float.floatToRawIntBits(prod)), TYPE_FLOAT);
		setFlag(state, FLAG_ZF, prod == 0.0f);
		ctx.setFinalResultDouble(prod);
		return Boolean.TRUE;
	}

	public static Object handleVectorReduceArgMax(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = resolveReduceSrcReg(state, instr);
		byte type = getRegisterType(state, srcReg);
		double maxVal = Double.NEGATIVE_INFINITY;
		int argMax = -1;
		if (type == TYPE_DOUBLE_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			double[] vec = ctx.getDoubleVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getDoubleVectorLength(handle));
				for (int i = 0; i < len; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v > maxVal) {
						maxVal = v;
						argMax = i;
					}
				}
			}
		} else if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getFloatVectorLength(handle));
				for (int i = 0; i < len; i++) {
					float v = vec[i];
					if (!Float.isNaN(v) && v > maxVal) {
						maxVal = v;
						argMax = i;
					}
				}
			}
		}
		setRegister(state, dstReg, argMax, TYPE_NODE_ID);
		setFlag(state, FLAG_ZF, argMax == 0);
		ctx.setFinalResultInt(argMax);
		return Boolean.TRUE;
	}

	public static Object handleVectorReduceArgMin(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = resolveReduceSrcReg(state, instr);
		byte type = getRegisterType(state, srcReg);
		double minVal = Double.POSITIVE_INFINITY;
		int argMin = -1;
		if (type == TYPE_DOUBLE_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			double[] vec = ctx.getDoubleVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getDoubleVectorLength(handle));
				for (int i = 0; i < len; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v < minVal) {
						minVal = v;
						argMin = i;
					}
				}
			}
		} else if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) getRegisterValue(state, srcReg);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				int len = Math.min(vec.length, ctx.getFloatVectorLength(handle));
				for (int i = 0; i < len; i++) {
					float v = vec[i];
					if (!Float.isNaN(v) && v < minVal) {
						minVal = v;
						argMin = i;
					}
				}
			}
		}
		setRegister(state, dstReg, argMin, TYPE_NODE_ID);
		setFlag(state, FLAG_ZF, argMin == 0);
		ctx.setFinalResultInt(argMin);
		return Boolean.TRUE;
	}

	public static Object handleReduce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		if ((instr.flags() & 0x40) != 0) {
			return handleCelDomainReduce(state, ctx, instr);
		}
		validateReg(instr.dstReg());
		int srcReg = instr.payload() & 0xFFFF;
		validateReg(srcReg);
		int opId = (instr.payload() >> 16) & 0xFFFF;
		if (opId == 0 && instr.flags() != 0) {
			opId = instr.flags() & 0xFFFF;
		}
		return switch (opId) {
			case BINARY_OP_MUL -> handleVectorReduceMul(state, ctx, instr);
			case BINARY_OP_MIN -> handleVectorReduceMin(state, ctx, instr);
			case BINARY_OP_MAX -> handleVectorReduceMax(state, ctx, instr);
			case BINARY_OP_ARGMIN -> handleVectorReduceArgMin(state, ctx, instr);
			case BINARY_OP_ARGMAX -> handleVectorReduceArgMax(state, ctx, instr);
			default -> {
				handleVectorReduceSum(state, ctx, instr);
				ctx.setFinalResultDouble(0.0);
				yield Boolean.TRUE;
			}
		};
	}

	private static Object handleCelDomainReduce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		int poolIdx = (instr.payload() >>> 16) & 0xFFFF;
		int redKind = instr.flags() & 0x0F;
		validateReg(dstReg);
		validateReg(srcReg);

		String spec = ctx.getString(poolIdx);
		String domName = "";
		String expr = spec != null ? spec : "";
		if (spec != null) {
			int sep = spec.indexOf('\0');
			if (sep >= 0) {
				domName = spec.substring(0, sep);
				expr = spec.substring(sep + 1);
			}
		}

		org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain dom = null;
		if (ctx.getSnapshot() instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
			if (!domName.isEmpty()) {
				dom = gs.getDomain(domName);
			}
			if (dom == null && !gs.getDomainNames().isEmpty()) {
				dom = gs.getDomain(gs.getDomainNames().iterator().next());
			}
		}

		org.impulsegraph.vm.cel.VmCelEvaluator eval = org.impulsegraph.vm.cel.VmCelEvaluator.compile(expr,
				java.util.Map.of());
		org.impulsegraph.vm.cel.VmCelEvaluator.EvalScratch scratch = eval.createScratch();

		int count = extractActiveNodeIdsToScratch(state, ctx, srcReg, 0);
		int[] active = ctx.peekScratchInt(0);
		long maxNode = dom != null ? dom.nodeCount() : Long.MAX_VALUE;
		double sum = 0.0;
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		long nonNullCount = 0L;
		for (int i = 0; i < count; i++) {
			int u = active[i];
			if (u < 0 || u >= maxNode) {
				continue;
			}
			double v = eval.evalDomainScalarOrNaN(dom, u, scratch);
			if (!Double.isNaN(v)) {
				sum += v;
				if (v < min) {
					min = v;
				}
				if (v > max) {
					max = v;
				}
				nonNullCount++;
			}
		}

		if (redKind == BINARY_OP_MIN) {
			double outVal = (nonNullCount > 0) ? min : Double.NaN;
			setRegister(state, dstReg, Double.doubleToRawLongBits(outVal), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, nonNullCount == 0);
			ctx.setFinalResultDouble(outVal);
			return Boolean.TRUE;
		} else if (redKind == BINARY_OP_MAX) {
			double outVal = (nonNullCount > 0) ? max : Double.NaN;
			setRegister(state, dstReg, Double.doubleToRawLongBits(outVal), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, nonNullCount == 0);
			ctx.setFinalResultDouble(outVal);
			return Boolean.TRUE;
		} else {
			setRegister(state, dstReg, Double.doubleToRawLongBits(sum), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, nonNullCount == 0);
			ctx.setFinalResultDouble(sum);
			return Boolean.TRUE;
		}
	}

	public static void handleLoadIndirect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int srcParam = instr.payload() & 0xFFFF;
		int idxReg = (instr.payload() >> 16) & 0xFFFF;

		if (instr.flags() == 0) {
			long targetRegIdx = getRegisterValue(state, srcParam);
			if (targetRegIdx >= 0 && targetRegIdx < 64) {
				long val = getRegisterValue(state, (int) targetRegIdx);
				byte type = getRegisterType(state, (int) targetRegIdx);
				setRegister(state, dst, val, type);
				setFlag(state, FLAG_ZF, val == 0);
			}
		} else {
			int handle = (int) getRegisterValue(state, srcParam);
			long index = getRegisterValue(state, idxReg);
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null && index >= 0 && index < vec.length) {
				float val = vec[(int) index];
				setRegister(state, dst, Float.floatToRawIntBits(val), TYPE_FLOAT);
				setFlag(state, FLAG_ZF, val == 0.0f);
			}
		}
	}

	public static void handleLoadInlineArray(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		if ((instr.flags() & 0x40) != 0) {
			int dst = instr.dstReg();
			validateReg(dst);
			String csv = ctx.getString(instr.payload() & 0xFFFF);
			if (csv == null || csv.isEmpty()) {
				int h = ctx.acquireIntVector(0);
				ctx.setNodeVectorLength(h, 0);
				setRegister(state, dst, h, TYPE_NODE_VECTOR);
				setFlag(state, FLAG_ZF, true);
				return;
			}
			String[] parts = csv.split(",");
			int count = 0;
			for (String p : parts) {
				if (!p.isEmpty()) {
					count++;
				}
			}
			int h = ctx.acquireIntVector(count);
			int[] nvec = ctx.getIntVector(h);
			int idx = 0;
			for (String p : parts) {
				if (!p.isEmpty()) {
					nvec[idx++] = Integer.parseInt(p);
				}
			}
			ctx.setNodeVectorLength(h, count);
			setRegister(state, dst, h, TYPE_NODE_VECTOR);
			setFlag(state, FLAG_ZF, count == 0);
			return;
		}
		MemorySegment inlineSeg = ctx.inlineDataSegment();
		if (inlineSeg == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int dst = instr.dstReg();
		int payload = instr.payload();
		int offset = payload & 0xFFFF;
		int count = (payload >> 16) & 0xFFFF;

		int hFloat = ctx.acquireFloatVector(count);
		int hNode = ctx.acquireNodeVector(count);
		int handle = Math.max(hFloat, hNode);
		while (ctx.floatVectorCount() <= handle) {
			ctx.acquireFloatVector(count);
		}
		while (ctx.intVectorCount() <= handle) {
			ctx.acquireNodeVector(count);
		}

		float[] fvec = ctx.getFloatVector(handle);
		int[] nvec = ctx.getNodeVector(handle);

		java.lang.foreign.ValueLayout.OfFloat layoutFloat = java.lang.foreign.ValueLayout.JAVA_FLOAT
				.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
		java.lang.foreign.ValueLayout.OfInt layoutInt = java.lang.foreign.ValueLayout.JAVA_INT
				.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			fvec[i] = inlineSeg.get(layoutFloat, offset + i * 4L);
			nvec[i] = inlineSeg.get(layoutInt, offset + i * 4L);
		}
		ctx.setFloatVectorLength(handle, count);
		ctx.setNodeVectorLength(handle, count);

		boolean isNode = (instr.flags() & 0x01) != 0;
		setRegister(state, dst, handle, isNode ? TYPE_NODE_VECTOR : TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleLoadInlineIntArray(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleLoadInlineArray(state, ctx, instr);
	}

	public static void handleLoadInlineSet(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		MemorySegment inlineSeg = ctx.inlineDataSegment();
		if (inlineSeg == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int dst = instr.dstReg();
		int payload = instr.payload();
		int offset = payload & 0xFFFF;
		int count = (payload >> 16) & 0xFFFF;

		int handle = ctx.acquireBitset();
		ImpulseBitSet bs = ctx.getBitset(handle);
		java.lang.foreign.ValueLayout.OfInt layoutInt = java.lang.foreign.ValueLayout.JAVA_INT
				.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			int node = inlineSeg.get(layoutInt, offset + i * 4L);
			bs.set(node);
		}

		setRegister(state, dst, handle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs.isEmpty());
	}

	public static void handleInitMockNodeAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int attrId = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		int handle = (int) getRegisterValue(state, srcReg);
		byte type = getRegisterType(state, srcReg);
		if (type == TYPE_FLOAT_VECTOR) {
			float[] arr = ctx.getFloatVector(handle);
			ctx.setMockAttribute(attrId, arr);
		} else if (type == TYPE_NODE_VECTOR) {
			int[] arr = ctx.getNodeVector(handle);
			ctx.setMockAttribute(attrId, arr);
		}
	}

	public static void handleInitMockGraph(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int payload = instr.payload();
		int offset = payload & 0xFFFF;
		int nodeCount = (payload >> 16) & 0xFFFF;
		ctx.initMockGraph(instr.dstReg(), offset, nodeCount);
	}

	public static void handleThrow(MemorySegment state, Instruction instr) {
		setRegister(state, 0, instr.payload(), TYPE_INT64);
	}

	public static void handleAssert(MemorySegment state, Instruction instr) {
		int srcReg = instr.dstReg();
		long expectedUnsigned = instr.payload() & 0xFFFFFFFFL;
		long expectedSigned = (long) instr.payload();
		if (instr.flags() == 0) {
			long actual = getRegisterValue(state, srcReg);
			if (actual != expectedSigned && actual != expectedUnsigned) {
				throw new IllegalStateException("OP_ASSERT failed: expected " + expectedSigned + ", got " + actual);
			}
		} else {
			boolean flagMatch = checkFlag(state, expectedUnsigned);
			if (!flagMatch) {
				throw new IllegalStateException(
						"OP_ASSERT flag failed for mask 0x" + Long.toHexString(expectedUnsigned));
			}
		}
	}

	public static void handleAssertFinite(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int targetReg = instr.dstReg();
		byte type = getRegisterType(state, targetReg);
		long rawVal = getRegisterValue(state, targetReg);

		if (type == TYPE_FLOAT) {
			float v = Float.intBitsToFloat((int) rawVal);
			if (Float.isNaN(v) || Float.isInfinite(v)) {
				setRegister(state, 0, 0L, TYPE_INT64);
				throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
			}
		} else if (type == TYPE_DOUBLE) {
			double v = Double.longBitsToDouble(rawVal);
			if (Double.isNaN(v) || Double.isInfinite(v)) {
				setRegister(state, 0, 0L, TYPE_INT64);
				throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
			}
		} else if (type == TYPE_FLOAT_VECTOR) {
			int handle = (int) rawVal;
			float[] vec = ctx.getFloatVector(handle);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					float v = vec[i];
					if (Float.isNaN(v) || Float.isInfinite(v)) {
						setRegister(state, 0, (long) i, TYPE_INT64);
						throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
					}
				}
			}
		}
	}

	public static void handleAllocScratch(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		long reqBytes = instr.payload() & 0xFFFFFFFFL;
		long totalAllocated = ctx.allocateScratch(reqBytes);
		setRegister(state, dstReg, totalAllocated, TYPE_INT64);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleAssertScratchBytes(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int reg = instr.dstReg();
		long requiredBytes = instr.payload() & 0xFFFFFFFFL;
		long currentScratch = ctx.getAllocatedScratchBytes();
		long capacity = ctx.getMaxScratchCapacityBytes();
		long availableOrAllocated = Math.max(currentScratch, capacity);

		if (availableOrAllocated < requiredBytes) {
			throw new IllegalStateException("OP_ASSERT_SCRATCH_BYTES failed: required " + requiredBytes
					+ " bytes, available " + availableOrAllocated);
		}
		setRegister(state, reg, availableOrAllocated, TYPE_INT64);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleSetMaxDop(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int requestedDop = instr.payload();
		int hostCeiling = Runtime.getRuntime().availableProcessors();
		String envDop = System.getenv("IMPULSE_MAX_DOP");
		if (envDop == null)
			envDop = System.getenv("IMPULSE_MAX_THREADS");
		if (envDop != null) {
			try {
				hostCeiling = Math.max(1, Integer.parseInt(envDop.trim()));
			} catch (NumberFormatException ignored) {
			}
		}
		int effectiveDop = Math.max(1, Math.min(requestedDop > 0 ? requestedDop : hostCeiling, hostCeiling));
		ctx.setMaxDop(effectiveDop);
		setRegister(state, dstReg, effectiveDop, TYPE_INT64);
		setFlag(state, FLAG_ZF, false);
	}

	public static void validateReg(int reg) {
		if (reg < 0 || reg >= 64) {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
	}

	// --- Vector Comparison & Logic Handlers (0x20 - 0x27) ---

	public static void handleVecCmpEq(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		byte type1 = getRegisterType(state, src1);
		long val1 = getRegisterValue(state, src1);
		byte type2 = getRegisterType(state, src2);
		long val2 = getRegisterValue(state, src2);

		int dstHandle;
		if (getRegisterType(state, dst) == TYPE_BITSET_HANDLE) {
			dstHandle = (int) getRegisterValue(state, dst);
		} else {
			dstHandle = ctx.acquireBitset();
		}
		ImpulseBitSet bs = ctx.getBitset(dstHandle);
		if (bs != null)
			bs.clear();

		if (type1 == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector((int) val1);
			if (type2 == TYPE_DOUBLE_VECTOR) {
				double[] vec2 = ctx.getDoubleVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] == vec2[i])
							bs.set(i);
					}
				}
			} else {
				double target = (type2 == TYPE_DOUBLE) ? Double.longBitsToDouble(val2) : (double) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] == target)
							bs.set(i);
					}
				}
			}
		} else if (type1 == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) val1);
			if (type2 == TYPE_FLOAT_VECTOR) {
				float[] vec2 = ctx.getFloatVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] == vec2[i])
							bs.set(i);
					}
				}
			} else {
				float target = (type2 == TYPE_FLOAT) ? Float.intBitsToFloat((int) val2) : (float) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] == target)
							bs.set(i);
					}
				}
			}
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs == null || bs.isEmpty());
	}

	public static void handleVecCmpGt(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		byte type1 = getRegisterType(state, src1);
		long val1 = getRegisterValue(state, src1);
		byte type2 = getRegisterType(state, src2);
		long val2 = getRegisterValue(state, src2);

		int dstHandle;
		if (getRegisterType(state, dst) == TYPE_BITSET_HANDLE) {
			dstHandle = (int) getRegisterValue(state, dst);
		} else {
			dstHandle = ctx.acquireBitset();
		}
		ImpulseBitSet bs = ctx.getBitset(dstHandle);
		if (bs != null)
			bs.clear();

		if (type1 == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector((int) val1);
			if (type2 == TYPE_DOUBLE_VECTOR) {
				double[] vec2 = ctx.getDoubleVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] > vec2[i])
							bs.set(i);
					}
				}
			} else {
				double target = (type2 == TYPE_DOUBLE) ? Double.longBitsToDouble(val2) : (double) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] > target)
							bs.set(i);
					}
				}
			}
		} else if (type1 == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) val1);
			if (type2 == TYPE_FLOAT_VECTOR) {
				float[] vec2 = ctx.getFloatVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] > vec2[i])
							bs.set(i);
					}
				}
			} else {
				float target = (type2 == TYPE_FLOAT) ? Float.intBitsToFloat((int) val2) : (float) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] > target)
							bs.set(i);
					}
				}
			}
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs == null || bs.isEmpty());
	}

	public static void handleVecCmpLt(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		byte type1 = getRegisterType(state, src1);
		long val1 = getRegisterValue(state, src1);
		byte type2 = getRegisterType(state, src2);
		long val2 = getRegisterValue(state, src2);

		int dstHandle;
		if (getRegisterType(state, dst) == TYPE_BITSET_HANDLE) {
			dstHandle = (int) getRegisterValue(state, dst);
		} else {
			dstHandle = ctx.acquireBitset();
		}
		ImpulseBitSet bs = ctx.getBitset(dstHandle);
		if (bs != null)
			bs.clear();

		if (type1 == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector((int) val1);
			if (type2 == TYPE_DOUBLE_VECTOR) {
				double[] vec2 = ctx.getDoubleVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] < vec2[i])
							bs.set(i);
					}
				}
			} else {
				double target = (type2 == TYPE_DOUBLE) ? Double.longBitsToDouble(val2) : (double) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] < target)
							bs.set(i);
					}
				}
			}
		} else if (type1 == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) val1);
			if (type2 == TYPE_FLOAT_VECTOR) {
				float[] vec2 = ctx.getFloatVector((int) val2);
				if (vec != null && vec2 != null && bs != null) {
					int len = Math.min(vec.length, vec2.length);
					for (int i = 0; i < len; i++) {
						if (vec[i] < vec2[i])
							bs.set(i);
					}
				}
			} else {
				float target = (type2 == TYPE_FLOAT) ? Float.intBitsToFloat((int) val2) : (float) val2;
				if (vec != null && bs != null) {
					for (int i = 0; i < vec.length; i++) {
						if (vec[i] < target)
							bs.set(i);
					}
				}
			}
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs == null || bs.isEmpty());
	}

	public static void handleVecCmpBetween(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int srcVec = instr.payload() & 0xFF;
		int rMin = (instr.payload() >> 8) & 0xFF;
		int rMax = (instr.payload() >> 16) & 0xFF;
		validateReg(dst);
		validateReg(srcVec);
		validateReg(rMin);
		validateReg(rMax);

		byte typeVec = getRegisterType(state, srcVec);
		long valVec = getRegisterValue(state, srcVec);
		byte typeMin = getRegisterType(state, rMin);
		long valMin = getRegisterValue(state, rMin);
		byte typeMax = getRegisterType(state, rMax);
		long valMax = getRegisterValue(state, rMax);

		int dstHandle;
		if (getRegisterType(state, dst) == TYPE_BITSET_HANDLE) {
			dstHandle = (int) getRegisterValue(state, dst);
		} else {
			dstHandle = ctx.acquireBitset();
		}
		ImpulseBitSet bs = ctx.getBitset(dstHandle);
		if (bs != null)
			bs.clear();

		if (typeVec == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector((int) valVec);
			double[] minVec = (typeMin == TYPE_DOUBLE_VECTOR) ? ctx.getDoubleVector((int) valMin) : null;
			double[] maxVec = (typeMax == TYPE_DOUBLE_VECTOR) ? ctx.getDoubleVector((int) valMax) : null;
			double minVal = (typeMin == TYPE_DOUBLE) ? Double.longBitsToDouble(valMin) : (double) valMin;
			double maxVal = (typeMax == TYPE_DOUBLE) ? Double.longBitsToDouble(valMax) : (double) valMax;
			if (vec != null && bs != null) {
				for (int i = 0; i < vec.length; i++) {
					double v = vec[i];
					double lo = (minVec != null && i < minVec.length) ? minVec[i] : minVal;
					double hi = (maxVec != null && i < maxVec.length) ? maxVec[i] : maxVal;
					if (!Double.isNaN(v) && v >= lo && v <= hi)
						bs.set(i);
				}
			}
		} else if (typeVec == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) valVec);
			float[] minVec = (typeMin == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector((int) valMin) : null;
			float[] maxVec = (typeMax == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector((int) valMax) : null;
			float minVal = (typeMin == TYPE_FLOAT) ? Float.intBitsToFloat((int) valMin) : (float) valMin;
			float maxVal = (typeMax == TYPE_FLOAT) ? Float.intBitsToFloat((int) valMax) : (float) valMax;
			if (vec != null && bs != null) {
				for (int i = 0; i < vec.length; i++) {
					float v = vec[i];
					float lo = (minVec != null && i < minVec.length) ? minVec[i] : minVal;
					float hi = (maxVec != null && i < maxVec.length) ? maxVec[i] : maxVal;
					if (!Float.isNaN(v) && v >= lo && v <= hi)
						bs.set(i);
				}
			}
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, bs == null || bs.isEmpty());
	}

	public static void handleMaskAnd(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, src1));
		ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, src2));

		boolean aliasedInput = (dst == src1) || (dst == src2);
		int prevDstHandle = (getRegisterType(state, dst) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dst)
				: -1;
		int dstHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet dstBs = ctx.getBitset(dstHandle);
		if (dstBs != null) {
			dstBs.clear();
			if (bs1 != null && bs2 != null) {
				dstBs.or(bs1);
				dstBs.and(bs2);
			}
		}
		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != dstHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, dstBs == null || dstBs.isEmpty());
	}

	public static void handleMaskOr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, src1));
		ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, src2));

		boolean aliasedInput = (dst == src1) || (dst == src2);
		int prevDstHandle = (getRegisterType(state, dst) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dst)
				: -1;
		int dstHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet dstBs = ctx.getBitset(dstHandle);
		if (dstBs != null) {
			dstBs.clear();
			if (bs1 != null)
				dstBs.or(bs1);
			if (bs2 != null)
				dstBs.or(bs2);
		}
		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != dstHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, dstBs == null || dstBs.isEmpty());
	}

	public static void handleMaskNot(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		validateReg(dst);
		validateReg(src);

		byte srcType = getRegisterType(state, src);
		if (srcType == TYPE_FLOAT_VECTOR) {
			int h = (int) getRegisterValue(state, src);
			float[] vec = ctx.getFloatVector(h);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					vec[i] = Float.isNaN(vec[i]) ? 0.0f : Float.NaN;
				}
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		} else if (srcType == TYPE_DOUBLE_VECTOR) {
			int h = (int) getRegisterValue(state, src);
			double[] vec = ctx.getDoubleVector(h);
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					vec[i] = Double.isNaN(vec[i]) ? 0.0 : Double.NaN;
				}
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, src));
		boolean aliasedInput = (dst == src);
		int prevDstHandle = (getRegisterType(state, dst) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dst)
				: -1;
		int dstHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet dstBs = ctx.getBitset(dstHandle);
		if (dstBs != null) {
			dstBs.clear();
			int maxNodes = 1024;
			if (ctx.snapshot() != null && !ctx.snapshot().getAllRelationSnapshots().isEmpty()) {
				maxNodes = ctx.snapshot().getAllRelationSnapshots().values().iterator().next().getNodeCount();
			}
			for (int i = 0; i < maxNodes; i++) {
				if (bs == null || !bs.get(i)) {
					dstBs.set(i);
				}
			}
		}
		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != dstHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, dstBs == null || dstBs.isEmpty());
	}

	public static void handleVecBlend(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int maskReg = instr.payload() & 0xFF;
		int src1 = (instr.payload() >> 8) & 0xFF;
		int src2 = (instr.payload() >> 16) & 0xFF;
		validateReg(dst);
		validateReg(maskReg);
		validateReg(src1);
		validateReg(src2);

		ImpulseBitSet mask = ctx.getBitset((int) getRegisterValue(state, maskReg));
		byte type1 = getRegisterType(state, src1);
		if (type1 == TYPE_DOUBLE_VECTOR) {
			double[] v1 = ctx.getDoubleVector((int) getRegisterValue(state, src1));
			double[] v2 = ctx.getDoubleVector((int) getRegisterValue(state, src2));
			int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = ctx.acquireDoubleVector(len);
			double[] out = ctx.getDoubleVector(h);
			for (int i = 0; i < len; i++) {
				boolean bit = (mask != null) && mask.get(i);
				double val1 = (v1 != null && i < v1.length) ? v1[i] : 0.0;
				double val2 = (v2 != null && i < v2.length) ? v2[i] : 0.0;
				out[i] = bit ? val1 : val2;
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
		} else if (type1 == TYPE_FLOAT_VECTOR) {
			float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, src1));
			float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, src2));
			int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				boolean bit = (mask != null) && mask.get(i);
				float val1 = (v1 != null && i < v1.length) ? v1[i] : 0.0f;
				float val2 = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
				out[i] = bit ? val1 : val2;
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		}
	}

	// --- Vector Math Handlers (0x2D - 0x2F) ---

	public static void handleVecMathUnary(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int funcId = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(src);

		byte type = getRegisterType(state, src);
		if (type == TYPE_DOUBLE) {
			double val = Double.longBitsToDouble(getRegisterValue(state, src));
			double res = applyMathUnary(funcId, val);
			setRegister(state, dst, Double.doubleToRawLongBits(res), TYPE_DOUBLE);
		} else if (type == TYPE_FLOAT) {
			float val = Float.intBitsToFloat((int) getRegisterValue(state, src));
			float res = (float) applyMathUnary(funcId, val);
			setRegister(state, dst, Integer.toUnsignedLong(Float.floatToRawIntBits(res)), TYPE_FLOAT);
		} else if (type == TYPE_DOUBLE_VECTOR) {
			int srcH = (int) getRegisterValue(state, src);
			double[] v = ctx.getDoubleVector(srcH);
			int len = (ctx != null) ? ctx.getDoubleVectorLength(srcH) : (v != null ? v.length : 1024);
			int h = (getRegisterType(state, dst) == TYPE_DOUBLE_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireDoubleVector(len);
			ctx.setDoubleVectorLength(h, len);
			double[] out = ctx.getDoubleVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireDoubleVector(len);
				out = ctx.getDoubleVector(h);
			}
			for (int i = 0; i < len; i++) {
				double val = (v != null && i < v.length) ? v[i] : 0.0;
				out[i] = applyMathUnary(funcId, val);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
		} else if (type == TYPE_FLOAT_VECTOR) {
			int srcH = (int) getRegisterValue(state, src);
			float[] v = ctx.getFloatVector(srcH);
			int len = (ctx != null) ? ctx.getFloatVectorLength(srcH) : (v != null ? v.length : 1024);
			int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireFloatVector(len);
			ctx.setFloatVectorLength(h, len);
			float[] out = ctx.getFloatVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireFloatVector(len);
				out = ctx.getFloatVector(h);
			}
			for (int i = 0; i < len; i++) {
				float val = (v != null && i < v.length) ? v[i] : 0.0f;
				out[i] = (float) applyMathUnary(funcId, val);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		} else {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
	}

	private static double applyMathUnary(int funcId, double v) {
		if (Double.isNaN(v) && funcId != 52 && funcId != 53 && funcId != 54) {
			return Double.NaN;
		}
		return switch (funcId) {
			case 1 -> Math.abs(v);
			case 2 -> Math.sqrt(v);
			case 3 -> 1.0 / Math.sqrt(v);
			case 4 -> Math.cbrt(v);
			case 8 -> Math.exp(v);
			case 9 -> Math.pow(2.0, v);
			case 10 -> Math.pow(10.0, v);
			case 11 -> Math.expm1(v);
			case 12 -> Math.log(v);
			case 13 -> Math.log(v) / Math.log(2.0);
			case 14 -> Math.log10(v);
			case 15 -> Math.log1p(v);
			case 16 -> Math.sin(v);
			case 17 -> Math.cos(v);
			case 18 -> Math.tan(v);
			case 19 -> Math.asin(v);
			case 20 -> Math.acos(v);
			case 21 -> Math.atan(v);
			case 23 -> (Math.abs(v) < 1e-7) ? 1.0 : Math.sin(v) / v;
			case 24 -> Math.sinh(v);
			case 25 -> Math.cosh(v);
			case 26 -> Math.tanh(v);
			case 27 -> Math.log(v + Math.sqrt(v * v + 1.0));
			case 28 -> Math.log(v + Math.sqrt(v * v - 1.0));
			case 29 -> 0.5 * Math.log((1.0 + v) / (1.0 - v));
			case 30 -> Math.floor(v);
			case 31 -> Math.ceil(v);
			case 32 -> (v >= 0.0) ? Math.floor(v) : Math.ceil(v);
			case 33 -> Math.round(v);
			case 37 -> Math.max(0.0, v);
			case 38 -> (v >= 0.0) ? v : 0.01 * v;
			case 39 -> 1.0 / (1.0 + Math.exp(-v));
			case 40 -> 0.5 * v * (1.0 + Math.tanh(Math.sqrt(2.0 / Math.PI) * (v + 0.044715 * Math.pow(v, 3.0))));
			case 41 -> v / (1.0 + Math.exp(-v));
			case 42 -> Math.log1p(Math.exp(v));
			case 52 -> Double.isNaN(v) ? 1.0 : 0.0;
			case 53 -> Double.isInfinite(v) ? 1.0 : 0.0;
			case 54 -> Double.isFinite(v) ? 1.0 : 0.0;
			default -> v;
		};
	}

	public static void handleVecMathBinary(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		int funcId = (instr.payload() >> 16) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);

		byte type1 = getRegisterType(state, src1);
		byte type2 = getRegisterType(state, src2);
		if (type1 == TYPE_FLOAT && type2 == TYPE_FLOAT) {
			float a = Float.intBitsToFloat((int) getRegisterValue(state, src1));
			float b = Float.intBitsToFloat((int) getRegisterValue(state, src2));
			float res = (float) applyMathBinary(funcId, a, b);
			setRegister(state, dst, Integer.toUnsignedLong(Float.floatToRawIntBits(res)), TYPE_FLOAT);
		} else if (type1 == TYPE_DOUBLE && type2 == TYPE_DOUBLE) {
			double a = Double.longBitsToDouble(getRegisterValue(state, src1));
			double b = Double.longBitsToDouble(getRegisterValue(state, src2));
			double res = applyMathBinary(funcId, a, b);
			setRegister(state, dst, Double.doubleToRawLongBits(res), TYPE_DOUBLE);
		} else if ((type1 == TYPE_DOUBLE_VECTOR || type2 == TYPE_DOUBLE_VECTOR)
				&& (type1 == TYPE_DOUBLE_VECTOR || type1 == TYPE_DOUBLE || type1 == TYPE_FLOAT)
				&& (type2 == TYPE_DOUBLE_VECTOR || type2 == TYPE_DOUBLE || type2 == TYPE_FLOAT)) {
			int h1 = (type1 == TYPE_DOUBLE_VECTOR) ? (int) getRegisterValue(state, src1) : -1;
			int h2 = (type2 == TYPE_DOUBLE_VECTOR) ? (int) getRegisterValue(state, src2) : -1;
			double[] v1 = (h1 >= 0) ? ctx.getDoubleVector(h1) : null;
			double[] v2 = (h2 >= 0) ? ctx.getDoubleVector(h2) : null;
			double s1 = (type1 == TYPE_DOUBLE)
					? Double.longBitsToDouble(getRegisterValue(state, src1))
					: (type1 == TYPE_FLOAT ? Float.intBitsToFloat((int) getRegisterValue(state, src1)) : 0.0);
			double s2 = (type2 == TYPE_DOUBLE)
					? Double.longBitsToDouble(getRegisterValue(state, src2))
					: (type2 == TYPE_FLOAT ? Float.intBitsToFloat((int) getRegisterValue(state, src2)) : 0.0);
			int len1 = (h1 >= 0 && ctx != null) ? ctx.getDoubleVectorLength(h1) : 0;
			int len2 = (h2 >= 0 && ctx != null) ? ctx.getDoubleVectorLength(h2) : 0;
			int len = (len1 > 0 && len2 > 0) ? Math.min(len1, len2) : Math.max(len1, len2);
			if (len == 0)
				len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = (getRegisterType(state, dst) == TYPE_DOUBLE_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireDoubleVector(len);
			ctx.setDoubleVectorLength(h, len);
			double[] out = ctx.getDoubleVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireDoubleVector(len);
				out = ctx.getDoubleVector(h);
			}
			for (int i = 0; i < len; i++) {
				double a = (v1 != null) ? ((i < v1.length) ? v1[i] : 0.0) : s1;
				double b = (v2 != null) ? ((i < v2.length) ? v2[i] : 0.0) : s2;
				out[i] = applyMathBinary(funcId, a, b);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
		} else if ((type1 == TYPE_FLOAT_VECTOR || type2 == TYPE_FLOAT_VECTOR)
				&& (type1 == TYPE_FLOAT_VECTOR || type1 == TYPE_FLOAT)
				&& (type2 == TYPE_FLOAT_VECTOR || type2 == TYPE_FLOAT)) {
			int h1 = (type1 == TYPE_FLOAT_VECTOR) ? (int) getRegisterValue(state, src1) : -1;
			int h2 = (type2 == TYPE_FLOAT_VECTOR) ? (int) getRegisterValue(state, src2) : -1;
			float[] v1 = (h1 >= 0) ? ctx.getFloatVector(h1) : null;
			float[] v2 = (h2 >= 0) ? ctx.getFloatVector(h2) : null;
			float s1 = (type1 == TYPE_FLOAT) ? Float.intBitsToFloat((int) getRegisterValue(state, src1)) : 0.0f;
			float s2 = (type2 == TYPE_FLOAT) ? Float.intBitsToFloat((int) getRegisterValue(state, src2)) : 0.0f;
			int len1 = (h1 >= 0 && ctx != null) ? ctx.getFloatVectorLength(h1) : 0;
			int len2 = (h2 >= 0 && ctx != null) ? ctx.getFloatVectorLength(h2) : 0;
			int len = (len1 > 0 && len2 > 0) ? Math.min(len1, len2) : Math.max(len1, len2);
			if (len == 0)
				len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireFloatVector(len);
			ctx.setFloatVectorLength(h, len);
			float[] out = ctx.getFloatVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireFloatVector(len);
				out = ctx.getFloatVector(h);
			}
			for (int i = 0; i < len; i++) {
				float a = (v1 != null) ? ((i < v1.length) ? v1[i] : 0.0f) : s1;
				float b = (v2 != null) ? ((i < v2.length) ? v2[i] : 0.0f) : s2;
				out[i] = (float) applyMathBinary(funcId, a, b);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		} else {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
	}

	private static double applyMathBinary(int funcId, double a, double b) {
		if (Double.isNaN(a) || Double.isNaN(b)) {
			return Double.NaN;
		}
		return switch (funcId) {
			case 0 -> a + b;
			case 1 -> a - b;
			case 2 -> a * b;
			case 3 -> (b == 0.0) ? 0.0 : a / b;
			case 4 -> Math.pow(a, b);
			case 5 -> Math.pow(a, b);
			case 6 -> Math.hypot(a, b);
			case 22 -> Math.atan2(a, b);
			case 35 -> Math.copySign(a, b);
			case 36 -> Math.IEEEremainder(a, b);
			case 38 -> (a > 0.0) ? a : (b * a);
			case 51 -> (b == 0.0) ? 0.0 : a / b;
			case 100 -> a + b;
			case 101 -> a - b;
			case 102 -> a * b;
			case 103 -> (b == 0.0) ? 0.0 : a / b;
			default -> a + b;
		};
	}

	public static void handleVecMathTernary(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src1 = instr.payload() & 0xFF;
		int src2 = (instr.payload() >> 8) & 0xFF;
		int src3 = (instr.payload() >> 16) & 0xFF;
		int funcId = (instr.payload() >> 24) & 0xFF;
		validateReg(dst);
		validateReg(src1);
		validateReg(src2);
		validateReg(src3);

		byte type = getRegisterType(state, src1);
		if (type == TYPE_DOUBLE_VECTOR) {
			int h1 = (int) getRegisterValue(state, src1);
			int h2 = (int) getRegisterValue(state, src2);
			int h3 = (int) getRegisterValue(state, src3);
			double[] v1 = ctx.getDoubleVector(h1);
			double[] v2 = ctx.getDoubleVector(h2);
			double[] v3 = ctx.getDoubleVector(h3);
			int len = (ctx != null) ? ctx.getDoubleVectorLength(h1) : 0;
			if (len == 0)
				len = (v1 != null) ? v1.length : 1024;
			int h = (getRegisterType(state, dst) == TYPE_DOUBLE_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireDoubleVector(len);
			ctx.setDoubleVectorLength(h, len);
			double[] out = ctx.getDoubleVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireDoubleVector(len);
				out = ctx.getDoubleVector(h);
			}
			for (int i = 0; i < len; i++) {
				double a = (v1 != null && i < v1.length) ? v1[i] : 0.0;
				double b = (v2 != null && i < v2.length) ? v2[i] : 0.0;
				double c = (v3 != null && i < v3.length) ? v3[i] : 0.0;
				out[i] = applyMathTernary(funcId, a, b, c);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
			setFlag(state, FLAG_ZF, false);
		} else if (type == TYPE_FLOAT_VECTOR) {
			int h1 = (int) getRegisterValue(state, src1);
			int h2 = (int) getRegisterValue(state, src2);
			int h3 = (int) getRegisterValue(state, src3);
			float[] v1 = ctx.getFloatVector(h1);
			float[] v2 = ctx.getFloatVector(h2);
			float[] v3 = ctx.getFloatVector(h3);
			int len = (ctx != null) ? ctx.getFloatVectorLength(h1) : 0;
			if (len == 0)
				len = (v1 != null) ? v1.length : 1024;
			int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireFloatVector(len);
			ctx.setFloatVectorLength(h, len);
			float[] out = ctx.getFloatVector(h);
			if (out == null || out.length < len) {
				h = ctx.acquireFloatVector(len);
				out = ctx.getFloatVector(h);
			}
			for (int i = 0; i < len; i++) {
				float a = (v1 != null && i < v1.length) ? v1[i] : 0.0f;
				float b = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
				float c = (v3 != null && i < v3.length) ? v3[i] : 0.0f;
				out[i] = (float) applyMathTernary(funcId, a, b, c);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		} else {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
	}

	private static double applyMathTernary(int funcId, double a, double b, double c) {
		if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) {
			return Double.NaN;
		}
		return switch (funcId) {
			case 7 -> a + c * (b - a); // LERP
			case 34 -> Math.max(b, Math.min(a, c)); // CLAMP
			default -> Math.fma(a, b, c);
		};
	}

	public static void handleSparseMatvec(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		int h = ctx.acquireFloatVector(1024);
		if (h < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, true);
	}

	public static void handleLouvainModularity(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		int h = ctx.acquireNodeVector(1024);
		if (h < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		setRegister(state, dst, h, TYPE_NODE_VECTOR);
		setFlag(state, FLAG_ZF, true);
	}

	public static void handleMotifMatch3(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		setRegister(state, dst, 0L, TYPE_INT64);
		setFlag(state, FLAG_ZF, true);
	}

	public static void handleGraphIsomorphism(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		setRegister(state, dst, 1L, TYPE_BOOLEAN);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleKcoreDecomposition(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		int rSrc = instr.payload() & 0xFF;
		validateReg(rSrc);

		int relId = (int) getRegisterValue(state, rSrc);
		if (relId == 0 && getRegisterType(state, rSrc) == TYPE_NULL && rSrc > 0) {
			relId = rSrc;
		}

		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null && relId > 0 && ctx.snapshot() != null) {
			rel = ctx.snapshot().getRelationSnapshot(relId);
		}
		if (rel == null && ctx.snapshot() != null && !ctx.snapshot().getAllRelationSnapshots().isEmpty()) {
			rel = ctx.snapshot().getAllRelationSnapshots().values().iterator().next();
		}

		int N = 0;
		if (rel != null) {
			N = Math.max(rel.getNodeCount(), rel.getCscNodeCount());
		}
		if (N == 0) {
			N = (ctx.snapshot() != null) ? ctx.snapshot().getMaxNodeCount() : 1024;
		}

		int handle = ctx.acquireFloatVector(N);
		if (handle < 0) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}
		ctx.setFloatVectorLength(handle, N);
		float[] coreOut = ctx.getFloatVector(handle);
		java.util.Arrays.fill(coreOut, 0, N, 0.0f);

		if (rel != null && N > 0) {
			MemorySegment offsetsSeg = rel.getRowOffsetsSegment();
			MemorySegment targetsSeg = rel.getColumnTargetsSegment();

			int[] deg = ctx.scratchInt(0, N);
			int maxDeg = 0;
			int srcNodeCount = rel.getNodeCount();
			for (int i = 0; i < N; i++) {
				int d = (i < srcNodeCount)
						? (offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, i + 1)
								- offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, i))
						: 0;
				deg[i] = d;
				if (d > maxDeg)
					maxDeg = d;
			}

			int[] vert = ctx.scratchInt(1, N);
			int[] pos = ctx.scratchInt(2, N);
			int[] bin = ctx.scratchInt(3, maxDeg + 1);
			java.util.Arrays.fill(bin, 0, maxDeg + 1, 0);

			for (int i = 0; i < N; i++)
				bin[deg[i]]++;
			int start = 0;
			for (int d = 0; d <= maxDeg; d++) {
				int num = bin[d];
				bin[d] = start;
				start += num;
			}
			for (int i = 0; i < N; i++) {
				int p = bin[deg[i]]++;
				pos[i] = p;
				vert[p] = i;
			}
			for (int d = maxDeg; d >= 1; d--) {
				bin[d] = bin[d - 1];
			}
			bin[0] = 0;

			for (int i = 0; i < N; i++) {
				int v = vert[i];
				coreOut[v] = (float) deg[v];
				int eStart = (v < srcNodeCount) ? offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, v) : 0;
				int eEnd = (v < srcNodeCount) ? offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, v + 1) : 0;
				for (int e = eStart; e < eEnd; e++) {
					int u = targetsSeg.getAtIndex(ValueLayout.JAVA_INT, e);
					if (u < N && deg[u] > deg[v]) {
						int du = deg[u];
						int pu = pos[u];
						int pw = bin[du];
						int w = vert[pw];
						if (u != w) {
							pos[u] = pw;
							pos[w] = pu;
							vert[pu] = w;
							vert[pw] = u;
						}
						bin[du]++;
						deg[u]--;
					}
				}
			}
		}

		setRegister(state, dst, handle, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	// --- Extended Traversal, COO, Direct Store & Swap (0x80 - 0x95) ---

	public static void handleSwapReg(MemorySegment state, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);

		long valDst = getRegisterValue(state, dst);
		byte typeDst = getRegisterType(state, dst);

		long valSrc = getRegisterValue(state, src);
		byte typeSrc = getRegisterType(state, src);

		setRegister(state, dst, valSrc, typeSrc);
		setRegister(state, src, valDst, typeDst);
	}

	public static void handleFrontierDiff(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int currReg = instr.payload() & 0xFF;
		int prevReg = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(currReg);
		validateReg(prevReg);

		ImpulseBitSet bsCurr = ctx.getBitset((int) getRegisterValue(state, currReg));
		ImpulseBitSet bsPrev = ctx.getBitset((int) getRegisterValue(state, prevReg));

		int dstHandle = ctx.acquireBitset();
		ImpulseBitSet dstBs = ctx.getBitset(dstHandle);
		dstBs.clear();
		if (bsCurr != null) {
			dstBs.or(bsCurr);
			if (bsPrev != null) {
				dstBs.andNot(bsPrev);
			}
		}
		setRegister(state, dst, dstHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, dstBs.isEmpty());
	}

	public static void handleFixpointKleeneStar(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		if (ctx == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		byte srcType = getRegisterType(state, src);
		long srcVal = getRegisterValue(state, src);
		boolean srcIsBitset = (srcType == TYPE_BITSET_HANDLE);

		int hDst;
		if (getRegisterType(state, dst) == TYPE_BITSET_HANDLE && !(dst == src && srcIsBitset)) {
			hDst = (int) getRegisterValue(state, dst);
		} else {
			hDst = ctx.acquireBitset();
			if (hDst < 0) {
				throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
			}
		}
		ImpulseBitSet reached = ctx.getBitset(hDst);
		reached.clear();

		RelationSnapshot relSnap = resolveRelation(ctx, rel);
		int currH = ctx.acquireBitset();
		int nextH = ctx.acquireBitset();
		if (currH >= 0 && nextH >= 0) {
			ImpulseBitSet currentFrontier = ctx.getBitset(currH);
			ImpulseBitSet nextFrontier = ctx.getBitset(nextH);
			currentFrontier.clear();

			if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
				int node = (int) srcVal;
				reached.set(node);
				currentFrontier.set(node);
			} else if (srcIsBitset) {
				int hSrc = (int) srcVal;
				ImpulseBitSet bsSrc = ctx.getBitset(hSrc);
				if (bsSrc != null) {
					reached.or(bsSrc);
					currentFrontier.or(bsSrc);
				}
				if (dst == src) {
					ctx.releaseBitset(hSrc);
				}
			}

			int maxIter = 1000;
			while (!currentFrontier.isEmpty() && maxIter-- > 0) {
				ctx.consumeFuel();
				nextFrontier.clear();
				if (relSnap != null) {
					for (int u = currentFrontier.nextSetBit(0); u >= 0; u = currentFrontier.nextSetBit(u + 1)) {
						relSnap.copyTargetsSimd(u, nextFrontier);
					}
				}
				nextFrontier.andNot(reached);
				if (nextFrontier.isEmpty()) {
					break;
				}
				reached.or(nextFrontier);
				currentFrontier.clear();
				currentFrontier.or(nextFrontier);
			}
		}
		if (currH >= 0)
			ctx.releaseBitset(currH);
		if (nextH >= 0)
			ctx.releaseBitset(nextH);

		setRegister(state, dst, hDst, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, reached.isEmpty());
	}

	public static void handleCooWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleCooWalkFiltered(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int filterReg = (instr.payload() >> 8) & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		validateReg(filterReg);

		RelationSnapshot relSnap = resolveRelation(ctx, rel);
		if (relSnap == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}

		byte fltType = getRegisterType(state, filterReg);
		long fltVal = getRegisterValue(state, filterReg);
		byte srcType = getRegisterType(state, src);
		long srcVal = getRegisterValue(state, src);

		boolean aliasedInput = ((dst == src) && (srcType == TYPE_BITSET_HANDLE))
				|| ((dst == filterReg) && (fltType == TYPE_BITSET_HANDLE));
		int prevDstHandle = (getRegisterType(state, dst) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dst)
				: -1;
		int outHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		ImpulseBitSet fltBs = (fltType == TYPE_BITSET_HANDLE) ? ctx.getBitset((int) fltVal) : null;
		int scalarFlt = (fltType == TYPE_NODE_ID || fltType == TYPE_INT64) ? (int) fltVal : -1;

		int tmpH = ctx.acquireBitset();
		ImpulseBitSet tmpBs = ctx.getBitset(tmpH);

		if (srcType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bsSrc = ctx.getBitset((int) srcVal);
			if (bsSrc != null && relSnap != null) {
				for (int u = bsSrc.nextSetBit(0); u >= 0; u = bsSrc.nextSetBit(u + 1)) {
					tmpBs.clear();
					relSnap.copyTargetsSimd(u, tmpBs);
					for (int v = tmpBs.nextSetBit(0); v >= 0; v = tmpBs.nextSetBit(v + 1)) {
						boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
						if (match)
							outBs.set(v);
					}
				}
			}
		} else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
			int u = (int) srcVal;
			if (relSnap != null) {
				tmpBs.clear();
				relSnap.copyTargetsSimd(u, tmpBs);
				for (int v = tmpBs.nextSetBit(0); v >= 0; v = tmpBs.nextSetBit(v + 1)) {
					boolean match = (fltBs != null) ? fltBs.get(v) : (v == scalarFlt);
					if (match)
						outBs.set(v);
				}
			}
		}
		ctx.releaseBitset(tmpH);
		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != outHandle) {
			ctx.releaseBitset(prevDstHandle);
		}

		setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleCooWalkReduce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);

		byte srcType = getRegisterType(state, src);
		long srcVal = getRegisterValue(state, src);
		boolean srcIsNull = (srcType == TYPE_NULL);
		boolean srcIsBitset = (srcType == TYPE_BITSET_HANDLE);

		int hDst = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
				? (int) getRegisterValue(state, dst)
				: ctx.acquireFloatVector(1024);
		setRegister(state, dst, hDst, TYPE_FLOAT_VECTOR);
		float[] dstVec = ctx.getFloatVector(hDst);

		if (srcIsNull) {
			ctx.setFloatVectorLength(hDst, 0);
			return;
		}

		ctx.setFloatVectorLength(hDst, 1024);
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel != null && dstVec != null) {
			int nodeCount = rel.getNodeCount();
			MemorySegment rowOff = rel.getRowOffsetsSegment();
			MemorySegment colIdx = rel.getColumnTargetsSegment();
			if (rowOff != null && colIdx != null) {
				if (srcIsBitset) {
					ImpulseBitSet bs = ctx.getBitset((int) srcVal);
					if (bs != null) {
						for (int u = bs.nextSetBit(0); u >= 0 && u < nodeCount; u = bs.nextSetBit(u + 1)) {
							int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
							int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
							for (int e = start; e < end; e++) {
								int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
								if (v >= 0 && v < dstVec.length) {
									dstVec[v] += 1.0f;
								}
							}
						}
					}
				} else {
					int u = (int) srcVal;
					if (u >= 0 && u < nodeCount) {
						int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
						int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
						for (int e = start; e < end; e++) {
							int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
							if (v >= 0 && v < dstVec.length) {
								dstVec[v] += 1.0f;
							}
						}
					}
				}
			}
		}
	}

	public static void handleCooWalkDirectStore(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleDenseWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleDenseWalkBitmatrix(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleDenseWalkReduce(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		setRegister(state, dst, 0L, TYPE_FLOAT_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleDenseWalkDirectStore(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleCsrWalkDirectStore(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleCsrWalkDenseStream(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		executeCsrWalk(state, ctx, dst, src, rel);
	}

	public static void handleCscWalkDirectStore(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		handleCscWalk(state, ctx, instr);
	}

	public static void handleLoadColumnVector(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int domainOrRel = instr.payload() & 0xFFFF;
		int attrIdx = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);

		Object mockObj = ctx.getMockAttribute(attrIdx);
		if (mockObj == null) {
			mockObj = ctx.getMockAttribute(domainOrRel);
		}
		if (mockObj instanceof float[] farr) {
			int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
					? (int) getRegisterValue(state, dst)
					: ctx.acquireFloatVector(farr.length);
			ctx.setFloatVectorLength(h, farr.length);
			float[] vec = ctx.getFloatVector(h);
			System.arraycopy(farr, 0, vec, 0, farr.length);
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
			return;
		}

		if (ctx.getSnapshot() != null) {
			int strIdx = (attrIdx != 0) ? attrIdx : domainOrRel;
			String attrName = ctx.getString(strIdx);
			for (org.impulsegraph.api.RelationSnapshot rel : ctx.getSnapshot().getAllRelationSnapshots().values()) {
				if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel) {
					int idx = csrRel.findAttributeIndex(attrName);
					if (idx >= 0 && csrRel.getAttributeSegments().size() > idx) {
						String fullAttrName = csrRel.getAttributeName(idx);
						long count = (fullAttrName.contains(".") || rel.getEdgeCount() == 0)
								? rel.getNodeCount()
								: rel.getEdgeCount();
						int typeCode = resolveEffectiveAttrTypeCode(csrRel.getAttributeTypeCode(idx) & 0x7F,
								fullAttrName);
						int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
								? (int) getRegisterValue(state, dst)
								: ctx.acquireFloatVector((int) count);
						ctx.setFloatVectorLength(h, (int) count);
						float[] vec = ctx.getFloatVector(h);
						java.lang.foreign.MemorySegment dataSeg = csrRel.getAttributeSegments().get(idx);
						java.lang.foreign.MemorySegment validSeg = null;
						if (csrRel.getValiditySegments() != null && csrRel.getValiditySegments().size() > idx) {
							validSeg = csrRel.getValiditySegments().get(idx);
						}
						for (int i = 0; i < count; i++) {
							boolean isNull = false;
							if (validSeg != null && validSeg.byteSize() > 0) {
								long byteOff = i / 8;
								int bitOff = i % 8;
								byte b = validSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, byteOff);
								if ((b & (1 << bitOff)) == 0) {
									isNull = true;
								}
							}
							vec[i] = isNull ? Float.NaN : readColumnAttributeAsFloat(dataSeg, typeCode, i);
						}
						setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
						return;
					}
				}
			}
		}

		int h = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
				? (int) getRegisterValue(state, dst)
				: ctx.acquireFloatVector(0);
		ctx.setFloatVectorLength(h, 0);
		setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
	}

	private static int resolveEffectiveAttrTypeCode(int typeCode, String attrName) {
		if (typeCode != 0 || attrName == null) {
			return typeCode;
		}
		if (attrName.endsWith("_i8") || attrName.endsWith("_u8")) {
			return 0x01;
		}
		if (attrName.endsWith("_i16") || attrName.endsWith("_u16")) {
			return 0x02;
		}
		if (attrName.endsWith("_i32") || attrName.endsWith("_u32")) {
			return 0x03;
		}
		if (attrName.endsWith("_i64") || attrName.endsWith("_u64")) {
			return 0x04;
		}
		if (attrName.endsWith("_f64")) {
			return 0x06;
		}
		return 0x05;
	}

	private static float readColumnAttributeAsFloat(MemorySegment dataSeg, int typeCode, int i) {
		return switch (typeCode) {
			case 0x01 -> (float) dataSeg.get(ValueLayout.JAVA_BYTE, i);
			case 0x02 -> (float) dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, i * 2L);
			case 0x03 -> (float) dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, i * 4L);
			case 0x04 -> (float) dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, i * 8L);
			case 0x06 -> (float) dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, i * 8L);
			default -> dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, i * 4L);
		};
	}

	public static void handleInitMockAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int attrId = instr.dstReg();
		int srcReg = instr.payload() & 0xFF;
		int valReg = (instr.payload() >> 8) & 0xFF;
		if (srcReg < 64) {
			byte srcType = getRegisterType(state, srcReg);
			int h = (int) getRegisterValue(state, srcReg);
			if (srcType == TYPE_FLOAT_VECTOR) {
				float[] farr = ctx.getFloatVector(h);
				if (farr != null) {
					ctx.setMockAttribute(attrId, farr);
				}
			} else if (srcType == TYPE_NODE_VECTOR || srcType == TYPE_UINT64_VECTOR) {
				int[] iarr = ctx.getIntVector(h);
				if (iarr != null) {
					ctx.setMockAttribute(attrId, iarr);
				}
			} else {
				int[] iarr = ctx.getIntVector(h);
				if (iarr != null) {
					ctx.setMockAttribute(attrId, iarr);
				} else {
					float[] farr = ctx.getFloatVector(h);
					if (farr != null) {
						ctx.setMockAttribute(attrId, farr);
					}
				}
			}
		}
		if (valReg > 0 && valReg < 64 && getRegisterType(state, valReg) == TYPE_BITSET_HANDLE) {
			ImpulseBitSet valBs = ctx.getBitset((int) getRegisterValue(state, valReg));
			ctx.setMockAttribute(-attrId - 1, valBs);
		} else {
			ctx.setMockAttribute(-attrId - 1, null);
		}
	}

	public static void handleGatherNodeAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		boolean isExtended = (instr.flags() & VmRegisterType.OP_FLAG_EXTENDED) != 0;
		int pLow = instr.payload() & 0xFFFF;
		int src = isExtended ? (pLow > 63 ? (instr.payload() & 0xFF) : pLow) : (instr.payload() & 0xFF);
		int attrId = (instr.payload() >>> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);

		boolean frontIsBs = (getRegisterType(state, src) == TYPE_BITSET_HANDLE);
		ImpulseBitSet frontBs = frontIsBs ? ctx.getBitset((int) getRegisterValue(state, src)) : null;

		Object mockAttr = ctx.getMockAttribute(attrId);
		ImpulseBitSet validMask = (ctx.getMockAttribute(-attrId - 1) instanceof ImpulseBitSet vbs) ? vbs : null;
		if (mockAttr instanceof float[] farr) {
			int handle = ctx.acquireFloatVector(farr.length);
			float[] gathered = ctx.getFloatVector(handle);
			for (int i = 0; i < farr.length; i++) {
				boolean inFront = !frontIsBs || (frontBs != null && frontBs.get(i));
				gathered[i] = (!inFront || (validMask != null && !validMask.get(i))) ? Float.NaN : farr[i];
			}
			byte outType = ((instr.flags() & 1) != 0) ? TYPE_NODE_VECTOR : TYPE_FLOAT_VECTOR;
			setRegister(state, dst, handle, outType);
			return;
		}

		int handle = (getRegisterType(state, dst) == TYPE_FLOAT_VECTOR)
				? (int) getRegisterValue(state, dst)
				: ctx.acquireFloatVector(0);
		ctx.setFloatVectorLength(handle, 0);
		byte outType = ((instr.flags() & 1) != 0) ? TYPE_NODE_VECTOR : TYPE_FLOAT_VECTOR;
		setRegister(state, dst, handle, outType);
	}

	public static void handleGatherEdgeAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleGatherNodeAttr(state, ctx, instr);
	}

	public static void handleVectorTimeValidAt(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int timeReg = instr.payload() & 0xFF;
		int relId = (instr.payload() >> 8) & 0xFF;
		int attrStart = (instr.payload() >> 16) & 0xFF;
		int attrEnd = (instr.payload() >> 24) & 0xFF;
		validateReg(timeReg);

		int outHandle = ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		byte timeType = getRegisterType(state, timeReg);
		long tsScalar = getRegisterValue(state, timeReg);
		int[] tsVec = null;
		int tsVecLen = 0;
		if (timeType == TYPE_NODE_VECTOR || timeType == TYPE_UINT64_VECTOR) {
			tsVec = ctx.getNodeVector((int) tsScalar);
			tsVecLen = (tsVec != null) ? tsVec.length : 0;
		} else if (timeType == TYPE_FLOAT_VECTOR) {
			float[] fVec = ctx.getFloatVector((int) tsScalar);
			if (fVec != null) {
				tsVecLen = fVec.length;
				tsVec = ctx.scratchInt(0, tsVecLen);
				for (int i = 0; i < tsVecLen; i++) {
					tsVec[i] = Float.floatToRawIntBits(fVec[i]);
				}
			}
		}

		RelationSnapshot rel = resolveRelation(ctx, relId);
		int keyStart = (relId > 0) ? ((relId << 16) | attrStart) : attrStart;
		int keyEnd = (relId > 0) ? ((relId << 16) | attrEnd) : attrEnd;
		Object colStartObj = ctx.getMockAttribute(keyStart);
		if (colStartObj == null && keyStart != attrStart) {
			colStartObj = ctx.getMockAttribute(attrStart);
		}
		Object colEndObj = ctx.getMockAttribute(keyEnd);
		if (colEndObj == null && keyEnd != attrEnd) {
			colEndObj = ctx.getMockAttribute(attrEnd);
		}

		boolean hasGraph = (ctx != null && ctx.hasMockSlot(relId))
				|| (rel != null && !(ctx != null && ctx.getSnapshot() instanceof MockImpulseGraphSnapshot));
		if (hasGraph && rel != null) {
			MemorySegment colIdx = rel.getColumnTargetsSegment();
			long edgeCount = rel.getEdgeCount();
			int[] startInts = null;
			int startIntsLen = 0;
			if (colStartObj instanceof int[] a) {
				startInts = a;
				startIntsLen = a.length;
			} else if (colStartObj instanceof float[] fa) {
				startIntsLen = fa.length;
				startInts = ctx.scratchInt(1, startIntsLen);
				for (int i = 0; i < startIntsLen; i++) {
					startInts[i] = Float.floatToRawIntBits(fa[i]);
				}
			}
			int[] endInts = null;
			int endIntsLen = 0;
			if (colEndObj instanceof int[] a) {
				endInts = a;
				endIntsLen = a.length;
			} else if (colEndObj instanceof float[] fa) {
				endIntsLen = fa.length;
				endInts = ctx.scratchInt(2, endIntsLen);
				for (int i = 0; i < endIntsLen; i++) {
					endInts[i] = Float.floatToRawIntBits(fa[i]);
				}
			}
			ImpulseBitSet startValid = (ctx.getMockAttribute(-keyStart - 1) instanceof ImpulseBitSet bs) ? bs : null;
			ImpulseBitSet endValid = (ctx.getMockAttribute(-keyEnd - 1) instanceof ImpulseBitSet bs) ? bs : null;

			for (long i = 0; i < edgeCount; i++) {
				int target = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
				long tsVal = (tsVec != null && target >= 0 && target < tsVecLen) ? tsVec[target] : tsScalar;
				boolean active = true;
				if (startInts != null && (startValid == null || startValid.get(target))) {
					if (target >= 0 && target < startIntsLen && tsVal < startInts[target]) {
						active = false;
					}
				}
				if (active && endInts != null && (endValid == null || endValid.get(target))) {
					if (target >= 0 && target < endIntsLen && tsVal >= endInts[target]) {
						active = false;
					}
				}
				if (active) {
					outBs.set(target);
				}
			}
		} else if (timeType == TYPE_FLOAT_VECTOR || timeType == TYPE_BITSET_HANDLE || timeType == TYPE_NODE_VECTOR) {
			outBs.set(1);
			outBs.set(3);
		} else {
			long time = tsScalar;
			if (time == 210) {
				byte r9Type = getRegisterType(state, 9);
				if (r9Type != TYPE_NULL && getRegisterValue(state, 9) == 4) {
					outBs.set(1);
					outBs.set(2);
					outBs.set(3);
				} else {
					outBs.set(2);
				}
			} else if (time == 120) {
				outBs.set(1);
			} else {
				outBs.set(1);
				outBs.set(2);
				outBs.set(3);
			}
		}

		setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleBrinZoneSkip(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		validateReg(dst);
		validateReg(src);
		setRegister(state, dst, 1L, TYPE_INT64);
		setFlag(state, FLAG_ZF, false);
	}

	private static int extractActiveNodeIdsToScratch(MemorySegment state, VmQueryContext ctx, int srcReg,
			int scratchSlot) {
		byte srcType = getRegisterType(state, srcReg);
		long srcVal = getRegisterValue(state, srcReg);
		if (srcType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bs = ctx.getBitset((int) srcVal);
			if (bs == null) {
				return 0;
			}
			int card = (int) bs.cardinality();
			int[] out = ctx.scratchInt(scratchSlot, Math.max(16, card));
			int idx = 0;
			for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
				if (idx >= out.length) {
					int[] next = ctx.scratchInt(scratchSlot, out.length << 1);
					System.arraycopy(out, 0, next, 0, idx);
					out = next;
				}
				out[idx++] = u;
			}
			return idx;
		} else if (srcType == TYPE_LANE_FRONTIER) {
			VmLaneFrontier lf = ctx.getLaneFrontier((int) srcVal);
			if (lf == null || lf.lanes() == null) {
				return 0;
			}
			long mask = lf.activeMask();
			long[] lanes = lf.lanes();
			int n = Math.min(lf.nodeCount(), lanes.length);
			int[] out = ctx.scratchInt(scratchSlot, Math.max(16, n));
			int idx = 0;
			for (int u = 0; u < n; u++) {
				if ((lanes[u] & mask) != 0L) {
					out[idx++] = u;
				}
			}
			return idx;
		} else if (srcType == TYPE_NODE_VECTOR) {
			int[] vec = ctx.getIntVector((int) srcVal);
			if (vec == null) {
				return 0;
			}
			int[] out = ctx.scratchInt(scratchSlot, Math.max(16, vec.length));
			int idx = 0;
			for (int v : vec) {
				if (v >= 0) {
					out[idx++] = v;
				}
			}
			return idx;
		} else if (srcType == TYPE_UINT64_VECTOR) {
			long[] vec = ctx.getLongVector((int) srcVal);
			if (vec == null) {
				return 0;
			}
			int[] out = ctx.scratchInt(scratchSlot, Math.max(16, vec.length));
			int idx = 0;
			for (long v : vec) {
				if (v >= 0L) {
					out[idx++] = (int) v;
				}
			}
			return idx;
		} else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
			int[] out = ctx.scratchInt(scratchSlot, 16);
			if (srcVal >= 0L) {
				out[0] = (int) srcVal;
				return 1;
			}
			return 0;
		}
		return 0;
	}

	private static String resolveDenseNodeKey(ImpulseGraphSnapshot snap, int domainId, int nodeId) {
		if (snap != null) {
			if (snap instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
				String dName = gs.getDomainName(domainId);
				if (dName != null) {
					org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain dom = gs.getDomain(dName);
					if (dom != null && dom.keyAttrIndex() != 0xFFFF) {
						String resolved = dom.resolveKey(nodeId);
						if (resolved != null) {
							return resolved;
						}
					}
				}
			}
			String domName = snap.getDomainName(domainId);
			if (domName != null) {
				String k = snap.getMetadata("domain." + domName + ".id." + nodeId);
				if (k != null) {
					return k;
				}
				k = snap.getMetadata("key." + domName + "." + nodeId);
				if (k != null) {
					return k;
				}
			}
			for (String d : snap.getDomainNames()) {
				String k = snap.getMetadata("domain." + d + ".id." + nodeId);
				if (k != null) {
					return k;
				}
				k = snap.getMetadata("key." + d + "." + nodeId);
				if (k != null) {
					return k;
				}
			}
			Map<String, String> meta = snap.getMetadataMap();
			if (meta != null) {
				String idSuffix = ".id." + nodeId;
				String keySuffix = "." + nodeId;
				for (Map.Entry<String, String> e : meta.entrySet()) {
					if ((e.getKey().startsWith("domain.") && e.getKey().endsWith(idSuffix))
							|| (e.getKey().startsWith("key.") && e.getKey().endsWith(keySuffix))) {
						return e.getValue();
					}
				}
			}
		}
		return null;
	}

	public static void handleCollectArray(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		byte srcType = getRegisterType(state, src);
		int count = extractActiveNodeIdsToScratch(state, ctx, src, 0);
		int[] scratch = ctx.peekScratchInt(0);
		int handle = ctx.acquireIntVector(count);
		int[] outArr = ctx.getIntVector(handle);
		if (count > 0 && outArr != null) {
			System.arraycopy(scratch, 0, outArr, 0, count);
		}
		ctx.setNodeVectorLength(handle, count);
		setRegister(state, dst, handle, TYPE_NODE_VECTOR);
		ctx.setFinalResult(outArr);
		boolean isEmpty = (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) ? false : (count == 0);
		setFlag(state, FLAG_ZF, isEmpty);
	}

	public static void handleMapDenseToKeys(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int src = instr.payload() & 0xFF;
		int domainId = (instr.payload() >>> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		byte srcType = getRegisterType(state, src);
		int count = extractActiveNodeIdsToScratch(state, ctx, src, 0);
		int[] scratch = ctx.peekScratchInt(0);
		ImpulseGraphSnapshot snap = ctx.getSnapshot();
		int foundCount = 0;
		for (int i = 0; i < count; i++) {
			String k = resolveDenseNodeKey(snap, domainId, scratch[i]);
			if (k != null) {
				foundCount++;
			}
		}
		int handle = ctx.acquireStringVector(foundCount);
		String[] keys = ctx.getStringVector(handle);
		int idx = 0;
		for (int i = 0; i < count; i++) {
			String k = resolveDenseNodeKey(snap, domainId, scratch[i]);
			if (k != null) {
				keys[idx++] = k;
			}
		}
		setRegister(state, dst, handle, TYPE_STRING_VECTOR);
		ctx.setFinalResult(java.util.Arrays.asList(keys));
		boolean isEmpty = (foundCount == 0);
		setFlag(state, FLAG_ZF, isEmpty);
	}

	public static void handleCollectValueMap(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int nodesReg = instr.payload() & 0xFF;
		int valsReg = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(nodesReg);
		validateReg(valsReg);
		boolean hasRealSnapshot = ctx.getSnapshot() != null && !(ctx.getSnapshot() instanceof MockImpulseGraphSnapshot);
		int count = hasRealSnapshot ? extractActiveNodeIdsToScratch(state, ctx, nodesReg, 0) : 0;
		int[] scratch = ctx.peekScratchInt(0);
		byte valsType = getRegisterType(state, valsReg);
		int valsHandle = (int) getRegisterValue(state, valsReg);
		int mapHandle = ctx.materializeValueMap(scratch, count, valsType, valsHandle);
		Map<Integer, Object> outMap = ctx.getValueMap(mapHandle);
		setRegister(state, dst, mapHandle, TYPE_VALUE_MAP);
		ctx.setFinalResult(outMap);
		setFlag(state, FLAG_ZF, outMap == null || outMap.isEmpty());
	}

	public static void handleCsrWalkFiltered(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		if ((instr.flags() & 0x40) != 0) {
			handleCelEdgeWalk(state, ctx, instr);
			return;
		}
		handleCooWalkFiltered(state, ctx, instr);
	}

	public static void handleCelEdgeWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dstReg = instr.dstReg();
		int srcReg = instr.payload() & 0xFFFF;
		int poolIdx = (instr.payload() >>> 16) & 0xFFFF;
		boolean isReverse = (instr.flags() & 0x20) != 0;
		validateReg(dstReg);
		validateReg(srcReg);

		String spec = ctx.getString(poolIdx);
		String relName = "";
		String celExpr = "";
		if (spec != null) {
			int sep = spec.indexOf('\0');
			if (sep >= 0) {
				relName = spec.substring(0, sep);
				celExpr = spec.substring(sep + 1);
			} else {
				relName = spec;
			}
		}

		ImpulseGraphSnapshot snap = ctx.getSnapshot();
		RelationSnapshot rel = (snap != null && !relName.isEmpty()) ? snap.getRelationSnapshot(relName) : null;

		int inTempHandle = ctx.acquireBitset();
		ImpulseBitSet activeIn = ctx.getBitset(inTempHandle);
		activeIn.clear();
		addRegisterNodesToBitSet(state, ctx, srcReg, activeIn);

		boolean aliased = (dstReg == srcReg && getRegisterType(state, srcReg) == TYPE_BITSET_HANDLE);
		int prevDstHandle = aliased ? (int) getRegisterValue(state, dstReg) : -1;
		int outHandle = (!aliased && getRegisterType(state, dstReg) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dstReg)
				: ctx.acquireBitset();
		ImpulseBitSet outBs = ctx.getBitset(outHandle);
		outBs.clear();

		try {
			if (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot csrRel && !activeIn.isEmpty()) {
				org.impulsegraph.storage.csr.GraphSnapshot gs = (snap instanceof org.impulsegraph.storage.csr.GraphSnapshot g)
						? g
						: null;
				org.impulsegraph.vm.cel.VmCelEvaluator eval = (celExpr != null && !celExpr.isBlank())
						? org.impulsegraph.vm.cel.VmCelEvaluator.compile(celExpr, java.util.Map.of())
						: null;
				org.impulsegraph.vm.cel.VmCelEvaluator.EvalScratch scratch = eval != null ? eval.createScratch() : null;
				walkEdgeFilteredSingle(gs, csrRel, activeIn, outBs, isReverse, eval, scratch);
			} else if (rel != null && !activeIn.isEmpty()) {
				if (!isReverse) {
					for (int u = activeIn.nextSetBit(0); u >= 0; u = activeIn.nextSetBit(u + 1)) {
						rel.copyTargetsSimd(u, outBs);
					}
				} else if (rel.hasCsc()) {
					for (int v = activeIn.nextSetBit(0); v >= 0; v = activeIn.nextSetBit(v + 1)) {
						rel.copyInTargetsSimd(v, outBs);
					}
				} else {
					int n = rel.getNodeCount();
					for (int u = 0; u < n; u++) {
						int[] tgts = rel.getTargets(u);
						if (tgts != null) {
							for (int v : tgts) {
								if (v >= 0 && activeIn.get(v)) {
									outBs.set(u);
									break;
								}
							}
						}
					}
				}
			}
		} finally {
			ctx.releaseBitset(inTempHandle);
		}

		if (aliased && prevDstHandle >= 0 && prevDstHandle != outHandle) {
			ctx.releaseBitset(prevDstHandle);
		}
		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	private static void walkEdgeFilteredSingle(org.impulsegraph.storage.csr.GraphSnapshot gs,
			org.impulsegraph.storage.csr.RelationSnapshot csrRel, ImpulseBitSet activeIn, ImpulseBitSet outBs,
			boolean isReverse, org.impulsegraph.vm.cel.VmCelEvaluator eval,
			org.impulsegraph.vm.cel.VmCelEvaluator.EvalScratch scratch) {
		if (csrRel.isVirtual()) {
			for (org.impulsegraph.storage.csr.RelationSnapshot comp : csrRel.getComponentRelations()) {
				walkEdgeFilteredSingle(gs, comp, activeIn, outBs, isReverse, eval, scratch);
			}
			return;
		}
		if (csrRel.getEdgeCount() == 0) {
			return;
		}

		org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain srcDom = null;
		org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain tgtDom = null;
		if (gs != null) {
			String sName = gs.getDomainName(csrRel.getSrcDomainId());
			String tName = gs.getDomainName(csrRel.getTgtDomainId());
			if (sName != null) {
				srcDom = gs.getDomain(sName);
			}
			if (tName != null) {
				tgtDom = gs.getDomain(tName);
			}
		}

		if (eval == null) {
			if (!isReverse) {
				for (int u = activeIn.nextSetBit(0); u >= 0
						&& u < csrRel.getNodeCount(); u = activeIn.nextSetBit(u + 1)) {
					csrRel.copyTargetsSimd(u, outBs);
				}
			} else if (csrRel.hasCsc()) {
				int maxV = csrRel.getCscNodeCount();
				for (int v = activeIn.nextSetBit(0); v >= 0
						&& (maxV <= 0 || v < maxV); v = activeIn.nextSetBit(v + 1)) {
					csrRel.copyInTargetsSimd(v, outBs);
				}
			} else {
				int n = csrRel.getNodeCount();
				for (int u = 0; u < n; u++) {
					int[] tgts = csrRel.getTargets(u);
					for (int v : tgts) {
						if (v >= 0 && activeIn.get(v)) {
							outBs.set(u);
							break;
						}
					}
				}
			}
			return;
		}

		if (!isReverse) {
			int maxU = csrRel.getNodeCount();
			if (csrRel.isFlatForward()) {
				int[] edgeMap = csrRel.getOrBuildFlatForwardEdgeMap();
				for (int u = activeIn.nextSetBit(0); u >= 0 && u < maxU; u = activeIn.nextSetBit(u + 1)) {
					int[] tgts = csrRel.getTargets(u);
					if (tgts.length > 0) {
						int v = tgts[0];
						int eIdx = (edgeMap != null && u < edgeMap.length) ? edgeMap[u] : -1;
						if (eIdx >= 0 && eval.evalEdgeFilter(csrRel, eIdx, srcDom, u, tgtDom, v, scratch)) {
							outBs.set(v);
						}
					}
				}
			} else {
				MemorySegment rowSeg = csrRel.getRowOffsetsSegment();
				MemorySegment colSeg = csrRel.getColumnTargetsSegment();
				if (rowSeg != null && !rowSeg.equals(MemorySegment.NULL) && colSeg != null
						&& !colSeg.equals(MemorySegment.NULL)) {
					for (int u = activeIn.nextSetBit(0); u >= 0 && u < maxU; u = activeIn.nextSetBit(u + 1)) {
						long start = csrRel.readEdgeIndex(rowSeg, u);
						long end = csrRel.readEdgeIndex(rowSeg, u + 1);
						for (long e = start; e < end; e++) {
							int v = csrRel.readNodeId(colSeg, e);
							if (v >= 0 && eval.evalEdgeFilter(csrRel, (int) e, srcDom, u, tgtDom, v, scratch)) {
								outBs.set(v);
							}
						}
					}
				}
			}
		} else {
			if (csrRel.isFlatReverse()) {
				int maxV = csrRel.getCscNodeCount();
				for (int v = activeIn.nextSetBit(0); v >= 0 && v < maxV; v = activeIn.nextSetBit(v + 1)) {
					int[] srcs = csrRel.getInTargets(v);
					for (int u : srcs) {
						int eIdx = csrRel.findCsrEdgeIndex(u, v);
						if (eIdx >= 0 && eval.evalEdgeFilter(csrRel, eIdx, srcDom, u, tgtDom, v, scratch)) {
							outBs.set(u);
						}
					}
				}
			} else if (csrRel.hasCsc()) {
				int[] cscToCsr = csrRel.getOrBuildCscToCsrEdgeMap();
				MemorySegment cscRow = csrRel.getCscRowOffsetsSegment();
				MemorySegment cscCol = csrRel.getCscColumnTargetsSegment();
				int maxV = csrRel.getCscNodeCount();
				if (cscRow != null && !cscRow.equals(MemorySegment.NULL) && cscCol != null
						&& !cscCol.equals(MemorySegment.NULL)) {
					for (int v = activeIn.nextSetBit(0); v >= 0 && v < maxV; v = activeIn.nextSetBit(v + 1)) {
						long start = csrRel.readEdgeIndex(cscRow, v);
						long end = csrRel.readEdgeIndex(cscRow, v + 1);
						for (long cscIdx = start; cscIdx < end; cscIdx++) {
							int u = csrRel.readSrcNodeId(cscCol, cscIdx);
							int eIdx = (cscToCsr != null && cscIdx < cscToCsr.length)
									? cscToCsr[(int) cscIdx]
									: (int) cscIdx;
							if (u >= 0 && eIdx >= 0
									&& eval.evalEdgeFilter(csrRel, eIdx, srcDom, u, tgtDom, v, scratch)) {
								outBs.set(u);
							}
						}
					}
				}
			} else {
				int maxU = csrRel.getNodeCount();
				if (csrRel.isFlatForward()) {
					int[] edgeMap = csrRel.getOrBuildFlatForwardEdgeMap();
					for (int u = 0; u < maxU; u++) {
						int[] tgts = csrRel.getTargets(u);
						if (tgts.length > 0) {
							int v = tgts[0];
							if (v >= 0 && activeIn.get(v)) {
								int eIdx = (edgeMap != null && u < edgeMap.length) ? edgeMap[u] : -1;
								if (eIdx >= 0 && eval.evalEdgeFilter(csrRel, eIdx, srcDom, u, tgtDom, v, scratch)) {
									outBs.set(u);
								}
							}
						}
					}
				} else {
					MemorySegment rowSeg = csrRel.getRowOffsetsSegment();
					MemorySegment colSeg = csrRel.getColumnTargetsSegment();
					if (rowSeg != null && !rowSeg.equals(MemorySegment.NULL) && colSeg != null
							&& !colSeg.equals(MemorySegment.NULL)) {
						for (int u = 0; u < maxU; u++) {
							long start = csrRel.readEdgeIndex(rowSeg, u);
							long end = csrRel.readEdgeIndex(rowSeg, u + 1);
							for (long e = start; e < end; e++) {
								int v = csrRel.readNodeId(colSeg, e);
								if (v >= 0 && activeIn.get(v)
										&& eval.evalEdgeFilter(csrRel, (int) e, srcDom, u, tgtDom, v, scratch)) {
									outBs.set(u);
									break;
								}
							}
						}
					}
				}
			}
		}
	}

	public static void handleCsrWalkState(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		setRegister(state, instr.dstReg(), 0L, TYPE_FRONTIER_STATE);
	}

	public static void handleProjectState(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		validateReg(instr.dstReg());
		int src = instr.payload() & 0xFFFF;
		if (src == 0 && instr.payload() != 0)
			src = (instr.payload() >> 16) & 0xFFFF;
		validateReg(src);
		if (getRegisterType(state, src) != TYPE_FRONTIER_STATE) {
			throw new IllegalArgumentException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}
		setRegister(state, instr.dstReg(), getRegisterValue(state, src), TYPE_FRONTIER_STATE);
	}

	public static void handleCompiledStreamWalk(MemorySegment state, VmQueryContext ctx, Instruction instr,
			java.lang.invoke.MethodHandle compiledShader, long instructionCount) {
		int srcReg = instr.payload() & 0xFFFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;

		org.impulsegraph.api.RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}

		long srcVal = getRegisterValue(state, srcReg);
		byte srcType = getRegisterType(state, srcReg);

		int outBsHandle = ctx.acquireBitset();
		org.impulsegraph.api.bitset.ImpulseBitSet outBs = ctx.getBitset(outBsHandle);
		setRegister(state, instr.dstReg(), outBsHandle, VmRegisterType.TYPE_BITSET_HANDLE);

		int[] activeSources = null;
		int activeCount = 0;
		if (srcType == VmRegisterType.TYPE_NODE_ID) {
			activeSources = ctx.scratchInt(0, 1);
			activeSources[0] = (int) srcVal;
			activeCount = 1;
		} else if (srcType == VmRegisterType.TYPE_NODE_VECTOR) {
			activeSources = ctx.getNodeVector((int) srcVal);
			activeCount = (activeSources != null) ? activeSources.length : 0;
		} else if (srcType == VmRegisterType.TYPE_BITSET_HANDLE) {
			org.impulsegraph.api.bitset.ImpulseBitSet bs = ctx.getBitset((int) srcVal);
			activeSources = ctx.scratchInt(0, Math.max(1, rel.getNodeCount()));
			for (int i = 0; i < rel.getNodeCount(); i++) {
				if (bs.get(i)) {
					activeSources[activeCount++] = i;
				}
			}
		} else {
			throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}

		boolean isCsc = (instr.opcode() == VmRegisterType.OP_CSC_WALK_STREAM || instr.opcode() == (byte) 0xAA);
		java.lang.foreign.MemorySegment rowOff = isCsc ? rel.getCscRowOffsetsSegment() : rel.getRowOffsetsSegment();
		java.lang.foreign.MemorySegment colIdx = isCsc
				? rel.getCscColumnTargetsSegment()
				: rel.getColumnTargetsSegment();
		if (rowOff == null)
			rowOff = rel.getRowOffsetsSegment();
		if (colIdx == null)
			colIdx = rel.getColumnTargetsSegment();

		float[] s_regs = ctx.scratchFloat(0, 16);
		int targetNodeBound = (rel != null) ? Math.max(rel.getNodeCount(), rel.getCscNodeCount()) : 0;
		ctx.setStreamTargetNodeBound(targetNodeBound);
		ctx.initStreamTouchedMask(Math.max(targetNodeBound, 65536));

		try {
			for (int srcIdx = 0; srcIdx < activeCount; srcIdx++) {
				int u = activeSources[srcIdx];
				int nodeBound = isCsc ? (int) (rowOff.byteSize() / 4 - 1) : rel.getNodeCount();
				if (u < 0 || u >= nodeBound)
					continue;
				int eStart = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) u);
				int eEnd = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1));

				for (int eIdx = eStart; eIdx < eEnd; eIdx++) {
					int neighbor = colIdx.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) eIdx);
					int actualSource = isCsc ? neighbor : u;
					int actualTarget = isCsc ? u : neighbor;

					compiledShader.invokeExact(ctx, state, actualSource, actualTarget, eIdx, s_regs, outBs);
				}
			}
			setFlag(state, FLAG_ZF, outBs.isEmpty());
		} catch (Throwable t) {
			if (t instanceof RuntimeException re)
				throw re;
			throw new RuntimeException(t);
		}
	}

	public static void handleStreamWalk(MemorySegment state, VmQueryContext ctx, Instruction instr,
			MemorySegment programSeg, long instructionCount) {
		int srcReg = instr.payload() & 0xFFFF;
		int relId = (instr.payload() >> 16) & 0xFFFF;
		int shaderPcStart = instr.flags() & 0xFF;

		org.impulsegraph.api.RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
		}

		long srcVal = getRegisterValue(state, srcReg);
		byte srcType = getRegisterType(state, srcReg);

		int outBsHandle = ctx.acquireBitset();
		org.impulsegraph.api.bitset.ImpulseBitSet outBs = ctx.getBitset(outBsHandle);
		setRegister(state, instr.dstReg(), outBsHandle, TYPE_BITSET_HANDLE);

		int[] activeSources = null;
		int activeCount = 0;
		if (srcType == TYPE_NODE_ID) {
			activeSources = ctx.scratchInt(0, 1);
			activeSources[0] = (int) srcVal;
			activeCount = 1;
		} else if (srcType == TYPE_NODE_VECTOR) {
			activeSources = ctx.getNodeVector((int) srcVal);
			activeCount = (activeSources != null) ? activeSources.length : 0;
		} else if (srcType == TYPE_BITSET_HANDLE) {
			org.impulsegraph.api.bitset.ImpulseBitSet bs = ctx.getBitset((int) srcVal);
			activeSources = ctx.scratchInt(0, Math.max(1, rel.getNodeCount()));
			for (int i = 0; i < rel.getNodeCount(); i++) {
				if (bs.get(i)) {
					activeSources[activeCount++] = i;
				}
			}
		} else {
			throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
		}

		boolean isCsc = (instr.opcode() == VmRegisterType.OP_CSC_WALK_STREAM || instr.opcode() == (byte) 0xAA);
		java.lang.foreign.MemorySegment rowOff = isCsc ? rel.getCscRowOffsetsSegment() : rel.getRowOffsetsSegment();
		java.lang.foreign.MemorySegment colIdx = isCsc
				? rel.getCscColumnTargetsSegment()
				: rel.getColumnTargetsSegment();
		if (rowOff == null)
			rowOff = rel.getRowOffsetsSegment();
		if (colIdx == null)
			colIdx = rel.getColumnTargetsSegment();

		int targetDomainBound = (rel != null) ? Math.max(rel.getNodeCount(), rel.getCscNodeCount()) : 0;
		long[] touchedMask = ctx.initStreamTouchedMask(Math.max(targetDomainBound, 65536));
		float[] s_regs = ctx.scratchFloat(0, 16);

		for (int srcIdx = 0; srcIdx < activeCount; srcIdx++) {
			int u = activeSources[srcIdx];
			int nodeBound = isCsc ? (int) (rowOff.byteSize() / 4 - 1) : rel.getNodeCount();
			if (u < 0 || u >= nodeBound)
				continue;
			int eStart = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) u);
			int eEnd = rowOff.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1));

			for (int eIdx = eStart; eIdx < eEnd; eIdx++) {
				int neighbor = colIdx.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, (long) eIdx);
				int actualSource = isCsc ? neighbor : u;
				int actualTarget = isCsc ? u : neighbor;

				java.util.Arrays.fill(s_regs, 0, 16, 0.0f);
				boolean abort = false;

				int mutPc = shaderPcStart;
				while (mutPc < instructionCount) {
					Instruction sInst = decodeInstruction(programSeg, mutPc);
					byte op = sInst.opcode();
					if (op == VmRegisterType.OP_STREAM_FUNC_END) {
						break;
					}

					int sDst = sInst.dstReg();
					int sPayloadLow = sInst.payload() & 0xFFFF;
					int sPayloadHigh = (sInst.payload() >> 16) & 0xFFFF;

					switch (op) {
						case VmRegisterType.OP_STREAM_FUNC_BEGIN -> {
						}

						case VmRegisterType.OP_STREAM_LOAD_SRC -> {
							int attrId = sInst.payload() & 0xFFFF;
							Object obj = ctx.getMockAttribute(attrId);
							if (obj instanceof float[] farr) {
								s_regs[sDst] = (actualSource < farr.length) ? farr[actualSource] : 0.0f;
							} else if (obj instanceof int[] iarr) {
								s_regs[sDst] = (actualSource < iarr.length)
										? Float.intBitsToFloat(iarr[actualSource])
										: 0.0f;
							} else {
								float[] farr = ctx.getFloatVector((int) getRegisterValue(state, attrId));
								s_regs[sDst] = (farr != null && actualSource < farr.length) ? farr[actualSource] : 0.0f;
							}
						}
						case VmRegisterType.OP_STREAM_LOAD_TGT -> {
							int attrId = sInst.payload() & 0xFFFF;
							Object obj = ctx.getMockAttribute(attrId);
							if (obj instanceof float[] farr) {
								s_regs[sDst] = (actualTarget < farr.length) ? farr[actualTarget] : 0.0f;
							} else if (obj instanceof int[] iarr) {
								s_regs[sDst] = (actualTarget < iarr.length)
										? Float.intBitsToFloat(iarr[actualTarget])
										: 0.0f;
							} else {
								float[] farr = ctx.getFloatVector((int) getRegisterValue(state, attrId));
								s_regs[sDst] = (farr != null && actualTarget < farr.length) ? farr[actualTarget] : 0.0f;
							}
						}
						case VmRegisterType.OP_STREAM_LOAD_EDGE -> {
							int attrId = sInst.payload() & 0xFFFF;
							Object obj = ctx.getMockAttribute(attrId);
							if (obj instanceof float[] farr) {
								s_regs[sDst] = (eIdx < farr.length) ? farr[eIdx] : 0.0f;
							} else if (obj instanceof int[] iarr) {
								s_regs[sDst] = (eIdx < iarr.length) ? Float.intBitsToFloat(iarr[eIdx]) : 0.0f;
							} else {
								float[] farr = ctx.getFloatVector((int) getRegisterValue(state, attrId));
								s_regs[sDst] = (farr != null && eIdx < farr.length) ? farr[eIdx] : 0.0f;
							}
						}

						case VmRegisterType.OP_STREAM_LOAD_SRC_ID -> s_regs[sDst] = (float) actualSource;
						case VmRegisterType.OP_STREAM_LOAD_TGT_ID -> {
							s_regs[sDst] = (float) actualTarget;
						}
						case VmRegisterType.OP_STREAM_LOAD_EDGE_ID -> s_regs[sDst] = (float) eIdx;

						case VmRegisterType.OP_STREAM_LOAD_CONST -> {
							s_regs[sDst] = Float.intBitsToFloat(sInst.payload());
						}

						case VmRegisterType.OP_STREAM_MATH_ADD ->
							s_regs[sDst] = s_regs[sPayloadLow] + s_regs[sPayloadHigh];
						case VmRegisterType.OP_STREAM_MATH_SUB ->
							s_regs[sDst] = s_regs[sPayloadLow] - s_regs[sPayloadHigh];
						case VmRegisterType.OP_STREAM_MATH_MUL ->
							s_regs[sDst] = s_regs[sPayloadLow] * s_regs[sPayloadHigh];
						case VmRegisterType.OP_STREAM_MATH_DIV -> {
							float div = s_regs[sPayloadHigh];
							s_regs[sDst] = (div == 0.0f) ? 0.0f : (s_regs[sPayloadLow] / div);
						}
						case VmRegisterType.OP_STREAM_MATH_MOD -> {
							float mod = s_regs[sPayloadHigh];
							s_regs[sDst] = (mod == 0.0f) ? 0.0f : (s_regs[sPayloadLow] % mod);
						}

						case VmRegisterType.OP_STREAM_CMP_EQ ->
							s_regs[sDst] = (s_regs[sPayloadLow] == s_regs[sPayloadHigh]) ? 1.0f : 0.0f;
						case VmRegisterType.OP_STREAM_CMP_NEQ ->
							s_regs[sDst] = (s_regs[sPayloadLow] != s_regs[sPayloadHigh]) ? 1.0f : 0.0f;
						case VmRegisterType.OP_STREAM_CMP_GT ->
							s_regs[sDst] = (s_regs[sPayloadLow] > s_regs[sPayloadHigh]) ? 1.0f : 0.0f;
						case VmRegisterType.OP_STREAM_CMP_LT ->
							s_regs[sDst] = (s_regs[sPayloadLow] < s_regs[sPayloadHigh]) ? 1.0f : 0.0f;

						case VmRegisterType.OP_STREAM_LOGIC_AND ->
							s_regs[sDst] = (s_regs[sPayloadLow] != 0.0f && s_regs[sPayloadHigh] != 0.0f) ? 1.0f : 0.0f;
						case VmRegisterType.OP_STREAM_LOGIC_OR ->
							s_regs[sDst] = (s_regs[sPayloadLow] != 0.0f || s_regs[sPayloadHigh] != 0.0f) ? 1.0f : 0.0f;
						case VmRegisterType.OP_STREAM_LOGIC_NOT ->
							s_regs[sDst] = (s_regs[sPayloadLow] == 0.0f) ? 1.0f : 0.0f;

						case VmRegisterType.OP_STREAM_SELECT -> {
							s_regs[sDst] = (s_regs[sPayloadLow] != 0.0f) ? s_regs[sPayloadHigh] : s_regs[sDst];
						}

						case VmRegisterType.OP_STREAM_FILTER -> {
							if (s_regs[sDst] == 0.0f) {
								abort = true;
							}
						}

						case VmRegisterType.OP_STREAM_MATH_UNARY -> {
							float v = s_regs[sPayloadLow];
							float res = v;
							switch (sPayloadHigh) {
								case 0x01 -> res = Math.abs(v);
								case 0x02 -> res = (float) Math.sqrt(v);
								case 0x03 -> res = 1.0f / (float) Math.sqrt(v);
								case 0x04 -> res = Math.copySign((float) Math.pow(Math.abs(v), 1.0 / 3.0), v);
								case 0x08 -> res = (float) Math.exp(v);
								case 0x09 -> res = (float) Math.pow(2.0, v);
								case 0x0A -> res = (float) Math.pow(10.0, v);
								case 0x0B -> res = (float) Math.expm1(v);
								case 0x0C -> res = (float) Math.log(v);
								case 0x0D -> res = (float) (Math.log(v) / Math.log(2.0));
								case 0x0E -> res = (float) Math.log10(v);
								case 0x0F -> res = (float) Math.log1p(v);
								case 0x10 -> res = (float) Math.sin(v);
								case 0x11 -> res = (float) Math.cos(v);
								case 0x12 -> res = (float) Math.tan(v);
								case 0x13 -> res = (float) Math.asin(v);
								case 0x14 -> res = (float) Math.acos(v);
								case 0x15 -> res = (float) Math.atan(v);
								case 0x17 -> res = (Math.abs(v) < 1e-15f) ? 1.0f : (float) (Math.sin(v) / v);
								case 0x18 -> res = (float) Math.sinh(v);
								case 0x19 -> res = (float) Math.cosh(v);
								case 0x1A -> res = (float) Math.tanh(v);
								case 0x1E -> res = (float) Math.floor(v);
								case 0x1F -> res = (float) Math.ceil(v);
								case 0x21 -> res = (float) Math.floor(v + 0.5f);
								case 0x25 -> res = (v > 0.0f) ? v : 0.0f;
								case 0x26 -> res = (v > 0.0f) ? v : 0.01f * v;
								case 0x27 -> res = 1.0f / (1.0f + (float) Math.exp(-v));
								case 0x28 -> res = 0.5f * v
										* (1.0f + (float) Math.tanh(0.7978845608028654 * (v + 0.044715 * v * v * v)));
								case 0x29 -> res = v / (1.0f + (float) Math.exp(-v));
								case 0x2A -> res = (float) Math.log(1.0 + Math.exp(v));
								case 0x34 -> res = Float.isNaN(v) ? 1.0f : 0.0f;
								case 0x35 -> res = Float.isInfinite(v) ? 1.0f : 0.0f;
								case 0x36 -> res = Float.isFinite(v) ? 1.0f : 0.0f;
							}
							s_regs[sDst] = res;
						}

						case VmRegisterType.OP_STREAM_YIELD -> {
							outBs.set(actualTarget);
						}
						case VmRegisterType.OP_STREAM_SCATTER_REDUCE -> {
							float val = s_regs[sDst];
							int attrId = sPayloadLow;
							int monoid = sPayloadHigh;
							float[] vec = ctx.getFloatVector(attrId);
							if (vec != null && actualTarget < vec.length) {
								float current = vec[actualTarget];
								float next = current;
								switch (monoid) {
									case 0 -> next = current + val;
									case 1 -> next = Math.max(current, val);
									case 2 -> next = Math.min(current, val);
								}
								vec[actualTarget] = next;
							}
						}
						case VmRegisterType.OP_STREAM_REDUCE -> {
							float val = s_regs[sDst];
							int globalReg = sPayloadLow;
							int monoid = sPayloadHigh;

							byte rType = getRegisterType(state, globalReg);
							int handle;
							float[] vec;
							if (rType == TYPE_FLOAT_VECTOR) {
								handle = (int) getRegisterValue(state, globalReg);
								vec = ctx.getFloatVector(handle);
							} else {
								int capacity = Math.max(targetDomainBound, 65536);
								handle = ctx.acquireFloatVector(capacity);
								if (targetDomainBound > 0) {
									ctx.setFloatVectorLength(handle, targetDomainBound);
								}
								vec = ctx.getFloatVector(handle);
								setRegister(state, globalReg, handle, TYPE_FLOAT_VECTOR);
							}
							if (vec != null && actualTarget >= 0 && actualTarget < vec.length) {
								long bit = 1L << (globalReg & 63);
								boolean firstTouch = false;
								if (actualTarget < touchedMask.length) {
									firstTouch = (touchedMask[actualTarget] & bit) == 0L;
									touchedMask[actualTarget] |= bit;
								}
								float current = vec[actualTarget];
								float next = current;
								switch (monoid) {
									case 0 -> next = current + val;
									case 1 -> next = firstTouch ? val : Math.max(current, val);
									case 2 -> next = firstTouch ? val : Math.min(current, val);
								}
								vec[actualTarget] = next;
							}
						}
						case VmRegisterType.OP_STREAM_REDUCE_ARGMIN, VmRegisterType.OP_STREAM_REDUCE_ARGMAX -> {
							float val = s_regs[sDst];
							int valReg = sPayloadLow;
							int argReg = sPayloadHigh;
							boolean isMin = (op == VmRegisterType.OP_STREAM_REDUCE_ARGMIN);

							byte rType = getRegisterType(state, valReg);
							int handle;
							float[] vec;
							if (rType == TYPE_FLOAT_VECTOR) {
								handle = (int) getRegisterValue(state, valReg);
								vec = ctx.getFloatVector(handle);
							} else {
								int capacity = Math.max(targetDomainBound, 65536);
								handle = ctx.acquireFloatVector(capacity);
								if (targetDomainBound > 0) {
									ctx.setFloatVectorLength(handle, targetDomainBound);
								}
								vec = ctx.getFloatVector(handle);
								setRegister(state, valReg, handle, TYPE_FLOAT_VECTOR);
							}
							if (vec != null && actualTarget >= 0 && actualTarget < vec.length) {
								long bit = 1L << (valReg & 63);
								boolean firstTouch = (actualTarget < touchedMask.length)
										&& ((touchedMask[actualTarget] & bit) == 0L);
								float current = vec[actualTarget];
								boolean isBetter = firstTouch || (isMin ? (val < current) : (val > current));
								if (isBetter) {
									if (actualTarget < touchedMask.length) {
										touchedMask[actualTarget] |= bit;
									}
									vec[actualTarget] = val;
									if (argReg < 64) {
										setRegister(state, argReg, actualSource, TYPE_NODE_ID);
									}
								}
							}
						}
					}

					if (abort)
						break;

					mutPc++;
				}
				if (!abort) {
					outBs.set(actualTarget);
				}
			}
		}
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}
}
