package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.v11.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;

public class CrossCompilerParityHarness {

	private static MemorySegment allocInts(Arena arena, int... vals) {
		MemorySegment seg = arena.allocate(ValueLayout.JAVA_INT, vals.length);
		for (int i = 0; i < vals.length; i++)
			seg.setAtIndex(ValueLayout.JAVA_INT, i, vals[i]);
		return seg;
	}

	private static GraphSnapshot buildRebacSnapshot(Arena arena) {
		RelationSnapshot rMember = new RelationSnapshot(arena, 2, 1, allocInts(arena, 0, 1, 1), allocInts(arena, 0));
		rMember.setCatalogMetadata(0, 0, 1, (byte) 1, 0L, null);
		RelationSnapshot rGroup = new RelationSnapshot(arena, 3, 2, allocInts(arena, 0, 1, 2, 2),
				allocInts(arena, 1, 2));
		rGroup.setCatalogMetadata(1, 1, 1, (byte) 1, 0L, null);
		RelationSnapshot rAccess = new RelationSnapshot(arena, 3, 1, allocInts(arena, 0, 0, 0, 1), allocInts(arena, 1));
		rAccess.setCatalogMetadata(2, 1, 2, (byte) 1, 0L, null);
		Map<String, BinarySnapshotLoader.LoadedDomain> doms = Map.of("User",
				new BinarySnapshotLoader.LoadedDomain(0, "User", (byte) 0, 2L), "Group",
				new BinarySnapshotLoader.LoadedDomain(1, "Group", (byte) 0, 3L), "Resource",
				new BinarySnapshotLoader.LoadedDomain(2, "Resource", (byte) 0, 2L));
		return new GraphSnapshot(arena, Map.of("MEMBER_OF", rMember, "GROUP_INCLUDES", rGroup, "CAN_ACCESS", rAccess),
				Map.of(), Map.of(), doms);
	}

	private static GraphSnapshot buildChainSnapshot(Arena arena) {
		RelationSnapshot rel = new RelationSnapshot(arena, 5, 4, allocInts(arena, 0, 1, 2, 3, 4, 4),
				allocInts(arena, 1, 2, 3, 4));
		rel.setCatalogMetadata(0, 0, 0, (byte) 1, 0L, null);
		Map<String, BinarySnapshotLoader.LoadedDomain> doms = Map.of("Node",
				new BinarySnapshotLoader.LoadedDomain(0, "Node", (byte) 0, 5L));
		return new GraphSnapshot(arena, Map.of("NEXT", rel), Map.of(), Map.of(), doms);
	}

	private static void runV11Impscm(String[] args) {
		// args: --v11-impscm <path> <opt:0|1> <snap:rebac|chain> [param0_u64
		// [param1_u64]]
		String path = args[1];
		boolean opt = !"0".equals(args[2]);
		String snapKind = args[3];
		try (Arena arena = Arena.ofConfined()) {
			String src = Files.readString(Path.of(path));
			GraphSnapshot snap = "rebac".equals(snapKind) ? buildRebacSnapshot(arena) : buildChainSnapshot(arena);
			ImpScmV11Compiler.CompileResult cr;
			try {
				cr = ImpScmV11Compiler.compile(src, snap, opt);
			} catch (IllegalArgumentException ex) {
				String msg = ex.getMessage() != null ? ex.getMessage().replace("\"", "'") : "error";
				System.out.println("{\"status\": \"compile_error\", \"error\": \"" + msg + "\"}");
				return;
			}
			V11SnapshotView view = V11SnapshotAdapter.from(snap);
			int[] domCards = new int[view.domainCards.length];
			for (int i = 0; i < domCards.length; i++)
				domCards[i] = (int) view.domainCards[i];
			V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(domCards, view);
			V11Workspace ws = new V11Workspace(16);
			for (int p = 4; p < args.length; p++) {
				ws.params.put(p - 4,
						new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, Long.parseLong(args[p])));
			}
			V11Interpreter.execute(cr.codeWords(), binding, ws);
			StringBuilder outJson = new StringBuilder("[");
			for (int s = 0; s < cr.outputNames().size(); s++) {
				if (s > 0)
					outJson.append(",");
				V11Vector v = ws.outputs.get(s);
				outJson.append("[");
				if (v != null && v.i64Data != null) {
					for (int k = 0; k < v.length; k++) {
						if (k > 0)
							outJson.append(",");
						outJson.append(v.i64Data[k]);
					}
				}
				outJson.append("]");
			}
			outJson.append("]");
			String hex = HexFormat.of().formatHex(cr.impbBytes());
			System.out.println("{\"status\": \"ok\", \"vm_status\": " + ws.status + ", \"impb_hex\": \"" + hex
					+ "\", \"outputs\": " + outJson + "}");
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : e.toString();
			System.out.println("{\"status\": \"error\", \"error\": \"" + msg + "\"}");
			System.exit(1);
		}
	}

	public static void main(String[] args) {
		if (args.length >= 4 && "--v11-impscm".equals(args[0])) {
			runV11Impscm(args);
			return;
		}
		if (args.length < 2) {
			System.err.println(
					"{\"status\": \"error\", \"error\": \"Usage: java CrossCompilerParityHarness <type> <expr>\"}");
			System.exit(1);
		}

		String type = args[0]; // "filter" or "project"
		String expr = args[1];

		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, arena.allocate(16), arena.allocate(12));
			ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("ROAD", rel));

			org.impulsegraph.compiler.registry.QueryCompilerEngine engine = new org.impulsegraph.compiler.registry.QueryCompilerEngine();
			org.impulsegraph.compiler.ast.ScmProgram ast;

			if (type.equals("project")) {
				ast = org.impulsegraph.compiler.ast.ScmProgram.of(
						org.impulsegraph.compiler.ast.ScmWalk.forward("ROAD")
								.withShaderSteps(java.util.List.of(new org.impulsegraph.compiler.ast.ScmStreamProject(
										new org.impulsegraph.compiler.ast.ScmCelExpr(expr)))),
						org.impulsegraph.compiler.ast.ScmCollect.bitset());
			} else {
				ast = org.impulsegraph.compiler.ast.ScmProgram.of(
						org.impulsegraph.compiler.ast.ScmWalk.forward("ROAD",
								new org.impulsegraph.compiler.ast.ScmCelExpr(expr)),
						org.impulsegraph.compiler.ast.ScmCollect.bitset());
			}

			org.impulsegraph.compiler.registry.QueryObject q = engine.compileStage1("test", "", ast,
					org.impulsegraph.compiler.trace.CompilerOptions.DEFAULT);
			CompiledQuery cq = engine.compileStage2(q, snapshot, arena,
					org.impulsegraph.compiler.trace.CompilerOptions.DEFAULT);

			byte[] bytes = cq.v11().impbBytes();
			System.out.println("{\"status\": \"ok\", \"hex\": \"" + HexFormat.of().formatHex(bytes) + "\"}");

		} catch (Exception e) {
			System.err.println("{\"status\": \"error\", \"error\": \"" + e.getMessage() + "\"}");
			System.exit(1);
		}
	}
}
