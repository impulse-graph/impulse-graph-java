// Generated from impulse-graph-spec v0.10.0 specification.
package org.impulsegraph.spec.v0_10;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.StructLayout;

public final class ImpulseLayoutsV0_10 {
	private ImpulseLayoutsV0_10() {
	}

	public static final short VERSION_MAJOR = 0;
	public static final short VERSION_MINOR = 10;
	public static final int MAGIC = 0x494D5053;
	public static final int SPEC_MAGIC = 0x494D5053;
	public static final short SPEC_VERSION_PACKED = 10;
	public static final int HEADER_BASELINE_OFFSET = 4096;

	// Section 1 Fixed 4KB Baseline Snapshot Header (Spec v0.10.0)
	public static final StructLayout IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT = MemoryLayout
			.structLayout(ValueLayout.JAVA_INT_UNALIGNED.withName("magic"),
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
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("index_count"),
					MemoryLayout.sequenceLayout(4030, ValueLayout.JAVA_BYTE).withName("header_padding"))
			.withName("impulse_snapshot_header_v0_10_t");

	// Section 2.2 Domain Catalog Entry (32 Bytes)
	public static final StructLayout IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT = MemoryLayout
			.structLayout(ValueLayout.JAVA_SHORT_UNALIGNED.withName("domain_id"),
					ValueLayout.JAVA_BYTE.withName("key_type"), ValueLayout.JAVA_BYTE.withName("reserved1"),
					ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
					ValueLayout.JAVA_LONG_UNALIGNED.withName("node_count"),
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("attr_count"),
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("key_attr_index"),
					MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("reserved2"))
			.withName("impulse_domain_catalog_entry_v0_10_t");

	// Section 2.3 Relation Directory Entry (128 Bytes)
	public static final StructLayout IMPULSE_RELATION_DIRECTORY_ENTRY_V0_10_T_LAYOUT = MemoryLayout
			.structLayout(ValueLayout.JAVA_SHORT_UNALIGNED.withName("relation_id"),
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("src_domain_id"),
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("tgt_domain_id"),
					ValueLayout.JAVA_BYTE.withName("encoding_id"), ValueLayout.JAVA_BYTE.withName("node_id_width"),
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
					MemoryLayout.sequenceLayout(22, ValueLayout.JAVA_BYTE).withName("reserved2"))
			.withName("impulse_relation_directory_entry_v0_10_t");

	// Section 2.4 Attribute Descriptor Entry (44 Bytes)
	public static final StructLayout IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT = MemoryLayout
			.structLayout(ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
					ValueLayout.JAVA_BYTE.withName("type_code"), ValueLayout.JAVA_BYTE.withName("reserved1"),
					ValueLayout.JAVA_SHORT_UNALIGNED.withName("reserved2"),
					ValueLayout.JAVA_INT_UNALIGNED.withName("dimension"),
					ValueLayout.JAVA_LONG_UNALIGNED.withName("data_offset"),
					ValueLayout.JAVA_LONG_UNALIGNED.withName("data_bytes"),
					ValueLayout.JAVA_LONG_UNALIGNED.withName("offsets_offset"),
					ValueLayout.JAVA_LONG_UNALIGNED.withName("offsets_bytes"))
			.withName("impulse_attribute_descriptor_v0_10_t");

	// Section 2.6 Secondary Index Directory Entry (Fixed 64 Bytes POD)
	public static final StructLayout IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT = MemoryLayout.structLayout(
			ValueLayout.JAVA_INT_UNALIGNED.withName("index_id"), ValueLayout.JAVA_SHORT_UNALIGNED.withName("domain_id"),
			ValueLayout.JAVA_SHORT_UNALIGNED.withName("relation_id"),
			ValueLayout.JAVA_SHORT_UNALIGNED.withName("attribute_index"), ValueLayout.JAVA_BYTE.withName("index_type"),
			ValueLayout.JAVA_BYTE.withName("reserved1"), ValueLayout.JAVA_INT_UNALIGNED.withName("name_offset"),
			ValueLayout.JAVA_LONG_UNALIGNED.withName("data_offset"),
			ValueLayout.JAVA_LONG_UNALIGNED.withName("data_bytes"),
			ValueLayout.JAVA_LONG_UNALIGNED.withName("payload_feature_mask"),
			MemoryLayout.sequenceLayout(24, ValueLayout.JAVA_BYTE).withName("reserved_padding"))
			.withName("impulse_index_directory_entry_v0_10_t");

	// 16-Byte Footer Trailer at EOF
	public static final StructLayout IMPULSE_FOOTER_TRAILER_V0_10_T_LAYOUT = MemoryLayout
			.structLayout(ValueLayout.JAVA_LONG_UNALIGNED.withName("footer_length"),
					ValueLayout.JAVA_INT_UNALIGNED.withName("spec_version"),
					ValueLayout.JAVA_INT_UNALIGNED.withName("footer_magic"))
			.withName("impulse_footer_trailer_v0_10_t");
}
