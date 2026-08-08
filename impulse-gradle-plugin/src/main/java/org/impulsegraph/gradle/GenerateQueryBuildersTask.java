package org.impulsegraph.gradle;

import org.impulsegraph.codegen.GeneratorMain;

import java.io.File;

/**
 * Task executing strongly-typed Impulse Query Builder code generation (`impulseGenerate`).
 */
public class GenerateQueryBuildersTask {

    private File schemaFile;
    private File outputDirectory;
    private String packageName = "org.impulsegraph.generated";

    public void setSchemaFile(File schemaFile) {
        this.schemaFile = schemaFile;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void generate() throws Exception {
        if (outputDirectory == null) {
            outputDirectory = new File("build/generated/sources/impulse");
        }
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        if (schemaFile != null && schemaFile.exists()) {
            GeneratorMain.generate(schemaFile.getAbsolutePath(), outputDirectory.getAbsolutePath(), packageName);
        } else {
            File tempSchema = File.createTempFile("impulse-default-schema", ".yaml");
            GeneratorMain.generate(tempSchema.getAbsolutePath(), outputDirectory.getAbsolutePath(), packageName);
        }
    }
}
