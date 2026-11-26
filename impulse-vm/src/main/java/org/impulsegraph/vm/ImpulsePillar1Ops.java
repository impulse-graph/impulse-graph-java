package org.impulsegraph.vm;

import java.util.Arrays;
import java.util.TreeMap;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Normative Pillar 1 Evaluator ({@code 0x00}–{@code 0x14}, 21 opcodes / 107
 * subops) for ImpulseVM v0.11.0.
 *
 * <p>
 * Bit-identical to {@code V11_eval_p1} in {@code impulse-ocaml}:
 * lifecycle/traps, constants/pool/params, moves/swaps/spills, scalar int/fp/3VL
 * ALU, jumps/JCC/LOOP_DECR, variable-stride sliding windows
 * ({@code CALL}/{@code RET}), and {@code SET_MAX_DOP}.
 */
public final class ImpulsePillar1Ops {

	public static final int RESULT_UNHANDLED = 0;
	public static final int RESULT_STEPPED = 1;
	public static final int RESULT_HALTED = 2;

	private ImpulsePillar1Ops() {
	}

	public static int step(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long[] program, long w) {
		final int op = (int) (w & 0xFFL);
		final int subop = (int) ((w >>> 8) & 0xFL);
		final int flags = (int) ((w >>> 12) & 0xFL);
		final int s1 = (int) ((w >>> 16) & 0xFL);
		final int dst = (int) ((w >>> 20) & 0xFL);
		final int s3 = (int) ((w >>> 24) & 0xFL);
		final int s2 = (int) ((w >>> 28) & 0xFL);
		final int imm32 = (int) (w >>> 32);
		final int imm16Lo = imm32 & 0xFFFF;
		final int imm16Hi = (imm32 >>> 16) & 0xFFFF;

		switch (op) {
			case OP_HALT -> {
				return RESULT_HALTED;
			}
			case OP_NOP -> {
				ws.pc++;
				return RESULT_STEPPED;
			}
			case OP_TRAP -> {
				ws.userThrowCode = imm32;
				ws.status = IMPULSE_VM_ERR_TRAP;
				return RESULT_STEPPED;
			}
			case OP_THROW -> {
				ws.userThrowCode = imm32;
				if (subop == 0) {
					ws.hasUserThrowPayload = false;
					ws.userThrowPayload = 0L;
				} else if (subop == 1) {
					if (ws.isRValid(s1)) {
						ws.hasUserThrowPayload = true;
						ws.userThrowPayload = ws.rRegs[s1];
					} else {
						ws.hasUserThrowPayload = false;
						ws.userThrowPayload = 0L;
					}
				}
				ws.status = IMPULSE_VM_ERR_USER_THROW;
				return RESULT_STEPPED;
			}
			case OP_ASSERT -> {
				boolean ok = switch (subop) {
					case 0 -> ws.isRValid(s1) && ws.rRegs[s1] != 0L;
					case 1 -> !isFrontierEmpty(ws, s1);
					case 2 -> isFrontierEmpty(ws, s1);
					case 3 -> ws.zf;
					case 4 -> !ws.zf;
					case 5 -> ws.st;
					case 6 -> ws.isRValid(s1);
					case 7 -> !ws.isRValid(s1);
					default -> false;
				};
				if (ok) {
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_ASSERTION_FAILED;
				}
				return RESULT_STEPPED;
			}
			case OP_LOAD_CONST -> {
				execLoadConst(ws, program, subop, dst, imm32);
				return RESULT_STEPPED;
			}
			case OP_LOAD_CONST_POOL -> {
				execLoadConstPool(ws, bind, subop, flags, dst, imm32, imm16Lo, imm16Hi);
				return RESULT_STEPPED;
			}
			case OP_LOAD_PARAM -> {
				execLoadParam(ws, bind, subop, flags, dst, imm16Lo, imm16Hi);
				return RESULT_STEPPED;
			}
			case OP_MOV -> {
				execMov(ws, bind, subop, dst, s1, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_SWAP -> {
				execSwap(ws, subop, dst, s1);
				return RESULT_STEPPED;
			}
			case OP_SPILL -> {
				execSpill(ws, subop, s1, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_RELOAD -> {
				execReload(ws, subop, dst, imm16Lo);
				return RESULT_STEPPED;
			}
			case OP_SCALAR_INT_ALU -> {
				execScalarIntAlu(ws, subop, dst, s1, s2, s3, imm32);
				return RESULT_STEPPED;
			}
			case OP_SCALAR_FP_ALU -> {
				execScalarFpAlu(ws, subop, dst, s1, s2, s3);
				return RESULT_STEPPED;
			}
			case OP_SCALAR_3VL -> {
				execScalar3vl(ws, subop, dst, s1, s2);
				return RESULT_STEPPED;
			}
			case OP_JMP -> {
				ws.pc += imm32;
				return RESULT_STEPPED;
			}
			case OP_JCC -> {
				boolean take = switch (subop) {
					case 0 -> ws.zf;
					case 1 -> !ws.zf;
					case 2 -> ws.st;
					case 3 -> !ws.st;
					case 4 -> ws.nf;
					case 5 -> !ws.nf;
					case 6 -> ws.isRValid(s1) && ws.rRegs[s1] != 0L;
					case 7 -> !ws.isRValid(s1) || ws.rRegs[s1] == 0L;
					case 8 -> isFrontierEmpty(ws, s1);
					case 9 -> !isFrontierEmpty(ws, s1);
					default -> false;
				};
				ws.pc = take ? (ws.pc + imm32) : (ws.pc + 1);
				return RESULT_STEPPED;
			}
			case OP_LOOP_DECR -> {
				if (!ws.isRValid(dst)) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				} else {
					long next = ws.rRegs[dst] - 1L;
					int ty = ws.readRType(dst);
					ws.writeR(dst, ty, next);
					ws.zf = (next == 0L);
					ws.pc = (next > 0L) ? (ws.pc + imm32) : (ws.pc + 1);
				}
				return RESULT_STEPPED;
			}
			case OP_CALL -> {
				execCall(ws, subop, s1, s2, s3, imm32);
				return RESULT_STEPPED;
			}
			case OP_RET -> {
				execRet(ws);
				return RESULT_STEPPED;
			}
			case OP_SET_MAX_DOP -> {
				execSetMaxDop(ws, subop, s1, imm16Lo);
				return RESULT_STEPPED;
			}
			default -> {
				return RESULT_UNHANDLED;
			}
		}
	}

	private static boolean isFrontierEmpty(ImpulseWorkspace ws, int reg) {
		return !ws.isFInitialized(reg) || ws.fRegs[reg].isEmpty();
	}

	private static void setScalarFlags(ImpulseWorkspace ws, boolean valid, long val) {
		if (!valid) {
			ws.zf = true;
			ws.nf = true;
		} else {
			ws.zf = (val == 0L);
			ws.nf = false;
		}
	}

	private static void setF64Flags(ImpulseWorkspace ws, boolean valid, double val) {
		if (!valid) {
			ws.zf = true;
			ws.nf = true;
		} else {
			ws.zf = (val == 0.0);
			ws.nf = false;
		}
	}

	private static void execLoadConst(ImpulseWorkspace ws, long[] program, int subop, int dst, int imm32) {
		switch (subop) {
			case 0 -> { // .i32
				long v = imm32;
				ws.writeR(dst, SCALAR_I64, v);
				setScalarFlags(ws, true, v);
				ws.pc++;
			}
			case 1 -> { // .u32
				long v = imm32 & 0xFFFFFFFFL;
				ws.writeR(dst, SCALAR_U64, v);
				setScalarFlags(ws, true, v);
				ws.pc++;
			}
			case 2 -> { // .f32 -> f64
				double f = Float.intBitsToFloat(imm32);
				if (Double.isNaN(f)) {
					ws.writeRNull(dst);
					setF64Flags(ws, false, 0.0);
				} else {
					ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(f));
					setF64Flags(ws, true, f);
				}
				ws.pc++;
			}
			case 3 -> { // .f32_raw
				float f = Float.intBitsToFloat(imm32);
				if (Float.isNaN(f)) {
					ws.writeRNull(dst);
					setScalarFlags(ws, false, 0L);
				} else {
					ws.writeR(dst, SCALAR_F32_RAW, imm32 & 0xFFFFFFFFL);
					ws.zf = (f == 0.0f);
					ws.nf = false;
				}
				ws.pc++;
			}
			case 4 -> { // .i64.ext
				long w2 = program[ws.pc + 1];
				long lo = imm32 & 0xFFFFFFFFL;
				long hi = (w2 >>> 32) << 32;
				long v = hi | lo;
				ws.writeR(dst, SCALAR_I64, v);
				setScalarFlags(ws, true, v);
				ws.pc += 2;
			}
			case 5 -> { // .u64.ext
				long w2 = program[ws.pc + 1];
				long lo = imm32 & 0xFFFFFFFFL;
				long hi = (w2 >>> 32) << 32;
				long v = hi | lo;
				ws.writeR(dst, SCALAR_U64, v);
				setScalarFlags(ws, true, v);
				ws.pc += 2;
			}
			case 6 -> { // .f64.ext
				long w2 = program[ws.pc + 1];
				long lo = imm32 & 0xFFFFFFFFL;
				long hi = (w2 >>> 32) << 32;
				long bits = hi | lo;
				double f = Double.longBitsToDouble(bits);
				if (Double.isNaN(f)) {
					ws.writeRNull(dst);
					setF64Flags(ws, false, 0.0);
				} else {
					ws.writeR(dst, SCALAR_F64, bits);
					setF64Flags(ws, true, f);
				}
				ws.pc += 2;
			}
			case 7 -> { // .null
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static int getDomainCard(ImpulseInterpreter.SnapshotBinding bind, int domId) {
		if (bind == null) {
			return -IMPULSE_VM_ERR_NULL_SNAPSHOT;
		}
		if (domId < 0 || domId >= bind.domainCards.length) {
			return -IMPULSE_VM_ERR_OUT_OF_BOUNDS;
		}
		return bind.domainCards[domId];
	}

	private static int[] validateAndDedupeIds(long[] ids, int domCard) {
		if (ids == null || ids.length == 0) {
			return new int[0];
		}
		int[] tmp = new int[ids.length];
		for (int i = 0; i < ids.length; i++) {
			long u = ids[i];
			if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
				return null;
			}
			tmp[i] = (int) u;
		}
		Arrays.sort(tmp);
		int uniq = 1;
		for (int i = 1; i < tmp.length; i++) {
			if (tmp[i] != tmp[uniq - 1]) {
				tmp[uniq++] = tmp[i];
			}
		}
		return uniq == tmp.length ? tmp : Arrays.copyOf(tmp, uniq);
	}

	private static int[] mergeSortedUnique(int[] a, int[] b) {
		int[] out = new int[a.length + b.length];
		int i = 0, j = 0, k = 0;
		while (i < a.length && j < b.length) {
			if (a[i] < b[j]) {
				out[k++] = a[i++];
			} else if (b[j] < a[i]) {
				out[k++] = b[j++];
			} else {
				out[k++] = a[i++];
				j++;
			}
		}
		while (i < a.length)
			out[k++] = a[i++];
		while (j < b.length)
			out[k++] = b[j++];
		return k == out.length ? out : Arrays.copyOf(out, k);
	}

	private static void loadFrontierIds(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int dst,
			int domId, long[] ids, boolean accum) {
		int domCard = getDomainCard(bind, domId);
		if (domCard < 0) {
			ws.status = -domCard;
			return;
		}
		int[] incoming = validateAndDedupeIds(ids, domCard);
		if (incoming == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int[] merged;
		if (accum) {
			if (!ws.isFInitialized(dst)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			ImpulseFrontier existing = ws.fRegs[dst];
			if (existing.domainId != domId) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			if (existing.multiLane) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			merged = mergeSortedUnique(existing.toSortedArray(), incoming);
		} else {
			merged = incoming;
		}
		ws.fRegs[dst].initFromValidatedIds(domId, domCard, merged, ws.geometry, true);
		ws.setFInitialized(dst, true);
		ws.zf = (merged.length == 0);
		ws.pc++;
	}

	private static void execLoadConstPool(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int imm32, int imm16Lo, int imm16Hi) {
		switch (subop) {
			case 0 -> { // .vec
				ImpulseWorkspace.ConstPoolEntry entry = ws.constPool.get(imm32);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.PoolVec pv) {
					ws.vVectors[dst] = pv.vec();
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			case 1 -> { // .frontier
				ImpulseWorkspace.ConstPoolEntry entry = ws.constPool.get(imm16Lo);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.PoolFrontier pf) {
					loadFrontierIds(ws, bind, dst, imm16Hi, pf.ids(), (flags & FLAG_ACCUM) != 0);
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			case 2 -> { // .str
				ImpulseWorkspace.ConstPoolEntry entry = ws.constPool.get(imm32);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.PoolStr ps) {
					long h = ws.internScalarStr(ps.value());
					ws.writeR(dst, SCALAR_STR_HANDLE, h);
					ws.zf = ps.value().isEmpty();
					ws.nf = false;
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execLoadParam(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int pid, int hi) {
		switch (subop) {
			case 0 -> { // .scalar
				ImpulseWorkspace.ParamEntry entry = ws.params.get(pid);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.ParamScalar ps) {
					boolean validExpectedTag = (hi >= 0 && hi <= 6);
					boolean expOk = validExpectedTag
							&& (hi == ps.scalarType() || (!ps.valid() && ps.scalarType() == SCALAR_NULL_UNSET));
					if (!expOk) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					} else {
						if (ps.scalarType() == SCALAR_F64 && ps.valid()) {
							double f = Double.longBitsToDouble(ps.bits());
							if (Double.isNaN(f)) {
								ws.writeRNull(dst);
								setF64Flags(ws, false, 0.0);
							} else {
								ws.writeR(dst, SCALAR_F64, ps.bits());
								setF64Flags(ws, true, f);
							}
						} else if (!ps.valid()) {
							ws.writeRNull(dst);
							setScalarFlags(ws, false, 0L);
						} else {
							ws.writeR(dst, ps.scalarType(), ps.bits());
							setScalarFlags(ws, true, ps.bits());
						}
						ws.pc++;
					}
				} else if (entry instanceof ImpulseWorkspace.ParamStr pstr) {
					if (hi != SCALAR_STR_HANDLE) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					} else {
						if (pstr.value() == null) {
							ws.writeRNull(dst);
							setScalarFlags(ws, false, 0L);
						} else {
							long h = ws.internScalarStr(pstr.value());
							ws.writeR(dst, SCALAR_STR_HANDLE, h);
							ws.zf = pstr.value().isEmpty();
							ws.nf = false;
						}
						ws.pc++;
					}
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			case 1 -> { // .frontier
				ImpulseWorkspace.ParamEntry entry = ws.params.get(pid);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.ParamFrontier pf) {
					if (pf.domainId() != hi) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					} else {
						loadFrontierIds(ws, bind, dst, hi, pf.ids(), (flags & FLAG_ACCUM) != 0);
					}
				} else if (entry instanceof ImpulseWorkspace.ParamMultiLane ml) {
					if (ml.domainId() != hi) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					} else {
						int domCard = getDomainCard(bind, hi);
						if (domCard < 0) {
							ws.status = -domCard;
						} else {
							TreeMap<Long, Long> map = new TreeMap<>();
							long[] nodes = ml.nodes();
							long[] masks = ml.masks();
							for (int i = 0; i < nodes.length; i++) {
								long u = nodes[i];
								long m = masks[i];
								if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
									ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
									return;
								}
								if (m != 0L) {
									map.put(u, m);
								}
							}
							long[] sNodes = new long[map.size()];
							long[] sMasks = new long[map.size()];
							int idx = 0;
							for (var e : map.entrySet()) {
								sNodes[idx] = e.getKey();
								sMasks[idx] = e.getValue();
								idx++;
							}
							ws.fRegs[dst].initMultiLane(hi, domCard, ml.allocWidth(), ml.activeLaneCount(), sNodes,
									sMasks, ws.geometry);
							ws.setFInitialized(dst, true);
							ws.zf = (sNodes.length == 0);
							ws.pc++;
						}
					}
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			case 2 -> { // .vec
				ImpulseWorkspace.ParamEntry entry = ws.params.get(pid);
				if (entry == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				} else if (entry instanceof ImpulseWorkspace.ParamVec pv) {
					if (!pv.vec().matchesExpectedParamTag(hi)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					} else {
						ws.vVectors[dst] = pv.vec();
						ws.pc++;
					}
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execMov(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int dst,
			int s1, int imm16Lo) {
		switch (subop) {
			case 0 -> { // .r
				if (!ws.isRValid(s1)) {
					ws.writeRNull(dst);
					setScalarFlags(ws, false, 0L);
				} else {
					int ty = ws.readRType(s1);
					long v = ws.rRegs[s1];
					ws.writeR(dst, ty, v);
					if (ty == SCALAR_F64) {
						setF64Flags(ws, true, Double.longBitsToDouble(v));
					} else {
						setScalarFlags(ws, true, v);
					}
				}
				ws.pc++;
			}
			case 1 -> { // .f
				if (!ws.isFInitialized(s1)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				} else {
					if (dst != s1) {
						ws.fRegs[dst].copyFrom(ws.fRegs[s1]);
						ws.setFInitialized(dst, true);
					}
					ws.zf = ws.fRegs[dst].isEmpty();
					ws.pc++;
				}
			}
			case 2 -> { // .v
				if (ws.vVectors[s1] == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				} else {
					if (dst != s1) {
						ws.vVectors[dst] = ws.vVectors[s1];
					}
					ws.pc++;
				}
			}
			case 3 -> { // .clear_r
				ws.writeR(dst, SCALAR_I64, 0L);
				setScalarFlags(ws, true, 0L);
				ws.pc++;
			}
			case 4 -> { // .clear_f
				int domCard = getDomainCard(bind, imm16Lo);
				if (domCard < 0) {
					ws.status = -domCard;
				} else {
					ws.fRegs[dst].resetEmpty(imm16Lo, domCard);
					ws.setFInitialized(dst, true);
					ws.zf = true;
					ws.pc++;
				}
			}
			case 5 -> { // .clear_v
				ws.vVectors[dst] = null;
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execSwap(ImpulseWorkspace ws, int subop, int dst, int s1) {
		switch (subop) {
			case 0 -> { // .r
				boolean vd = ws.isRValid(dst);
				long valD = ws.rRegs[dst];
				int tyD = ws.readRType(dst);
				boolean vs = ws.isRValid(s1);
				long valS = ws.rRegs[s1];
				int tyS = ws.readRType(s1);
				if (vs)
					ws.writeR(dst, tyS, valS);
				else
					ws.writeRNull(dst);
				if (vd)
					ws.writeR(s1, tyD, valD);
				else
					ws.writeRNull(s1);
				ws.pc++;
			}
			case 1 -> { // .f
				boolean initD = ws.isFInitialized(dst);
				boolean initS = ws.isFInitialized(s1);
				ImpulseFrontier tmp = ws.fRegs[dst];
				ws.fRegs[dst] = ws.fRegs[s1];
				ws.fRegs[s1] = tmp;
				ws.setFInitialized(dst, initS);
				ws.setFInitialized(s1, initD);
				ws.pc++;
			}
			case 2 -> { // .v
				ImpulseVector tmp = ws.vVectors[dst];
				ws.vVectors[dst] = ws.vVectors[s1];
				ws.vVectors[s1] = tmp;
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execSpill(ImpulseWorkspace ws, int subop, int s1, int slot) {
		if (slot < 0 || slot >= ImpulseWorkspace.SPILL_PER_FRAME) {
			ws.status = IMPULSE_VM_ERR_STACK_OVERFLOW;
			return;
		}
		int idx = ws.callDepth * ImpulseWorkspace.SPILL_PER_FRAME + slot;
		switch (subop) {
			case 0 -> {
				ws.spillKind[idx] = ImpulseWorkspace.SPILL_R;
				ws.spillRVal[idx] = ws.rRegs[s1];
				ws.spillRValid[idx] = ws.isRValid(s1);
				ws.spillRType[idx] = ws.readRType(s1);
				ws.pc++;
			}
			case 1 -> {
				if (!ws.isFInitialized(s1)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				} else {
					ws.spillKind[idx] = ImpulseWorkspace.SPILL_F;
					ws.spillF[idx] = ws.fRegs[s1].snapshotCopy();
					ws.pc++;
				}
			}
			case 2 -> {
				if (ws.vVectors[s1] == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				} else {
					ws.spillKind[idx] = ImpulseWorkspace.SPILL_V;
					ws.spillV[idx] = ws.vVectors[s1];
					ws.pc++;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	private static void execReload(ImpulseWorkspace ws, int subop, int dst, int slot) {
		if (slot < 0 || slot >= ImpulseWorkspace.SPILL_PER_FRAME) {
			ws.status = IMPULSE_VM_ERR_STACK_OVERFLOW;
			return;
		}
		int idx = ws.callDepth * ImpulseWorkspace.SPILL_PER_FRAME + slot;
		int kind = ws.spillKind[idx];
		if (subop == 0 && kind == ImpulseWorkspace.SPILL_R) {
			boolean valid = ws.spillRValid[idx];
			long val = ws.spillRVal[idx];
			int ty = ws.spillRType[idx];
			if (!valid) {
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
			} else {
				ws.writeR(dst, ty, val);
				if (ty == SCALAR_F64) {
					setF64Flags(ws, true, Double.longBitsToDouble(val));
				} else {
					setScalarFlags(ws, true, val);
				}
			}
			ws.pc++;
		} else if (subop == 1 && kind == ImpulseWorkspace.SPILL_F) {
			ws.fRegs[dst].copyFrom(ws.spillF[idx]);
			ws.setFInitialized(dst, true);
			ws.zf = ws.fRegs[dst].isEmpty();
			ws.pc++;
		} else if (subop == 2 && kind == ImpulseWorkspace.SPILL_V) {
			ws.vVectors[dst] = ws.spillV[idx];
			ws.pc++;
		} else {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
		}
	}

	private static void execScalarIntAlu(ImpulseWorkspace ws, int subop, int dst, int s1, int s2, int s3, int imm32) {
		boolean aValid = ws.isRValid(s1);
		long a = ws.rRegs[s1];
		if (subop == 10) { // .not (unary)
			if (!aValid) {
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
			} else {
				long r = ~a;
				ws.writeR(dst, SCALAR_I64, r);
				setScalarFlags(ws, true, r);
			}
			ws.pc++;
			return;
		}
		boolean bValid = (s3 == 1) || ws.isRValid(s2);
		long b = (s3 == 1) ? imm32 : ws.rRegs[s2];
		if (!aValid || !bValid) {
			ws.writeRNull(dst);
			setScalarFlags(ws, false, 0L);
			ws.pc++;
			return;
		}
		int outTy = (subop == 4 || subop == 6 || subop == 12) ? SCALAR_U64 : SCALAR_I64;
		boolean resValid = true;
		long r = 0L;
		switch (subop) {
			case 0 -> r = a + b;
			case 1 -> r = a - b;
			case 2 -> r = a * b;
			case 3 -> {
				if (b == 0L) {
					resValid = false;
				} else if (a == Long.MIN_VALUE && b == -1L) {
					r = Long.MIN_VALUE;
				} else {
					r = a / b;
				}
			}
			case 4 -> {
				if (b == 0L)
					resValid = false;
				else
					r = Long.divideUnsigned(a, b);
			}
			case 5 -> {
				if (b == 0L) {
					resValid = false;
				} else if (a == Long.MIN_VALUE && b == -1L) {
					r = 0L;
				} else {
					r = a % b;
				}
			}
			case 6 -> {
				if (b == 0L)
					resValid = false;
				else
					r = Long.remainderUnsigned(a, b);
			}
			case 7 -> r = a & b;
			case 8 -> r = a | b;
			case 9 -> r = a ^ b;
			case 11 -> r = a << (int) (b & 63L);
			case 12 -> r = a >>> (int) (b & 63L);
			case 13 -> r = a >> (int) (b & 63L);
			case 14 -> r = Math.min(a, b);
			case 15 -> r = Math.max(a, b);
			default -> resValid = false;
		}
		if (!resValid) {
			ws.writeRNull(dst);
			setScalarFlags(ws, false, 0L);
		} else {
			ws.writeR(dst, outTy, r);
			setScalarFlags(ws, true, r);
		}
		ws.pc++;
	}

	private static double ieeeMinF64(double a, double b) {
		if (a == 0.0 && b == 0.0) {
			return Double.longBitsToDouble(Double.doubleToRawLongBits(a) | Double.doubleToRawLongBits(b));
		}
		return (a < b) ? a : b;
	}

	private static double ieeeMaxF64(double a, double b) {
		if (a == 0.0 && b == 0.0) {
			return Double.longBitsToDouble(Double.doubleToRawLongBits(a) & Double.doubleToRawLongBits(b));
		}
		return (a > b) ? a : b;
	}

	private static double roundHalfAway(double x) {
		if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) >= 4503599627370496.0) {
			return x;
		}
		double a = Math.abs(x);
		double f = Math.floor(a);
		double r = (a - f >= 0.5) ? (f + 1.0) : f;
		return Math.copySign(r, x);
	}

	private static void writeF64Result(ImpulseWorkspace ws, int dst, boolean valid, double f) {
		if (!valid || Double.isNaN(f)) {
			ws.writeRNull(dst);
			ws.zf = true;
			ws.nf = true;
		} else {
			ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(f));
			ws.zf = (f == 0.0);
			ws.nf = false;
		}
	}

	private static void execScalarFpAlu(ImpulseWorkspace ws, int subop, int dst, int s1, int s2, int s3) {
		boolean aRawValid = ws.isRValid(s1);
		double a = aRawValid ? Double.longBitsToDouble(ws.rRegs[s1]) : Double.NaN;
		boolean aValid = aRawValid && !Double.isNaN(a);
		boolean bRawValid = ws.isRValid(s2);
		double b = bRawValid ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
		boolean bValid = bRawValid && !Double.isNaN(b);
		boolean cRawValid = ws.isRValid(s3);
		double c = cRawValid ? Double.longBitsToDouble(ws.rRegs[s3]) : Double.NaN;
		boolean cValid = cRawValid && !Double.isNaN(c);

		switch (subop) {
			case 0 -> writeF64Result(ws, dst, aValid && bValid, a + b);
			case 1 -> writeF64Result(ws, dst, aValid && bValid, a - b);
			case 2 -> writeF64Result(ws, dst, aValid && bValid, a * b);
			case 3 -> {
				if (!aValid || !bValid || b == 0.0)
					writeF64Result(ws, dst, false, 0.0);
				else
					writeF64Result(ws, dst, true, a / b);
			}
			case 4 -> writeF64Result(ws, dst, aValid, -a);
			case 5 -> writeF64Result(ws, dst, aValid, Math.abs(a));
			case 6 -> {
				if (!aValid || a < 0.0)
					writeF64Result(ws, dst, false, 0.0);
				else
					writeF64Result(ws, dst, true, Math.sqrt(a));
			}
			case 7 -> writeF64Result(ws, dst, aValid && bValid, ieeeMinF64(a, b));
			case 8 -> writeF64Result(ws, dst, aValid && bValid, ieeeMaxF64(a, b));
			case 9 -> writeF64Result(ws, dst, aValid && bValid && cValid, Math.fma(a, b, c));
			case 10 -> writeF64Result(ws, dst, aValid, Math.floor(a));
			case 11 -> writeF64Result(ws, dst, aValid, Math.ceil(a));
			case 12 -> writeF64Result(ws, dst, aValid, roundHalfAway(a));
			case 13 -> { // i64_to_f64
				if (!aRawValid)
					writeF64Result(ws, dst, false, 0.0);
				else
					writeF64Result(ws, dst, true, (double) ws.rRegs[s1]);
			}
			case 14 -> { // f64_to_i64 saturating trunc
				if (!aValid) {
					ws.writeRNull(dst);
					ws.zf = true;
					ws.nf = true;
				} else {
					long i;
					if (a >= 9223372036854775808.0)
						i = Long.MAX_VALUE;
					else if (a <= -9223372036854775808.0)
						i = Long.MIN_VALUE;
					else
						i = (long) (a < 0.0 ? Math.ceil(a) : Math.floor(a));
					ws.writeR(dst, SCALAR_I64, i);
					ws.zf = (i == 0L);
					ws.nf = false;
				}
			}
			case 15 -> { // safe_div: (b == 0.0 || !bValid) ? c : a / b
				if (!bValid || b == 0.0) {
					writeF64Result(ws, dst, cValid, c);
				} else if (!aValid) {
					writeF64Result(ws, dst, false, 0.0);
				} else {
					writeF64Result(ws, dst, true, a / b);
				}
			}
			default -> writeF64Result(ws, dst, false, 0.0);
		}
		ws.pc++;
	}

	private static void execScalar3vl(ImpulseWorkspace ws, int subop, int dst, int s1, int s2) {
		boolean aValid = ws.isRValid(s1);
		long a = ws.rRegs[s1];
		boolean bValid = ws.isRValid(s2);
		long b = ws.rRegs[s2];

		if (subop == 15) { // .coalesce
			if (aValid) {
				int ty = ws.readRType(s1);
				ws.writeR(dst, ty, a);
				if (ty == SCALAR_F64)
					setF64Flags(ws, true, Double.longBitsToDouble(a));
				else
					setScalarFlags(ws, true, a);
			} else if (bValid) {
				int ty = ws.readRType(s2);
				ws.writeR(dst, ty, b);
				if (ty == SCALAR_F64)
					setF64Flags(ws, true, Double.longBitsToDouble(b));
				else
					setScalarFlags(ws, true, b);
			} else {
				ws.writeRNull(dst);
				setScalarFlags(ws, false, 0L);
			}
			ws.pc++;
			return;
		}

		// 0 = NULL, 1 = FALSE, 2 = TRUE
		int kRes;
		switch (subop) {
			case 0 -> kRes = (aValid && bValid) ? (a == b ? 2 : 1) : 0;
			case 1 -> kRes = (aValid && bValid) ? (a != b ? 2 : 1) : 0;
			case 2 -> kRes = (aValid && bValid) ? (a < b ? 2 : 1) : 0;
			case 3 -> kRes = (aValid && bValid) ? (a <= b ? 2 : 1) : 0;
			case 4 -> kRes = (aValid && bValid) ? (Long.compareUnsigned(a, b) < 0 ? 2 : 1) : 0;
			case 5 -> kRes = (aValid && bValid) ? (Long.compareUnsigned(a, b) <= 0 ? 2 : 1) : 0;
			case 6, 7, 8, 9 -> {
				if (!aValid || !bValid) {
					kRes = 0;
				} else {
					double af = Double.longBitsToDouble(a);
					double bf = Double.longBitsToDouble(b);
					if (Double.isNaN(af) || Double.isNaN(bf)) {
						kRes = 0;
					} else {
						boolean p = switch (subop) {
							case 6 -> af == bf;
							case 7 -> af != bf;
							case 8 -> af < bf;
							default -> af <= bf;
						};
						kRes = p ? 2 : 1;
					}
				}
			}
			case 10 -> { // Kleene AND
				boolean aFalse = aValid && (a == 0L);
				boolean bFalse = bValid && (b == 0L);
				if (aFalse || bFalse)
					kRes = 1;
				else if (aValid && bValid)
					kRes = 2;
				else
					kRes = 0;
			}
			case 11 -> { // Kleene OR
				boolean aTrue = aValid && (a != 0L);
				boolean bTrue = bValid && (b != 0L);
				if (aTrue || bTrue)
					kRes = 2;
				else if (aValid && bValid)
					kRes = 1;
				else
					kRes = 0;
			}
			case 12 -> { // Kleene NOT
				if (!aValid)
					kRes = 0;
				else
					kRes = (a == 0L) ? 2 : 1;
			}
			case 13 -> kRes = !aValid ? 2 : 1; // is_null
			case 14 -> kRes = aValid ? 2 : 1; // is_not_null
			default -> kRes = 0;
		}

		if (kRes == 0) {
			ws.writeRNull(dst);
			setScalarFlags(ws, false, 0L);
		} else {
			long out = (kRes == 2) ? 1L : 0L;
			ws.writeR(dst, SCALAR_BOOL_3VL, out);
			setScalarFlags(ws, true, out);
		}
		ws.pc++;
	}

	private static void execCall(ImpulseWorkspace ws, int subop, int s1, int s2, int s3, int imm32) {
		if (ws.callDepth >= ImpulseWorkspace.MAX_CALL_DEPTH) {
			ws.status = IMPULSE_VM_ERR_STACK_OVERFLOW;
			return;
		}
		int rs = (subop == 1) ? 16 : s1;
		int fs = (subop == 1) ? 16 : s2;
		int vs = (subop == 1) ? 16 : s3;
		int d = ws.callDepth;

		ws.callReturnPc[d] = ws.pc + 1;
		ws.callRStride[d] = rs;
		ws.callFStride[d] = fs;
		ws.callVStride[d] = vs;

		// Save caller [0..stride-1]
		System.arraycopy(ws.rRegs, 0, ws.savedRVal[d], 0, rs);
		ws.savedRValidMask[d] = ws.rValidMask;
		ws.savedRTypeWord[d] = ws.rTypeWord;
		ws.savedFValidMask[d] = ws.fValidMask;
		for (int i = 0; i < fs; i++) {
			ws.savedF[d][i] = ws.isFInitialized(i) ? ws.fRegs[i].snapshotCopy() : null;
		}
		System.arraycopy(ws.vVectors, 0, ws.savedV[d], 0, vs);

		// Shift caller [stride..15] -> callee [0..15-stride], reset [16-stride..15]
		for (int i = 0; i < 16; i++) {
			if (i + rs < 16) {
				int src = i + rs;
				if (ws.isRValid(src)) {
					ws.writeR(i, ws.readRType(src), ws.rRegs[src]);
				} else {
					ws.writeRNull(i);
				}
			} else {
				ws.writeRNull(i);
			}
			if (i + fs < 16) {
				int src = i + fs;
				if (ws.isFInitialized(src)) {
					ws.fRegs[i].copyFrom(ws.fRegs[src]);
					ws.setFInitialized(i, true);
				} else {
					ws.setFInitialized(i, false);
				}
			} else {
				ws.setFInitialized(i, false);
			}
			ws.vVectors[i] = (i + vs < 16) ? ws.vVectors[i + vs] : null;
		}

		ws.callDepth = d + 1;
		int spillBase = ws.callDepth * ImpulseWorkspace.SPILL_PER_FRAME;
		Arrays.fill(ws.spillKind, spillBase, spillBase + ImpulseWorkspace.SPILL_PER_FRAME,
				ImpulseWorkspace.SPILL_EMPTY);
		ws.pc += imm32;
	}

	private static void execRet(ImpulseWorkspace ws) {
		if (ws.callDepth <= 0) {
			ws.status = IMPULSE_VM_ERR_STACK_UNDERFLOW;
			return;
		}
		// Release callee spill slots (RAII)
		int calleeSpillBase = ws.callDepth * ImpulseWorkspace.SPILL_PER_FRAME;
		Arrays.fill(ws.spillKind, calleeSpillBase, calleeSpillBase + ImpulseWorkspace.SPILL_PER_FRAME,
				ImpulseWorkspace.SPILL_EMPTY);

		int d = --ws.callDepth;
		int rs = ws.callRStride[d];
		int fs = ws.callFStride[d];
		int vs = ws.callVStride[d];
		int savedRVM = ws.savedRValidMask[d];
		long savedRTW = ws.savedRTypeWord[d];

		for (int i = 15; i >= 0; i--) {
			if (i >= rs) {
				int src = i - rs;
				if (ws.isRValid(src)) {
					ws.writeR(i, ws.readRType(src), ws.rRegs[src]);
				} else {
					ws.writeRNull(i);
				}
			} else {
				boolean valid = ((savedRVM >>> i) & 1) != 0;
				if (valid) {
					int ty = (int) ((savedRTW >>> (i * 4)) & 0xFL);
					ws.writeR(i, ty, ws.savedRVal[d][i]);
				} else {
					ws.writeRNull(i);
				}
			}

			if (i >= fs) {
				int src = i - fs;
				if (ws.isFInitialized(src)) {
					ws.fRegs[i].copyFrom(ws.fRegs[src]);
					ws.setFInitialized(i, true);
				} else {
					ws.setFInitialized(i, false);
				}
			} else {
				ImpulseFrontier sf = ws.savedF[d][i];
				if (sf != null) {
					ws.fRegs[i].copyFrom(sf);
					ws.setFInitialized(i, true);
				} else {
					ws.setFInitialized(i, false);
				}
			}

			ws.vVectors[i] = (i >= vs) ? ws.vVectors[i - vs] : ws.savedV[d][i];
		}

		ws.pc = ws.callReturnPc[d];
	}

	private static void execSetMaxDop(ImpulseWorkspace ws, int subop, int s1, int imm16Lo) {
		if (subop == 0) {
			int d = imm16Lo;
			if (d <= 0)
				d = 1;
			else if (d > ws.hostMaxThreads)
				d = ws.hostMaxThreads;
			ws.maxDop = d;
			ws.pc++;
		} else if (subop == 1) {
			if (!ws.isRValid(s1)) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			} else {
				long d64 = ws.rRegs[s1];
				int d;
				if (d64 <= 0L)
					d = 1;
				else if (d64 >= ws.hostMaxThreads)
					d = ws.hostMaxThreads;
				else
					d = (int) d64;
				ws.maxDop = d;
				ws.pc++;
			}
		} else {
			ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}
}
