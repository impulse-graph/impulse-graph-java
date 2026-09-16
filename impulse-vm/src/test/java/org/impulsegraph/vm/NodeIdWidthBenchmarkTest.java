package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Disabled("Manual 1.4B edge macro-benchmark")
public class NodeIdWidthBenchmarkTest {

	private static final Path TWITTER_SNAPSHOT_PATH = Path
			.of("/Users/jesse/impulse/datasets/twitter-2010/twitter-2010.imps");

	private record InstructionData(byte opcode, byte flags, short dstReg, int payload) {
	}

	private MemorySegment buildProgram(Arena arena, InstructionData... instrs) {
		MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * instrs.length);
		for (int i = 0; i < instrs.length; i++) {
			long off = i * INSTRUCTION_SIZE_BYTES;
			INSTR_OPCODE_HANDLE.set(prog, off, instrs[i].opcode);
			INSTR_FLAGS_HANDLE.set(prog, off, instrs[i].flags);
			INSTR_DST_REG_HANDLE.set(prog, off, instrs[i].dstReg);
			INSTR_PAYLOAD_HANDLE.set(prog, off, instrs[i].payload);
		}
		return prog;
	}

	@Test
	public void runTwitter2010NodeIdWidthBenchmark() throws Throwable {
		if (!Files.exists(TWITTER_SNAPSHOT_PATH)) {
			System.out.println("Twitter 2010 snapshot not found at " + TWITTER_SNAPSHOT_PATH + ", skipping benchmark.");
			return;
		}

		System.out
				.println("\n=========================================================================================");
		System.out.println("   TWITTER-2010 DATASET: 32-BIT vs 64-BIT NODE ID TRAVERSAL BENCHMARK (1.47B EDGES)     ");
		System.out.println("=========================================================================================");

		try (Arena arena = Arena.ofShared()) {
			long t0Load = System.nanoTime();
			BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = BinarySnapshotLoader
					.loadSnapshot(TWITTER_SNAPSHOT_PATH, arena);
			double loadTimeMs = (System.nanoTime() - t0Load) / 1_000_000.0;

			assertNotNull(loadedSnapshot);
			System.out.printf("Snapshot Load Time (mmap off-heap): %.3f ms%n", loadTimeMs);

			ImpulseGraphSnapshot baseGraph = loadedSnapshot.graph();
			RelationSnapshot rel32 = (RelationSnapshot) baseGraph.getAllRelationSnapshots().values().iterator().next();
			int nodeCount = rel32.getNodeCount();
			long edgeCount = rel32.getEdgeCount();

			System.out.printf("Dataset Node Count (|V|):            %,d nodes%n", nodeCount);
			System.out.printf("Dataset Edge Count (|E|):            %,d edges%n", edgeCount);

			// 1. Prepare 32-bit relation layout (4 bytes/target, 4 bytes/offset)
			double size32Gb = (rel32.getColumnTargetsSegment().byteSize() + rel32.getRowOffsetsSegment().byteSize())
					/ (1024.0 * 1024.0 * 1024.0);
			System.out.printf("32-bit Topology Buffer Memory Size:   %.2f GB (4 bytes/node ID)%n", size32Gb);

			// 2. Prepare 64-bit relation layout (8 bytes/target, 4 bytes/offset)
			MemorySegment targets32 = rel32.getColumnTargetsSegment();
			long targets64ByteSize = edgeCount * 8L;
			System.out.printf("Allocating 64-bit target array:       %.2f GB (8 bytes/node ID)...%n",
					targets64ByteSize / (1024.0 * 1024.0 * 1024.0));

			MemorySegment targets64;
			try {
				targets64 = arena.allocate(targets64ByteSize);
			} catch (OutOfMemoryError err) {
				System.out.println("[SKIP] Insufficient direct memory to allocate 64-bit target array (needs "
						+ (targets64ByteSize / (1024 * 1024 * 1024)) + " GB): " + err.getMessage());
				return;
			}
			for (long i = 0; i < edgeCount; i++) {
				int targetVal = targets32.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
				targets64.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, (long) targetVal);
			}
			double size64Gb = (targets64.byteSize() + rel32.getRowOffsetsSegment().byteSize())
					/ (1024.0 * 1024.0 * 1024.0);
			System.out.printf("64-bit Topology Buffer Memory Size:   %.2f GB (8 bytes/node ID)%n%n", size64Gb);

			RelationSnapshot rel64 = new RelationSnapshot(arena, nodeCount, (int) edgeCount,
					rel32.getRowOffsetsSegment(), targets64, rel32.getCscRowOffsetsSegment(),
					rel32.getCscColumnTargetsSegment(), java.util.Collections.<MemorySegment>emptyList(),
					java.util.Collections.<MemorySegment>emptyList(), (byte) 4, // srcNodeIdWidth = 4 bytes
					(byte) 8, // dstNodeIdWidth = 8 bytes (64-bit)
					(byte) 4 // edgeIndexWidth = 4 bytes
			);

			ImpulseGraphSnapshot graph32 = new GraphSnapshot(arena, Map.of("rel_0", rel32));
			ImpulseGraphSnapshot graph64 = new GraphSnapshot(arena, Map.of("rel_0", rel64));

			// Benchmark instruction program: 1-hop CSR walk from seed node
			InstructionData[] code = {new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
					new InstructionData(OP_CSR_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
					new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 1, 0),
					new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)};
			MemorySegment prog = buildProgram(arena, code);

			// Test across high-degree hub nodes (e.g. node 613, degree ~3,000,000, and node
			// 12, degree ~2,000,000)
			int[] testSeeds = {613, 12, 115, 23, 45};

			// Warmup runs
			for (int seed : testSeeds) {
				ImpulseVmInterpreter.execute(prog, code.length, graph32, seed, arena);
				ImpulseVmInterpreter.execute(prog, code.length, graph64, seed, arena);
			}

			int iterations = 1000;
			System.out.println(
					"-----------------------------------------------------------------------------------------");
			System.out.println(
					" BENCHMARK RESULTS: 1000 CSR TRAVERSAL ITERATIONS OVER HIGH-DEGREE HUBS                 ");
			System.out.println(
					"-----------------------------------------------------------------------------------------");

			// Measure 32-bit Node ID Performance
			long t0_32 = System.nanoTime();
			long totalEdges32 = 0;
			for (int iter = 0; iter < iterations; iter++) {
				int seed = testSeeds[iter % testSeeds.length];
				totalEdges32 += rel32.getDegree(seed);
				ImpulseVmInterpreter.execute(prog, code.length, graph32, seed, arena);
			}
			long duration32Ns = System.nanoTime() - t0_32;
			double duration32Ms = duration32Ns / 1_000_000.0;
			double avgLatency32Us = (duration32Ns / 1000.0) / iterations;
			double mteps32 = (totalEdges32 / 1_000_000.0) / (duration32Ns / 1_000_000_000.0);

			// Measure 64-bit Node ID Performance
			long t0_64 = System.nanoTime();
			long totalEdges64 = 0;
			for (int iter = 0; iter < iterations; iter++) {
				int seed = testSeeds[iter % testSeeds.length];
				totalEdges64 += rel64.getDegree(seed);
				ImpulseVmInterpreter.execute(prog, code.length, graph64, seed, arena);
			}
			long duration64Ns = System.nanoTime() - t0_64;
			double duration64Ms = duration64Ns / 1_000_000.0;
			double avgLatency64Us = (duration64Ns / 1000.0) / iterations;
			double mteps64 = (totalEdges64 / 1_000_000.0) / (duration64Ns / 1_000_000_000.0);

			double speedup = duration64Ms / duration32Ms;
			double cacheEfficiencyGain = ((8.0 - 4.0) / 4.0) * 100.0;

			System.out.printf(" 32-Bit Node IDs (uint32_t):%n");
			System.out.printf("   • Memory Footprint:           %.2f GB (16 node IDs / 64-byte L1 cache line)%n",
					size32Gb);
			System.out.printf("   • Total Traversal Time:       %.3f ms (%d runs)%n", duration32Ms, iterations);
			System.out.printf("   • Average Latency per Walk:   %.2f us%n", avgLatency32Us);
			System.out.printf("   • Traversal Throughput:       %,.1f MTEPS%n%n", mteps32);

			System.out.printf(" 64-Bit Node IDs (uint64_t):%n");
			System.out.printf("   • Memory Footprint:           %.2f GB (8 node IDs / 64-byte L1 cache line)%n",
					size64Gb);
			System.out.printf("   • Total Traversal Time:       %.3f ms (%d runs)%n", duration64Ms, iterations);
			System.out.printf("   • Average Latency per Walk:   %.2f us%n", avgLatency64Us);
			System.out.printf("   • Traversal Throughput:       %,.1f MTEPS%n%n", mteps64);

			System.out.println(
					"-----------------------------------------------------------------------------------------");
			System.out.printf(" EMPIRICAL PERFORMANCE DELTA:%n");
			System.out.printf("   • 32-bit Node IDs are %.2fx FASTER than 64-bit Node IDs%n", speedup);
			System.out.printf(
					"   • 64-bit Node IDs suffer a %.1f%% Latency Penalty (L1/L2 Cache Pressure & Bandwidth)%n",
					((avgLatency64Us - avgLatency32Us) / avgLatency32Us) * 100.0);
			System.out.printf("   • Memory Bandwidth Efficiency Gain: +%.0f%% denser cache line packing%n",
					cacheEfficiencyGain);
			System.out.println(
					"=========================================================================================\n");
		}
	}
}
