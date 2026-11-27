package org.impulsegraph.vm;

import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Straight-Line & Lexical-Morsel MethodHandle JIT Compiler for ImpulseVM
 * v0.11.0.
 *
 * <p>
 * Compiles a v0.11 program + bound snapshot into a single flat
 * {@code (ImpulseWorkspace) -> void} {@link MethodHandle} chain with all
 * register indices, CSR/CSC {@link MemorySegment}s, integer widths, and morsel
 * bodies constant-folded for C2 inlining.
 */
public final class ImpulseMethodHandleCompiler {

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	private static final MethodHandle MH_RESET;
	private static final MethodHandle MH_LOAD_CONST_F32;
	private static final MethodHandle MH_FSEED_IMM;
	private static final MethodHandle MH_INIT_DOMAIN_SPAN;
	private static final MethodHandle MH_SET_OP_UNION;
	private static final MethodHandle MH_VEC_INIT_F64;
	private static final MethodHandle MH_WALK;
	private static final MethodHandle MH_WALK_MORSEL;
	private static final MethodHandle MH_MORSEL_GATHER_F32;
	private static final MethodHandle MH_MORSEL_GATHER_F64;
	private static final MethodHandle MH_MORSEL_CMP_GT_SCALAR;
	private static final MethodHandle MH_MORSEL_B2F;
	private static final MethodHandle MH_MORSEL_COMMIT_FRONTIER;
	private static final MethodHandle MH_MORSEL_COMMIT_SCATTER_ADD;
	private static final MethodHandle MH_LEAF_P1;
	private static final MethodHandle MH_LEAF_P2;
	private static final MethodHandle MH_LEAF_P3;
	private static final MethodHandle MH_LEAF_P4_MATH;
	private static final MethodHandle MH_LEAF_P4;
	private static final MethodHandle MH_LEAF_P5;
	private static final MethodHandle MH_LEAF_INVALID;

	static {
		try {
			MH_RESET = LOOKUP.findVirtual(ImpulseWorkspace.class, "reset", MethodType.methodType(void.class));
			MH_LOAD_CONST_F32 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opLoadConstF32",
					MethodType.methodType(void.class, int.class, long.class, boolean.class, ImpulseWorkspace.class));
			MH_FSEED_IMM = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opFseedImm", MethodType.methodType(
					void.class, int.class, int.class, int.class, int.class, boolean.class, ImpulseWorkspace.class));
			MH_INIT_DOMAIN_SPAN = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opInitDomainSpan",
					MethodType.methodType(void.class, int.class, int.class, int.class, ImpulseWorkspace.class));
			MH_SET_OP_UNION = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opSetUnion",
					MethodType.methodType(void.class, int.class, int.class, int.class, ImpulseWorkspace.class));
			MH_VEC_INIT_F64 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opVecInitF64",
					MethodType.methodType(void.class, int.class, int.class, double.class, ImpulseWorkspace.class));
			MH_WALK = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opWalk",
					MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class, int.class,
							int.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class,
							ImpulseWorkspace.class));
			MH_WALK_MORSEL = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "opWalkMorsel",
					MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class, int.class,
							int.class, int.class, boolean.class, int.class, int.class, int.class, int.class,
							MethodHandle.class, ImpulseWorkspace.class));
			MH_MORSEL_GATHER_F32 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "morselGatherF32",
					MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, ImpulseWorkspace.class,
							int.class));
			MH_MORSEL_GATHER_F64 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "morselGatherF64",
					MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, ImpulseWorkspace.class,
							int.class));
			MH_MORSEL_CMP_GT_SCALAR = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "morselCmpGtScalar",
					MethodType.methodType(void.class, int.class, int.class, int.class, ImpulseWorkspace.class,
							int.class));
			MH_MORSEL_B2F = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "morselB2f",
					MethodType.methodType(void.class, int.class, int.class, ImpulseWorkspace.class, int.class));
			MH_MORSEL_COMMIT_FRONTIER = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "morselCommitFrontier",
					MethodType.methodType(void.class, int.class, int.class, int.class, boolean.class,
							ImpulseWorkspace.class, int.class));
			MH_MORSEL_COMMIT_SCATTER_ADD = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class,
					"morselCommitScatterAdd", MethodType.methodType(void.class, int.class, int.class, int.class,
							int.class, boolean.class, ImpulseWorkspace.class, int.class));
			MH_LEAF_P1 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP1",
					MethodType.methodType(void.class, ImpulseInterpreter.SnapshotBinding.class, long[].class,
							long.class, ImpulseWorkspace.class, long.class));
			MH_LEAF_P2 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP2",
					MethodType.methodType(void.class, ImpulseInterpreter.SnapshotBinding.class, long.class,
							ImpulseWorkspace.class, long.class));
			MH_LEAF_P3 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP3",
					MethodType.methodType(void.class, ImpulseInterpreter.SnapshotBinding.class, long[].class,
							long.class, ImpulseWorkspace.class, long.class));
			MH_LEAF_P4_MATH = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP4Math",
					MethodType.methodType(void.class, long.class, ImpulseWorkspace.class, long.class));
			MH_LEAF_P4 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP4",
					MethodType.methodType(void.class, ImpulseInterpreter.SnapshotBinding.class, long.class,
							ImpulseWorkspace.class, long.class));
			MH_LEAF_P5 = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafP5",
					MethodType.methodType(void.class, ImpulseInterpreter.SnapshotBinding.class, long.class,
							ImpulseWorkspace.class, long.class));
			MH_LEAF_INVALID = LOOKUP.findStatic(ImpulseMethodHandleCompiler.class, "leafInvalid",
					MethodType.methodType(void.class, ImpulseWorkspace.class, long.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/**
	 * Compiled full-ISA ImpulseVM v0.11.0 program backed by statically-resolved,
	 * basic-block-fused {@link MethodHandle}s.
	 */
	public static final class CompiledProgram {
		private final ImpulseValidator.ValidationResult validation;
		private final MethodHandle[] blockHandles;
		private final int programLength;
		private final int fusedBlockCount;

		CompiledProgram(ImpulseValidator.ValidationResult validation, MethodHandle[] blockHandles, int programLength,
				int fusedBlockCount) {
			this.validation = validation;
			this.blockHandles = blockHandles;
			this.programLength = programLength;
			this.fusedBlockCount = fusedBlockCount;
		}

		public boolean isValid() {
			return validation.isOk();
		}

		public int fusedBlockCount() {
			return fusedBlockCount;
		}

		/**
		 * Execute the compiled program to HALT or error with zero per-step allocation.
		 */
		public int execute(ImpulseWorkspace ws, long maxSteps) {
			if (!validation.isOk()) {
				ws.pc = validation.badPc();
				ws.status = validation.status();
				return ws.status;
			}
			ws.pc = 0;
			ws.status = ImpulseIsaTables.IMPULSE_VM_OK;
			ws.stepsScratch[0] = 0L;
			final MethodHandle[] handles = this.blockHandles;
			final int n = this.programLength;
			try {
				while (ws.status == ImpulseIsaTables.IMPULSE_VM_OK) {
					int pc = ws.pc;
					if (pc < 0 || pc >= n) {
						if (ws.stepsScratch[0] >= maxSteps) {
							ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_GAS_EXHAUSTED;
						} else {
							ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						}
						return ws.status;
					}
					handles[pc].invokeExact(ws, maxSteps);
					if (ws.status == ImpulseIsaTables.IMPULSE_VM_OK && pc == ws.pc && handles[pc] == null) {
						break;
					}
					// HALT leaves status == OK and sets stepsScratch[0] < 0 sentinel
					if (ws.stepsScratch[0] < 0L) {
						return ws.status;
					}
				}
			} catch (Throwable t) {
				throw new RuntimeException("ImpulseMethodHandleCompiler execution error", t);
			}
			return ws.status;
		}
	}

	private static final int MAX_FUSED_BLOCK = 16;

	/**
	 * Validate and compile any v0.11.0 program (all 5 pillars, control flow, calls,
	 * morsels) into statically-bound, basic-block-fused {@link MethodHandle}
	 * chains.
	 */
	public static CompiledProgram compileValidated(long[] program, ImpulseInterpreter.SnapshotBinding bind) {
		ImpulseValidator.ValidationResult vr = ImpulseValidator.validate(program);
		if (!vr.isOk()) {
			return new CompiledProgram(vr, new MethodHandle[0], program.length, 0);
		}
		int n = program.length;
		boolean[] isLeader = new boolean[n];
		if (n > 0)
			isLeader[0] = true;
		for (int pc = 0; pc < n; pc++) {
			long w = program[pc];
			int op = (int) (w & 0xFFL);
			if (isControlOrMorselOp(op, w)) {
				isLeader[pc] = true;
				if (pc + 1 < n)
					isLeader[pc + 1] = true;
				if (((int) ((w >>> 12) & 0xFL) & ImpulseIsaTables.FLAG_EXTENDED) != 0 && pc + 2 < n) {
					isLeader[pc + 2] = true;
				}
				if (op == ImpulseIsaTables.OP_JMP || op == ImpulseIsaTables.OP_JCC
						|| op == ImpulseIsaTables.OP_LOOP_DECR || op == ImpulseIsaTables.OP_CALL) {
					int imm32 = (int) (w >>> 32);
					int target = (op == ImpulseIsaTables.OP_CALL) ? (pc + (short) (imm32 & 0xFFFF)) : (pc + imm32);
					if (target >= 0 && target < n)
						isLeader[target] = true;
				} else if (op == ImpulseIsaTables.OP_WALK_MORSEL || op == ImpulseIsaTables.OP_FILTER_MORSEL) {
					int endOff = (int) ((w >>> 48) & 0xFFFFL);
					int after = pc + endOff + 1;
					if (after >= 0 && after < n)
						isLeader[after] = true;
				}
			}
		}

		MethodHandle[] leaf = new MethodHandle[n];
		for (int pc = 0; pc < n; pc++) {
			leaf[pc] = bindLeaf(program, bind, program[pc]);
		}

		MethodHandle[] blocks = new MethodHandle[n];
		int fusedBlocks = 0;
		for (int pc = 0; pc < n; pc++) {
			long w = program[pc];
			int op = (int) (w & 0xFFL);
			if (isControlOrMorselOp(op, w)) {
				blocks[pc] = leaf[pc];
				continue;
			}
			MethodHandle chain = leaf[pc];
			int len = 1;
			while (len < MAX_FUSED_BLOCK && pc + len < n && !isLeader[pc + len]) {
				long nw = program[pc + len];
				int nextOp = (int) (nw & 0xFFL);
				if (isControlOrMorselOp(nextOp, nw))
					break;
				chain = MethodHandles.foldArguments(leaf[pc + len], chain);
				len++;
			}
			blocks[pc] = chain;
			if (len > 1)
				fusedBlocks++;
		}
		return new CompiledProgram(vr, blocks, n, fusedBlocks);
	}

	private static boolean isControlOrMorselOp(int op, long w) {
		if (((int) ((w >>> 12) & 0xFL) & ImpulseIsaTables.FLAG_EXTENDED) != 0) {
			return true;
		}
		return op == ImpulseIsaTables.OP_HALT || op == ImpulseIsaTables.OP_TRAP || op == ImpulseIsaTables.OP_THROW
				|| op == ImpulseIsaTables.OP_JMP || op == ImpulseIsaTables.OP_JCC || op == ImpulseIsaTables.OP_LOOP_DECR
				|| op == ImpulseIsaTables.OP_CALL || op == ImpulseIsaTables.OP_RET
				|| op == ImpulseIsaTables.OP_WALK_MORSEL || op == ImpulseIsaTables.OP_FILTER_MORSEL
				|| op == ImpulseIsaTables.OP_MORSEL_COMMIT || op == ImpulseIsaTables.OP_MORSEL_END
				|| op == ImpulseIsaTables.OP_EXTENSION_PAYLOAD;
	}

	private static MethodHandle bindLeaf(long[] program, ImpulseInterpreter.SnapshotBinding bind, long w) {
		int op = (int) (w & 0xFFL);
		int pillar = ImpulseIsaTables.PILLAR[op];
		return switch (pillar) {
			case 1 -> MethodHandles.insertArguments(MH_LEAF_P1, 0, bind, program, w);
			case 2 -> MethodHandles.insertArguments(MH_LEAF_P2, 0, bind, w);
			case 3 -> MethodHandles.insertArguments(MH_LEAF_P3, 0, bind, program, w);
			case 4 -> ((op >= ImpulseIsaTables.OP_VEC_MATH_ARITH && op <= ImpulseIsaTables.OP_VEC_TERNARY)
					|| op == ImpulseIsaTables.OP_VEC_DECIMAL_ALU)
					? MethodHandles.insertArguments(MH_LEAF_P4_MATH, 0, w)
					: MethodHandles.insertArguments(MH_LEAF_P4, 0, bind, w);
			case 5 -> MethodHandles.insertArguments(MH_LEAF_P5, 0, bind, w);
			default -> MH_LEAF_INVALID;
		};
	}

	private static boolean preStep(ImpulseWorkspace ws, long maxSteps) {
		if (ws.status != ImpulseIsaTables.IMPULSE_VM_OK || ws.stepsScratch[0] < 0L) {
			return false;
		}
		if (ws.stepsScratch[0] >= maxSteps) {
			ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_GAS_EXHAUSTED;
			return false;
		}
		ws.stepsScratch[0]++;
		return true;
	}

	private static void leafP1(ImpulseInterpreter.SnapshotBinding bind, long[] program, long w, ImpulseWorkspace ws,
			long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		int r = ImpulsePillar1Ops.step(ws, bind, program, w);
		if (r == ImpulsePillar1Ops.RESULT_HALTED) {
			ws.stepsScratch[0] = -1L;
		}
	}

	private static void leafP2(ImpulseInterpreter.SnapshotBinding bind, long w, ImpulseWorkspace ws, long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ImpulsePillar2Ops.step(ws, bind, w);
	}

	private static void leafP3(ImpulseInterpreter.SnapshotBinding bind, long[] program, long w, ImpulseWorkspace ws,
			long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ImpulsePillar3Ops.step(ws, bind, program, w, ws.stepsScratch, maxSteps);
	}

	private static void leafP4Math(long w, ImpulseWorkspace ws, long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ImpulsePillar4MathOps.step(ws, w);
	}

	private static void leafP4(ImpulseInterpreter.SnapshotBinding bind, long w, ImpulseWorkspace ws, long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ImpulsePillar4Ops.step(ws, bind, w);
	}

	private static void leafP5(ImpulseInterpreter.SnapshotBinding bind, long w, ImpulseWorkspace ws, long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ImpulsePillar5Ops.step(ws, bind, w);
	}

	private static void leafInvalid(ImpulseWorkspace ws, long maxSteps) {
		if (!preStep(ws, maxSteps))
			return;
		ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_INVALID_OPCODE;
	}

	private ImpulseMethodHandleCompiler() {
	}

	/**
	 * Compile a straight-line / morsel v0.11 program into a
	 * {@code (ImpulseWorkspace) -> void} MethodHandle.
	 */
	public static MethodHandle compile(long[] program, ImpulseInterpreter.SnapshotBinding bind) {
		MethodHandle chain = MH_RESET;
		int pc = 0;
		while (pc < program.length) {
			long w = program[pc];
			int op = (int) (w & 0xFFL);
			int subop = (int) ((w >>> 8) & 0xFL);
			int flags = (int) ((w >>> 12) & 0xFL);
			int s1 = (int) ((w >>> 16) & 0xFL);
			int dst = (int) ((w >>> 20) & 0xFL);
			int s3 = (int) ((w >>> 24) & 0xFL);
			int s2 = (int) ((w >>> 28) & 0xFL);
			int imm32 = (int) (w >>> 32);
			int imm16Lo = imm32 & 0xFFFF;
			int imm16Hi = (imm32 >>> 16) & 0xFFFF;

			if (op == ImpulseInterpreter.OP_HALT) {
				break;
			}
			MethodHandle step;
			switch (op) {
				case ImpulseInterpreter.OP_LOAD_CONST -> {
					double d = Float.intBitsToFloat(imm32);
					step = MethodHandles.insertArguments(MH_LOAD_CONST_F32, 0, dst, Double.doubleToRawLongBits(d),
							d == 0.0);
					pc++;
				}
				case ImpulseInterpreter.OP_FSEED -> {
					int domCard = bind.domainCards[imm16Hi];
					step = MethodHandles.insertArguments(MH_FSEED_IMM, 0, dst, imm16Lo, imm16Hi, domCard,
							(flags & ImpulseInterpreter.FLAG_ACCUM) != 0);
					pc++;
				}
				case ImpulseInterpreter.OP_INIT_DOMAIN_SPAN -> {
					int domCard = bind.domainCards[imm16Lo];
					step = MethodHandles.insertArguments(MH_INIT_DOMAIN_SPAN, 0, dst, imm16Lo, domCard);
					pc++;
				}
				case ImpulseInterpreter.OP_SET_OP -> {
					step = MethodHandles.insertArguments(MH_SET_OP_UNION, 0, dst, s1, s2);
					pc++;
				}
				case ImpulseInterpreter.OP_VEC_INIT -> {
					int len = ((subop & 8) != 0) ? bind.domainCards[imm16Lo] : imm32;
					step = MethodHandles.insertArguments(MH_VEC_INIT_F64, 0, dst, len, 0.0);
					pc++;
				}
				case ImpulseInterpreter.OP_WALK -> {
					RelationSnapshot rel = bind.rels[imm16Lo];
					boolean rev = (subop == 1);
					MemorySegment rowOff = rev ? rel.getCscRowOffsetsSegment() : rel.getRowOffsetsSegment();
					MemorySegment colTgt = rev ? rel.getCscColumnTargetsSegment() : rel.getColumnTargetsSegment();
					int edgeWidth = rel.getEdgeIndexWidth();
					int nodeWidth = rev ? rel.getSrcNodeIdWidth() : rel.getNodeIdWidth();
					int tgtDom = rev ? rel.getSrcDomainId() : rel.getTgtDomainId();
					int tgtCard = bind.domainCards[tgtDom];
					step = MethodHandles.insertArguments(MH_WALK, 0, rowOff, colTgt, edgeWidth, nodeWidth, dst, s1, s3,
							tgtDom, tgtCard, (flags & ImpulseInterpreter.FLAG_MASK) != 0,
							(flags & ImpulseInterpreter.FLAG_ACCUM) != 0);
					pc++;
				}
				case ImpulseInterpreter.OP_WALK_MORSEL -> {
					int endPc = pc + imm16Hi;
					RelationSnapshot rel = bind.rels[imm16Lo];
					int tgtDom = rel.getTgtDomainId();
					int tgtCard = bind.domainCards[tgtDom];
					int clearFMask = 0;
					int clearVMask = 0;
					MethodHandle body = null;
					for (int p = pc + 1; p < endPc; p++) {
						long mw = program[p];
						int mop = (int) (mw & 0xFFL);
						int msub = (int) ((mw >>> 8) & 0xFL);
						int mflg = (int) ((mw >>> 12) & 0xFL);
						int ms1 = (int) ((mw >>> 16) & 0xFL);
						int mdst = (int) ((mw >>> 20) & 0xFL);
						int ms3 = (int) ((mw >>> 24) & 0xFL);
						int ms2 = (int) ((mw >>> 28) & 0xFL);
						int mimm = (int) (mw >>> 32);
						int mHi = (mimm >>> 16) & 0xFFFF;
						MethodHandle mstep = switch (mop) {
							case ImpulseInterpreter.OP_GATHER_EDGE_ATTR -> {
								MemorySegment seg = rel.getAttributeSegments().get(mHi);
								boolean isF32 = (seg.byteSize() == rel.getEdgeCount() * 4L);
								yield MethodHandles.insertArguments(isF32 ? MH_MORSEL_GATHER_F32 : MH_MORSEL_GATHER_F64,
										0, seg, mdst, ms1);
							}
							case ImpulseInterpreter.OP_VEC_CMP ->
								MethodHandles.insertArguments(MH_MORSEL_CMP_GT_SCALAR, 0, mdst, ms1, ms2);
							case ImpulseInterpreter.OP_SET_CONVERT ->
								MethodHandles.insertArguments(MH_MORSEL_B2F, 0, mdst, ms1);
							case ImpulseInterpreter.OP_MORSEL_COMMIT -> {
								if ((mflg & ImpulseInterpreter.FLAG_ACCUM) == 0) {
									if (msub == 0)
										clearFMask |= (1 << mdst);
									else if (msub == 2)
										clearVMask |= (1 << mdst);
								}
								yield switch (msub) {
									case 0 -> MethodHandles.insertArguments(MH_MORSEL_COMMIT_FRONTIER, 0, mdst, ms1, ms3,
											(mflg & ImpulseInterpreter.FLAG_MASK) != 0);
									case 2 -> MethodHandles.insertArguments(MH_MORSEL_COMMIT_SCATTER_ADD, 0, mdst, ms1, ms2,
											ms3, (mflg & ImpulseInterpreter.FLAG_MASK) != 0);
									default -> throw new IllegalArgumentException(
											"Unsupported legacy JIT morsel commit subop: " + msub);
								};
							}
							default -> throw new IllegalArgumentException(
									"Unsupported morsel op: 0x" + Integer.toHexString(mop));
						};
						body = (body == null) ? mstep : MethodHandles.foldArguments(mstep, body);
					}
					step = MethodHandles.insertArguments(MH_WALK_MORSEL, 0, rel.getRowOffsetsSegment(),
							rel.getColumnTargetsSegment(), (int) rel.getEdgeIndexWidth(), (int) rel.getNodeIdWidth(),
							s1, s3, (flags & ImpulseInterpreter.FLAG_MASK) != 0, tgtDom, tgtCard, clearFMask,
							clearVMask, body);
					pc = endPc + 1;
				}
				default -> throw new IllegalArgumentException("Unsupported JIT op: 0x" + Integer.toHexString(op));
			}
			chain = MethodHandles.foldArguments(step, chain);
		}
		return chain;
	}

	private static void opLoadConstF32(int dst, long f64Bits, boolean isZero, ImpulseWorkspace ws) {
		ws.rRegs[dst] = f64Bits;
		ws.rValidMask |= (1 << dst);
		ws.zf = isZero;
	}

	private static void opFseedImm(int dst, int nodeId, int domId, int domCard, boolean accum, ImpulseWorkspace ws) {
		ImpulseFrontier fd = ws.fRegs[dst];
		if (!accum)
			fd.resetEmpty(domId, domCard);
		fd.add(nodeId);
		ws.zf = false;
	}

	private static void opInitDomainSpan(int dst, int domId, int domCard, ImpulseWorkspace ws) {
		ws.fRegs[dst].initAllSpan(domId, domCard);
		ws.zf = (domCard == 0);
	}

	private static void opSetUnion(int dst, int s1, int s2, ImpulseWorkspace ws) {
		ImpulseFrontier fd = ws.fRegs[dst];
		ImpulseFrontier fa = ws.fRegs[s1];
		ImpulseFrontier fb = ws.fRegs[s2];
		if (fd != fa)
			fd.copyFrom(fa);
		switch (fb.tier) {
			case ImpulseFrontier.TIER0_INLINE -> {
				for (int i = 0; i < fb.inlineCount; i++)
					fd.add(fb.inlineIds[i]);
			}
			case ImpulseFrontier.TIER1_PAGED -> {
				for (int s = 0; s < fb.dirtyPageCount; s++) {
					int baseNode = fb.dirtyPageIds[s] << ImpulseFrontier.PAGE_SHIFT;
					int baseWord = s << 6;
					for (int w = 0; w < ImpulseFrontier.PAGE_WORDS; w++) {
						long bits = fb.pageBits[baseWord + w];
						while (bits != 0L) {
							fd.add(baseNode + (w << 6) + Long.numberOfTrailingZeros(bits));
							bits &= (bits - 1L);
						}
					}
				}
			}
			case ImpulseFrontier.TIER2_DENSE -> {
				for (int w = 0; w < fb.denseWordCount; w++) {
					long bits = fb.denseWords[w];
					while (bits != 0L) {
						fd.add((w << 6) + Long.numberOfTrailingZeros(bits));
						bits &= (bits - 1L);
					}
				}
			}
			default -> {
			}
		}
		ws.zf = fd.isEmpty();
	}

	private static void opVecInitF64(int dst, int len, double fill, ImpulseWorkspace ws) {
		ws.ensureLegacyJitBuffers();
		Arrays.fill(ws.vF64[dst], 0, len, fill);
		ws.vLen[dst] = len;
	}

	private static void opWalk(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth, int dst,
			int s1, int s3, int tgtDom, int tgtCard, boolean masked, boolean accum, ImpulseWorkspace ws) {
		ImpulseFrontier fSrc = ws.fRegs[s1];
		ImpulseFrontier fDst = ws.fRegs[dst];
		if (!accum)
			fDst.resetEmpty(tgtDom, tgtCard);
		if (fSrc.isEmpty()) {
			ws.zf = fDst.isEmpty();
			return;
		}
		ImpulseFrontier fExcl = masked ? ws.fRegs[s3] : null;
		switch (fSrc.tier) {
			case ImpulseFrontier.TIER0_INLINE -> {
				for (int i = 0; i < fSrc.inlineCount; i++) {
					walkNode(rowOff, colTgt, edgeWidth, nodeWidth, fSrc.inlineIds[i], fExcl, fDst);
				}
			}
			case ImpulseFrontier.TIER1_PAGED -> {
				for (int s = 0; s < fSrc.dirtyPageCount; s++) {
					int baseNode = fSrc.dirtyPageIds[s] << ImpulseFrontier.PAGE_SHIFT;
					int baseWord = s << 6;
					for (int w = 0; w < ImpulseFrontier.PAGE_WORDS; w++) {
						long bits = fSrc.pageBits[baseWord + w];
						while (bits != 0L) {
							walkNode(rowOff, colTgt, edgeWidth, nodeWidth,
									baseNode + (w << 6) + Long.numberOfTrailingZeros(bits), fExcl, fDst);
							bits &= (bits - 1L);
						}
					}
				}
			}
			case ImpulseFrontier.TIER2_DENSE -> {
				for (int w = 0; w < fSrc.denseWordCount; w++) {
					long bits = fSrc.denseWords[w];
					while (bits != 0L) {
						walkNode(rowOff, colTgt, edgeWidth, nodeWidth, (w << 6) + Long.numberOfTrailingZeros(bits),
								fExcl, fDst);
						bits &= (bits - 1L);
					}
				}
			}
			case ImpulseFrontier.TIER_ALL_SPAN -> {
				for (int u = 0; u < fSrc.domainCard; u++) {
					walkNode(rowOff, colTgt, edgeWidth, nodeWidth, u, fExcl, fDst);
				}
			}
		}
		ws.zf = fDst.isEmpty();
	}

	private static void walkNode(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth, int u,
			ImpulseFrontier excl, ImpulseFrontier dst) {
		long start = (edgeWidth == 8)
				? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u * 8L)
				: Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u * 4L));
		long end = (edgeWidth == 8)
				? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) (u + 1) * 8L)
				: Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) * 4L));
		if (excl == null || excl.isEmpty()) {
			for (long e = start; e < end; e++) {
				dst.add(readNode(colTgt, nodeWidth, e));
			}
		} else {
			for (long e = start; e < end; e++) {
				int v = readNode(colTgt, nodeWidth, e);
				if (!excl.contains(v))
					dst.add(v);
			}
		}
	}

	private static void opWalkMorsel(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth, int s1,
			int s3, boolean masked, int tgtDom, int tgtCard, int clearFMask, int clearVMask, MethodHandle body,
			ImpulseWorkspace ws) throws Throwable {
		for (int d = 0; d < 8; d++) {
			if ((clearFMask & (1 << d)) != 0)
				ws.fRegs[d].resetEmpty(tgtDom, tgtCard);
			if ((clearVMask & (1 << d)) != 0) {
				Arrays.fill(ws.vF64[d], 0, tgtCard, 0.0);
				ws.vLen[d] = tgtCard;
			}
		}
		ImpulseFrontier src = ws.fRegs[s1];
		if (src.isEmpty()) {
			ws.zf = true;
			return;
		}
		int[] mSrc = ws.vU32[8];
		int[] mTgt = ws.vU32[9];
		int[] mEdge = ws.vU32[10];
		int len = 0;
		if (src.tier == ImpulseFrontier.TIER_ALL_SPAN) {
			for (int u = 0; u < src.domainCard; u++) {
				long s = (edgeWidth == 8)
						? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u * 8L)
						: Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u * 4L));
				long e = (edgeWidth == 8)
						? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) (u + 1) * 8L)
						: Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) * 4L));
				for (long edge = s; edge < e; edge++) {
					mSrc[len] = u;
					mTgt[len] = readNode(colTgt, nodeWidth, edge);
					mEdge[len] = (int) edge;
					if (++len == ImpulseWorkspace.MORSEL_MAX) {
						body.invokeExact(ws, len);
						len = 0;
					}
				}
			}
		}
		if (len > 0) {
			body.invokeExact(ws, len);
		}
	}

	private static void morselGatherF32(MemorySegment seg, int dst, int s1, ImpulseWorkspace ws, int len) {
		int[] idx = ws.vU32[s1];
		double[] out = ws.vF64[dst];
		for (int i = 0; i < len; i++) {
			out[i] = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) idx[i] * 4L);
		}
	}

	private static void morselGatherF64(MemorySegment seg, int dst, int s1, ImpulseWorkspace ws, int len) {
		int[] idx = ws.vU32[s1];
		double[] out = ws.vF64[dst];
		for (int i = 0; i < len; i++) {
			out[i] = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) idx[i] * 8L);
		}
	}

	private static void morselCmpGtScalar(int dst, int s1, int s2, ImpulseWorkspace ws, int len) {
		double[] lhs = ws.vF64[s1];
		double rhs = Double.longBitsToDouble(ws.rRegs[s2]);
		long[] outBits = ws.vBool[dst];
		int words = (len + 63) >>> 6;
		Arrays.fill(outBits, 0, words, 0L);
		for (int i = 0; i < len; i++) {
			if (lhs[i] > rhs)
				outBits[i >>> 6] |= (1L << i);
		}
	}

	private static void morselB2f(int dst, int s1, ImpulseWorkspace ws, int len) {
		System.arraycopy(ws.vBool[s1], 0, ws.fRegs[dst].denseWords, 0, (len + 63) >>> 6);
	}

	private static void morselCommitFrontier(int dst, int s1, int s3, boolean masked, ImpulseWorkspace ws, int len) {
		long[] maskWords = ws.fRegs[masked ? s3 : 8].denseWords;
		int[] tgtIds = ws.vU32[s1];
		ImpulseFrontier fDst = ws.fRegs[dst];
		int words = (len + 63) >>> 6;
		for (int wi = 0; wi < words; wi++) {
			long bits = maskWords[wi];
			while (bits != 0L) {
				fDst.add(tgtIds[(wi << 6) + Long.numberOfTrailingZeros(bits)]);
				bits &= (bits - 1L);
			}
		}
	}

	private static void morselCommitScatterAdd(int dst, int s1, int s2, int s3, boolean masked, ImpulseWorkspace ws,
			int len) {
		long[] maskWords = ws.fRegs[masked ? s3 : 8].denseWords;
		int[] tgtIds = ws.vU32[s1];
		double[] vals = ws.vF64[s2];
		double[] outVec = ws.vF64[dst];
		int words = (len + 63) >>> 6;
		for (int wi = 0; wi < words; wi++) {
			long bits = maskWords[wi];
			while (bits != 0L) {
				int lane = (wi << 6) + Long.numberOfTrailingZeros(bits);
				outVec[tgtIds[lane]] += vals[lane];
				bits &= (bits - 1L);
			}
		}
	}

	private static int readNode(MemorySegment seg, int width, long edgeIdx) {
		return switch (width) {
			case 2 -> Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, edgeIdx * 2L));
			case 8 -> (int) seg.get(ValueLayout.JAVA_LONG_UNALIGNED, edgeIdx * 8L);
			default -> seg.get(ValueLayout.JAVA_INT_UNALIGNED, edgeIdx * 4L);
		};
	}
}
