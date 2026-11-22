package org.impulsegraph.api.lifecycle;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Reference to a candidate local {@code .imps} snapshot file discovered by a
 * {@link SnapshotResolver} for validation and transactional blue/green swap.
 */
public record CandidateSnapshotRef(String revisionId, Path localFile, boolean deleteOnRejectOrClose) {

	public CandidateSnapshotRef {
		Objects.requireNonNull(revisionId, "revisionId must not be null");
		Objects.requireNonNull(localFile, "localFile must not be null");
	}
}
