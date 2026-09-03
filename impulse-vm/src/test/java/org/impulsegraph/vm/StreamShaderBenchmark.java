package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class StreamShaderBenchmark {

	public static void main(String[] args) {
		System.out.println("==================================================");
		System.out.println(" Impulse VM Edge Stream Shader Microbenchmark");
		System.out.println("==================================================");

		try (Arena arena = Arena.ofShared()) {
			int numNodes = 1000001;
			int numEdges = 1000000;

			int[] offsets = new int[numNodes + 1];
			int[] targets = new int[numEdges];

			offsets[0] = 0;
			offsets[1] = numEdges;
			for (int i = 2; i <= numNodes; i++)
				offsets[i] = numEdges;

			for (int i = 0; i < numEdges; i++)
				targets[i] = i + 1;

			MockRelationSnapshot rel = new MockRelationSnapshot(arena, numNodes, numEdges, offsets, targets);

			List<Long> instructions = new ArrayList<>();
			instructions.add(encode(0x02, 0, 3, 0)); // OP_INIT_INPUT_NODE R3, node 0
			instructions.add(encode(0x06, 0, 4, 0)); // OP_CLEAR_REG R4
			instructions.add(encode(0x06, 0, 5, 0)); // OP_CLEAR_REG R5

			instructions.add(encode(0xA0, 5, 0, 3)); // OP_COO_WALK_STREAM R0, R3, rel 0, shaderPc=5
			instructions.add(encode(0x00, 0, 0, 0)); // OP_HALT

			instructions.add(encode(0xA1, 0, 0, 0)); // OP_STREAM_FUNC_BEGIN

			// OP_STREAM_LOAD_TGT_ID 0
			instructions.add(encode(0xBB, 0, 0, 0));
			// OP_STREAM_LOAD_CONST 1, 2.0
			instructions.add(encode(0xBD, 0, 1, Float.floatToRawIntBits(2.0f)));
			// OP_STREAM_MATH_MOD 2, 0, 1 (TGT_ID % 2.0)
			instructions.add(encode(0xAE, 0, 2, (0 & 0xFFFF) | ((1 & 0xFFFF) << 16)));
			// OP_STREAM_LOAD_CONST 3, 0.0
			instructions.add(encode(0xBD, 0, 3, Float.floatToRawIntBits(0.0f)));
			// OP_STREAM_CMP_EQ 4, 2, 3 (TGT_ID % 2.0 == 0.0) -> Even numbers
			instructions.add(encode(0xB0, 0, 4, (2 & 0xFFFF) | ((3 & 0xFFFF) << 16)));
			// OP_STREAM_FILTER 4
			instructions.add(encode(0xA7, 0, 4, 0));
			// OP_STREAM_REDUCE 0, R4, ADD
			instructions.add(encode(0xA8, 0, 0, (4 & 0xFFFF) | ((0 & 0xFFFF) << 16)));

			instructions.add(encode(0xA2, 0, 0, 0)); // OP_STREAM_FUNC_END

			MemorySegment prog = arena.allocate(instructions.size() * 8L);
			for (int i = 0; i < instructions.size(); i++) {
				prog.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, (long) i, instructions.get(i));
			}

			Map<String, org.impulsegraph.api.RelationSnapshot> map = new HashMap<>();
			map.put("0", rel);

			ImpulseGraphSnapshot mockGraph = new MockImpulseGraphSnapshot(map);

			System.out.println("Graph initialized: 1 node -> 1,000,000 edges.");
			System.out.println("Shader: TGT_ID % 2.0 == 0.0 -> Filter -> Reduce SUM(R4)");

			// Cold Pass
			CompiledQuery query = new CompiledQuery(prog, instructions.size(), java.util.List.of(),
					new java.util.HashMap<>(), mockGraph, arena, null);
			query = new CompiledQuery(prog, instructions.size(), java.util.List.of(), new java.util.HashMap<>(),
					mockGraph, arena, null);
			long t0 = System.nanoTime();
			query.execute(mockGraph, null, arena);
			long t1 = System.nanoTime();
			System.out.printf("Cold Pass: %.2f ms\n", (t1 - t0) / 1_000_000.0);

			// Warmup
			System.out.println("Warming up JIT (100 passes)...");
			for (int i = 0; i < 100; i++) {
				query.execute(mockGraph, null, arena);
			}

			// Hot Pass
			System.out.println("Running Hot Passes (50 passes)...");
			long totalTime = 0;
			for (int i = 0; i < 50; i++) {
				long s = System.nanoTime();
				query.execute(mockGraph, null, arena);
				totalTime += (System.nanoTime() - s);
			}

			double avgTime = (totalTime / 50.0) / 1_000_000.0;
			System.out.printf("Hot Pass Average: %.3f ms (%.2f MTEPS)\n", avgTime, 1.0 / (avgTime / 1000.0));
		}
	}

	private static long encode(int opcode, int flags, int dst, int payload) {
		return (opcode & 0xFFL) | ((flags & 0xFFL) << 8) | ((dst & 0xFFFFL) << 16) | ((payload & 0xFFFFFFFFL) << 32);
	}
}
