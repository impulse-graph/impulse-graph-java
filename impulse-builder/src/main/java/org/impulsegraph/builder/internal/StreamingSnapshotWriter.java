package org.impulsegraph.builder.internal;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.*;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;
import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_VERSION_PACKED;

/**
 * Core streaming writer implementation for Impulse Binary Snapshots v0.9.0.
 */
public final class StreamingSnapshotWriter implements SnapshotBuilder {

	private Path stagingDirectory;
	private long stagingMemoryLimit = 64 * 1024 * 1024L; // 64 MB default
	private PrivateKey privateKey;
	private List<Certificate> certificateChain;

	private final Map<String, DomainDefinition> domains = new LinkedHashMap<>();
	private final Map<String, RelationDefinition> relations = new LinkedHashMap<>();
	private final Map<String, String> stringMetadata = new LinkedHashMap<>();
	private final Map<String, byte[]> binaryMetadata = new LinkedHashMap<>();

	public StreamingSnapshotWriter() {
	}

	@Override
	public SnapshotBuilder withStagingDirectory(Path tempDir) {
		this.stagingDirectory = tempDir;
		return this;
	}

	@Override
	public SnapshotBuilder withStagingMemoryLimit(long bytes) {
		this.stagingMemoryLimit = bytes;
		return this;
	}

	@Override
	public SnapshotBuilder withSignature(PrivateKey privateKey, List<Certificate> certChain) {
		this.privateKey = Objects.requireNonNull(privateKey, "privateKey cannot be null");
		this.certificateChain = certChain != null ? List.copyOf(certChain) : List.of();
		return this;
	}

	@Override
	public SnapshotBuilder addDomain(String domainName, DomainDefinition domain) {
		Objects.requireNonNull(domainName, "domainName cannot be null");
		Objects.requireNonNull(domain, "domain cannot be null");
		domains.put(domainName, domain);
		return this;
	}

	@Override
	public SnapshotBuilder addRelation(String relationName, RelationDefinition relation) {
		Objects.requireNonNull(relationName, "relationName cannot be null");
		Objects.requireNonNull(relation, "relation cannot be null");
		relations.put(relationName, relation);
		return this;
	}

	@Override
	public SnapshotBuilder addFooterMetadata(String key, String value) {
		Objects.requireNonNull(key, "metadata key cannot be null");
		Objects.requireNonNull(value, "metadata value cannot be null");
		stringMetadata.put(key, value);
		return this;
	}

	@Override
	public SnapshotBuilder addFooterMetadata(String key, byte[] payload) {
		Objects.requireNonNull(key, "metadata key cannot be null");
		Objects.requireNonNull(payload, "metadata payload cannot be null");
		binaryMetadata.put(key, payload);
		return this;
	}

	@Override
	public void writeTo(WritableByteChannel channel) throws IOException {
		boolean createdTempStaging = false;
		Path effectiveStagingDir = stagingDirectory;
		if (effectiveStagingDir == null) {
			effectiveStagingDir = Files.createTempDirectory("impulse_stage_");
			createdTempStaging = true;
		} else {
			Files.createDirectories(effectiveStagingDir);
		}

		ExternalSortStaging staging = new ExternalSortStaging(effectiveStagingDir, stagingMemoryLimit);
		List<ExternalSortStaging.TopologyFiles> stagedFilesToClean = new ArrayList<>();

		try {
			// 1. Assign IDs
			List<String> domainNames = new ArrayList<>(domains.keySet());
			Map<String, Integer> domainNameToId = new HashMap<>();
			for (int i = 0; i < domainNames.size(); i++) {
				domainNameToId.put(domainNames.get(i), i);
			}

			List<String> relationNames = new ArrayList<>(relations.keySet());
			Map<String, Integer> relationNameToId = new HashMap<>();
			for (int i = 0; i < relationNames.size(); i++) {
				relationNameToId.put(relationNames.get(i), i);
			}

			// 2. Build Shared String Table
			ByteArrayOutputStream stringPoolOut = new ByteArrayOutputStream();
			stringPoolOut.write(0); // Offset 0 is empty string
			Map<String, Integer> stringMap = new HashMap<>();
			stringMap.put("", 0);

			var getOrAddString = new Object() {
				int apply(String s) {
					if (s == null || s.isEmpty())
						return 0;
					Integer existing = stringMap.get(s);
					if (existing != null)
						return existing;
					int off = stringPoolOut.size();
					byte[] b = s.getBytes(StandardCharsets.UTF_8);
					stringPoolOut.write(b, 0, b.length);
					stringPoolOut.write(0); // null-terminate
					stringMap.put(s, off);
					return off;
				}
			};

			for (String dName : domainNames) {
				getOrAddString.apply(dName);
			}
			for (String rName : relationNames) {
				getOrAddString.apply(rName);
			}

			// 3. Stage Relation Topologies to files
			record StagedRel(int relId, int srcDomId, int tgtDomId, long nodeCount, long edgeCount,
					ExternalSortStaging.TopologyFiles csr, ExternalSortStaging.TopologyFiles csc, boolean hasCsc) {
			}

			List<StagedRel> stagedRelations = new ArrayList<>();

			for (int relIdx = 0; relIdx < relationNames.size(); relIdx++) {
				String rName = relationNames.get(relIdx);
				RelationDefinition relDef = relations.get(rName);
				Integer srcDomId = domainNameToId.get(relDef.getSourceDomain());
				Integer tgtDomId = domainNameToId.get(relDef.getTargetDomain());
				if (srcDomId == null || tgtDomId == null) {
					throw new IllegalStateException("Relation '" + rName + "' references undefined domain: "
							+ relDef.getSourceDomain() + " -> " + relDef.getTargetDomain());
				}

				DomainDefinition srcDom = domains.get(relDef.getSourceDomain());
				DomainDefinition tgtDom = domains.get(relDef.getTargetDomain());

				int srcIdWidth = srcDom.getIdWidth().byteSize();
				int tgtIdWidth = tgtDom.getIdWidth().byteSize();
				int edgeIndexWidth = (srcDom.getCardinality() > 4_000_000_000L
						|| relDef.getDataSource().getEdgeCount() > 4_000_000_000L) ? 8 : 4;

				RelationDataSource rds = relDef.getDataSource();
				long edgeCount = rds != null ? rds.getEdgeCount() : 0;
				int srcNodeCount = (int) srcDom.getCardinality();
				int tgtNodeCount = (int) tgtDom.getCardinality();

				ExternalSortStaging.TopologyFiles csrFiles = null;
				ExternalSortStaging.TopologyFiles cscFiles = null;
				boolean includeCsc = relDef.includesTopology(Topology.CSC);

				if (rds != null && edgeCount > 0) {
					EdgeChunkIterator edgeIt = rds.getEdges();
					ExternalSortStaging.EdgeStreamReader reader = new FfmEdgeStreamReader(edgeIt, srcIdWidth,
							tgtIdWidth);
					Path prefix = effectiveStagingDir.resolve("rel_" + relIdx);

					csrFiles = staging.buildCsr(srcNodeCount, edgeCount, tgtIdWidth, edgeIndexWidth, prefix, reader);
					stagedFilesToClean.add(csrFiles);

					if (includeCsc) {
						if (rds.getTargetSortedEdges().isPresent()) {
							EdgeChunkIterator cscEdgeIt = rds.getTargetSortedEdges().get();
							ExternalSortStaging.EdgeStreamReader cscReader = new FfmEdgeStreamReader(cscEdgeIt,
									tgtIdWidth, srcIdWidth);
							cscFiles = staging.buildCsr(tgtNodeCount, edgeCount, srcIdWidth, edgeIndexWidth,
									prefix.resolveSibling("csc_" + relIdx), cscReader);
						} else {
							cscFiles = staging.buildCscFromCsr(tgtNodeCount, edgeCount, srcIdWidth, edgeIndexWidth,
									prefix, csrFiles.rowOffsetsFile(), csrFiles.columnTargetsFile(), srcNodeCount);
						}
						stagedFilesToClean.add(cscFiles);
					}
				}

				stagedRelations.add(new StagedRel(relIdx, srcDomId, tgtDomId, srcNodeCount, edgeCount, csrFiles,
						cscFiles, includeCsc));
			}

			// 4. Build Section 2 Directory Table
			ByteArrayOutputStream dirTableOut = new ByteArrayOutputStream();

			int poolBytes = stringPoolOut.size();
			ByteBuffer strHdrBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			strHdrBuf.putInt(poolBytes);
			dirTableOut.write(strHdrBuf.array());
			dirTableOut.write(stringPoolOut.toByteArray());

			align128(dirTableOut);

			// Domain Catalog Entries (16 bytes each)
			for (int dId = 0; dId < domainNames.size(); dId++) {
				String dName = domainNames.get(dId);
				DomainDefinition dom = domains.get(dName);
				int dNameOff = getOrAddString.apply(dName);

				ByteBuffer domBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
				domBuf.putShort((short) dId);
				domBuf.put((byte) 0x03); // key_type
				domBuf.put((byte) 0); // reserved
				domBuf.putInt(dNameOff);
				domBuf.putLong(dom.getCardinality());
				dirTableOut.write(domBuf.array());
			}

			align128(dirTableOut);

			// Calculate Section Layout Offsets
			int relationCount = stagedRelations.size();
			int totalDirLen = dirTableOut.size() + relationCount * 128;
			int alignedDirLen = (totalDirLen + 4095) & ~4095;
			long payloadBaseOffset = 4096L + alignedDirLen;

			// Layout relation payload offsets
			long currentPayloadOffset = payloadBaseOffset;
			List<ByteBuffer> relEntries = new ArrayList<>();

			for (StagedRel sr : stagedRelations) {
				currentPayloadOffset = (currentPayloadOffset + 4095) & ~4095;

				long csrRowOffOffset = 0, csrRowOffBytes = 0;
				long csrColIdxOffset = 0, csrColIdxBytes = 0;
				long cscRowOffOffset = 0, cscRowOffBytes = 0;
				long cscColIdxOffset = 0, cscColIdxBytes = 0;

				if (sr.csr != null) {
					currentPayloadOffset = (currentPayloadOffset + 127) & ~127;
					csrRowOffOffset = currentPayloadOffset;
					csrRowOffBytes = sr.csr.rowOffsetsBytes();
					currentPayloadOffset += csrRowOffBytes;

					currentPayloadOffset = (currentPayloadOffset + 127) & ~127;
					csrColIdxOffset = currentPayloadOffset;
					csrColIdxBytes = sr.csr.columnTargetsBytes();
					currentPayloadOffset += csrColIdxBytes;
				}

				if (sr.hasCsc && sr.csc != null) {
					currentPayloadOffset = (currentPayloadOffset + 127) & ~127;
					cscRowOffOffset = currentPayloadOffset;
					cscRowOffBytes = sr.csc.rowOffsetsBytes();
					currentPayloadOffset += cscRowOffBytes;

					currentPayloadOffset = (currentPayloadOffset + 127) & ~127;
					cscColIdxOffset = currentPayloadOffset;
					cscColIdxBytes = sr.csc.columnTargetsBytes();
					currentPayloadOffset += cscColIdxBytes;
				}

				String rName = relationNames.get(sr.relId);
				int rNameOff = getOrAddString.apply(rName);

				ByteBuffer relBuf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
				relBuf.putShort((short) sr.relId);
				relBuf.putShort((short) sr.srcDomId);
				relBuf.putShort((short) sr.tgtDomId);
				relBuf.put((byte) 0); // encoding_id = RAW
				relBuf.put((byte) 4); // node_id_width = 4
				relBuf.put((byte) 4); // edge_index_width = 4
				relBuf.put(new byte[3]); // reserved1
				relBuf.putInt(rNameOff);
				relBuf.putLong(sr.nodeCount);
				relBuf.putLong(sr.edgeCount);
				relBuf.putLong(sr.hasCsc ? 1L : 0L); // section_features (bit 0 = CSC)
				relBuf.putLong(csrRowOffOffset);
				relBuf.putLong(csrRowOffBytes);
				relBuf.putLong(csrColIdxOffset);
				relBuf.putLong(csrColIdxBytes);
				relBuf.putLong(cscRowOffOffset);
				relBuf.putLong(cscRowOffBytes);
				relBuf.putLong(cscColIdxOffset);
				relBuf.putLong(cscColIdxBytes);
				relBuf.putShort((short) 0); // attr_count
				relBuf.put(new byte[22]); // reserved2
				relEntries.add(relBuf);
			}

			// Add relation entries to directory table
			for (ByteBuffer rb : relEntries) {
				dirTableOut.write(rb.array());
			}
			align4k(dirTableOut);

			// 5. Build Header Page 0 (4096 bytes)
			ByteBuffer hdrBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
			hdrBuf.putInt(SPEC_MAGIC);
			hdrBuf.putShort((short) SPEC_VERSION_PACKED);
			hdrBuf.putInt(4096); // data_offset
			hdrBuf.putShort((short) domainNames.size());
			hdrBuf.putShort((short) relationNames.size());
			hdrBuf.putLong(System.currentTimeMillis());
			hdrBuf.putLong(1L); // required_features (4KB_PAGE_ALIGNED)
			hdrBuf.putLong(0L); // footer_directory_offset
			hdrBuf.putLong(0L); // footer_directory_bytes
			hdrBuf.put(
					UUID.randomUUID().toString().replace("-", "").substring(0, 16).getBytes(StandardCharsets.US_ASCII));

			// Compute CRC-16
			byte[] hdrBytes = hdrBuf.array();
			short crc = (short) computeCrc16(hdrBytes, 0, 0x3E);
			hdrBuf.putShort(0x3E, crc);

			// 6. Stream all bytes with rolling SHA-256
			MessageDigest sha256;
			try {
				sha256 = MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("SHA-256 not available", e);
			}

			DigestWritableChannel digestChannel = new DigestWritableChannel(channel, sha256);

			// Write Header Page 0
			hdrBuf.position(0);
			digestChannel.write(hdrBuf);

			// Write Section 2 Directory Table
			ByteBuffer dirBuf = ByteBuffer.wrap(dirTableOut.toByteArray());
			digestChannel.write(dirBuf);

			// Stream Relation Payloads
			long currentStreamOffset = payloadBaseOffset;
			byte[] pad128 = new byte[128];
			byte[] pad4k = new byte[4096];

			for (StagedRel sr : stagedRelations) {
				long target4k = (currentStreamOffset + 4095) & ~4095;
				if (target4k > currentStreamOffset) {
					int diff = (int) (target4k - currentStreamOffset);
					digestChannel.write(ByteBuffer.wrap(pad4k, 0, diff));
					currentStreamOffset = target4k;
				}

				if (sr.csr != null) {
					currentStreamOffset = writeStagedFileAligned(digestChannel, sr.csr.rowOffsetsFile(),
							currentStreamOffset, pad128);
					currentStreamOffset = writeStagedFileAligned(digestChannel, sr.csr.columnTargetsFile(),
							currentStreamOffset, pad128);
				}

				if (sr.hasCsc && sr.csc != null) {
					currentStreamOffset = writeStagedFileAligned(digestChannel, sr.csc.rowOffsetsFile(),
							currentStreamOffset, pad128);
					currentStreamOffset = writeStagedFileAligned(digestChannel, sr.csc.columnTargetsFile(),
							currentStreamOffset, pad128);
				}
			}

			// 7. Write Footer Block (4KB aligned)
			long footerStartOffset = (currentStreamOffset + 4095) & ~4095;
			if (footerStartOffset > currentStreamOffset) {
				int diff = (int) (footerStartOffset - currentStreamOffset);
				digestChannel.write(ByteBuffer.wrap(pad4k, 0, diff));
				currentStreamOffset = footerStartOffset;
			}

			ByteArrayOutputStream footerOut = new ByteArrayOutputStream();

			// Custom metadata stream
			Map<String, byte[]> allMetadata = new LinkedHashMap<>();
			for (var e : stringMetadata.entrySet()) {
				allMetadata.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
			}
			allMetadata.putAll(binaryMetadata);

			ByteBuffer metaCountBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			metaCountBuf.putInt(allMetadata.size());
			footerOut.write(metaCountBuf.array());

			for (var entry : allMetadata.entrySet()) {
				byte[] kb = entry.getKey().getBytes(StandardCharsets.UTF_8);
				ByteBuffer kBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
				kBuf.putShort((short) kb.length);
				footerOut.write(kBuf.array());
				footerOut.write(kb);

				byte[] vb = entry.getValue();
				ByteBuffer vBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
				vBuf.putInt(vb.length);
				footerOut.write(vBuf.array());
				footerOut.write(vb);
			}

			// Mandatory SHA-256 Checksum (32 bytes)
			byte[] payloadDigest = sha256.digest();
			footerOut.write(payloadDigest);

			// Optional Cryptographic Signature Block
			if (privateKey != null) {
				try {
					Signature sig = Signature.getInstance("SHA256withRSA"); // or Ed25519 if supported
					sig.initSign(privateKey);
					sig.update(payloadDigest);
					byte[] signatureBytes = sig.sign();

					ByteBuffer sigHdr = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
					sigHdr.putInt(signatureBytes.length);
					footerOut.write(sigHdr.array());
					footerOut.write(signatureBytes);
				} catch (Exception e) {
					throw new IOException("Failed to sign snapshot payload", e);
				}
			}

			// 16-byte Footer Trailer at EOF
			long footerLen = footerOut.size() + 16;
			ByteBuffer trailerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			trailerBuf.putLong(footerLen);
			trailerBuf.putInt(SPEC_VERSION_PACKED); // 9
			trailerBuf.putInt(SPEC_MAGIC); // 0x494D5053
			footerOut.write(trailerBuf.array());

			// Write entire footer
			channel.write(ByteBuffer.wrap(footerOut.toByteArray()));

		} finally {
			for (var f : stagedFilesToClean) {
				f.close();
			}
			if (createdTempStaging && effectiveStagingDir != null) {
				try {
					Files.deleteIfExists(effectiveStagingDir);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private static long writeStagedFileAligned(WritableByteChannel channel, Path file, long currentOffset,
			byte[] pad128) throws IOException {
		long target128 = (currentOffset + 127) & ~127;
		if (target128 > currentOffset) {
			int diff = (int) (target128 - currentOffset);
			channel.write(ByteBuffer.wrap(pad128, 0, diff));
			currentOffset = target128;
		}

		try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
			while (fileChannel.read(buf) > 0) {
				buf.flip();
				while (buf.hasRemaining()) {
					channel.write(buf);
				}
				currentOffset += buf.position();
				buf.clear();
			}
		}
		return currentOffset;
	}

	private static void align128(ByteArrayOutputStream out) throws IOException {
		int rem = out.size() % 128;
		if (rem != 0) {
			out.write(new byte[128 - rem]);
		}
	}

	private static void align4k(ByteArrayOutputStream out) throws IOException {
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

	private static final class DigestWritableChannel implements WritableByteChannel {
		private final WritableByteChannel delegate;
		private final MessageDigest digest;

		DigestWritableChannel(WritableByteChannel delegate, MessageDigest digest) {
			this.delegate = delegate;
			this.digest = digest;
		}

		@Override
		public int write(ByteBuffer src) throws IOException {
			int pos = src.position();
			int remaining = src.remaining();
			digest.update(src.duplicate());
			while (src.hasRemaining()) {
				delegate.write(src);
			}
			return remaining;
		}

		@Override
		public boolean isOpen() {
			return delegate.isOpen();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}

	private static final class FfmEdgeStreamReader implements ExternalSortStaging.EdgeStreamReader {
		private final EdgeChunkIterator iterator;
		private final int srcIdWidth;
		private final int tgtIdWidth;
		private final Arena arena;
		private final MemorySegment srcSeg;
		private final MemorySegment tgtSeg;
		private int currentCount = 0;

		FfmEdgeStreamReader(EdgeChunkIterator iterator, int srcIdWidth, int tgtIdWidth) {
			this.iterator = iterator;
			this.srcIdWidth = srcIdWidth;
			this.tgtIdWidth = tgtIdWidth;
			this.arena = Arena.ofConfined();
			this.srcSeg = arena.allocate((long) 8192 * srcIdWidth, 128);
			this.tgtSeg = arena.allocate((long) 8192 * tgtIdWidth, 128);
		}

		@Override
		public boolean hasNext() {
			return iterator.hasNext();
		}

		@Override
		public int readNextChunk() {
			currentCount = iterator.nextChunk(srcSeg, tgtSeg, 8192);
			return currentCount;
		}

		@Override
		public long currentSrc(int index) {
			if (srcIdWidth == 2) {
				return Short.toUnsignedInt(srcSeg.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, index));
			} else if (srcIdWidth == 8) {
				return srcSeg.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, index);
			} else {
				return Integer.toUnsignedLong(srcSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, index));
			}
		}

		@Override
		public long currentTgt(int index) {
			if (tgtIdWidth == 2) {
				return Short.toUnsignedInt(tgtSeg.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, index));
			} else if (tgtIdWidth == 8) {
				return tgtSeg.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, index);
			} else {
				return Integer.toUnsignedLong(tgtSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, index));
			}
		}
	}
}
