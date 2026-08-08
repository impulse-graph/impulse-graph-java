package org.impulsegraph.gradle;

/**
 * Gradle plugin entrypoint for Impulse Graph tooling (`io.impulse.graph`).
 */
public class ImpulseGradlePlugin {

    public static final String EXTENSION_NAME = "impulse";
    public static final String TASK_GENERATE = "impulseGenerate";
    public static final String TASK_COMPILE_CSV = "impulseCompileCsv";
    public static final String TASK_INSPECT = "impulseInspect";

    private final ImpulseExtension extension = new ImpulseExtension();

    public ImpulseExtension getExtension() {
        return extension;
    }

    public GenerateQueryBuildersTask createGenerateTask() {
        GenerateQueryBuildersTask task = new GenerateQueryBuildersTask();
        task.setSchemaFile(extension.getSchemaFile());
        task.setOutputDirectory(extension.getOutputDirectory());
        task.setPackageName(extension.getPackageName());
        return task;
    }

    public CompileCsvToSnapshotTask createCompileCsvTask() {
        CompileCsvToSnapshotTask task = new CompileCsvToSnapshotTask();
        task.setCsvFile(extension.getCsvFile());
        task.setOutputFile(extension.getSnapshotOutputFile());
        return task;
    }

    public InspectSnapshotTask createInspectTask() {
        InspectSnapshotTask task = new InspectSnapshotTask();
        task.setSnapshotFile(extension.getSnapshotOutputFile());
        return task;
    }
}
