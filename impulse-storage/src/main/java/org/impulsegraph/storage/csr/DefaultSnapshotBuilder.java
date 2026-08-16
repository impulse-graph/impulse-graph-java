package org.impulsegraph.storage.csr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
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
    private Boolean includeCscOverride = null;
    private Boolean includeCooOverride = null;

    public DefaultSnapshotBuilder() {}

    public static byte[] writeSnapshotBytes(GraphSnapshot graph) {
        return new DefaultSnapshotBuilder().build(new BinarySnapshotLoader.DefaultLoadedSnapshot(SPEC_MAGIC, (short) SPEC_VERSION_PACKED, graph, Map.of(), Map.of(), Map.of(), Map.of()));
    }

    public static class DomainEntry {
        private final int domainId;
        private final String name;
        private final byte keyType;
        private final long nodeCount;

        public DomainEntry(int domainId, String name, byte keyType, long nodeCount) {
            this.domainId = domainId;
            this.name = name;
            this.keyType = keyType;
            this.nodeCount = nodeCount;
        }

        public int domainId() { return domainId; }
        public String name() { return name; }
        public byte keyType() { return keyType; }
        public long nodeCount() { return nodeCount; }
    }

    private final List<DomainEntry> customDomains = new ArrayList<>();
    private final Map<String, int[]> customRelationDomains = new HashMap<>();

    public DefaultSnapshotBuilder withDomain(int domainId, String name, byte keyType, long nodeCount) {
        customDomains.add(new DomainEntry(domainId, name, keyType, nodeCount));
        return this;
    }

    public DefaultSnapshotBuilder withRelationDomain(String relName, int srcDomainId, int tgtDomainId) {
        customRelationDomains.put(relName, new int[]{srcDomainId, tgtDomainId});
        return this;
    }

    public DefaultSnapshotBuilder withMetadata(String key, String value) {
        metadata.put(key, value);
        return this;
    }

    public DefaultSnapshotBuilder withCsc(boolean include) {
        this.includeCscOverride = include;
        return this;
    }

    public DefaultSnapshotBuilder withCoo(boolean include) {
        this.includeCooOverride = include;
        return this;
    }

    public static MemorySegment[] computeCscSegments(Arena arena, int nodeCount, int edgeCount, MemorySegment csrRowOffsets, MemorySegment csrColumnTargets) {
        if (nodeCount <= 0 || csrRowOffsets == null || csrRowOffsets.equals(MemorySegment.NULL) || csrColumnTargets == null || csrColumnTargets.equals(MemorySegment.NULL)) {
            return new MemorySegment[] { MemorySegment.NULL, MemorySegment.NULL };
        }

        int numEdges = (int) (csrColumnTargets.byteSize() / 4);
        MemorySegment cscRowOffSeg = arena.allocate(ValueLayout.JAVA_INT, nodeCount + 1);
        MemorySegment cscColIdxSeg = arena.allocate(ValueLayout.JAVA_INT, numEdges);

        int[] inDegrees = new int[nodeCount];
        for (int i = 0; i < numEdges; i++) {
            int target = csrColumnTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            if (target >= 0 && target < nodeCount) {
                inDegrees[target]++;
            }
        }

        int accum = 0;
        for (int n = 0; n < nodeCount; n++) {
            cscRowOffSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, n, accum);
            accum += inDegrees[n];
        }
        cscRowOffSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeCount, accum);

        int[] currentOffsets = new int[nodeCount];
        for (int n = 0; n < nodeCount; n++) {
            currentOffsets[n] = cscRowOffSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, n);
        }

        for (int u = 0; u < nodeCount; u++) {
            int startIdx = csrRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
            int endIdx = csrRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
            for (int edgeIdx = startIdx; edgeIdx < endIdx; edgeIdx++) {
                int v = csrColumnTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, edgeIdx);
                if (v >= 0 && v < nodeCount) {
                    int insertPos = currentOffsets[v]++;
                    cscColIdxSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, insertPos, u);
                }
            }
        }

        return new MemorySegment[] { cscRowOffSeg, cscColIdxSeg };
    }

    public static byte[][] computeCscBytes(int nodeCount, int edgeCount, MemorySegment csrRowOffsets, MemorySegment csrColumnTargets) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment[] segs = computeCscSegments(arena, nodeCount, edgeCount, csrRowOffsets, csrColumnTargets);
            if (segs[0] == MemorySegment.NULL) {
                return new byte[][] { new byte[0], new byte[0] };
            }
            return new byte[][] { segs[0].toArray(ValueLayout.JAVA_BYTE), segs[1].toArray(ValueLayout.JAVA_BYTE) };
        }
    }

    public byte[] build(BinarySnapshotLoader.LoadedSnapshot loaded) {
        List<String> relNames = (loaded != null && loaded.graph() != null)
                ? new ArrayList<>(new java.util.TreeSet<>(loaded.graph().getAllRelationSnapshots().keySet()))
                : List.of();
        int relationCount = relNames.size();

        int dataOffset = 4096;

        // Resolve domain list
        List<DomainEntry> domainList = new ArrayList<>(this.customDomains);
        if (domainList.isEmpty() && loaded != null && loaded.domainsById() != null && !loaded.domainsById().isEmpty()) {
            for (BinarySnapshotLoader.LoadedDomain ld : loaded.domainsById().values()) {
                domainList.add(new DomainEntry(ld.domainId(), ld.name(), ld.keyType(), 0L));
            }
        }
        if (domainList.isEmpty()) {
            domainList.add(new DomainEntry(0, "User", (byte) 0x03, 0L));
        }
        int domainCount = domainList.size();

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

        for (DomainEntry dom : domainList) {
            getOrAddString.apply(dom.name());
        }

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

            // Write Domain Catalog Entries (Fixed 16 Bytes each)
            for (DomainEntry dom : domainList) {
                int domNameOff = getOrAddString.apply(dom.name());
                ByteBuffer domBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                domBuf.putShort((short) dom.domainId());
                domBuf.put(dom.keyType());
                domBuf.put((byte) 0);           // reserved
                domBuf.putInt(domNameOff);      // name_offset
                domBuf.putLong(dom.nodeCount());// node_count
                dirTableOut.write(domBuf.array());
            }

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

                RelationSnapshot relSnap = loaded != null && loaded.graph() != null ? loaded.graph().getRelationSnapshot(rName) : null;

                // Row Offsets
                align128Out(payloadOut);
                long csrRowOffOffset = relBlocksBaseOffset + payloadOut.size();
                MemorySegment rowSeg = relSnap != null ? relSnap.getRowOffsetsSegment() : MemorySegment.NULL;
                byte[] rowBytes = rowSeg != MemorySegment.NULL ? rowSeg.toArray(ValueLayout.JAVA_BYTE) : new byte[0];
                long csrRowOffBytes = rowBytes.length;
                payloadOut.write(rowBytes);

                // Column Indices
                align128Out(payloadOut);
                long csrColIdxOffset = relBlocksBaseOffset + payloadOut.size();
                MemorySegment colSeg = relSnap != null ? relSnap.getColumnTargetsSegment() : MemorySegment.NULL;
                byte[] colBytes = colSeg != MemorySegment.NULL ? colSeg.toArray(ValueLayout.JAVA_BYTE) : new byte[0];
                long csrColIdxBytes = colBytes.length;
                payloadOut.write(colBytes);

                // CSC Segments evaluation
                boolean sourceHasCsc = relSnap != null && relSnap.hasCsc();
                boolean shouldWriteCsc = includeCscOverride != null ? includeCscOverride : sourceHasCsc;

                long cscRowOffOffset = 0L;
                long cscRowOffBytes = 0L;
                long cscColIdxOffset = 0L;
                long cscColIdxBytes = 0L;

                if (shouldWriteCsc && relSnap != null) {
                    byte[] cscRowBytes;
                    byte[] cscColBytes;

                    if (relSnap.hasCsc()) {
                        cscRowBytes = relSnap.getCscRowOffsetsSegment().toArray(ValueLayout.JAVA_BYTE);
                        cscColBytes = relSnap.getCscColumnTargetsSegment().toArray(ValueLayout.JAVA_BYTE);
                    } else {
                        byte[][] computed = computeCscBytes(relSnap.getNodeCount(), (int) relSnap.getEdgeCount(), rowSeg, colSeg);
                        cscRowBytes = computed[0];
                        cscColBytes = computed[1];
                    }

                    if (cscRowBytes.length > 0) {
                        align128Out(payloadOut);
                        cscRowOffOffset = relBlocksBaseOffset + payloadOut.size();
                        cscRowOffBytes = cscRowBytes.length;
                        payloadOut.write(cscRowBytes);

                        align128Out(payloadOut);
                        cscColIdxOffset = relBlocksBaseOffset + payloadOut.size();
                        cscColIdxBytes = cscColBytes.length;
                        payloadOut.write(cscColBytes);
                    }
                }

                // COO evaluation
                boolean shouldWriteCoo = includeCooOverride != null ? includeCooOverride : false;
                byte encodingId = shouldWriteCoo ? (byte) 6 : (byte) 0;

                // Relation Entry (Fixed 128 Bytes)
                int rNameOff = relNameOffsets.get(relIdx);
                int[] doms = customRelationDomains.getOrDefault(rName, new int[]{0, 0});
                ByteBuffer relBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
                relBuf.putShort((short) relIdx);  // relation_id
                relBuf.putShort((short) doms[0]); // src_domain_id
                relBuf.putShort((short) doms[1]); // tgt_domain_id
                relBuf.put(encodingId);           // encoding_id = RAW (0) or TPU_BCOO (6)
                relBuf.put((byte) 4);             // node_id_width = 4
                relBuf.put((byte) 4);             // edge_index_width = 4
                relBuf.put(new byte[3]);          // reserved1
                relBuf.putInt(rNameOff);          // name_offset

                long nodeCount = relSnap != null ? relSnap.getNodeCount() : 0;
                long edgeCount = relSnap != null ? (int) relSnap.getEdgeCount() : 0;

                long sectionFeatures = (cscRowOffBytes > 0) ? 1L : 0L;

                relBuf.putLong(nodeCount);
                relBuf.putLong(edgeCount);
                relBuf.putLong(sectionFeatures);  // section_features (bit 0 = CSC)
                relBuf.putLong(csrRowOffOffset);
                relBuf.putLong(csrRowOffBytes);
                relBuf.putLong(csrColIdxOffset);
                relBuf.putLong(csrColIdxBytes);
                relBuf.putLong(cscRowOffOffset);
                relBuf.putLong(cscRowOffBytes);
                relBuf.putLong(cscColIdxOffset);
                relBuf.putLong(cscColIdxBytes);
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
        hdrBuf.putShort((short) domainCount);   // domain_count
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
