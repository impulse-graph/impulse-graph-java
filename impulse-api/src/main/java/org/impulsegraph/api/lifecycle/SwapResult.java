package org.impulsegraph.api.lifecycle;

import org.impulsegraph.api.schema.CompatibilityViolation;

import java.util.List;
import java.util.Objects;

/**
 * Result of a transactional blue/green snapshot swap attempt.
 *
 * @param <S>
 *            typed snapshot facade exposed to readers
 */
public sealed interface SwapResult<S> permits SwapResult.Swapped, SwapResult.Unchanged, SwapResult.Rejected {

	record Swapped<S>(String revisionId, S snapshot) implements SwapResult<S> {
		public Swapped {
			Objects.requireNonNull(revisionId, "revisionId must not be null");
			Objects.requireNonNull(snapshot, "snapshot must not be null");
		}
	}

	record Unchanged<S>(String activeRevisionId) implements SwapResult<S> {
	}

	record Rejected<S>(String candidateRevisionId, List<CompatibilityViolation> violations,
			String message) implements SwapResult<S> {
		public Rejected {
			violations = violations != null ? List.copyOf(violations) : List.of();
			message = message != null ? message : "Candidate snapshot rejected";
		}
	}
}
