package org.impulsegraph.compiler.datalog;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.impscm.v11.ImpScmV11Compiler;

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

	public static String lowerToImpSchemeV11(String source, ImpulseGraphSnapshot snapshot) {
		return ImpLogCompiler.lowerToImpSchemeV11(source, snapshot, Map.of());
	}

	public static ImpScmV11Compiler.CompileResult compileToV11(String source, ImpulseGraphSnapshot snapshot) {
		return ImpLogCompiler.compileToV11(source, snapshot, Map.of());
	}
}
