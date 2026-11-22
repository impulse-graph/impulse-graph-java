package org.impulsegraph.cli.service;

import org.impulsegraph.codegen.GeneratorMain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Reusable code generation service delegating to {@link GeneratorMain}.
 * Supports both {@code .yaml}/{@code .yml} schema files and {@code .imps}
 * binary snapshots (extracting schema YAML automatically).
 */
public final class CodegenService {

	private CodegenService() {
	}

	public static void generate(Path schemaOrSnapshot, Path outputDir, String packageName) throws IOException {
		Objects.requireNonNull(schemaOrSnapshot, "schemaOrSnapshot must not be null");
		Objects.requireNonNull(outputDir, "outputDir must not be null");
		if (!Files.exists(schemaOrSnapshot)) {
			throw new IllegalArgumentException("Schema or snapshot file not found: " + schemaOrSnapshot);
		}

		String lower = schemaOrSnapshot.getFileName().toString().toLowerCase(Locale.ROOT);
		if (lower.endsWith(".imps")) {
			String yaml = SchemaService.extractYaml(schemaOrSnapshot);
			Path tempYaml = Files.createTempFile("impulse-extracted-schema-", ".yaml");
			try {
				Files.writeString(tempYaml, yaml);
				GeneratorMain.generate(tempYaml.toAbsolutePath().toString(), outputDir.toAbsolutePath().toString(),
						packageName);
			} finally {
				Files.deleteIfExists(tempYaml);
			}
		} else {
			GeneratorMain.generate(schemaOrSnapshot.toAbsolutePath().toString(), outputDir.toAbsolutePath().toString(),
					packageName);
		}
	}
}
