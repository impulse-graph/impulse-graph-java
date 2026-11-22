package org.impulsegraph.cli.service;

import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.builder.csv.CsvManifestSnapshotBuilder;
import org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.storage.stats.RelationStatisticsCalculator;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reusable service for building, inspecting, validating, and computing
 * statistics on {@code .imps} binary snapshots.
 */
public final class SnapshotService {

	private static final int ALIGNMENT_128 = 128;
	private static final int PAGE_SIZE_4K = 4096;

	private SnapshotService() {
	}

	public static void build(Path manifestPath, Path outputPath) throws IOException {
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(outputPath, "outputPath must not be null");
		Path parent = outputPath.toAbsolutePath().normalize().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		CsvManifestSnapshotBuilder.buildV10Snapshot(manifestPath, outputPath);
	}

	// ---- Inspect ----

	public record InspectOptions(boolean pageMap, String domainFilter, String relationFilter, boolean anonymize) {
		public static InspectOptions defaults() {
			return new InspectOptions(false, null, null, false);
		}
	}

	public record HeaderInfo(int magic, short version, int dataOffset, int domainCount, int relationCount,
			long timestampMs, long requiredFeatures, long footerDirectoryOffset, long footerDirectoryBytes,
			long fileSizeBytes, boolean header4kAligned, boolean data128Aligned) {
	}

	public record DomainSummary(int id, String name, long nodeCount, byte keyType, List<String> attributes) {
	}

	public record RelationSummary(int id, String name, String srcDomain, String tgtDomain, long nodeCount,
			long edgeCount, byte encodingId, int nodeIdWidth, int edgeIndexWidth, boolean hasCsr, boolean hasCsc,
			boolean hasCoo, long csrRowOffOffset, long csrRowOffBytes, long csrColIdxOffset, long csrColIdxBytes,
			List<String> attributes) {
	}

	public record PageMapEntry(String section, long offset, long bytes, boolean aligned128, boolean aligned4k) {
	}

	public record InspectReport(Path snapshotPath, HeaderInfo header, List<DomainSummary> domains,
			List<RelationSummary> relations, Map<String, String> metadata, String sha256Checksum,
			List<PageMapEntry> pageMap) {

		public String toText(boolean includePageMap) {
			StringBuilder sb = new StringBuilder();
			sb.append("Impulse Binary Snapshot Inspection\n");
			sb.append("  File              : ").append(snapshotPath).append('\n');
			sb.append("  File Size         : ").append(header.fileSizeBytes()).append(" bytes\n");
			sb.append("  Magic             : 0x").append(String.format("%08X", header.magic())).append('\n');
			sb.append("  Format Version    : 0.").append(header.version()).append(".0\n");
			sb.append("  Data Offset       : ").append(header.dataOffset()).append(" (128B aligned: ")
					.append(header.data128Aligned()).append(", 4KB page: ").append(header.header4kAligned())
					.append(")\n");
			sb.append("  Footer Dir Offset : ").append(header.footerDirectoryOffset()).append(" (")
					.append(header.footerDirectoryBytes()).append(" bytes)\n");
			sb.append("  Required Features : 0x").append(String.format("%016X", header.requiredFeatures()))
					.append('\n');
			sb.append("  Timestamp (ms)    : ").append(header.timestampMs()).append('\n');
			if (sha256Checksum != null && !sha256Checksum.isEmpty()) {
				sb.append("  SHA-256           : ").append(sha256Checksum).append('\n');
			}
			sb.append("  Domains (").append(domains.size()).append("):\n");
			for (DomainSummary d : domains) {
				sb.append("    [").append(d.id()).append("] ").append(d.name()).append(" (nodes=").append(d.nodeCount())
						.append(", keyType=0x").append(String.format("%02X", d.keyType() & 0xFF)).append(", attrs=")
						.append(d.attributes()).append(")\n");
			}
			sb.append("  Relations (").append(relations.size()).append("):\n");
			for (RelationSummary r : relations) {
				sb.append("    [").append(r.id()).append("] ").append(r.name()).append(": ").append(r.srcDomain())
						.append(" -> ").append(r.tgtDomain()).append(" (edges=").append(r.edgeCount())
						.append(", idWidth=").append(r.nodeIdWidth()).append("B, CSR=").append(r.hasCsr())
						.append(", CSC=").append(r.hasCsc()).append(", COO=").append(r.hasCoo()).append(", attrs=")
						.append(r.attributes()).append(")\n");
			}
			if (!metadata.isEmpty()) {
				sb.append("  Footer Metadata:\n");
				for (Map.Entry<String, String> e : metadata.entrySet()) {
					sb.append("    ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
				}
			}
			if (includePageMap && !pageMap.isEmpty()) {
				sb.append("  Page / Alignment Map:\n");
				for (PageMapEntry p : pageMap) {
					sb.append(String.format("    %-32s offset=0x%08X (%d) bytes=%d align128=%s align4K=%s%n",
							p.section(), p.offset(), p.offset(), p.bytes(), p.aligned128(), p.aligned4k()));
				}
			}
			return sb.toString();
		}

		public String toJson(boolean includePageMap) {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("file", snapshotPath.toString());
			Map<String, Object> hdr = new LinkedHashMap<>();
			hdr.put("magic", String.format("0x%08X", header.magic()));
			hdr.put("version", "0." + header.version() + ".0");
			hdr.put("fileSizeBytes", header.fileSizeBytes());
			hdr.put("dataOffset", header.dataOffset());
			hdr.put("domainCount", header.domainCount());
			hdr.put("relationCount", header.relationCount());
			hdr.put("timestampMs", header.timestampMs());
			hdr.put("requiredFeatures", String.format("0x%016X", header.requiredFeatures()));
			hdr.put("footerDirectoryOffset", header.footerDirectoryOffset());
			hdr.put("footerDirectoryBytes", header.footerDirectoryBytes());
			hdr.put("header4kAligned", header.header4kAligned());
			hdr.put("data128Aligned", header.data128Aligned());
			root.put("header", hdr);
			root.put("sha256", sha256Checksum);

			List<Map<String, Object>> domList = new ArrayList<>();
			for (DomainSummary d : domains) {
				Map<String, Object> dm = new LinkedHashMap<>();
				dm.put("id", d.id());
				dm.put("name", d.name());
				dm.put("nodeCount", d.nodeCount());
				dm.put("keyType", String.format("0x%02X", d.keyType() & 0xFF));
				dm.put("attributes", d.attributes());
				domList.add(dm);
			}
			root.put("domains", domList);

			List<Map<String, Object>> relList = new ArrayList<>();
			for (RelationSummary r : relations) {
				Map<String, Object> rm = new LinkedHashMap<>();
				rm.put("id", r.id());
				rm.put("name", r.name());
				rm.put("srcDomain", r.srcDomain());
				rm.put("tgtDomain", r.tgtDomain());
				rm.put("nodeCount", r.nodeCount());
				rm.put("edgeCount", r.edgeCount());
				rm.put("encodingId", r.encodingId());
				rm.put("nodeIdWidth", r.nodeIdWidth());
				rm.put("edgeIndexWidth", r.edgeIndexWidth());
				rm.put("hasCsr", r.hasCsr());
				rm.put("hasCsc", r.hasCsc());
				rm.put("hasCoo", r.hasCoo());
				rm.put("csrRowOffOffset", r.csrRowOffOffset());
				rm.put("csrRowOffBytes", r.csrRowOffBytes());
				rm.put("csrColIdxOffset", r.csrColIdxOffset());
				rm.put("csrColIdxBytes", r.csrColIdxBytes());
				rm.put("attributes", r.attributes());
				relList.add(rm);
			}
			root.put("relations", relList);
			root.put("metadata", metadata);

			if (includePageMap) {
				List<Map<String, Object>> pmList = new ArrayList<>();
				for (PageMapEntry p : pageMap) {
					Map<String, Object> pm = new LinkedHashMap<>();
					pm.put("section", p.section());
					pm.put("offset", p.offset());
					pm.put("bytes", p.bytes());
					pm.put("aligned128", p.aligned128());
					pm.put("aligned4k", p.aligned4k());
					pmList.add(pm);
				}
				root.put("pageMap", pmList);
			}

			return JsonWriter.toJson(root);
		}
	}

	public static InspectReport inspect(Path snapshotPath, InspectOptions opts) throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		InspectOptions effective = opts != null ? opts : InspectOptions.defaults();
		HeaderInfo header = readRawHeader(snapshotPath);

		List<DomainSummary> domains = new ArrayList<>();
		List<RelationSummary> relations = new ArrayList<>();
		List<PageMapEntry> pageMap = new ArrayList<>();
		Map<String, String> metadata = new LinkedHashMap<>();
		String sha256;

		pageMap.add(new PageMapEntry("Page0.Header", 0L, PAGE_SIZE_4K, true, true));

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, false);
			sha256 = loaded.getSha256Checksum();
			if (loaded.getMetadataMap() != null) {
				for (Map.Entry<String, String> entry : loaded.getMetadataMap().entrySet()) {
					metadata.put(entry.getKey(), effective.anonymize() ? "<redacted>" : entry.getValue());
				}
			}

			Map<Integer, String> domainNames = new HashMap<>();
			List<BinarySnapshotLoader.LoadedDomain> sortedDoms = new ArrayList<>(loaded.domainsById().values());
			sortedDoms.sort(Comparator.comparingInt(BinarySnapshotLoader.LoadedDomain::domainId));
			for (BinarySnapshotLoader.LoadedDomain d : sortedDoms) {
				String displayName = effective.anonymize() ? ("domain_" + d.domainId()) : d.name();
				domainNames.put(d.domainId(), displayName);
				if (effective.domainFilter() != null && !effective.domainFilter().isBlank()
						&& !d.name().equalsIgnoreCase(effective.domainFilter())) {
					continue;
				}
				List<String> attrNames = new ArrayList<>();
				int idx = 0;
				for (BinarySnapshotLoader.LoadedAttribute a : d.attributes()) {
					attrNames.add(effective.anonymize() ? ("attr_" + idx) : a.name());
					idx++;
				}
				domains.add(new DomainSummary(d.domainId(), displayName, d.nodeCount(), d.keyType(), attrNames));
			}

			Map<Integer, String> relIdToName = new HashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnap = new HashMap<>();
			if (loaded.graph() != null) {
				for (Map.Entry<String, org.impulsegraph.api.RelationSnapshot> e : loaded.graph()
						.getAllRelationSnapshots().entrySet()) {
					if (e.getValue() instanceof RelationSnapshot rs && rs.getRelationId() >= 0) {
						relIdToName.putIfAbsent(rs.getRelationId(), e.getKey());
						relIdToSnap.putIfAbsent(rs.getRelationId(), rs);
					}
				}
			}

			List<BinarySnapshotLoader.LoadedRelation> sortedRels = new ArrayList<>(loaded.relationsById().values());
			sortedRels.sort(Comparator.comparingInt(BinarySnapshotLoader.LoadedRelation::relationId));
			for (BinarySnapshotLoader.LoadedRelation lr : sortedRels) {
				String rawName = relIdToName.getOrDefault(lr.relationId(), "rel_" + lr.relationId());
				if (effective.relationFilter() != null && !effective.relationFilter().isBlank()
						&& !rawName.equalsIgnoreCase(effective.relationFilter())) {
					continue;
				}
				String displayName = effective.anonymize() ? ("rel_" + lr.relationId()) : rawName;
				RelationSnapshot rs = relIdToSnap.get(lr.relationId());
				boolean hasCsr = rs != null ? rs.hasCsr() : (lr.csrRowOffBytes() > 0);
				boolean hasCsc = rs != null && rs.hasCsc();
				boolean hasCoo = lr.encodingId() == 1;

				List<String> attrNames = new ArrayList<>();
				int idx = 0;
				if (lr.attributes() != null) {
					for (BinarySnapshotLoader.LoadedAttribute a : lr.attributes()) {
						attrNames.add(effective.anonymize() ? ("attr_" + idx) : a.name());
						idx++;
					}
				}

				relations.add(new RelationSummary(lr.relationId(), displayName,
						domainNames.getOrDefault(lr.srcDomainId(), "domain_" + lr.srcDomainId()),
						domainNames.getOrDefault(lr.tgtDomainId(), "domain_" + lr.tgtDomainId()), lr.nodeCount(),
						lr.edgeCount(), lr.encodingId(), Byte.toUnsignedInt(lr.nodeIdWidth()),
						Byte.toUnsignedInt(lr.edgeIndexWidth()), hasCsr, hasCsc, hasCoo, lr.csrRowOffOffset(),
						lr.csrRowOffBytes(), lr.csrColIdxOffset(), lr.csrColIdxBytes(), attrNames));

				if (lr.csrRowOffOffset() > 0) {
					pageMap.add(new PageMapEntry("Relation[" + displayName + "].CSR.RowOff", lr.csrRowOffOffset(),
							lr.csrRowOffBytes(), (lr.csrRowOffOffset() % ALIGNMENT_128) == 0,
							(lr.csrRowOffOffset() % PAGE_SIZE_4K) == 0));
				}
				if (lr.csrColIdxOffset() > 0) {
					pageMap.add(new PageMapEntry("Relation[" + displayName + "].CSR.ColIdx", lr.csrColIdxOffset(),
							lr.csrColIdxBytes(), (lr.csrColIdxOffset() % ALIGNMENT_128) == 0,
							(lr.csrColIdxOffset() % PAGE_SIZE_4K) == 0));
				}
			}

			if (header.footerDirectoryOffset() > 0) {
				pageMap.add(new PageMapEntry("Footer.Directory", header.footerDirectoryOffset(),
						header.footerDirectoryBytes(), (header.footerDirectoryOffset() % ALIGNMENT_128) == 0,
						(header.footerDirectoryOffset() % PAGE_SIZE_4K) == 0));
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to inspect snapshot " + snapshotPath + ": " + e.getMessage(), e);
		}

		return new InspectReport(snapshotPath, header, List.copyOf(domains), List.copyOf(relations),
				Map.copyOf(metadata), sha256, List.copyOf(pageMap));
	}

	// ---- Validate ----

	public record ValidateOptions(boolean strictAlignment, boolean quickCheck, boolean deepAudit) {
		public static ValidateOptions defaults() {
			return new ValidateOptions(false, false, false);
		}
	}

	public record ValidationReport(Path snapshotPath, boolean valid, List<String> checksPassed, List<String> errors,
			List<String> warnings) {

		public String toText() {
			StringBuilder sb = new StringBuilder();
			sb.append("Impulse Snapshot Validation Report\n");
			sb.append("  File   : ").append(snapshotPath).append('\n');
			sb.append("  Status : ").append(valid ? "PASS" : "FAIL").append('\n');
			if (!checksPassed.isEmpty()) {
				sb.append("  Checks Passed (").append(checksPassed.size()).append("):\n");
				for (String c : checksPassed) {
					sb.append("    [OK] ").append(c).append('\n');
				}
			}
			if (!warnings.isEmpty()) {
				sb.append("  Warnings (").append(warnings.size()).append("):\n");
				for (String w : warnings) {
					sb.append("    [WARN] ").append(w).append('\n');
				}
			}
			if (!errors.isEmpty()) {
				sb.append("  Errors (").append(errors.size()).append("):\n");
				for (String e : errors) {
					sb.append("    [ERR] ").append(e).append('\n');
				}
			}
			return sb.toString();
		}

		public String toJson() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("file", snapshotPath.toString());
			root.put("valid", valid);
			root.put("checksPassed", checksPassed);
			root.put("warnings", warnings);
			root.put("errors", errors);
			return JsonWriter.toJson(root);
		}
	}

	public static ValidationReport validate(Path snapshotPath, ValidateOptions opts) throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		ValidateOptions effective = opts != null ? opts : ValidateOptions.defaults();
		List<String> passed = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		if (!Files.exists(snapshotPath)) {
			errors.add("Snapshot file does not exist: " + snapshotPath);
			return new ValidationReport(snapshotPath, false, passed, errors, warnings);
		}
		long fileSize = Files.size(snapshotPath);
		if (fileSize < PAGE_SIZE_4K) {
			errors.add("File too small for 4KB Page 0 header: " + fileSize + " bytes");
			return new ValidationReport(snapshotPath, false, passed, errors, warnings);
		}

		HeaderInfo header = readRawHeader(snapshotPath);
		if (header.magic() != ImpulseLayoutsV0_9.SPEC_MAGIC) {
			errors.add(String.format("Invalid magic: 0x%08X (expected 0x%08X)", header.magic(),
					ImpulseLayoutsV0_9.SPEC_MAGIC));
		} else {
			passed.add("Header magic 0x494D5053 (IMPS) verified");
		}

		if (header.version() != 9 && header.version() != 10) {
			errors.add("Unsupported snapshot format version: " + header.version());
		} else {
			passed.add("Format version 0." + header.version() + ".0 supported");
		}

		if (effective.strictAlignment()) {
			if (!header.data128Aligned()) {
				errors.add("Header data_offset=" + header.dataOffset() + " is not 128-byte aligned");
			} else {
				passed.add("Header data_offset 128-byte alignment verified");
			}
		}

		if (!errors.isEmpty()) {
			return new ValidationReport(snapshotPath, false, passed, errors, warnings);
		}

		boolean verifyChecksum = !effective.quickCheck();
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena,
					verifyChecksum);
			if (verifyChecksum) {
				passed.add("SHA-256 footer checksum verified");
			} else {
				warnings.add("SHA-256 checksum verification skipped (--quick-check)");
			}

			Map<Integer, String> relIdToName = new HashMap<>();
			Map<Integer, RelationSnapshot> relIdToSnap = new HashMap<>();
			if (loaded.graph() != null) {
				for (Map.Entry<String, org.impulsegraph.api.RelationSnapshot> e : loaded.graph()
						.getAllRelationSnapshots().entrySet()) {
					if (e.getValue() instanceof RelationSnapshot rs && rs.getRelationId() >= 0) {
						relIdToName.putIfAbsent(rs.getRelationId(), e.getKey());
						relIdToSnap.putIfAbsent(rs.getRelationId(), rs);
					}
				}
			}

			for (BinarySnapshotLoader.LoadedRelation lr : loaded.relationsById().values()) {
				String relName = relIdToName.getOrDefault(lr.relationId(), "rel_" + lr.relationId());
				if (effective.strictAlignment()) {
					if (lr.csrRowOffOffset() > 0 && (lr.csrRowOffOffset() % ALIGNMENT_128) != 0) {
						errors.add("Relation '" + relName + "' CSR row_offsets offset " + lr.csrRowOffOffset()
								+ " is not 128-byte aligned");
					}
					if (lr.csrColIdxOffset() > 0 && (lr.csrColIdxOffset() % ALIGNMENT_128) != 0) {
						errors.add("Relation '" + relName + "' CSR col_indices offset " + lr.csrColIdxOffset()
								+ " is not 128-byte aligned");
					}
				}

				RelationSnapshot rs = relIdToSnap.get(lr.relationId());
				if (rs == null) {
					continue;
				}
				BinarySnapshotLoader.LoadedDomain tgtDom = loaded.getDomain(lr.tgtDomainId());
				long tgtNodeCount = tgtDom != null ? tgtDom.nodeCount() : rs.getTargetNodeCount();

				validateCsrTopology(relName, rs, tgtNodeCount, errors);

				if (effective.deepAudit() && rs.hasCsc()) {
					BinarySnapshotLoader.LoadedDomain srcDom = loaded.getDomain(lr.srcDomainId());
					long srcNodeCount = srcDom != null ? srcDom.nodeCount() : rs.getNodeCount();
					validateCscTransposeConsistency(relName, rs, srcNodeCount, tgtNodeCount, errors);
				}
			}

			if (errors.isEmpty()) {
				passed.add("CSR row-offset monotonicity and target bounds verified across "
						+ loaded.relationsById().size() + " relation(s)");
				if (effective.deepAudit()) {
					passed.add("Deep audit CSC transpose consistency verified");
				}
			}
		} catch (Exception e) {
			errors.add("Snapshot load/verification failed: " + e.getMessage());
		}

		return new ValidationReport(snapshotPath, errors.isEmpty(), passed, errors, warnings);
	}

	private static void validateCsrTopology(String relName, RelationSnapshot rs, long tgtNodeCount,
			List<String> errors) {
		int nodeCount = rs.getNodeCount();
		long edgeCount = rs.getEdgeCount();
		MemorySegment rowOff = rs.getRowOffsetsSegment();
		MemorySegment colIdx = rs.getColumnTargetsSegment();

		if (rowOff == null || rowOff.equals(MemorySegment.NULL) || rowOff.byteSize() < (nodeCount + 1L) * 4L) {
			errors.add("Relation '" + relName + "' has missing or truncated CSR row_offsets segment");
			return;
		}
		int prev = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0);
		if (prev != 0) {
			errors.add("Relation '" + relName + "' CSR row_offsets[0] = " + prev + " (expected 0)");
		}
		for (int i = 1; i <= nodeCount; i++) {
			int cur = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
			if (cur < prev) {
				errors.add("Relation '" + relName + "' CSR row_offsets not monotonic at node " + i + ": " + cur + " < "
						+ prev);
				return;
			}
			prev = cur;
		}
		if (prev != edgeCount) {
			errors.add("Relation '" + relName + "' CSR row_offsets[" + nodeCount + "] = " + prev
					+ " does not match edgeCount " + edgeCount);
		}

		if (edgeCount > 0) {
			if (colIdx == null || colIdx.equals(MemorySegment.NULL) || colIdx.byteSize() < edgeCount * 4L) {
				errors.add("Relation '" + relName + "' has missing or truncated CSR col_indices segment");
				return;
			}
			for (long e = 0; e < edgeCount; e++) {
				int tgt = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
				if (tgt < 0 || (tgtNodeCount > 0 && tgt >= tgtNodeCount)) {
					errors.add("Relation '" + relName + "' CSR target out of bounds at edge " + e + ": tgt=" + tgt
							+ ", tgtNodeCount=" + tgtNodeCount);
					return;
				}
			}
		}
	}

	private static void validateCscTransposeConsistency(String relName, RelationSnapshot rs, long srcNodeCount,
			long tgtNodeCount, List<String> errors) {
		MemorySegment csrOff = rs.getRowOffsetsSegment();
		MemorySegment csrCol = rs.getColumnTargetsSegment();
		MemorySegment cscOff = rs.getCscRowOffsetsSegment();
		MemorySegment cscCol = rs.getCscColumnTargetsSegment();
		int cscNodes = rs.getCscNodeCount();

		if (cscOff == null || cscOff.equals(MemorySegment.NULL) || cscOff.byteSize() < (cscNodes + 1L) * 4L) {
			errors.add("Relation '" + relName + "' has missing or truncated CSC row_offsets segment");
			return;
		}
		int cscEdgeCount = cscOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, cscNodes);
		if (cscEdgeCount != rs.getEdgeCount()) {
			errors.add("Relation '" + relName + "' CSC edge count (" + cscEdgeCount + ") != CSR edge count ("
					+ rs.getEdgeCount() + ")");
			return;
		}

		// Build edge multiset from CSR and compare against CSC transpose
		Map<Long, Integer> edgeMultiset = new HashMap<>();
		int srcNodes = rs.getNodeCount();
		for (int u = 0; u < srcNodes; u++) {
			int start = csrOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
			int end = csrOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
			for (int e = start; e < end; e++) {
				int v = csrCol.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
				long pair = (((long) u) << 32) | (v & 0xFFFFFFFFL);
				edgeMultiset.merge(pair, 1, Integer::sum);
			}
		}

		for (int v = 0; v < cscNodes; v++) {
			int start = cscOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v);
			int end = cscOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v + 1);
			if (end < start) {
				errors.add("Relation '" + relName + "' CSC row_offsets not monotonic at target node " + v);
				return;
			}
			for (int e = start; e < end; e++) {
				int u = cscCol.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
				if (u < 0 || (srcNodeCount > 0 && u >= srcNodeCount)) {
					errors.add("Relation '" + relName + "' CSC source out of bounds at edge " + e + ": src=" + u);
					return;
				}
				long pair = (((long) u) << 32) | (v & 0xFFFFFFFFL);
				Integer remaining = edgeMultiset.get(pair);
				if (remaining == null || remaining <= 0) {
					errors.add("Relation '" + relName + "' CSC contains extra edge (" + u + " -> " + v
							+ ") not present in CSR");
					return;
				}
				if (remaining == 1) {
					edgeMultiset.remove(pair);
				} else {
					edgeMultiset.put(pair, remaining - 1);
				}
			}
		}

		if (!edgeMultiset.isEmpty()) {
			errors.add(
					"Relation '" + relName + "' CSC is missing " + edgeMultiset.size() + " edge key(s) present in CSR");
		}
	}

	// ---- Stats ----

	public record StatsOptions(double supernodeThreshold, boolean verbose) {
		public static StatsOptions defaults() {
			return new StatsOptions(RelationStatisticsCalculator.DEFAULT_SUPERNODE_ZSCORE_THRESHOLD, false);
		}
	}

	public record RelationStatsEntry(String name, int nodeCount, long edgeCount, int minOutDegree, int maxOutDegree,
			double avgOutDegree, double stdDevDegree, int p50Degree, int p90Degree, int p99Degree, double sparsity,
			long supernodeCount, String multiplicity) {
	}

	public record StatsReport(Path snapshotPath, Map<String, Long> domainCounts, List<RelationStatsEntry> relations,
			double supernodeThreshold) {

		public String toText(boolean verbose) {
			StringBuilder sb = new StringBuilder();
			sb.append("Impulse Snapshot Statistics\n");
			sb.append("  File                 : ").append(snapshotPath).append('\n');
			sb.append("  Supernode Z-Threshold: ").append(String.format(Locale.ROOT, "%.2f", supernodeThreshold))
					.append('\n');
			sb.append("  Domains (").append(domainCounts.size()).append("):\n");
			for (Map.Entry<String, Long> e : domainCounts.entrySet()) {
				sb.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append(" nodes\n");
			}
			sb.append("  Relations (").append(relations.size()).append("):\n");
			for (RelationStatsEntry r : relations) {
				sb.append(String.format(Locale.ROOT,
						"    %s: nodes=%d, edges=%d, deg[min=%d, avg=%.2f, p50=%d, p99=%d, max=%d], supernodes=%d, multiplicity=%s%n",
						r.name(), r.nodeCount(), r.edgeCount(), r.minOutDegree(), r.avgOutDegree(), r.p50Degree(),
						r.p99Degree(), r.maxOutDegree(), r.supernodeCount(), r.multiplicity()));
				if (verbose) {
					sb.append(String.format(Locale.ROOT, "      stdDev=%.3f, p90=%d, sparsity=%.4f%n", r.stdDevDegree(),
							r.p90Degree(), r.sparsity()));
				}
			}
			return sb.toString();
		}

		public String toJson() {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("file", snapshotPath.toString());
			root.put("supernodeThreshold", supernodeThreshold);
			root.put("domains", domainCounts);
			List<Map<String, Object>> relList = new ArrayList<>();
			for (RelationStatsEntry r : relations) {
				Map<String, Object> rm = new LinkedHashMap<>();
				rm.put("name", r.name());
				rm.put("nodeCount", r.nodeCount());
				rm.put("edgeCount", r.edgeCount());
				rm.put("minOutDegree", r.minOutDegree());
				rm.put("maxOutDegree", r.maxOutDegree());
				rm.put("avgOutDegree", r.avgOutDegree());
				rm.put("stdDevDegree", r.stdDevDegree());
				rm.put("p50Degree", r.p50Degree());
				rm.put("p90Degree", r.p90Degree());
				rm.put("p99Degree", r.p99Degree());
				rm.put("sparsity", r.sparsity());
				rm.put("supernodeCount", r.supernodeCount());
				rm.put("multiplicity", r.multiplicity());
				relList.add(rm);
			}
			root.put("relations", relList);
			return JsonWriter.toJson(root);
		}
	}

	public static StatsReport stats(Path snapshotPath, StatsOptions opts) throws IOException {
		Objects.requireNonNull(snapshotPath, "snapshotPath must not be null");
		StatsOptions effective = opts != null ? opts : StatsOptions.defaults();
		Map<String, Long> domainCounts = new LinkedHashMap<>();
		List<RelationStatsEntry> relEntries = new ArrayList<>();

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, false);
			List<BinarySnapshotLoader.LoadedDomain> sortedDoms = new ArrayList<>(loaded.domainsById().values());
			sortedDoms.sort(Comparator.comparingInt(BinarySnapshotLoader.LoadedDomain::domainId));
			for (BinarySnapshotLoader.LoadedDomain d : sortedDoms) {
				domainCounts.put(d.name(), d.nodeCount());
			}

			if (loaded.graph() != null) {
				for (Map.Entry<String, org.impulsegraph.api.RelationSnapshot> e : loaded.graph()
						.getAllRelationSnapshots().entrySet()) {
					org.impulsegraph.api.RelationSnapshot rs = e.getValue();
					RelationStatistics st = RelationStatisticsCalculator.calculate(rs, arena,
							effective.supernodeThreshold());
					int minDeg = computeMinOutDegree(rs);
					long supernodes = st.getSupernodeBitSet() != null ? st.getSupernodeBitSet().cardinality() : 0L;
					relEntries.add(new RelationStatsEntry(e.getKey(), st.getNodeCount(), st.getEdgeCount(), minDeg,
							st.getMaxDegree(), st.getAvgDegree(), st.getStdDevDegree(), st.getP50Degree(),
							st.getP90Degree(), st.getP99Degree(), st.getSparsity(), supernodes,
							st.getMultiplicity().name()));
				}
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to compute statistics for " + snapshotPath + ": " + e.getMessage(), e);
		}

		return new StatsReport(snapshotPath, Map.copyOf(domainCounts), List.copyOf(relEntries),
				effective.supernodeThreshold());
	}

	private static int computeMinOutDegree(org.impulsegraph.api.RelationSnapshot rs) {
		int nodeCount = rs.getNodeCount();
		if (nodeCount <= 0) {
			return 0;
		}
		MemorySegment rowOff = rs.getRowOffsetsSegment();
		if (rowOff == null || rowOff.equals(MemorySegment.NULL) || rowOff.byteSize() < (nodeCount + 1L) * 4L) {
			return 0;
		}
		int minDeg = Integer.MAX_VALUE;
		int prev = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0);
		for (int i = 1; i <= nodeCount; i++) {
			int next = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
			int deg = next - prev;
			if (deg < minDeg) {
				minDeg = deg;
			}
			prev = next;
		}
		return minDeg == Integer.MAX_VALUE ? 0 : minDeg;
	}

	private static HeaderInfo readRawHeader(Path snapshotPath) throws IOException {
		long fileSize = Files.size(snapshotPath);
		ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		try (FileChannel fc = FileChannel.open(snapshotPath, StandardOpenOption.READ)) {
			while (buf.hasRemaining()) {
				int read = fc.read(buf);
				if (read < 0) {
					break;
				}
			}
		}
		buf.flip();
		int magic = buf.getInt(0);
		short version = buf.getShort(4);
		int dataOffset = buf.getInt(6);
		int domainCount = Short.toUnsignedInt(buf.getShort(10));
		int relationCount = Short.toUnsignedInt(buf.getShort(12));
		long timestampMs = buf.getLong(14);
		long requiredFeatures = buf.getLong(22);
		long footerDirOffset = buf.getLong(30);
		long footerDirBytes = buf.getLong(38);
		boolean header4kAligned = dataOffset >= PAGE_SIZE_4K && (dataOffset % PAGE_SIZE_4K) == 0;
		boolean data128Aligned = dataOffset > 0 && (dataOffset % ALIGNMENT_128) == 0;
		return new HeaderInfo(magic, version, dataOffset, domainCount, relationCount, timestampMs, requiredFeatures,
				footerDirOffset, footerDirBytes, fileSize, header4kAligned, data128Aligned);
	}
}
