package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.CompilerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "compile", mixinStandardHelpOptions = true, description = "Compile Cypher, ImpK, ImpLog, or ImpScheme queries to ImpScheme IR, ImpAsm (.impas), or ImpOps bytecode (.impb)")
public final class CompileCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", description = "Query source file (.cypher, .impk, .implog, .impscm)")
	private Path queryFile;

	@Option(names = {"-o",
			"--output"}, description = "Output file (.impscm, .impas, or .impb); prints to stdout if omitted")
	private Path output;

	@Option(names = {
			"--schema"}, description = "Optional schema.yaml to validate query references via SchemaBindValidatorPass")
	private Path schema;

	@Option(names = {"--snapshot",
			"-s"}, description = "Optional .imps snapshot for physical binding and bytecode/assembly emission")
	private Path snapshot;

	@Option(names = {"--emit-ir"}, description = "Emit normalized ImpScheme (.impscm) IR instead of assembly/bytecode")
	private boolean emitIr;

	@Option(names = {"--lang"}, description = "Override language detection: cypher|impk|implog|impscm")
	private String lang;

	@Override
	public Integer call() {
		try {
			CompilerService.QueryLanguage parsedLang = CompilerService.QueryLanguage.parse(lang);
			CompilerService.CompileResult res = CompilerService.compile(queryFile, parsedLang, schema, snapshot, emitIr,
					output);
			if (res.writtenPath() != null) {
				spec.commandLine().getOut().println("Compiled (" + res.language() + ") -> " + res.writtenPath());
			} else if (res.textOutput() != null) {
				spec.commandLine().getOut().println(res.textOutput());
			}
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("compile failed: " + e.getMessage());
			return 1;
		}
	}
}
