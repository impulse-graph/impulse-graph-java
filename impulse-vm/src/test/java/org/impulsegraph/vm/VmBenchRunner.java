package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.emitter.V11ProgramBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Empirical Java 25 v0.11 E2E benchmark runner (exact counterpart of C++
 * {@code bench_v11_e2e.cpp}).
 */
public final class VmBenchRunner {

	private static double runTimed(long[] words, ImpulseInterpreter.SnapshotBinding binding, ImpulseWorkspace ws,
			long seed, int warmup, int iters, long[] outVal) {
		ImpulseWorkspace.ParamFrontier pf = new ImpulseWorkspace.ParamFrontier(0, new long[]{seed});
		for (int i = 0; i < warmup; i++) {
			ws.params.put(0, pf);
			ImpulseInterpreter.execute(words, binding, ws);
			if (ws.status != ImpulseIsaTables.IMPULSE_VM_OK) {
				throw new IllegalStateException("VM error during warmup: " + ws.status);
			}
		}
		double[] us = new double[iters];
		for (int i = 0; i < iters; i++) {
			ws.params.put(0, pf);
			long t0 = System.nanoTime();
			ImpulseInterpreter.execute(words, binding, ws);
			long t1 = System.nanoTime();
			if (ws.status != ImpulseIsaTables.IMPULSE_VM_OK) {
				throw new IllegalStateException("VM error during iter " + i + ": " + ws.status);
			}
			us[i] = (t1 - t0) / 1000.0;
		}
		ImpulseVector ov = ws.outputs.get(0);
		if (ov != null && ov.length > 0 && ov.i64Data != null) {
			outVal[0] = ov.i64Data[0];
		}
		Arrays.sort(us);
		return us[us.length / 2];
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Usage: VmBenchRunner <snapshot.imps> [seed=0] [warmup=50] [iters=100]");
			System.exit(1);
		}
		String path = args[0];
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 0L;
		int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 50;
		int iters = args.length > 3 ? Integer.parseInt(args[3]) : 100;

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(Path.of(path), arena);
			ImpulseGraphSnapshot snap = loaded.graph();
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(snap);
			int[] domCards = new int[view.domainCards.length];
			int maxCard = 64;
			for (int i = 0; i < domCards.length; i++) {
				domCards[i] = (int) view.domainCards[i];
				if (domCards[i] > maxCard)
					maxCard = domCards[i];
			}
			ImpulseInterpreter.SnapshotBinding binding = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			ImpulseWorkspace ws = new ImpulseWorkspace(maxCard);

			// 1. 2-Hop Walk + Cardinality
			V11ProgramBuilder b2 = new V11ProgramBuilder();
			b2.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, 0);
			b2.emitU16(ImpulseIsaTables.OP_WALK, ImpulseIsaTables.SUBOP_WALK_FWD, 0, 1, 0, 0, 0, 0, 0);
			b2.emitU16(ImpulseIsaTables.OP_WALK, ImpulseIsaTables.SUBOP_WALK_FWD, 0, 0, 1, 0, 0, 0, 0);
			b2.emit(ImpulseIsaTables.OP_SET_CARDINALITY, ImpulseIsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 0, 0,
					0, 0);
			b2.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
			b2.emit(ImpulseIsaTables.OP_HALT, 0, 0, 0, 0, 0, 0, 0);
			long[] w2 = b2.toWordArray();

			// 2. Fixpoint Kleene* Reachability + Cardinality
			V11ProgramBuilder bf = new V11ProgramBuilder();
			bf.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, 0);
			bf.emitU16(ImpulseIsaTables.OP_WALK, ImpulseIsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR, 0, 1, 0, 0, 0, 0, 0);
			bf.emit(ImpulseIsaTables.OP_SET_CARDINALITY, ImpulseIsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 1, 0,
					0, 0);
			bf.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
			bf.emit(ImpulseIsaTables.OP_HALT, 0, 0, 0, 0, 0, 0, 0);
			long[] wf = bf.toWordArray();

			long[] card2 = {-1};
			long[] cardBfs = {-1};
			double us2 = runTimed(w2, binding, ws, seed, warmup, iters, card2);
			double usBfs = runTimed(wf, binding, ws, seed, warmup, iters, cardBfs);

			System.out.printf(Locale.ROOT,
					"{\"engine\":\"java-v11\",\"snapshot\":\"%s\",\"2hop_us\":%.2f,\"2hop_card\":%d,\"bfs_us\":%.2f,\"bfs_card\":%d}%n",
					path, us2, card2[0], usBfs, cardBfs[0]);
		}
	}
}
