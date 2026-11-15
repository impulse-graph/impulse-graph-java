package org.impulsegraph.storage.csr;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

import java.lang.foreign.Arena;

/**
 * Jazzer coverage-guided fuzz target for {@link BinarySnapshotLoader}. Ensures
 * malformed, truncated, or mutated {@code .imps} binary payloads fail cleanly
 * with validation exceptions rather than crashing or throwing unexpected
 * runtime errors (such as {@link NullPointerException}).
 */
class ImpsSnapshotReaderFuzzTest {

	@FuzzTest(maxDuration = "5s")
	void fuzzLoadSnapshot(FuzzedDataProvider data) {
		byte[] raw = data.consumeRemainingAsBytes();
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.loadSnapshot(raw, arena, true);
		} catch (IllegalArgumentException | IllegalStateException expected) {
			// Expected rejection of malformed .imps snapshots
		}
	}
}
