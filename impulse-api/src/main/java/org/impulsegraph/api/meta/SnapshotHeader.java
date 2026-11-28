package org.impulsegraph.api.meta;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable summary of Page 0 binary snapshot header metadata (Spec v0.9 /
 * v0.10 / v0.11).
 */
public record SnapshotHeader(int magic, int version, long dataOffset, int domainCount, int relationCount,
		int indexCount, long timestampMs, long requiredFeatures, long footerDirectoryOffset, long footerDirectoryBytes,
		long fileSizeBytes, int headerChecksum, byte[] snapshotUuid) {

	public SnapshotHeader {
		snapshotUuid = (snapshotUuid != null && snapshotUuid.length == 16)
				? Arrays.copyOf(snapshotUuid, 16)
				: new byte[16];
	}

	@Override
	public byte[] snapshotUuid() {
		return Arrays.copyOf(snapshotUuid, snapshotUuid.length);
	}

	public String snapshotUuidHex() {
		return HexFormat.of().formatHex(snapshotUuid);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SnapshotHeader other)) {
			return false;
		}
		return magic == other.magic && version == other.version && dataOffset == other.dataOffset
				&& domainCount == other.domainCount && relationCount == other.relationCount
				&& indexCount == other.indexCount && timestampMs == other.timestampMs
				&& requiredFeatures == other.requiredFeatures && footerDirectoryOffset == other.footerDirectoryOffset
				&& footerDirectoryBytes == other.footerDirectoryBytes && fileSizeBytes == other.fileSizeBytes
				&& headerChecksum == other.headerChecksum && Arrays.equals(snapshotUuid, other.snapshotUuid);
	}

	@Override
	public int hashCode() {
		int result = Objects.hash(magic, version, dataOffset, domainCount, relationCount, indexCount, timestampMs,
				requiredFeatures, footerDirectoryOffset, footerDirectoryBytes, fileSizeBytes, headerChecksum);
		return 31 * result + Arrays.hashCode(snapshotUuid);
	}

	@Override
	public String toString() {
		return "SnapshotHeader[magic=0x" + Integer.toHexString(magic) + ", version=" + version + ", dataOffset="
				+ dataOffset + ", domainCount=" + domainCount + ", relationCount=" + relationCount + ", indexCount="
				+ indexCount + ", timestampMs=" + timestampMs + ", requiredFeatures=0x"
				+ Long.toHexString(requiredFeatures) + ", footerDirectoryOffset=" + footerDirectoryOffset
				+ ", footerDirectoryBytes=" + footerDirectoryBytes + ", fileSizeBytes=" + fileSizeBytes
				+ ", headerChecksum=0x" + Integer.toHexString(headerChecksum) + ", snapshotUuid=" + snapshotUuidHex()
				+ "]";
	}
}
