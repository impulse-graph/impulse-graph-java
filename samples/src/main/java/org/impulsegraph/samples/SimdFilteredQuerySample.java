package org.impulsegraph.samples;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * Demonstrates SIMD-accelerated attribute filtering on node and edge attributes using CEL expressions.
 */
public class SimdFilteredQuerySample {

    public static void main(String[] args) throws Exception {
        Path snapshotFile = Path.of("datasets/hetionet.imps");

        try (Arena arena = Arena.ofShared()) {
            BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotFile, arena);
            ImpulseGraphSnapshot snap = loaded.getGraph();

            DomainView compoundDomain = snap.domain("Compound");
            long sourceCompoundId = compoundDomain.toDenseId("DB00001");

            // 1. Traverse edge with SIMD-vectorized float32 filter on edge attribute
            // "potency_ic50 < 50.0" is evaluated directly off-heap with AVX-512 / ARM Neon vector instructions
            ImpulseBitSet potentInhibitors = compoundDomain.from(sourceCompoundId)
                    .out("INHIBITOR", "edge.potency_ic50 < 50.0")
                    .toBitSet();

            System.out.printf("Potent inhibitors count: %d%n", potentInhibitors.cardinality());

            // 2. Multi-hop traversal with combined edge confidence and node status filters
            DomainView geneDomain = snap.domain("Gene");
            long gene1001 = geneDomain.toDenseId("GENE_1001");
            long gene1002 = geneDomain.toDenseId("GENE_1002");

            ImpulseBitSet downstreamTargets = geneDomain.from(gene1001, gene1002)
                    .out("INTERACTS_WITH", "edge.confidence >= 0.85")
                    .out("TREATS", "edge.phase >= 3")
                    .toBitSet();

            System.out.printf("Downstream treatment candidates count: %d%n", downstreamTargets.cardinality());
        }
    }
}
