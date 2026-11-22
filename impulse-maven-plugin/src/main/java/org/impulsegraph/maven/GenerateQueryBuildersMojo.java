package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.impulsegraph.cli.service.CodegenService;

import java.io.File;

/**
 * Generates strongly-typed Impulse Graph query builder Java source files during
 * {@code generate-sources}.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateQueryBuildersMojo extends AbstractMojo {

	@Parameter(property = "impulse.schemaFile", defaultValue = "${project.basedir}/src/main/resources/schema.yaml")
	File schemaFile;

	@Parameter(property = "impulse.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/impulse")
	File outputDirectory;

	@Parameter(property = "impulse.packageName", defaultValue = "org.impulsegraph.generated")
	String packageName;

	@Parameter(property = "impulse.skipIfMissing", defaultValue = "false")
	boolean skipIfMissing;

	@Parameter(defaultValue = "${project}", readonly = true)
	MavenProject project;

	@Override
	public void execute() throws MojoExecutionException {
		if (schemaFile == null || !schemaFile.exists()) {
			String location = schemaFile != null ? schemaFile.getAbsolutePath() : "<null>";
			if (skipIfMissing) {
				getLog().info("Skipping impulse:generate because schemaFile does not exist: " + location);
				return;
			}
			throw new MojoExecutionException("Required schemaFile does not exist: " + location
					+ " (set skipIfMissing=true to skip when absent)");
		}

		try {
			getLog().info("Generating Impulse query builders from " + schemaFile.getAbsolutePath() + " -> "
					+ outputDirectory.getAbsolutePath());
			CodegenService.generate(schemaFile.toPath(), outputDirectory.toPath(), packageName);
			if (project != null) {
				project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to generate Impulse query builder sources: " + e.getMessage(), e);
		}
	}
}
