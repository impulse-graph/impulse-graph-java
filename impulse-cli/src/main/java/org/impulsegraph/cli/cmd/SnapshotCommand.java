package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.SnapshotService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "snapshot", mixinStandardHelpOptions = true, description = "Snapshot lifecycle commands (build, inspect, validate, stats)", subcommands = {
		BuildCommand.class, InspectCommand.class, SnapshotCommand.ValidateCommand.class, StatsCommand.class})
public final class SnapshotCommand implements Runnable {

	@Spec
	private CommandSpec spec;

	@Override
	public void run() {
		spec.commandLine().usage(spec.commandLine().getOut());
	}

	@Command(name = "validate", mixinStandardHelpOptions = true, description = "Verify binary header, 128B alignment, CSR monotonicity/bounds, CSC transpose consistency, and SHA-256")
	public static final class ValidateCommand implements Callable<Integer> {

		@Spec
		private CommandSpec spec;

		@Parameters(index = "0", description = "Input .imps snapshot file")
		private Path snapshot;

		@Option(names = {
				"--strict-alignment"}, description = "Enforce strict 128-byte alignment on header and CSR section offsets")
		private boolean strictAlignment;

		@Option(names = {"--quick-check"}, description = "Skip SHA-256 footer checksum verification")
		private boolean quickCheck;

		@Option(names = {
				"--deep-audit"}, description = "Verify full CSC transpose edge count and multiset consistency against CSR")
		private boolean deepAudit;

		@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
		private String format;

		@Override
		public Integer call() {
			try {
				SnapshotService.ValidateOptions opts = new SnapshotService.ValidateOptions(strictAlignment, quickCheck,
						deepAudit);
				SnapshotService.ValidationReport report = SnapshotService.validate(snapshot, opts);
				if ("json".equalsIgnoreCase(format)) {
					spec.commandLine().getOut().println(report.toJson());
				} else {
					spec.commandLine().getOut().print(report.toText());
					spec.commandLine().getOut().flush();
				}
				return report.valid() ? 0 : 1;
			} catch (Exception e) {
				spec.commandLine().getErr().println("snapshot validate failed: " + e.getMessage());
				return 1;
			}
		}
	}
}
