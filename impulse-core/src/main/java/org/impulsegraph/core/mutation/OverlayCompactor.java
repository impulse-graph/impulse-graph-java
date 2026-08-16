package org.impulsegraph.core.mutation;

import org.impulsegraph.core.csr.BinarySnapshotLoader;
import org.impulsegraph.core.csr.DefaultSnapshotBuilder;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;
import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_VERSION_PACKED;

/**
 * Background compaction engine that merges an immutable base {@link GraphSnapshot}
 * with live {@link OverlayMutator} delta layers and tombstone bitsets.
 * <p>
 * Purges deleted nodes and tombstoned edges, includes newly added nodes and edges,
 * reconstructs contiguous CSR/CSC layouts, streams the binary {@code .imps} file directly to disk,
 * and loads the newly compacted snapshot zero-copy.
 */
public class OverlayCompactor {

    private final OverlayMutator mutator;
    private final GraphSnapshot baseSnapshot;

    /**
     * Creates an OverlayCompactor for the given OverlayMutator.
     *
     * @param mutator the active overlay mutator
     */
    public OverlayCompactor(OverlayMutator mutator) {
        this.mutator = Objects.requireNonNull(mutator, "mutator must not be null");
        this.baseSnapshot = mutator.getBaseSnapshot();
    }

    /**
     * Creates an OverlayCompactor with explicit base snapshot and mutator.
     */
    public OverlayCompactor(GraphSnapshot baseSnapshot, OverlayMutator mutator) {
        this.mutator = Objects.requireNonNull(mutator, "mutator must not be null");
        this.baseSnapshot = baseSnapshot != null ? baseSnapshot : mutator.getBaseSnapshot();
    }

    /**
     * Compacts the base snapshot and all overlay mutations directly to a fresh binary {@code .imps}
     * file on disk and returns the memory-mapped compacted {@link GraphSnapshot}.
     *
     * @param newSnapshotFile target destination file path for the .imps snapshot
     * @return newly memory-mapped GraphSnapshot
     * @throws IOException if disk write or memory mapping fails
     */
    public GraphSnapshot compactToDisk(Path newSnapshotFile) throws IOException {
        Objects.requireNonNull(newSnapshotFile, "newSnapshotFile must not be null");

        // Ensure all pending batch mutations are committed before compaction
        mutator.commitBatch();

        NodeIdentityOverlay identity = mutator.getNodeIdentityOverlay();
        DeletedNodeBitSet deletedNodes = mutator.getDeletedNodes();
        BinarySnapshotLoader.LoadedSnapshot loaded = mutator.getLoadedSnapshot();

        org.impulsegraph.core.stats.StreamingStatsCollector statsCollector = new org.impulsegraph.core.stats.StreamingStatsCollector();

        // 1. Build Domain List
        DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder();
        List<DefaultSnapshotBuilder.DomainEntry> domains = new ArrayList<>();

        if (loaded != null && loaded.domainsById() != null && !loaded.domainsById().isEmpty()) {
            for (BinarySnapshotLoader.LoadedDomain ld : loaded.domainsById().values()) {
                int domId = ld.domainId();
                long totalCount = identity.getNodeCount(domId);
                builder.withDomain(domId, ld.name(), ld.keyType(), totalCount);
            }
        } else {
            long totalCount = identity.getNodeCount(0);
            builder.withDomain(0, "User", (byte) 0x03, totalCount);
        }

        // 2. Identify all relations to compact
        Set<String> relationNames = new LinkedHashSet<>();
        if (baseSnapshot != null) {
            relationNames.addAll(baseSnapshot.getAllRelationSnapshots().keySet());
        }
        for (int relId : mutator.getCommittedEdgeAdditions().keySet()) {
            relationNames.add("rel_" + relId + "_0To0");
        }
        if (relationNames.isEmpty()) {
            relationNames.add("rel_0_0To0");
        }

        // 3. Compact CSR structures for each relation in an off-heap arena
        Arena buildArena = Arena.ofConfined();
        Map<String, RelationSnapshot> compactedRelations = new LinkedHashMap<>();

        try {
            int relIdx = 0;
            for (String rName : relationNames) {
                RelationSnapshot baseRel = baseSnapshot != null ? baseSnapshot.getRelationSnapshot(rName) : null;
                OffHeapTombstoneBitSet tombBitSet = mutator.getEdgeTombstoneBitSet(relIdx);

                int srcDomId = 0;
                int tgtDomId = 0;
                if (loaded != null && loaded.relationsById() != null && loaded.relationsById().containsKey(relIdx)) {
                    BinarySnapshotLoader.LoadedRelation lr = loaded.relationsById().get(relIdx);
                    srcDomId = lr.srcDomainId();
                    tgtDomId = lr.tgtDomainId();
                }
                builder.withRelationDomain(rName, srcDomId, tgtDomId);

                int nodeCount = Math.max(
                        identity.getNodeCount(srcDomId),
                        baseRel != null ? baseRel.getNodeCount() : 0
                );

                List<Integer> rowOffsetsList = new ArrayList<>(nodeCount + 1);
                List<Integer> columnTargetsList = new ArrayList<>();
                int currentEdgeAccum = 0;
                rowOffsetsList.add(currentEdgeAccum);

                var committedAdditions = mutator.getCommittedEdgeAdditions().get(relIdx);

                for (int u = 0; u < nodeCount; u++) {
                    if (deletedNodes.isDeleted(srcDomId, u)) {
                        rowOffsetsList.add(currentEdgeAccum);
                        continue;
                    }

                    Set<Integer> targetSet = new LinkedHashSet<>();

                    // Base edges
                    if (baseRel != null && baseRel.hasCsr() && u < baseRel.getNodeCount()) {
                        int baseStart = baseRel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                        int baseEnd = baseRel.getRowOffsetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);

                        for (int e = baseStart; e < baseEnd; e++) {
                            if (tombBitSet != null && tombBitSet.get(e)) {
                                continue;
                            }
                            int v = baseRel.getColumnTargetsSegment().getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
                            if (deletedNodes.isDeleted(tgtDomId, v)) {
                                continue;
                            }
                            if (mutator.isEdgeDeleted(relIdx, u, v)) {
                                continue;
                            }
                            targetSet.add(v);
                        }
                    }

                    // Overlay additions
                    if (committedAdditions != null) {
                        int[] additions = committedAdditions.getForwardEdges(u);
                        for (int v : additions) {
                            if (!deletedNodes.isDeleted(tgtDomId, v) && !mutator.isEdgeDeleted(relIdx, u, v)) {
                                targetSet.add(v);
                            }
                        }
                    }

                    for (int tgt : targetSet) {
                        columnTargetsList.add(tgt);
                        currentEdgeAccum++;
                    }
                    rowOffsetsList.add(currentEdgeAccum);
                    
                    if (statsCollector != null) {
                        statsCollector.observeOutDegree(relIdx, targetSet.size());
                    }
                }

                int edgeCount = columnTargetsList.size();
                int[] rowOffsetsArr = new int[rowOffsetsList.size()];
                for (int i = 0; i < rowOffsetsList.size(); i++) {
                    rowOffsetsArr[i] = rowOffsetsList.get(i);
                }
                int[] columnTargetsArr = new int[edgeCount];
                for (int j = 0; j < edgeCount; j++) {
                    columnTargetsArr[j] = columnTargetsList.get(j);
                }

                RelationSnapshot compactedRel = new RelationSnapshot(
                        buildArena, nodeCount, edgeCount, rowOffsetsArr, columnTargetsArr
                );
                compactedRelations.put(rName, compactedRel);
                relIdx++;
            }

            GraphSnapshot inMemoryCompactGraph = new GraphSnapshot(buildArena, compactedRelations);

            // 4. Generate .imps binary bytes
            BinarySnapshotLoader.LoadedSnapshot loadedToBuild = new BinarySnapshotLoader.DefaultLoadedSnapshot(
                    SPEC_MAGIC,
                    (short) SPEC_VERSION_PACKED,
                    inMemoryCompactGraph,
                    loaded != null ? loaded.domainsById() : Map.of(),
                    loaded != null ? loaded.domainsByName() : Map.of(),
                    loaded != null ? loaded.relationsById() : Map.of(),
                    loaded != null ? loaded.getMetadataMap() : Map.of()
            );

            // Serialize stats to unified UTF-8 metadata stream
            for (Map.Entry<String, String> entry : statsCollector.toJsonMap().entrySet()) {
                builder.withMetadata(entry.getKey(), entry.getValue());
            }
            builder.withMetadata("impulse.graph.mutable", "true");

            byte[] snapshotBytes = builder.build(loadedToBuild);

            // 5. Write binary bytes to disk
            if (newSnapshotFile.getParent() != null) {
                Files.createDirectories(newSnapshotFile.getParent());
            }
            Files.write(newSnapshotFile, snapshotBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

        } finally {
            buildArena.close();
        }

        // 6. Memory-map the newly written snapshot from disk
        BinarySnapshotLoader.LoadedSnapshot freshlyLoaded = BinarySnapshotLoader.loadSnapshot(
                newSnapshotFile, Arena.ofShared()
        );

        return freshlyLoaded.graph();
    }
}
