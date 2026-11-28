package org.impulsegraph.compiler.datalog;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.impscm.ImpScmCompiler;

import java.util.List;
import java.util.Map;

/**
 * Datalog parser entrypoint delegating to {@link ImpLogCompiler}.
 */
public final class DatalogParser {

	private DatalogParser() {
	}

	public static ScmProgram parse(String source) {
		return ImpLogCompiler.compile(source);
	}

	public static List<ImpLogCompiler.ParsedRule> parseRules(String source) {
		return ImpLogCompiler.parseRules(source);
	}

	public static String lowerToImpScheme(String source, ImpulseGraphSnapshot snapshot) {
		return ImpLogCompiler.lowerToImpScheme(source, snapshot, Map.of());
	}

	public static ImpScmCompiler.CompileResult compileToBytecode(String source, ImpulseGraphSnapshot snapshot) {
		return ImpLogCompiler.compileToBytecode(source, snapshot, Map.of());
	}
}
