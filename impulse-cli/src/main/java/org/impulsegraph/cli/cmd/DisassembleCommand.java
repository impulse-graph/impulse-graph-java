package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.CompilerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "disassemble", mixinStandardHelpOptions = true, description = "Disassemble ImpOps (.impb) binary bytecode into canonical ImpAsm (.impas) text")
public final class DisassembleCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Option(names = {"-i", "--input"}, required = true, description = "Input .impb binary bytecode file")
	private Path input;

	@Option(names = {"-o", "--output"}, description = "Optional output .impas file (stdout if omitted)")
	private Path output;

	@Override
	public Integer call() {
		try {
			String asm = CompilerService.disassemble(input);
			if (output != null) {
				Path parent = output.toAbsolutePath().normalize().getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				Files.writeString(output, asm);
				spec.commandLine().getOut().println("Disassembled -> " + output.toAbsolutePath());
			} else {
				spec.commandLine().getOut().print(asm);
				spec.commandLine().getOut().flush();
			}
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("disassemble failed: " + e.getMessage());
			return 1;
		}
	}
}
