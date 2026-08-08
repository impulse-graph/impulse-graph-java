package org.impulsegraph.gradle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;

public class ImpulseGradlePluginTest {

    @Test
    public void testPluginExtensionDefaults() {
        ImpulseGradlePlugin plugin = new ImpulseGradlePlugin();
        ImpulseExtension ext = plugin.getExtension();
        Assertions.assertNotNull(ext);
        Assertions.assertEquals("org.impulsegraph.generated", ext.getPackageName());
    }

    @Test
    public void testCodegenTaskExecution(@TempDir Path tempDir) throws Exception {
        ImpulseGradlePlugin plugin = new ImpulseGradlePlugin();
        ImpulseExtension ext = plugin.getExtension();

        File outDir = tempDir.resolve("generated").toFile();
        ext.setOutputDirectory(outDir);
        ext.setPackageName("com.example.testgen");

        GenerateQueryBuildersTask task = plugin.createGenerateTask();
        task.generate();

        Assertions.assertTrue(outDir.exists());
        Assertions.assertTrue(outDir.listFiles().length > 0);
    }

    @Test
    public void testCsvCompileAndInspectTaskExecution(@TempDir Path tempDir) throws Exception {
        File csvFile = tempDir.resolve("test_edges.csv").toFile();
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("# src, dst, rel\n");
            writer.write("1, 2, userToGroup\n");
            writer.write("2, 3, groupToRole\n");
            writer.write("1, 3, userToRole\n");
        }

        File snapshotFile = tempDir.resolve("compiled_test.imps").toFile();

        ImpulseGradlePlugin plugin = new ImpulseGradlePlugin();
        ImpulseExtension ext = plugin.getExtension();
        ext.setCsvFile(csvFile);
        ext.setSnapshotOutputFile(snapshotFile);

        CompileCsvToSnapshotTask compileTask = plugin.createCompileCsvTask();
        compileTask.compileCsv();

        Assertions.assertTrue(snapshotFile.exists());
        Assertions.assertTrue(snapshotFile.length() > 0);

        InspectSnapshotTask inspectTask = plugin.createInspectTask();
        inspectTask.inspect();
    }
}
