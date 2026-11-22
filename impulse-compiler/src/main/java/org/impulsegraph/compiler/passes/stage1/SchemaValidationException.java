package org.impulsegraph.compiler.passes.stage1;

import java.util.List;
import java.util.Objects;

/**
 * Structured exception thrown by {@link SchemaBindValidatorPass} containing all
 * schema validation violations collected across an ImpScheme AST.
 */
public final class SchemaValidationException extends RuntimeException {

	private final List<String> violations;

	public SchemaValidationException(List<String> violations) {
		super(formatMessage(violations));
		this.violations = List.copyOf(Objects.requireNonNull(violations, "violations must not be null"));
	}

	public List<String> violations() {
		return violations;
	}

	private static String formatMessage(List<String> violations) {
		if (violations == null || violations.isEmpty()) {
			return "Schema validation failed";
		}
		StringBuilder sb = new StringBuilder("Schema validation failed with ").append(violations.size())
				.append(violations.size() == 1 ? " violation:" : " violations:");
		for (int i = 0; i < violations.size(); i++) {
			sb.append("\n  ").append(i + 1).append(". ").append(violations.get(i));
		}
		return sb.toString();
	}
}
