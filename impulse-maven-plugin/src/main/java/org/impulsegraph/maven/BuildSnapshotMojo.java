package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.impulsegraph.cli.service.SnapshotService;

import java.io.File;

/**
 * Compiles a CSV/TSV/JSONL manifest ({@code manifest.yaml} or
 * {@code manifest.json}) into an immutable {@code .imps} binary snapshot.
 */
@Mojo(name = "build-snapshot", defaultPhase = LifecyclePhase.GENERATE_TEST_RESOURCES, threadSafe = true)
public class BuildSnapshotMojo extends AbstractMojo {

	@Parameter(property = "impulse.manifestFile", required = true)
	File manifestFile;

	@Parameter(property = "impulse.outputFile", required = true)
	File outputFile;

	@Parameter(property = "impulse.skipIfMissing", defaultValue = "false")
	boolean skipIfMissing;

	@Override
	public void execute() throws MojoExecutionException {
		if (manifestFile == null || !manifestFile.exists()) {
			String location = manifestFile != null ? manifestFile.getAbsolutePath() : "<null>";
			if (skipIfMissing) {
				getLog().info("Skipping impulse:build-snapshot because manifestFile does not exist: " + location);
				return;
			}
			throw new MojoExecutionException("manifestFile does not exist: " + location);
		}
		if (outputFile == null) {
			throw new MojoExecutionException("outputFile must be specified");
		}
		try {
			getLog().info("Building Impulse snapshot " + manifestFile.getAbsolutePath() + " -> "
					+ outputFile.getAbsolutePath());
			SnapshotService.build(manifestFile.toPath(), outputFile.toPath());
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to build snapshot: " + e.getMessage(), e);
		}
	}
}
