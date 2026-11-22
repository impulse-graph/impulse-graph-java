package org.impulsegraph.api.schema;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a candidate {@code .imps} snapshot fails load-time consumer
 * contract validation against a required {@link GraphManifest}.
 */
public class SnapshotCompatibilityException extends RuntimeException {

	private final List<CompatibilityViolation> violations;

	public SnapshotCompatibilityException(List<CompatibilityViolation> violations) {
		this(buildMessage(violations), violations);
	}

	public SnapshotCompatibilityException(String message, List<CompatibilityViolation> violations) {
		super(message);
		this.violations = violations != null ? List.copyOf(violations) : List.of();
	}

	public SnapshotCompatibilityException(String message, List<CompatibilityViolation> violations, Throwable cause) {
		super(message, cause);
		this.violations = violations != null ? List.copyOf(violations) : List.of();
	}

	public List<CompatibilityViolation> violations() {
		return violations;
	}

	private static String buildMessage(List<CompatibilityViolation> violations) {
		if (violations == null || violations.isEmpty()) {
			return "Snapshot failed compatibility validation";
		}
		return "Snapshot failed compatibility validation with " + violations.size() + " violation(s): "
				+ violations.stream().map(CompatibilityViolation::formatSummary).collect(Collectors.joining("; "));
	}
}
