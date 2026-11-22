package org.impulsegraph.cli.service;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Lightweight, deterministic JSON pretty-printer for CLI structured output.
 */
public final class JsonWriter {

	private JsonWriter() {
	}

	public static String toJson(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value, 0);
		return sb.toString();
	}

	private static void writeValue(StringBuilder sb, Object value, int indent) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof String s) {
			writeString(sb, s);
		} else if (value instanceof Number || value instanceof Boolean) {
			sb.append(value);
		} else if (value instanceof Map<?, ?> map) {
			writeMap(sb, map, indent);
		} else if (value instanceof Collection<?> col) {
			writeCollection(sb, col, indent);
		} else {
			writeString(sb, value.toString());
		}
	}

	private static void writeMap(StringBuilder sb, Map<?, ?> map, int indent) {
		if (map.isEmpty()) {
			sb.append("{}");
			return;
		}
		sb.append("{\n");
		Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<?, ?> entry = it.next();
			appendIndent(sb, indent + 2);
			writeString(sb, String.valueOf(entry.getKey()));
			sb.append(": ");
			writeValue(sb, entry.getValue(), indent + 2);
			if (it.hasNext()) {
				sb.append(',');
			}
			sb.append('\n');
		}
		appendIndent(sb, indent);
		sb.append('}');
	}

	private static void writeCollection(StringBuilder sb, Collection<?> col, int indent) {
		if (col.isEmpty()) {
			sb.append("[]");
			return;
		}
		sb.append("[\n");
		Iterator<?> it = col.iterator();
		while (it.hasNext()) {
			Object item = it.next();
			appendIndent(sb, indent + 2);
			writeValue(sb, item, indent + 2);
			if (it.hasNext()) {
				sb.append(',');
			}
			sb.append('\n');
		}
		appendIndent(sb, indent);
		sb.append(']');
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		sb.append('"');
	}

	private static void appendIndent(StringBuilder sb, int count) {
		for (int i = 0; i < count; i++) {
			sb.append(' ');
		}
	}
}
