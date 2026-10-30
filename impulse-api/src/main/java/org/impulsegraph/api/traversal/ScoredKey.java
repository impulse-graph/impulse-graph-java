package org.impulsegraph.api.traversal;

import java.util.Objects;

/**
 * External business key paired with a similarity/rerank score and multi-lane
 * quorum count.
 */
public record ScoredKey(String key, float score, int quorum) {
	public ScoredKey {
		Objects.requireNonNull(key, "key must not be null");
	}

	public ScoredKey(String key, float score) {
		this(key, score, 1);
	}
}
