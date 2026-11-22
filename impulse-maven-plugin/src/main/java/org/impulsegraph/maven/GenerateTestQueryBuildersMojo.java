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
 * Generates strongly-typed Impulse Graph query builder Java test source files
 * during {@code generate-test-sources}.
 */
@Mojo(name = "generate-test", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true)
public class GenerateTestQueryBuildersMojo extends AbstractMojo {

	@Parameter(property = "impulse.testSchemaFile", defaultValue = "${project.basedir}/src/test/resources/schema.yaml")
	File schemaFile;

	@Parameter(property = "impulse.testOutputDirectory", defaultValue = "${project.build.directory}/generated-test-sources/impulse")
	File outputDirectory;

	@Parameter(property = "impulse.testPackageName", defaultValue = "org.impulsegraph.generated.test")
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
				getLog().info("Skipping impulse:generate-test because schemaFile does not exist: " + location);
				return;
			}
			throw new MojoExecutionException("Required test schemaFile does not exist: " + location
					+ " (set skipIfMissing=true to skip when absent)");
		}

		try {
			getLog().info("Generating Impulse test query builders from " + schemaFile.getAbsolutePath() + " -> "
					+ outputDirectory.getAbsolutePath());
			CodegenService.generate(schemaFile.toPath(), outputDirectory.toPath(), packageName);
			if (project != null) {
				project.addTestCompileSourceRoot(outputDirectory.getAbsolutePath());
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to generate Impulse test query builder sources: " + e.getMessage(),
					e);
		}
	}
}
