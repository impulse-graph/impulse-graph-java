package org.impulsegraph.core.csr;

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
 * High-performance binary snapshot loader conforming to Impulse-Graph C-ABI Binary Snapshot Spec v2.4 (and v2.3 / v1).
 * Supports SIMDComp / PFOR-Delta (0x04), Delta-VByte (0x01), UINT16 (0x02), HYBRID (0x03), RAW_UINT64 (0x07), and RAW_UINT32 (0x00).
 */
public class BinarySnapshotLoader {

    public static final int SNAPSHOT_MAGIC = 0x494D5053; // "IMPS"
    public static final long SUPPORTED_GLOBAL_FEATURES = 0x00000000000000FFL;

    public record LoadedDomain(int id, String name, byte keyType, Map<String, Integer> bkToDenseMap) {}

    public record LoadedSnapshot(
            int magic,
            short version,
            int domainCount,
            int relationCount,
            long kafkaOffset,
            long timestampMs,
            long globalFeatures,
            String sha256Hex,
            Map<Integer, LoadedDomain> domainsById,
            Map<String, LoadedDomain> domainsByName,
            GraphSnapshot graph
    ) implements org.impulsegraph.api.ImpulseGraphSnapshot {

        @Override
        public int getRelationCount() {
            return relationCount;
        }

        @Override
        public Set<String> getRelationNames() {
            return graph != null ? graph.getAllRelationSnapshots().keySet() : Set.of();
        }

        @Override
        public long getNodeCount(String domainName) {
            LoadedDomain dom = domainsByName != null ? domainsByName.get(domainName) : null;
            return dom != null ? dom.bkToDenseMap().size() : 0;
        }

        @Override
        public long getEdgeCount(String relationName) {
            if (graph == null) return 0;
            RelationSnapshot rel = graph.getRelationSnapshot(relationName);
            return rel != null ? rel.getEdgeCount() : 0;
        }

        @Override
        public java.lang.foreign.MemorySegment getRelationTargetsSegment(String relationName) {
            if (graph == null) return null;
            RelationSnapshot rel = graph.getRelationSnapshot(relationName);
            return rel != null ? rel.getColumnTargetsSegment() : null;
        }

        @Override
        public long getOffHeapMemorySizeBytes() {
            return graph != null ? graph.getOffHeapMemorySizeBytes() : 0;
        }

        @Override
        public String getSha256Checksum() {
            return sha256Hex;
        }

        @Override
        public String getMetadata(String key) {
            return null;
        }

        @Override
        public Map<String, String> getMetadataMap() {
            return Map.of();
        }

        @Override
        public void close() {
            if (graph != null) {
                graph.close();
            }
        }
    }

    public static LoadedSnapshot loadSnapshot(Path filePath, Arena arena) throws IOException {
        byte[] bytes = Files.readAllBytes(filePath);
        return loadSnapshot(bytes, arena, false);
    }

    public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena) {
        return loadSnapshot(data, arena, false);
    }

    public static LoadedSnapshot loadSnapshot(byte[] data, Arena arena, boolean verifyChecksum) {
        Objects.requireNonNull(data, "snapshot data must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        if (data.length < 58) {
            throw new IllegalArgumentException("snapshot file size (" + data.length + " bytes) is smaller than 58-byte header");
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt();
        if (magic != SNAPSHOT_MAGIC) {
            throw new IllegalArgumentException(String.format("invalid snapshot magic 0x%X (expected 0x%X)", magic, SNAPSHOT_MAGIC));
        }

        short version = buf.getShort();
        int ver = Short.toUnsignedInt(version);
        if (ver != 1 && ver != 2 && ver != 0x0204) {
            throw new IllegalArgumentException("Unsupported protocol version number: " + ver);
        }

        int dataOffset = 58;
        int domainCount = 0;
        int relationCount = 0;

        if (version >= 2) {
            dataOffset = buf.getInt();
            domainCount = Short.toUnsignedInt(buf.getShort());
            relationCount = Short.toUnsignedInt(buf.getShort());
        } else {
            domainCount = Short.toUnsignedInt(buf.getShort());
            relationCount = Short.toUnsignedInt(buf.getShort());
        }

        long kafkaOffset = buf.getLong();
        long timestampMs = buf.getLong();

        byte[] expectedSha256 = new byte[32];
        buf.get(expectedSha256);

        long globalFeatures = 0;
        if (version >= 2 && data.length >= 72) {
            buf.position(64);
            globalFeatures = buf.getLong();
            if ((globalFeatures & ~SUPPORTED_GLOBAL_FEATURES) != 0) {
                throw new UnsupportedOperationException(String.format("Unsupported global feature bitmask 0x%X", globalFeatures));
            }
        }

        // Verify SHA256 payload checksum if enabled
        if (verifyChecksum) {
            byte[] payload = Arrays.copyOfRange(data, dataOffset, data.length);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] actualSha256 = md.digest(payload);
                if (!Arrays.equals(expectedSha256, actualSha256)) {
                    throw new IllegalStateException("SHA256 checksum mismatch on snapshot binary load!");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("SHA-256 algorithm missing", e);
            }
        }

        String sha256Hex = bytesToHex(expectedSha256);
        buf.position(dataOffset);

        // Parse Domain Section
        Map<Integer, LoadedDomain> domainsById = new HashMap<>();
        Map<String, LoadedDomain> domainsByName = new HashMap<>();

        for (int i = 0; i < domainCount; i++) {
            int domId = 0;
            byte keyType = 0;
            String domName = "";

            if (ver == 0x0204) {
                int entryPos = dataOffset + i * 64;
                buf.position(entryPos);
                domId = Short.toUnsignedInt(buf.getShort());
                keyType = buf.get();
                buf.get(); // reserved1
                long domNodeCount = buf.getLong();
                buf.getLong(); // requiredFeatures
                buf.getLong(); // compatFeatures
                buf.getLong(); // auxSectionsPos
                buf.getLong(); // auxSectionsSize
                int nameOff = buf.getInt();
                int nameLen = Short.toUnsignedInt(buf.getShort());
                
                if (nameLen > 0 && nameOff > 0) {
                    if (nameOff + nameLen > data.length) {
                        throw new IllegalArgumentException("Domain NameLen " + nameLen + " exceeds remaining file buffer size");
                    }
                    byte[] nameBytes = new byte[nameLen];
                    int oldPos = buf.position();
                    buf.position(nameOff);
                    buf.get(nameBytes);
                    buf.position(oldPos);
                    domName = new String(nameBytes, StandardCharsets.UTF_8);
                } else {
                    domName = "dom_" + domId;
                }
            } else {
                domId = Short.toUnsignedInt(buf.getShort());
                keyType = buf.get();
                int nameLen = Short.toUnsignedInt(buf.getShort());

                if (buf.position() + nameLen > data.length) {
                    throw new IllegalArgumentException("Domain NameLen " + nameLen + " exceeds remaining file buffer size");
                }

                byte[] nameBytes = new byte[nameLen];
                buf.get(nameBytes);
                domName = new String(nameBytes, StandardCharsets.UTF_8);

                int mapCount = buf.getInt();
                for (int m = 0; m < mapCount; m++) {
                    buf.getInt();
                    int bkLen = Short.toUnsignedInt(buf.getShort());
                    buf.get(new byte[bkLen]);
                }
            }

            LoadedDomain domain = new LoadedDomain(domId, domName, keyType, Map.of());
            domainsById.put(domId, domain);
            domainsByName.put(domName, domain);
        }

        if (ver >= 2) {
            align64(buf);
        }

        // Parse Relation Section (CSR Adjacency Matrices)
        Map<String, RelationSnapshot> relationSnapshots = new HashMap<>();
        int dirTableOffset = (ver == 0x0204) ? dataOffset + domainCount * 64 : buf.position();

        for (int j = 0; j < relationCount; j++) {
            int entrySize = (ver == 0x0204) ? 128 : (ver >= 2 ? 109 : 28);
            buf.position(dirTableOffset + j * entrySize);
            int srcDomId = Short.toUnsignedInt(buf.getShort());
            int tgtDomId = Short.toUnsignedInt(buf.getShort());

            byte encodingType = 0x00;
            long sectionFeatures = 0;
            long csrRowOffOffset = 0;
            long csrRowOffBytes = 0;
            long csrColIdxOffset = 0;
            long csrColIdxBytes = 0;

            long nodeCount = 0;
            long edgeCount = 0;

            if (ver == 0x0204) {
                encodingType = buf.get();
                nodeCount = buf.getLong();
                edgeCount = buf.getLong();
                sectionFeatures = buf.getLong();
                buf.getLong(); // compatFeatures
                csrRowOffOffset = buf.getLong();
                csrRowOffBytes = buf.getLong();
                csrColIdxOffset = buf.getLong();
                csrColIdxBytes = buf.getLong();

                if (csrRowOffOffset > data.length || csrColIdxOffset > data.length) {
                    throw new IllegalArgumentException("Catalog section offset points outside file boundaries");
                }
                if (csrRowOffOffset > 0 && csrRowOffOffset % 64 != 0) {
                    throw new IllegalArgumentException("Catalog section offset not aligned to 64-byte boundary");
                }
            } else if (ver >= 2) {
                encodingType = buf.get();
                nodeCount = buf.getLong();
                edgeCount = buf.getLong();
                sectionFeatures = buf.getLong();
                csrRowOffOffset = buf.getLong();
                csrRowOffBytes = buf.getLong();
                csrColIdxOffset = buf.getLong();
                csrColIdxBytes = buf.getLong();
                buf.getLong(); // idMapOffset
                buf.getLong(); // idMapBytes
                buf.getLong(); // dtoLookupOffset
                buf.getLong(); // dtoLookupBytes
                buf.getLong(); // deltaLogOffset
                buf.getLong(); // deltaLogBytes

                if (csrRowOffOffset > data.length || csrColIdxOffset > data.length) {
                    throw new IllegalArgumentException("Catalog section offset points outside file boundaries");
                }
                if (csrRowOffOffset > 0 && csrRowOffOffset % 64 != 0) {
                    throw new IllegalArgumentException("Catalog section offset not aligned to 64-byte boundary");
                }
            } else {
                nodeCount = buf.getInt();
                edgeCount = buf.getLong();
                csrRowOffBytes = buf.getLong();
                csrColIdxBytes = buf.getLong();
            }

            LoadedDomain srcDom = domainsById.get(srcDomId);
            LoadedDomain tgtDom = domainsById.get(tgtDomId);
            String relName = (srcDom != null && tgtDom != null) ?
                    "rel_" + j + "_" + srcDom.name().toLowerCase() + "To" + capitalize(tgtDom.name().toLowerCase()) :
                    "relation_" + j;

            int numRowOffsets = (int) (csrRowOffBytes / 4);
            if (csrRowOffOffset > 0) {
                buf.position((int) csrRowOffOffset);
            } else if (version >= 2) {
                align64(buf);
            }

            int[] rowOffsetsData = new int[numRowOffsets];
            for (int r = 0; r < numRowOffsets; r++) {
                rowOffsetsData[r] = buf.getInt();
            }

            if (csrColIdxOffset > 0) {
                buf.position((int) csrColIdxOffset);
            } else if (version >= 2) {
                align64(buf);
            }

            int[] columnIndicesData = new int[(int) edgeCount];
            if (encodingType == 0x01) {
                // 0x01 = DELTA_VBYTE
                int colPtr = 0;
                for (int node = 0; node < (int) nodeCount; node++) {
                    int start = rowOffsetsData[node];
                    int end = rowOffsetsData[node + 1];
                    int prevTgt = 0;
                    for (int idx = start; idx < end; idx++) {
                        int delta = readVByte(buf);
                        int tgt = (idx == start) ? delta : (prevTgt + delta);
                        columnIndicesData[colPtr++] = tgt;
                        prevTgt = tgt;
                    }
                }
            } else if (encodingType == 0x02) {
                // 0x02 = RAW_UINT16
                int numColIndices = (int) (csrColIdxBytes / 2);
                for (int c = 0; c < numColIndices; c++) {
                    columnIndicesData[c] = Short.toUnsignedInt(buf.getShort());
                }
            } else if (encodingType == 0x03) {
                // 0x03 = HYBRID_UINT16_UINT32
                int colPtr = 0;
                for (int node = 0; node < (int) nodeCount; node++) {
                    int start = rowOffsetsData[node];
                    int end = rowOffsetsData[node + 1];
                    int rowLen = end - start;
                    int numHot = Short.toUnsignedInt(buf.getShort());
                    for (int i = 0; i < numHot; i++) {
                        columnIndicesData[colPtr++] = Short.toUnsignedInt(buf.getShort());
                    }
                    for (int i = numHot; i < rowLen; i++) {
                        columnIndicesData[colPtr++] = buf.getInt();
                    }
                }
            } else if (encodingType == 0x04) {
                // 0x04 = RELATION_FEAT_ENC_SIMDCOMP (SIMDComp / PFOR-Delta Bit-Packed Integer Stream)
                int colPtr = 0;
                for (int node = 0; node < (int) nodeCount; node++) {
                    int start = rowOffsetsData[node];
                    int end = rowOffsetsData[node + 1];
                    int rowLen = end - start;
                    if (rowLen == 0) continue;

                    int prevTgt = 0;
                    int idx = start;
                    while (idx < end) {
                        int chunkSize = Math.min(128, end - idx);
                        byte bitWidth = buf.get();
                        byte numExceptions = buf.get();
                        int packedByteLen = (chunkSize * bitWidth + 7) / 8;
                        byte[] packedBytes = new byte[packedByteLen];
                        buf.get(packedBytes);

                        // Bit-unpack deltas
                        long bitPos = 0;
                        int[] unpackedDeltas = new int[chunkSize];
                        for (int i = 0; i < chunkSize; i++) {
                            int val = 0;
                            for (int b = 0; b < bitWidth; b++) {
                                int byteIdx = (int) (bitPos >> 3);
                                int bitIdx = (int) (bitPos & 7);
                                if (byteIdx < packedBytes.length) {
                                    int bit = (packedBytes[byteIdx] >> bitIdx) & 1;
                                    val |= (bit << b);
                                }
                                bitPos++;
                            }
                            unpackedDeltas[i] = val;
                        }

                        // Apply frame exceptions
                        for (int ex = 0; ex < numExceptions; ex++) {
                            int exPos = Short.toUnsignedInt(buf.getShort());
                            int exVal = buf.getInt();
                            if (exPos < chunkSize) {
                                unpackedDeltas[exPos] = exVal;
                            }
                        }

                        // Reconstruct absolute target node IDs
                        for (int i = 0; i < chunkSize; i++) {
                            int delta = unpackedDeltas[i];
                            int tgt = (idx == start && i == 0) ? delta : (prevTgt + delta);
                            columnIndicesData[colPtr++] = tgt;
                            prevTgt = tgt;
                        }

                        idx += chunkSize;
                    }
                }
            } else if (encodingType == 0x07) {
                // 0x07 = RAW_UINT64
                int numColIndices = (int) (csrColIdxBytes / 8);
                for (int c = 0; c < numColIndices; c++) {
                    columnIndicesData[c] = (int) buf.getLong();
                }
            } else {
                // 0x00 = RAW_UINT32
                int numColIndices = (int) (csrColIdxBytes / 4);
                for (int c = 0; c < numColIndices; c++) {
                    columnIndicesData[c] = buf.getInt();
                }
            }

            if (version >= 2) {
                align64(buf);
            }

            RelationSnapshot csrSnapshot = new RelationSnapshot(arena, (int) nodeCount, (int) edgeCount, rowOffsetsData, columnIndicesData);
            relationSnapshots.put(relName, csrSnapshot);
        }

        GraphSnapshot fullGraph = new GraphSnapshot(arena, relationSnapshots);

        return new LoadedSnapshot(
                magic, version, domainCount, relationCount,
                kafkaOffset, timestampMs, globalFeatures, sha256Hex,
                domainsById, domainsByName, fullGraph
        );
    }

    private static int readVByte(ByteBuffer buf) {
        int val = 0;
        int shift = 0;
        while (true) {
            byte b = buf.get();
            val |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return val;
    }

    private static void align64(ByteBuffer buf) {
        int rem = buf.position() % 64;
        if (rem != 0) {
            buf.position(buf.position() + (64 - rem));
        }
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
