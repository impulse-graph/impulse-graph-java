package org.impulsegraph.api.traversal;

import java.util.Objects;

/**
 * Pair of external business keys (e.g., 2-node set cover key pair).
 */
public record KeyPair(String firstKey, String secondKey) {
	public KeyPair {
		Objects.requireNonNull(firstKey, "firstKey must not be null");
		Objects.requireNonNull(secondKey, "secondKey must not be null");
	}
}
