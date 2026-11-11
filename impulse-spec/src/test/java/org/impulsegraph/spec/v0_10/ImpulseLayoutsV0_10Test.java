package org.impulsegraph.spec.v0_10;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.impulsegraph.spec.v0_10.ImpulseLayoutsV0_10.*;

@DisplayName("Impulse Layouts v0.10 Specification Test Suite")
class ImpulseLayoutsV0_10Test {

	@Test
	@DisplayName("Test private constructor accessibility and instantiation for coverage")
	void testPrivateConstructor() throws Exception {
		Constructor<ImpulseLayoutsV0_10> constructor = ImpulseLayoutsV0_10.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		constructor.setAccessible(true);
		ImpulseLayoutsV0_10 instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("Test header specification constants")
	void testHeaderConstants() {
		assertThat(MAGIC).isEqualTo(0x494D5053);
		assertThat(SPEC_MAGIC).isEqualTo(0x494D5053);
		assertThat(VERSION_MAJOR).isEqualTo((short) 0);
		assertThat(VERSION_MINOR).isEqualTo((short) 10);
		assertThat(SPEC_VERSION_PACKED).isEqualTo((short) 10);
		assertThat(HEADER_BASELINE_OFFSET).isEqualTo(4096);

		byte[] magicBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC).array();
		assertThat(new String(magicBytes, StandardCharsets.US_ASCII)).isEqualTo("IMPS");
	}

	@Test
	@DisplayName("Test struct layout byte sizes")
	void testLayoutByteSizes() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteSize()).isEqualTo(4096L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteSize()).isEqualTo(32L);
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteSize()).isEqualTo(128L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteSize()).isEqualTo(44L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteSize()).isEqualTo(64L);
		assertThat(IMPULSE_FOOTER_TRAILER_V0_10_T_LAYOUT.byteSize()).isEqualTo(16L);
	}

	@Test
	@DisplayName("Test struct layout names")
	void testLayoutNames() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.name()).contains("impulse_snapshot_header_v0_10_t");
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.name()).contains("impulse_domain_catalog_entry_v0_10_t");
		assertThat(IMPULSE_RELATION_DIRECTORY_ENTRY_V0_10_T_LAYOUT.name())
				.contains("impulse_relation_directory_entry_v0_10_t");
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.name()).contains("impulse_attribute_descriptor_v0_10_t");
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.name())
				.contains("impulse_index_directory_entry_v0_10_t");
		assertThat(IMPULSE_FOOTER_TRAILER_V0_10_T_LAYOUT.name()).contains("impulse_footer_trailer_v0_10_t");
	}

	@Test
	@DisplayName("Test Section 1 Baseline Snapshot Header field byte offsets")
	void testSnapshotHeaderFieldOffsets() {
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("magic"))).isEqualTo(0L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("version")))
				.isEqualTo(4L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(6L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("domain_count")))
				.isEqualTo(10L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("relation_count")))
				.isEqualTo(12L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("timestamp_ms")))
				.isEqualTo(14L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("required_features")))
				.isEqualTo(22L);
		assertThat(
				IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("footer_directory_offset")))
				.isEqualTo(30L);
		assertThat(
				IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("footer_directory_bytes")))
				.isEqualTo(38L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("snapshot_uuid")))
				.isEqualTo(46L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("header_checksum")))
				.isEqualTo(62L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("index_count")))
				.isEqualTo(64L);
		assertThat(IMPULSE_SNAPSHOT_HEADER_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("header_padding")))
				.isEqualTo(66L);
	}

	@Test
	@DisplayName("Test Section 2.2 Domain Catalog Entry (32B) field byte offsets")
	void testDomainCatalogEntryFieldOffsets() {
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("domain_id")))
				.isEqualTo(0L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("key_type")))
				.isEqualTo(2L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(3L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(4L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("node_count")))
				.isEqualTo(8L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("attr_count")))
				.isEqualTo(16L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("key_attr_index")))
				.isEqualTo(18L);
		assertThat(IMPULSE_DOMAIN_CATALOG_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved2")))
				.isEqualTo(20L);
	}

	@Test
	@DisplayName("Test Section 2.4 Attribute Descriptor Entry (44B) field byte offsets")
	void testAttributeDescriptorFieldOffsets() {
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(0L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("type_code")))
				.isEqualTo(4L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(5L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved2")))
				.isEqualTo(6L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("dimension")))
				.isEqualTo(8L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(12L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("data_bytes")))
				.isEqualTo(20L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("offsets_offset")))
				.isEqualTo(28L);
		assertThat(IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("offsets_bytes")))
				.isEqualTo(36L);
	}

	@Test
	@DisplayName("Test Section 2.6 Index Directory Entry (64B) field byte offsets")
	void testIndexDirectoryEntryFieldOffsets() {
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("index_id")))
				.isEqualTo(0L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("domain_id")))
				.isEqualTo(4L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("relation_id")))
				.isEqualTo(6L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("attribute_index")))
				.isEqualTo(8L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("index_type")))
				.isEqualTo(10L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1")))
				.isEqualTo(11L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset")))
				.isEqualTo(12L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("data_offset")))
				.isEqualTo(16L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("data_bytes")))
				.isEqualTo(24L);
		assertThat(IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT
				.byteOffset(PathElement.groupElement("payload_feature_mask"))).isEqualTo(32L);
		assertThat(
				IMPULSE_INDEX_DIRECTORY_ENTRY_V0_10_T_LAYOUT.byteOffset(PathElement.groupElement("reserved_padding")))
				.isEqualTo(40L);
	}
}
