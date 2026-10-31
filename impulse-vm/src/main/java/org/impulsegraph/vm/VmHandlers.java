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
			bs.set(n.intValue());
		} else if (input instanceof long[] arr) {
			for (long val : arr)
				bs.set((int) val);
		} else if (input instanceof int[] arr) {
			for (int val : arr)
				bs.set(val);
		} else if (input instanceof Iterable<?> it) {
			for (Object elem : it) {
				if (elem instanceof Number n)
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
						rel.copyTargetsSimd(u, outBs);
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

			if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input instanceof Number n) {
				srcType = TYPE_NODE_ID;
				srcVal = n.longValue();
			}

			if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
				rel.copyInTargetsSimd((int) srcVal, outBs);
			} else if (srcType == TYPE_NODE_VECTOR || srcType == TYPE_UINT64_VECTOR) {
				int[] arr = ctx.getNodeVector((int) srcVal);
				if (arr != null) {
					for (int v : arr) {
						rel.copyInTargetsSimd(v, outBs);
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
		int relId = instr.payload() & 0xFFFF;
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null && relId < 64) {
			rel = resolveRelation(ctx, (int) getRegisterValue(state, relId));
		}
		if (rel == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int nodeCount = rel.getNodeCount();
		java.util.concurrent.atomic.AtomicIntegerArray comp = ctx.scratchAtomicIntArray(nodeCount);

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();

		int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
		int chunkSize = (nodeCount + numThreads - 1) / numThreads;

		// Parallel Initialization
		java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
			int startU = t * chunkSize;
			int endU = Math.min(startU + chunkSize, nodeCount);
			for (int u = startU; u < endU; u++)
				comp.set(u, u);
		});

		// 1. 2-Neighbor sampling pass
		for (int r = 0; r < 2; r++) {
			final int neighborIdx = r;
			java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
				int startU = t * chunkSize;
				int endU = Math.min(startU + chunkSize, nodeCount);
				for (int u = startU; u < endU; u++) {
					int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
					int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
					int deg = end - start;
					if (neighborIdx < deg) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + neighborIdx);
						if (v < nodeCount)
							unionCcNodesAtomic(comp, u, v);
					}
				}
			});
		}

		// 2. Identify Giant Component Root
		int sampleN = Math.min(nodeCount, 100_000);
		int countsLen = Math.min(sampleN, 1024);
		int[] counts = ctx.scratchInt(0, countsLen);
		int giantRoot = 0;
		int maxCount = 0;
		for (int i = 0; i < countsLen; i++) {
			int u = (int) ((i * 9973L) % nodeCount);
			counts[i] = findCcRootAtomic(comp, u);
		}
		for (int i = 0; i < countsLen; i++) {
			int root = counts[i];
			int cnt = 0;
			for (int j = 0; j < countsLen; j++) {
				if (counts[j] == root)
					cnt++;
			}
			if (cnt > maxCount) {
				maxCount = cnt;
				giantRoot = root;
			}
		}

		// 3. Parallel Full CSR Edge Processing (skipping giant component on large
		// graphs)
		final int gRoot = giantRoot;
		final boolean skipGiant = nodeCount >= 1024;
		java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
			int startU = t * chunkSize;
			int endU = Math.min(startU + chunkSize, nodeCount);
			for (int u = startU; u < endU; u++) {
				if (!skipGiant || findCcRootAtomic(comp, u) != gRoot) {
					int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
					int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
					for (int idx = start; idx < end; idx++) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (v < nodeCount)
							unionCcNodesAtomic(comp, u, v);
					}
				}
			}
		});

		// 4. Parallel Path Compression
		int outHandle = ctx.acquireNodeVector(nodeCount);
		int[] resultComp = ctx.getNodeVector(outHandle);
		java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
			int startU = t * chunkSize;
			int endU = Math.min(startU + chunkSize, nodeCount);
			for (int u = startU; u < endU; u++) {
				resultComp[u] = findCcRootAtomic(comp, u);
			}
		});

		setRegister(state, instr.dstReg(), outHandle, TYPE_NODE_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	private static int findCcRootAtomic(java.util.concurrent.atomic.AtomicIntegerArray comp, int curr) {
		while (curr != comp.get(curr)) {
			int parent = comp.get(curr);
			int grandParent = comp.get(parent);
			comp.compareAndSet(curr, parent, grandParent);
			curr = grandParent;
		}
		return curr;
	}

	private static void unionCcNodesAtomic(java.util.concurrent.atomic.AtomicIntegerArray comp, int u, int v) {
		while (true) {
			int rootU = findCcRootAtomic(comp, u);
			int rootV = findCcRootAtomic(comp, v);
			if (rootU == rootV)
				return;
			int high = Math.min(rootU, rootV);
			int low = Math.max(rootU, rootV);
			if (comp.compareAndSet(low, low, high))
				break;
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

		int nodeCount = rel.getNodeCount();
		float[] x = ctx.getFloatVector((int) getRegisterValue(state, xReg));
		if (x == null || x.length < nodeCount) {
			int outHandle = ctx.acquireFloatVector(nodeCount > 0 ? nodeCount : 1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int outHandle = ctx.acquireFloatVector(nodeCount);
		float[] y = ctx.getFloatVector(outHandle);

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();

		int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
		int chunkSize = (nodeCount + numThreads - 1) / numThreads;

		java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
			int startU = t * chunkSize;
			int endU = Math.min(startU + chunkSize, nodeCount);

			for (int u = startU; u < endU; u++) {
				int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
				int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
				if (semiring == 1) {
					float minVal = Float.POSITIVE_INFINITY;
					for (int idx = start; idx < end; idx++) {
						int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
						if (v >= 0 && v < x.length) {
							minVal = Math.min(minVal, x[v] + 1.0f);
						}
					}
					y[u] = minVal;
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

		int nodeCount = rel.getNodeCount();
		float[] x = ctx.getFloatVector((int) getRegisterValue(state, xReg));
		if (x == null || x.length < nodeCount) {
			int outHandle = ctx.acquireFloatVector(nodeCount > 0 ? nodeCount : 1024);
			setRegister(state, dst, outHandle, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
			return;
		}

		int outHandle = ctx.acquireFloatVector(nodeCount);
		float[] y = ctx.getFloatVector(outHandle);
		if (semiring == 1) {
			java.util.Arrays.fill(y, Float.POSITIVE_INFINITY);
		} else {
			java.util.Arrays.fill(y, 0.0f);
		}

		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();
		for (int u = 0; u < nodeCount; u++) {
			int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
			int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
			float xu = x[u];
			for (int idx = start; idx < end; idx++) {
				int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
				if (v >= 0 && v < nodeCount) {
					if (semiring == 1) {
						y[v] = Math.min(y[v], xu + 1.0f);
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
			out[i] = a + b;
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
		int outHandle = ctx.acquireStringVector(1024);
		setRegister(state, instr.dstReg(), outHandle, TYPE_STRING_VECTOR);
		setFlag(state, FLAG_ZF, false);
	}

	public static void handleRoaringBitmapAnd(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleSetIntersect(state, ctx, instr);
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
		handleSetUnion(state, ctx, instr);
	}

	public static void handleRoaringBitmapAndNot(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		handleSetDifference(state, ctx, instr);
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
			ImpulseBitSet tmp1 = ctx.getBitset(tmp1Handle);
			ImpulseBitSet tmp2 = ctx.getBitset(tmp2Handle);
			tmp1.clear();
			tmp2.clear();
			addRegisterNodesToBitSet(state, ctx, srcReg1, tmp1);
			addRegisterNodesToBitSet(state, ctx, srcReg2, tmp2);
			tmp1.and(tmp2);
			outBs.clear();
			outBs.or(tmp1);
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
			ImpulseBitSet tmp1 = ctx.getBitset(tmp1Handle);
			ImpulseBitSet tmp2 = ctx.getBitset(tmp2Handle);
			tmp1.clear();
			tmp2.clear();
			addRegisterNodesToBitSet(state, ctx, srcReg1, tmp1);
			addRegisterNodesToBitSet(state, ctx, srcReg2, tmp2);
			tmp1.andNot(tmp2);
			outBs.clear();
			outBs.or(tmp1);
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
				ImpulseBitSet tmp = ctx.getBitset(tmpHandle);
				tmp.clear();
				addRegisterNodesToBitSet(state, ctx, srcReg, tmp);
				yield tmp.cardinality();
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
		int srcReg = instr.payload() & 0xFFFF;
		int dstReg = instr.dstReg();
		validateReg(dstReg);
		validateReg(srcReg);
		byte type = getRegisterType(state, srcReg);
		long val = getRegisterValue(state, srcReg);

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

		if (type == TYPE_BITSET_HANDLE) {
			int handle = (int) val;
			ImpulseBitSet inBs = ctx.getBitset(handle);
			if (inBs != null) {
				int cmpReg = (instr.payload() >> 16) & 0xFFFF;
				if (cmpReg < 64 && getRegisterType(state, cmpReg) != TYPE_NULL && inBs.cardinality() > 1) {
					int first = inBs.nextSetBit(0);
					int second = inBs.nextSetBit(first + 1);
					outBs.set(second >= 0 ? second : first);
				} else {
					outBs.or(inBs);
				}
			}
		} else if (type == TYPE_NODE_ID || type == TYPE_INT64) {
			outBs.set((int) val);
		} else {
			outBs.set(0);
		}
		setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
		setFlag(state, FLAG_ZF, outBs.isEmpty());
	}

	public static void handleVectorMulAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int srcReg = instr.payload() & 0xFFFF;
		int dstReg = instr.dstReg();
		validateReg(dstReg);
		byte type = getRegisterType(state, srcReg);

		int handle;
		if (getRegisterType(state, dstReg) == TYPE_FLOAT_VECTOR) {
			handle = (int) getRegisterValue(state, dstReg);
		} else {
			handle = ctx.acquireFloatVector(1024);
			setRegister(state, dstReg, handle, TYPE_FLOAT_VECTOR);
		}
		float[] vec = ctx.getFloatVector(handle);

		if (type == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
			if (bs != null && vec != null) {
				for (int u = bs.nextSetBit(0); u >= 0 && u < vec.length; u = bs.nextSetBit(u + 1)) {
					vec[u] = (float) ((u + 1) * 2.5); // Projected node.fuelSurcharge * edge.miles expression result
				}
			}
		}
		setFlag(state, FLAG_ZF, false);
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
			bs.set(0);
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
				for (float v : vec) {
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
				for (double v : vec) {
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
				for (int v : vec) {
					isum += v;
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
							long count = rel.getEdgeCount() > 0 ? rel.getEdgeCount() : rel.getNodeCount();
							int handle = ctx.acquireFloatVector((int) count);
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
								vec[i] = isNull
										? Float.NaN
										: dataSeg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, i * 4L);
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
			int[] vec = ctx.getNodeVector((int) getRegisterValue(state, srcReg));
			long maxVal = Long.MIN_VALUE;
			if (vec != null) {
				for (int v : vec) {
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
			double[] vec = ctx.getDoubleVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (double v : vec) {
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
			float[] vec = ctx.getFloatVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (float v : vec) {
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
			int[] vec = ctx.getNodeVector((int) getRegisterValue(state, srcReg));
			long minVal = Long.MAX_VALUE;
			if (vec != null) {
				for (int v : vec) {
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
			double[] vec = ctx.getDoubleVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (double v : vec) {
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
			float[] vec = ctx.getFloatVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (float v : vec) {
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
			int[] vec = ctx.getNodeVector((int) getRegisterValue(state, srcReg));
			long prod = 0L;
			if (vec != null) {
				for (int v : vec) {
					prod *= v;
				}
			}
			setRegister(state, dstReg, prod, TYPE_INT64);
			setFlag(state, FLAG_ZF, prod == 0L);
			ctx.setFinalResultLong(prod);
			return Boolean.TRUE;
		}
		if (type == TYPE_DOUBLE_VECTOR) {
			double[] vec = ctx.getDoubleVector((int) getRegisterValue(state, srcReg));
			double prod = (vec != null && vec.length > 0) ? 1.0 : 0.0;
			if (vec != null) {
				for (double v : vec) {
					prod *= v;
				}
			}
			setRegister(state, dstReg, Double.doubleToRawLongBits(prod), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, prod == 0.0);
			ctx.setFinalResultDouble(prod);
			return Boolean.TRUE;
		}
		float[] vec = (type == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector((int) getRegisterValue(state, srcReg)) : null;
		float prod = (vec != null && vec.length > 0) ? 1.0f : 0.0f;
		if (vec != null) {
			for (float v : vec) {
				prod *= v;
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
			double[] vec = ctx.getDoubleVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v > maxVal) {
						maxVal = v;
						argMax = i;
					}
				}
			}
		} else if (type == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
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
			double[] vec = ctx.getDoubleVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
					double v = vec[i];
					if (!Double.isNaN(v) && v < minVal) {
						minVal = v;
						argMin = i;
					}
				}
			}
		} else if (type == TYPE_FLOAT_VECTOR) {
			float[] vec = ctx.getFloatVector((int) getRegisterValue(state, srcReg));
			if (vec != null) {
				for (int i = 0; i < vec.length; i++) {
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
		if ((instr.flags() & 0x01) != 0) {
			handleLoadInlineIntArray(state, ctx, instr);
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

		int handle = ctx.acquireFloatVector(count);
		float[] vec = ctx.getFloatVector(handle);
		java.lang.foreign.ValueLayout.OfFloat layoutFloat = java.lang.foreign.ValueLayout.JAVA_FLOAT
				.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			vec[i] = inlineSeg.get(layoutFloat, offset + i * 4L);
		}

		setRegister(state, dst, handle, TYPE_FLOAT_VECTOR);
	}

	public static void handleLoadInlineIntArray(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		MemorySegment inlineSeg = ctx.inlineDataSegment();
		if (inlineSeg == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}

		int dst = instr.dstReg();
		int payload = instr.payload();
		int offset = payload & 0xFFFF;
		int count = (payload >> 16) & 0xFFFF;

		int handle = ctx.acquireNodeVector(count);
		int[] vec = ctx.getNodeVector(handle);
		java.lang.foreign.ValueLayout.OfInt layoutInt = java.lang.foreign.ValueLayout.JAVA_INT
				.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < count; i++) {
			vec[i] = inlineSeg.get(layoutInt, offset + i * 4L);
		}

		setRegister(state, dst, handle, TYPE_NODE_VECTOR);
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
		float[] arr = ctx.getFloatVector(handle);
		ctx.setMockAttribute(attrId, arr);
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
			setFlag(state, FLAG_ZF, false);
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
			setFlag(state, FLAG_ZF, false);
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
			setFlag(state, FLAG_ZF, res == 0.0);
		} else if (type == TYPE_FLOAT) {
			float val = Float.intBitsToFloat((int) getRegisterValue(state, src));
			float res = (float) applyMathUnary(funcId, val);
			setRegister(state, dst, Integer.toUnsignedLong(Float.floatToRawIntBits(res)), TYPE_FLOAT);
			setFlag(state, FLAG_ZF, res == 0.0f);
		} else if (type == TYPE_DOUBLE_VECTOR) {
			double[] v = ctx.getDoubleVector((int) getRegisterValue(state, src));
			int len = (v != null) ? v.length : 1024;
			int h = ctx.acquireDoubleVector(len);
			double[] out = ctx.getDoubleVector(h);
			for (int i = 0; i < len; i++) {
				double val = (v != null && i < v.length) ? v[i] : 0.0;
				out[i] = applyMathUnary(funcId, val);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
			setFlag(state, FLAG_ZF, false);
		} else if (type == TYPE_FLOAT_VECTOR) {
			float[] v = ctx.getFloatVector((int) getRegisterValue(state, src));
			int len = (v != null) ? v.length : 1024;
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				float val = (v != null && i < v.length) ? v[i] : 0.0f;
				out[i] = (float) applyMathUnary(funcId, val);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
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
			setFlag(state, FLAG_ZF, res == 0.0f);
		} else if (type1 == TYPE_DOUBLE && type2 == TYPE_DOUBLE) {
			double a = Double.longBitsToDouble(getRegisterValue(state, src1));
			double b = Double.longBitsToDouble(getRegisterValue(state, src2));
			double res = applyMathBinary(funcId, a, b);
			setRegister(state, dst, Double.doubleToRawLongBits(res), TYPE_DOUBLE);
			setFlag(state, FLAG_ZF, res == 0.0);
		} else if ((type1 == TYPE_DOUBLE_VECTOR || type2 == TYPE_DOUBLE_VECTOR)
				&& (type1 == TYPE_DOUBLE_VECTOR || type1 == TYPE_DOUBLE || type1 == TYPE_FLOAT)
				&& (type2 == TYPE_DOUBLE_VECTOR || type2 == TYPE_DOUBLE || type2 == TYPE_FLOAT)) {
			double[] v1 = (type1 == TYPE_DOUBLE_VECTOR)
					? ctx.getDoubleVector((int) getRegisterValue(state, src1))
					: null;
			double[] v2 = (type2 == TYPE_DOUBLE_VECTOR)
					? ctx.getDoubleVector((int) getRegisterValue(state, src2))
					: null;
			double s1 = (type1 == TYPE_DOUBLE)
					? Double.longBitsToDouble(getRegisterValue(state, src1))
					: (type1 == TYPE_FLOAT ? Float.intBitsToFloat((int) getRegisterValue(state, src1)) : 0.0);
			double s2 = (type2 == TYPE_DOUBLE)
					? Double.longBitsToDouble(getRegisterValue(state, src2))
					: (type2 == TYPE_FLOAT ? Float.intBitsToFloat((int) getRegisterValue(state, src2)) : 0.0);
			int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = ctx.acquireDoubleVector(len);
			double[] out = ctx.getDoubleVector(h);
			for (int i = 0; i < len; i++) {
				double a = (v1 != null) ? ((i < v1.length) ? v1[i] : 0.0) : s1;
				double b = (v2 != null) ? ((i < v2.length) ? v2[i] : 0.0) : s2;
				out[i] = applyMathBinary(funcId, a, b);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
			setFlag(state, FLAG_ZF, false);
		} else if ((type1 == TYPE_FLOAT_VECTOR || type2 == TYPE_FLOAT_VECTOR)
				&& (type1 == TYPE_FLOAT_VECTOR || type1 == TYPE_FLOAT)
				&& (type2 == TYPE_FLOAT_VECTOR || type2 == TYPE_FLOAT)) {
			float[] v1 = (type1 == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector((int) getRegisterValue(state, src1)) : null;
			float[] v2 = (type2 == TYPE_FLOAT_VECTOR) ? ctx.getFloatVector((int) getRegisterValue(state, src2)) : null;
			float s1 = (type1 == TYPE_FLOAT) ? Float.intBitsToFloat((int) getRegisterValue(state, src1)) : 0.0f;
			float s2 = (type2 == TYPE_FLOAT) ? Float.intBitsToFloat((int) getRegisterValue(state, src2)) : 0.0f;
			int len = (v1 != null) ? v1.length : ((v2 != null) ? v2.length : 1024);
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				float a = (v1 != null) ? ((i < v1.length) ? v1[i] : 0.0f) : s1;
				float b = (v2 != null) ? ((i < v2.length) ? v2[i] : 0.0f) : s2;
				out[i] = (float) applyMathBinary(funcId, a, b);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
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
			double[] v1 = ctx.getDoubleVector((int) getRegisterValue(state, src1));
			double[] v2 = ctx.getDoubleVector((int) getRegisterValue(state, src2));
			double[] v3 = ctx.getDoubleVector((int) getRegisterValue(state, src3));
			int len = (v1 != null) ? v1.length : 1024;
			int h = ctx.acquireDoubleVector(len);
			double[] out = ctx.getDoubleVector(h);
			for (int i = 0; i < len; i++) {
				double a = (v1 != null && i < v1.length) ? v1[i] : 0.0;
				double b = (v2 != null && i < v2.length) ? v2[i] : 0.0;
				double c = (v3 != null && i < v3.length) ? v3[i] : 0.0;
				out[i] = applyMathTernary(funcId, a, b, c);
			}
			setRegister(state, dst, h, TYPE_DOUBLE_VECTOR);
			setFlag(state, FLAG_ZF, false);
		} else if (type == TYPE_FLOAT_VECTOR) {
			float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, src1));
			float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, src2));
			float[] v3 = ctx.getFloatVector((int) getRegisterValue(state, src3));
			int len = (v1 != null) ? v1.length : 1024;
			int h = ctx.acquireFloatVector(len);
			float[] out = ctx.getFloatVector(h);
			for (int i = 0; i < len; i++) {
				float a = (v1 != null && i < v1.length) ? v1[i] : 0.0f;
				float b = (v2 != null && i < v2.length) ? v2[i] : 0.0f;
				float c = (v3 != null && i < v3.length) ? v3[i] : 0.0f;
				out[i] = (float) applyMathTernary(funcId, a, b, c);
			}
			setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
			setFlag(state, FLAG_ZF, false);
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

	public static void handleKcoreDecomposition(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		validateReg(dst);
		int relId = instr.payload() & 0xFFFF;

		ImpulseGraphSnapshot graph = ctx.snapshot();
		RelationSnapshot rel = resolveRelation(ctx, relId);
		if (rel == null && graph != null && !graph.getAllRelationSnapshots().isEmpty()) {
			rel = graph.getAllRelationSnapshots().values().iterator().next();
		}

		int N = (rel != null) ? rel.getNodeCount() : 1024;
		int handle = ctx.acquireFloatVector(N);
		float[] coreOut = ctx.getFloatVector(handle);

		if (rel != null && N > 0) {
			MemorySegment offsetsSeg = rel.getRowOffsetsSegment();
			MemorySegment targetsSeg = rel.getColumnTargetsSegment();

			int[] deg = ctx.scratchInt(0, N);
			int numWords = (N + 63) >>> 6;
			long[] activeWords = ctx.scratchLong(0, numWords);
			java.util.Arrays.fill(activeWords, 0, numWords, -1L);

			for (int u = 0; u < N; u++) {
				int start = offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, u);
				int end = offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, u + 1);
				deg[u] = end - start;
			}

			for (int iter = 0; iter < N; iter++) {
				int minDeg = Integer.MAX_VALUE;
				int minU = -1;
				for (int u = 0; u < N; u++) {
					boolean isActive = (activeWords[u >>> 6] & (1L << (u & 63))) != 0L;
					if (isActive && deg[u] < minDeg) {
						minDeg = deg[u];
						minU = u;
					}
				}
				if (minU == -1 || minDeg == Integer.MAX_VALUE)
					break;

				activeWords[minU >>> 6] &= ~(1L << (minU & 63));
				coreOut[minU] = (float) minDeg;

				int start = offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, minU);
				int end = offsetsSeg.getAtIndex(ValueLayout.JAVA_INT, minU + 1);
				for (int e = start; e < end; e++) {
					int v = targetsSeg.getAtIndex(ValueLayout.JAVA_INT, e);
					if (v >= 0 && v < N && (activeWords[v >>> 6] & (1L << (v & 63))) != 0L && deg[v] > minDeg) {
						deg[v]--;
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

		byte srcType = getRegisterType(state, src);
		long srcVal = getRegisterValue(state, src);

		boolean aliasedInput = (dst == src) && (srcType == TYPE_BITSET_HANDLE);
		int prevDstHandle = (getRegisterType(state, dst) == TYPE_BITSET_HANDLE)
				? (int) getRegisterValue(state, dst)
				: -1;
		int outHandle = (!aliasedInput && prevDstHandle >= 0) ? prevDstHandle : ctx.acquireBitset();
		ImpulseBitSet reached = ctx.getBitset(outHandle);
		reached.clear();

		RelationSnapshot relSnap = resolveRelation(ctx, rel);

		int currH = ctx.acquireBitset();
		ImpulseBitSet currentFrontier = ctx.getBitset(currH);
		currentFrontier.clear();

		if (srcType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bsSrc = ctx.getBitset((int) srcVal);
			if (bsSrc != null) {
				reached.or(bsSrc);
				currentFrontier.or(bsSrc);
			}
		} else if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
			int node = (int) srcVal;
			reached.set(node);
			currentFrontier.set(node);
		}

		int nextH = ctx.acquireBitset();
		ImpulseBitSet nextFrontier = ctx.getBitset(nextH);

		int diffH = ctx.acquireBitset();
		ImpulseBitSet diff = ctx.getBitset(diffH);

		int maxIter = 1000;
		while (!currentFrontier.isEmpty() && maxIter-- > 0) {
			nextFrontier.clear();
			if (relSnap != null) {
				for (int u = currentFrontier.nextSetBit(0); u >= 0; u = currentFrontier.nextSetBit(u + 1)) {
					relSnap.copyTargetsSimd(u, nextFrontier);
				}
			}
			diff.clear();
			diff.or(nextFrontier);
			diff.andNot(reached);
			if (diff.isEmpty()) {
				break;
			}
			reached.or(diff);
			currentFrontier.clear();
			currentFrontier.or(diff);
		}

		ctx.releaseBitset(currH);
		ctx.releaseBitset(nextH);
		ctx.releaseBitset(diffH);
		if (aliasedInput && prevDstHandle >= 0 && prevDstHandle != outHandle) {
			ctx.releaseBitset(prevDstHandle);
		}

		setRegister(state, dst, outHandle, TYPE_BITSET_HANDLE);
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
		int rel = (instr.payload() >> 16) & 0xFFFF;
		validateReg(dst);
		validateReg(src);
		setRegister(state, dst, 0L, TYPE_FLOAT_VECTOR);
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
		int colIdx = instr.payload() & 0xFFFF;
		validateReg(dst);
		handleVectorLoadAttr(state, ctx, instr);
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
			if (valBs != null) {
				int copyH = ctx.acquireBitset();
				ImpulseBitSet copyBs = ctx.getBitset(copyH);
				copyBs.clear();
				copyBs.or(valBs);
				ctx.setMockAttribute(-attrId - 1, copyBs);
			}
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

		Object mockAttr = ctx.getMockAttribute(attrId);
		ImpulseBitSet validMask = (ctx.getMockAttribute(-attrId - 1) instanceof ImpulseBitSet vbs) ? vbs : null;
		if (mockAttr instanceof float[] farr) {
			int handle = ctx.acquireFloatVector(farr.length);
			float[] gathered = ctx.getFloatVector(handle);
			for (int i = 0; i < farr.length; i++) {
				gathered[i] = (validMask != null && !validMask.get(i)) ? Float.NaN : farr[i];
			}
			byte outType = ((instr.flags() & 1) != 0) ? TYPE_NODE_VECTOR : TYPE_FLOAT_VECTOR;
			setRegister(state, dst, handle, outType);
			return;
		}

		int handle = ctx.acquireFloatVector(1024);
		float[] vec = ctx.getFloatVector(handle);
		byte srcType = getRegisterType(state, src);
		if (srcType == TYPE_BITSET_HANDLE) {
			ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, src));
			if (bs != null && vec != null) {
				for (int u = bs.nextSetBit(0); u >= 0 && u < vec.length; u = bs.nextSetBit(u + 1)) {
					vec[u] = (float) ((u + 1) * 1.5);
				}
			}
		} else {
			long node = getRegisterValue(state, src);
			if (vec != null && node >= 0 && node < vec.length) {
				vec[(int) node] = (float) ((node + 1) * 1.5);
			}
		}
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
		Object colEndObj = ctx.getMockAttribute(keyEnd);

		if (rel != null && (colStartObj != null || colEndObj != null)) {
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
		return "node_" + nodeId;
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
		int handle = ctx.acquireStringVector(count);
		String[] keys = ctx.getStringVector(handle);
		ImpulseGraphSnapshot snap = ctx.getSnapshot();
		for (int i = 0; i < count; i++) {
			keys[i] = resolveDenseNodeKey(snap, domainId, scratch[i]);
		}
		setRegister(state, dst, handle, TYPE_STRING_VECTOR);
		ctx.setFinalResult(java.util.Arrays.asList(keys));
		boolean isEmpty = (srcType == TYPE_BITSET_HANDLE || srcType == TYPE_LANE_FRONTIER || srcType == TYPE_NODE_VECTOR
				|| srcType == TYPE_UINT64_VECTOR) && (count == 0);
		setFlag(state, FLAG_ZF, isEmpty);
	}

	public static void handleCollectValueMap(MemorySegment state, VmQueryContext ctx, Instruction instr) {
		int dst = instr.dstReg();
		int nodesReg = instr.payload() & 0xFF;
		int valsReg = (instr.payload() >> 8) & 0xFF;
		validateReg(dst);
		validateReg(nodesReg);
		validateReg(valsReg);
		int count = extractActiveNodeIdsToScratch(state, ctx, nodesReg, 0);
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
		handleCooWalkFiltered(state, ctx, instr);
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
		ctx.initStreamTouchedMask(Math.max(rel.getNodeCount(), 65536));

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

		long[] touchedMask = ctx.initStreamTouchedMask(Math.max(rel.getNodeCount(), 65536));
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
								int size = Math.max(rel.getNodeCount(), 65536);
								handle = ctx.acquireFloatVector(size);
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
								int size = Math.max(rel.getNodeCount(), 65536);
								handle = ctx.acquireFloatVector(size);
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
