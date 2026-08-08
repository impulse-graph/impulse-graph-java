package org.impulsegraph.gradle;

import java.io.File;

/**
 * Gradle Extension object for configuring Impulse Graph build properties.
 */
public class ImpulseExtension {

    private File schemaFile;
    private File outputDirectory;
    private String packageName = "org.impulsegraph.generated";
    private File csvFile;
    private File snapshotOutputFile;

    public File getSchemaFile() {
        return schemaFile;
    }

    public void setSchemaFile(File schemaFile) {
        this.schemaFile = schemaFile;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public File getCsvFile() {
        return csvFile;
    }

    public void setCsvFile(File csvFile) {
        this.csvFile = csvFile;
    }

    public File getSnapshotOutputFile() {
        return snapshotOutputFile;
    }

    public void setSnapshotOutputFile(File snapshotOutputFile) {
        this.snapshotOutputFile = snapshotOutputFile;
    }
}
