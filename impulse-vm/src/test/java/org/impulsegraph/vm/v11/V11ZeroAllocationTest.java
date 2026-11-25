package org.impulsegraph.vm.v11;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Enforces the zero-allocation hot-path invariant for ImpulseVM v0.11.0 (Java)
 * across Pillars 1–5 using {@link ThreadMXBean#getCurrentThreadAllocatedBytes()}.
 */
public final class V11ZeroAllocationTest {

    private static boolean isJacocoAgentAttached() {
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.contains("jacoco")) {
                return true;
            }
        }
        return false;
    }

    private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, long imm32) {
        long b0 = op & 0xFFL;
        long b1 = (((flags & 0xF) << 4) | (subop & 0xF)) & 0xFFL;
        long b2 = (((dst & 0xF) << 4) | (s1 & 0xF)) & 0xFFL;
        long b3 = (((s2 & 0xF) << 4) | (s3 & 0xF)) & 0xFFL;
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | ((imm32 & 0xFFFFFFFFL) << 32);
    }

    @Test
    void steadyStateHotPathPerformsZeroHeapAllocationsAcrossPillars1To5() {
        Assumptions.assumeFalse(isJacocoAgentAttached(), "Skipped under JaCoCo bytecode instrumentation");
        final int N = 256;
        final int DEG = 4;
        final int E = N * DEG;
        int[] roff = new int[N + 1];
        long[] ctgt = new long[E];
        double[] eweights = new double[E];
        double[] nattrs = new double[N];
        for (int u = 0; u < N; u++) {
            roff[u] = u * DEG;
            nattrs[u] = (u + 1) * 0.5;
            for (int k = 0; k < DEG; k++) {
                int idx = u * DEG + k;
                ctgt[idx] = (u + k + 1) % N;
                eweights[idx] = 0.25 * (k + 1);
            }
        }
        roff[N] = E;

        V11Vector evec = V11Vector.ofF64(eweights, V11Vector.allocValidAllTrue(E));
        V11Vector nvec = V11Vector.ofF64(nattrs, V11Vector.allocValidAllTrue(N));
        Map<Integer, V11Vector> eattrs = new HashMap<>();
        eattrs.put(0, evec);
        V11SnapshotView.RelView rel = new V11SnapshotView.RelView(0, 0, 0, roff, ctgt, eattrs, true, true, false, false);
        Map<Long, V11Vector> naMap = new HashMap<>();
        naMap.put(V11SnapshotView.domAttrKey(0, 0), nvec);
        V11SnapshotView view = new V11SnapshotView(
                new long[]{N}, new V11SnapshotView.RelView[]{rel}, naMap, new HashMap<>(), new HashMap<>());
        V11Interpreter.SnapshotBinding bind = new V11Interpreter.SnapshotBinding(new int[]{N}, view);

        long[] seedIds = new long[16];
        for (int i = 0; i < 16; i++) seedIds[i] = i * 8L;
        V11Vector seedVec = V11Vector.ofU64(seedIds, V11Vector.allocValidAllTrue(16));

        long[][] progs = {
            // 1. BFS fixpoint (P1+P2+P3)
            {
                enc(0x05, 0, 1, 0, 0, 0, 0, 0),
                enc(0x20, 0, 2, 0, 0, 0, 0, 0),
                enc(0x30, 0, 4, 1, 0, 0, 0, 0),
                enc(0x24, 0, 0, 1, 1, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
            // 2. SpMV PageRank step (P2+P3+P4)
            {
                enc(0x21, 0, 0, 0, 0, 0, 0, 0),
                enc(0x40, 0, 0, 0, 0, 0, 0, 0),
                enc(0x34, 0, 0, 1, 0, 0, 0, 0),
                enc(0x52, 0, 0, 1, 1, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
            // 3. Morsel walk + edge gather + filter + commit (P3+P4)
            {
                enc(0x21, 0, 0, 0, 0, 0, 0, 0),
                enc(0x05, 0, 2, 0, 0, 0, 0, 0x3F000000L),
                enc(0x35, 0, 0, 0, 0, 0, 0, (5L << 16)),
                enc(0x42, 0, 0, 11, 10, 0, 0, 0),
                enc(0x45, 2, 4, 12, 11, 0, 0, 0),
                enc(0x26, 0, 2, 8, 12, 0, 0, 0),
                enc(0x37, 1, 0, 2, 9, 0, 8, 0),
                enc(0x38, 0, 0, 0, 0, 0, 0, (5L << 16)),
                enc(0x24, 0, 0, 1, 2, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
            // 4. Vector math arith + reduce (P4)
            {
                enc(0x40, 0, 0, 0, 0, 0, 0, 0),
                enc(0x48, 0, 0, 1, 0, 0, 0, 0),
                enc(0x48, 0, 2, 2, 1, 0, 0, 0),
                enc(0x52, 0, 0, 1, 2, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
            // 5. MITra multi-lane walk (P5)
            {
                enc(0x60, 0, 0, 0, 0, 0, 0, 0),
                enc(0x63, 0, 0, 1, 0, 0, 0, 0),
                enc(0x63, 0, 0, 2, 1, 0, 0, 0),
                enc(0x24, 0, 2, 1, 2, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
            // 6. MITra lane-state init + walk + reduce (P5)
            {
                enc(0x6B, 0, 0, 0, 0, 0, 0, (8L << 16)),
                enc(0x6C, 0, 0, 1, 0, 0, 0, 0),
                enc(0x6E, 0, 0, 2, 1, 0, 0, 0),
                enc(0x52, 0, 0, 1, 2, 0, 0, 0),
                enc(0x00, 0, 0, 0, 0, 0, 0, 0),
            },
        };

        V11Workspace ws = new V11Workspace(N);
        ThreadMXBean tmxb = (ThreadMXBean) ManagementFactory.getThreadMXBean();

        for (int w = 0; w < progs.length; w++) {
            boolean needSeed = (w == 4);
            // Warmup workspace arena + JIT
            for (int i = 0; i < 50; i++) {
                ws.reset();
                if (needSeed) ws.vVectors[0] = seedVec;
                int st = V11Interpreter.executeValidated(progs[w], bind, ws, 100000L);
                assertEquals(V11IsaTables.IMPULSE_VM_OK, st, "workload " + w);
            }
            long before = tmxb.getCurrentThreadAllocatedBytes();
            int badStatus = V11IsaTables.IMPULSE_VM_OK;
            for (int i = 0; i < 20; i++) {
                ws.reset();
                if (needSeed) ws.vVectors[0] = seedVec;
                int st = V11Interpreter.executeValidated(progs[w], bind, ws, 100000L);
                if (st != V11IsaTables.IMPULSE_VM_OK) badStatus = st;
            }
            long after = tmxb.getCurrentThreadAllocatedBytes();
            assertEquals(V11IsaTables.IMPULSE_VM_OK, badStatus, "workload " + w);
            assertEquals(0L, after - before, "Expected 0 allocated bytes for workload " + w);
        }
    }
}
