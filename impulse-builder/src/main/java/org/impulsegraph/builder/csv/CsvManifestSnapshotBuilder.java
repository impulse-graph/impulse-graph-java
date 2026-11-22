package org.impulsegraph.builder.csv;

import org.impulsegraph.builder.api.CompressionScheme;
import org.impulsegraph.builder.api.DataType;
import org.impulsegraph.builder.api.DomainDefinition;
import org.impulsegraph.builder.api.Nullability;
import org.impulsegraph.builder.api.PrimitiveWidth;
import org.impulsegraph.builder.api.RelationDefinition;
import org.impulsegraph.builder.api.SnapshotBuilder;
import org.impulsegraph.builder.api.Topology;
import org.impulsegraph.builder.internal.ExternalSortStaging;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java builder utility (zero external dependencies beyond JDK) that parses
 * an {@code e2e-csv-suite/manifest.json} specification and its referenced
 * {@code domains/*.csv} and {@code relations/*.csv} files to produce a
 * compliant Impulse Binary Snapshot ({@code .imps} v0.9.0).
 *
 * <p>
 * Provides two pure-Java build paths:
 * <ul>
 * <li>{@link #buildFullV09Snapshot(Path, Path)}: Writes the complete v0.9.0
 * binary layout including Section 2 Shared String Table, 16-byte Domain Catalog
 * entries, 128-byte Relation Directory entries, interleaved 44-byte Attribute
 * Descriptors (both relation attributes and domain attributes), 128-byte-padded
 * nullable validity bitmaps ({@code 0x80} typeCode flag), CSR/CSC segments, and
 * Footer metadata/CRC-64/SHA-256 trailer.</li>
 * <li>{@link #buildViaStreamingSnapshotWriter(Path, Path)}: Builds the snapshot
 * through the {@link SnapshotBuilder} ({@code StreamingSnapshotWriter}) API for
 * direct comparison and verification of {@code StreamingSnapshotWriter}
 * behavior (which currently writes {@code attr_count = 0} and {@code key_type =
 * 0x03}).</li>
 * </ul>
 */
public final class CsvManifestSnapshotBuilder {

	public static final int SPEC_MAGIC = 0x494D5053; // "IMPS"
	public static final short SPEC_VERSION = 0x0009;
	public static final int HEADER_SIZE = 4096;
	public static final int ALIGNMENT = 128;

	public static final byte TYPE_INT8 = 1;
	public static final byte TYPE_INT16 = 2;
	public static final byte TYPE_INT32 = 3;
	public static final byte TYPE_INT64 = 4;
	public static final byte TYPE_FLOAT32 = 5;
	public static final byte TYPE_FLOAT64 = 6;
	public static final byte TYPE_STRING = 7;
	public static final byte FLAG_NULLABLE = (byte) 0x80;

	private CsvManifestSnapshotBuilder() {
	}

	public record AttributeSpec(String name, String rawType, byte baseTypeCode, boolean nullable) {
		public byte encodedTypeCode() {
			return nullable ? (byte) (baseTypeCode | FLAG_NULLABLE) : baseTypeCode;
		}
	}

	public record DomainSpec(int id, String name, String keyType, int nodeCount, String csvRelPath,
			List<AttributeSpec> attributes) {
	}

	public record RelationSpec(int id, String name, int srcDomainId, int tgtDomainId, String encoding,
			boolean includeCsc, int edgeCount, String csvRelPath, List<AttributeSpec> attributes) {
	}

	public record ManifestSpec(String formatVersion, String suiteName, List<DomainSpec> domains,
			List<RelationSpec> relations) {
	}

	public record ParsedDomainData(DomainSpec spec, List<String> keys, Map<String, Integer> keyToDenseId,
			Map<String, List<String>> rawColumns) {
	}

	public record EdgeRow(int srcDenseId, int tgtDenseId, int originalRowIdx, Map<String, String> attrValues) {
	}

	public record ParsedRelationData(RelationSpec spec, List<EdgeRow> csrSortedEdges, int[] csrRowOffsets,
			int[] csrColTargets, int[] cscRowOffsets, int[] cscColTargets) {
	}

	/**
	 * Parses {@code manifest.json} or {@code manifest.yaml}/{@code .yml} at the
	 * given path.
	 */
	@SuppressWarnings("unchecked")
	public static ManifestSpec parseManifest(Path manifestPath) throws IOException {
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Path baseDir = manifestPath.getParent();
		Map<String, Object> root = parseManifestRoot(manifestPath);

		String formatVersion = String
				.valueOf(root.getOrDefault("version", root.getOrDefault("format_version", "0.9.0")));
		String suiteName = String.valueOf(root.getOrDefault("suite_name",
				root.getOrDefault("schema_name", root.getOrDefault("name", "e2e-csv-suite"))));

		List<DomainSpec> domains = new ArrayList<>();
		Map<String, Integer> domainNameToId = new HashMap<>();
		List<Object> domList = (List<Object>) root.getOrDefault("domains", List.of());
		for (int idx = 0; idx < domList.size(); idx++) {
			Map<String, Object> m = (Map<String, Object>) domList.get(idx);
			int id = m.containsKey("id") ? ((Number) m.get("id")).intValue() : idx;
			String name = (String) m.get("name");
			String keyType = String.valueOf(m.getOrDefault("key_type", m.getOrDefault("keyType", "string")));
			String csv = resolveFileField(m);
			int nodeCount = m.containsKey("node_count")
					? ((Number) m.get("node_count")).intValue()
					: countCsvDataRows(baseDir.resolve(csv));
			List<AttributeSpec> attrs = parseAttributeSpecs((List<Object>) m.getOrDefault("attributes", List.of()));
			domains.add(new DomainSpec(id, name, keyType, nodeCount, csv, attrs));
			domainNameToId.put(name, id);
		}
		domains.sort(Comparator.comparingInt(DomainSpec::id));

		List<RelationSpec> relations = new ArrayList<>();
		List<Object> relList = (List<Object>) root.getOrDefault("relations", List.of());
		for (int idx = 0; idx < relList.size(); idx++) {
			Map<String, Object> m = (Map<String, Object>) relList.get(idx);
			int id = m.containsKey("id") ? ((Number) m.get("id")).intValue() : idx;
			String name = (String) m.get("name");
			int srcDom = resolveDomainRef(m.getOrDefault("src_domain", m.getOrDefault("source_domain", m.get("from"))),
					domainNameToId);
			int tgtDom = resolveDomainRef(m.getOrDefault("tgt_domain", m.getOrDefault("target_domain", m.get("to"))),
					domainNameToId);
			String encoding = String.valueOf(m.getOrDefault("encoding", "CSR"));
			boolean includeCsc = resolveIncludeCsc(m);
			String csv = resolveFileField(m);
			int edgeCount = m.containsKey("edge_count")
					? ((Number) m.get("edge_count")).intValue()
					: countCsvDataRows(baseDir.resolve(csv));
			List<AttributeSpec> attrs = parseAttributeSpecs((List<Object>) m.getOrDefault("attributes", List.of()));
			relations.add(new RelationSpec(id, name, srcDom, tgtDom, encoding, includeCsc, edgeCount, csv, attrs));
		}
		relations.sort(Comparator.comparingInt(RelationSpec::id));

		return new ManifestSpec(formatVersion, suiteName, domains, relations);
	}

	private static int countCsvDataRows(Path csvPath) throws IOException {
		List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
		int count = 0;
		for (int i = 1; i < lines.size(); i++) {
			if (!lines.get(i).isBlank()) {
				count++;
			}
		}
		return count;
	}

	@SuppressWarnings("unchecked")
	private static List<AttributeSpec> parseAttributeSpecs(List<Object> rawAttrs) {
		List<AttributeSpec> out = new ArrayList<>(rawAttrs.size());
		for (Object obj : rawAttrs) {
			Map<String, Object> am = (Map<String, Object>) obj;
			String name = (String) am.get("name");
			String rawType = (String) am.get("type");
			boolean nullable = rawType.endsWith("?");
			String base = nullable ? rawType.substring(0, rawType.length() - 1) : rawType;
			byte code = switch (base.toLowerCase()) {
				case "int8", "bool", "boolean" -> TYPE_INT8;
				case "int16" -> TYPE_INT16;
				case "int32", "uint32" -> TYPE_INT32;
				case "int64", "uint64" -> TYPE_INT64;
				case "float32", "float" -> TYPE_FLOAT32;
				case "float64", "double" -> TYPE_FLOAT64;
				case "string" -> TYPE_STRING;
				default -> TYPE_FLOAT32;
			};
			out.add(new AttributeSpec(name, rawType, code, nullable));
		}
		return out;
	}

	/**
	 * Builds a streaming Spec {@code v0.10.0} ({@code 0x000A}) snapshot via
	 * {@link SnapshotBuilder} ({@code StreamingSnapshotWriter}) with zero full-file
	 * heap buffering. Supports CSV, TSV, and JSONL inputs as well as JSON and YAML
	 * manifests.
	 */
	public static void buildViaStreamingSnapshotWriter(Path manifestPath, Path outputPath) throws IOException {
		buildV10Snapshot(manifestPath, outputPath);
	}

	/**
	 * Canonical streaming Spec {@code v0.10.0} ({@code 0x000A}) snapshot builder
	 * from a JSON or YAML manifest referencing CSV, TSV, or JSONL domain and
	 * relation files.
	 */
	public static void buildV10Snapshot(Path manifestPath, Path outputPath) throws IOException {
		buildV10Snapshot(manifestPath, outputPath, null, 64 * 1024 * 1024L);
	}

	/**
	 * Canonical streaming Spec {@code v0.10.0} ({@code 0x000A}) snapshot builder
	 * with configurable disk staging directory and external-sort memory budget.
	 */
	public static void buildV10Snapshot(Path manifestPath, Path outputPath, Path stagingDir,
			long stagingMemoryLimitBytes) throws IOException {
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Objects.requireNonNull(outputPath, "outputPath must not be null");

		Path baseDir = manifestPath.toAbsolutePath().normalize().getParent();
		byte[] manifestRawBytes = Files.readAllBytes(manifestPath);
		String manifestSha256 = computeSha256Hex(manifestRawBytes);
		V10ManifestSpec manifest = parseV10Manifest(manifestPath, new String(manifestRawBytes, StandardCharsets.UTF_8));

		boolean createdTempStaging = false;
		Path effectiveStagingDir = stagingDir;
		if (effectiveStagingDir == null) {
			effectiveStagingDir = Files.createTempDirectory("impulse_csv_v10_");
			createdTempStaging = true;
		} else {
			Files.createDirectories(effectiveStagingDir);
		}
		long effectiveMemLimit = stagingMemoryLimitBytes > 0 ? stagingMemoryLimitBytes : 64 * 1024 * 1024L;
		List<Path> tempStagedFiles = new ArrayList<>();

		try {
			ExternalSortStaging sortStaging = new ExternalSortStaging(effectiveStagingDir, effectiveMemLimit);
			SnapshotBuilder builder = SnapshotBuilder.create().withVersion((short) 10)
					.withStagingDirectory(effectiveStagingDir).withStagingMemoryLimit(effectiveMemLimit);

			Map<Integer, V10DomainSpec> domainSpecById = new LinkedHashMap<>();
			Map<Integer, Map<String, Long>> domainKeyMaps = new LinkedHashMap<>();

			// 1. Stream each domain file in O(chunk) memory into staged column files
			for (V10DomainSpec d : manifest.domains()) {
				domainSpecById.put(d.id(), d);
				Path dataPath = baseDir.resolve(d.fileRelPath());
				Map<String, Long> keyToDense = new HashMap<>();

				// Ensure primary key column is present as a domain attribute
				List<V10AttrSpec> effectiveAttrs = new ArrayList<>();
				String pkAttrName = null;
				for (V10AttrSpec a : d.attributes()) {
					if (a.name().equalsIgnoreCase(d.idColumn()) || a.column().equalsIgnoreCase(d.idColumn())) {
						pkAttrName = a.name();
					}
					effectiveAttrs.add(a);
				}
				if (pkAttrName == null) {
					pkAttrName = d.idColumn();
					effectiveAttrs.add(0,
							new V10AttrSpec(pkAttrName, d.idColumn(), d.keyDataType(), Nullability.NON_NULL));
				}

				List<StreamingColumnStager.ColumnWriter> colWriters = new ArrayList<>(effectiveAttrs.size());
				for (int a = 0; a < effectiveAttrs.size(); a++) {
					V10AttrSpec attr = effectiveAttrs.get(a);
					Path colFile = Files.createTempFile(effectiveStagingDir, "dom_" + d.id() + "_col_" + a + "_",
							".bin");
					tempStagedFiles.add(colFile);
					colWriters
							.add(new StreamingColumnStager.ColumnWriter(colFile, attr.dataType(), attr.nullability()));
				}

				long nodeCount = 0L;
				try {
					nodeCount = streamDomainFile(dataPath, d, effectiveAttrs, colWriters, keyToDense);
				} finally {
					for (StreamingColumnStager.ColumnWriter cw : colWriters) {
						cw.close();
					}
				}

				domainKeyMaps.put(d.id(), keyToDense);

				DomainDefinition.Builder domBuilder = DomainDefinition.builder(nodeCount).idWidth(d.idWidth())
						.withPrimaryKeyIndex(true).primaryKeyAttribute(pkAttrName);
				for (int a = 0; a < effectiveAttrs.size(); a++) {
					domBuilder.addAttribute(effectiveAttrs.get(a).name(), colWriters.get(a).toAttributeDataSource());
				}
				builder.addDomain(d.name(), domBuilder.build());
			}

			// 2. Stream each relation file, externally sort edges + edge attributes, and
			// stage for StreamingSnapshotWriter
			for (V10RelationSpec r : manifest.relations()) {
				V10DomainSpec srcDom = domainSpecById.get(r.srcDomainId());
				V10DomainSpec tgtDom = domainSpecById.get(r.tgtDomainId());
				if (srcDom == null || tgtDom == null) {
					throw new IllegalArgumentException("Relation '" + r.name() + "' references unknown domain IDs: "
							+ r.srcDomainId() + " -> " + r.tgtDomainId());
				}

				Map<String, Long> srcKeys = domainKeyMaps.get(r.srcDomainId());
				Map<String, Long> tgtKeys = domainKeyMaps.get(r.tgtDomainId());
				Path dataPath = baseDir.resolve(r.fileRelPath());

				Path edgesFile = Files.createTempFile(effectiveStagingDir, "rel_" + r.id() + "_edges_", ".bin");
				tempStagedFiles.add(edgesFile);

				List<StreamingColumnStager.ColumnWriter> relColWriters = new ArrayList<>(r.attributes().size());
				for (int a = 0; a < r.attributes().size(); a++) {
					V10AttrSpec attr = r.attributes().get(a);
					Path colFile = Files.createTempFile(effectiveStagingDir, "rel_" + r.id() + "_col_" + a + "_",
							".bin");
					tempStagedFiles.add(colFile);
					relColWriters
							.add(new StreamingColumnStager.ColumnWriter(colFile, attr.dataType(), attr.nullability()));
				}

				int srcWidth = srcDom.idWidth().byteSize();
				int tgtWidth = tgtDom.idWidth().byteSize();
				StreamingColumnStager.EdgeFileWriter edgeWriter = new StreamingColumnStager.EdgeFileWriter(edgesFile,
						srcWidth, tgtWidth);

				try (ExternalSortStaging.EdgeRecordRunSorter sorter = sortStaging.newEdgeRecordSorter("rel_" + r.id(),
						r.attributes().size())) {
					streamRelationFileIntoSorter(dataPath, r, srcKeys, tgtKeys, sorter);
					sorter.drainSorted((srcId, tgtId, attrVals) -> {
						edgeWriter.writeEdge(srcId, tgtId);
						for (int a = 0; a < relColWriters.size(); a++) {
							Object v = (attrVals != null && a < attrVals.length) ? attrVals[a] : null;
							relColWriters.get(a).writeValue(v);
						}
					});
				} finally {
					edgeWriter.close();
					for (StreamingColumnStager.ColumnWriter cw : relColWriters) {
						cw.close();
					}
				}

				RelationDefinition.Builder relBuilder = RelationDefinition.builder(srcDom.name(), tgtDom.name());
				relBuilder.addTopology(Topology.CSR, CompressionScheme.RAW);
				if (r.includeCsc()) {
					relBuilder.addTopology(Topology.CSC, CompressionScheme.RAW);
				}
				relBuilder.dataSource(edgeWriter.toRelationDataSource());
				for (int a = 0; a < r.attributes().size(); a++) {
					relBuilder.addAttribute(r.attributes().get(a).name(), relColWriters.get(a).toAttributeDataSource());
				}
				builder.addRelation(r.name(), relBuilder.build());
			}

			// 3. Embed canonical schema provenance + legacy metadata keys and stream to
			// outputPath
			builder.withSchemaProvenance(manifest.schemaName(), manifest.schemaVersion(), manifestSha256);
			builder.addFooterMetadata("suite_name", manifest.schemaName());
			builder.addFooterMetadata("format_version", manifest.schemaVersion());
			builder.writeTo(outputPath);
		} finally {
			System.Logger log = System.getLogger(CsvManifestSnapshotBuilder.class.getName());
			for (Path f : tempStagedFiles) {
				try {
					Files.deleteIfExists(f);
				} catch (IOException e) {
					log.log(System.Logger.Level.DEBUG, "Failed to delete staged file " + f, e);
				}
			}
			if (createdTempStaging && effectiveStagingDir != null) {
				try {
					Files.deleteIfExists(effectiveStagingDir);
				} catch (IOException e) {
					log.log(System.Logger.Level.DEBUG, "Failed to delete temp staging dir " + effectiveStagingDir, e);
				}
			}
		}
	}

	private enum InputFormat {
		CSV, TSV, JSONL
	}

	private record V10AttrSpec(String name, String column, DataType dataType, Nullability nullability) {
	}

	private record V10DomainSpec(int id, String name, PrimitiveWidth idWidth, DataType keyDataType, String idColumn,
			String fileRelPath, InputFormat format, char delimiter, List<V10AttrSpec> attributes) {
	}

	private record V10RelationSpec(int id, String name, int srcDomainId, int tgtDomainId, boolean includeCsc,
			String srcColumn, String tgtColumn, String fileRelPath, InputFormat format, char delimiter,
			List<V10AttrSpec> attributes) {
	}

	private record V10ManifestSpec(String schemaName, String schemaVersion, List<V10DomainSpec> domains,
			List<V10RelationSpec> relations) {
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseManifestRoot(Path manifestPath) throws IOException {
		String text = Files.readString(manifestPath, StandardCharsets.UTF_8);
		return parseManifestRoot(manifestPath, text);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseManifestRoot(Path manifestPath, String text) {
		String lowerName = manifestPath.getFileName().toString().toLowerCase(Locale.ROOT);
		if (lowerName.endsWith(".yaml") || lowerName.endsWith(".yml")) {
			return (Map<String, Object>) MiniYamlParser.parse(text);
		}
		return (Map<String, Object>) MiniJsonParser.parse(text);
	}

	@SuppressWarnings("unchecked")
	private static V10ManifestSpec parseV10Manifest(Path manifestPath, String text) {
		Map<String, Object> root = parseManifestRoot(manifestPath, text);

		String defaultName = manifestPath.getFileName().toString();
		int dot = defaultName.lastIndexOf('.');
		if (dot > 0) {
			defaultName = defaultName.substring(0, dot);
		}
		String schemaName = String.valueOf(root.getOrDefault("schema_name",
				root.getOrDefault("name", root.getOrDefault("suite_name", defaultName))));
		String schemaVersion = String.valueOf(root.getOrDefault("schema_version",
				root.getOrDefault("version", root.getOrDefault("format_version", "0.10.0"))));
		String defaultDelimiterStr = root.containsKey("delimiter") ? String.valueOf(root.get("delimiter")) : null;

		List<V10DomainSpec> domains = new ArrayList<>();
		Map<String, Integer> domainNameToId = new HashMap<>();
		List<Object> domList = (List<Object>) root.getOrDefault("domains", List.of());
		for (int idx = 0; idx < domList.size(); idx++) {
			Map<String, Object> m = (Map<String, Object>) domList.get(idx);
			int id = m.containsKey("id") ? ((Number) m.get("id")).intValue() : idx;
			String name = String.valueOf(m.get("name"));
			PrimitiveWidth idWidth = parsePrimitiveWidth(m.getOrDefault("id_width", m.get("idWidth")));
			String keyTypeStr = String.valueOf(m.getOrDefault("key_type", m.getOrDefault("keyType", "string")));
			DataType keyDataType = parseKeyDataType(keyTypeStr);
			String idCol = String.valueOf(
					m.getOrDefault("id_column", m.getOrDefault("key_column", m.getOrDefault("primary_key", "id"))));
			String fileRel = resolveFileField(m);
			String delimStr = m.containsKey("delimiter") ? String.valueOf(m.get("delimiter")) : defaultDelimiterStr;
			String fmtStr = m.containsKey("format") ? String.valueOf(m.get("format")) : null;
			InputFormat fmt = detectInputFormat(fileRel, fmtStr, delimStr);
			char delim = resolveDelimiter(fmt, delimStr);
			List<V10AttrSpec> attrs = parseV10AttributeSpecs((List<Object>) m.getOrDefault("attributes", List.of()));
			domains.add(new V10DomainSpec(id, name, idWidth, keyDataType, idCol, fileRel, fmt, delim, attrs));
			domainNameToId.put(name, id);
		}
		domains.sort(Comparator.comparingInt(V10DomainSpec::id));

		List<V10RelationSpec> relations = new ArrayList<>();
		List<Object> relList = (List<Object>) root.getOrDefault("relations", List.of());
		for (int idx = 0; idx < relList.size(); idx++) {
			Map<String, Object> m = (Map<String, Object>) relList.get(idx);
			int id = m.containsKey("id") ? ((Number) m.get("id")).intValue() : idx;
			String name = String.valueOf(m.get("name"));
			int srcDom = resolveDomainRef(m.getOrDefault("src_domain", m.getOrDefault("source_domain", m.get("from"))),
					domainNameToId);
			int tgtDom = resolveDomainRef(m.getOrDefault("tgt_domain", m.getOrDefault("target_domain", m.get("to"))),
					domainNameToId);
			boolean includeCsc = resolveIncludeCsc(m);
			String srcCol = String.valueOf(m.getOrDefault("src_column", "src"));
			String tgtCol = String.valueOf(m.getOrDefault("tgt_column", "tgt"));
			String fileRel = resolveFileField(m);
			String delimStr = m.containsKey("delimiter") ? String.valueOf(m.get("delimiter")) : defaultDelimiterStr;
			String fmtStr = m.containsKey("format") ? String.valueOf(m.get("format")) : null;
			InputFormat fmt = detectInputFormat(fileRel, fmtStr, delimStr);
			char delim = resolveDelimiter(fmt, delimStr);
			List<V10AttrSpec> attrs = parseV10AttributeSpecs((List<Object>) m.getOrDefault("attributes", List.of()));
			relations.add(new V10RelationSpec(id, name, srcDom, tgtDom, includeCsc, srcCol, tgtCol, fileRel, fmt, delim,
					attrs));
		}
		relations.sort(Comparator.comparingInt(V10RelationSpec::id));

		return new V10ManifestSpec(schemaName, schemaVersion, domains, relations);
	}

	private static String resolveFileField(Map<String, Object> m) {
		Object v = m.get("file");
		if (v == null) {
			v = m.get("csv");
		}
		if (v == null) {
			v = m.get("path");
		}
		if (v == null) {
			throw new IllegalArgumentException("Manifest entry missing 'file'/'csv'/'path': " + m);
		}
		return String.valueOf(v);
	}

	private static int resolveDomainRef(Object ref, Map<String, Integer> domainNameToId) {
		if (ref instanceof Number n) {
			return n.intValue();
		}
		if (ref instanceof String s) {
			Integer byName = domainNameToId.get(s);
			if (byName != null) {
				return byName;
			}
			return Integer.parseInt(s.trim());
		}
		throw new IllegalArgumentException("Invalid domain reference in manifest: " + ref);
	}

	private static boolean resolveIncludeCsc(Map<String, Object> m) {
		if (m.containsKey("include_csc")) {
			return Boolean.TRUE.equals(m.get("include_csc"));
		}
		Object dir = m.get("direction");
		if (dir != null) {
			String d = String.valueOf(dir).trim().toLowerCase(Locale.ROOT);
			return "bidirectional".equals(d) || "undirected".equals(d) || "both".equals(d) || "csc".equals(d);
		}
		return false;
	}

	private static PrimitiveWidth parsePrimitiveWidth(Object raw) {
		if (raw == null) {
			return PrimitiveWidth.UINT32;
		}
		String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
		return switch (s) {
			case "uint16", "u16", "16", "2" -> PrimitiveWidth.UINT16;
			case "uint64", "u64", "64", "8" -> PrimitiveWidth.UINT64;
			default -> PrimitiveWidth.UINT32;
		};
	}

	private static DataType parseKeyDataType(String keyType) {
		if (keyType == null) {
			return DataType.STRING;
		}
		return switch (keyType.trim().toLowerCase(Locale.ROOT)) {
			case "int8", "uint8" -> DataType.I8;
			case "int16", "uint16" -> DataType.I16;
			case "int32", "uint32" -> DataType.I32;
			case "int64", "uint64" -> DataType.I64;
			default -> DataType.STRING;
		};
	}

	@SuppressWarnings("unchecked")
	private static List<V10AttrSpec> parseV10AttributeSpecs(List<Object> rawAttrs) {
		List<V10AttrSpec> out = new ArrayList<>(rawAttrs.size());
		for (Object obj : rawAttrs) {
			Map<String, Object> am = (Map<String, Object>) obj;
			String name = String.valueOf(am.get("name"));
			String column = am.containsKey("column") ? String.valueOf(am.get("column")) : name;
			String rawType = String.valueOf(am.get("type")).trim();
			boolean nullable = rawType.endsWith("?") || Boolean.TRUE.equals(am.get("nullable"));
			String baseType = rawType.endsWith("?") ? rawType.substring(0, rawType.length() - 1).trim() : rawType;
			DataType dt = parseV10DataType(baseType);
			out.add(new V10AttrSpec(name, column, dt, nullable ? Nullability.NULLABLE : Nullability.NON_NULL));
		}
		return out;
	}

	private static DataType parseV10DataType(String typeStr) {
		String lower = typeStr.trim().toLowerCase(Locale.ROOT);
		int openBracket = lower.indexOf('[');
		if (openBracket > 0 && lower.endsWith("]")) {
			String elemType = lower.substring(0, openBracket).trim();
			int dim = Integer.parseInt(lower.substring(openBracket + 1, lower.length() - 1).trim());
			DataType base = parseScalarDataType(elemType);
			return DataType.vector(base, dim);
		}
		return parseScalarDataType(lower);
	}

	private static DataType parseScalarDataType(String lower) {
		return switch (lower) {
			case "int8", "i8", "bool", "boolean" -> DataType.I8;
			case "int16", "i16" -> DataType.I16;
			case "int32", "i32", "uint32" -> DataType.I32;
			case "int64", "i64", "uint64" -> DataType.I64;
			case "float16", "f16" -> DataType.F16;
			case "float32", "f32", "float" -> DataType.F32;
			case "float64", "f64", "double" -> DataType.F64;
			case "timestamp_ms", "ts_ms" -> DataType.TIMESTAMP_MS;
			case "timestamp_ns", "ts_ns" -> DataType.TIMESTAMP_NS;
			case "string", "var_string", "utf8" -> DataType.STRING;
			case "bytes", "var_bytes" -> DataType.BYTES;
			default -> throw new IllegalArgumentException("Unsupported attribute type in manifest: " + lower);
		};
	}

	private static InputFormat detectInputFormat(String fileRel, String explicitFormat, String explicitDelim) {
		if (explicitFormat != null && !explicitFormat.isBlank()) {
			String f = explicitFormat.trim().toLowerCase(Locale.ROOT);
			if ("jsonl".equals(f) || "ndjson".equals(f)) {
				return InputFormat.JSONL;
			}
			if ("tsv".equals(f)) {
				return InputFormat.TSV;
			}
			if ("csv".equals(f)) {
				return InputFormat.CSV;
			}
		}
		String lower = fileRel.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) {
			return InputFormat.JSONL;
		}
		if (lower.endsWith(".tsv")) {
			return InputFormat.TSV;
		}
		if (explicitDelim != null && ("\t".equals(explicitDelim) || "\\t".equals(explicitDelim)
				|| "tab".equalsIgnoreCase(explicitDelim))) {
			return InputFormat.TSV;
		}
		return InputFormat.CSV;
	}

	private static char resolveDelimiter(InputFormat fmt, String explicitDelim) {
		if (explicitDelim != null && !explicitDelim.isEmpty()) {
			if ("\\t".equals(explicitDelim) || "tab".equalsIgnoreCase(explicitDelim)) {
				return '\t';
			}
			return explicitDelim.charAt(0);
		}
		return (fmt == InputFormat.TSV) ? '\t' : ',';
	}

	@SuppressWarnings("unchecked")
	private static long streamDomainFile(Path filePath, V10DomainSpec spec, List<V10AttrSpec> effectiveAttrs,
			List<StreamingColumnStager.ColumnWriter> writers, Map<String, Long> keyToDense) throws IOException {
		long denseId = 0L;
		try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
			if (spec.format() == InputFormat.JSONL) {
				String line;
				while ((line = br.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					Map<String, Object> row = (Map<String, Object>) MiniJsonParser.parse(line);
					Object rawKey = row.get(spec.idColumn());
					String key = rawKey != null ? String.valueOf(rawKey) : String.valueOf(denseId);
					keyToDense.put(key, denseId);
					for (int a = 0; a < effectiveAttrs.size(); a++) {
						V10AttrSpec attr = effectiveAttrs.get(a);
						Object val = row.get(attr.column());
						if (val == null && attr.column().equalsIgnoreCase(spec.idColumn())) {
							val = key;
						}
						writers.get(a).writeValue(val);
					}
					denseId++;
				}
				return denseId;
			}

			// CSV / TSV
			String headerLine = br.readLine();
			if (headerLine == null) {
				return 0L;
			}
			String[] headers = splitDelimitedLine(headerLine, spec.delimiter());
			Map<String, Integer> colIdxByName = new HashMap<>();
			for (int i = 0; i < headers.length; i++) {
				colIdxByName.put(headers[i], i);
				colIdxByName.putIfAbsent(headers[i].toLowerCase(Locale.ROOT), i);
			}
			int idColIdx = resolveColumnIndex(colIdxByName, spec.idColumn(), 0);
			int[] attrColIndices = new int[effectiveAttrs.size()];
			for (int a = 0; a < effectiveAttrs.size(); a++) {
				attrColIndices[a] = resolveColumnIndex(colIdxByName, effectiveAttrs.get(a).column(), -1);
			}

			String line;
			while ((line = br.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] cells = splitDelimitedLine(line, spec.delimiter());
				String key = (idColIdx >= 0 && idColIdx < cells.length) ? cells[idColIdx] : String.valueOf(denseId);
				keyToDense.put(key, denseId);
				for (int a = 0; a < effectiveAttrs.size(); a++) {
					int cIdx = attrColIndices[a];
					String cell = (cIdx >= 0 && cIdx < cells.length) ? cells[cIdx] : null;
					if (cell == null && effectiveAttrs.get(a).column().equalsIgnoreCase(spec.idColumn())) {
						cell = key;
					}
					writers.get(a).writeValue(cell);
				}
				denseId++;
			}
		}
		return denseId;
	}

	@SuppressWarnings("unchecked")
	private static void streamRelationFileIntoSorter(Path filePath, V10RelationSpec spec, Map<String, Long> srcKeys,
			Map<String, Long> tgtKeys, ExternalSortStaging.EdgeRecordRunSorter sorter) throws IOException {
		int attrCount = spec.attributes().size();
		long rowIdx = 0L;
		try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
			if (spec.format() == InputFormat.JSONL) {
				String line;
				while ((line = br.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					Map<String, Object> row = (Map<String, Object>) MiniJsonParser.parse(line);
					long srcId = resolveDenseIdLong(String.valueOf(row.get(spec.srcColumn())), srcKeys);
					long tgtId = resolveDenseIdLong(String.valueOf(row.get(spec.tgtColumn())), tgtKeys);
					String[] attrVals = attrCount > 0 ? new String[attrCount] : null;
					for (int a = 0; a < attrCount; a++) {
						Object val = row.get(spec.attributes().get(a).column());
						attrVals[a] = formatJsonAttrValue(val);
					}
					sorter.add(srcId, tgtId, rowIdx++, attrVals);
				}
				return;
			}

			// CSV / TSV
			String headerLine = br.readLine();
			if (headerLine == null) {
				return;
			}
			String[] headers = splitDelimitedLine(headerLine, spec.delimiter());
			Map<String, Integer> colIdxByName = new HashMap<>();
			for (int i = 0; i < headers.length; i++) {
				colIdxByName.put(headers[i], i);
				colIdxByName.putIfAbsent(headers[i].toLowerCase(Locale.ROOT), i);
			}
			int srcColIdx = resolveColumnIndex(colIdxByName, spec.srcColumn(), 0);
			int tgtColIdx = resolveColumnIndex(colIdxByName, spec.tgtColumn(), 1);
			int[] attrColIndices = new int[attrCount];
			for (int a = 0; a < attrCount; a++) {
				attrColIndices[a] = resolveColumnIndex(colIdxByName, spec.attributes().get(a).column(), -1);
			}

			String line;
			while ((line = br.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] cells = splitDelimitedLine(line, spec.delimiter());
				long srcId = resolveDenseIdLong(cells[srcColIdx], srcKeys);
				long tgtId = resolveDenseIdLong(cells[tgtColIdx], tgtKeys);
				String[] attrVals = attrCount > 0 ? new String[attrCount] : null;
				for (int a = 0; a < attrCount; a++) {
					int cIdx = attrColIndices[a];
					String cell = (cIdx >= 0 && cIdx < cells.length) ? cells[cIdx] : null;
					attrVals[a] = (cell != null && cell.isEmpty()
							&& spec.attributes().get(a).nullability() == Nullability.NULLABLE) ? null : cell;
				}
				sorter.add(srcId, tgtId, rowIdx++, attrVals);
			}
		}
	}

	private static String formatJsonAttrValue(Object val) {
		if (val == null) {
			return null;
		}
		if (val instanceof List<?> list) {
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(list.get(i));
			}
			sb.append(']');
			return sb.toString();
		}
		return String.valueOf(val);
	}

	private static int resolveColumnIndex(Map<String, Integer> colIdxByName, String colName, int fallback) {
		Integer idx = colIdxByName.get(colName);
		if (idx != null) {
			return idx;
		}
		idx = colIdxByName.get(colName.toLowerCase(Locale.ROOT));
		return idx != null ? idx : fallback;
	}

	private static long resolveDenseIdLong(String rawKey, Map<String, Long> keyMap) {
		if (rawKey == null) {
			throw new IllegalArgumentException("Null edge endpoint key");
		}
		Long mapped = keyMap.get(rawKey);
		if (mapped != null) {
			return mapped;
		}
		String trimmed = rawKey.trim();
		mapped = keyMap.get(trimmed);
		if (mapped != null) {
			return mapped;
		}
		return Long.parseLong(trimmed);
	}

	private static String[] splitDelimitedLine(String line, char delimiter) {
		List<String> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (inQuotes) {
				if (c == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						current.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					current.append(c);
				}
			} else if (c == '"' && current.isEmpty()) {
				inQuotes = true;
			} else if (c == delimiter) {
				tokens.add(current.toString().trim());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		tokens.add(current.toString().trim());
		return tokens.toArray(new String[0]);
	}

	private static String computeSha256Hex(byte[] data) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}

	/**
	 * Builds a full v0.9.0 compliant {@code .imps} snapshot (including all relation
	 * and domain attributes, 128-byte-padded nullable validity bitmaps, CSR/CSC
	 * topologies, and CRC/SHA-256 footer) and writes it to {@code outputPath}.
	 */
	public static void buildFullV09Snapshot(Path manifestPath, Path outputPath) throws IOException {
		byte[] bytes = buildFullV09SnapshotBytes(manifestPath);
		Files.write(outputPath, bytes);
	}

	/**
	 * Builds a full v0.9.0 compliant {@code .imps} snapshot in memory and returns
	 * the raw bytes.
	 */
	public static byte[] buildFullV09SnapshotBytes(Path manifestPath) throws IOException {
		Objects.requireNonNull(manifestPath, "manifestPath must not be null");
		Path baseDir = manifestPath.getParent();
		ManifestSpec manifest = parseManifest(manifestPath);

		Map<Integer, ParsedDomainData> domainDataById = new LinkedHashMap<>();
		for (DomainSpec d : manifest.domains()) {
			domainDataById.put(d.id(), parseDomainCsv(baseDir.resolve(d.csvRelPath()), d));
		}

		Map<Integer, ParsedRelationData> relationDataById = new LinkedHashMap<>();
		for (RelationSpec r : manifest.relations()) {
			ParsedDomainData srcDom = domainDataById.get(r.srcDomainId());
			ParsedDomainData tgtDom = domainDataById.get(r.tgtDomainId());
			relationDataById.put(r.id(), parseRelationCsv(baseDir.resolve(r.csvRelPath()), r, srcDom, tgtDom));
		}

		// Choose one primary relation per domain (preferring edgeCount == 0 if
		// available) so domain-level attributes are also persisted as v0.9.0
		// AttributeDescriptors in the snapshot binary under "<DomainName>.<attrName>".
		Map<Integer, Integer> primaryRelForDomain = new HashMap<>();
		for (RelationSpec r : manifest.relations()) {
			int srcDomId = r.srcDomainId();
			if (!primaryRelForDomain.containsKey(srcDomId) || r.edgeCount() == 0) {
				primaryRelForDomain.put(srcDomId, r.id());
			}
		}

		// 1. Build Shared String Pool (byte 0 = 0x00 for "")
		StringPoolBuilder stringPool = new StringPoolBuilder();
		for (DomainSpec d : manifest.domains()) {
			stringPool.intern(d.name());
			for (AttributeSpec a : d.attributes()) {
				stringPool.intern(a.name());
				stringPool.intern(d.name() + "." + a.name());
			}
			ParsedDomainData pdd = domainDataById.get(d.id());
			for (AttributeSpec a : d.attributes()) {
				if (a.baseTypeCode() == TYPE_STRING) {
					for (String val : pdd.rawColumns().getOrDefault(a.name(), List.of())) {
						if (val != null && !val.isEmpty()) {
							stringPool.intern(val);
						}
					}
				}
			}
		}

		for (RelationSpec r : manifest.relations()) {
			stringPool.intern(r.name());
			for (AttributeSpec a : r.attributes()) {
				stringPool.intern(a.name());
				if (a.baseTypeCode() == TYPE_STRING) {
					ParsedRelationData prd = relationDataById.get(r.id());
					for (EdgeRow row : prd.csrSortedEdges()) {
						String val = row.attrValues().get(a.name());
						if (val != null && !val.isEmpty()) {
							stringPool.intern(val);
						}
					}
				}
			}
		}

		byte[] strPoolBytes = stringPool.toByteArray();

		// 2. Compute Section 2 Directory Size
		int pos = HEADER_SIZE;
		pos += 4 + strPoolBytes.length;
		pos = align128(pos);

		int domainCatalogStart = pos;
		pos += manifest.domains().size() * 16;
		pos = align128(pos);

		int relationDirectoryStart = pos;
		Map<Integer, List<EncodedAttributePayload>> encodedAttrsByRel = new LinkedHashMap<>();

		for (RelationSpec r : manifest.relations()) {
			ParsedRelationData prd = relationDataById.get(r.id());
			ParsedDomainData srcDom = domainDataById.get(r.srcDomainId());
			List<EncodedAttributePayload> relAttrs = new ArrayList<>();

			// Relation's own attributes from manifest.json
			for (AttributeSpec a : r.attributes()) {
				List<String> colValues = new ArrayList<>(prd.csrSortedEdges().size());
				for (EdgeRow row : prd.csrSortedEdges()) {
					colValues.add(row.attrValues().getOrDefault(a.name(), ""));
				}
				relAttrs.add(
						encodeAttribute(a.name(), a, colValues, srcDom.spec().nodeCount(), r.edgeCount(), stringPool));
			}

			// If this relation is the primary relation for its srcDomain, also attach
			// "<DomainName>.<attrName>" descriptors so domain attributes are in the .imps
			// binary.
			if (Integer.valueOf(r.id()).equals(primaryRelForDomain.get(r.srcDomainId()))) {
				for (AttributeSpec da : srcDom.spec().attributes()) {
					List<String> domColValues = srcDom.rawColumns().getOrDefault(da.name(), List.of());
					String qualifiedName = srcDom.spec().name() + "." + da.name();
					relAttrs.add(encodeDomainAttributeOnRelation(qualifiedName, da, domColValues,
							srcDom.spec().nodeCount(), r.edgeCount(), stringPool));
				}
			}

			encodedAttrsByRel.put(r.id(), relAttrs);
			pos += 128 + (relAttrs.size() * 44);
		}
		pos = align128(pos);

		// 3. Lay out Data Payload Offsets (CSR, CSC, Attributes) with 128B alignment
		long[] csrRowOffOffsets = new long[manifest.relations().size()];
		long[] csrRowOffBytes = new long[manifest.relations().size()];
		long[] csrColIdxOffsets = new long[manifest.relations().size()];
		long[] csrColIdxBytes = new long[manifest.relations().size()];
		long[] cscRowOffOffsets = new long[manifest.relations().size()];
		long[] cscRowOffBytes = new long[manifest.relations().size()];
		long[] cscColIdxOffsets = new long[manifest.relations().size()];
		long[] cscColIdxBytes = new long[manifest.relations().size()];

		ByteArrayOutputStream dataPayload = new ByteArrayOutputStream();
		int dataRegionStart = pos;

		for (int i = 0; i < manifest.relations().size(); i++) {
			RelationSpec r = manifest.relations().get(i);
			ParsedRelationData prd = relationDataById.get(r.id());

			// CSR Row Offsets
			padStreamTo128(dataPayload, dataRegionStart);
			csrRowOffOffsets[i] = dataRegionStart + dataPayload.size();
			byte[] rowOffB = intsToLittleEndianBytes(prd.csrRowOffsets());
			csrRowOffBytes[i] = rowOffB.length;
			dataPayload.write(rowOffB);

			// CSR Column Targets
			padStreamTo128(dataPayload, dataRegionStart);
			csrColIdxOffsets[i] = dataRegionStart + dataPayload.size();
			byte[] colIdxB = intsToLittleEndianBytes(prd.csrColTargets());
			csrColIdxBytes[i] = colIdxB.length;
			dataPayload.write(colIdxB);

			// CSC Topology (if include_csc is true)
			if (r.includeCsc()) {
				padStreamTo128(dataPayload, dataRegionStart);
				cscRowOffOffsets[i] = dataRegionStart + dataPayload.size();
				byte[] cscRowB = intsToLittleEndianBytes(prd.cscRowOffsets());
				cscRowOffBytes[i] = cscRowB.length;
				dataPayload.write(cscRowB);

				padStreamTo128(dataPayload, dataRegionStart);
				cscColIdxOffsets[i] = dataRegionStart + dataPayload.size();
				byte[] cscColB = intsToLittleEndianBytes(prd.cscColTargets());
				cscColIdxBytes[i] = cscColB.length;
				dataPayload.write(cscColB);
			}

			// Attributes for relation i
			for (EncodedAttributePayload attrPayload : encodedAttrsByRel.get(r.id())) {
				padStreamTo128(dataPayload, dataRegionStart);
				attrPayload.dataOffset = dataRegionStart + dataPayload.size();
				attrPayload.dataBytes = attrPayload.combinedDataBytes.length;
				dataPayload.write(attrPayload.combinedDataBytes);
			}
		}

		padStreamTo128(dataPayload, dataRegionStart);

		// 4. Build Footer Metadata Directory + 16-byte Trailer
		byte[] footerDirBytes = buildFooterDirectory(manifest);
		dataPayload.write(footerDirBytes);
		ByteBuffer trailerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		trailerBuf.putLong(footerDirBytes.length + 16L);
		trailerBuf.putInt(SPEC_VERSION);
		trailerBuf.putInt(SPEC_MAGIC);
		dataPayload.write(trailerBuf.array());

		byte[] dataRegionArray = dataPayload.toByteArray();
		int totalFileSize = dataRegionStart + dataRegionArray.length;

		ByteBuffer fileBuf = ByteBuffer.allocate(totalFileSize).order(ByteOrder.LITTLE_ENDIAN);

		// 5. Write Page 0 Header (4096 bytes)
		fileBuf.position(0);
		fileBuf.putInt(SPEC_MAGIC);
		fileBuf.putShort(SPEC_VERSION);
		fileBuf.putInt(HEADER_SIZE); // data_offset = 4096
		fileBuf.putShort((short) manifest.domains().size());
		fileBuf.putShort((short) manifest.relations().size());
		fileBuf.putLong(1700000000000L); // deterministic timestamp_ms
		fileBuf.putLong(1L); // required_features
		fileBuf.putLong(0L); // footer_directory_offset (0 = directory starts at data_offset 4096)
		fileBuf.putLong(0L); // footer_directory_bytes
		for (int b = 0; b < 16; b++) {
			fileBuf.put((byte) (b + 1));
		}
		byte[] hdrFirst62 = new byte[0x3E];
		fileBuf.position(0);
		fileBuf.get(hdrFirst62);
		short crc16 = computeCrc16Ccitt(hdrFirst62, 0, hdrFirst62.length);
		fileBuf.position(0x3E);
		fileBuf.putShort(crc16);

		// 6. Write Section 2 String Pool
		fileBuf.position(HEADER_SIZE);
		fileBuf.putInt(strPoolBytes.length);
		fileBuf.put(strPoolBytes);

		// 7. Write Domain Catalog
		fileBuf.position(domainCatalogStart);
		for (DomainSpec d : manifest.domains()) {
			fileBuf.putShort((short) d.id());
			byte kt = switch (d.keyType().toLowerCase()) {
				case "uint32" -> (byte) 0x01;
				case "uint64" -> (byte) 0x02;
				default -> (byte) 0x03;
			};
			fileBuf.put(kt);
			fileBuf.put((byte) 0); // reserved
			fileBuf.putInt(stringPool.offsetOf(d.name()));
			fileBuf.putLong(d.nodeCount());
		}

		// 8. Write Relation Directory + Interleaved 44B Attribute Descriptors
		fileBuf.position(relationDirectoryStart);
		for (int i = 0; i < manifest.relations().size(); i++) {
			RelationSpec r = manifest.relations().get(i);
			DomainSpec srcDom = manifest.domains().get(r.srcDomainId());
			List<EncodedAttributePayload> relAttrs = encodedAttrsByRel.get(r.id());

			fileBuf.putShort((short) r.id());
			fileBuf.putShort((short) r.srcDomainId());
			fileBuf.putShort((short) r.tgtDomainId());
			byte encId = "COO".equalsIgnoreCase(r.encoding()) ? (byte) 6 : (byte) 0;
			fileBuf.put(encId);
			fileBuf.put((byte) 4); // node_id_width = 4
			fileBuf.put((byte) 4); // edge_index_width = 4
			fileBuf.put(new byte[3]); // reserved1
			fileBuf.putInt(stringPool.offsetOf(r.name()));
			fileBuf.putLong(srcDom.nodeCount());
			fileBuf.putLong(r.edgeCount());
			fileBuf.putLong(r.includeCsc() ? 1L : 0L); // section_features (bit 0 = CSC)
			fileBuf.putLong(csrRowOffOffsets[i]);
			fileBuf.putLong(csrRowOffBytes[i]);
			fileBuf.putLong(csrColIdxOffsets[i]);
			fileBuf.putLong(csrColIdxBytes[i]);
			fileBuf.putLong(cscRowOffOffsets[i]);
			fileBuf.putLong(cscRowOffBytes[i]);
			fileBuf.putLong(cscColIdxOffsets[i]);
			fileBuf.putLong(cscColIdxBytes[i]);
			fileBuf.putShort((short) relAttrs.size());
			fileBuf.put(new byte[22]); // reserved2

			for (EncodedAttributePayload attr : relAttrs) {
				fileBuf.putInt(attr.attrNameOff);
				fileBuf.put(attr.encodedTypeCode);
				fileBuf.put((byte) 0); // reserved1
				fileBuf.putShort((short) 0); // reserved2
				fileBuf.putInt(1); // dimension = 1
				fileBuf.putLong(attr.dataOffset);
				fileBuf.putLong(attr.dataBytes);
				fileBuf.putLong(0L); // offsOff
				fileBuf.putLong(0L); // offsBytes
			}
		}

		// 9. Write Data Region + Footer Directory + Trailer
		fileBuf.position(dataRegionStart);
		fileBuf.put(dataRegionArray);

		return fileBuf.array();
	}

	private static final class EncodedAttributePayload {
		final int attrNameOff;
		final byte encodedTypeCode;
		final byte[] combinedDataBytes;
		long dataOffset;
		long dataBytes;

		EncodedAttributePayload(int attrNameOff, byte encodedTypeCode, byte[] combinedDataBytes) {
			this.attrNameOff = attrNameOff;
			this.encodedTypeCode = encodedTypeCode;
			this.combinedDataBytes = combinedDataBytes;
		}
	}

	private static EncodedAttributePayload encodeAttribute(String attrName, AttributeSpec spec, List<String> rawValues,
			int nodeCount, int edgeCount, StringPoolBuilder stringPool) {
		int elemCount = edgeCount > 0 ? edgeCount : nodeCount;
		byte[] rawData = serializeColumnValues(spec.baseTypeCode(), rawValues, elemCount, stringPool);

		byte[] validityBlock = new byte[0];
		if (spec.nullable()) {
			long numBits = (edgeCount > 0) ? edgeCount : nodeCount;
			long rawBitmapBytes = ((numBits + 63) / 64) * 8;
			int paddedBitmapBytes = (int) ((rawBitmapBytes + 127) & ~127L);
			validityBlock = new byte[paddedBitmapBytes];
			for (int i = 0; i < Math.min(elemCount, rawValues.size()); i++) {
				String v = rawValues.get(i);
				if (v != null && !v.isEmpty()) {
					validityBlock[i / 8] |= (byte) (1 << (i % 8));
				}
			}
		}

		byte[] combined = new byte[validityBlock.length + rawData.length];
		System.arraycopy(validityBlock, 0, combined, 0, validityBlock.length);
		System.arraycopy(rawData, 0, combined, validityBlock.length, rawData.length);

		return new EncodedAttributePayload(stringPool.offsetOf(attrName), spec.encodedTypeCode(), combined);
	}

	private static EncodedAttributePayload encodeDomainAttributeOnRelation(String qualifiedAttrName, AttributeSpec spec,
			List<String> domValues, int domNodeCount, int relEdgeCount, StringPoolBuilder stringPool) {
		int carrierCount = Math.max(domNodeCount, relEdgeCount);
		byte[] rawData = serializeColumnValues(spec.baseTypeCode(), domValues, carrierCount, stringPool);

		byte[] validityBlock = new byte[0];
		if (spec.nullable()) {
			long numBits = (relEdgeCount > 0) ? relEdgeCount : domNodeCount;
			long rawBitmapBytes = ((numBits + 63) / 64) * 8;
			int paddedBitmapBytes = (int) ((rawBitmapBytes + 127) & ~127L);
			validityBlock = new byte[paddedBitmapBytes];
			for (int i = 0; i < Math.min(domNodeCount, domValues.size()); i++) {
				String v = domValues.get(i);
				if (v != null && !v.isEmpty()) {
					validityBlock[i / 8] |= (byte) (1 << (i % 8));
				}
			}
		}

		byte[] combined = new byte[validityBlock.length + rawData.length];
		System.arraycopy(validityBlock, 0, combined, 0, validityBlock.length);
		System.arraycopy(rawData, 0, combined, validityBlock.length, rawData.length);

		return new EncodedAttributePayload(stringPool.offsetOf(qualifiedAttrName), spec.encodedTypeCode(), combined);
	}

	private static byte[] serializeColumnValues(byte baseTypeCode, List<String> rawValues, int elemCount,
			StringPoolBuilder stringPool) {
		int elemWidth = switch (baseTypeCode) {
			case TYPE_INT8 -> 1;
			case TYPE_INT16 -> 2;
			case TYPE_INT32, TYPE_FLOAT32, TYPE_STRING -> 4;
			case TYPE_INT64, TYPE_FLOAT64 -> 8;
			default -> 4;
		};
		ByteBuffer buf = ByteBuffer.allocate(elemCount * elemWidth).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < elemCount; i++) {
			String raw = (i < rawValues.size() && rawValues.get(i) != null) ? rawValues.get(i).trim() : "";
			boolean empty = raw.isEmpty();
			switch (baseTypeCode) {
				case TYPE_INT8 -> buf.put(empty ? (byte) 0 : Byte.parseByte(raw));
				case TYPE_INT16 -> buf.putShort(empty ? (short) 0 : Short.parseShort(raw));
				case TYPE_INT32 -> buf.putInt(empty ? 0 : Integer.parseInt(raw));
				case TYPE_INT64 -> buf.putLong(empty ? 0L : Long.parseLong(raw));
				case TYPE_FLOAT32 -> buf.putFloat(empty ? 0.0f : Float.parseFloat(raw));
				case TYPE_FLOAT64 -> buf.putDouble(empty ? 0.0 : Double.parseDouble(raw));
				case TYPE_STRING -> buf.putInt(empty ? 0 : stringPool.offsetOf(raw));
				default -> buf.putFloat(0.0f);
			}
		}
		return buf.array();
	}

	public static ParsedDomainData parseDomainCsv(Path csvPath, DomainSpec spec) throws IOException {
		List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
		List<String> keys = new ArrayList<>(spec.nodeCount());
		Map<String, Integer> keyToDense = new LinkedHashMap<>();
		Map<String, List<String>> columns = new LinkedHashMap<>();
		for (AttributeSpec a : spec.attributes()) {
			columns.put(a.name(), new ArrayList<>(spec.nodeCount()));
		}

		if (lines.isEmpty()) {
			return new ParsedDomainData(spec, keys, keyToDense, columns);
		}

		String[] headers = splitCsvLine(lines.get(0));
		int denseId = 0;
		for (int lineIdx = 1; lineIdx < lines.size(); lineIdx++) {
			String line = lines.get(lineIdx);
			if (line.isBlank()) {
				continue;
			}
			String[] cells = splitCsvLine(line);
			String key = cells.length > 0 ? cells[0] : String.valueOf(denseId);
			keys.add(key);
			keyToDense.put(key, denseId);
			for (int col = 1; col < headers.length; col++) {
				String colName = headers[col];
				String val = col < cells.length ? cells[col] : "";
				columns.computeIfAbsent(colName, k -> new ArrayList<>()).add(val);
			}
			denseId++;
		}
		return new ParsedDomainData(spec, keys, keyToDense, columns);
	}

	public static ParsedRelationData parseRelationCsv(Path csvPath, RelationSpec spec, ParsedDomainData srcDom,
			ParsedDomainData tgtDom) throws IOException {
		List<String> lines = Files.readAllLines(csvPath, StandardCharsets.UTF_8);
		List<EdgeRow> rows = new ArrayList<>(spec.edgeCount());

		if (!lines.isEmpty()) {
			String[] headers = splitCsvLine(lines.get(0));
			int rowIdx = 0;
			for (int lineIdx = 1; lineIdx < lines.size(); lineIdx++) {
				String line = lines.get(lineIdx);
				if (line.isBlank()) {
					continue;
				}
				String[] cells = splitCsvLine(line);
				int srcId = resolveDenseId(cells[0], srcDom);
				int tgtId = resolveDenseId(cells[1], tgtDom);
				Map<String, String> attrs = new LinkedHashMap<>();
				for (int col = 2; col < headers.length; col++) {
					String val = col < cells.length ? cells[col] : "";
					attrs.put(headers[col], val);
				}
				rows.add(new EdgeRow(srcId, tgtId, rowIdx++, attrs));
			}
		}

		rows.sort(Comparator.comparingInt(EdgeRow::srcDenseId).thenComparingInt(EdgeRow::tgtDenseId)
				.thenComparingInt(EdgeRow::originalRowIdx));

		int srcN = srcDom.spec().nodeCount();
		int tgtN = tgtDom.spec().nodeCount();
		int edgeCount = rows.size();

		int[] csrRowOffsets = new int[srcN + 1];
		int[] csrColTargets = new int[edgeCount];
		for (int e = 0; e < edgeCount; e++) {
			EdgeRow er = rows.get(e);
			csrRowOffsets[er.srcDenseId() + 1]++;
			csrColTargets[e] = er.tgtDenseId();
		}
		for (int u = 0; u < srcN; u++) {
			csrRowOffsets[u + 1] += csrRowOffsets[u];
		}

		int[] cscRowOffsets = new int[0];
		int[] cscColTargets = new int[0];
		if (spec.includeCsc()) {
			List<EdgeRow> cscSorted = new ArrayList<>(rows);
			cscSorted.sort(Comparator.comparingInt(EdgeRow::tgtDenseId).thenComparingInt(EdgeRow::srcDenseId)
					.thenComparingInt(EdgeRow::originalRowIdx));
			cscRowOffsets = new int[tgtN + 1];
			cscColTargets = new int[edgeCount];
			for (int e = 0; e < edgeCount; e++) {
				EdgeRow er = cscSorted.get(e);
				cscRowOffsets[er.tgtDenseId() + 1]++;
				cscColTargets[e] = er.srcDenseId();
			}
			for (int v = 0; v < tgtN; v++) {
				cscRowOffsets[v + 1] += cscRowOffsets[v];
			}
		}

		return new ParsedRelationData(spec, rows, csrRowOffsets, csrColTargets, cscRowOffsets, cscColTargets);
	}

	private static int resolveDenseId(String rawKey, ParsedDomainData dom) {
		Integer mapped = dom.keyToDenseId().get(rawKey);
		if (mapped != null) {
			return mapped;
		}
		return Integer.parseInt(rawKey.trim());
	}

	private static String[] splitCsvLine(String line) {
		List<String> tokens = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) == ',') {
				tokens.add(line.substring(start, i).trim());
				start = i + 1;
			}
		}
		tokens.add(line.substring(start).trim());
		return tokens.toArray(new String[0]);
	}

	private static byte[] intsToLittleEndianBytes(int[] values) {
		ByteBuffer bb = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		for (int v : values) {
			bb.putInt(v);
		}
		return bb.array();
	}

	private static int align128(int pos) {
		return (pos + (ALIGNMENT - 1)) & ~(ALIGNMENT - 1);
	}

	private static void padStreamTo128(ByteArrayOutputStream out, int baseOffset) {
		int current = baseOffset + out.size();
		int aligned = align128(current);
		int pad = aligned - current;
		for (int i = 0; i < pad; i++) {
			out.write(0);
		}
	}

	private static byte[] buildFooterDirectory(ManifestSpec manifest) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("suite_name", manifest.suiteName().getBytes(StandardCharsets.UTF_8));
		entries.put("format_version", manifest.formatVersion().getBytes(StandardCharsets.UTF_8));

		int totalSize = 4;
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			byte[] kBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
			totalSize += 2 + kBytes.length + 4 + e.getValue().length;
		}
		ByteBuffer bb = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
		bb.putInt(entries.size());
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			byte[] kBytes = e.getKey().getBytes(StandardCharsets.UTF_8);
			bb.putShort((short) kBytes.length);
			bb.put(kBytes);
			bb.putInt(e.getValue().length);
			bb.put(e.getValue());
		}
		return bb.array();
	}

	public static short computeCrc16Ccitt(byte[] data, int offset, int length) {
		int crc = 0xFFFF;
		for (int i = offset; i < offset + length; i++) {
			crc ^= (data[i] & 0xFF) << 8;
			for (int b = 0; b < 8; b++) {
				if ((crc & 0x8000) != 0) {
					crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
				} else {
					crc = (crc << 1) & 0xFFFF;
				}
			}
		}
		return (short) crc;
	}

	public static long computeCrc64Ecma(byte[] data, int offset, int length) {
		long poly = 0xC96C5795D7870F42L;
		long crc = ~0L;
		for (int i = offset; i < offset + length; i++) {
			crc ^= (data[i] & 0xFFL);
			for (int b = 0; b < 8; b++) {
				if ((crc & 1L) != 0L) {
					crc = (crc >>> 1) ^ poly;
				} else {
					crc = (crc >>> 1);
				}
			}
		}
		return ~crc;
	}

	private static final class StringPoolBuilder {
		private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
		private final Map<String, Integer> offsets = new LinkedHashMap<>();

		StringPoolBuilder() {
			buf.write(0); // offset 0 = ""
			offsets.put("", 0);
		}

		int intern(String s) {
			if (s == null || s.isEmpty()) {
				return 0;
			}
			Integer existing = offsets.get(s);
			if (existing != null) {
				return existing;
			}
			int off = buf.size();
			byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
			buf.write(utf8, 0, utf8.length);
			buf.write(0);
			offsets.put(s, off);
			return off;
		}

		int offsetOf(String s) {
			return intern(s);
		}

		byte[] toByteArray() {
			return buf.toByteArray();
		}
	}

	private static final class InMemoryEdgeSource implements RelationDataSource {
		private final List<EdgeRow> edges;

		InMemoryEdgeSource(List<EdgeRow> edges) {
			this.edges = edges;
		}

		@Override
		public long getEdgeCount() {
			return edges.size();
		}

		@Override
		public EdgeChunkIterator getEdges() {
			return new EdgeChunkIterator() {
				private int index = 0;

				@Override
				public boolean hasNext() {
					return index < edges.size();
				}

				@Override
				public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
					int count = 0;
					while (index < edges.size() && count < limit) {
						EdgeRow e = edges.get(index++);
						srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, e.srcDenseId());
						tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, e.tgtDenseId());
						count++;
					}
					return count;
				}
			};
		}
	}

	/**
	 * Minimal zero-dependency recursive descent JSON parser for manifest.json.
	 */
	public static final class MiniJsonParser {
		private final String s;
		private int pos;

		private MiniJsonParser(String s) {
			this.s = s;
		}

		public static Object parse(String json) {
			return new MiniJsonParser(json).parseValue();
		}

		private void skipWs() {
			while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
				pos++;
			}
		}

		private Object parseValue() {
			skipWs();
			char c = s.charAt(pos);
			return switch (c) {
				case '{' -> parseObject();
				case '[' -> parseArray();
				case '"' -> parseString();
				case 't', 'f' -> parseBoolean();
				case 'n' -> parseNull();
				default -> parseNumber();
			};
		}

		private Map<String, Object> parseObject() {
			pos++; // '{'
			Map<String, Object> map = new LinkedHashMap<>();
			skipWs();
			if (s.charAt(pos) == '}') {
				pos++;
				return map;
			}
			while (true) {
				skipWs();
				String key = parseString();
				skipWs();
				if (s.charAt(pos) == ':') {
					pos++;
				}
				Object val = parseValue();
				map.put(key, val);
				skipWs();
				char c = s.charAt(pos++);
				if (c == '}') {
					break;
				}
			}
			return map;
		}

		private List<Object> parseArray() {
			pos++; // '['
			List<Object> list = new ArrayList<>();
			skipWs();
			if (s.charAt(pos) == ']') {
				pos++;
				return list;
			}
			while (true) {
				list.add(parseValue());
				skipWs();
				char c = s.charAt(pos++);
				if (c == ']') {
					break;
				}
			}
			return list;
		}

		private String parseString() {
			pos++; // '"'
			StringBuilder sb = new StringBuilder();
			while (pos < s.length()) {
				char c = s.charAt(pos++);
				if (c == '"') {
					break;
				}
				if (c == '\\' && pos < s.length()) {
					char esc = s.charAt(pos++);
					switch (esc) {
						case 'n' -> sb.append('\n');
						case 't' -> sb.append('\t');
						case 'r' -> sb.append('\r');
						case '"' -> sb.append('"');
						case '\\' -> sb.append('\\');
						default -> sb.append(esc);
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		private Boolean parseBoolean() {
			if (s.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			pos += 5;
			return Boolean.FALSE;
		}

		private Object parseNull() {
			pos += 4;
			return null;
		}

		private Number parseNumber() {
			int start = pos;
			boolean isFloat = false;
			if (s.charAt(pos) == '-') {
				pos++;
			}
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (Character.isDigit(c)) {
					pos++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					isFloat = true;
					pos++;
				} else {
					break;
				}
			}
			String tok = s.substring(start, pos);
			if (isFloat) {
				return Double.parseDouble(tok);
			}
			return Long.parseLong(tok);
		}
	}
}
