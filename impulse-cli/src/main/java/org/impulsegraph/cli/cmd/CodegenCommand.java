package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.CodegenService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "codegen", mixinStandardHelpOptions = true, description = "Generate strongly-typed Java query builders from a schema.yaml or .imps snapshot")
public final class CodegenCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Option(names = {"--schema",
			"-s"}, required = true, description = "Input schema YAML (.yaml/.yml) or snapshot (.imps)")
	private Path schema;

	@Option(names = {"--out", "-o"}, required = true, description = "Output directory for generated Java source files")
	private Path out;

	@Option(names = {"--package", "-p"}, description = "Override Java package name for generated sources")
	private String packageName;

	@Override
	public Integer call() {
		try {
			CodegenService.generate(schema, out, packageName);
			spec.commandLine().getOut().println("Generated query builders to " + out.toAbsolutePath());
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("codegen failed: " + e.getMessage());
			return 1;
		}
	}
}
