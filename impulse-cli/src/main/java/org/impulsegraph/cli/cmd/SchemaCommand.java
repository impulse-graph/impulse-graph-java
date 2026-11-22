package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.SchemaService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "schema", mixinStandardHelpOptions = true, description = "Schema extraction, validation, and multi-consumer compatibility gating", subcommands = {
		SchemaCommand.ExtractCommand.class, SchemaCommand.ValidateCommand.class,
		SchemaCommand.CheckCompatCommand.class})
public final class SchemaCommand implements Runnable {

	@Spec
	private CommandSpec spec;

	@Override
	public void run() {
		spec.commandLine().usage(spec.commandLine().getOut());
	}

	@Command(name = "extract", mixinStandardHelpOptions = true, description = "Extract canonical .imps.schema.yaml from a binary snapshot")
	public static final class ExtractCommand implements Callable<Integer> {

		@Spec
		private CommandSpec spec;

		@Parameters(index = "0", description = "Input .imps snapshot file")
		private Path snapshot;

		@Option(names = {"-o", "--output"}, description = "Write extracted schema YAML to file (stdout if omitted)")
		private Path output;

		@Override
		public Integer call() {
			try {
				if (output != null) {
					SchemaService.extractToFile(snapshot, output);
					spec.commandLine().getOut().println("Extracted schema to " + output.toAbsolutePath());
				} else {
					String yaml = SchemaService.extractYaml(snapshot);
					spec.commandLine().getOut().print(yaml);
					spec.commandLine().getOut().flush();
				}
				return 0;
			} catch (Exception e) {
				spec.commandLine().getErr().println("schema extract failed: " + e.getMessage());
				return 1;
			}
		}
	}

	@Command(name = "validate", mixinStandardHelpOptions = true, description = "Validate schema.yaml invariants and optional snapshot contract conformance")
	public static final class ValidateCommand implements Callable<Integer> {

		@Spec
		private CommandSpec spec;

		@Parameters(index = "0", description = "Schema YAML file to validate")
		private Path schema;

		@Option(names = {"--snapshot", "-s"}, description = "Optional .imps snapshot to validate against the schema")
		private Path snapshot;

		@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
		private String format;

		@Override
		public Integer call() {
			try {
				SchemaService.SchemaValidationReport report = SchemaService.validateSchema(schema, snapshot);
				if ("json".equalsIgnoreCase(format)) {
					spec.commandLine().getOut().println(report.toJson());
				} else {
					spec.commandLine().getOut().print(report.toText());
					spec.commandLine().getOut().flush();
				}
				return report.valid() ? 0 : 1;
			} catch (Exception e) {
				spec.commandLine().getErr().println("schema validate failed: " + e.getMessage());
				return 1;
			}
		}
	}

	@Command(name = "check-compat", mixinStandardHelpOptions = true, description = "Validate a candidate .imps snapshot against one or more consumer schema contracts")
	public static final class CheckCompatCommand implements Callable<Integer> {

		@Spec
		private CommandSpec spec;

		@Option(names = {"--snapshot", "-s"}, required = true, description = "Candidate .imps snapshot file")
		private Path snapshot;

		@Option(names = {"--consumer", "-c"}, required = true, description = "Consumer schema YAML file (repeatable)")
		private List<Path> consumers;

		@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
		private String format;

		@Override
		public Integer call() {
			try {
				SchemaService.MultiConsumerCompatReport report = SchemaService.checkCompatibility(snapshot, consumers);
				if ("json".equalsIgnoreCase(format)) {
					spec.commandLine().getOut().println(report.toJson());
				} else {
					spec.commandLine().getOut().print(report.toText());
					spec.commandLine().getOut().flush();
				}
				return report.allCompatible() ? 0 : 1;
			} catch (Exception e) {
				spec.commandLine().getErr().println("schema check-compat failed: " + e.getMessage());
				return 1;
			}
		}
	}
}
