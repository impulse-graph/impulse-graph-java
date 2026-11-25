package org.impulsegraph.vm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.emitter.V11ProgramBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Empirical Java 25 v0.11 E2E benchmark runner (exact counterpart of C++ {@code bench_v11_e2e.cpp}).
 */
public final class V11BenchRunner {

    private static double runTimed(long[] words, V11Interpreter.SnapshotBinding binding,
                                   V11Workspace ws, long seed, int warmup, int iters, long[] outVal) {
        V11Workspace.ParamFrontier pf = new V11Workspace.ParamFrontier(0, new long[]{seed});
        for (int i = 0; i < warmup; i++) {
            ws.params.put(0, pf);
            V11Interpreter.execute(words, binding, ws);
            if (ws.status != V11IsaTables.IMPULSE_VM_OK) {
                throw new IllegalStateException("VM error during warmup: " + ws.status);
            }
        }
        double[] us = new double[iters];
        for (int i = 0; i < iters; i++) {
            ws.params.put(0, pf);
            long t0 = System.nanoTime();
            V11Interpreter.execute(words, binding, ws);
            long t1 = System.nanoTime();
            if (ws.status != V11IsaTables.IMPULSE_VM_OK) {
                throw new IllegalStateException("VM error during iter " + i + ": " + ws.status);
            }
            us[i] = (t1 - t0) / 1000.0;
        }
        V11Vector ov = ws.outputs.get(0);
        if (ov != null && ov.length > 0 && ov.i64Data != null) {
            outVal[0] = ov.i64Data[0];
        }
        Arrays.sort(us);
        return us[us.length / 2];
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: V11BenchRunner <snapshot.imps> [seed=0] [warmup=50] [iters=100]");
            System.exit(1);
        }
        String path = args[0];
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 0L;
        int warmup = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        int iters = args.length > 3 ? Integer.parseInt(args[3]) : 100;

        try (Arena arena = Arena.ofConfined()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(Path.of(path), arena);
            ImpulseGraphSnapshot snap = loaded.graph();
            V11SnapshotView view = V11SnapshotAdapter.from(snap);
            int[] domCards = new int[view.domainCards.length];
            int maxCard = 64;
            for (int i = 0; i < domCards.length; i++) {
                domCards[i] = (int) view.domainCards[i];
                if (domCards[i] > maxCard) maxCard = domCards[i];
            }
            V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(domCards, view);
            V11Workspace ws = new V11Workspace(maxCard);

            // 1. 2-Hop Walk + Cardinality
            V11ProgramBuilder b2 = new V11ProgramBuilder();
            b2.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, 0);
            b2.emitU16(V11IsaTables.OP_WALK, V11IsaTables.SUBOP_WALK_FWD, 0, 1, 0, 0, 0, 0, 0);
            b2.emitU16(V11IsaTables.OP_WALK, V11IsaTables.SUBOP_WALK_FWD, 0, 0, 1, 0, 0, 0, 0);
            b2.emit(V11IsaTables.OP_SET_CARDINALITY, V11IsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 0, 0, 0, 0);
            b2.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
            b2.emit(V11IsaTables.OP_HALT, 0, 0, 0, 0, 0, 0, 0);
            long[] w2 = b2.toWordArray();

            // 2. Fixpoint Kleene* Reachability + Cardinality
            V11ProgramBuilder bf = new V11ProgramBuilder();
            bf.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, 0, 0, 0, 0, 0, 0);
            bf.emitU16(V11IsaTables.OP_WALK, V11IsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR, 0, 1, 0, 0, 0, 0, 0);
            bf.emit(V11IsaTables.OP_SET_CARDINALITY, V11IsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0, 0, 1, 0, 0, 0);
            bf.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_SCALAR, 0, 0, 0, 0, 0, 0, 0);
            bf.emit(V11IsaTables.OP_HALT, 0, 0, 0, 0, 0, 0, 0);
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
