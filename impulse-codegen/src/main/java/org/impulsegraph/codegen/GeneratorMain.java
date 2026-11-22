package org.impulsegraph.codegen;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaValidator;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.schema.SnapshotSchemaExtractor;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Public CLI and library entrypoint for Impulse Graph Engine v2 typed Java code
 * generation from either {@code .yaml}/{@code .yml} schema manifests or mounted
 * binary {@code .imps} snapshots.
 */
public final class GeneratorMain {

	public static final String DEFAULT_PACKAGE = "org.impulsegraph.generated";

	private GeneratorMain() {
	}

	public static void main(String[] args) {
		if (args.length < 2) {
			System.err.println("Usage: GeneratorMain <schema.yaml|snapshot.imps> <target-folder> [package-name]");
			System.exit(1);
		}
		String inputPath = args[0];
		String targetFolder = args[1];
		String packageNameOverride = args.length >= 3 ? args[2] : null;
		try {
			generate(inputPath, targetFolder, packageNameOverride);
			System.out.println("Successfully generated Impulse Graph sources to " + targetFolder);
		} catch (Exception e) {
			System.err.println("Generation failed: " + e.getMessage());
			System.exit(1);
		}
	}

	public static void generate(String schemaOrImpsPath, String targetFolder) throws IOException {
		generate(schemaOrImpsPath, targetFolder, null);
	}

	public static void generate(String schemaOrImpsPath, String targetFolder, String packageNameOverride)
			throws IOException {
		Objects.requireNonNull(schemaOrImpsPath, "schemaOrImpsPath must not be null");
		Objects.requireNonNull(targetFolder, "targetFolder must not be null");
		generate(Paths.get(schemaOrImpsPath), Paths.get(targetFolder), packageNameOverride);
	}

	public static void generate(Path schemaOrImpsPath, Path targetFolder, String packageNameOverride)
			throws IOException {
		Objects.requireNonNull(schemaOrImpsPath, "schemaOrImpsPath must not be null");
		Objects.requireNonNull(targetFolder, "targetFolder must not be null");
		if (!Files.exists(schemaOrImpsPath)) {
			throw new IllegalArgumentException("Schema or snapshot file does not exist: " + schemaOrImpsPath);
		}

		GraphManifest manifest;
		String fileName = schemaOrImpsPath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (fileName.endsWith(".imps")) {
			try (Arena arena = Arena.ofConfined()) {
				BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(schemaOrImpsPath, arena);
				manifest = SnapshotSchemaExtractor.extract(loaded);
			}
		} else {
			manifest = SchemaYamlCodec.parse(schemaOrImpsPath);
		}

		generateFromManifest(manifest, targetFolder, packageNameOverride);
	}

	public static List<Path> generateFromManifest(GraphManifest manifest, Path targetFolder, String packageNameOverride)
			throws IOException {
		Objects.requireNonNull(manifest, "manifest must not be null");
		Objects.requireNonNull(targetFolder, "targetFolder must not be null");

		List<String> errors = SchemaValidator.validate(manifest);
		if (!errors.isEmpty()) {
			throw new IllegalArgumentException("Invalid Impulse Graph schema: " + String.join("; ", errors));
		}
		if (manifest.domains().isEmpty()) {
			throw new IllegalArgumentException(
					"Invalid Impulse Graph schema: schema must declare at least one domain (domains: or nodes:)");
		}

		String resolvedPackage = resolvePackageName(manifest, packageNameOverride);
		SchemaCodeEmitter emitter = new SchemaCodeEmitter(manifest, resolvedPackage);
		return emitter.emitAll(targetFolder);
	}

	private static String resolvePackageName(GraphManifest manifest, String packageNameOverride) {
		if (packageNameOverride != null && !packageNameOverride.isBlank()) {
			return packageNameOverride.trim();
		}
		if (manifest.packageName() != null && !manifest.packageName().isBlank()) {
			return manifest.packageName().trim();
		}
		return DEFAULT_PACKAGE;
	}
}
