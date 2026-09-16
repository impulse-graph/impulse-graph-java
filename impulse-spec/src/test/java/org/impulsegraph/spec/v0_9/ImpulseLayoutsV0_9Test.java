package org.impulsegraph.spec.v0_9;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.*;

@DisplayName("Impulse Layouts v0.9 Specification Test Suite")
class ImpulseLayoutsV0_9Test {

	@Test
	@DisplayName("Test private constructor accessibility and instantiation for coverage")
	void testPrivateConstructor() throws Exception {
		Constructor<ImpulseLayoutsV0_9> constructor = ImpulseLayoutsV0_9.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		constructor.setAccessible(true);
		ImpulseLayoutsV0_9 instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("Test header specification constants")
	void testHeaderConstants() {
		assertThat(MAGIC).isEqualTo(0x494D5053);
		assertThat(SPEC_MAGIC).isEqualTo(0x494D5053);
		assertThat(VERSION_MAJOR).isEqualTo((short) 0);
		assertThat(VERSION_MINOR).isEqualTo((short) 9);
		assertThat(SPEC_VERSION_PACKED).isEqualTo((short) 9);
		assertThat(HEADER_BASELINE_OFFSET).isEqualTo(4096);

		// Verify MAGIC matches ASCII 'IMPS' in big-endian order
		byte[] magicBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC).array();
		assertThat(new String(magicBytes, StandardCharsets.US_ASCII)).isEqualTo("IMPS");
	}

	@Test
	@DisplayName("Test struct layout byte sizes")
	void testLayoutByteSizes() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteSize()).isEqualTo(4096L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteSize()).isEqualTo(16L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteSize()).isEqualTo(128L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteSize()).isEqualTo(44L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteSize()).isEqualTo(64L);
		assertThat(IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteSize()).isEqualTo(16L);
	}

	@Test
	@DisplayName("Test struct layout names")
	void testLayoutNames() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.name()).contains("impulse_snapshot_header_v0_9_t");
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.name()).contains("impulse_domain_catalog_entry_v0_9_t");
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.name())
				.contains("impulse_relation_directory_entry_v0_9_t");
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.name()).contains("impulse_attribute_descriptor_v0_9_t");
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.name()).contains("impulse_index_directory_entry_v0_9_t");
		assertThat(IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.name()).contains("impulse_footer_trailer_v0_9_t");
	}

	@Test
	@DisplayName("Test Section 1 Baseline Snapshot Header field byte offsets")
	void testSnapshotHeaderFieldOffsets() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("magic"))).isEqualTo(0L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("version"))).isEqualTo(4L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(6L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("domain_count")))
				.isEqualTo(10L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("relation_count")))
				.isEqualTo(12L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("timestamp_ms")))
				.isEqualTo(14L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("required_features")))
				.isEqualTo(22L);
		assertThat(
				IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_directory_offset")))
				.isEqualTo(30L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_directory_bytes")))
				.isEqualTo(38L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("snapshot_uuid")))
				.isEqualTo(46L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("header_checksum")))
				.isEqualTo(62L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("header_padding")))
				.isEqualTo(64L);
	}

	@Test
	@DisplayName("Test Section 2 Domain Catalog Entry field byte offsets")
	void testDomainCatalogEntryFieldOffsets() {
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("domain_id")))
				.isEqualTo(0L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("key_type")))
				.isEqualTo(2L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved")))
				.isEqualTo(3L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(4L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("node_count")))
				.isEqualTo(8L);
	}

	@Test
	@DisplayName("Test Section 2 Relation Directory Entry field byte offsets")
	void testRelationDirectoryEntryFieldOffsets() {
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("relation_id")))
				.isEqualTo(0L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("src_domain_id")))
				.isEqualTo(2L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("tgt_domain_id")))
				.isEqualTo(4L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("encoding_id")))
				.isEqualTo(6L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("node_id_width")))
				.isEqualTo(7L);
		assertThat(
				IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("edge_index_width")))
				.isEqualTo(8L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(9L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(12L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("node_count")))
				.isEqualTo(16L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("edge_count")))
				.isEqualTo(24L);
		assertThat(
				IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("section_features")))
				.isEqualTo(32L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_row_off_offset"))).isEqualTo(40L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_row_off_bytes"))).isEqualTo(48L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_col_idx_offset"))).isEqualTo(56L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_col_idx_bytes"))).isEqualTo(64L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_row_off_offset"))).isEqualTo(72L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_row_off_bytes"))).isEqualTo(80L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_col_idx_offset"))).isEqualTo(88L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_col_idx_bytes"))).isEqualTo(96L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("attr_count")))
				.isEqualTo(104L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved2")))
				.isEqualTo(106L);
	}

	@Test
	@DisplayName("Test Section 2 Edge Attribute Descriptor field byte offsets")
	void testAttributeDescriptorFieldOffsets() {
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(0L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("type_code")))
				.isEqualTo(4L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(5L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved2")))
				.isEqualTo(6L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("dimension")))
				.isEqualTo(8L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(12L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_bytes")))
				.isEqualTo(20L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("offsets_offset")))
				.isEqualTo(28L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("offsets_bytes")))
				.isEqualTo(36L);
	}

	@Test
	@DisplayName("Test Section 2.6 Secondary Index Directory Entry field byte offsets")
	void testIndexDirectoryEntryFieldOffsets() {
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("index_id")))
				.isEqualTo(0L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("domain_id")))
				.isEqualTo(4L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("relation_id")))
				.isEqualTo(6L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("attribute_index")))
				.isEqualTo(8L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("index_type")))
				.isEqualTo(10L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(11L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(12L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(16L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_bytes")))
				.isEqualTo(24L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("payload_feature_mask"))).isEqualTo(32L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved_padding")))
				.isEqualTo(40L);
	}

	@Test
	@DisplayName("Test Section Footer Trailer field byte offsets")
	void testFooterTrailerFieldOffsets() {
		assertThat(IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_length")))
				.isEqualTo(0L);
		assertThat(IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("spec_version")))
				.isEqualTo(8L);
		assertThat(IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_magic")))
				.isEqualTo(12L);
	}

	@Test
	@DisplayName("Test 128-byte hardware alignment rules for vector traversal offsets")
	void test128ByteHardwareAlignmentRules() {
		// 1. Header baseline offset (4096) must be strictly 128-byte and 4KB page
		// aligned
		assertThat(is128ByteAligned(HEADER_BASELINE_OFFSET)).isTrue();
		assertThat(is4KbPageAligned(HEADER_BASELINE_OFFSET)).isTrue();

		// 2. Header layout size (4096) must be strictly 128-byte and 4KB page aligned
		assertThat(is128ByteAligned(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteSize())).isTrue();
		assertThat(is4KbPageAligned(IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteSize())).isTrue();

		// 3. Relation directory entry layout size (128) must be strictly 128-byte
		// aligned
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteSize() % 128L).isZero();
		assertThat(is128ByteAligned(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteSize())).isTrue();

		// 4. Test aligning helper functions
		assertThat(align128(0L)).isEqualTo(0L);
		assertThat(align128(1L)).isEqualTo(128L);
		assertThat(align128(127L)).isEqualTo(128L);
		assertThat(align128(128L)).isEqualTo(128L);
		assertThat(align128(129L)).isEqualTo(256L);
		assertThat(align128(4096L)).isEqualTo(4096L);

		// 5. Test 4KB alignment helper functions
		assertThat(align4Kb(0L)).isEqualTo(0L);
		assertThat(align4Kb(1L)).isEqualTo(4096L);
		assertThat(align4Kb(4095L)).isEqualTo(4096L);
		assertThat(align4Kb(4096L)).isEqualTo(4096L);
		assertThat(align4Kb(4097L)).isEqualTo(8192L);

		// 6. Any 4KB aligned offset is also 128-byte aligned
		for (long p = 0; p <= 16384; p += 4096) {
			assertThat(is128ByteAligned(p)).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(longs = {0L, 128L, 256L, 512L, 1024L, 2048L, 4096L, 8192L, 65536L, 1048576L})
	@DisplayName("Test valid 128-byte aligned offsets")
	void testValid128ByteAlignedOffsets(long offset) {
		assertThat(is128ByteAligned(offset)).isTrue();
		assertThat(align128(offset)).isEqualTo(offset);
	}

	@ParameterizedTest
	@ValueSource(longs = {1L, 2L, 15L, 63L, 64L, 127L, 129L, 255L, 257L, 4095L, 4097L})
	@DisplayName("Test non-128-byte aligned offsets")
	void testNon128ByteAlignedOffsets(long offset) {
		assertThat(is128ByteAligned(offset)).isFalse();
		long aligned = align128(offset);
		assertThat(is128ByteAligned(aligned)).isTrue();
		assertThat(aligned).isGreaterThan(offset);
		assertThat(aligned - offset).isLessThan(128L);
	}

	@Test
	@DisplayName("Test Header serialization and layout offset reading round-trip")
	void testHeaderBinarySerializationRoundTrip() {
		byte[] buffer = new byte[4096];
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offMagic = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("magic"));
		long offVersion = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("version"));
		long offDataOffset = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset"));
		long offDomainCount = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("domain_count"));
		long offRelationCount = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("relation_count"));
		long offTimestamp = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("timestamp_ms"));
		long offReqFeatures = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("required_features"));
		long offFooterDirOffset = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("footer_directory_offset"));
		long offFooterDirBytes = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("footer_directory_bytes"));
		long offUuid = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("snapshot_uuid"));
		long offChecksum = IMPULSE_SNAPSHOT_HEADER_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("header_checksum"));

		bb.putInt((int) offMagic, MAGIC);
		bb.putShort((int) offVersion, SPEC_VERSION_PACKED);
		bb.putInt((int) offDataOffset, HEADER_BASELINE_OFFSET);
		bb.putShort((int) offDomainCount, (short) 3);
		bb.putShort((int) offRelationCount, (short) 5);
		bb.putLong((int) offTimestamp, 1726444800000L);
		bb.putLong((int) offReqFeatures, 0x01L);
		bb.putLong((int) offFooterDirOffset, 65536L);
		bb.putLong((int) offFooterDirBytes, 1024L);

		byte[] uuid = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
		bb.position((int) offUuid);
		bb.put(uuid);

		bb.putShort((int) offChecksum, (short) 0xABCD);

		// Read back via offsets
		assertThat(bb.getInt((int) offMagic)).isEqualTo(MAGIC);
		assertThat(bb.getShort((int) offVersion)).isEqualTo(SPEC_VERSION_PACKED);
		assertThat(bb.getInt((int) offDataOffset)).isEqualTo(HEADER_BASELINE_OFFSET);
		assertThat(bb.getShort((int) offDomainCount)).isEqualTo((short) 3);
		assertThat(bb.getShort((int) offRelationCount)).isEqualTo((short) 5);
		assertThat(bb.getLong((int) offTimestamp)).isEqualTo(1726444800000L);
		assertThat(bb.getLong((int) offReqFeatures)).isEqualTo(0x01L);
		assertThat(bb.getLong((int) offFooterDirOffset)).isEqualTo(65536L);
		assertThat(bb.getLong((int) offFooterDirBytes)).isEqualTo(1024L);

		byte[] readUuid = new byte[16];
		bb.position((int) offUuid);
		bb.get(readUuid);
		assertThat(readUuid).containsExactly(uuid);

		assertThat(bb.getShort((int) offChecksum)).isEqualTo((short) 0xABCD);
	}

	@Test
	@DisplayName("Test Relation Directory Entry binary serialization round-trip")
	void testRelationDirectoryBinarySerializationRoundTrip() {
		byte[] buffer = new byte[128];
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offRelId = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("relation_id"));
		long offSrcDomain = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("src_domain_id"));
		long offTgtDomain = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("tgt_domain_id"));
		long offEncId = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("encoding_id"));
		long offNodeWidth = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("node_id_width"));
		long offEdgeWidth = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("edge_index_width"));
		long offNameOffset = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("name_offset"));
		long offNodeCount = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("node_count"));
		long offEdgeCount = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("edge_count"));
		long offFeatures = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("section_features"));
		long offCsrRowOff = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_row_off_offset"));
		long offCsrRowBytes = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_row_off_bytes"));
		long offCsrColIdx = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_col_idx_offset"));
		long offCsrColBytes = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csr_col_idx_bytes"));
		long offAttrCount = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("attr_count"));

		bb.putShort((int) offRelId, (short) 1);
		bb.putShort((int) offSrcDomain, (short) 0);
		bb.putShort((int) offTgtDomain, (short) 2);
		bb.put((int) offEncId, (byte) 0);
		bb.put((int) offNodeWidth, (byte) 32);
		bb.put((int) offEdgeWidth, (byte) 32);
		bb.putInt((int) offNameOffset, 128);
		bb.putLong((int) offNodeCount, 100_000L);
		bb.putLong((int) offEdgeCount, 500_000L);
		bb.putLong((int) offFeatures, 0x01L);
		bb.putLong((int) offCsrRowOff, 8192L); // 128-byte aligned
		bb.putLong((int) offCsrRowBytes, 400_004L);
		bb.putLong((int) offCsrColIdx, 409600L); // 128-byte aligned
		bb.putLong((int) offCsrColBytes, 2_000_000L);
		bb.putShort((int) offAttrCount, (short) 2);

		assertThat(bb.getShort((int) offRelId)).isEqualTo((short) 1);
		assertThat(bb.getShort((int) offSrcDomain)).isEqualTo((short) 0);
		assertThat(bb.getShort((int) offTgtDomain)).isEqualTo((short) 2);
		assertThat(bb.get((int) offEncId)).isEqualTo((byte) 0);
		assertThat(bb.get((int) offNodeWidth)).isEqualTo((byte) 32);
		assertThat(bb.get((int) offEdgeWidth)).isEqualTo((byte) 32);
		assertThat(bb.getInt((int) offNameOffset)).isEqualTo(128);
		assertThat(bb.getLong((int) offNodeCount)).isEqualTo(100_000L);
		assertThat(bb.getLong((int) offEdgeCount)).isEqualTo(500_000L);
		assertThat(bb.getLong((int) offFeatures)).isEqualTo(0x01L);
		assertThat(is128ByteAligned(bb.getLong((int) offCsrRowOff))).isTrue();
		assertThat(bb.getLong((int) offCsrRowBytes)).isEqualTo(400_004L);
		assertThat(is128ByteAligned(bb.getLong((int) offCsrColIdx))).isTrue();
		assertThat(bb.getLong((int) offCsrColBytes)).isEqualTo(2_000_000L);
		assertThat(bb.getShort((int) offAttrCount)).isEqualTo((short) 2);
	}

	public static boolean is128ByteAligned(long offset) {
		return (offset & 0x7FL) == 0;
	}

	public static long align128(long offset) {
		return (offset + 127L) & ~127L;
	}

	public static boolean is4KbPageAligned(long offset) {
		return (offset & 0xFFFL) == 0;
	}

	public static long align4Kb(long offset) {
		return (offset + 4095L) & ~4095L;
	}
}
