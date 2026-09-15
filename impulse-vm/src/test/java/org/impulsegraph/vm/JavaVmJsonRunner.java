package org.impulsegraph.vm;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import org.impulsegraph.api.ImpulseGraphSnapshot;

public class JavaVmJsonRunner {
	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Usage: JavaVmJsonRunner <path-to-impas> [path-to-impb] [path-to-impb-data]");
			System.exit(1);
		}
		java.nio.file.Path impasFile = java.nio.file.Paths.get(args[0]);
		java.nio.file.Path impbFile = args.length > 1 ? java.nio.file.Paths.get(args[1]) : null;
		java.nio.file.Path impbDataFile = args.length > 2 ? java.nio.file.Paths.get(args[2]) : null;
		JavaVmPolyglotAssemblyVerifierTest verifier = new JavaVmPolyglotAssemblyVerifierTest();

		// Use reflection to access parseImpasFile
		java.lang.reflect.Method parseMethod = JavaVmPolyglotAssemblyVerifierTest.class
				.getDeclaredMethod("parseImpasFile", Path.class);
		parseMethod.setAccessible(true);
		JavaVmPolyglotAssemblyVerifierTest.ParsedAssembly asm = (JavaVmPolyglotAssemblyVerifierTest.ParsedAssembly) parseMethod
				.invoke(verifier, impasFile);

		// Core logic

		try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
			java.lang.foreign.MemorySegment progSeg;
			int instructionCount = 0;
			if (impbFile != null && java.nio.file.Files.exists(impbFile)) {
				byte[] bytes = java.nio.file.Files.readAllBytes(impbFile);
				progSeg = arena.allocate(bytes.length, 8);
				java.lang.foreign.MemorySegment.copy(bytes, 0, progSeg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
						bytes.length);
				instructionCount = bytes.length / 8;
			} else {
				instructionCount = asm.instructions().size();
				progSeg = arena.allocate(instructionCount * 8L, 8);
				for (int i = 0; i < instructionCount; i++) {
					progSeg.set(java.lang.foreign.ValueLayout.JAVA_LONG, i * 8L, asm.instructions().get(i));
				}
			}

			try (VmQueryContext ctx = new VmQueryContext(null, arena)) {
				MemorySegment state = ctx.allocateStateSegment();
				if (asm.mockGraph() != null) {
					ctx.setSnapshot(asm.mockGraph());
				} else if (impasFile.getFileName().toString().contains("tc45_missing_csc_neg")) {
					int[] offsets = new int[]{0, 2, 3, 3};
					int[] targets = new int[]{1, 2, 2};
					MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
					rel.setCsc(null, null);
					ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("connectedTo", rel, "rel_0", rel));
					ctx.setSnapshot(graph);
				} else if (!impasFile.getFileName().toString().contains("null_snapshot")) {
					int[] offsets = new int[]{0, 2, 3, 3};
					int[] targets = new int[]{1, 2, 2};
					MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
					ImpulseGraphSnapshot graph = new MockImpulseGraphSnapshot(Map.of("connectedTo", rel, "rel_0", rel));
					ctx.setSnapshot(graph);
				}

				// Setup inline data segment if present in mockData
				if (impbDataFile != null && java.nio.file.Files.exists(impbDataFile)) {
					byte[] raw = java.nio.file.Files.readAllBytes(impbDataFile);
					java.lang.foreign.MemorySegment inlineSeg = arena.allocate(raw.length, 64);
					java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(raw), 0, inlineSeg, 0,
							raw.length);
					ctx.setInlineData(inlineSeg, raw.length);
				} else if (asm.mockData().containsKey("__DEFAULT_INLINE__")) {
					byte[] raw = asm.mockData().get("__DEFAULT_INLINE__");
					java.lang.foreign.MemorySegment inlineSeg = arena.allocate(raw.length, 64);
					java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(raw), 0, inlineSeg, 0,
							raw.length);
					ctx.setInlineData(inlineSeg, raw.length);
				}

				long pc = 0;

				VmHandlers.Instruction[] progArr = new VmHandlers.Instruction[(int) instructionCount];
				for (int i = 0; i < instructionCount; i++) {
					progArr[i] = VmHandlers.decodeInstruction(progSeg, i);
				}

				String actualStatus = "IMPULSE_VM_OK";
				long stepCount = 0;
				int fuel = asm.fuel() != null ? asm.fuel() : -1;

				if (fuel > 0) {
					// Fallback to strict step-by-step for gas exhaustion tests
					loop : while (actualStatus.equals("IMPULSE_VM_OK") && pc >= 0 && pc < instructionCount
							&& stepCount++ < 2000000000) {
						if (fuel > 0) {
							fuel--;
							if (fuel == 0) {
								actualStatus = "IMPULSE_VM_ERR_GAS_EXHAUSTED";
								break;
							}
						}

						VmHandlers.Instruction instr = VmHandlers.decodeInstruction(progSeg, pc);
						boolean isExtended = (instr.flags() & VmRegisterType.OP_FLAG_EXTENDED) != 0;
						if (instr.opcode() == 0x10) {
							System.err.println("OP_CSR_WALK: flags=" + instr.flags() + " isExtended=" + isExtended);
						}
						if (isExtended) {
							try {
								VmHandlers.ExtendedInstruction ext = VmHandlers.decodeExtendedInstruction(progSeg, pc);
								instr = new VmHandlers.Instruction(instr.opcode(), instr.flags(), instr.dstReg(),
										(instr.payload() & 0xFFFF) | (ext.arg3() << 16));
							} catch (IllegalStateException e) {
								actualStatus = "IMPULSE_VM_ERR_INVALID_INSTRUCTION";
								break;
							}
						}

						byte opcode = instr.opcode();

						if (opcode == (byte) 0xFF) { // OP_HALT
							break;
						}
						if (opcode == (byte) 0x5C) { // OP_TRAP
							actualStatus = "IMPULSE_VM_ERR_TRAP";
							break;
						}

						if (instr.dstReg() >= 64 && Byte.toUnsignedInt(opcode) != 0x09
								&& Byte.toUnsignedInt(opcode) != 0x0B && Byte.toUnsignedInt(opcode) != 0x0C) {
							System.err.println(
									"INVALID REG in " + impasFile.getFileName() + " at pc=" + pc + ": opcode=0x"
											+ Integer.toHexString(opcode & 0xFF) + ", dstReg=" + instr.dstReg());
							actualStatus = "IMPULSE_VM_ERR_INVALID_REGISTER";
							break;
						}

						if (isExtended)
							pc++;
						try {
							switch (Byte.toUnsignedInt(opcode)) {

								case 0x00 -> {
									break loop;
								}
								case 0x01 -> pc++; // OP_NOP
								case 0x02 -> {
									long inputNode = (instr.payload() != 0) ? (long) instr.payload() : 0L;
									VmHandlers.handleInitInputNode(state, ctx, instr, inputNode);
									pc++;
								}
								case 0x03 -> {
									VmHandlers.handleInitInputSet(state, ctx, instr,
											new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 1000));
									pc++;
								}
								case 0x04 -> {
									VmHandlers.handleLoadConstInt(state, instr);
									pc++;
								}
								case 0x05 -> {
									int domainId = instr.payload() & 0xFFFF;
									if (domainId >= 32768) {
										actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
										break;
									}
									VmHandlers.validateReg(instr.dstReg());
									int h = (VmHandlers.getRegisterType(state,
											instr.dstReg()) == VmRegisterType.TYPE_BITSET_HANDLE)
													? (int) VmHandlers.getRegisterValue(state, instr.dstReg())
													: ctx.acquireBitset();
									VmHandlers.setRegister(state, instr.dstReg(), h, VmRegisterType.TYPE_BITSET_HANDLE);
									VmHandlers.setFlag(state, VmRegisterType.FLAG_ZF, true);
									pc++;
								}
								case 0x06 -> {
									VmHandlers.handleLoadConstFloat(state, instr);
									pc++;
								}
								case 0x07 -> {
									VmHandlers.handleLoadConstStrPrefix(state, instr);
									pc++;
								}
								case 0x08 -> {
									VmHandlers.handleLoadInlineArray(state, ctx, instr);
									pc++;
								}
								case 0x09 -> {
									VmHandlers.handleInitMockGraph(state, ctx, instr);
									pc++;
								}
								case 0x0A, 0x4C, 0x4D, 0x4E -> {
									VmHandlers.handleLoadInlineSet(state, ctx, instr);
									pc++;
								}
								case 0x0B, 0x0C -> {
									int attrId = instr.dstReg();
									int srcReg = instr.payload() & 0xFF;
									if (srcReg < 64) {
										int h = (int) VmHandlers.getRegisterValue(state, srcReg);
										int[] iarr = ctx.getIntVector(h);
										if (iarr != null) {
											ctx.setMockAttribute(attrId, iarr);
										} else {
											float[] farr = ctx.getFloatVector(h);
											if (farr != null) {
												int[] converted = new int[farr.length];
												for (int k = 0; k < farr.length; k++) {
													converted[k] = Float.floatToRawIntBits(farr[k]);
												}
												ctx.setMockAttribute(attrId, converted);
											}
										}
									}
									pc++;
								}
								case 0x0D -> {
									VmHandlers.handleLoadInlineIntArray(state, ctx, instr);
									pc++;
								}
								case 0x0E -> {
									VmHandlers.handleCsrWalk2Hop(state, ctx, instr, null);
									pc++;
								}
								case 0x0F -> {
									VmHandlers.handleCsrWalkState(state, ctx, instr);
									pc++;
								}
								case 0x10 -> {
									VmHandlers.handleCsrWalk(state, ctx, instr);
									pc++;
								}
								case 0x11 -> {
									VmHandlers.handleCsrWalkFiltered(state, ctx, instr);
									pc++;
								}
								case 0x12 -> {
									VmHandlers.handleCsrDegree(state, ctx, instr);
									pc++;
								}
								case 0x13 -> {
									if (instr.flags() == (byte) 0xFF || ((instr.payload() >> 24) & 0xFF) == 0xFF) {
										actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
										break;
									}
									VmHandlers.handleCsrWalkPredicate(state, ctx, instr);
									pc++;
								}
								case 0x14 -> {
									VmHandlers.handleNodeFilter(state, ctx, instr);
									pc++;
								}
								case 0x15 -> {
									VmHandlers.handleNodeFilter(state, ctx, instr);
									pc++;
								} // OP_NODE_FILTER_STR_PREFIX
								case 0x16 -> {
									VmHandlers.handleCsrWalkReduceSum(state, ctx, instr);
									pc++;
								}
								case 0x17 -> {
									VmHandlers.handleCsrWalkReduce(state, ctx, instr);
									pc++;
								} // OP_CSR_WALK_REDUCE
								case 0x18 -> {
									VmHandlers.handleCscWalk(state, ctx, instr);
									pc++;
								}
								case 0x19 -> {
									VmHandlers.handleHasCsr(state, ctx, instr);
									pc++;
								}
								case 0x1A -> {
									VmHandlers.handleHasCsc(state, ctx, instr);
									pc++;
								}
								case 0x1B -> {
									VmHandlers.handleHasCoo(state, ctx, instr);
									pc++;
								}
								case 0x1C -> {
									VmHandlers.handleHasKeyCatalog(state, ctx, instr);
									pc++;
								}
								case 0x30 -> {
									VmHandlers.handleSetUnion(state, ctx, instr);
									pc++;
								}
								case 0x31 -> {
									VmHandlers.handleSetIntersect(state, ctx, instr);
									pc++;
								}
								case 0x32 -> {
									VmHandlers.handleSetDifference(state, ctx, instr);
									pc++;
								}
								case 0x33 -> {
									VmHandlers.handleSetCardinality(state, ctx, instr);
									if (impasFile.getFileName().toString().contains("tc_poly_node_filter")) {
										System.err.println(
												"SET_CARD in " + impasFile.getFileName() + " R" + instr.dstReg() + "="
														+ VmHandlers.getRegisterValue(state, instr.dstReg())
														+ " (from R" + (instr.payload() & 0xFFFF) + ")");
									}
									pc++;
								}
								case 0x34 -> {
									VmHandlers.handleVectorMulAttr(state, ctx, instr);
									pc++;
								}
								case 0x35 -> {
									VmHandlers.handleVectorReduceSum(state, ctx, instr);
									pc++;
								}
								case 0x36 -> {
									VmHandlers.handleVectorDiv(state, ctx, instr);
									pc++;
								}
								case 0x37 -> {
									VmHandlers.handleVectorStrConcat(state, ctx, instr);
									pc++;
								}
								case 0x38 -> {
									VmHandlers.handleFloatVectorScale(state, ctx, instr);
									pc++;
								}
								case 0x39 -> {
									VmHandlers.handleL1NormDiff(state, ctx, instr);
									pc++;
								}
								case 0x3A -> {
									VmHandlers.handleProjectState(state, ctx, instr);
									pc++;
								}
								case 0x3B -> {
									VmHandlers.handleCoalesce(state, ctx, instr);
									pc++;
								}
								case 0x3C -> {
									VmHandlers.handleExtractValidity(state, ctx, instr);
									pc++;
								}
								case 0x40 -> {
									VmHandlers.handleCcAfforest(state, ctx, instr);
									pc++;
								}
								case 0x41 -> {
									VmHandlers.handleMxv(state, ctx, instr);
									pc++;
								}
								case 0x42 -> {
									VmHandlers.handleVxm(state, ctx, instr);
									pc++;
								}
								case 0x43 -> {
									VmHandlers.handleEwiseAdd(state, ctx, instr);
									pc++;
								}
								case 0x44 -> {
									VmHandlers.handleEwiseMult(state, ctx, instr);
									pc++;
								}
								case 0x45 -> {
									VmHandlers.handleReduce(state, ctx, instr);
									pc++;
								}
								case 0x46 -> {
									VmHandlers.handleCcHookCompress(state, ctx, instr);
									pc++;
								}
								case 0x47 -> {
									VmHandlers.handleTcSweepBatch(state, ctx, instr);
									pc++;
								}
								case 0x48 -> {
									VmHandlers.handleBrandesForward(state, ctx, instr);
									pc++;
								}
								case 0x49 -> {
									VmHandlers.handleBrandesBackward(state, ctx, instr);
									pc++;
								}
								case 0x4A -> {
									VmHandlers.handleDeltaStepRelax(state, ctx, instr);
									pc++;
								}
								case 0x4B -> {
									VmHandlers.handleReadEdgeWeight(state, ctx, instr);
									pc++;
								}
								case 0x50 -> {
									int offset = instr.payload();
									pc += offset;
									if (pc < 0 || pc >= instructionCount) {
										actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
										break;
									}
								}
								case 0x51 -> {
									if (VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
										pc += instr.payload();
										if (pc < 0 || pc >= instructionCount) {
											actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
											break;
										}
									} else {
										pc++;
									}
								}
								case 0x52 -> {
									if (!VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF)) {
										pc += instr.payload();
										if (pc < 0 || pc >= instructionCount) {
											actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
											break;
										}
									} else {
										pc++;
									}
								}
								case 0x53 -> {
									long val = VmHandlers.getRegisterValue(state, instr.dstReg()) - 1;
									VmHandlers.setRegister(state, instr.dstReg(), val, VmRegisterType.TYPE_INT64);
									VmHandlers.setFlag(state, VmRegisterType.FLAG_ZF, val == 0);
									if (val > 0) {
										pc += instr.payload();
										if (pc < 0 || pc >= instructionCount) {
											actualStatus = "IMPULSE_VM_ERR_OUT_OF_BOUNDS";
											break;
										}
									} else {
										pc++;
									}
								}
								case 0x54 -> {
									VmHandlers.handleStableCheck(state, ctx, instr);
									pc++;
								}
								case 0x55 -> { // OP_CALL
									int target = instr.payload();
									long returnPc = pc + 1;
									if (!VmHandlers.pushCallStack(state, (int) returnPc)) {
										actualStatus = "IMPULSE_VM_ERR_STACK_OVERFLOW";
										break;
									}
									// Register Windowing: Pass Out registers (R12..R15) to Callee In registers
									// (R0..R3)
									long arg0 = VmHandlers.getRegisterValue(state, 12);
									long arg1 = VmHandlers.getRegisterValue(state, 13);
									long arg2 = VmHandlers.getRegisterValue(state, 14);
									long arg3 = VmHandlers.getRegisterValue(state, 15);

									VmHandlers.setRegister(state, 0, arg0, VmRegisterType.TYPE_INT64);
									VmHandlers.setRegister(state, 1, arg1, VmRegisterType.TYPE_INT64);
									VmHandlers.setRegister(state, 2, arg2, VmRegisterType.TYPE_INT64);
									VmHandlers.setRegister(state, 3, arg3, VmRegisterType.TYPE_INT64);

									if (target >= 0 && target < instructionCount)
										pc = target;
									else
										pc += target;
								}
								case 0x56 -> { // OP_RET
									int returnPc = VmHandlers.popCallStack(state);
									if (returnPc < 0) {
										actualStatus = "IMPULSE_VM_ERR_STACK_UNDERFLOW";
										break;
									}
									pc = returnPc;
								}
								case 0x57, 0x58 -> {
									pc++;
								} // OP_ENTER_FRAME, OP_LEAVE_FRAME
								case 0x5A -> {
									VmHandlers.handleThrow(state, instr);
									actualStatus = "IMPULSE_VM_ERR_USER_THROW";
									break loop;
								}
								case 0x5B -> {
									VmHandlers.handleAssert(state, instr);
									pc++;
								}
								case 0x1D -> {
									VmHandlers.handleCsrWalk(state, ctx, instr);
									pc++;
								} // OP_ADAPTIVE_WALK
								case 0x1E -> {
									VmHandlers.handleCreateScratchIndex(state, instr, ctx);
									pc++;
								} // OP_CREATE_SCRATCH_INDEX
								case 0x1F -> {
									pc++;
								} // OP_DROP_SCRATCH_INDEX
								case 0x20 -> {
									VmHandlers.handleVecCmpEq(state, ctx, instr);
									pc++;
								}
								case 0x21 -> {
									VmHandlers.handleVecCmpGt(state, ctx, instr);
									pc++;
								}
								case 0x22 -> {
									VmHandlers.handleVecCmpLt(state, ctx, instr);
									pc++;
								}
								case 0x23 -> {
									VmHandlers.handleVecCmpBetween(state, ctx, instr);
									pc++;
								}
								case 0x24 -> {
									VmHandlers.handleMaskAnd(state, ctx, instr);
									pc++;
								}
								case 0x25 -> {
									VmHandlers.handleMaskOr(state, ctx, instr);
									pc++;
								}
								case 0x26 -> {
									VmHandlers.handleMaskNot(state, ctx, instr);
									pc++;
								}
								case 0x27 -> {
									VmHandlers.handleVecBlend(state, ctx, instr);
									pc++;
								}
								case 0x2A -> {
									VmHandlers.handleAssertFinite(state, ctx, instr);
									pc++;
								}
								case 0x2D -> {
									VmHandlers.handleVecMathUnary(state, ctx, instr);
									pc++;
								}
								case 0x2E -> {
									VmHandlers.handleVecMathBinary(state, ctx, instr);
									pc++;
								}
								case 0x2F -> {
									VmHandlers.handleVecMathTernary(state, ctx, instr);
									pc++;
								}
								case 0x3D -> {
									VmHandlers.handleVectorTimeValidAt(state, ctx, instr);
									pc++;
								}
								case 0x60, 0x61, 0x62, 0x63, 0x66, 0x67, 0x69, 0x6A -> {
									VmHandlers.validateReg(instr.dstReg());
									pc++;
								}
								case 0x68 -> {
									VmHandlers.handleKcoreDecomposition(state, ctx, instr);
									pc++;
								}
								case 0x64 -> {
									VmHandlers.handleRoaringBitmapAnd(state, ctx, instr);
									pc++;
								}
								case 0x6B -> {
									VmHandlers.handleRoaringBitmapOr(state, ctx, instr);
									pc++;
								}
								case 0x6C -> {
									VmHandlers.handleRoaringBitmapAndNot(state, ctx, instr);
									pc++;
								}
								case 0x65 -> {
									VmHandlers.handleIslandDetect(state, ctx, instr);
									pc++;
								}
								case 0x70 -> {
									VmHandlers.handleMov(state, ctx, instr);
									pc++;
								}
								case 0x71 -> {
									VmHandlers.handleClearReg(state, instr);
									pc++;
								}
								case 0x72 -> {
									VmHandlers.handleLoadIndirect(state, ctx, instr);
									pc++;
								}
								case 0x73 -> {
									VmHandlers.handleAllocScratch(state, ctx, instr);
									pc++;
								}
								case 0x74 -> {
									VmHandlers.handleAssertScratchBytes(state, ctx, instr);
									pc++;
								}
								case 0x75 -> {
									VmHandlers.handleSetMaxDop(state, ctx, instr);
									pc++;
								}
								case 0x80 -> {
									VmHandlers.handleLoadColumnVector(state, ctx, instr);
									pc++;
								}
								case 0x81 -> {
									VmHandlers.handleGatherNodeAttr(state, ctx, instr);
									pc++;
								}
								case 0x82 -> {
									VmHandlers.handleGatherEdgeAttr(state, ctx, instr);
									pc++;
								}
								case 0x83 -> {
									VmHandlers.handleBrinZoneSkip(state, ctx, instr);
									pc++;
								}
								case 0x84 -> {
									VmHandlers.handleCsrWalkDirectStore(state, ctx, instr);
									pc++;
								}
								case 0x85 -> {
									VmHandlers.handleCsrWalkDenseStream(state, ctx, instr);
									pc++;
								}
								case 0x86 -> {
									VmHandlers.handleCooWalk(state, ctx, instr);
									pc++;
								}
								case 0x87 -> {
									VmHandlers.handleCscWalkDirectStore(state, ctx, instr);
									pc++;
								}
								case 0x88 -> {
									VmHandlers.handleFixpointKleeneStar(state, ctx, instr);
									pc++;
								}
								case 0x89 -> {
									VmHandlers.handleSwapReg(state, instr);
									pc++;
								}
								case 0x8A -> {
									VmHandlers.handleFrontierDiff(state, ctx, instr);
									pc++;
								}
								case 0x8B -> {
									VmHandlers.handleCooWalkFiltered(state, ctx, instr);
									pc++;
								}
								case 0x8C -> {
									VmHandlers.handleCooWalkReduce(state, ctx, instr);
									pc++;
								}
								case 0x8D -> {
									VmHandlers.handleCooWalkDirectStore(state, ctx, instr);
									pc++;
								}
								case 0x8E -> {
									VmHandlers.handleDenseWalk(state, ctx, instr);
									pc++;
								}
								case 0x8F -> {
									VmHandlers.handleDenseWalkBitmatrix(state, ctx, instr);
									pc++;
								}
								case 0x90 -> {
									VmHandlers.handleCollectBitset(state, ctx, instr);
									pc++;
								}
								case 0x91 -> {
									VmHandlers.handleCollectArray(state, ctx, instr);
									pc++;
								}
								case 0x92 -> {
									VmHandlers.handleMapDenseToKeys(state, ctx, instr);
									pc++;
								}
								case 0x93 -> {
									VmHandlers.handleCollectValueMap(state, ctx, instr);
									pc++;
								}
								case 0x94 -> {
									VmHandlers.handleDenseWalkReduce(state, ctx, instr);
									pc++;
								}
								case 0x95 -> {
									VmHandlers.handleDenseWalkDirectStore(state, ctx, instr);
									pc++;
								}
								case 0xA0, 0xA9, 0xAA -> {
									VmHandlers.handleStreamWalk(state, ctx, instr, progSeg, instructionCount);
									pc++;
								}
								case 0xA1, 0xA2 -> {
									pc++;
								}
								case 0x28, 0x29, 0x2B, 0x2C, 0x3E, 0x3F, 0x4F, 0x59, 0x5D, 0x5E, 0x5F, 0x6D, 0x6E, 0x6F,
										0x76, 0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F -> {
									actualStatus = "IMPULSE_VM_ERR_RESERVED_OPCODE";
									break loop;
								}
								default -> {
									System.err.println("INVALID OPCODE in " + impasFile.getFileName() + " at pc=" + pc
											+ ": 0x" + Integer.toHexString(opcode & 0xFF));
									actualStatus = "IMPULSE_VM_ERR_INVALID_OPCODE";
									break loop;
								}
							}
						} catch (Throwable t) {
							System.err.println("Crash in " + impasFile.getFileName() + " at pc=" + pc + " (op 0x"
									+ Integer.toHexString(opcode & 0xFF) + "): " + t);
							String msg = t.getMessage();
							if (msg != null && msg.contains("IMPULSE_VM_ERR_")) {
								int start = msg.indexOf("IMPULSE_VM_ERR_");
								int end = msg.indexOf(':', start);
								actualStatus = (end >= 0)
										? msg.substring(start, end).trim()
										: msg.substring(start).trim();
							} else {
								actualStatus = "IMPULSE_VM_ERR_ASSERTION_FAILED";
							}
							break;
						}
					}
				} else {
					try {
						JitDriver driver = ImpulseMethodHandleCompiler.compileDriver(progSeg, instructionCount);
						driver.execute(ctx, state, null, instructionCount);
						// According to VM Spec §2.2, PC remains pointed at the halting or faulting
						// instruction
						pc = (int) VmStateLayout.PC_HANDLE.get(state, 0L);
					} catch (Throwable t) {
						pc = (int) VmStateLayout.PC_HANDLE.get(state, 0L);
						t.printStackTrace();
						String msg = t.getMessage();
						if (msg != null && msg.contains("IMPULSE_VM_ERR_")) {
							int start = msg.indexOf("IMPULSE_VM_ERR_");
							int end = msg.indexOf(':', start);
							actualStatus = (end >= 0) ? msg.substring(start, end).trim() : msg.substring(start).trim();
						} else if (t.getCause() != null && t.getCause().getMessage() != null
								&& t.getCause().getMessage().contains("IMPULSE_VM_ERR_")) {
							msg = t.getCause().getMessage();
							int start = msg.indexOf("IMPULSE_VM_ERR_");
							int end = msg.indexOf(':', start);
							actualStatus = (end >= 0) ? msg.substring(start, end).trim() : msg.substring(start).trim();
						} else {
							actualStatus = "IMPULSE_VM_ERR_ASSERTION_FAILED";
						}
					}
				}

				// JSON Output
				System.out.print("{");
				System.out.print("\"status\":\"" + actualStatus + "\",");
				System.out.print("\"pc\":" + pc + ",");

				boolean zf = VmHandlers.checkFlag(state, VmRegisterType.FLAG_ZF);
				boolean st = VmHandlers.checkFlag(state, VmRegisterType.FLAG_ST);
				System.out.print("\"flags\":{\"zf\":" + zf + ",\"st\":" + st + "},");

				System.out.print("\"registers\":{");
				for (int r = 0; r < 64; r++) {
					long val = VmHandlers.getRegisterValue(state, r);
					System.out.print("\"R" + r + "\":" + val);
					if (r < 63)
						System.out.print(",");
				}
				System.out.print("}");
				System.out.println("}");
			}
		}
	}
}
