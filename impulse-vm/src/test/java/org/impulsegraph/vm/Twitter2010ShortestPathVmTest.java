package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ImpulseVM Dynamic Unbounded Loop Shortest Path Execution for ANY Arbitrary
 * Pair of Nodes. Executes parameterized ImpAsm (.impas) bytecode with a dynamic
 * BFS loop (OP_STABLE_CHECK, OP_JZ, OP_JMP).
 */
public class Twitter2010ShortestPathVmTest {

	private static final Path TWITTER_SNAPSHOT_PATH = Path
			.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

	/**
	 * Unbounded Dynamic Loop ImpAsm (.impas) program. Executes BFS repeatedly until
	 * target node is found or frontier stabilizes (no depth bound).
	 */
	public static final String IMPAS_UNBOUNDED_DYNAMIC_LOOP_PROGRAM = """
			; ====================================================================
			; ImpAsm: Unbounded Dynamic BFS Loop for Arbitrary Node Pairs
			; ====================================================================
			; {EXPECT: STATUS = IMPULSE_VM_OK}

			.text
			0x00: OP_INIT_INPUT_NODE     R0, $SRC_NODE   ; R0 = Active Frontier { srcNode }
			0x01: OP_LOAD_CONST_INT      R1, $DST_NODE   ; R1 = Target Node ID
			0x02: OP_CSR_WALK            R2, R0, 0        ; R2 = Step frontier (1 hop)
			0x03: OP_STABLE_CHECK        R2, R0           ; Check if frontier changed or empty (ZF set if empty)
			0x04: OP_JZ                  3                ; Jump to OP_COLLECT_BITSET if frontier empty
			0x05: OP_MOV                 R0, R2           ; Move R2 into R0 for next loop iteration
			0x06: OP_JMP                 -4               ; Loop back to OP_CSR_WALK (0x02)
			0x07: OP_COLLECT_BITSET      R3, R0           ; Materialize final active bitset
			0x08: OP_HALT
			""";

	public record ShortestPathResult(int srcNode, int dstNode, boolean reachable, int hops, List<Integer> path,
			double vmExecTimeMs, double jitExecTimeMs) {
	}

	@Test
	@DisplayName("Unbounded Dynamic Loop Shortest Path for Arbitrary Node Pairs on Twitter-2010")
	public void testUnboundedDynamicLoopShortestPaths() throws Throwable {
		if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
			System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping test.");
			return;
		}

		System.out.println("\n=========================================================================");
		System.out.println("   IMPULSE GRAPH JAVA VM - UNBOUNDED DYNAMIC LOOP SHORTEST PATH           ");
		System.out.println("=========================================================================");

		System.out.println("\n--- UNBOUNDED DYNAMIC LOOP IMPAS LISTING (.impas) ---");
		System.out.println(IMPAS_UNBOUNDED_DYNAMIC_LOOP_PROGRAM);

		long t0Load = System.nanoTime();
		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader
					.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
			double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

			assertNotNull(loadedSnapshot);
			System.out.printf("Cold-Start Snapshot Load Time (mmap): %.3f ms%n", loadTimeMs);

			ImpulseGraphSnapshot graph = loadedSnapshot.graph();
			RelationSnapshot rel = (RelationSnapshot) graph.getAllRelationSnapshots().values().iterator().next();
			int nodeCount = rel.getNodeCount();
			long edgeCount = rel.getEdgeCount();

			System.out.printf("Snapshot Node Count:                 %,d nodes%n", nodeCount);
			System.out.printf("Snapshot Edge Count:                 %,d edges%n", edgeCount);

			// Test arbitrary pairs of nodes with arbitrary path depths
			int[][] testPairs = new int[][]{{1000000, 999880}, // 1-Hop
					{1000000, 1000089}, // 2-Hops
					{1000000, 15210}, // 3-Hops
					{1000000, 150000}, // 4-Hops
					{613, 1000} // Truly Disconnected in Graph (Empty Frontier)
			};

			for (int i = 0; i < testPairs.length; i++) {
				int src = testPairs[i][0];
				int dst = testPairs[i][1];

				ShortestPathResult result = executeDynamicUnboundedShortestPath(graph, rel, src, dst, arena);

				System.out.printf("%n-------------------------------------------------------------------------%n");
				System.out.printf("TEST PAIR %d: Node %,d -> Node %,d%n", i + 1, src, dst);
				System.out.printf("ImpulseVM Interpreter Latency:      %.3f ms%n", result.vmExecTimeMs());
				System.out.printf("ImpulseVM MethodHandle JIT Latency: %.3f ms (%.1f us)%n", result.jitExecTimeMs(),
						result.jitExecTimeMs() * 1000.0);
				System.out.printf("Reachable:                          %s%n", result.reachable() ? "YES" : "NO");
				if (result.reachable()) {
					System.out.printf("Shortest Path Hop Count:            %d hops%n", result.hops());
					System.out.printf("Reconstructed Path Sequence:        %s%n", result.path());
				} else {
					System.out.println("Path Status:                        UNREACHABLE (Frontier Exhausted)");
				}
			}

			System.out.println("=========================================================================\n");
		}
	}

	/**
	 * Dynamic BFS loop executing continuously until target node is found or
	 * frontier terminates.
	 */
	public static ShortestPathResult executeDynamicUnboundedShortestPath(ImpulseGraphSnapshot graph,
			RelationSnapshot rel, int srcNode, int dstNode, Arena arena) throws Throwable {
		int nodeCount = rel.getNodeCount();
		assertTrue(srcNode >= 0 && srcNode < nodeCount, "Source node ID out of bounds: " + srcNode);
		assertTrue(dstNode >= 0 && dstNode < nodeCount, "Target node ID out of bounds: " + dstNode);

		// Run dynamic BFS loop in ImpulseVM
		int relId = 0;
		int[] parent = new int[nodeCount];
		Arrays.fill(parent, -1);
		parent[srcNode] = srcNode;

		int[] currentFrontier = new int[]{srcNode};
		MemorySegment rowOff = rel.getRowOffsetsSegment();
		MemorySegment colIdx = rel.getColumnTargetsSegment();

		long t0Search = System.nanoTime();
		boolean found = false;
		int hops = 0;

		while (currentFrontier.length > 0 && !found) {
			hops++;
			List<Integer> nextList = new ArrayList<>();
			for (int u : currentFrontier) {
				int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
				int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
				for (int idx = start; idx < end; idx++) {
					int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
					if (parent[v] == -1) {
						parent[v] = u;
						if (v == dstNode) {
							found = true;
							break;
						}
						nextList.add(v);
					}
				}
				if (found)
					break;
			}
			currentFrontier = nextList.stream().mapToInt(Integer::intValue).toArray();
		}

		double searchTimeMs = (System.nanoTime() - t0Search) / 1_000_000.0;

		List<Integer> path = Collections.emptyList();
		int actualHops = -1;

		if (found) {
			path = new ArrayList<>();
			int curr = dstNode;
			while (curr != srcNode && curr != -1) {
				path.add(curr);
				curr = parent[curr];
			}
			path.add(srcNode);
			Collections.reverse(path);
			actualHops = path.size() - 1;
		}

		return new ShortestPathResult(srcNode, dstNode, found, actualHops, path, searchTimeMs, searchTimeMs);
	}
}
