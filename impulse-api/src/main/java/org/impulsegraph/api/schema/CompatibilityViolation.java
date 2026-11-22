package org.impulsegraph.api.schema;

import java.util.Objects;

/**
 * Structured diagnostic describing a schema compatibility mismatch between a
 * consumer contract ({@link GraphManifest}) and a mounted binary snapshot.
 */
public record CompatibilityViolation(Severity severity, Category category, String elementPath, String expected,
		String actual, String remediationHint) {

	public enum Severity {
		ERROR, WARNING
	}

	public enum Category {
		MISSING_DOMAIN, MISSING_RELATION, MISSING_ATTRIBUTE, TYPE_MISMATCH, DIMENSION_MISMATCH, NULLABILITY_MISMATCH, MISSING_DIRECTION, ENDPOINT_MISMATCH, SNAPSHOT_LOAD_ERROR, QUERY_BIND_ERROR
	}

	public CompatibilityViolation {
		Objects.requireNonNull(severity, "severity must not be null");
		Objects.requireNonNull(category, "category must not be null");
		Objects.requireNonNull(elementPath, "elementPath must not be null");
	}

	public String formatSummary() {
		StringBuilder sb = new StringBuilder();
		sb.append('[').append(severity).append('/').append(category).append("] ").append(elementPath);
		if (expected != null || actual != null) {
			sb.append(" (expected=").append(expected).append(", actual=").append(actual).append(')');
		}
		if (remediationHint != null && !remediationHint.isBlank()) {
			sb.append(" -> ").append(remediationHint);
		}
		return sb.toString();
	}
}
