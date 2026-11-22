package org.impulsegraph.cli.service;

import org.impulsegraph.api.schema.CompatibilityViolation;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaValidator;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.api.schema.SnapshotCompatibilityException;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.schema.SnapshotContractValidator;
import org.impulsegraph.storage.schema.SnapshotSchemaExtractor;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable service for schema extraction, structural/semantic validation, and
 * multi-consumer compatibility gating.
 */
public final class SchemaService {

	private SchemaService() {
	}

	public record SchemaValidationReport(Path schemaPath, Path snapshotPath, boolean valid, String fingerprintSha256,
			List<String> schemaErrors, List<CompatibilityViolation> snapshotViolations) {

		public String toText() {
			StringBuilder sb = new StringBuilder();
			sb.append("Schema Validation Report\n");
			sb.append("  Schema     : ").append(schemaPath).append('\n');
			if (snapshotPath != null) {
				sb.append("  Snapshot   : ").append(snapshotPath).append('\n');
			}
			if (fingerprintSha256 != null && !fingerprintSha256.isEmpty()) {
				sb.append("  SHA-256    : ").append(fingerprintSha256).append('\n');
			}
			sb.append("  Status     : ").append(valid ? "PASS" : "FAIL").append('\n');
			if (!schemaErrors.isEmpty()) {
				sb.append("  Schema Errors (").append(schemaErrors.size()).append("):\n");
				for (String err : schemaErrors) {
					sb.append("    - ").append(err).append('\n');
				}
			}
			if (!snapshotViolations.isEmpty()) {
				sb.append("  Snapshot Contract Violations (").append(snapshotViolations.size()).append("):\n");
				for (CompatibilityViolation v : snapshotViolations) {
					sb.append("    - ").append(v.formatSummary()).append('\n');
				}
			}
			return sb.toString();
		}

		public String toJson() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("schema", schemaPath.toString());
			if (snapshotPath != null) {
				root.put("snapshot", snapshotPath.toString());
			}
			root.put("fingerprintSha256", fingerprintSha256);
			root.put("valid", valid);
			root.put("schemaErrors", schemaErrors);
			root.put("snapshotViolations", formatViolationsAsMaps(snapshotViolations));
			return JsonWriter.toJson(root);
		}
	}

	public record ConsumerCompatResult(Path consumerSchemaPath, String graphName, String version, boolean compatible,
			List<String> schemaErrors, List<CompatibilityViolation> violations) {
	}

	public record MultiConsumerCompatReport(Path snapshotPath, boolean allCompatible,
			List<ConsumerCompatResult> consumers) {

		public String toText() {
			StringBuilder sb = new StringBuilder();
			sb.append("Multi-Consumer Compatibility Report\n");
			sb.append("  Snapshot : ").append(snapshotPath).append('\n');
			sb.append("  Overall  : ").append(allCompatible ? "COMPATIBLE" : "INCOMPATIBLE").append('\n');
			sb.append("  Consumers checked: ").append(consumers.size()).append('\n');
			for (ConsumerCompatResult r : consumers) {
				sb.append("  - [").append(r.compatible() ? "PASS" : "FAIL").append("] ").append(r.consumerSchemaPath());
				if (r.graphName() != null) {
					sb.append(" (").append(r.graphName()).append(" v").append(r.version()).append(')');
				}
				sb.append('\n');
				for (String err : r.schemaErrors()) {
					sb.append("      [SCHEMA_ERROR] ").append(err).append('\n');
				}
				for (CompatibilityViolation v : r.violations()) {
					sb.append("      ").append(v.formatSummary()).append('\n');
				}
			}
			return sb.toString();
		}

		public String toJson() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("snapshot", snapshotPath.toString());
			root.put("compatible", allCompatible);
			List<Map<String, Object>> list = new ArrayList<>();
			for (ConsumerCompatResult r : consumers) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("consumer", r.consumerSchemaPath().toString());
				item.put("graphName", r.graphName());
				item.put("version", r.version());
				item.put("compatible", r.compatible());
				item.put("schemaErrors", r.schemaErrors());
				item.put("violations", formatViolationsAsMaps(r.violations()));
				list.add(item);
			}
			root.put("consumers", list);
			return JsonWriter.toJson(root);
		}
	}

	public static String extractYaml(Path snapshotPath) throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, false);
			return SnapshotSchemaExtractor.extractYaml(loaded);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to extract schema from snapshot " + snapshotPath + ": " + e.getMessage(), e);
		}
	}

	public static Path extractToFile(Path snapshotPath, Path outputPath) throws IOException {
		Objects.requireNonNull(outputPath, "outputPath must not be null");
		String yaml = extractYaml(snapshotPath);
		Path parent = outputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(outputPath, yaml);
		return outputPath;
	}

	public static SchemaValidationReport validateSchema(Path schemaPath, Path optionalSnapshotPath) throws IOException {
		Objects.requireNonNull(schemaPath, "schemaPath must not be null");
		GraphManifest manifest = SchemaYamlCodec.parse(schemaPath);
		String fingerprint = manifest.schemaFingerprintSha256();
		List<String> schemaErrors = SchemaValidator.validate(manifest);
		List<CompatibilityViolation> snapshotViolations = new ArrayList<>();

		if (optionalSnapshotPath != null) {
			try (Arena arena = Arena.ofConfined()) {
				BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(optionalSnapshotPath,
						arena, false);
				SnapshotContractValidator.validateAndBind(loaded, manifest);
			} catch (SnapshotCompatibilityException sce) {
				snapshotViolations.addAll(sce.violations());
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				snapshotViolations.add(new CompatibilityViolation(CompatibilityViolation.Severity.ERROR,
						CompatibilityViolation.Category.SNAPSHOT_LOAD_ERROR, optionalSnapshotPath.toString(),
						"valid .imps snapshot", e.getMessage(), "Verify the snapshot file integrity"));
			}
		}

		boolean valid = schemaErrors.isEmpty() && snapshotViolations.isEmpty();
		return new SchemaValidationReport(schemaPath, optionalSnapshotPath, valid, fingerprint, schemaErrors,
				List.copyOf(snapshotViolations));
	}

	public static MultiConsumerCompatReport checkCompatibility(Path snapshotPath, List<Path> consumerSchemaPaths)
			throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		Objects.requireNonNull(consumerSchemaPaths, "consumerSchemaPaths must not be null");
		if (consumerSchemaPaths.isEmpty()) {
			throw new IllegalArgumentException("At least one consumer schema path is required");
		}

		List<ConsumerCompatResult> results = new ArrayList<>(consumerSchemaPaths.size());
		boolean allOk = true;

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, false);
			for (Path consumerPath : consumerSchemaPaths) {
				GraphManifest manifest = SchemaYamlCodec.parse(consumerPath);
				List<String> schemaErrors = SchemaValidator.validate(manifest);
				List<CompatibilityViolation> violations = new ArrayList<>();
				try {
					SnapshotContractValidator.validateAndBind(loaded, manifest);
				} catch (SnapshotCompatibilityException sce) {
					violations.addAll(sce.violations());
				}
				boolean compatible = schemaErrors.isEmpty() && violations.isEmpty();
				if (!compatible) {
					allOk = false;
				}
				results.add(new ConsumerCompatResult(consumerPath, manifest.graphName(), manifest.version(), compatible,
						schemaErrors, List.copyOf(violations)));
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to load candidate snapshot " + snapshotPath + ": " + e.getMessage(), e);
		}

		return new MultiConsumerCompatReport(snapshotPath, allOk, List.copyOf(results));
	}

	private static List<Map<String, Object>> formatViolationsAsMaps(List<CompatibilityViolation> violations) {
		List<Map<String, Object>> out = new ArrayList<>(violations.size());
		for (CompatibilityViolation v : violations) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("severity", v.severity().name());
			m.put("category", v.category().name());
			m.put("elementPath", v.elementPath());
			m.put("expected", v.expected());
			m.put("actual", v.actual());
			m.put("remediationHint", v.remediationHint());
			out.add(m);
		}
		return out;
	}
}
