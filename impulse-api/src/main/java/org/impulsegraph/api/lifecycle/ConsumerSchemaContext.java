package org.impulsegraph.api.lifecycle;

import org.impulsegraph.api.schema.GraphManifest;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable context passed to {@link SnapshotResolver#resolveNext} describing
 * the consumer's required schema contract and currently active snapshot
 * revision.
 */
public record ConsumerSchemaContext(String graphName, String schemaVersion, String schemaFingerprintSha256,
		GraphManifest requiredSchema, Optional<String> activeSnapshotRevision) {

	public ConsumerSchemaContext {
		Objects.requireNonNull(graphName, "graphName must not be null");
		Objects.requireNonNull(schemaVersion, "schemaVersion must not be null");
		Objects.requireNonNull(schemaFingerprintSha256, "schemaFingerprintSha256 must not be null");
		Objects.requireNonNull(requiredSchema, "requiredSchema must not be null");
		activeSnapshotRevision = activeSnapshotRevision != null ? activeSnapshotRevision : Optional.empty();
	}

	/**
	 * Convenience factory deriving {@code graphName}, {@code schemaVersion}, and
	 * {@code schemaFingerprintSha256} from the given {@link GraphManifest}.
	 */
	public static ConsumerSchemaContext of(GraphManifest requiredSchema, Optional<String> activeSnapshotRevision) {
		Objects.requireNonNull(requiredSchema, "requiredSchema must not be null");
		return new ConsumerSchemaContext(requiredSchema.graphName(), requiredSchema.version(),
				requiredSchema.schemaFingerprintSha256(), requiredSchema, activeSnapshotRevision);
	}

	/**
	 * Extracts the major version number from {@link #schemaVersion()} (e.g.,
	 * {@code "2.4.1"} or {@code "v2.0"} -> {@code 2}). Returns {@code 0} if the
	 * version string does not start with a numeric major component.
	 */
	public int majorVersion() {
		String s = schemaVersion.trim();
		if (s.startsWith("v") || s.startsWith("V")) {
			s = s.substring(1);
		}
		int dot = s.indexOf('.');
		String majorPart = dot >= 0 ? s.substring(0, dot) : s;
		try {
			return Integer.parseInt(majorPart.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
