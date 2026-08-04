package org.impulsegraph.core.csr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;
import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_VERSION_PACKED;

public final class DefaultSnapshotBuilder {

    private final Map<String, String> metadata = new HashMap<>();

    public DefaultSnapshotBuilder() {}

    public static byte[] writeSnapshotBytes(GraphSnapshot graph) {
        return new DefaultSnapshotBuilder().build(new BinarySnapshotLoader.DefaultLoadedSnapshot(graph, Map.of(), Map.of(), Map.of(), Map.of()));
    }

    public DefaultSnapshotBuilder withMetadata(String key, String value) {
        metadata.put(key, value);
        return this;
    }

    public byte[] build(BinarySnapshotLoader.LoadedSnapshot loaded) {
        Set<String> relNames = loaded != null ? loaded.getRelationNames() : Set.of();
        int relationCount = relNames.size();

        int dataOffset = 4096;

        // Build Shared String Table
        ByteArrayOutputStream stringPoolOut = new ByteArrayOutputStream();
        stringPoolOut.write(0); // Offset 0 = empty string ""
        Map<String, Integer> stringMap = new HashMap<>();
        stringMap.put("", 0);

        java.util.function.Function<String, Integer> getOrAddString = (str) -> {
            if (str == null || str.isEmpty()) return 0;
            if (stringMap.containsKey(str)) return stringMap.get(str);
            int off = stringPoolOut.size();
            byte[] sb = str.getBytes(StandardCharsets.UTF_8);
            stringPoolOut.write(sb, 0, sb.length);
            stringPoolOut.write(0); // null terminator
            stringMap.put(str, off);
            return off;
        };

        int userDomNameOff = getOrAddString.apply("User");
        List<Integer> relNameOffsets = new ArrayList<>();
        for (String rName : relNames) {
            relNameOffsets.add(getOrAddString.apply(rName));
        }

        ByteArrayOutputStream dirTableOut = new ByteArrayOutputStream();

        // Write String Table Header & Pool Blob
        try {
            int poolBytes = stringPoolOut.size();
            ByteBuffer strHdrBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            strHdrBuf.putInt(poolBytes);
            dirTableOut.write(strHdrBuf.array());
            dirTableOut.write(stringPoolOut.toByteArray());

            align128Out(dirTableOut);

            // Write Domain Catalog Entry (Fixed 16 Bytes)
            ByteBuffer domBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            domBuf.putShort((short) 0);      // domain_id = 0
            domBuf.put((byte) 0x03);         // key_type = INT32
            domBuf.put((byte) 0);            // reserved
            domBuf.putInt(userDomNameOff);   // name_offset
            domBuf.putLong(0L);              // node_count = 0
            dirTableOut.write(domBuf.array());

            align128Out(dirTableOut);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Calculate Relation Blocks payload base offset
        int totalDirLen = dirTableOut.size() + relationCount * 128;
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

                // Relation Entry (Fixed 128 Bytes)
                int rNameOff = relNameOffsets.get(relIdx);
                ByteBuffer relBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
                relBuf.putShort((short) relIdx);  // relation_id
                relBuf.putShort((short) 0);       // src_domain_id
                relBuf.putShort((short) 0);       // tgt_domain_id
                relBuf.put((byte) 0);             // encoding_id = RAW
                relBuf.put((byte) 4);             // node_id_width = 4
                relBuf.put((byte) 4);             // edge_index_width = 4
                relBuf.put(new byte[3]);          // reserved1
                relBuf.putInt(rNameOff);          // name_offset

                long nodeCount = loaded != null && loaded.graph() != null && loaded.graph().getRelationSnapshot(rName) != null
                        ? loaded.graph().getRelationSnapshot(rName).getNodeCount()
                        : 0;
                long edgeCount = loaded != null && loaded.graph() != null && loaded.graph().getRelationSnapshot(rName) != null
                        ? loaded.graph().getRelationSnapshot(rName).getEdgeCount()
                        : 0;

                relBuf.putLong(nodeCount);
                relBuf.putLong(edgeCount);
                relBuf.putLong(0L);               // section_features
                relBuf.putLong(csrRowOffOffset);
                relBuf.putLong(csrRowOffBytes);
                relBuf.putLong(csrColIdxOffset);
                relBuf.putLong(csrColIdxBytes);
                relBuf.putLong(0L);               // csc_row_off_offset
                relBuf.putLong(0L);               // csc_row_off_bytes
                relBuf.putLong(0L);               // csc_col_idx_offset
                relBuf.putLong(0L);               // csc_col_idx_bytes
                relBuf.putShort((short) 0);       // attr_count
                relBuf.put(new byte[22]);         // reserved2

                relEntries.add(relBuf.array());
                relIdx++;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (byte[] entryBytes : relEntries) {
            dirTableOut.write(entryBytes, 0, entryBytes.length);
        }

        try {
            align4kOut(dirTableOut);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ByteArrayOutputStream finalOut = new ByteArrayOutputStream();

        // 1. Write Header Page 0 (4096 Bytes)
        ByteBuffer hdrBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        hdrBuf.putInt(SPEC_MAGIC);              // magic
        hdrBuf.putShort((short) SPEC_VERSION_PACKED); // version = 9
        hdrBuf.putInt(dataOffset);              // data_offset = 4096
        hdrBuf.putShort((short) 1);             // domain_count = 1
        hdrBuf.putShort((short) relationCount); // relation_count
        hdrBuf.putLong(1700000000000L);         // timestamp_ms
        hdrBuf.putLong(1L);                     // required_features (4KB_PAGE_ALIGNED)
        hdrBuf.putLong(0L);                     // footer_directory_offset
        hdrBuf.putLong(0L);                     // footer_directory_bytes
        hdrBuf.put(new byte[16]);               // snapshot_uuid

        // CRC-16 over bytes 0..61
        byte[] hdrBytes = hdrBuf.array();
        short crc = (short) computeCrc16(hdrBytes, 0, 0x3E);
        hdrBuf.putShort(0x3E, crc);

        try {
            finalOut.write(hdrBuf.array());
            finalOut.write(dirTableOut.toByteArray());
            finalOut.write(payloadOut.toByteArray());

            // Write Footer Block
            align4kOut(finalOut);
            int footerStart = finalOut.size();

            ByteBuffer metaBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            metaBuf.putInt(metadata.size());
            finalOut.write(metaBuf.array());

            for (Map.Entry<String, String> kv : metadata.entrySet()) {
                byte[] kb = kv.getKey().getBytes(StandardCharsets.UTF_8);
                ByteBuffer kBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
                kBuf.putShort((short) kb.length);
                finalOut.write(kBuf.array());
                finalOut.write(kb);

                byte[] vb = kv.getValue().getBytes(StandardCharsets.UTF_8);
                ByteBuffer vBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                vBuf.putInt(vb.length);
                finalOut.write(vBuf.array());
                finalOut.write(vb);
            }

            long footerLen = finalOut.size() + 16 - footerStart;
            ByteBuffer trailerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            trailerBuf.putLong(footerLen);
            trailerBuf.putInt(SPEC_VERSION_PACKED);
            trailerBuf.putInt(SPEC_MAGIC);
            finalOut.write(trailerBuf.array());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return finalOut.toByteArray();
    }

    private static void align128Out(ByteArrayOutputStream out) throws IOException {
        int rem = out.size() % 128;
        if (rem != 0) {
            out.write(new byte[128 - rem]);
        }
    }

    private static void align4kOut(ByteArrayOutputStream out) throws IOException {
        int rem = out.size() % 4096;
        if (rem != 0) {
            out.write(new byte[4096 - rem]);
        }
    }

    private static int computeCrc16(byte[] data, int off, int len) {
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
        return crc & 0xFFFF;
    }
}
