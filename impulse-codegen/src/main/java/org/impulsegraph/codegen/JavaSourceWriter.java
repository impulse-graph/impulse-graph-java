package org.impulsegraph.codegen;

import java.util.Set;
import java.util.TreeSet;

/**
 * Lightweight, zero-dependency Java 21+ source emitter supporting records,
 * classes, clean imports, and consistent indentation.
 */
final class JavaSourceWriter {

	private final String packageName;
	private final Set<String> imports = new TreeSet<>();
	private final StringBuilder body = new StringBuilder(2048);
	private int indent = 0;

	JavaSourceWriter(String packageName) {
		this.packageName = packageName;
	}

	JavaSourceWriter addImport(String fqcn) {
		if (fqcn != null && !fqcn.isBlank()) {
			int lastDot = fqcn.lastIndexOf('.');
			if (lastDot > 0) {
				String pkg = fqcn.substring(0, lastDot);
				if (!"java.lang".equals(pkg) && !pkg.equals(packageName)) {
					imports.add(fqcn);
				}
			}
		}
		return this;
	}

	JavaSourceWriter line(String text) {
		for (int i = 0; i < indent; i++) {
			body.append('\t');
		}
		body.append(text).append('\n');
		return this;
	}

	JavaSourceWriter blankLine() {
		body.append('\n');
		return this;
	}

	JavaSourceWriter beginBlock(String header) {
		line(header + " {");
		indent++;
		return this;
	}

	JavaSourceWriter endBlock() {
		indent = Math.max(0, indent - 1);
		line("}");
		return this;
	}

	JavaSourceWriter deprecatedIfPresent(String deprecatedNote) {
		if (deprecatedNote != null && !deprecatedNote.isBlank()) {
			line("/** @deprecated " + deprecatedNote.replace("*/", "* /") + " */");
			line("@Deprecated");
		}
		return this;
	}

	String render() {
		StringBuilder out = new StringBuilder(body.length() + 512);
		if (packageName != null && !packageName.isBlank()) {
			out.append("package ").append(packageName).append(";\n\n");
		}
		if (!imports.isEmpty()) {
			for (String imp : imports) {
				out.append("import ").append(imp).append(";\n");
			}
			out.append('\n');
		}
		out.append(body);
		return out.toString();
	}
}
