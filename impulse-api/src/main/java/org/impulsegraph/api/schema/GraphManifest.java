package org.impulsegraph.api.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Unified, zero-dependency AST for Impulse Graph schema manifests
 * ({@code .imps.schema.yaml}).
 */
public record GraphManifest(String packageName, String graphName, String version,
		Map<String, TablespaceDef> tablespaces, Map<String, DomainDef> domains, Map<String, RelationDef> relations,
		Map<String, VirtualRelationDef> virtualRelations) {

	public GraphManifest {
		graphName = (graphName == null || graphName.isBlank()) ? "ImpulseGraph" : graphName;
		version = (version == null || version.isBlank()) ? "1.0" : version;
		tablespaces = copyOrderedMap(tablespaces);
		domains = copyOrderedMap(domains);
		relations = copyOrderedMap(relations);
		virtualRelations = copyOrderedMap(virtualRelations);
	}

	/**
	 * Backwards-compatible 6-argument constructor without {@code packageName}.
	 */
	public GraphManifest(String graphName, String version, Map<String, TablespaceDef> tablespaces,
			Map<String, DomainDef> domains, Map<String, RelationDef> relations,
			Map<String, VirtualRelationDef> virtualRelations) {
		this(null, graphName, version, tablespaces, domains, relations, virtualRelations);
	}

	/**
	 * Computes a deterministic SHA-256 fingerprint over the canonical YAML
	 * serialization of this manifest.
	 */
	public String schemaFingerprintSha256() {
		String canonicalYaml = SchemaYamlCodec.toYaml(this);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] digest = md.digest(canonicalYaml.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
		}
	}

	public enum IdWidth {
		UINT16(2, "uint16"), UINT32(4, "uint32"), UINT64(8, "uint64");

		private final int byteWidth;
		private final String yamlName;

		IdWidth(int byteWidth, String yamlName) {
			this.byteWidth = byteWidth;
			this.yamlName = yamlName;
		}

		public int byteWidth() {
			return byteWidth;
		}

		public String yamlName() {
			return yamlName;
		}

		public static IdWidth fromString(String raw) {
			if (raw == null || raw.isBlank()) {
				return UINT32;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "uint16", "int16", "16", "2" -> UINT16;
				case "uint32", "int32", "32", "4" -> UINT32;
				case "uint64", "int64", "64", "8" -> UINT64;
				default -> throw new IllegalArgumentException("Unknown idWidth: " + raw);
			};
		}

		public static IdWidth fromByteWidth(int bytes) {
			return switch (bytes) {
				case 2 -> UINT16;
				case 4 -> UINT32;
				case 8 -> UINT64;
				default -> UINT32;
			};
		}
	}

	public enum KeyType {
		INT32((byte) 0x03, "int32"), INT64((byte) 0x04, "int64"), UUID((byte) 0x0C, "uuid"), STRING((byte) 0x0B,
				"string"), BYTES((byte) 0x0A, "bytes"), NONE((byte) 0x00, "none");

		private final byte binaryCode;
		private final String yamlName;

		KeyType(byte binaryCode, String yamlName) {
			this.binaryCode = binaryCode;
			this.yamlName = yamlName;
		}

		public byte binaryCode() {
			return binaryCode;
		}

		public String yamlName() {
			return yamlName;
		}

		public static KeyType fromString(String raw) {
			if (raw == null || raw.isBlank()) {
				return NONE;
			}
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "int32", "uint32", "int", "i32" -> INT32;
				case "int64", "uint64", "long", "i64" -> INT64;
				case "uuid", "uuid128" -> UUID;
				case "string", "varstring", "var_string", "str" -> STRING;
				case "bytes", "varbytes", "var_bytes", "fixed_bytes" -> BYTES;
				case "none", "null" -> NONE;
				default -> throw new IllegalArgumentException("Unknown keyType: " + raw);
			};
		}

		public static KeyType fromBinaryCode(byte code) {
			byte base = (byte) (code & 0x7F);
			return switch (base) {
				case 0x00 -> NONE;
				case 0x01, 0x03, 0x13 -> INT32;
				case 0x02, 0x04, 0x14 -> INT64;
				case 0x0A -> BYTES;
				case 0x0B -> STRING;
				case 0x0C -> UUID;
				default -> NONE;
			};
		}
	}

	public enum AttributeType {
		INT8((byte) 0x01, "int8", false, false), INT16((byte) 0x02, "int16", false, false), INT32((byte) 0x03, "int32",
				false, false), INT64((byte) 0x04, "int64", false, false), FLOAT16((byte) 0x05, "float16", false,
						false), FLOAT32((byte) 0x06, "float32", false, false), FLOAT64((byte) 0x07, "float64", false,
								false), BOOL((byte) 0x01, "bool", false, false), TIMESTAMP_MS((byte) 0x08,
										"timestamp_ms", false,
										false), TIMESTAMP_NS((byte) 0x09, "timestamp_ns", false, false), FIXED_BYTES(
												(byte) 0x0A, "fixed_bytes", false, true), FIXED_STRING((byte) 0x0A,
														"fixed_string", false, true), VAR_STRING((byte) 0x0B,
																"var_string", true, false), VAR_BYTES((byte) 0x0C,
																		"var_bytes", true, false), INTERVAL_SEC_32(
																				(byte) 0x0D, "interval_sec_32", false,
																				false), INTERVAL_MS_64((byte) 0x0E,
																						"interval_ms_64", false, false);

		private final byte baseTypeCode;
		private final String yamlName;
		private final boolean variableLength;
		private final boolean fixedWidthPayload;

		AttributeType(byte baseTypeCode, String yamlName, boolean variableLength, boolean fixedWidthPayload) {
			this.baseTypeCode = baseTypeCode;
			this.yamlName = yamlName;
			this.variableLength = variableLength;
			this.fixedWidthPayload = fixedWidthPayload;
		}

		public byte baseTypeCode() {
			return baseTypeCode;
		}

		public String yamlName() {
			return yamlName;
		}

		public boolean isVariableLength() {
			return variableLength;
		}

		public boolean isFixedWidthPayload() {
			return fixedWidthPayload;
		}

		/**
		 * Returns {@code true} if the binary snapshot base type code (masked with
		 * {@code 0x7F}) is wire-compatible with this logical attribute type.
		 */
		public boolean matchesBinaryTypeCode(byte rawTypeCode) {
			byte base = (byte) (rawTypeCode & 0x7F);
			return this.baseTypeCode == base;
		}

		public static AttributeType fromBinaryTypeCode(byte rawTypeCode) {
			byte base = (byte) (rawTypeCode & 0x7F);
			return switch (base) {
				case 0x01 -> INT8;
				case 0x02 -> INT16;
				case 0x03 -> INT32;
				case 0x04 -> INT64;
				case 0x05 -> FLOAT16;
				case 0x06 -> FLOAT32;
				case 0x07 -> FLOAT64;
				case 0x08 -> TIMESTAMP_MS;
				case 0x09 -> TIMESTAMP_NS;
				case 0x0A -> FIXED_BYTES;
				case 0x0B -> VAR_STRING;
				case 0x0C -> VAR_BYTES;
				case 0x0D -> INTERVAL_SEC_32;
				case 0x0E -> INTERVAL_MS_64;
				default -> throw new IllegalArgumentException(
						"Unknown binary attribute typeCode: 0x" + Integer.toHexString(base & 0xFF));
			};
		}

		public static AttributeType fromName(String raw) {
			Objects.requireNonNull(raw, "attribute type must not be null");
			String normalized = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_");
			return switch (normalized) {
				case "int8", "i8", "byte", "uint8" -> INT8;
				case "int16", "i16", "short", "uint16" -> INT16;
				case "int32", "i32", "int", "integer", "uint32" -> INT32;
				case "int64", "i64", "long", "uint64" -> INT64;
				case "float16", "f16", "fp16", "bfloat16" -> FLOAT16;
				case "float32", "f32", "float" -> FLOAT32;
				case "float64", "f64", "double" -> FLOAT64;
				case "bool", "boolean" -> BOOL;
				case "timestamp_ms", "timestampms", "ts_ms" -> TIMESTAMP_MS;
				case "timestamp_ns", "timestampns", "ts_ns" -> TIMESTAMP_NS;
				case "fixed_bytes", "fixedbytes", "uuid", "uuid128" -> FIXED_BYTES;
				case "fixed_string", "fixedstring" -> FIXED_STRING;
				case "var_string", "varstring", "string", "str", "utf8" -> VAR_STRING;
				case "var_bytes", "varbytes", "bytes", "binary" -> VAR_BYTES;
				case "interval_sec_32", "intervalsec32" -> INTERVAL_SEC_32;
				case "interval_ms_64", "intervalms64" -> INTERVAL_MS_64;
				default -> throw new IllegalArgumentException("Unknown AttributeType: " + raw);
			};
		}
	}

	public enum Direction {
		OUT, IN;

		public String yamlName() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Direction fromString(String raw) {
			Objects.requireNonNull(raw, "direction must not be null");
			return switch (raw.trim().toLowerCase(Locale.ROOT)) {
				case "out", "forward", "csr" -> OUT;
				case "in", "reverse", "csc" -> IN;
				default -> throw new IllegalArgumentException("Unknown Direction: " + raw);
			};
		}
	}

	public enum Cardinality {
		MANY_TO_MANY, MANY_TO_ONE, ONE_TO_MANY, ONE_TO_ONE;

		public String yamlName() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Cardinality fromString(String raw) {
			if (raw == null || raw.isBlank()) {
				return MANY_TO_MANY;
			}
			String norm = raw.trim().toLowerCase(Locale.ROOT).replace("-", "_").replace(":", "_");
			return switch (norm) {
				case "many_to_many", "m_n", "m_m", "n_m", "many" -> MANY_TO_MANY;
				case "many_to_one", "m_1", "n_1" -> MANY_TO_ONE;
				case "one_to_many", "1_m", "1_n" -> ONE_TO_MANY;
				case "one_to_one", "1_1" -> ONE_TO_ONE;
				default -> throw new IllegalArgumentException("Unknown Cardinality: " + raw);
			};
		}
	}

	public record TablespaceDef(String file, String description, String mode) {
	}

	public record AttributeDef(String name, AttributeType type, int dimension, boolean nullable, boolean indexed,
			boolean optional, String since, String deprecated) {

		public AttributeDef {
			Objects.requireNonNull(name, "attribute name must not be null");
			Objects.requireNonNull(type, "attribute type must not be null");
		}

		public AttributeDef(String name, AttributeType type) {
			this(name, type, 1, false, false, false, null, null);
		}

		public AttributeDef(String name, AttributeType type, int dimension, boolean nullable, boolean indexed) {
			this(name, type, dimension, nullable, indexed, false, null, null);
		}

		/**
		 * Parses shorthand type strings such as {@code "Int16?"},
		 * {@code "Float32[768]"}, {@code "FixedString(16)"}, {@code "FixedBytes(32)?"},
		 * {@code "STRING"}, or {@code "float"}.
		 */
		public static AttributeDef parseShorthand(String name, String rawSpec) {
			Objects.requireNonNull(name, "attribute name must not be null");
			Objects.requireNonNull(rawSpec, "attribute type spec must not be null");
			String spec = rawSpec.trim();
			boolean nullable = false;
			if (spec.endsWith("?")) {
				nullable = true;
				spec = spec.substring(0, spec.length() - 1).trim();
			}
			int dimension = 1;
			int bracketStart = spec.indexOf('[');
			int parenStart = spec.indexOf('(');
			if (bracketStart > 0 && spec.endsWith("]")) {
				String dimStr = spec.substring(bracketStart + 1, spec.length() - 1).trim();
				dimension = Integer.parseInt(dimStr);
				spec = spec.substring(0, bracketStart).trim();
			} else if (parenStart > 0 && spec.endsWith(")")) {
				String dimStr = spec.substring(parenStart + 1, spec.length() - 1).trim();
				dimension = Integer.parseInt(dimStr);
				spec = spec.substring(0, parenStart).trim();
			}
			String lower = spec.toLowerCase(Locale.ROOT);
			if ("uuid".equals(lower) || "uuid128".equals(lower)) {
				dimension = 16;
			}
			AttributeType type = AttributeType.fromName(spec);
			if (type.isVariableLength()) {
				dimension = 1;
			}
			return new AttributeDef(name, type, dimension, nullable, false, false, null, null);
		}

		/**
		 * Returns a concise canonical type string (e.g. {@code "float32[768]"},
		 * {@code "fixed_string(16)"}, {@code "int16?"}).
		 */
		public String canonicalTypeString() {
			String base;
			if (type == AttributeType.FIXED_STRING || type == AttributeType.FIXED_BYTES) {
				base = type.yamlName() + "(" + dimension + ")";
			} else if (!type.isVariableLength() && dimension > 1) {
				base = type.yamlName() + "[" + dimension + "]";
			} else {
				base = type.yamlName();
			}
			return nullable ? base + "?" : base;
		}
	}

	public record DomainDef(String tablespace, IdWidth idWidth, KeyType keyType, boolean optional, String since,
			String deprecated, Map<String, AttributeDef> attributes) {

		public DomainDef {
			idWidth = idWidth != null ? idWidth : IdWidth.UINT32;
			keyType = keyType != null ? keyType : KeyType.NONE;
			attributes = copyOrderedMap(attributes);
		}

		public DomainDef(String tablespace, IdWidth idWidth, KeyType keyType, Map<String, AttributeDef> attributes) {
			this(tablespace, idWidth, keyType, false, null, null, attributes);
		}

		/**
		 * Backwards-compatible constructor accepting legacy {@code Map<String, String>}
		 * attribute type mappings.
		 */
		public DomainDef(String tablespace, Map<String, String> legacyAttributes) {
			this(tablespace, IdWidth.UINT32, KeyType.NONE, false, null, null, parseLegacyAttributes(legacyAttributes));
		}

		/**
		 * Returns a legacy {@code Map<String, String>} view mapping attribute names to
		 * canonical type strings.
		 */
		public Map<String, String> attributeTypeNames() {
			return toLegacyTypeNames(attributes);
		}
	}

	public record RelationDef(String source, String target, String tablespace, EnumSet<Direction> directions,
			Cardinality cardinality, boolean symmetric, String inverseAlias, boolean optional, String since,
			String deprecated, Map<String, AttributeDef> attributes) {

		public RelationDef {
			directions = (directions == null || directions.isEmpty())
					? EnumSet.of(Direction.OUT)
					: EnumSet.copyOf(directions);
			cardinality = cardinality != null ? cardinality : Cardinality.MANY_TO_MANY;
			attributes = copyOrderedMap(attributes);
		}

		/**
		 * Backwards-compatible constructor accepting legacy {@code Map<String, String>}
		 * attribute type mappings.
		 */
		public RelationDef(String source, String target, String tablespace, Map<String, String> legacyAttributes) {
			this(source, target, tablespace, EnumSet.of(Direction.OUT), Cardinality.MANY_TO_MANY, false, null, false,
					null, null, parseLegacyAttributes(legacyAttributes));
		}

		/**
		 * Returns a defensive copy of the required traversal directions.
		 */
		@Override
		public EnumSet<Direction> directions() {
			return EnumSet.copyOf(directions);
		}

		public Map<String, String> attributeTypeNames() {
			return toLegacyTypeNames(attributes);
		}
	}

	public record VirtualRelationDef(List<String> components, String deprecated) {

		public VirtualRelationDef {
			components = components != null ? List.copyOf(components) : List.of();
		}

		public VirtualRelationDef(List<String> components) {
			this(components, null);
		}
	}

	private static <K, V> Map<K, V> copyOrderedMap(Map<K, V> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	private static Map<String, AttributeDef> parseLegacyAttributes(Map<String, String> legacy) {
		if (legacy == null || legacy.isEmpty()) {
			return Map.of();
		}
		Map<String, AttributeDef> parsed = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : legacy.entrySet()) {
			parsed.put(entry.getKey(), AttributeDef.parseShorthand(entry.getKey(), entry.getValue()));
		}
		return parsed;
	}

	private static Map<String, String> toLegacyTypeNames(Map<String, AttributeDef> attrs) {
		if (attrs == null || attrs.isEmpty()) {
			return Map.of();
		}
		Map<String, String> view = new LinkedHashMap<>();
		for (Map.Entry<String, AttributeDef> entry : attrs.entrySet()) {
			view.put(entry.getKey(), entry.getValue().canonicalTypeString());
		}
		return Collections.unmodifiableMap(view);
	}
}
