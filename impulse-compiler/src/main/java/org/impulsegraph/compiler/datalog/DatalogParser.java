package org.impulsegraph.compiler.datalog;

import org.impulsegraph.compiler.ast.ScmProgram;

/**
 * Datalog parser entrypoint delegating to {@link ImpLogCompiler}.
 */
public final class DatalogParser {

	private DatalogParser() {
	}

	public static ScmProgram parse(String source) {
		return ImpLogCompiler.compile(source);
	}
}
