package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.CompilerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "assemble", mixinStandardHelpOptions = true, description = "Assemble ImpAsm (.impas) text into packed 8-byte ImpOps (.impb) binary bytecode")
public final class AssembleCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Option(names = {"-i", "--input"}, required = true, description = "Input .impas assembly file")
	private Path input;

	@Option(names = {"-o", "--output"}, required = true, description = "Output .impb binary bytecode file")
	private Path output;

	@Override
	public Integer call() {
		try {
			int count = CompilerService.assemble(input, output);
			spec.commandLine().getOut().println("Assembled " + count + " instruction(s) -> " + output.toAbsolutePath());
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("assemble failed: " + e.getMessage());
			return 1;
		}
	}
}
