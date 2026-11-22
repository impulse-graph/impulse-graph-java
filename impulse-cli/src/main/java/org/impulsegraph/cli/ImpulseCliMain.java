package org.impulsegraph.cli;

import org.impulsegraph.cli.cmd.AssembleCommand;
import org.impulsegraph.cli.cmd.BuildCommand;
import org.impulsegraph.cli.cmd.CodegenCommand;
import org.impulsegraph.cli.cmd.CompileCommand;
import org.impulsegraph.cli.cmd.DisassembleCommand;
import org.impulsegraph.cli.cmd.ExplainCommand;
import org.impulsegraph.cli.cmd.InspectCommand;
import org.impulsegraph.cli.cmd.RunCommand;
import org.impulsegraph.cli.cmd.SchemaCommand;
import org.impulsegraph.cli.cmd.SnapshotCommand;
import org.impulsegraph.cli.cmd.StatsCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Main entrypoint for {@code impulse-j}, the pure-Java Impulse Graph Engine
 * CLI.
 */
@Command(name = "impulse-j", mixinStandardHelpOptions = true, version = "0.11.0-SNAPSHOT", description = "Impulse Graph Engine Java CLI (impulse-j)", subcommands = {
		CodegenCommand.class, SchemaCommand.class, BuildCommand.class, InspectCommand.class, SnapshotCommand.class,
		StatsCommand.class, CompileCommand.class, AssembleCommand.class, DisassembleCommand.class, ExplainCommand.class,
		RunCommand.class})
public final class ImpulseCliMain implements Runnable {

	@Spec
	private CommandSpec spec;

	@Override
	public void run() {
		spec.commandLine().usage(spec.commandLine().getOut());
	}

	public static CommandLine createCommandLine() {
		return new CommandLine(new ImpulseCliMain());
	}

	public static void main(String[] args) {
		CommandLine cmd = createCommandLine();
		int exitCode = cmd.execute(args);
		cmd.getOut().flush();
		cmd.getErr().flush();
		System.exit(exitCode);
	}
}
