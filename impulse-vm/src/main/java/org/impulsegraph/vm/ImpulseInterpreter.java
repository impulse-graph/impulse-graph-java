package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal Vertical-Slice Interpreter for ImpulseVM v0.11.0 (Gut-Check Benchmark
 * Prototype).
 *
 * <p>
 * Executes 64-bit nibble-encoded v0.11 bytecode over a caller-owned
 * {@link ImpulseWorkspace} and direct off-heap {@link RelationSnapshot} CSR/CSC
 * segments with zero steady-state heap allocations.
 */
public final class ImpulseInterpreter {

	// Global Modifier Flags (high nibble of Byte 1)
	public static final int FLAG_MASK = 0x1;
	public static final int FLAG_SCALAR = 0x2;
	public static final int FLAG_ACCUM = 0x4;
	public static final int FLAG_EXT = 0x8;

	// Opcodes (vertical slice)
	public static final int OP_HALT = 0x00;
	public static final int OP_NOP = 0x01;
	public static final int OP_LOAD_CONST = 0x05;
	public static final int OP_SCALAR_INT_ALU = 0x0C;
	public static final int OP_SCALAR_FP_ALU = 0x0D;
	public static final int OP_JMP = 0x0F;
	public static final int OP_JCC = 0x10;
	public static final int OP_LOOP_DECR = 0x11;
	public static final int OP_FSEED = 0x20;
	public static final int OP_INIT_DOMAIN_SPAN = 0x21;
	public static final int OP_SET_OP = 0x22;
	public static final int OP_SET_CARDINALITY = 0x24;
	public static final int OP_SET_CONVERT = 0x26;
	public static final int OP_STABLE_CHECK = 0x27;
	public static final int OP_WALK = 0x30;
	public static final int OP_SPMV = 0x34;
	public static final int OP_WALK_MORSEL = 0x35;
	public static final int OP_MORSEL_COMMIT = 0x37;
	public static final int OP_MORSEL_END = 0x38;
	public static final int OP_GATHER_EDGE_ATTR = 0x42;
	public static final int OP_VEC_CMP = 0x45;
	public static final int OP_VEC_MATH_ARITH = 0x48;
	public static final int OP_VEC_INIT = 0x50;

	private ImpulseInterpreter() {
	}

	/**
	 * Pre-bound snapshot tables (constructed once per snapshot, zero map lookup
	 * during execution).
	 */
	public static final class SnapshotBinding {
		public final RelationSnapshot[] rels;
		public final int[] domainCards;
		public final int maxDomainCard;
		public final ImpulseSnapshotView view;

		public SnapshotBinding(int[] domainCards) {
			this(domainCards, null);
		}

		public SnapshotBinding(int[] domainCards, ImpulseSnapshotView view) {
			this.rels = new RelationSnapshot[0];
			this.domainCards = domainCards != null ? domainCards.clone() : new int[0]; // INVARIANT_ALLOW: one-time
																						// SnapshotBinding bind-time
																						// init
			int maxCard = 64;
			for (int c : this.domainCards) {
				if (c > maxCard)
					maxCard = c;
			}
			this.maxDomainCard = maxCard;
			this.view = view;
		}

		public SnapshotBinding(ImpulseGraphSnapshot snap) {
			this.view = null;
			int maxRelId = -1;
			int maxDomId = 0;
			for (RelationSnapshot r : snap.getAllRelationSnapshots().values()) {
				if (r != null) {
					maxRelId = Math.max(maxRelId, r.getRelationId());
					maxDomId = Math.max(maxDomId, Math.max(r.getSrcDomainId(), r.getTgtDomainId()));
				}
			}
			this.rels = new RelationSnapshot[maxRelId + 1];
			this.domainCards = new int[maxDomId + 1]; // INVARIANT_ALLOW: one-time SnapshotBinding bind-time init
			int maxCard = 64;
			for (RelationSnapshot r : snap.getAllRelationSnapshots().values()) {
				if (r != null && r.getRelationId() >= 0) {
					this.rels[r.getRelationId()] = r;
					int srcCard = r.getNodeCount();
					int tgtCard = r.hasCsc() ? r.getCscNodeCount() : srcCard;
					String srcName = snap.getDomainName(r.getSrcDomainId());
					if (srcName != null) {
						srcCard = Math.max(srcCard, (int) snap.getNodeCount(srcName));
					}
					String tgtName = snap.getDomainName(r.getTgtDomainId());
					if (tgtName != null) {
						tgtCard = Math.max(tgtCard, (int) snap.getNodeCount(tgtName));
					}
					this.domainCards[r.getSrcDomainId()] = Math.max(this.domainCards[r.getSrcDomainId()], srcCard);
					this.domainCards[r.getTgtDomainId()] = Math.max(this.domainCards[r.getTgtDomainId()], tgtCard);
					maxCard = Math.max(maxCard,
							Math.max(this.domainCards[r.getSrcDomainId()], this.domainCards[r.getTgtDomainId()]));
				}
			}
			this.maxDomainCard = maxCard;
		}
	}

	/** Encode a canonical 64-bit v0.11 instruction word. */
	public static long encode(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
		long b0 = op & 0xFFL;
		long b1 = (((flags & 0xFL) << 4) | (subop & 0xFL));
		long b2 = (((dst & 0xFL) << 4) | (s1 & 0xFL));
		long b3 = (((s2 & 0xFL) << 4) | (s3 & 0xFL));
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | ((imm32 & 0xFFFFFFFFL) << 32);
	}

	public static int packImm16(int lo, int hi) {
		return (lo & 0xFFFF) | ((hi & 0xFFFF) << 16);
	}

	/**
	 * Validate and execute a v0.11 program using caller-owned workspace {@code ws}
	 * (retaining pre-populated {@code constPool}, {@code params}, and
	 * {@code geometry}).
	 *
	 * <p>
	 * Returns the final status code ({@link ImpulseIsaTables#IMPULSE_VM_OK} = 0 on
	 * normal HALT).
	 */
	public static int executeValidated(long[] program, SnapshotBinding bind, ImpulseWorkspace ws, long maxSteps) {
		if (!ImpulseValidator.validateInto(program, ws)) {
			return ws.status;
		}
		return executePreValidated(program, bind, ws, maxSteps);
	}

	/**
	 * Execute an already-validated v0.11 program (Lifecycle 3/4: validated once at
	 * bind time, zero per-query re-validation overhead).
	 */
	public static int executePreValidated(long[] program, SnapshotBinding bind, ImpulseWorkspace ws, long maxSteps) {
		ws.pc = 0;
		ws.status = ImpulseIsaTables.IMPULSE_VM_OK;
		final int n = program.length;
		long[] stepsRef = ws.stepsScratch;
		stepsRef[0] = 0L;

		while (ws.status == ImpulseIsaTables.IMPULSE_VM_OK) {
			if (stepsRef[0] >= maxSteps) {
				ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_GAS_EXHAUSTED;
				return ws.status;
			}
			if (ws.pc < 0 || ws.pc >= n) {
				ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return ws.status;
			}
			stepsRef[0]++;
			long w = program[ws.pc];
			int profOp = 0;
			long profT0 = 0L;
			if (ImpulseWorkspace.OPCODE_PROFILE) {
				profOp = (int) (w & 0xFFL);
				if (profOp >= 0x20 && profOp < 128) {
					profT0 = System.nanoTime();
				} else if (profOp < 128) {
					ws.opCalls[profOp]++;
				}
			}
			int p1 = ImpulsePillar1Ops.step(ws, bind, program, w);
			if (p1 == ImpulsePillar1Ops.RESULT_HALTED) {
				return ws.status;
			}
			if (p1 == ImpulsePillar1Ops.RESULT_STEPPED) {
				continue;
			}
			int p2 = ImpulsePillar2Ops.step(ws, bind, w);
			if (p2 == ImpulsePillar2Ops.RESULT_STEPPED) {
				if (ImpulseWorkspace.OPCODE_PROFILE && profOp >= 0x20 && profOp < 128) {
					ws.recordOpcode(profOp, System.nanoTime() - profT0);
				}
				continue;
			}
			int p3 = ImpulsePillar3Ops.step(ws, bind, program, w, stepsRef, maxSteps);
			if (p3 == ImpulsePillar3Ops.RESULT_STEPPED) {
				if (ImpulseWorkspace.OPCODE_PROFILE && profOp >= 0x20 && profOp < 128) {
					ws.recordOpcode(profOp, System.nanoTime() - profT0);
				}
				continue;
			}
			int p4 = ImpulsePillar4Ops.step(ws, bind, w);
			if (p4 == ImpulsePillar4Ops.RESULT_STEPPED) {
				if (ImpulseWorkspace.OPCODE_PROFILE && profOp >= 0x20 && profOp < 128) {
					ws.recordOpcode(profOp, System.nanoTime() - profT0);
				}
				continue;
			}
			int p5 = ImpulsePillar5Ops.step(ws, bind, w);
			if (p5 == ImpulsePillar5Ops.RESULT_STEPPED) {
				if (ImpulseWorkspace.OPCODE_PROFILE && profOp >= 0x20 && profOp < 128) {
					ws.recordOpcode(profOp, System.nanoTime() - profT0);
				}
				continue;
			}
			ws.status = ImpulseIsaTables.IMPULSE_VM_ERR_INVALID_OPCODE;
			return ws.status;
		}
		return ws.status;
	}

	/** Execute a v0.11 program to HALT using caller-owned workspace [ws]. */
	public static int execute(long[] program, SnapshotBinding bind, ImpulseWorkspace ws) {
		ws.reset();
		return executeValidated(program, bind, ws, Long.MAX_VALUE);
	}
}
