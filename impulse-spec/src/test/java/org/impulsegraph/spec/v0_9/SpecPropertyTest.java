package org.impulsegraph.spec.v0_9;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.lang.foreign.MemoryLayout.PathElement;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.*;

class SpecPropertyTest {

	@Property
	void arbitraryOffsetAlign128PreservesAlignmentAndMinimalExpansion(
			@ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 128) long offset) {
		long aligned = (offset + 127L) & ~127L;

		assertThat(aligned % 128L).isZero();
		assertThat(aligned & 0x7FL).isZero();
		assertThat(aligned).isGreaterThanOrEqualTo(offset);
		assertThat(aligned - offset).isLessThan(128L);

		if ((offset & 0x7FL) == 0) {
			assertThat(aligned).isEqualTo(offset);
		} else {
			assertThat(aligned).isGreaterThan(offset);
		}
	}

	@Property
	void arbitraryOffsetAlign4KbPreservesPageAlignment(
			@ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 4096) long offset) {
		long aligned = (offset + 4095L) & ~4095L;

		assertThat(aligned % 4096L).isZero();
		assertThat(aligned & 0xFFFL).isZero();
		// 4KB page alignment strictly satisfies 128-byte alignment (4096 = 32 * 128)
		assertThat(aligned % 128L).isZero();
		assertThat(aligned & 0x7FL).isZero();
		assertThat(aligned).isGreaterThanOrEqualTo(offset);
		assertThat(aligned - offset).isLessThan(4096L);

		if ((offset & 0xFFFL) == 0) {
			assertThat(aligned).isEqualTo(offset);
		} else {
			assertThat(aligned).isGreaterThan(offset);
		}
	}

	@Property
	void arbitraryHeaderBufferRoundTrip(@ForAll("headerBuffers") byte[] buffer, @ForAll int magic,
			@ForAll short version, @ForAll int dataOffset, @ForAll short domainCount, @ForAll short relationCount,
			@ForAll long timestampMs, @ForAll long requiredFeatures, @ForAll long footerDirOffset,
			@ForAll long footerDirBytes, @ForAll("uuidBytes") byte[] uuid, @ForAll short checksum) {
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

		bb.putInt((int) offMagic, magic);
		bb.putShort((int) offVersion, version);
		bb.putInt((int) offDataOffset, dataOffset);
		bb.putShort((int) offDomainCount, domainCount);
		bb.putShort((int) offRelationCount, relationCount);
		bb.putLong((int) offTimestamp, timestampMs);
		bb.putLong((int) offReqFeatures, requiredFeatures);
		bb.putLong((int) offFooterDirOffset, footerDirOffset);
		bb.putLong((int) offFooterDirBytes, footerDirBytes);
		bb.position((int) offUuid);
		bb.put(uuid);
		bb.putShort((int) offChecksum, checksum);

		assertThat(bb.getInt((int) offMagic)).isEqualTo(magic);
		assertThat(bb.getShort((int) offVersion)).isEqualTo(version);
		assertThat(bb.getInt((int) offDataOffset)).isEqualTo(dataOffset);
		assertThat(bb.getShort((int) offDomainCount)).isEqualTo(domainCount);
		assertThat(bb.getShort((int) offRelationCount)).isEqualTo(relationCount);
		assertThat(bb.getLong((int) offTimestamp)).isEqualTo(timestampMs);
		assertThat(bb.getLong((int) offReqFeatures)).isEqualTo(requiredFeatures);
		assertThat(bb.getLong((int) offFooterDirOffset)).isEqualTo(footerDirOffset);
		assertThat(bb.getLong((int) offFooterDirBytes)).isEqualTo(footerDirBytes);

		byte[] readUuid = new byte[16];
		bb.position((int) offUuid);
		bb.get(readUuid);
		assertThat(readUuid).containsExactly(uuid);

		assertThat(bb.getShort((int) offChecksum)).isEqualTo(checksum);
	}

	@Property
	void arbitraryDomainCatalogEntryRoundTrip(@ForAll("domainBuffers") byte[] buffer, @ForAll short domainId,
			@ForAll byte keyType, @ForAll byte reserved, @ForAll int nameOffset, @ForAll long nodeCount) {
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offDomainId = IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("domain_id"));
		long offKeyType = IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("key_type"));
		long offReserved = IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved"));
		long offNameOffset = IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("name_offset"));
		long offNodeCount = IMPULSE_DOMAIN_CATALOG_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("node_count"));

		bb.putShort((int) offDomainId, domainId);
		bb.put((int) offKeyType, keyType);
		bb.put((int) offReserved, reserved);
		bb.putInt((int) offNameOffset, nameOffset);
		bb.putLong((int) offNodeCount, nodeCount);

		assertThat(bb.getShort((int) offDomainId)).isEqualTo(domainId);
		assertThat(bb.get((int) offKeyType)).isEqualTo(keyType);
		assertThat(bb.get((int) offReserved)).isEqualTo(reserved);
		assertThat(bb.getInt((int) offNameOffset)).isEqualTo(nameOffset);
		assertThat(bb.getLong((int) offNodeCount)).isEqualTo(nodeCount);
	}

	@Property
	void arbitraryRelationDirectoryEntryRoundTrip(@ForAll("relationBuffers") byte[] buffer, @ForAll short relationId,
			@ForAll short srcDomainId, @ForAll short tgtDomainId, @ForAll byte encodingId, @ForAll byte nodeIdWidth,
			@ForAll byte edgeIndexWidth, @ForAll int nameOffset, @ForAll long nodeCount, @ForAll long edgeCount,
			@ForAll long sectionFeatures, @ForAll long csrRowOffOffset, @ForAll long csrRowOffBytes,
			@ForAll long csrColIdxOffset, @ForAll long csrColIdxBytes, @ForAll long cscRowOffOffset,
			@ForAll long cscRowOffBytes, @ForAll long cscColIdxOffset, @ForAll long cscColIdxBytes,
			@ForAll short attrCount) {
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
		long offCscRowOff = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_row_off_offset"));
		long offCscRowBytes = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_row_off_bytes"));
		long offCscColIdx = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_col_idx_offset"));
		long offCscColBytes = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("csc_col_idx_bytes"));
		long offAttrCount = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("attr_count"));

		bb.putShort((int) offRelId, relationId);
		bb.putShort((int) offSrcDomain, srcDomainId);
		bb.putShort((int) offTgtDomain, tgtDomainId);
		bb.put((int) offEncId, encodingId);
		bb.put((int) offNodeWidth, nodeIdWidth);
		bb.put((int) offEdgeWidth, edgeIndexWidth);
		bb.putInt((int) offNameOffset, nameOffset);
		bb.putLong((int) offNodeCount, nodeCount);
		bb.putLong((int) offEdgeCount, edgeCount);
		bb.putLong((int) offFeatures, sectionFeatures);
		bb.putLong((int) offCsrRowOff, csrRowOffOffset);
		bb.putLong((int) offCsrRowBytes, csrRowOffBytes);
		bb.putLong((int) offCsrColIdx, csrColIdxOffset);
		bb.putLong((int) offCsrColBytes, csrColIdxBytes);
		bb.putLong((int) offCscRowOff, cscRowOffOffset);
		bb.putLong((int) offCscRowBytes, cscRowOffBytes);
		bb.putLong((int) offCscColIdx, cscColIdxOffset);
		bb.putLong((int) offCscColBytes, cscColIdxBytes);
		bb.putShort((int) offAttrCount, attrCount);

		assertThat(bb.getShort((int) offRelId)).isEqualTo(relationId);
		assertThat(bb.getShort((int) offSrcDomain)).isEqualTo(srcDomainId);
		assertThat(bb.getShort((int) offTgtDomain)).isEqualTo(tgtDomainId);
		assertThat(bb.get((int) offEncId)).isEqualTo(encodingId);
		assertThat(bb.get((int) offNodeWidth)).isEqualTo(nodeIdWidth);
		assertThat(bb.get((int) offEdgeWidth)).isEqualTo(edgeIndexWidth);
		assertThat(bb.getInt((int) offNameOffset)).isEqualTo(nameOffset);
		assertThat(bb.getLong((int) offNodeCount)).isEqualTo(nodeCount);
		assertThat(bb.getLong((int) offEdgeCount)).isEqualTo(edgeCount);
		assertThat(bb.getLong((int) offFeatures)).isEqualTo(sectionFeatures);
		assertThat(bb.getLong((int) offCsrRowOff)).isEqualTo(csrRowOffOffset);
		assertThat(bb.getLong((int) offCsrRowBytes)).isEqualTo(csrRowOffBytes);
		assertThat(bb.getLong((int) offCsrColIdx)).isEqualTo(csrColIdxOffset);
		assertThat(bb.getLong((int) offCsrColBytes)).isEqualTo(csrColIdxBytes);
		assertThat(bb.getLong((int) offCscRowOff)).isEqualTo(cscRowOffOffset);
		assertThat(bb.getLong((int) offCscRowBytes)).isEqualTo(cscRowOffBytes);
		assertThat(bb.getLong((int) offCscColIdx)).isEqualTo(cscColIdxOffset);
		assertThat(bb.getLong((int) offCscColBytes)).isEqualTo(cscColIdxBytes);
		assertThat(bb.getShort((int) offAttrCount)).isEqualTo(attrCount);
	}

	@Property
	void arbitraryAttributeDescriptorRoundTrip(@ForAll("attrBuffers") byte[] buffer, @ForAll int nameOffset,
			@ForAll byte typeCode, @ForAll byte reserved1, @ForAll short reserved2, @ForAll int dimension,
			@ForAll long dataOffset, @ForAll long dataBytes, @ForAll long offsetsOffset, @ForAll long offsetsBytes) {
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offName = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset"));
		long offType = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("type_code"));
		long offRes1 = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1"));
		long offRes2 = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved2"));
		long offDim = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("dimension"));
		long offDataOff = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("data_offset"));
		long offDataBytes = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("data_bytes"));
		long offOffsetsOff = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("offsets_offset"));
		long offOffsetsBytes = IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("offsets_bytes"));

		bb.putInt((int) offName, nameOffset);
		bb.put((int) offType, typeCode);
		bb.put((int) offRes1, reserved1);
		bb.putShort((int) offRes2, reserved2);
		bb.putInt((int) offDim, dimension);
		bb.putLong((int) offDataOff, dataOffset);
		bb.putLong((int) offDataBytes, dataBytes);
		bb.putLong((int) offOffsetsOff, offsetsOffset);
		bb.putLong((int) offOffsetsBytes, offsetsBytes);

		assertThat(bb.getInt((int) offName)).isEqualTo(nameOffset);
		assertThat(bb.get((int) offType)).isEqualTo(typeCode);
		assertThat(bb.get((int) offRes1)).isEqualTo(reserved1);
		assertThat(bb.getShort((int) offRes2)).isEqualTo(reserved2);
		assertThat(bb.getInt((int) offDim)).isEqualTo(dimension);
		assertThat(bb.getLong((int) offDataOff)).isEqualTo(dataOffset);
		assertThat(bb.getLong((int) offDataBytes)).isEqualTo(dataBytes);
		assertThat(bb.getLong((int) offOffsetsOff)).isEqualTo(offsetsOffset);
		assertThat(bb.getLong((int) offOffsetsBytes)).isEqualTo(offsetsBytes);
	}

	@Property
	void arbitraryIndexDirectoryEntryRoundTrip(@ForAll("indexBuffers") byte[] buffer, @ForAll int indexId,
			@ForAll short domainId, @ForAll short relationId, @ForAll short attributeIndex, @ForAll byte indexType,
			@ForAll byte reserved1, @ForAll int nameOffset, @ForAll long dataOffset, @ForAll long dataBytes,
			@ForAll long payloadFeatureMask) {
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offIndexId = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("index_id"));
		long offDomainId = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("domain_id"));
		long offRelId = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("relation_id"));
		long offAttrIdx = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("attribute_index"));
		long offType = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("index_type"));
		long offRes1 = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("reserved1"));
		long offName = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("name_offset"));
		long offDataOff = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("data_offset"));
		long offDataBytes = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("data_bytes"));
		long offFeatureMask = IMPULSE_INDEX_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("payload_feature_mask"));

		bb.putInt((int) offIndexId, indexId);
		bb.putShort((int) offDomainId, domainId);
		bb.putShort((int) offRelId, relationId);
		bb.putShort((int) offAttrIdx, attributeIndex);
		bb.put((int) offType, indexType);
		bb.put((int) offRes1, reserved1);
		bb.putInt((int) offName, nameOffset);
		bb.putLong((int) offDataOff, dataOffset);
		bb.putLong((int) offDataBytes, dataBytes);
		bb.putLong((int) offFeatureMask, payloadFeatureMask);

		assertThat(bb.getInt((int) offIndexId)).isEqualTo(indexId);
		assertThat(bb.getShort((int) offDomainId)).isEqualTo(domainId);
		assertThat(bb.getShort((int) offRelId)).isEqualTo(relationId);
		assertThat(bb.getShort((int) offAttrIdx)).isEqualTo(attributeIndex);
		assertThat(bb.get((int) offType)).isEqualTo(indexType);
		assertThat(bb.get((int) offRes1)).isEqualTo(reserved1);
		assertThat(bb.getInt((int) offName)).isEqualTo(nameOffset);
		assertThat(bb.getLong((int) offDataOff)).isEqualTo(dataOffset);
		assertThat(bb.getLong((int) offDataBytes)).isEqualTo(dataBytes);
		assertThat(bb.getLong((int) offFeatureMask)).isEqualTo(payloadFeatureMask);
	}

	@Property
	void arbitraryFooterTrailerRoundTrip(@ForAll("footerBuffers") byte[] buffer, @ForAll long footerLength,
			@ForAll int specVersion, @ForAll int footerMagic) {
		ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

		long offLength = IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_length"));
		long offVer = IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("spec_version"));
		long offMagic = IMPULSE_FOOTER_TRAILER_V0_9_T_LAYOUT.byteOffset(PathElement.groupElement("footer_magic"));

		bb.putLong((int) offLength, footerLength);
		bb.putInt((int) offVer, specVersion);
		bb.putInt((int) offMagic, footerMagic);

		assertThat(bb.getLong((int) offLength)).isEqualTo(footerLength);
		assertThat(bb.getInt((int) offVer)).isEqualTo(specVersion);
		assertThat(bb.getInt((int) offMagic)).isEqualTo(footerMagic);
	}

	@Property
	void arbitraryMultiEntryTableSlicingInvariants(@ForAll @IntRange(min = 1, max = 32) int count) {
		byte[] table = new byte[count * 128];
		ByteBuffer bb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN);
		long relIdOffset = IMPULSE_RELATION_DIRECTORY_ENTRY_V0_9_T_LAYOUT
				.byteOffset(PathElement.groupElement("relation_id"));

		for (int i = 0; i < count; i++) {
			long entryOffset = i * 128L;
			// Each relation directory entry is strictly aligned to a 128-byte hardware
			// boundary
			assertThat(entryOffset % 128L).isZero();
			bb.putShort((int) (entryOffset + relIdOffset), (short) (i + 1));
		}

		for (int i = 0; i < count; i++) {
			long entryOffset = i * 128L;
			assertThat(bb.getShort((int) (entryOffset + relIdOffset))).isEqualTo((short) (i + 1));
		}
	}

	@Property
	void arbitraryVectorOffsetsHardwareAlignmentVerification(@ForAll @IntRange(min = 0, max = 50000) int multiplier) {
		long offset = multiplier * 128L;
		assertThat(offset % 128L).isZero();
		assertThat(offset & 0x7FL).isZero();
		long aligned = (offset + 127L) & ~127L;
		assertThat(aligned).isEqualTo(offset);
	}

	@Provide
	Arbitrary<byte[]> headerBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(4096);
	}

	@Provide
	Arbitrary<byte[]> relationBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(128);
	}

	@Provide
	Arbitrary<byte[]> domainBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(16);
	}

	@Provide
	Arbitrary<byte[]> attrBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(44);
	}

	@Provide
	Arbitrary<byte[]> indexBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(64);
	}

	@Provide
	Arbitrary<byte[]> footerBuffers() {
		return Arbitraries.bytes().array(byte[].class).ofSize(16);
	}

	@Provide
	Arbitrary<byte[]> uuidBytes() {
		return Arbitraries.bytes().array(byte[].class).ofSize(16);
	}
}
