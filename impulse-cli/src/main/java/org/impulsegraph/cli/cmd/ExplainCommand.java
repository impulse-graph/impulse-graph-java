package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.CompilerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "explain", mixinStandardHelpOptions = true, description = "Generate a multi-pass compilation and disassembly EXPLAIN report for a query")
public final class ExplainCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", description = "Query file path or inline query string")
	private String query;

	@Option(names = {"--lang"}, description = "Query language: cypher|impk|implog|impscm")
	private String lang;

	@Option(names = {"--snapshot",
			"-s"}, description = "Optional .imps snapshot for Stage 2 physical binding and disassembly")
	private Path snapshot;

	@Option(names = {"--schema"}, description = "Optional schema.yaml for Stage 1 schema validation")
	private Path schema;

	@Override
	public Integer call() {
		try {
			CompilerService.QueryLanguage parsedLang = CompilerService.QueryLanguage.parse(lang);
			String report = CompilerService.explain(query, parsedLang, snapshot, schema);
			spec.commandLine().getOut().print(report);
			spec.commandLine().getOut().flush();
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("explain failed: " + e.getMessage());
			return 1;
		}
	}
}
