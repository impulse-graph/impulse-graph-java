package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.impulsegraph.cli.service.SchemaService;
import org.impulsegraph.cli.service.SnapshotService;

import java.io.File;

/**
 * Validates an {@code .imps} binary snapshot (header, 128B alignment, CSR/CSC
 * topology, SHA-256) and optional consumer {@code schemaFile} contract
 * conformance during the {@code verify} phase.
 */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class ValidateSnapshotMojo extends AbstractMojo {

	@Parameter(property = "impulse.snapshotFile", required = true)
	File snapshotFile;

	@Parameter(property = "impulse.schemaFile")
	File schemaFile;

	@Parameter(property = "impulse.strictAlignment", defaultValue = "false")
	boolean strictAlignment;

	@Parameter(property = "impulse.deepAudit", defaultValue = "false")
	boolean deepAudit;

	@Parameter(property = "impulse.quickCheck", defaultValue = "false")
	boolean quickCheck;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (snapshotFile == null || !snapshotFile.exists()) {
			throw new MojoExecutionException("snapshotFile does not exist: "
					+ (snapshotFile != null ? snapshotFile.getAbsolutePath() : "<null>"));
		}

		try {
			SnapshotService.ValidateOptions opts = new SnapshotService.ValidateOptions(strictAlignment, quickCheck,
					deepAudit);
			SnapshotService.ValidationReport report = SnapshotService.validate(snapshotFile.toPath(), opts);
			getLog().info(report.toText());
			if (!report.valid()) {
				throw new MojoFailureException("Snapshot validation failed:\n" + report.toText());
			}

			if (schemaFile != null) {
				if (!schemaFile.exists()) {
					throw new MojoExecutionException(
							"Configured schemaFile does not exist: " + schemaFile.getAbsolutePath());
				}
				SchemaService.SchemaValidationReport schemaReport = SchemaService.validateSchema(schemaFile.toPath(),
						snapshotFile.toPath());
				getLog().info(schemaReport.toText());
				if (!schemaReport.valid()) {
					throw new MojoFailureException(
							"Snapshot schema contract validation failed:\n" + schemaReport.toText());
				}
			}
		} catch (MojoFailureException | MojoExecutionException e) {
			throw e;
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to validate snapshot: " + e.getMessage(), e);
		}
	}
}
