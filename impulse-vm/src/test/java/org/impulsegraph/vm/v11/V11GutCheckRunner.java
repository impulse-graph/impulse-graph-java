package org.impulsegraph.vm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.vm.ImpulseMethodHandleCompiler;
import org.impulsegraph.vm.ImpulseVmInterpreter;
import org.impulsegraph.vm.VmRegisterType;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A/B Gut-Check Benchmark & JFR Harness: ImpulseVM v0.10 (Interpreter + JIT) vs
 * v0.11 (Interpreter).
 *
 * <p>
 * Usage:
 * 
 * <pre>
 *   java --add-modules jdk.incubator.vector -cp ... org.impulsegraph.vm.v11.V11GutCheckRunner \
 *     [--engine v10-interp|v10-jit|v11|all] [--workload W1|W2|W3|W4|all] [--iters 5000]
 * </pre>
 */
public final class V11GutCheckRunner {

	private static final Path HETIONET_PATH = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
	private static final Path POWERGRID_PATH = Path
			.of("/Users/jesse/impulse/impulse-powergrid/datasets/case2869pegase.v09.imps");

	public static void main(String[] args) throws Throwable {
		String engine = "all";
		String workload = "all";
		int warmup = 2000;
		int iters = 5000;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--engine" -> engine = args[++i];
				case "--workload" -> workload = args[++i];
				case "--warmup" -> warmup = Integer.parseInt(args[++i]);
				case "--iters" -> iters = Integer.parseInt(args[++i]);
			}
		}

		System.out.printf(
				"=== ImpulseVM v0.10 vs v0.11 Java 25 Gut-Check (engine=%s, workload=%s, warmup=%d, iters=%d) ===%n",
				engine, workload, warmup, iters);

		try (Arena snapArena = Arena.ofShared()) {
			if (workload.equals("all") || workload.equals("W1") || workload.equals("W2")) {
				ImpulseGraphSnapshot hetio = BinarySnapshotLoader.loadSnapshot(HETIONET_PATH, snapArena).graph();
				V11Interpreter.SnapshotBinding hetioBind = new V11Interpreter.SnapshotBinding(hetio);
				V11Workspace hetioWs = new V11Workspace(hetioBind.maxDomainCard);

				// Pick a 2-hop chain rel1 (D0 -> D1) and rel2 (D1 -> D2) with non-empty edges
				RelationSnapshot rel1 = null;
				RelationSnapshot rel2 = null;
				for (RelationSnapshot r1 : hetio.getAllRelationSnapshots().values()) {
					if (r1 == null || !r1.hasCsr() || r1.getEdgeCount() < 100)
						continue;
					for (RelationSnapshot r2 : hetio.getAllRelationSnapshots().values()) {
						if (r2 == null || !r2.hasCsr() || r2.getEdgeCount() < 100)
							continue;
						if (r2.getSrcDomainId() == r1.getTgtDomainId() && r2.getRelationId() != r1.getRelationId()) {
							rel1 = r1;
							rel2 = r2;
							break;
						}
					}
					if (rel1 != null)
						break;
				}
				int seed = findSeedWithDegree(rel1);
				if (workload.equals("all") || workload.equals("W1")) {
					runW1Hetionet2Hop(hetio, hetioBind, hetioWs, rel1, rel2, seed, engine, warmup, iters);
				}
				if (workload.equals("all") || workload.equals("W2")) {
					// Pick a homogeneous relation (srcDom == tgtDom) for 3-hop BFS
					RelationSnapshot homo = null;
					for (RelationSnapshot r : hetio.getAllRelationSnapshots().values()) {
						if (r != null && r.hasCsr() && r.getSrcDomainId() == r.getTgtDomainId()
								&& r.getEdgeCount() > 500) {
							homo = r;
							break;
						}
					}
					if (homo != null) {
						int bfsSeed = findSeedWithDegree(homo);
						runW2Hetionet3HopBfs(hetio, hetioBind, hetioWs, homo, bfsSeed, engine, warmup, iters);
					}
				}
			}

			if (workload.equals("all") || workload.equals("W3") || workload.equals("W4")) {
				ImpulseGraphSnapshot pg = BinarySnapshotLoader.loadSnapshot(POWERGRID_PATH, snapArena).graph();
				V11Interpreter.SnapshotBinding pgBind = new V11Interpreter.SnapshotBinding(pg);
				V11Workspace pgWs = new V11Workspace(pgBind.maxDomainCard);
				RelationSnapshot pgRel = null;
				for (RelationSnapshot r : pg.getAllRelationSnapshots().values()) {
					if (r != null && r.hasCsr() && r.getEdgeCount() > 0) {
						pgRel = r;
						break;
					}
				}
				if (pgRel != null) {
					if (workload.equals("all") || workload.equals("W3")) {
						runW3PowerGridMorsel(pg, pgBind, pgWs, pgRel, engine, warmup, iters);
					}
					if (workload.equals("all") || workload.equals("W4")) {
						runW4PowerGridSpmv(pg, pgBind, pgWs, pgRel, engine, warmup, iters);
					}
				}
			}
		}
	}

	private static int findSeedWithDegree(RelationSnapshot rel) {
		int n = rel.getNodeCount();
		for (int u = 0; u < n; u++) {
			if (rel.getDegree(u) >= 3)
				return u;
		}
		return 0;
	}

	private static MemorySegment buildV10Prog(Arena arena, long... words) {
		MemorySegment seg = arena.allocate((long) words.length * 8L, 8L);
		for (int i = 0; i < words.length; i++) {
			seg.set(ValueLayout.JAVA_LONG_UNALIGNED, (long) i * 8L, words[i]);
		}
		return seg;
	}

	/**
	 * Encode v0.10 64-bit instruction: [opcode:8 | flags:8 | dstReg:16 |
	 * payload:32].
	 */
	private static long v10Word(byte opcode, byte flags, int dstReg, int payload) {
		return (opcode & 0xFFL) | ((flags & 0xFFL) << 8) | ((dstReg & 0xFFFFL) << 16) | ((payload & 0xFFFFFFFFL) << 32);
	}

	private static int[] bitsetToSorted(ImpulseBitSet bs) {
		int card = (int) bs.cardinality();
		int[] out = new int[card];
		int p = 0;
		for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
			out[p++] = i;
		}
		return out;
	}

	private static void runW1Hetionet2Hop(ImpulseGraphSnapshot snap, V11Interpreter.SnapshotBinding bind,
			V11Workspace ws, RelationSnapshot r1, RelationSnapshot r2, int seed, String engine, int warmup, int iters)
			throws Throwable {
		System.out.printf("%n[W1] Hetionet 2-Hop Sparse Walk: seed=%d, rel1=%d (%d edges) -> rel2=%d (%d edges)%n",
				seed, r1.getRelationId(), r1.getEdgeCount(), r2.getRelationId(), r2.getEdgeCount());

		// v0.10 program: INIT_INPUT_NODE R0 -> CSR_WALK R1=walk(R0, rel1) -> CSR_WALK
		// R2=walk(R1, rel2) -> COLLECT R2
		try (Arena progArena = Arena.ofConfined()) {
			MemorySegment v10Prog = buildV10Prog(progArena,
					v10Word(VmRegisterType.OP_INIT_INPUT_NODE, (byte) 0, 0, r1.getSrcDomainId()),
					v10Word(VmRegisterType.OP_CSR_WALK, (byte) 0, 1,
							(0 & 0xFFFF) | ((r1.getRelationId() & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_CSR_WALK, (byte) 0, 2,
							(1 & 0xFFFF) | ((r2.getRelationId() & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_COLLECT_BITSET, (byte) 0, 2, 0),
					v10Word(VmRegisterType.OP_HALT, (byte) 0, 0, 0));

			long[] v11Prog = new long[]{
					V11Interpreter.encode(V11Interpreter.OP_FSEED, 0, 1, 0, 0, 0, 0,
							V11Interpreter.packImm16(seed, r1.getSrcDomainId())),
					V11Interpreter.encode(V11Interpreter.OP_WALK, 0, 0, 1, 0, 0, 0,
							V11Interpreter.packImm16(r1.getRelationId(), 0)),
					V11Interpreter.encode(V11Interpreter.OP_WALK, 0, 0, 2, 1, 0, 0,
							V11Interpreter.packImm16(r2.getRelationId(), 0)),
					V11Interpreter.encode(V11Interpreter.OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

			// Parity check
			ImpulseBitSet v10Res = (ImpulseBitSet) ImpulseVmInterpreter.execute(v10Prog, 5, snap, seed, progArena);
			V11Interpreter.execute(v11Prog, bind, ws);
			int[] a10 = bitsetToSorted(v10Res);
			int[] a11 = ws.fRegs[2].toSortedArray();
			if (!Arrays.equals(a10, a11)) {
				throw new AssertionError("W1 PARITY MISMATCH: v10=" + a10.length + " vs v11=" + a11.length);
			}
			System.out.printf("  Parity OK: |F2| = %d nodes (v11 final tier=%d)%n", a11.length, ws.fRegs[2].tier);

			if (engine.equals("all") || engine.equals("v10-interp")) {
				bench("W1 v0.10-interp", warmup, iters, () -> {
					try (Arena q = Arena.ofConfined()) {
						ImpulseVmInterpreter.execute(v10Prog, 5, snap, seed, q);
					}
				});
			}
			if (engine.equals("all") || engine.equals("v10-jit")) {
				MethodHandle jit = ImpulseMethodHandleCompiler.compile(v10Prog, 5);
				bench("W1 v0.10-jit   ", warmup, iters, () -> {
					try (Arena q = Arena.ofConfined()) {
						jit.invoke(snap, seed, q);
					}
				});
			}
			if (engine.equals("all") || engine.equals("v11")) {
				bench("W1 v0.11-interp", warmup, iters, () -> V11Interpreter.execute(v11Prog, bind, ws));
			}
			if (engine.equals("all") || engine.equals("v11-jit")) {
				MethodHandle v11Jit = V11MethodHandleCompiler.compile(v11Prog, bind);
				v11Jit.invokeExact(ws);
				if (!Arrays.equals(a10, ws.fRegs[2].toSortedArray())) {
					throw new AssertionError("W1 v0.11-jit PARITY MISMATCH");
				}
				bench("W1 v0.11-jit   ", warmup, iters, () -> {
					v11Jit.invokeExact(ws);
				});
			}
		}
	}

	private static void runW2Hetionet3HopBfs(ImpulseGraphSnapshot snap, V11Interpreter.SnapshotBinding bind,
			V11Workspace ws, RelationSnapshot rel, int seed, String engine, int warmup, int iters) throws Throwable {
		System.out.printf("%n[W2] Hetionet 3-Hop BFS (visited exclusion): seed=%d, rel=%d (%d edges)%n", seed,
				rel.getRelationId(), rel.getEdgeCount());

		try (Arena progArena = Arena.ofConfined()) {
			int relId = rel.getRelationId();
			int domId = rel.getSrcDomainId();
			// v0.10: R0=seed bitset, R1=visited, R2=hop1, R3=hop2, R4=hop3
			int[] seedArr = new int[]{seed};
			MemorySegment v10Prog = buildV10Prog(progArena,
					v10Word(VmRegisterType.OP_INIT_INPUT_SET, (byte) 0, 0, domId),
					v10Word(VmRegisterType.OP_CSR_WALK, (byte) 0, 2, (0 & 0xFFFF) | ((relId & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_SET_UNION, (byte) 0, 1, (0 & 0xFFFF) | ((2 & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_CSR_WALK, (byte) 0, 3, (2 & 0xFFFF) | ((relId & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_SET_DIFFERENCE, (byte) 0, 3, (3 & 0xFFFF) | ((1 & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_SET_UNION, (byte) 0, 1, (1 & 0xFFFF) | ((3 & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_CSR_WALK, (byte) 0, 4, (3 & 0xFFFF) | ((relId & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_SET_DIFFERENCE, (byte) 0, 4, (4 & 0xFFFF) | ((1 & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_SET_UNION, (byte) 0, 1, (1 & 0xFFFF) | ((4 & 0xFFFF) << 16)),
					v10Word(VmRegisterType.OP_COLLECT_BITSET, (byte) 0, 1, 0),
					v10Word(VmRegisterType.OP_HALT, (byte) 0, 0, 0));

			long[] v11Prog = new long[]{
					V11Interpreter.encode(V11Interpreter.OP_FSEED, 0, 1, 0, 0, 0, 0,
							V11Interpreter.packImm16(seed, domId)),
					V11Interpreter.encode(V11Interpreter.OP_WALK, V11Interpreter.FLAG_MASK, 0, 2, 0, 0, 0,
							V11Interpreter.packImm16(relId, 0)),
					V11Interpreter.encode(V11Interpreter.OP_SET_OP, 0, 0, 1, 0, 2, 0, 0),
					V11Interpreter.encode(V11Interpreter.OP_WALK, V11Interpreter.FLAG_MASK, 0, 3, 2, 0, 1,
							V11Interpreter.packImm16(relId, 0)),
					V11Interpreter.encode(V11Interpreter.OP_SET_OP, 0, 0, 1, 1, 3, 0, 0),
					V11Interpreter.encode(V11Interpreter.OP_WALK, V11Interpreter.FLAG_MASK, 0, 4, 3, 0, 1,
							V11Interpreter.packImm16(relId, 0)),
					V11Interpreter.encode(V11Interpreter.OP_SET_OP, 0, 0, 1, 1, 4, 0, 0),
					V11Interpreter.encode(V11Interpreter.OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

			ImpulseBitSet v10Res = (ImpulseBitSet) ImpulseVmInterpreter.execute(v10Prog, 11, snap, seedArr, progArena);
			V11Interpreter.execute(v11Prog, bind, ws);
			int[] a10 = bitsetToSorted(v10Res);
			int[] a11 = ws.fRegs[1].toSortedArray();
			if (!Arrays.equals(a10, a11)) {
				throw new AssertionError("W2 PARITY MISMATCH: v10=" + a10.length + " vs v11=" + a11.length);
			}
			System.out.printf("  Parity OK: |visited| = %d nodes (v11 final tier=%d)%n", a11.length, ws.fRegs[1].tier);

			if (engine.equals("all") || engine.equals("v10-interp")) {
				bench("W2 v0.10-interp", warmup, iters, () -> {
					try (Arena q = Arena.ofConfined()) {
						ImpulseVmInterpreter.execute(v10Prog, 11, snap, seedArr, q);
					}
				});
			}
			if (engine.equals("all") || engine.equals("v10-jit")) {
				MethodHandle jit = ImpulseMethodHandleCompiler.compile(v10Prog, 11);
				bench("W2 v0.10-jit   ", warmup, iters, () -> {
					try (Arena q = Arena.ofConfined()) {
						jit.invoke(snap, seedArr, q);
					}
				});
			}
			if (engine.equals("all") || engine.equals("v11")) {
				bench("W2 v0.11-interp", warmup, iters, () -> V11Interpreter.execute(v11Prog, bind, ws));
			}
			if (engine.equals("all") || engine.equals("v11-jit")) {
				MethodHandle v11Jit = V11MethodHandleCompiler.compile(v11Prog, bind);
				v11Jit.invokeExact(ws);
				if (!Arrays.equals(a10, ws.fRegs[1].toSortedArray())) {
					throw new AssertionError("W2 v0.11-jit PARITY MISMATCH");
				}
				bench("W2 v0.11-jit   ", warmup, iters, () -> {
					v11Jit.invokeExact(ws);
				});
			}
		}
	}

	private static void runW3PowerGridMorsel(ImpulseGraphSnapshot snap, V11Interpreter.SnapshotBinding bind,
			V11Workspace ws, RelationSnapshot rel, String engine, int warmup, int iters) throws Throwable {
		System.out.printf(
				"%n[W3] PowerGrid Filtered Edge Walk + Score Scatter: rel=%d (%d nodes, %d edges, %d edge attrs)%n",
				rel.getRelationId(), rel.getNodeCount(), rel.getEdgeCount(), rel.getAttributeSegments().size());

		int relId = rel.getRelationId();
		int domId = rel.getSrcDomainId();
		float thresh = 0.01f;

		long[] v11Prog = new long[]{
				V11Interpreter.encode(V11Interpreter.OP_INIT_DOMAIN_SPAN, 0, 0, 0, 0, 0, 0,
						V11Interpreter.packImm16(domId, 0)),
				V11Interpreter.encode(V11Interpreter.OP_LOAD_CONST, 0, 2, 1, 0, 0, 0, Float.floatToRawIntBits(thresh)),
				V11Interpreter.encode(V11Interpreter.OP_VEC_INIT, 0, 12, 1, 0, 0, 0,
						V11Interpreter.packImm16(rel.getTgtDomainId(), 0)),
				// pc 3: WALK_MORSEL over F0, end=+6 -> pc 9
				V11Interpreter.encode(V11Interpreter.OP_WALK_MORSEL, 0, 0, 0, 0, 0, 0,
						V11Interpreter.packImm16(relId, 6)),
				V11Interpreter.encode(V11Interpreter.OP_GATHER_EDGE_ATTR, 0, 0, 12, 10, 0, 0,
						V11Interpreter.packImm16(relId, 0)),
				V11Interpreter.encode(V11Interpreter.OP_VEC_CMP, V11Interpreter.FLAG_SCALAR, 4, 13, 12, 1, 0, 0),
				V11Interpreter.encode(V11Interpreter.OP_SET_CONVERT, 0, 2, 8, 13, 0, 0, 0),
				V11Interpreter.encode(V11Interpreter.OP_MORSEL_COMMIT, V11Interpreter.FLAG_MASK, 0, 1, 9, 0, 8,
						V11Interpreter.packImm16(rel.getTgtDomainId(), 0)),
				V11Interpreter.encode(V11Interpreter.OP_MORSEL_COMMIT, V11Interpreter.FLAG_MASK, 2, 1, 9, 12, 8, 0),
				V11Interpreter.encode(V11Interpreter.OP_MORSEL_END, 0, 0, 0, 0, 0, 0, V11Interpreter.packImm16(0, 6)),
				V11Interpreter.encode(V11Interpreter.OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

		V11Interpreter.execute(v11Prog, bind, ws);
		double scoreSum = 0.0;
		for (int i = 0; i < ws.vLen[1]; i++)
			scoreSum += ws.vF64[1][i];
		System.out.printf("  v0.11 morsel output: |F1| = %d nodes, sum(V1) = %.4f%n", ws.fRegs[1].cardinality(),
				scoreSum);

		if (engine.equals("all") || engine.equals("v11")) {
			bench("W3 v0.11-morsel", warmup, iters, () -> V11Interpreter.execute(v11Prog, bind, ws));
		}
		if (engine.equals("all") || engine.equals("v11-jit")) {
			MethodHandle v11Jit = V11MethodHandleCompiler.compile(v11Prog, bind);
			v11Jit.invokeExact(ws);
			bench("W3 v0.11-jit   ", warmup, iters, () -> {
				v11Jit.invokeExact(ws);
			});
		}
	}

	private static void runW4PowerGridSpmv(ImpulseGraphSnapshot snap, V11Interpreter.SnapshotBinding bind,
			V11Workspace ws, RelationSnapshot rel, String engine, int warmup, int iters) throws Throwable {
		if (!rel.hasCsc()) {
			System.out.println("\n[W4] Skipped (PowerGrid snapshot has no CSC)");
			return;
		}
		System.out.printf("%n[W4] PowerGrid 10-Iter SpMV (CSC Pull): rel=%d (%d nodes, %d edges)%n",
				rel.getRelationId(), rel.getNodeCount(), rel.getEdgeCount());
		int relId = rel.getRelationId();
		int domId = rel.getSrcDomainId();

		long[] v11Prog = new long[]{
				V11Interpreter.encode(V11Interpreter.OP_LOAD_CONST, 0, 2, 1, 0, 0, 0, Float.floatToRawIntBits(1.0f)),
				V11Interpreter.encode(V11Interpreter.OP_VEC_INIT, V11Interpreter.FLAG_SCALAR, 12, 0, 0, 1, 0,
						V11Interpreter.packImm16(domId, 0)),
				V11Interpreter.encode(V11Interpreter.OP_LOAD_CONST, 0, 0, 2, 0, 0, 0, 5), // 5 double-iters = 10 SpMVs
				// pc 3: loop body
				V11Interpreter.encode(V11Interpreter.OP_SPMV, 0, 0, 1, 0, 0, 0, V11Interpreter.packImm16(relId, 0)),
				V11Interpreter.encode(V11Interpreter.OP_SPMV, 0, 0, 0, 1, 0, 0, V11Interpreter.packImm16(relId, 0)),
				V11Interpreter.encode(V11Interpreter.OP_LOOP_DECR, 0, 0, 2, 0, 0, 0, -2),
				V11Interpreter.encode(V11Interpreter.OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

		V11Interpreter.execute(v11Prog, bind, ws);
		System.out.printf("  v0.11 SpMV 10-iter V0[0] = %.4e%n", ws.vF64[0][0]);

		if (engine.equals("all") || engine.equals("v11")) {
			bench("W4 v0.11-spmv10", warmup, iters, () -> V11Interpreter.execute(v11Prog, bind, ws));
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Throwable;
	}

	private static void bench(String label, int warmup, int iters, ThrowingRunnable r) throws Throwable {
		for (int i = 0; i < warmup; i++) {
			r.run();
		}
		long[] times = new long[iters];
		long tAll0 = System.nanoTime();
		for (int i = 0; i < iters; i++) {
			long t0 = System.nanoTime();
			r.run();
			times[i] = System.nanoTime() - t0;
		}
		long totalNs = System.nanoTime() - tAll0;
		Arrays.sort(times);
		double p50Us = times[iters / 2] / 1000.0;
		double p95Us = times[(int) (iters * 0.95)] / 1000.0;
		double p99Us = times[(int) (iters * 0.99)] / 1000.0;
		double opsSec = (iters * 1e9) / totalNs;
		System.out.printf("  %-16s | p50: %8.2f us | p95: %8.2f us | p99: %8.2f us | %10.0f ops/s%n", label, p50Us,
				p95Us, p99Us, opsSec);
	}
}
