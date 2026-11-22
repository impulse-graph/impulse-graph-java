package org.impulsegraph.api.lifecycle;

import java.io.IOException;
import java.util.Optional;

/**
 * Pluggable SPI for discovering candidate {@code .imps} snapshot revisions from
 * local filesystems, object stores (GCS/S3), or artifact registries.
 */
@FunctionalInterface
public interface SnapshotResolver {

	/**
	 * Resolves the next candidate snapshot to mount for the given consumer schema
	 * context.
	 *
	 * @param context
	 *            consumer schema contract and currently active revision
	 * @return {@link Optional#empty()} if no newer revision is available
	 * @throws IOException
	 *             if the backing store cannot be queried or staged locally
	 */
	Optional<CandidateSnapshotRef> resolveNext(ConsumerSchemaContext context) throws IOException;
}
