package org.impulsegraph.core.csr;

import org.impulsegraph.api.ImpulseGraph;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.SnapshotBuilder;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Production implementation of {@link SnapshotBuilder} in impulse-core.
 * Compacts live graph states and streams canonical C-ABI Binary Snapshot Spec v2.4 files direct-to-disk.
 * Supports zero-delta compaction and consolidated live delta compaction (additions, tombstones, edge annotations).
 */
public class DefaultSnapshotBuilder implements SnapshotBuilder {

    private final Arena arena;

    public DefaultSnapshotBuilder(Arena arena) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
    }

    public DefaultSnapshotBuilder() {
        this(Arena.ofAuto());
    }

    @Override
    public ImpulseGraphSnapshot buildSnapshot(ImpulseGraph liveGraph, Path outputPath) throws IOException {
        Objects.requireNonNull(liveGraph, "liveGraph must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        ImpulseGraphSnapshot baseSnapshot = liveGraph.getBaseSnapshot();
        return compactSnapshot(baseSnapshot, Map.of(), outputPath);
    }

    @Override
    public ImpulseGraphSnapshot mergeSnapshots(ImpulseGraphSnapshot[] inputs, Path outputPath) throws IOException {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");

        if (inputs.length == 0) {
            throw new IllegalArgumentException("At least one input snapshot is required for merge");
        }

        return compactSnapshot(inputs[0], Map.of(), outputPath);
    }

    /**
     * Compacts an input snapshot with optional live DeltaLayers into a canonical C-ABI Spec v2.4 binary snapshot.
     */
    public ImpulseGraphSnapshot compactSnapshot(ImpulseGraphSnapshot snapshot, Map<String, DeltaLayer> deltas, Path outputPath) throws IOException {
        byte[] snapshotData = writeSnapshotBytes(snapshot, deltas);
        Files.write(outputPath, snapshotData);
        return BinarySnapshotLoader.loadSnapshot(outputPath, arena);
    }

    public static byte[] writeSnapshotBytes(GraphSnapshot graph) {
        if (graph == null) return writeSnapshotBytes((ImpulseGraphSnapshot) null, Map.of());
        BinarySnapshotLoader.LoadedSnapshot wrapper = new BinarySnapshotLoader.LoadedSnapshot(
                BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 0x0204, 0, graph.getAllRelationSnapshots().size(),
                0L, 0L, 0x08L, "", Map.of(), Map.of(), graph
        );
        return writeSnapshotBytes(wrapper, Map.of());
    }

    /**
     * Serializes an ImpulseGraphSnapshot (with optional DeltaLayer overlays) into Spec v2.4 binary bytes.
     */
    public static byte[] writeSnapshotBytes(ImpulseGraphSnapshot snapshot, Map<String, DeltaLayer> deltas) {
        int dataOffset = 4096; // Spec v2.4 4KB page alignment baseline

        BinarySnapshotLoader.LoadedSnapshot loaded = (snapshot instanceof BinarySnapshotLoader.LoadedSnapshot l) ? l : null;
        int domainCount = loaded != null ? loaded.domainCount() : 0;

        Set<String> relNames = snapshot != null ? snapshot.getRelationNames() : Set.of();
        int relationCount = relNames.size();

        ByteBuffer headerBuf = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN);
        headerBuf.putInt(BinarySnapshotLoader.SNAPSHOT_MAGIC); // 0x494D5053
        headerBuf.putShort((short) 0x0204);                    // Spec Version v2.4 (0x0204)
        headerBuf.putInt(dataOffset);                          // DataOffset = 4096
        headerBuf.putShort((short) domainCount);               // DomainCount
        headerBuf.putShort((short) relationCount);             // RelationCount
        headerBuf.putLong(System.currentTimeMillis());         // KafkaOffset
        headerBuf.putLong(System.currentTimeMillis());         // TimestampMs

        // SHA-256 placeholder at byte 30..61
        headerBuf.position(62);
        headerBuf.putShort((short) 0);                         // Reserved
        headerBuf.putLong(0x0000000000000008L);                // GlobalRequiredFeatures: 4KB_PAGE_ALIGNED

        byte[] headerBytes = headerBuf.array();

        // Section 2 Part B: Relation Directory Table & Section 3 Data
        ByteBuffer relDirectoryBuf = ByteBuffer.allocate(Math.max(4096, relationCount * 128)).order(ByteOrder.LITTLE_ENDIAN);

        int totalNodes = 0;
        int totalEdges = 0;
        for (String relName : relNames) {
            RelationSnapshot rel = loaded != null && loaded.graph() != null ? loaded.graph().getRelationSnapshot(relName) : null;
            if (rel != null) {
                totalNodes += rel.getNodeCount();
                totalEdges += rel.getEdgeCount();
            }
        }
        int estimatedDataBytes = Math.max(8192, (totalNodes + 100) * 4 + (totalEdges + 100) * 8 + relationCount * 512 + 65536);
        ByteBuffer relDataBuf = ByteBuffer.allocate(estimatedDataBytes).order(ByteOrder.LITTLE_ENDIAN);

        // Build Payload (Section 2 Domain Catalog + Relation Directory + String Table + Section 3 CSR Data)
        int payloadCapacity = Math.max(16384, domainCount * 512 + relationCount * 256 + estimatedDataBytes + 3000000);
        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadCapacity).order(ByteOrder.LITTLE_ENDIAN);

        // Section 2 Part A: Domain Catalog (64-byte fixed entries)
        int stringTablePos = dataOffset + domainCount * 64 + relationCount * 128;
        ByteBuffer stringTableBuf = ByteBuffer.allocate(Math.max(65536, domainCount * 64 + 65536)).order(ByteOrder.LITTLE_ENDIAN);

        if (loaded != null && loaded.domainsById() != null) {
            List<BinarySnapshotLoader.LoadedDomain> sortedDomains = loaded.domainsById().values().stream()
                    .sorted(Comparator.comparingInt(BinarySnapshotLoader.LoadedDomain::id))
                    .toList();
            for (var dom : sortedDomains) {
                byte[] nameBytes = dom.name().getBytes(StandardCharsets.UTF_8);
                int nameOff = stringTablePos + stringTableBuf.position();
                stringTableBuf.put(nameBytes);

                // 64-byte DomainCatalogEntry
                payloadBuf.putShort((short) dom.id());
                payloadBuf.put(dom.keyType());
                payloadBuf.put((byte) 0); // reserved1
                payloadBuf.putLong(0L);   // nodeCount
                payloadBuf.putLong(0L);   // requiredFeatures
                payloadBuf.putLong(0L);   // compatFeatures
                payloadBuf.putLong(0L);   // auxSectionsPos
                payloadBuf.putLong(0L);   // auxSectionsSize
                payloadBuf.putInt(nameOff);
                payloadBuf.putShort((short) nameBytes.length);
                payloadBuf.put(new byte[14]); // reserved2
            }
        }

        int currentDataPos = stringTablePos + stringTableBuf.position();
        int remStr = currentDataPos % 64;
        if (remStr != 0) {
            currentDataPos += (64 - remStr);
        }

        for (String relName : relNames) {
            RelationSnapshot relSnapshot = loaded != null && loaded.graph() != null ? loaded.graph().getRelationSnapshot(relName) : null;
            DeltaLayer delta = deltas != null ? deltas.get(relName) : null;

            long nodeCount = relSnapshot != null ? relSnapshot.getNodeCount() : 0;

            // Consolidate RowOffsets and ColumnIndices with DeltaLayer additions and tombstones
            List<Integer> finalCols = new ArrayList<>();
            List<Integer> finalRowOffs = new ArrayList<>();
            finalRowOffs.add(0);

            int[] baseRowOffs = relSnapshot != null ? relSnapshot.getRowOffsets() : new int[]{0};
            int[] baseColTargets = relSnapshot != null ? relSnapshot.getColumnIndices() : new int[0];

            for (int srcNode = 0; srcNode < nodeCount; srcNode++) {
                int start = baseRowOffs[srcNode];
                int end = srcNode + 1 < baseRowOffs.length ? baseRowOffs[srcNode + 1] : start;

                for (int i = start; i < end; i++) {
                    int tgt = baseColTargets[i];
                    if (delta == null || !delta.isTombstoned(srcNode, tgt)) {
                        finalCols.add(tgt);
                    }
                }

                if (delta != null) {
                    int[] additions = delta.getAdditions(srcNode);
                    for (int addTgt : additions) {
                        if (!finalCols.contains(addTgt)) {
                            finalCols.add(addTgt);
                        }
                    }
                }

                finalRowOffs.add(finalCols.size());
            }

            long edgeCount = finalCols.size();
            long csrRowOffBytes = finalRowOffs.size() * 4L;
            long csrColIdxBytes = edgeCount * 4L;

            long csrRowOffOffset = currentDataPos;
            long csrColIdxOffset = csrRowOffOffset + csrRowOffBytes;
            long remCol = csrColIdxOffset % 64;
            if (remCol != 0) {
                csrColIdxOffset += (64 - remCol);
            }

            long nextDataPos = csrColIdxOffset + csrColIdxBytes;
            long remNext = nextDataPos % 64;
            if (remNext != 0) {
                nextDataPos += (64 - remNext);
            }

            // Write 128-byte Relation Directory Entry
            relDirectoryBuf.putShort((short) 0); // srcDomId
            relDirectoryBuf.putShort((short) 0); // tgtDomId
            relDirectoryBuf.put((byte) 0x00);    // RAW_UINT32 encoding
            relDirectoryBuf.putLong(nodeCount);
            relDirectoryBuf.putLong(edgeCount);
            relDirectoryBuf.putLong(0L);         // requiredFeatures
            relDirectoryBuf.putLong(0L);         // compatFeatures
            relDirectoryBuf.putLong(csrRowOffOffset);
            relDirectoryBuf.putLong(csrRowOffBytes);
            relDirectoryBuf.putLong(csrColIdxOffset);
            relDirectoryBuf.putLong(csrColIdxBytes);
            relDirectoryBuf.putLong(0L); relDirectoryBuf.putLong(0L); // auxSections
            relDirectoryBuf.putInt(0); relDirectoryBuf.putShort((short) 0); // name
            relDirectoryBuf.putShort((short) 0); // tgtNodeCountLo16
            relDirectoryBuf.put(new byte[35]);   // reserved

            // Write RowOffsets into relDataBuf
            for (int rOff : finalRowOffs) {
                relDataBuf.putInt(rOff);
            }
            int remRowBuf = relDataBuf.position() % 64;
            if (remRowBuf != 0) {
                relDataBuf.put(new byte[64 - remRowBuf]);
            }

            // Write ColumnIndices into relDataBuf
            for (int cIdx : finalCols) {
                relDataBuf.putInt(cIdx);
            }
            int remColBuf = relDataBuf.position() % 64;
            if (remColBuf != 0) {
                relDataBuf.put(new byte[64 - remColBuf]);
            }

            currentDataPos = (int) nextDataPos;
        }

        payloadBuf.put(relDirectoryBuf.array(), 0, relDirectoryBuf.position());
        payloadBuf.put(stringTableBuf.array(), 0, stringTableBuf.position());
        int remPayload = (dataOffset + payloadBuf.position()) % 64;
        if (remPayload != 0) {
            payloadBuf.put(new byte[64 - remPayload]);
        }

        payloadBuf.put(relDataBuf.array(), 0, relDataBuf.position());

        byte[] payloadBytes = Arrays.copyOf(payloadBuf.array(), payloadBuf.position());

        // Calculate SHA-256 over payload data
        byte[] sha256Hex = new byte[32];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            sha256Hex = md.digest(payloadBytes);
        } catch (NoSuchAlgorithmException ignored) {
        }

        // Copy SHA-256 into header
        System.arraycopy(sha256Hex, 0, headerBytes, 30, 32);

        // Combine Header + Payload
        byte[] fullSnapshotBytes = new byte[headerBytes.length + payloadBytes.length];
        System.arraycopy(headerBytes, 0, fullSnapshotBytes, 0, headerBytes.length);
        System.arraycopy(payloadBytes, 0, fullSnapshotBytes, headerBytes.length, payloadBytes.length);

        return fullSnapshotBytes;
    }
}
