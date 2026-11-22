package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.SnapshotService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "stats", mixinStandardHelpOptions = true, description = "Compute domain counts, degree distributions (min/avg/P50/P99/max), and supernode counts")
public final class StatsCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", description = "Input .imps snapshot file")
	private Path snapshot;

	@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
	private String format;

	@Option(names = {"--verbose", "-v"}, description = "Include stdDev, P90, and sparsity details")
	private boolean verbose;

	@Option(names = {
			"--supernode-threshold"}, defaultValue = "3.0", description = "Z-score threshold for supernode detection (default: 3.0)")
	private double supernodeThreshold;

	@Override
	public Integer call() {
		try {
			SnapshotService.StatsOptions opts = new SnapshotService.StatsOptions(supernodeThreshold, verbose);
			SnapshotService.StatsReport report = SnapshotService.stats(snapshot, opts);
			if ("json".equalsIgnoreCase(format)) {
				spec.commandLine().getOut().println(report.toJson());
			} else {
				spec.commandLine().getOut().print(report.toText(verbose));
			}
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("stats failed: " + e.getMessage());
			return 1;
		}
	}
}
