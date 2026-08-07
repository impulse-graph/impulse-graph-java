package org.impulsegraph.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.impulsegraph.codegen.GeneratorMain;

import java.io.File;

/**
 * Maven Mojo for generating strongly-typed Impulse Graph query builder Java source files during build.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateQueryBuildersMojo extends AbstractMojo {

    @Parameter(property = "schemaFile", defaultValue = "${project.basedir}/src/main/resources/schema.yaml")
    private File schemaFile;

    @Parameter(property = "outputDirectory", defaultValue = "${project.build.directory}/generated-sources/impulse")
    private File outputDirectory;

    @Parameter(property = "packageName", defaultValue = "org.impulsegraph.generated")
    private String packageName;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (schemaFile == null || !schemaFile.exists()) {
            getLog().info("No schema.yaml found at " + (schemaFile != null ? schemaFile.getAbsolutePath() : "null") + ". Generating default schema builders.");
        }

        try {
            getLog().info("Generating strongly-typed Impulse Query Builders to " + outputDirectory.getAbsolutePath());
            if (schemaFile != null && schemaFile.exists()) {
                GeneratorMain.generate(schemaFile.getAbsolutePath(), outputDirectory.getAbsolutePath(), packageName);
            } else {
                File tempSchema = File.createTempFile("impulse-default-schema", ".yaml");
                GeneratorMain.generate(tempSchema.getAbsolutePath(), outputDirectory.getAbsolutePath(), packageName);
            }

            if (project != null) {
                project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
                getLog().info("Added compile source root: " + outputDirectory.getAbsolutePath());
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to generate Impulse query builder source files", e);
        }
    }
}
