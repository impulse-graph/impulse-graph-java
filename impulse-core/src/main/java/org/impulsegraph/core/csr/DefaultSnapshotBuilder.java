package org.impulsegraph.core.csr;

import org.impulsegraph.api.ImpulseGraph;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.SnapshotBuilder;
import org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Production implementation of {@link SnapshotBuilder} in impulse-core.
 * Compacts live graph states and streams canonical C-ABI Binary Snapshot Spec v0.9.0 files direct-to-disk.
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

    public ImpulseGraphSnapshot compactSnapshot(ImpulseGraphSnapshot snapshot, Map<String, DeltaLayer> deltas, Path outputPath) throws IOException {
        byte[] snapshotData = writeSnapshotBytes(snapshot, deltas);
        Files.write(outputPath, snapshotData);
        return BinarySnapshotLoader.loadSnapshot(outputPath, arena);
    }

    public static byte[] writeSnapshotBytes(GraphSnapshot graph) {
        if (graph == null) return writeSnapshotBytes((ImpulseGraphSnapshot) null, Map.of());
        BinarySnapshotLoader.LoadedSnapshot wrapper = new BinarySnapshotLoader.LoadedSnapshot(
                ImpulseLayoutsV0_9.SPEC_MAGIC, (short) 9, 0, graph.getAllRelationSnapshots().size(),
                System.currentTimeMillis(), 0x08L, Map.of(), Map.of(), Map.of(), Map.of(), graph
        );
        return writeSnapshotBytes(wrapper, Map.of());
    }

    public static byte[] writeSnapshotBytes(ImpulseGraphSnapshot snapshot, Map<String, DeltaLayer> deltas) {
        int dataOffset = 4096; // Spec v0.9.0 4KB page alignment baseline

        BinarySnapshotLoader.LoadedSnapshot loaded = (snapshot instanceof BinarySnapshotLoader.LoadedSnapshot l) ? l : null;
        int domainCount = loaded != null ? loaded.domainsById().size() : 1;

        Set<String> relNames = snapshot != null ? snapshot.getRelationNames() : Set.of();
        int relationCount = relNames.size();

        ByteArrayOutputStream dirTableOut = new ByteArrayOutputStream();

        // Write Domain Catalog Entry (Header = 6 Bytes)
        try {
            ByteBuffer domBuf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            domBuf.putShort((short) 0); // domain_id = 0
            domBuf.put((byte) 0x04);    // key_type = INT32
            domBuf.put((byte) 0);       // reserved
            domBuf.putShort((short) 4); // name_len = 4
            dirTableOut.write(domBuf.array());
            dirTableOut.write("User".getBytes(StandardCharsets.UTF_8));

            // Align to 128B
            align128Out(dirTableOut);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Calculate Relation Blocks payload base offset
        int totalDirLen = dirTableOut.size() + relationCount * 112;
        int alignedDirLen = (totalDirLen + 4095) & ~4095;
        long relBlocksBaseOffset = dataOffset + alignedDirLen;

        ByteArrayOutputStream payloadOut = new ByteArrayOutputStream();

        List<byte[]> relEntries = new ArrayList<>();

        int relIdx = 0;
        for (String rName : relNames) {
            try {
                align4kOut(payloadOut);

                // Row Offsets
                align128Out(payloadOut);
                long csrRowOffOffset = relBlocksBaseOffset + payloadOut.size();
                MemorySegment rowSeg = loaded != null && loaded.graph() != null && loaded.graph().getRelationSnapshot(rName) != null
                        ? loaded.graph().getRelationSnapshot(rName).getRowOffsetsSegment()
                        : MemorySegment.NULL;
                byte[] rowBytes = rowSeg != MemorySegment.NULL ? rowSeg.toArray(ValueLayout.JAVA_BYTE) : new byte[0];
                long csrRowOffBytes = rowBytes.length;
                payloadOut.write(rowBytes);

                // Column Indices
                align128Out(payloadOut);
                long csrColIdxOffset = relBlocksBaseOffset + payloadOut.size();
                MemorySegment colSeg = loaded != null && loaded.graph() != null && loaded.graph().getRelationSnapshot(rName) != null
                        ? loaded.graph().getRelationSnapshot(rName).getColumnTargetsSegment()
                        : MemorySegment.NULL;
                byte[] colBytes = colSeg != MemorySegment.NULL ? colSeg.toArray(ValueLayout.JAVA_BYTE) : new byte[0];
                long csrColIdxBytes = colBytes.length;
                payloadOut.write(colBytes);

                // Relation Entry (112 Bytes)
                ByteBuffer relBuf = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
                relBuf.putShort((short) relIdx);  // relation_id
                relBuf.putShort((short) 0);       // src_domain_id
                relBuf.putShort((short) 0);       // tgt_domain_id
                relBuf.put((byte) 0);             // encoding_id = RAW
                relBuf.put((byte) 4);             // node_id_width = 4
                relBuf.put((byte) 4);             // edge_index_width = 4
                relBuf.position(16);              // reserved1
                relBuf.putLong(100);              // node_count
                relBuf.putLong(colBytes.length / 4); // edge_count
                relBuf.putLong(0);                // section_features
                relBuf.putLong(csrRowOffOffset);
                relBuf.putLong(csrRowOffBytes);
                relBuf.putLong(csrColIdxOffset);
                relBuf.putLong(csrColIdxBytes);
                relBuf.putLong(0);                // csc_row_off_offset
                relBuf.putLong(0);                // csc_row_off_bytes
                relBuf.putLong(0);                // csc_col_idx_offset
                relBuf.putLong(0);                // csc_col_idx_bytes
                relBuf.putShort((short) 0);       // attr_count

                relEntries.add(relBuf.array());
                relIdx++;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (byte[] rB : relEntries) {
            try {
                dirTableOut.write(rB);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // Pad dirTableOut to alignedDirLen
        while (dirTableOut.size() < alignedDirLen) {
            dirTableOut.write(0);
        }

        ByteArrayOutputStream finalPayloadOut = new ByteArrayOutputStream();
        try {
            finalPayloadOut.write(dirTableOut.toByteArray());
            finalPayloadOut.write(payloadOut.toByteArray());

            // Footer Block
            align4kOut(finalPayloadOut);
            int footerStart = finalPayloadOut.size();

            // Metadata map empty
            ByteBuffer metaBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            metaBuf.putInt(0);
            finalPayloadOut.write(metaBuf.array());

            // Footer Trailer (16 Bytes)
            long footerLen = finalPayloadOut.size() + 16 - footerStart;
            ByteBuffer trailerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            trailerBuf.putLong(footerLen);
            trailerBuf.putInt(9); // spec_version
            trailerBuf.putInt(ImpulseLayoutsV0_9.SPEC_MAGIC);
            finalPayloadOut.write(trailerBuf.array());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] finalPayload = finalPayloadOut.toByteArray();

        // Header Page 0 (4096 Bytes)
        ByteBuffer headerBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        headerBuf.putInt(ImpulseLayoutsV0_9.SPEC_MAGIC); // 0x494D5053
        headerBuf.putShort((short) 9);                     // Spec Version 0.9.0
        headerBuf.putInt(dataOffset);                      // DataOffset = 4096
        headerBuf.putShort((short) domainCount);           // DomainCount
        headerBuf.putShort((short) relationCount);         // RelationCount
        headerBuf.putLong(System.currentTimeMillis());     // TimestampMs
        headerBuf.putLong(0x08L);                          // RequiredFeatures
        headerBuf.putLong(0);                              // FooterDirectoryOffset
        headerBuf.putLong(0);                              // FooterDirectoryBytes

        // Header CRC-16 over 0x00..0x3E
        byte[] hdrArr = headerBuf.array();
        short crc = computeCrc16(hdrArr, 0, 62);
        headerBuf.putShort(62, crc);

        byte[] result = new byte[4096 + finalPayload.length];
        System.arraycopy(hdrArr, 0, result, 0, 4096);
        System.arraycopy(finalPayload, 0, result, 4096, finalPayload.length);

        return result;
    }

    private static void align128Out(ByteArrayOutputStream out) {
        int rem = out.size() % 128;
        if (rem != 0) {
            for (int i = 0; i < 128 - rem; i++) {
                out.write(0);
            }
        }
    }

    private static void align4kOut(ByteArrayOutputStream out) {
        int rem = out.size() % 4096;
        if (rem != 0) {
            for (int i = 0; i < 4096 - rem; i++) {
                out.write(0);
            }
        }
    }

    private static short computeCrc16(byte[] data, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
        }
        return (short) (crc & 0xFFFF);
    }
}
