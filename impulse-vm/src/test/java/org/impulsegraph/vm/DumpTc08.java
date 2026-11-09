
package org.impulsegraph.vm;
import java.nio.file.Paths;
public class DumpTc08 {
	public static void main(String[] args) throws Exception {
		JavaVmPolyglotAssemblyVerifierTest verifier = new JavaVmPolyglotAssemblyVerifierTest();
		java.lang.reflect.Method m = JavaVmPolyglotAssemblyVerifierTest.class.getDeclaredMethod("parseImpasFile",
				java.nio.file.Path.class);
		m.setAccessible(true);
		var base = java.nio.file.Files.exists(Paths.get("../spec/test-vectors/vm-impas"))
				? Paths.get("../spec/test-vectors/vm-impas")
				: Paths.get("spec/test-vectors/vm-impas");
		var asm = (JavaVmPolyglotAssemblyVerifierTest.ParsedAssembly) m.invoke(verifier,
				base.resolve("04_graph_and_matrix/tc08_graphblas_mxv_pos.impas"));
		try (var arena = java.lang.foreign.Arena.ofConfined(); var ctx = new VmQueryContext(null, arena)) {
			var state = ctx.allocateStateSegment();
			byte[] raw = java.nio.file.Files
					.readAllBytes(base.resolve("04_graph_and_matrix/tc08_graphblas_mxv_pos.impb_data"));
			var inlineSeg = arena.allocate(raw.length, 64);
			java.lang.foreign.MemorySegment.copy(java.lang.foreign.MemorySegment.ofArray(raw), 0, inlineSeg, 0,
					raw.length);
			ctx.setInlineData(inlineSeg, raw.length);

			byte[] bytes = java.nio.file.Files
					.readAllBytes(base.resolve("04_graph_and_matrix/tc08_graphblas_mxv_pos.impb"));
			var progSeg = arena.allocate(bytes.length, 8);
			java.lang.foreign.MemorySegment.copy(bytes, 0, progSeg, java.lang.foreign.ValueLayout.JAVA_BYTE, 0,
					bytes.length);
			var driver = ImpulseMethodHandleCompiler.compileDriver(progSeg, bytes.length / 8);
			driver.execute(ctx, state, null, bytes.length / 8);

			for (int h = 0; h < 3; h++) {
				float[] v = ctx.getFloatVector(h);
				int len = ctx.getFloatVectorLength(h);
				System.out.println("Java h=" + h + " active_len=" + len + " arr.len=" + (v != null ? v.length : -1));
				if (v != null) {
					System.out.print("  [");
					for (int i = 0; i < len; i++)
						System.out.print(v[i] + ", ");
					System.out.println("]");
				}
			}
		}
	}
}
