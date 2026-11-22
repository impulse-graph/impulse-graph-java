package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.impulsegraph.cli.service.SchemaService;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-consumer compatibility CI gate Mojo: validates a candidate
 * {@code .imps} snapshot against every supplied consumer schema YAML, failing
 * the Maven build if any consumer contract is violated.
 */
@Mojo(name = "check-compat", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckCompatMojo extends AbstractMojo {

	@Parameter(property = "impulse.snapshotFile", required = true)
	File snapshotFile;

	@Parameter(required = true)
	List<File> consumerSchemas;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (snapshotFile == null || !snapshotFile.exists()) {
			throw new MojoExecutionException("snapshotFile does not exist: "
					+ (snapshotFile != null ? snapshotFile.getAbsolutePath() : "<null>"));
		}
		if (consumerSchemas == null || consumerSchemas.isEmpty()) {
			throw new MojoExecutionException("At least one consumerSchemas entry must be configured");
		}

		List<Path> schemaPaths = new ArrayList<>(consumerSchemas.size());
		for (File f : consumerSchemas) {
			if (f == null || !f.exists()) {
				throw new MojoExecutionException(
						"Consumer schema file does not exist: " + (f != null ? f.getAbsolutePath() : "<null>"));
			}
			schemaPaths.add(f.toPath());
		}

		try {
			SchemaService.MultiConsumerCompatReport report = SchemaService.checkCompatibility(snapshotFile.toPath(),
					schemaPaths);
			getLog().info(report.toText());
			if (!report.allCompatible()) {
				throw new MojoFailureException(
						"Candidate snapshot failed multi-consumer compatibility gate:\n" + report.toText());
			}
		} catch (MojoFailureException e) {
			throw e;
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to execute multi-consumer compatibility check: " + e.getMessage(),
					e);
		}
	}
}
