package org.impulsegraph.api.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Zero-dependency YAML parser and canonical YAML serializer for
 * {@link GraphManifest} ({@code .imps.schema.yaml}).
 *
 * <p>
 * Supports both canonical {@code domains:} and legacy {@code nodes:} sections,
 * arbitrary 2- or 4-space indentation, inline {@code #} comments outside
 * quotes, inline flow maps ({@code { type: string, length: 32 }}), inline flow
 * lists ({@code [out, in]}), block lists, shorthand type strings
 * ({@code "Int16?"}, {@code "Float32[768]"}, {@code "FixedString(16)"}), and
 * field aliases ({@code denseId}/{@code idWidth}, {@code key}/{@code keyType},
 * {@code direction}/{@code directions}, {@code package}/{@code packageName}).
 */
public final class SchemaYamlCodec {

	private SchemaYamlCodec() {
	}

	public static GraphManifest parse(Path yamlPath) throws IOException {
		Objects.requireNonNull(yamlPath, "yamlPath must not be null");
		return parse(Files.readString(yamlPath));
	}

	public static GraphManifest parse(String yamlContent) {
		if (yamlContent == null || yamlContent.isBlank()) {
			return new GraphManifest(null, "ImpulseGraph", "1.0", Map.of(), Map.of(), Map.of(), Map.of());
		}

		List<YamlLine> lines = preprocessLines(yamlContent);
		if (lines.isEmpty()) {
			return new GraphManifest(null, "ImpulseGraph", "1.0", Map.of(), Map.of(), Map.of(), Map.of());
		}

		int[] cursor = new int[]{0};
		Map<String, Object> root = parseBlockMap(lines, cursor, lines.get(0).indent());

		String packageName = asString(firstPresent(root, "package", "packageName", "package_name"));
		String graphName = asString(firstPresent(root, "graphName", "graph_name", "name"));
		String version = asString(firstPresent(root, "version", "schemaVersion", "schema_version"));

		Map<String, GraphManifest.TablespaceDef> tablespaces = parseTablespaces(asMap(root.get("tablespaces")));

		Map<String, GraphManifest.DomainDef> domains = new LinkedHashMap<>();
		domains.putAll(parseDomains(asMap(root.get("nodes"))));
		domains.putAll(parseDomains(asMap(root.get("domains"))));

		Map<String, GraphManifest.RelationDef> relations = parseRelations(asMap(root.get("relations")));

		Map<String, GraphManifest.VirtualRelationDef> virtualRelations = parseVirtualRelations(
				asMap(firstPresent(root, "virtual_relations", "virtualRelations")));

		return new GraphManifest(packageName, graphName, version, tablespaces, domains, relations, virtualRelations);
	}

	/**
	 * Serializes a {@link GraphManifest} into a deterministic, canonical YAML
	 * representation (sorted keys for stable SHA-256 fingerprinting).
	 */
	public static String toYaml(GraphManifest manifest) {
		Objects.requireNonNull(manifest, "manifest must not be null");
		StringBuilder sb = new StringBuilder();
		if (manifest.packageName() != null && !manifest.packageName().isBlank()) {
			sb.append("package: ").append(formatScalar(manifest.packageName())).append('\n');
		}
		sb.append("graphName: ").append(formatScalar(manifest.graphName())).append('\n');
		sb.append("version: ").append(formatScalar(manifest.version())).append('\n');

		if (!manifest.tablespaces().isEmpty()) {
			sb.append("\ntablespaces:\n");
			for (Map.Entry<String, GraphManifest.TablespaceDef> entry : new TreeMap<>(manifest.tablespaces())
					.entrySet()) {
				GraphManifest.TablespaceDef ts = entry.getValue();
				sb.append("  ").append(entry.getKey()).append(":\n");
				if (ts.file() != null) {
					sb.append("    file: ").append(formatScalar(ts.file())).append('\n');
				}
				if (ts.description() != null) {
					sb.append("    description: ").append(formatScalar(ts.description())).append('\n');
				}
				if (ts.mode() != null) {
					sb.append("    mode: ").append(formatScalar(ts.mode())).append('\n');
				}
			}
		}

		if (!manifest.domains().isEmpty()) {
			sb.append("\ndomains:\n");
			for (Map.Entry<String, GraphManifest.DomainDef> entry : new TreeMap<>(manifest.domains()).entrySet()) {
				GraphManifest.DomainDef d = entry.getValue();
				sb.append("  ").append(entry.getKey()).append(":\n");
				if (d.tablespace() != null) {
					sb.append("    tablespace: ").append(formatScalar(d.tablespace())).append('\n');
				}
				sb.append("    idWidth: ").append(d.idWidth().yamlName()).append('\n');
				sb.append("    keyType: ").append(d.keyType().yamlName()).append('\n');
				if (d.optional()) {
					sb.append("    optional: true\n");
				}
				if (d.since() != null) {
					sb.append("    since: ").append(formatScalar(d.since())).append('\n');
				}
				if (d.deprecated() != null) {
					sb.append("    deprecated: ").append(formatScalar(d.deprecated())).append('\n');
				}
				appendAttributes(sb, d.attributes());
			}
		}

		if (!manifest.relations().isEmpty()) {
			sb.append("\nrelations:\n");
			for (Map.Entry<String, GraphManifest.RelationDef> entry : new TreeMap<>(manifest.relations()).entrySet()) {
				GraphManifest.RelationDef r = entry.getValue();
				sb.append("  ").append(entry.getKey()).append(":\n");
				if (r.source() != null) {
					sb.append("    source: ").append(formatScalar(r.source())).append('\n');
				}
				if (r.target() != null) {
					sb.append("    target: ").append(formatScalar(r.target())).append('\n');
				}
				if (r.tablespace() != null) {
					sb.append("    tablespace: ").append(formatScalar(r.tablespace())).append('\n');
				}
				sb.append("    directions: [");
				List<String> dirs = new ArrayList<>();
				if (r.directions().contains(GraphManifest.Direction.OUT)) {
					dirs.add("out");
				}
				if (r.directions().contains(GraphManifest.Direction.IN)) {
					dirs.add("in");
				}
				sb.append(String.join(", ", dirs)).append("]\n");
				sb.append("    cardinality: ").append(r.cardinality().yamlName()).append('\n');
				if (r.symmetric()) {
					sb.append("    symmetric: true\n");
				}
				if (r.inverseAlias() != null) {
					sb.append("    inverseAlias: ").append(formatScalar(r.inverseAlias())).append('\n');
				}
				if (r.optional()) {
					sb.append("    optional: true\n");
				}
				if (r.since() != null) {
					sb.append("    since: ").append(formatScalar(r.since())).append('\n');
				}
				if (r.deprecated() != null) {
					sb.append("    deprecated: ").append(formatScalar(r.deprecated())).append('\n');
				}
				appendAttributes(sb, r.attributes());
			}
		}

		if (!manifest.virtualRelations().isEmpty()) {
			sb.append("\nvirtual_relations:\n");
			for (Map.Entry<String, GraphManifest.VirtualRelationDef> entry : new TreeMap<>(manifest.virtualRelations())
					.entrySet()) {
				GraphManifest.VirtualRelationDef vr = entry.getValue();
				sb.append("  ").append(entry.getKey()).append(":\n");
				if (vr.deprecated() != null) {
					sb.append("    deprecated: ").append(formatScalar(vr.deprecated())).append('\n');
				}
				sb.append("    components:\n");
				for (String comp : vr.components()) {
					sb.append("      - ").append(formatScalar(comp)).append('\n');
				}
			}
		}

		return sb.toString();
	}

	private static void appendAttributes(StringBuilder sb, Map<String, GraphManifest.AttributeDef> attributes) {
		if (attributes == null || attributes.isEmpty()) {
			return;
		}
		sb.append("    attributes:\n");
		for (Map.Entry<String, GraphManifest.AttributeDef> entry : new TreeMap<>(attributes).entrySet()) {
			GraphManifest.AttributeDef a = entry.getValue();
			sb.append("      ").append(entry.getKey()).append(": { type: ").append(a.type().yamlName());
			if (a.type().isFixedWidthPayload() || (!a.type().isVariableLength() && a.dimension() > 1)) {
				sb.append(", dimension: ").append(a.dimension());
			}
			if (a.nullable()) {
				sb.append(", nullable: true");
			}
			if (a.indexed()) {
				sb.append(", indexed: true");
			}
			if (a.optional()) {
				sb.append(", optional: true");
			}
			if (a.since() != null) {
				sb.append(", since: ").append(formatScalar(a.since()));
			}
			if (a.deprecated() != null) {
				sb.append(", deprecated: ").append(formatScalar(a.deprecated()));
			}
			sb.append(" }\n");
		}
	}

	private static String formatScalar(String value) {
		if (value == null) {
			return "null";
		}
		if (value.isEmpty() || value.contains(":") || value.contains("#") || value.contains("{") || value.contains("}")
				|| value.contains("[") || value.contains("]") || value.contains(",") || value.contains("\"")
				|| value.startsWith(" ") || value.endsWith(" ")) {
			return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		return value;
	}

	// ---- AST Builders ----

	private static Map<String, GraphManifest.TablespaceDef> parseTablespaces(Map<String, Object> raw) {
		Map<String, GraphManifest.TablespaceDef> result = new LinkedHashMap<>();
		if (raw == null) {
			return result;
		}
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			String name = entry.getKey();
			Map<String, Object> props = asMap(entry.getValue());
			String file = asString(props != null ? props.get("file") : null);
			if (file == null || file.isBlank()) {
				file = name + ".imps";
			}
			String description = asString(props != null ? props.get("description") : null);
			String mode = asString(props != null ? props.get("mode") : null);
			if (mode == null || mode.isBlank()) {
				mode = "read-write";
			}
			result.put(name, new GraphManifest.TablespaceDef(file, description, mode));
		}
		return result;
	}

	private static Map<String, GraphManifest.DomainDef> parseDomains(Map<String, Object> raw) {
		Map<String, GraphManifest.DomainDef> result = new LinkedHashMap<>();
		if (raw == null) {
			return result;
		}
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			String name = entry.getKey();
			Map<String, Object> props = asMap(entry.getValue());
			if (props == null) {
				result.put(name, new GraphManifest.DomainDef(null, GraphManifest.IdWidth.UINT32,
						GraphManifest.KeyType.NONE, false, null, null, Map.of()));
				continue;
			}
			String tablespace = asString(props.get("tablespace"));
			String idWidthRaw = asString(firstPresent(props, "idWidth", "id_width", "denseId", "dense_id"));
			GraphManifest.IdWidth idWidth = GraphManifest.IdWidth.fromString(idWidthRaw);
			String keyTypeRaw = asString(firstPresent(props, "keyType", "key_type", "key"));
			GraphManifest.KeyType keyType = GraphManifest.KeyType.fromString(keyTypeRaw);
			boolean optional = asBoolean(props.get("optional"), false);
			String since = asString(props.get("since"));
			String deprecated = asString(props.get("deprecated"));
			Map<String, GraphManifest.AttributeDef> attrs = parseAttributes(asMap(props.get("attributes")));
			result.put(name,
					new GraphManifest.DomainDef(tablespace, idWidth, keyType, optional, since, deprecated, attrs));
		}
		return result;
	}

	private static Map<String, GraphManifest.RelationDef> parseRelations(Map<String, Object> raw) {
		Map<String, GraphManifest.RelationDef> result = new LinkedHashMap<>();
		if (raw == null) {
			return result;
		}
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			String name = entry.getKey();
			Map<String, Object> props = asMap(entry.getValue());
			if (props == null) {
				continue;
			}
			String source = asString(firstPresent(props, "source", "src", "from"));
			String target = asString(firstPresent(props, "target", "tgt", "to"));
			String tablespace = asString(props.get("tablespace"));
			EnumSet<GraphManifest.Direction> directions = parseDirections(
					firstPresent(props, "directions", "direction"));
			GraphManifest.Cardinality cardinality = GraphManifest.Cardinality
					.fromString(asString(props.get("cardinality")));
			boolean symmetric = asBoolean(props.get("symmetric"), false);
			String inverseAlias = asString(firstPresent(props, "inverseAlias", "inverse_alias"));
			boolean optional = asBoolean(props.get("optional"), false);
			String since = asString(props.get("since"));
			String deprecated = asString(props.get("deprecated"));
			Map<String, GraphManifest.AttributeDef> attrs = parseAttributes(asMap(props.get("attributes")));

			result.put(name, new GraphManifest.RelationDef(source, target, tablespace, directions, cardinality,
					symmetric, inverseAlias, optional, since, deprecated, attrs));
		}
		return result;
	}

	private static Map<String, GraphManifest.VirtualRelationDef> parseVirtualRelations(Map<String, Object> raw) {
		Map<String, GraphManifest.VirtualRelationDef> result = new LinkedHashMap<>();
		if (raw == null) {
			return result;
		}
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			String name = entry.getKey();
			Map<String, Object> props = asMap(entry.getValue());
			if (props == null) {
				result.put(name, new GraphManifest.VirtualRelationDef(List.of(), null));
				continue;
			}
			List<String> components = asStringList(props.get("components"));
			String deprecated = asString(props.get("deprecated"));
			result.put(name, new GraphManifest.VirtualRelationDef(components, deprecated));
		}
		return result;
	}

	private static Map<String, GraphManifest.AttributeDef> parseAttributes(Map<String, Object> raw) {
		Map<String, GraphManifest.AttributeDef> result = new LinkedHashMap<>();
		if (raw == null) {
			return result;
		}
		for (Map.Entry<String, Object> entry : raw.entrySet()) {
			String attrName = entry.getKey();
			Object val = entry.getValue();
			if (val instanceof String str) {
				result.put(attrName, GraphManifest.AttributeDef.parseShorthand(attrName, str));
			} else if (val instanceof Map<?, ?>) {
				Map<String, Object> map = asMap(val);
				String rawType = asString(map.get("type"));
				if (rawType == null || rawType.isBlank()) {
					throw new IllegalArgumentException(
							"Attribute '" + attrName + "' map specification is missing 'type'");
				}
				GraphManifest.AttributeDef base = GraphManifest.AttributeDef.parseShorthand(attrName, rawType);
				GraphManifest.AttributeType type = base.type();
				int dimension = base.dimension();

				Object lengthOrDim = firstPresent(map, "dimension", "length", "size");
				if (lengthOrDim != null) {
					int explicitDim = asInt(lengthOrDim, dimension);
					dimension = explicitDim;
					// Promote string+length -> FIXED_STRING, bytes+length -> FIXED_BYTES
					String lowerType = rawType.trim().toLowerCase(Locale.ROOT);
					if (lowerType.equals("string") || lowerType.equals("str")) {
						type = GraphManifest.AttributeType.FIXED_STRING;
					} else if (lowerType.equals("bytes") || lowerType.equals("binary")) {
						type = GraphManifest.AttributeType.FIXED_BYTES;
					}
				}

				boolean nullable = asBoolean(map.get("nullable"), base.nullable());
				boolean indexed = asBoolean(map.get("indexed"), false);
				boolean optional = asBoolean(map.get("optional"), false);
				String since = asString(map.get("since"));
				String deprecated = asString(map.get("deprecated"));

				result.put(attrName, new GraphManifest.AttributeDef(attrName, type, dimension, nullable, indexed,
						optional, since, deprecated));
			}
		}
		return result;
	}

	private static EnumSet<GraphManifest.Direction> parseDirections(Object raw) {
		if (raw == null) {
			return EnumSet.of(GraphManifest.Direction.OUT);
		}
		EnumSet<GraphManifest.Direction> set = EnumSet.noneOf(GraphManifest.Direction.class);
		if (raw instanceof String s) {
			if (!s.isBlank()) {
				set.add(GraphManifest.Direction.fromString(s));
			}
		} else if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item != null && !item.toString().isBlank()) {
					set.add(GraphManifest.Direction.fromString(item.toString()));
				}
			}
		}
		return set.isEmpty() ? EnumSet.of(GraphManifest.Direction.OUT) : set;
	}

	// ---- Lightweight Zero-Dependency YAML Parser ----

	private record YamlLine(int indent, String content) {
	}

	private static List<YamlLine> preprocessLines(String yaml) {
		List<YamlLine> result = new ArrayList<>();
		String[] rawLines = yaml.split("\\R");
		for (String rawLine : rawLines) {
			String stripped = stripInlineComment(rawLine);
			if (stripped.trim().isEmpty()) {
				continue;
			}
			int indent = countLeadingSpaces(stripped);
			String content = stripped.trim();
			if (content.equals("---") || content.equals("...")) {
				continue;
			}
			result.add(new YamlLine(indent, content));
		}
		return result;
	}

	private static String stripInlineComment(String line) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\\' && inDouble && i + 1 < line.length()) {
				i++;
				continue;
			}
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == '#' && !inSingle && !inDouble) {
				if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
					return line.substring(0, i);
				}
			}
		}
		return line;
	}

	private static int countLeadingSpaces(String line) {
		int count = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == ' ') {
				count++;
			} else if (c == '\t') {
				count += 2;
			} else {
				break;
			}
		}
		return count;
	}

	private static Map<String, Object> parseBlockMap(List<YamlLine> lines, int[] cursor, int baseIndent) {
		Map<String, Object> map = new LinkedHashMap<>();
		while (cursor[0] < lines.size()) {
			YamlLine line = lines.get(cursor[0]);
			if (line.indent() < baseIndent) {
				break;
			}
			if (line.indent() > baseIndent) {
				// Unexpected extra indent at map level; skip defensively
				cursor[0]++;
				continue;
			}
			String content = line.content();
			if (content.startsWith("- ")) {
				break;
			}
			int colonIdx = findKeyValueColon(content);
			if (colonIdx < 0) {
				cursor[0]++;
				continue;
			}
			String key = unquote(content.substring(0, colonIdx).trim());
			String rest = content.substring(colonIdx + 1).trim();
			cursor[0]++;

			if (!rest.isEmpty()) {
				map.put(key, parseInlineValue(rest));
			} else if (cursor[0] < lines.size() && lines.get(cursor[0]).indent() > baseIndent) {
				YamlLine next = lines.get(cursor[0]);
				if (next.content().startsWith("-")
						&& (next.content().length() == 1 || Character.isWhitespace(next.content().charAt(1)))) {
					map.put(key, parseBlockList(lines, cursor, next.indent()));
				} else {
					map.put(key, parseBlockMap(lines, cursor, next.indent()));
				}
			} else {
				map.put(key, null);
			}
		}
		return map;
	}

	private static List<Object> parseBlockList(List<YamlLine> lines, int[] cursor, int baseIndent) {
		List<Object> list = new ArrayList<>();
		while (cursor[0] < lines.size()) {
			YamlLine line = lines.get(cursor[0]);
			if (line.indent() < baseIndent) {
				break;
			}
			if (line.indent() > baseIndent) {
				cursor[0]++;
				continue;
			}
			String content = line.content();
			if (!content.startsWith("-")) {
				break;
			}
			String rest = content.length() > 1 ? content.substring(1).trim() : "";
			cursor[0]++;
			if (!rest.isEmpty()) {
				list.add(parseInlineValue(rest));
			}
		}
		return list;
	}

	private static Object parseInlineValue(String raw) {
		String s = raw.trim();
		if (s.startsWith("{") && s.endsWith("}")) {
			return parseFlowMap(s.substring(1, s.length() - 1));
		}
		if (s.startsWith("[") && s.endsWith("]")) {
			return parseFlowList(s.substring(1, s.length() - 1));
		}
		return unquote(s);
	}

	private static Map<String, Object> parseFlowMap(String inner) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (String part : splitTopLevel(inner, ',')) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int colonIdx = findKeyValueColon(trimmed);
			if (colonIdx > 0) {
				String key = unquote(trimmed.substring(0, colonIdx).trim());
				String val = trimmed.substring(colonIdx + 1).trim();
				map.put(key, parseInlineValue(val));
			}
		}
		return map;
	}

	private static List<Object> parseFlowList(String inner) {
		List<Object> list = new ArrayList<>();
		for (String part : splitTopLevel(inner, ',')) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				list.add(parseInlineValue(trimmed));
			}
		}
		return list;
	}

	private static List<String> splitTopLevel(String s, char delimiter) {
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;
		int braceDepth = 0;
		int bracketDepth = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && inDouble && i + 1 < s.length()) {
				current.append(c).append(s.charAt(++i));
				continue;
			}
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (!inSingle && !inDouble) {
				if (c == '{') {
					braceDepth++;
				} else if (c == '}') {
					braceDepth--;
				} else if (c == '[') {
					bracketDepth++;
				} else if (c == ']') {
					bracketDepth--;
				} else if (c == delimiter && braceDepth == 0 && bracketDepth == 0) {
					parts.add(current.toString());
					current.setLength(0);
					continue;
				}
			}
			current.append(c);
		}
		parts.add(current.toString());
		return parts;
	}

	private static int findKeyValueColon(String s) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && inDouble && i + 1 < s.length()) {
				i++;
				continue;
			}
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				inDouble = !inDouble;
			} else if (c == ':' && !inSingle && !inDouble) {
				if (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1))) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String unquote(String s) {
		if (s == null) {
			return null;
		}
		String trimmed = s.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
		}
		if (trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
			return trimmed.substring(1, trimmed.length() - 1).replace("''", "'");
		}
		return trimmed;
	}

	// ---- Type Conversion Helpers ----

	private static Object firstPresent(Map<String, Object> map, String... keys) {
		if (map == null) {
			return null;
		}
		for (String key : keys) {
			if (map.containsKey(key) && map.get(key) != null) {
				return map.get(key);
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object obj) {
		if (obj instanceof Map<?, ?> m) {
			return (Map<String, Object>) m;
		}
		return null;
	}

	private static String asString(Object obj) {
		if (obj == null) {
			return null;
		}
		String s = obj.toString();
		return "null".equalsIgnoreCase(s) ? null : s;
	}

	private static boolean asBoolean(Object obj, boolean defaultValue) {
		if (obj == null) {
			return defaultValue;
		}
		String s = obj.toString().trim().toLowerCase(Locale.ROOT);
		if ("true".equals(s) || "yes".equals(s)) {
			return true;
		}
		if ("false".equals(s) || "no".equals(s)) {
			return false;
		}
		return defaultValue;
	}

	private static int asInt(Object obj, int defaultValue) {
		if (obj == null) {
			return defaultValue;
		}
		return Integer.parseInt(obj.toString().trim());
	}

	private static List<String> asStringList(Object obj) {
		if (obj == null) {
			return List.of();
		}
		if (obj instanceof List<?> list) {
			List<String> out = new ArrayList<>(list.size());
			for (Object item : list) {
				if (item != null) {
					out.add(item.toString());
				}
			}
			return out;
		}
		return List.of(obj.toString());
	}
}
