package org.impulsegraph.cli.cmd;

import org.impulsegraph.cli.service.SnapshotService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "inspect", mixinStandardHelpOptions = true, description = "Inspect Page 0 header, domains, relations, CSR/CSC layout, metadata, and SHA-256 of an .imps snapshot")
public final class InspectCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec;

	@Parameters(index = "0", description = "Input .imps snapshot file")
	private Path snapshot;

	@Option(names = {"--format"}, defaultValue = "text", description = "Output format: text|json")
	private String format;

	@Option(names = {"--page-map"}, description = "Include 128B/4KB section alignment page map")
	private boolean pageMap;

	@Option(names = {"--domain"}, description = "Filter output to a specific domain name")
	private String domain;

	@Option(names = {"--relation"}, description = "Filter output to a specific relation name")
	private String relation;

	@Option(names = {"--anonymize"}, description = "Anonymize domain, relation, attribute, and metadata identifiers")
	private boolean anonymize;

	@Override
	public Integer call() {
		try {
			SnapshotService.InspectOptions opts = new SnapshotService.InspectOptions(pageMap, domain, relation,
					anonymize);
			SnapshotService.InspectReport report = SnapshotService.inspect(snapshot, opts);
			if ("json".equalsIgnoreCase(format)) {
				spec.commandLine().getOut().println(report.toJson(pageMap));
			} else {
				spec.commandLine().getOut().print(report.toText(pageMap));
			}
			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("inspect failed: " + e.getMessage());
			return 1;
		}
	}
}
