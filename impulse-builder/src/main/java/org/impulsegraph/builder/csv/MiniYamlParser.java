package org.impulsegraph.builder.csv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency YAML subset parser for Impulse manifest files
 * ({@code .yaml} / {@code .yml}). Supports indentation-based mappings,
 * sequences, list-of-maps ({@code - key: value}), quoted strings, inline flow
 * collections ({@code [...]}, {@code {...}}), comments, booleans, null, and
 * numbers.
 */
final class MiniYamlParser {

	private record Line(int indent, String content, int lineNum) {
	}

	private final List<Line> lines;
	private int cursor = 0;

	private MiniYamlParser(List<Line> lines) {
		this.lines = lines;
	}

	static Object parse(String yamlText) {
		List<Line> parsedLines = preprocess(yamlText);
		if (parsedLines.isEmpty()) {
			return new LinkedHashMap<String, Object>();
		}
		MiniYamlParser parser = new MiniYamlParser(parsedLines);
		return parser.parseBlock(parsedLines.get(0).indent());
	}

	private static List<Line> preprocess(String yamlText) {
		List<Line> result = new ArrayList<>();
		String[] rawLines = yamlText.split("\\r?\\n", -1);
		for (int i = 0; i < rawLines.length; i++) {
			String stripped = stripComment(rawLines[i]);
			if (stripped.isBlank()) {
				continue;
			}
			String trimmed = stripped.trim();
			if ("---".equals(trimmed) || "...".equals(trimmed)) {
				continue;
			}
			int indent = 0;
			while (indent < stripped.length() && stripped.charAt(indent) == ' ') {
				indent++;
			}
			result.add(new Line(indent, stripped.substring(indent).trim(), i + 1));
		}
		return result;
	}

	private static String stripComment(String line) {
		boolean inSingle = false;
		boolean inDouble = false;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				if (i == 0 || line.charAt(i - 1) != '\\') {
					inDouble = !inDouble;
				}
			} else if (c == '#' && !inSingle && !inDouble) {
				if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
					return line.substring(0, i);
				}
			}
		}
		return line;
	}

	private Object parseBlock(int expectedIndent) {
		if (cursor >= lines.size()) {
			return null;
		}
		Line first = lines.get(cursor);
		if (isSequenceItem(first.content())) {
			return parseSequence(expectedIndent);
		}
		return parseMapping(expectedIndent);
	}

	private static boolean isSequenceItem(String content) {
		return content.equals("-") || content.startsWith("- ");
	}

	private List<Object> parseSequence(int seqIndent) {
		List<Object> list = new ArrayList<>();
		while (cursor < lines.size()) {
			Line line = lines.get(cursor);
			if (line.indent() < seqIndent || !isSequenceItem(line.content())) {
				break;
			}
			if (line.indent() > seqIndent) {
				throw new IllegalArgumentException("Unexpected indentation in YAML sequence at line " + line.lineNum());
			}
			cursor++;
			String rest = line.content().length() > 1 ? line.content().substring(1).trim() : "";
			if (rest.isEmpty()) {
				if (cursor < lines.size() && lines.get(cursor).indent() > seqIndent) {
					list.add(parseBlock(lines.get(cursor).indent()));
				} else {
					list.add(null);
				}
			} else {
				int colonIdx = findMappingColon(rest);
				if (colonIdx >= 0) {
					// Inline mapping entry inside sequence item: "- key: val"
					int itemIndent = seqIndent + 2;
					Map<String, Object> map = new LinkedHashMap<>();
					parseKeyValueIntoMap(rest, colonIdx, itemIndent, map);
					while (cursor < lines.size() && lines.get(cursor).indent() >= itemIndent) {
						Line next = lines.get(cursor);
						if (isSequenceItem(next.content()) && next.indent() == seqIndent) {
							break;
						}
						if (next.indent() != itemIndent) {
							break;
						}
						int nextColon = findMappingColon(next.content());
						if (nextColon < 0) {
							break;
						}
						cursor++;
						parseKeyValueIntoMap(next.content(), nextColon, itemIndent, map);
					}
					list.add(map);
				} else {
					list.add(parseScalarOrFlow(rest));
				}
			}
		}
		return list;
	}

	private Map<String, Object> parseMapping(int mapIndent) {
		Map<String, Object> map = new LinkedHashMap<>();
		while (cursor < lines.size()) {
			Line line = lines.get(cursor);
			if (line.indent() < mapIndent) {
				break;
			}
			if (line.indent() > mapIndent) {
				throw new IllegalArgumentException("Unexpected indentation in YAML mapping at line " + line.lineNum());
			}
			if (isSequenceItem(line.content())) {
				break;
			}
			int colonIdx = findMappingColon(line.content());
			if (colonIdx < 0) {
				throw new IllegalArgumentException(
						"Expected 'key: value' in YAML mapping at line " + line.lineNum() + ": " + line.content());
			}
			cursor++;
			parseKeyValueIntoMap(line.content(), colonIdx, mapIndent, map);
		}
		return map;
	}

	private void parseKeyValueIntoMap(String content, int colonIdx, int keyIndent, Map<String, Object> map) {
		String rawKey = content.substring(0, colonIdx).trim();
		String key = unquoteString(rawKey);
		String rawVal = content.substring(colonIdx + 1).trim();
		if (rawVal.isEmpty()) {
			if (cursor < lines.size() && lines.get(cursor).indent() > keyIndent) {
				map.put(key, parseBlock(lines.get(cursor).indent()));
			} else if (cursor < lines.size() && lines.get(cursor).indent() == keyIndent
					&& isSequenceItem(lines.get(cursor).content())) {
				// YAML allows sequence items at the same indent as the parent key
				map.put(key, parseSequence(keyIndent));
			} else {
				map.put(key, null);
			}
		} else {
			map.put(key, parseScalarOrFlow(rawVal));
		}
	}

	private static int findMappingColon(String text) {
		boolean inSingle = false;
		boolean inDouble = false;
		int bracketDepth = 0;
		int braceDepth = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				if (i == 0 || text.charAt(i - 1) != '\\') {
					inDouble = !inDouble;
				}
			} else if (!inSingle && !inDouble) {
				if (c == '[') {
					bracketDepth++;
				} else if (c == ']') {
					bracketDepth--;
				} else if (c == '{') {
					braceDepth++;
				} else if (c == '}') {
					braceDepth--;
				} else if (c == ':' && bracketDepth == 0 && braceDepth == 0) {
					if (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))) {
						return i;
					}
				}
			}
		}
		return -1;
	}

	private static Object parseScalarOrFlow(String raw) {
		String s = raw.trim();
		if (s.isEmpty() || "null".equalsIgnoreCase(s) || "~".equals(s)) {
			return null;
		}
		if ((s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
				|| (s.startsWith("'") && s.endsWith("'") && s.length() >= 2)) {
			return unquoteString(s);
		}
		if (s.startsWith("[") && s.endsWith("]")) {
			return parseFlowList(s);
		}
		if (s.startsWith("{") && s.endsWith("}")) {
			return parseFlowMap(s);
		}
		if ("true".equalsIgnoreCase(s)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(s)) {
			return Boolean.FALSE;
		}
		// Try integer
		if (isIntegerToken(s)) {
			try {
				return Long.parseLong(s);
			} catch (NumberFormatException ignored) {
			}
		}
		// Try double
		if (isFloatToken(s)) {
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException ignored) {
			}
		}
		return s;
	}

	private static List<Object> parseFlowList(String s) {
		String inner = s.substring(1, s.length() - 1).trim();
		List<Object> out = new ArrayList<>();
		if (inner.isEmpty()) {
			return out;
		}
		for (String part : splitFlowTopLevel(inner, ',')) {
			out.add(parseScalarOrFlow(part));
		}
		return out;
	}

	private static Map<String, Object> parseFlowMap(String s) {
		String inner = s.substring(1, s.length() - 1).trim();
		Map<String, Object> out = new LinkedHashMap<>();
		if (inner.isEmpty()) {
			return out;
		}
		for (String entry : splitFlowTopLevel(inner, ',')) {
			int colon = findMappingColon(entry);
			if (colon < 0) {
				colon = entry.indexOf(':');
			}
			if (colon > 0) {
				String k = unquoteString(entry.substring(0, colon).trim());
				Object v = parseScalarOrFlow(entry.substring(colon + 1).trim());
				out.put(k, v);
			}
		}
		return out;
	}

	private static List<String> splitFlowTopLevel(String s, char sep) {
		List<String> parts = new ArrayList<>();
		boolean inSingle = false;
		boolean inDouble = false;
		int depth = 0;
		int start = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
			} else if (c == '"' && !inSingle) {
				if (i == 0 || s.charAt(i - 1) != '\\') {
					inDouble = !inDouble;
				}
			} else if (!inSingle && !inDouble) {
				if (c == '[' || c == '{') {
					depth++;
				} else if (c == ']' || c == '}') {
					depth--;
				} else if (c == sep && depth == 0) {
					parts.add(s.substring(start, i).trim());
					start = i + 1;
				}
			}
		}
		parts.add(s.substring(start).trim());
		return parts;
	}

	private static String unquoteString(String s) {
		if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
			return s.substring(1, s.length() - 1).replace("''", "'");
		}
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			StringBuilder sb = new StringBuilder();
			for (int i = 1; i < s.length() - 1; i++) {
				char c = s.charAt(i);
				if (c == '\\' && i + 1 < s.length() - 1) {
					char esc = s.charAt(++i);
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
		return s;
	}

	private static boolean isIntegerToken(String s) {
		int i = 0;
		if (s.charAt(0) == '-' || s.charAt(0) == '+') {
			if (s.length() == 1) {
				return false;
			}
			i = 1;
		}
		for (; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isFloatToken(String s) {
		boolean hasDotOrExp = false;
		int i = 0;
		if (s.charAt(0) == '-' || s.charAt(0) == '+') {
			if (s.length() == 1) {
				return false;
			}
			i = 1;
		}
		for (; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isDigit(c)) {
				continue;
			}
			if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
				hasDotOrExp = true;
			} else {
				return false;
			}
		}
		return hasDotOrExp;
	}
}
