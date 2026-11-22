package org.impulsegraph.codegen;

import org.impulsegraph.api.schema.GraphManifest;

import java.util.Locale;
import java.util.Set;

/**
 * Deterministic Java identifier, naming, and type-mapping utilities for
 * {@code impulse-codegen} v2.
 */
final class CodegenNames {

	private static final Set<String> JAVA_KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield");

	private CodegenNames() {
	}

	static String toPascalCase(String raw) {
		if (raw == null || raw.isBlank()) {
			return "Unnamed";
		}
		String trimmed = raw.trim();
		String[] parts = trimmed.split("[^A-Za-z0-9]+");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			boolean allUpper = isAllUpperCase(part);
			if (allUpper) {
				sb.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1) {
					sb.append(part.substring(1).toLowerCase(Locale.ROOT));
				}
			} else {
				sb.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1) {
					sb.append(part.substring(1));
				}
			}
		}
		if (sb.isEmpty()) {
			return "Unnamed";
		}
		if (Character.isDigit(sb.charAt(0))) {
			sb.insert(0, '_');
		}
		return sb.toString();
	}

	static String toCamelCase(String raw) {
		String pascal = toPascalCase(raw);
		if (pascal.isEmpty()) {
			return "unnamed";
		}
		String camel = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
		return escapeKeyword(camel);
	}

	static String toPluralCamelCase(String domainName) {
		String camel = toCamelCase(domainName);
		if (camel.endsWith("_")) {
			return camel + "s";
		}
		String lower = camel.toLowerCase(Locale.ROOT);
		if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch")
				|| lower.endsWith("sh")) {
			return camel + "es";
		}
		if (lower.length() >= 2 && lower.endsWith("y")) {
			char prev = lower.charAt(lower.length() - 2);
			if ("aeiou".indexOf(prev) < 0) {
				return camel.substring(0, camel.length() - 1) + "ies";
			}
		}
		return camel + "s";
	}

	static String toConstantName(String raw) {
		if (raw == null || raw.isBlank()) {
			return "UNNAMED";
		}
		StringBuilder sb = new StringBuilder();
		char prev = 0;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
					sb.append('_');
				}
			} else {
				if (Character.isUpperCase(c) && prev != 0 && Character.isLowerCase(prev) && sb.length() > 0
						&& sb.charAt(sb.length() - 1) != '_') {
					sb.append('_');
				}
				sb.append(Character.toUpperCase(c));
			}
			prev = c;
		}
		while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
			sb.setLength(sb.length() - 1);
		}
		if (sb.isEmpty()) {
			return "UNNAMED";
		}
		if (Character.isDigit(sb.charAt(0))) {
			sb.insert(0, '_');
		}
		return sb.toString();
	}

	static String escapeKeyword(String ident) {
		return JAVA_KEYWORDS.contains(ident) ? ident + "_" : ident;
	}

	static String escapeJavaString(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	static boolean isVectorAttribute(GraphManifest.AttributeDef attr) {
		return !attr.type().isVariableLength() && !attr.type().isFixedWidthPayload() && attr.dimension() > 1;
	}

	static boolean isFloat32Vector(GraphManifest.AttributeDef attr) {
		return attr.type() == GraphManifest.AttributeType.FLOAT32 && attr.dimension() > 1;
	}

	static boolean isOptionalAttribute(GraphManifest.AttributeDef attr) {
		return attr.optional() || (attr.since() != null && !attr.since().isBlank());
	}

	/**
	 * Java type for a domain's external key (primitive where applicable).
	 */
	static String keyJavaType(GraphManifest.KeyType keyType) {
		return switch (keyType) {
			case UUID -> "UUID";
			case INT64 -> "long";
			case INT32 -> "int";
			case STRING, BYTES, NONE -> "String";
		};
	}

	/**
	 * Boxed Java type for a domain's external key (for {@code List<...>}).
	 */
	static String keyBoxedJavaType(GraphManifest.KeyType keyType) {
		return switch (keyType) {
			case UUID -> "UUID";
			case INT64 -> "Long";
			case INT32 -> "Integer";
			case STRING, BYTES, NONE -> "String";
		};
	}

	/**
	 * Java getter return type for a {@code RowView} attribute accessor.
	 */
	static String rowViewJavaType(GraphManifest.AttributeDef attr) {
		if (isVectorAttribute(attr)) {
			return switch (attr.type()) {
				case INT8, BOOL -> "byte[]";
				case INT16 -> "short[]";
				case INT32 -> "int[]";
				case INT64, TIMESTAMP_MS, TIMESTAMP_NS, INTERVAL_SEC_32 -> "long[]";
				case FLOAT16, FLOAT32 -> "float[]";
				case FLOAT64 -> "double[]";
				default -> "byte[]";
			};
		}
		return switch (attr.type()) {
			case INT8 -> "byte";
			case INT16 -> "short";
			case INT32 -> "int";
			case INT64, TIMESTAMP_MS, TIMESTAMP_NS, INTERVAL_SEC_32 -> "long";
			case FLOAT16, FLOAT32 -> "float";
			case FLOAT64 -> "double";
			case BOOL -> "boolean";
			case VAR_STRING, FIXED_STRING -> "String";
			case VAR_BYTES, FIXED_BYTES, INTERVAL_MS_64 -> "byte[]";
		};
	}

	/**
	 * Java component type for a {@code Record} DTO field (boxes primitives when
	 * nullable or optional so {@code null} is representable).
	 */
	static String recordComponentJavaType(GraphManifest.AttributeDef attr) {
		String base = rowViewJavaType(attr);
		if (attr.nullable() || isOptionalAttribute(attr)) {
			return switch (base) {
				case "byte" -> "Byte";
				case "short" -> "Short";
				case "int" -> "Integer";
				case "long" -> "Long";
				case "float" -> "Float";
				case "double" -> "Double";
				case "boolean" -> "Boolean";
				default -> base;
			};
		}
		return base;
	}

	static boolean isPrimitiveType(String javaType) {
		return switch (javaType) {
			case "byte", "short", "int", "long", "float", "double", "boolean" -> true;
			default -> false;
		};
	}

	private static boolean isAllUpperCase(String s) {
		boolean hasLetter = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isLetter(c)) {
				hasLetter = true;
				if (!Character.isUpperCase(c)) {
					return false;
				}
			}
		}
		return hasLetter;
	}
}
