package org.impulsegraph.spec.v0_9;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

/**
 * Java 25 Foreign Function & Memory (FFM) Layouts for Impulse Graph Binary Snapshot Spec v0.9.0.
 */
public final class ImpulseLayoutsV0_9 {
    private ImpulseLayoutsV0_9() {}

    public static final int SPEC_VERSION_MAJOR = 0;
    public static final int SPEC_VERSION_MINOR = 9;
    public static final int SPEC_VERSION_PACKED = 9;
    public static final int SPEC_MAGIC = 0x494D5053;
    public static final int HEADER_BASELINE_OFFSET = 4096;

    public static final byte IMPULSE_TYPE_MASK = (byte) 0x7F;
    public static final byte IMPULSE_NULLABLE_FLAG = (byte) 0x80;

    /** Section 1 Fixed 4KB Page 0 Header (v0.9.0) */
    public static final StructLayout IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT_UNALIGNED.withName("magic"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("version"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("data_offset"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("domain_count"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("relation_count"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("timestamp_ms"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("required_features"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("footer_directory_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("footer_directory_bytes"),
        MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_BYTE).withName("snapshot_uuid"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("header_checksum"),
        MemoryLayout.sequenceLayout(4032, ValueLayout.JAVA_BYTE).withName("header_padding")
    ).withName("impulse_snapshot_header_v0_9_t");

    public static final VarHandle VH_HEADER_MAGIC =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("magic"));
    public static final VarHandle VH_HEADER_VERSION =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("version"));
    public static final VarHandle VH_HEADER_DATA_OFFSET =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("data_offset"));
    public static final VarHandle VH_HEADER_DOMAIN_COUNT =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("domain_count"));
    public static final VarHandle VH_HEADER_RELATION_COUNT =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("relation_count"));
    public static final VarHandle VH_HEADER_TIMESTAMP_MS =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("timestamp_ms"));
    public static final VarHandle VH_HEADER_REQUIRED_FEATURES =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("required_features"));
    public static final VarHandle VH_HEADER_FOOTER_DIRECTORY_OFFSET =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("footer_directory_offset"));
    public static final VarHandle VH_HEADER_FOOTER_DIRECTORY_BYTES =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("footer_directory_bytes"));
    public static final VarHandle VH_HEADER_HEADER_CHECKSUM =
        IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("header_checksum"));

    /** 16-Byte Footer Trailer (EOF - 16) */
    public static final StructLayout IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG_UNALIGNED.withName("footer_length"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("spec_version"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("footer_magic")
    ).withName("impulse_footer_trailer_v0_9_t");

    public static final VarHandle VH_FOOTER_LENGTH =
        IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("footer_length"));
    public static final VarHandle VH_FOOTER_SPEC_VERSION =
        IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("spec_version"));
    public static final VarHandle VH_FOOTER_MAGIC =
        IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("footer_magic"));

    /** Domain Catalog Entry (Fixed 16 Bytes) */
    public static final StructLayout IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("domain_id"),
        ValueLayout.JAVA_BYTE.withName("key_type"),
        ValueLayout.JAVA_BYTE.withName("reserved"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("node_count")
    ).withName("impulse_domain_catalog_entry_v0_9_t");

    public static final VarHandle VH_DOMAIN_ID =
        IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("domain_id"));
    public static final VarHandle VH_DOMAIN_KEY_TYPE =
        IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("key_type"));
    public static final VarHandle VH_DOMAIN_NAME_OFFSET =
        IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("name_offset"));
    public static final VarHandle VH_DOMAIN_NODE_COUNT =
        IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("node_count"));

    /** Section 2 Relation Directory Entry Descriptor (Fixed 128 Bytes) */
    public static final StructLayout IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("relation_id"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("src_domain_id"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("tgt_domain_id"),
        ValueLayout.JAVA_BYTE.withName("encoding_id"),
        ValueLayout.JAVA_BYTE.withName("node_id_width"),
        ValueLayout.JAVA_BYTE.withName("edge_index_width"),
        MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("reserved1"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("node_count"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("edge_count"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("section_features"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csr_row_off_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csr_row_off_bytes"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csr_col_idx_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csr_col_idx_bytes"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csc_row_off_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csc_row_off_bytes"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csc_col_idx_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("csc_col_idx_bytes"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("attr_count"),
        MemoryLayout.sequenceLayout(22, ValueLayout.JAVA_BYTE).withName("reserved2")
    ).withName("impulse_relation_directory_entry_v0_9_t");

    public static final VarHandle VH_RELATION_ID =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("relation_id"));
    public static final VarHandle VH_RELATION_SRC_DOMAIN_ID =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("src_domain_id"));
    public static final VarHandle VH_RELATION_TGT_DOMAIN_ID =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("tgt_domain_id"));
    public static final VarHandle VH_RELATION_ENCODING_ID =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("encoding_id"));
    public static final VarHandle VH_RELATION_NODE_ID_WIDTH =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("node_id_width"));
    public static final VarHandle VH_RELATION_EDGE_INDEX_WIDTH =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("edge_index_width"));
    public static final VarHandle VH_RELATION_NAME_OFFSET =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("name_offset"));
    public static final VarHandle VH_RELATION_NODE_COUNT =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("node_count"));
    public static final VarHandle VH_RELATION_EDGE_COUNT =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("edge_count"));
    public static final VarHandle VH_RELATION_CSR_ROW_OFF_OFFSET =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("csr_row_off_offset"));
    public static final VarHandle VH_RELATION_CSR_ROW_OFF_BYTES =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("csr_row_off_bytes"));
    public static final VarHandle VH_RELATION_CSR_COL_IDX_OFFSET =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("csr_col_idx_offset"));
    public static final VarHandle VH_RELATION_CSR_COL_IDX_BYTES =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("csr_col_idx_bytes"));
    public static final VarHandle VH_RELATION_ATTR_COUNT =
        IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("attr_count"));

    /** Attribute Descriptor Entry (Fixed 44 Bytes) */
    public static final StructLayout IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
        ValueLayout.JAVA_BYTE.withName("type_code"),
        ValueLayout.JAVA_BYTE.withName("reserved1"),
        ValueLayout.JAVA_SHORT_UNALIGNED.withName("reserved2"),
        ValueLayout.JAVA_INT_UNALIGNED.withName("dimension"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("data_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("data_bytes"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("offsets_offset"),
        ValueLayout.JAVA_LONG_UNALIGNED.withName("offsets_bytes")
    ).withName("impulse_attribute_descriptor_v0_9_t");

    public static final VarHandle VH_ATTR_NAME_OFFSET =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("name_offset"));
    public static final VarHandle VH_ATTR_TYPE_CODE =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("type_code"));
    public static final VarHandle VH_ATTR_DIMENSION =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("dimension"));
    public static final VarHandle VH_ATTR_DATA_OFFSET =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("data_offset"));
    public static final VarHandle VH_ATTR_DATA_BYTES =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("data_bytes"));
    public static final VarHandle VH_ATTR_OFFSETS_OFFSET =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("offsets_offset"));
    public static final VarHandle VH_ATTR_OFFSETS_BYTES =
        IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("offsets_bytes"));
}
