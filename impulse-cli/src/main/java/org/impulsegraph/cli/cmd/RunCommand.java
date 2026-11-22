package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.ExecutionService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "run", mixinStandardHelpOptions = true, description = "Execute an .impb, .impas, .impscm, .cypher, .impk, or .implog program against an .imps snapshot")
public final class RunCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Option(names = {"-s", "--snapshot"}, required = true, description = "Input .imps snapshot file")
	private Path snapshot;

	@Option(names = {"-b",
			"--bytecode"}, required = true, description = "Program or query file (.impb, .impas, .impscm, .cypher, .impk, .implog)")
	private Path bytecode;

	@Option(names = {"-i",
			"--input-val"}, defaultValue = "0", description = "Initial seed node ID or input integer value (default: 0)")
	private long inputVal;

	@Option(names = {"--verify"}, description = "Run ImpulseVmValidator register/type verification before execution")
	private boolean verify;

	@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
	private String format;

	@Override
	public Integer call() {
		try {
			ExecutionService.ExecutionReport report = ExecutionService.run(snapshot, bytecode, inputVal, verify);
			if ("json".equalsIgnoreCase(format)) {
				spec.commandLine().getOut().println(report.toJson());
			} else {
				spec.commandLine().getOut().print(report.toText());
			}
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("run failed: " + e.getMessage());
			return 1;
		}
	}
}
