package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.impulsegraph.cli.service.SchemaService;

import java.io.File;

/**
 * Extracts a canonical {@code .imps.schema.yaml} manifest from an existing
 * {@code .imps} binary snapshot.
 */
@Mojo(name = "extract-schema", threadSafe = true)
public class ExtractSchemaMojo extends AbstractMojo {

	@Parameter(property = "impulse.snapshotFile", required = true)
	File snapshotFile;

	@Parameter(property = "impulse.outputFile", required = true)
	File outputFile;

	@Override
	public void execute() throws MojoExecutionException {
		if (snapshotFile == null || !snapshotFile.exists()) {
			throw new MojoExecutionException("snapshotFile does not exist: "
					+ (snapshotFile != null ? snapshotFile.getAbsolutePath() : "<null>"));
		}
		if (outputFile == null) {
			throw new MojoExecutionException("outputFile must be specified");
		}
		try {
			SchemaService.extractToFile(snapshotFile.toPath(), outputFile.toPath());
			getLog().info("Extracted schema YAML from " + snapshotFile.getAbsolutePath() + " -> "
					+ outputFile.getAbsolutePath());
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to extract schema YAML: " + e.getMessage(), e);
		}
	}
}
