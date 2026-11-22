package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.SnapshotService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "build", mixinStandardHelpOptions = true, description = "Build a binary .imps snapshot from a manifest.yaml or manifest.json")
public final class BuildCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Option(names = {"-m", "--manifest"}, required = true, description = "Input manifest (.yaml/.yml/.json)")
	private Path manifest;

	@Option(names = {"-o", "--output"}, required = true, description = "Output .imps snapshot path")
	private Path output;

	@Override
	public Integer call() {
		try {
			SnapshotService.build(manifest, output);
			spec.commandLine().getOut().println("Built snapshot: " + output.toAbsolutePath());
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("build failed: " + e.getMessage());
			return 1;
		}
	}
}
