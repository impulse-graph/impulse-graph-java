package org.impulsegraph.codegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodegenVerificationTest {

    @TempDir
    Path tempDir;

    @Test
    public void testSocialGraphGeneration() throws IOException {
        Path yamlPath = Paths.get("src/test/resources/schemas/social-graph.yaml");
        GeneratorMain.generate(yamlPath.toString(), tempDir.toString());
        
        Path generatedFile = tempDir.resolve("org/impulsegraph/example/social/SocialGraphSnapshot.java");
        assertTrue(generatedFile.toFile().exists(), "Generated SocialGraphSnapshot.java should exist");
    }

    @Test
    public void testMinimalGraphGeneration() throws IOException {
        Path yamlPath = Paths.get("src/test/resources/schemas/minimal-graph.yaml");
        GeneratorMain.generate(yamlPath.toString(), tempDir.toString());
        
        Path generatedFile = tempDir.resolve("org/impulsegraph/example/minimal/MinimalGraphSnapshot.java");
        assertTrue(generatedFile.toFile().exists(), "Generated MinimalGraphSnapshot.java should exist");
    }
}
