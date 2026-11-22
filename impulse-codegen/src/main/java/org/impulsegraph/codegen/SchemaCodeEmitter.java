package org.impulsegraph.codegen;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits all v2 typed Impulse Graph Java 21+ artifacts from a validated
 * {@link GraphManifest}. Generated source files depend exclusively on
 * {@code java.*} and {@code org.impulsegraph.*}.
 */
final class SchemaCodeEmitter {

	private final GraphManifest manifest;
	private final String packageName;
	private final String graphClassPrefix;
	private final Map<String, String> domainClassNames = new LinkedHashMap<>();
	private final Map<String, String> domainMethodNames = new LinkedHashMap<>();

	SchemaCodeEmitter(GraphManifest manifest, String packageName) {
		this.manifest = manifest;
		this.packageName = packageName;
		this.graphClassPrefix = CodegenNames.toPascalCase(manifest.graphName());

		Set<String> usedDomainMethods = new HashSet<>();
		for (String domainName : manifest.domains().keySet()) {
			String pascal = CodegenNames.toPascalCase(domainName);
			domainClassNames.put(domainName, pascal);

			String plural = CodegenNames.toPluralCamelCase(domainName);
			if (!usedDomainMethods.add(plural)) {
				plural = CodegenNames.toCamelCase(domainName) + "Domain";
				usedDomainMethods.add(plural);
			}
			domainMethodNames.put(domainName, plural);
		}
	}

	List<Path> emitAll(Path targetFolder) throws IOException {
		Path packageDir = packageName.isBlank() ? targetFolder : targetFolder.resolve(packageName.replace('.', '/'));
		Files.createDirectories(packageDir);

		List<Path> written = new ArrayList<>();

		// Artifact 1, 3, 4 per Domain
		for (Map.Entry<String, GraphManifest.DomainDef> entry : manifest.domains().entrySet()) {
			String domainName = entry.getKey();
			GraphManifest.DomainDef domain = entry.getValue();
			String domClass = domainClassNames.get(domainName);

			written.add(writeFile(packageDir, domClass + "Id.java", emitDomainId(domainName, domClass)));
			written.add(writeFile(packageDir, domClass + "Record.java", emitDomainRecord(domClass, domain)));
			written.add(
					writeFile(packageDir, domClass + "RowView.java", emitDomainRowView(domainName, domClass, domain)));
			written.add(writeFile(packageDir, domClass + "Predicate.java", emitPredicate(domClass + "Predicate")));
			written.add(writeFile(packageDir, domClass + "Attrs.java",
					emitAttrs(domClass + "Attrs", domClass + "Predicate", domain.attributes(), false)));
			written.add(
					writeFile(packageDir, domClass + "DomainView.java", emitDomainView(domainName, domClass, domain)));
			written.add(writeFile(packageDir, domClass + "Traversal.java",
					emitDomainTraversal(domainName, domClass, domain)));
		}

		// Edge Predicate & Edge Attrs for relations that declare attributes
		for (Map.Entry<String, GraphManifest.RelationDef> entry : manifest.relations().entrySet()) {
			String relName = entry.getKey();
			GraphManifest.RelationDef rel = entry.getValue();
			if (!rel.attributes().isEmpty()) {
				String relClass = CodegenNames.toPascalCase(relName);
				written.add(writeFile(packageDir, relClass + "EdgePredicate.java",
						emitPredicate(relClass + "EdgePredicate")));
				written.add(writeFile(packageDir, relClass + "EdgeAttrs.java",
						emitAttrs(relClass + "EdgeAttrs", relClass + "EdgePredicate", rel.attributes(), true)));
			}
		}

		// Artifact 2 — Typed Snapshot Facade
		written.add(writeFile(packageDir, graphClassPrefix + "Snapshot.java", emitSnapshotFacade()));

		// Artifact 5 — Typed Streaming Snapshot Writer
		written.add(writeFile(packageDir, graphClassPrefix + "SnapshotWriter.java", emitSnapshotWriter()));

		return List.copyOf(written);
	}

	private Path writeFile(Path packageDir, String fileName, String content) throws IOException {
		Path path = packageDir.resolve(fileName);
		Files.writeString(path, content, StandardCharsets.UTF_8);
		return path;
	}

	// =========================================================================
	// Artifact 1: Per-Domain Distinct Node ID Record (<Domain>Id.java)
	// =========================================================================

	private String emitDomainId(String domainName, String domClass) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.line("/** Strongly-typed dense node ID for domain {@code " + domainName + "}. */");
		w.beginBlock("public record " + domClass + "Id(long denseId)");
		w.beginBlock("public " + domClass + "Id");
		w.beginBlock("if (denseId < 0L)");
		w.line("throw new IllegalArgumentException(\"" + domClass + "Id denseId must be >= 0, got: \" + denseId);");
		w.endBlock();
		w.endBlock();
		w.endBlock();
		return w.render();
	}

	// =========================================================================
	// Artifact 2: Typed Snapshot Facade (<GraphName>Snapshot.java)
	// =========================================================================

	private String emitSnapshotFacade() {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.io.IOException");
		w.addImport("java.lang.foreign.Arena");
		w.addImport("java.nio.file.Path");
		w.addImport("java.util.Map");
		w.addImport("java.util.Objects");
		w.addImport("java.util.Optional");
		w.addImport("java.util.function.Function");
		w.addImport("org.impulsegraph.api.ImpulseGraphSnapshot");
		w.addImport("org.impulsegraph.api.lifecycle.ConsumerSchemaContext");
		w.addImport("org.impulsegraph.api.lifecycle.SnapshotResolver");
		w.addImport("org.impulsegraph.api.schema.GraphManifest");
		w.addImport("org.impulsegraph.api.schema.SchemaYamlCodec");
		w.addImport("org.impulsegraph.storage.csr.BinarySnapshotLoader");
		w.addImport("org.impulsegraph.storage.schema.BoundSchemaHandles");
		w.addImport("org.impulsegraph.storage.schema.SnapshotContractValidator");
		w.addImport("org.impulsegraph.vm.CompiledQuery");
		w.addImport("org.impulsegraph.vm.lifecycle.SnapshotLifecycleManager");

		String cls = graphClassPrefix + "Snapshot";
		String canonicalYaml = SchemaYamlCodec.toYaml(manifest);

		w.line("/** Typed snapshot facade for {@code " + manifest.graphName() + "} (v" + manifest.version() + "). */");
		w.beginBlock("public final class " + cls + " implements AutoCloseable");
		w.blankLine();

		// Embedded SCHEMA_YAML & SCHEMA
		String[] yamlLines = canonicalYaml.split("\n", -1);
		w.line("public static final String SCHEMA_YAML =");
		for (int i = 0; i < yamlLines.length; i++) {
			String suffix = (i < yamlLines.length - 1) ? "\\n\" +" : "\";";
			w.line("\t\t\"" + CodegenNames.escapeJavaString(yamlLines[i]) + suffix);
		}
		w.blankLine();
		w.line("public static final GraphManifest SCHEMA = SchemaYamlCodec.parse(SCHEMA_YAML);");
		w.blankLine();

		w.beginBlock("public static ConsumerSchemaContext consumerContext(Optional<String> activeRevision)");
		w.line("return ConsumerSchemaContext.of(SCHEMA, activeRevision);");
		w.endBlock();
		w.blankLine();

		w.line("private final BinarySnapshotLoader.LoadedSnapshot loaded;");
		w.line("private final ImpulseGraphSnapshot underlying;");
		w.line("private final BoundSchemaHandles handles;");
		for (String domainName : manifest.domains().keySet()) {
			String domClass = domainClassNames.get(domainName);
			String fieldName = CodegenNames.toCamelCase(domainName) + "View";
			w.line("private final " + domClass + "DomainView " + fieldName + ";");
		}
		w.blankLine();

		w.beginBlock("private " + cls + "(BinarySnapshotLoader.LoadedSnapshot loaded, BoundSchemaHandles handles)");
		w.line("this.loaded = Objects.requireNonNull(loaded, \"loaded must not be null\");");
		w.line("this.underlying = Objects.requireNonNull(loaded.graph(), \"loaded.graph() must not be null\");");
		w.line("this.handles = Objects.requireNonNull(handles, \"handles must not be null\");");
		for (String domainName : manifest.domains().keySet()) {
			String domClass = domainClassNames.get(domainName);
			String fieldName = CodegenNames.toCamelCase(domainName) + "View";
			w.line("this." + fieldName + " = new " + domClass + "DomainView(this.underlying, this.handles);");
		}
		w.endBlock();
		w.blankLine();

		// open(Path, Arena)
		w.beginBlock("public static " + cls + " open(Path impsFile, Arena arena) throws IOException");
		w.line("return open(impsFile, arena, true);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public static " + cls
				+ " open(Path impsFile, Arena arena, boolean verifyChecksum) throws IOException");
		w.line("BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(impsFile, arena, verifyChecksum);");
		w.line("return bind(loaded);");
		w.endBlock();
		w.blankLine();

		// bind(LoadedSnapshot)
		w.beginBlock("public static " + cls + " bind(BinarySnapshotLoader.LoadedSnapshot loaded)");
		w.line("BoundSchemaHandles handles = SnapshotContractValidator.validateAndBind(loaded, SCHEMA);");
		w.line("return new " + cls + "(loaded, handles);");
		w.endBlock();
		w.blankLine();

		// createLifecycleManager overloads
		w.beginBlock("public static SnapshotLifecycleManager<" + cls
				+ "> createLifecycleManager(SnapshotResolver resolver)");
		w.line("return createLifecycleManager(resolver, null);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public static SnapshotLifecycleManager<" + cls
				+ "> createLifecycleManager(SnapshotResolver resolver, Function<SnapshotLifecycleManager.EpochContext, Map<String, CompiledQuery>> preSwapQueryBinder)");
		w.line("SnapshotLifecycleManager<" + cls + "> manager = new SnapshotLifecycleManager<>(SCHEMA, bs -> new " + cls
				+ "(bs.loaded(), bs.handles()), preSwapQueryBinder, true);");
		w.beginBlock("if (resolver != null)");
		w.line("manager.pollAndSwap(resolver);");
		w.endBlock();
		w.line("return manager;");
		w.endBlock();
		w.blankLine();

		// Typed domain entrypoints
		for (Map.Entry<String, GraphManifest.DomainDef> entry : manifest.domains().entrySet()) {
			String domainName = entry.getKey();
			GraphManifest.DomainDef domain = entry.getValue();
			String domClass = domainClassNames.get(domainName);
			String methodName = domainMethodNames.get(domainName);
			String fieldName = CodegenNames.toCamelCase(domainName) + "View";
			w.deprecatedIfPresent(domain.deprecated());
			w.beginBlock("public " + domClass + "DomainView " + methodName + "()");
			w.line("return " + fieldName + ";");
			w.endBlock();
			w.blankLine();
		}

		w.beginBlock("public ImpulseGraphSnapshot underlying()");
		w.line("return underlying;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public BoundSchemaHandles handles()");
		w.line("return handles;");
		w.endBlock();
		w.blankLine();

		w.line("@Override");
		w.beginBlock("public void close()");
		w.beginBlock("try");
		w.line("loaded.close();");
		w.endBlock();
		w.beginBlock("catch (Exception e)");
		w.line("throw new RuntimeException(\"Failed to close snapshot\", e);");
		w.endBlock();
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	// =========================================================================
	// Artifact 3a: Typed DomainView (<Domain>DomainView.java)
	// =========================================================================

	private String emitDomainView(String domainName, String domClass, GraphManifest.DomainDef domain) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.util.Objects");
		w.addImport("org.impulsegraph.api.ImpulseGraphSnapshot");
		w.addImport("org.impulsegraph.api.bitset.ImpulseBitSet");
		w.addImport("org.impulsegraph.api.traversal.DomainView");
		w.addImport("org.impulsegraph.api.traversal.MultiLaneBuilder");
		w.addImport("org.impulsegraph.storage.schema.BoundSchemaHandles");
		if (domain.keyType() == GraphManifest.KeyType.UUID) {
			w.addImport("java.util.UUID");
		}

		String cls = domClass + "DomainView";
		String travCls = domClass + "Traversal";
		String idCls = domClass + "Id";
		String keyType = CodegenNames.keyJavaType(domain.keyType());

		w.line("/** Typed domain entrypoint for {@code " + domainName + "}. */");
		w.beginBlock("public final class " + cls);
		w.blankLine();
		w.line("private final DomainView delegate;");
		w.line("private final BoundSchemaHandles handles;");
		w.line("private final BoundSchemaHandles.BoundDomainHandle boundDomain;");
		w.blankLine();

		w.beginBlock(cls + "(ImpulseGraphSnapshot snapshot, BoundSchemaHandles handles)");
		w.line("this.delegate = Objects.requireNonNull(snapshot, \"snapshot must not be null\").domain(\""
				+ CodegenNames.escapeJavaString(domainName) + "\");");
		w.line("this.handles = Objects.requireNonNull(handles, \"handles must not be null\");");
		w.line("this.boundDomain = handles.domain(\"" + CodegenNames.escapeJavaString(domainName) + "\");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " all()");
		w.line("return new " + travCls + "(delegate.all(), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " from(" + idCls + " id)");
		w.line("Objects.requireNonNull(id, \"id must not be null\");");
		w.line("return new " + travCls + "(delegate.from(id.denseId()), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " from(" + idCls + "... ids)");
		w.line("Objects.requireNonNull(ids, \"ids must not be null\");");
		w.line("long[] raw = new long[ids.length];");
		w.beginBlock("for (int i = 0; i < ids.length; i++)");
		w.line("raw[i] = Objects.requireNonNull(ids[i], \"ids[\" + i + \"] must not be null\").denseId();");
		w.endBlock();
		w.line("return new " + travCls + "(delegate.from(raw), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " from(long denseId)");
		w.line("return new " + travCls + "(delegate.from(denseId), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " from(long... denseIds)");
		w.line("return new " + travCls + "(delegate.from(denseIds), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " from(ImpulseBitSet bitSet)");
		w.line("return new " + travCls + "(delegate.from(bitSet), handles);");
		w.endBlock();
		w.blankLine();

		// Key methods typed to domain.keyType()
		emitKeyLookupMethods(w, domClass, travCls, domain.keyType(), keyType);

		w.beginBlock("public long nodeCount()");
		w.line("return delegate.nodeCount();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public MultiLaneBuilder multiLane()");
		w.line("return delegate.multiLane();");
		w.endBlock();
		w.blankLine();

		// Vector search methods for float32[D] attributes
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			if (CodegenNames.isFloat32Vector(attr)) {
				String attrPascal = CodegenNames.toPascalCase(attr.name());
				w.deprecatedIfPresent(attr.deprecated());
				w.beginBlock("public " + travCls + " vectorSearch" + attrPascal + "(float[] queryVector, int topK)");
				w.line("Objects.requireNonNull(queryVector, \"queryVector must not be null\");");
				w.beginBlock("if (queryVector.length != " + attr.dimension() + ")");
				w.line("throw new IllegalArgumentException(\"Expected vector dimension " + attr.dimension()
						+ " for attribute '" + CodegenNames.escapeJavaString(attr.name())
						+ "', got \" + queryVector.length);");
				w.endBlock();
				w.line("return new " + travCls + "(delegate.vectorSearch(\""
						+ CodegenNames.escapeJavaString(attr.name()) + "\", queryVector, topK), handles);");
				w.endBlock();
				w.blankLine();
			}
		}

		// Presence checks for optional / since attributes
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			if (CodegenNames.isOptionalAttribute(attr)) {
				String attrPascal = CodegenNames.toPascalCase(attr.name());
				w.deprecatedIfPresent(attr.deprecated());
				w.beginBlock("public boolean has" + attrPascal + "()");
				w.line("BoundSchemaHandles.BoundAttributeHandle h = boundDomain != null ? boundDomain.attribute(\""
						+ CodegenNames.escapeJavaString(attr.name()) + "\") : null;");
				w.line("return h != null && h.present();");
				w.endBlock();
				w.blankLine();
			}
		}

		w.beginBlock("public DomainView unwrap()");
		w.line("return delegate;");
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	private void emitKeyLookupMethods(JavaSourceWriter w, String domClass, String travCls,
			GraphManifest.KeyType keyType, String javaKeyType) {
		String keyToStringExpr = switch (keyType) {
			case UUID -> "Objects.requireNonNull(key, \"key must not be null\").toString()";
			case INT64 -> "Long.toString(key)";
			case INT32 -> "Integer.toString(key)";
			case STRING, BYTES, NONE -> "Objects.requireNonNull(key, \"key must not be null\")";
		};
		String keyElemToStringExpr = switch (keyType) {
			case UUID -> "Objects.requireNonNull(keys[i], \"keys[\" + i + \"] must not be null\").toString()";
			case INT64 -> "Long.toString(keys[i])";
			case INT32 -> "Integer.toString(keys[i])";
			case STRING, BYTES, NONE -> "Objects.requireNonNull(keys[i], \"keys[\" + i + \"] must not be null\")";
		};

		w.beginBlock("public " + travCls + " fromKey(" + javaKeyType + " key)");
		w.line("return new " + travCls + "(delegate.fromKey(" + keyToStringExpr + "), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + travCls + " fromKeys(" + javaKeyType + "... keys)");
		w.line("Objects.requireNonNull(keys, \"keys must not be null\");");
		w.line("String[] raw = new String[keys.length];");
		w.beginBlock("for (int i = 0; i < keys.length; i++)");
		w.line("raw[i] = " + keyElemToStringExpr + ";");
		w.endBlock();
		w.line("return new " + travCls + "(delegate.fromKeys(raw), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public long toDenseId(" + javaKeyType + " key)");
		w.line("return delegate.toDenseId(" + keyToStringExpr + ");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + javaKeyType + " toKey(long denseId)");
		w.line("String raw = delegate.toKey(denseId);");
		switch (keyType) {
			case UUID -> w.line("return raw != null ? UUID.fromString(raw) : null;");
			case INT64 -> {
				w.beginBlock("if (raw == null)");
				w.line("throw new IllegalStateException(\"No int64 key found for " + domClass
						+ " denseId=\" + denseId);");
				w.endBlock();
				w.line("return Long.parseLong(raw);");
			}
			case INT32 -> {
				w.beginBlock("if (raw == null)");
				w.line("throw new IllegalStateException(\"No int32 key found for " + domClass
						+ " denseId=\" + denseId);");
				w.endBlock();
				w.line("return Integer.parseInt(raw);");
			}
			case STRING, BYTES, NONE -> w.line("return raw;");
		}
		w.endBlock();
		w.blankLine();
	}

	// =========================================================================
	// Artifact 3b: Domain-Transitioning Kleisli Traversal (<Domain>Traversal.java)
	// =========================================================================

	private String emitDomainTraversal(String domainName, String domClass, GraphManifest.DomainDef domain) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.util.ArrayList");
		w.addImport("java.util.List");
		w.addImport("java.util.Objects");
		w.addImport("java.util.function.Consumer");
		w.addImport("java.util.function.UnaryOperator");
		w.addImport("org.impulsegraph.api.bitset.ImpulseBitSet");
		w.addImport("org.impulsegraph.api.traversal.Traversal");
		w.addImport("org.impulsegraph.storage.schema.BoundSchemaHandles");
		if (domain.keyType() == GraphManifest.KeyType.UUID) {
			w.addImport("java.util.UUID");
		}

		String cls = domClass + "Traversal";
		String idCls = domClass + "Id";
		String predCls = domClass + "Predicate";
		String rowCls = domClass + "RowView";
		String recCls = domClass + "Record";
		String keyBoxedType = CodegenNames.keyBoxedJavaType(domain.keyType());

		w.line("/** Typed Kleisli frontier traversal anchored in domain {@code " + domainName + "}. */");
		w.beginBlock("public final class " + cls);
		w.blankLine();
		w.line("private final Traversal<ImpulseBitSet> delegate;");
		w.line("private final BoundSchemaHandles handles;");
		w.blankLine();

		w.beginBlock(cls + "(Traversal<ImpulseBitSet> delegate, BoundSchemaHandles handles)");
		w.line("this.delegate = Objects.requireNonNull(delegate, \"delegate must not be null\");");
		w.line("this.handles = Objects.requireNonNull(handles, \"handles must not be null\");");
		w.endBlock();
		w.blankLine();

		// Outgoing relations where source == domainName
		for (Map.Entry<String, GraphManifest.RelationDef> entry : manifest.relations().entrySet()) {
			String relName = entry.getKey();
			GraphManifest.RelationDef rel = entry.getValue();
			if (domainName.equals(rel.source())) {
				String tgtClass = domainClassNames.get(rel.target()) + "Traversal";
				String relPascal = CodegenNames.toPascalCase(relName);
				w.deprecatedIfPresent(rel.deprecated());
				w.beginBlock("public " + tgtClass + " out" + relPascal + "()");
				w.line("return new " + tgtClass + "(delegate.out(\"" + CodegenNames.escapeJavaString(relName)
						+ "\"), handles);");
				w.endBlock();
				w.blankLine();

				if (!rel.attributes().isEmpty()) {
					String edgePredCls = relPascal + "EdgePredicate";
					w.deprecatedIfPresent(rel.deprecated());
					w.beginBlock("public " + tgtClass + " out" + relPascal + "(" + edgePredCls + " predicate)");
					w.line("Objects.requireNonNull(predicate, \"predicate must not be null\");");
					w.line("return new " + tgtClass + "(delegate.out(\"" + CodegenNames.escapeJavaString(relName)
							+ "\", predicate.toCelExpression()), handles);");
					w.endBlock();
					w.blankLine();
				}
			}
		}

		// Incoming relations where target == domainName AND (directions contains IN ||
		// symmetric)
		for (Map.Entry<String, GraphManifest.RelationDef> entry : manifest.relations().entrySet()) {
			String relName = entry.getKey();
			GraphManifest.RelationDef rel = entry.getValue();
			boolean supportsIn = rel.directions().contains(GraphManifest.Direction.IN) || rel.symmetric();
			if (domainName.equals(rel.target()) && supportsIn) {
				String srcClass = domainClassNames.get(rel.source()) + "Traversal";
				String relPascal = CodegenNames.toPascalCase(relName);
				String inMethod = "in" + relPascal;

				w.deprecatedIfPresent(rel.deprecated());
				w.beginBlock("public " + srcClass + " " + inMethod + "()");
				w.line("return new " + srcClass + "(delegate.in(\"" + CodegenNames.escapeJavaString(relName)
						+ "\"), handles);");
				w.endBlock();
				w.blankLine();

				if (!rel.attributes().isEmpty()) {
					String edgePredCls = relPascal + "EdgePredicate";
					w.deprecatedIfPresent(rel.deprecated());
					w.beginBlock("public " + srcClass + " " + inMethod + "(" + edgePredCls + " predicate)");
					w.line("Objects.requireNonNull(predicate, \"predicate must not be null\");");
					w.line("return new " + srcClass + "(delegate.in(\"" + CodegenNames.escapeJavaString(relName)
							+ "\", predicate.toCelExpression()), handles);");
					w.endBlock();
					w.blankLine();
				}

				if (rel.inverseAlias() != null && !rel.inverseAlias().isBlank()) {
					String aliasMethod = CodegenNames.toCamelCase(rel.inverseAlias());
					if (!aliasMethod.equals(inMethod)) {
						w.deprecatedIfPresent(rel.deprecated());
						w.beginBlock("public " + srcClass + " " + aliasMethod + "()");
						w.line("return " + inMethod + "();");
						w.endBlock();
						w.blankLine();

						if (!rel.attributes().isEmpty()) {
							String edgePredCls = relPascal + "EdgePredicate";
							w.deprecatedIfPresent(rel.deprecated());
							w.beginBlock("public " + srcClass + " " + aliasMethod + "(" + edgePredCls + " predicate)");
							w.line("return " + inMethod + "(predicate);");
							w.endBlock();
							w.blankLine();
						}
					}
				}
			}
		}

		// Virtual relations where all component relations share a common source ==
		// domainName and target
		for (Map.Entry<String, GraphManifest.VirtualRelationDef> entry : manifest.virtualRelations().entrySet()) {
			String vrName = entry.getKey();
			GraphManifest.VirtualRelationDef vr = entry.getValue();
			if (vr.components().isEmpty()) {
				continue;
			}
			String commonSrc = null;
			String commonTgt = null;
			boolean uniform = true;
			for (String compName : vr.components()) {
				GraphManifest.RelationDef comp = manifest.relations().get(compName);
				if (comp == null || comp.source() == null || comp.target() == null) {
					uniform = false;
					break;
				}
				if (commonSrc == null) {
					commonSrc = comp.source();
					commonTgt = comp.target();
				} else if (!commonSrc.equals(comp.source()) || !commonTgt.equals(comp.target())) {
					uniform = false;
					break;
				}
			}
			if (uniform && domainName.equals(commonSrc)) {
				String tgtClass = domainClassNames.get(commonTgt) + "Traversal";
				String vrPascal = CodegenNames.toPascalCase(vrName);
				StringBuilder args = new StringBuilder();
				for (int i = 0; i < vr.components().size(); i++) {
					if (i > 0) {
						args.append(", ");
					}
					args.append('"').append(CodegenNames.escapeJavaString(vr.components().get(i))).append('"');
				}
				w.deprecatedIfPresent(vr.deprecated());
				w.beginBlock("public " + tgtClass + " out" + vrPascal + "()");
				w.line("return new " + tgtClass + "(delegate.out(" + args + "), handles);");
				w.endBlock();
				w.blankLine();
			}
		}

		// Filtering & loops
		w.beginBlock("public " + cls + " where(" + predCls + " predicate)");
		w.line("Objects.requireNonNull(predicate, \"predicate must not be null\");");
		w.line("return new " + cls + "(delegate.filter(predicate.toCelExpression()), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " repeat(int hops, UnaryOperator<" + cls + "> step)");
		w.line("Objects.requireNonNull(step, \"step must not be null\");");
		w.line("return new " + cls + "(delegate.repeat(hops, t -> step.apply(new " + cls
				+ "(t, handles)).delegate), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " repeatUntilStable(UnaryOperator<" + cls + "> step)");
		w.line("Objects.requireNonNull(step, \"step must not be null\");");
		w.line("return new " + cls + "(delegate.repeatUntilStable(t -> step.apply(new " + cls
				+ "(t, handles)).delegate), handles);");
		w.endBlock();
		w.blankLine();

		// Set operations
		w.beginBlock("public " + cls + " union(" + cls + " other)");
		w.line("Objects.requireNonNull(other, \"other must not be null\");");
		w.line("return new " + cls + "(delegate.union(other.delegate), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " intersect(" + cls + " other)");
		w.line("Objects.requireNonNull(other, \"other must not be null\");");
		w.line("return new " + cls + "(delegate.intersect(other.delegate), handles);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " difference(" + cls + " other)");
		w.line("Objects.requireNonNull(other, \"other must not be null\");");
		w.line("return new " + cls + "(delegate.difference(other.delegate), handles);");
		w.endBlock();
		w.blankLine();

		// Terminal extractors
		w.beginBlock("public long count()");
		w.line("return delegate.count();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public ImpulseBitSet toBitSet()");
		w.line("return delegate.toBitSet();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public long[] toDenseIds()");
		w.line("ImpulseBitSet bits = delegate.toBitSet();");
		w.line("long[] ids = new long[(int) bits.cardinality()];");
		w.line("int idx = 0;");
		w.beginBlock("for (int id = bits.nextSetBit(0); id >= 0; id = bits.nextSetBit(id + 1))");
		w.line("ids[idx++] = id;");
		w.endBlock();
		w.line("return ids;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public List<" + idCls + "> toIds()");
		w.line("ImpulseBitSet bits = delegate.toBitSet();");
		w.line("List<" + idCls + "> result = new ArrayList<>((int) bits.cardinality());");
		w.beginBlock("for (int id = bits.nextSetBit(0); id >= 0; id = bits.nextSetBit(id + 1))");
		w.line("result.add(new " + idCls + "(id));");
		w.endBlock();
		w.line("return result;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public List<" + keyBoxedType + "> toKeyList()");
		switch (domain.keyType()) {
			case UUID -> {
				w.line("List<String> raw = delegate.toKeyList();");
				w.line("List<UUID> result = new ArrayList<>(raw.size());");
				w.beginBlock("for (String s : raw)");
				w.line("result.add(UUID.fromString(s));");
				w.endBlock();
				w.line("return result;");
			}
			case INT64 -> {
				w.line("List<String> raw = delegate.toKeyList();");
				w.line("List<Long> result = new ArrayList<>(raw.size());");
				w.beginBlock("for (String s : raw)");
				w.line("result.add(Long.valueOf(s));");
				w.endBlock();
				w.line("return result;");
			}
			case INT32 -> {
				w.line("List<String> raw = delegate.toKeyList();");
				w.line("List<Integer> result = new ArrayList<>(raw.size());");
				w.beginBlock("for (String s : raw)");
				w.line("result.add(Integer.valueOf(s));");
				w.endBlock();
				w.line("return result;");
			}
			case STRING, BYTES, NONE -> w.line("return delegate.toKeyList();");
		}
		w.endBlock();
		w.blankLine();

		// Zero-allocation flyweight RowView iteration
		w.beginBlock("public void forEachRow(Consumer<" + rowCls + "> consumer)");
		w.line("Objects.requireNonNull(consumer, \"consumer must not be null\");");
		w.line("ImpulseBitSet bits = delegate.toBitSet();");
		w.beginBlock("if (bits.isEmpty())");
		w.line("return;");
		w.endBlock();
		w.line(rowCls + " view = new " + rowCls + "(handles.domain(\"" + CodegenNames.escapeJavaString(domainName)
				+ "\"));");
		w.beginBlock("for (int id = bits.nextSetBit(0); id >= 0; id = bits.nextSetBit(id + 1))");
		w.line("view.positionAt(id);");
		w.line("consumer.accept(view);");
		w.endBlock();
		w.endBlock();
		w.blankLine();

		w.beginBlock("public List<" + recCls + "> toRecords()");
		w.line("List<" + recCls + "> result = new ArrayList<>();");
		w.line("forEachRow(row -> result.add(row.toRecord()));");
		w.line("return result;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public String toImpAsm()");
		w.line("return delegate.toImpAsm();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public Traversal<ImpulseBitSet> unwrap()");
		w.line("return delegate;");
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	// =========================================================================
	// Artifact 3c: CEL Predicate & Attribute Metamodel
	// =========================================================================

	private String emitPredicate(String predClass) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.util.Objects");

		w.line("/** Type-safe CEL predicate expression for ImpulseVM evaluation. */");
		w.beginBlock("public final class " + predClass);
		w.blankLine();
		w.line("private final String celExpression;");
		w.blankLine();

		w.beginBlock(predClass + "(String celExpression)");
		w.line("this.celExpression = Objects.requireNonNull(celExpression, \"celExpression must not be null\");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public static " + predClass + " raw(String celExpression)");
		w.line("return new " + predClass + "(celExpression);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + predClass + " and(" + predClass + " other)");
		w.line("Objects.requireNonNull(other, \"other must not be null\");");
		w.line("return new " + predClass + "(\"(\" + this.celExpression + \") && (\" + other.celExpression + \")\");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + predClass + " or(" + predClass + " other)");
		w.line("Objects.requireNonNull(other, \"other must not be null\");");
		w.line("return new " + predClass + "(\"(\" + this.celExpression + \") || (\" + other.celExpression + \")\");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + predClass + " not()");
		w.line("return new " + predClass + "(\"!(\" + this.celExpression + \")\");");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public String toCelExpression()");
		w.line("return celExpression;");
		w.endBlock();
		w.blankLine();

		w.line("@Override");
		w.beginBlock("public String toString()");
		w.line("return celExpression;");
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	private String emitAttrs(String attrsClass, String predClass, Map<String, GraphManifest.AttributeDef> attributes,
			boolean isEdge) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.util.Objects");

		w.line("/** Type-safe CEL attribute metamodel. */");
		w.beginBlock("public final class " + attrsClass);
		w.blankLine();
		w.beginBlock("private " + attrsClass + "()");
		w.endBlock();
		w.blankLine();

		String prefix = isEdge ? "edge." : "";
		for (GraphManifest.AttributeDef attr : attributes.values()) {
			if (CodegenNames.isVectorAttribute(attr)) {
				continue;
			}
			String constName = CodegenNames.toConstantName(attr.name());
			String celIdent = CodegenNames.escapeJavaString(prefix + attr.name());
			String attrTypeClass = switch (attr.type()) {
				case INT8, INT16, INT32 -> "IntAttr";
				case INT64, TIMESTAMP_MS, TIMESTAMP_NS, INTERVAL_SEC_32 -> "LongAttr";
				case FLOAT16, FLOAT32, FLOAT64 -> "DoubleAttr";
				case BOOL -> "BoolAttr";
				case VAR_STRING, FIXED_STRING -> "StringAttr";
				case VAR_BYTES, FIXED_BYTES, INTERVAL_MS_64 -> "BytesAttr";
			};
			w.deprecatedIfPresent(attr.deprecated());
			w.line("public static final " + attrTypeClass + " " + constName + " = new " + attrTypeClass + "(\""
					+ celIdent + "\");");
		}
		w.blankLine();

		// IntAttr
		emitNumericAttrClass(w, "IntAttr", "long", predClass);
		// LongAttr
		emitNumericAttrClass(w, "LongAttr", "long", predClass);
		// DoubleAttr
		emitNumericAttrClass(w, "DoubleAttr", "double", predClass);

		// BoolAttr
		w.beginBlock("public static final class BoolAttr");
		w.line("private final String name;");
		w.beginBlock("BoolAttr(String name)");
		w.line("this.name = name;");
		w.endBlock();
		w.beginBlock("public " + predClass + " isTrue()");
		w.line("return new " + predClass + "(name + \" == true\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isFalse()");
		w.line("return new " + predClass + "(name + \" == false\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " eq(boolean value)");
		w.line("return new " + predClass + "(name + \" == \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNull()");
		w.line("return new " + predClass + "(\"isNull(\" + name + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNotNull()");
		w.line("return new " + predClass + "(\"isNotNull(\" + name + \")\");");
		w.endBlock();
		w.endBlock();
		w.blankLine();

		// StringAttr
		w.beginBlock("public static final class StringAttr");
		w.line("private final String name;");
		w.beginBlock("StringAttr(String name)");
		w.line("this.name = name;");
		w.endBlock();
		w.beginBlock("public " + predClass + " eq(String value)");
		w.line("return new " + predClass + "(name + \" == \" + escapeCelString(value));");
		w.endBlock();
		w.beginBlock("public " + predClass + " ne(String value)");
		w.line("return new " + predClass + "(name + \" != \" + escapeCelString(value));");
		w.endBlock();
		w.beginBlock("public " + predClass + " startsWith(String prefix)");
		w.line("return new " + predClass + "(name + \".startsWith(\" + escapeCelString(prefix) + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " contains(String substring)");
		w.line("return new " + predClass + "(name + \".contains(\" + escapeCelString(substring) + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNull()");
		w.line("return new " + predClass + "(\"isNull(\" + name + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNotNull()");
		w.line("return new " + predClass + "(\"isNotNull(\" + name + \")\");");
		w.endBlock();
		w.endBlock();
		w.blankLine();

		// BytesAttr
		w.beginBlock("public static final class BytesAttr");
		w.line("private final String name;");
		w.beginBlock("BytesAttr(String name)");
		w.line("this.name = name;");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNull()");
		w.line("return new " + predClass + "(\"isNull(\" + name + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNotNull()");
		w.line("return new " + predClass + "(\"isNotNull(\" + name + \")\");");
		w.endBlock();
		w.endBlock();
		w.blankLine();

		// CEL string escaper
		w.beginBlock("private static String escapeCelString(String value)");
		w.line("Objects.requireNonNull(value, \"CEL string literal must not be null\");");
		w.line("StringBuilder sb = new StringBuilder(value.length() + 4);");
		w.line("sb.append('\"');");
		w.beginBlock("for (int i = 0; i < value.length(); i++)");
		w.line("char c = value.charAt(i);");
		w.beginBlock("switch (c)");
		w.line("case '\\\\' -> sb.append(\"\\\\\\\\\");");
		w.line("case '\"' -> sb.append(\"\\\\\\\"\");");
		w.line("case '\\n' -> sb.append(\"\\\\n\");");
		w.line("case '\\r' -> sb.append(\"\\\\r\");");
		w.line("case '\\t' -> sb.append(\"\\\\t\");");
		w.line("default -> sb.append(c);");
		w.endBlock();
		w.endBlock();
		w.line("sb.append('\"');");
		w.line("return sb.toString();");
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	private void emitNumericAttrClass(JavaSourceWriter w, String attrClass, String paramType, String predClass) {
		w.beginBlock("public static final class " + attrClass);
		w.line("private final String name;");
		w.beginBlock(attrClass + "(String name)");
		w.line("this.name = name;");
		w.endBlock();
		w.beginBlock("public " + predClass + " eq(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" == \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " ne(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" != \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " gt(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" > \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " ge(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" >= \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " lt(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" < \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " le(" + paramType + " value)");
		w.line("return new " + predClass + "(name + \" <= \" + value);");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNull()");
		w.line("return new " + predClass + "(\"isNull(\" + name + \")\");");
		w.endBlock();
		w.beginBlock("public " + predClass + " isNotNull()");
		w.line("return new " + predClass + "(\"isNotNull(\" + name + \")\");");
		w.endBlock();
		w.endBlock();
		w.blankLine();
	}

	// =========================================================================
	// Artifact 4: Zero-Allocation RowView & Boundary Record DTO
	// =========================================================================

	private String emitDomainRecord(String domClass, GraphManifest.DomainDef domain) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		StringBuilder params = new StringBuilder("long denseId");
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			String fieldName = safeRowFieldName(attr.name());
			String compType = CodegenNames.recordComponentJavaType(attr);
			params.append(", ").append(compType).append(' ').append(fieldName);
		}
		w.line("/** Immutable boundary DTO materialized from {@link " + domClass + "RowView}. */");
		w.beginBlock("public record " + domClass + "Record(" + params + ")");
		w.endBlock();
		return w.render();
	}

	private String emitDomainRowView(String domainName, String domClass, GraphManifest.DomainDef domain) {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.lang.foreign.MemorySegment");
		w.addImport("java.lang.foreign.ValueLayout");
		w.addImport("java.nio.ByteOrder");
		w.addImport("java.nio.charset.StandardCharsets");
		w.addImport("java.util.Objects");
		w.addImport("org.impulsegraph.storage.schema.BoundSchemaHandles");

		String cls = domClass + "RowView";
		String idCls = domClass + "Id";
		String recCls = domClass + "Record";

		w.line("/** Zero-allocation flyweight off-heap cursor for domain {@code " + domainName + "}. */");
		w.beginBlock("public final class " + cls);
		w.blankLine();

		w.line("private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);");
		w.line("private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);");
		w.line("private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);");
		w.line("private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);");
		w.line("private static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);");
		w.blankLine();

		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			String base = safeRowFieldName(attr.name());
			w.line("private final MemorySegment " + base + "Data;");
			w.line("private final MemorySegment " + base + "Validity;");
			w.line("private final MemorySegment " + base + "Offsets;");
			w.line("private final boolean " + base + "Present;");
			w.line("private final boolean " + base + "Nullable;");
		}
		w.line("private long currentDenseId;");
		w.blankLine();

		w.beginBlock(cls + "(BoundSchemaHandles.BoundDomainHandle boundDomain)");
		w.line("Objects.requireNonNull(boundDomain, \"boundDomain must not be null\");");
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			String base = safeRowFieldName(attr.name());
			String varH = base + "H";
			w.line("BoundSchemaHandles.BoundAttributeHandle " + varH + " = boundDomain.attribute(\""
					+ CodegenNames.escapeJavaString(attr.name()) + "\");");
			w.line("this." + base + "Present = " + varH + " != null && " + varH + ".present();");
			w.line("this." + base + "Nullable = " + varH + " != null && " + varH + ".nullableInSnapshot();");
			w.line("this." + base + "Data = " + varH + " != null ? " + varH + ".dataSeg() : MemorySegment.NULL;");
			w.line("this." + base + "Validity = " + varH + " != null ? " + varH
					+ ".validitySeg() : MemorySegment.NULL;");
			w.line("this." + base + "Offsets = " + varH + " != null ? " + varH + ".offsetsSeg() : MemorySegment.NULL;");
		}
		w.endBlock();
		w.blankLine();

		w.beginBlock("void positionAt(long denseId)");
		w.line("this.currentDenseId = denseId;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public long denseId()");
		w.line("return currentDenseId;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + idCls + " id()");
		w.line("return new " + idCls + "(currentDenseId);");
		w.endBlock();
		w.blankLine();

		// Per-attribute accessors
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			emitRowViewAttributeMethods(w, attr);
		}

		// toRecord()
		w.beginBlock("public " + recCls + " toRecord()");
		StringBuilder recArgs = new StringBuilder("currentDenseId");
		for (GraphManifest.AttributeDef attr : domain.attributes().values()) {
			String method = safeRowFieldName(attr.name());
			String pascal = CodegenNames.toPascalCase(attr.name());
			boolean opt = CodegenNames.isOptionalAttribute(attr);
			boolean nullable = attr.nullable();
			recArgs.append(", ");
			if (opt && nullable) {
				recArgs.append("(!has").append(pascal).append("() || is").append(pascal).append("Null()) ? null : ")
						.append(method).append("()");
			} else if (opt) {
				recArgs.append("has").append(pascal).append("() ? ").append(method).append("() : null");
			} else if (nullable) {
				recArgs.append("is").append(pascal).append("Null() ? null : ").append(method).append("()");
			} else {
				recArgs.append(method).append("()");
			}
		}
		w.line("return new " + recCls + "(" + recArgs + ");");
		w.endBlock();
		w.blankLine();

		// Off-heap helper methods
		w.beginBlock("private static boolean isBitNull(MemorySegment validitySeg, boolean nullable, long denseId)");
		w.beginBlock("if (!nullable || validitySeg == null || validitySeg.equals(MemorySegment.NULL))");
		w.line("return false;");
		w.endBlock();
		w.line("long byteIdx = denseId >>> 3;");
		w.beginBlock("if (byteIdx >= validitySeg.byteSize())");
		w.line("return false;");
		w.endBlock();
		w.line("return (validitySeg.get(ValueLayout.JAVA_BYTE, byteIdx) & (1 << (denseId & 7))) == 0;");
		w.endBlock();
		w.blankLine();

		w.beginBlock(
				"private static String readVarString(MemorySegment dataSeg, MemorySegment offsetsSeg, long denseId)");
		w.line("long start = Integer.toUnsignedLong(offsetsSeg.get(LE_INT, denseId * 4L));");
		w.line("long end = Integer.toUnsignedLong(offsetsSeg.get(LE_INT, (denseId + 1L) * 4L));");
		w.line("int len = (int) (end - start);");
		w.beginBlock("if (len <= 0)");
		w.line("return \"\";");
		w.endBlock();
		w.line("byte[] bytes = dataSeg.asSlice(start, len).toArray(ValueLayout.JAVA_BYTE);");
		w.line("return new String(bytes, StandardCharsets.UTF_8);");
		w.endBlock();
		w.blankLine();

		w.beginBlock(
				"private static byte[] readVarBytes(MemorySegment dataSeg, MemorySegment offsetsSeg, long denseId)");
		w.line("long start = Integer.toUnsignedLong(offsetsSeg.get(LE_INT, denseId * 4L));");
		w.line("long end = Integer.toUnsignedLong(offsetsSeg.get(LE_INT, (denseId + 1L) * 4L));");
		w.line("int len = (int) (end - start);");
		w.beginBlock("if (len <= 0)");
		w.line("return new byte[0];");
		w.endBlock();
		w.line("return dataSeg.asSlice(start, len).toArray(ValueLayout.JAVA_BYTE);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("private static String readFixedString(MemorySegment dataSeg, long denseId, int width)");
		w.line("byte[] bytes = dataSeg.asSlice(denseId * (long) width, width).toArray(ValueLayout.JAVA_BYTE);");
		w.line("int len = width;");
		w.beginBlock("while (len > 0 && bytes[len - 1] == 0)");
		w.line("len--;");
		w.endBlock();
		w.line("return new String(bytes, 0, len, StandardCharsets.UTF_8);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("private static byte[] readFixedBytes(MemorySegment dataSeg, long denseId, int width)");
		w.line("return dataSeg.asSlice(denseId * (long) width, width).toArray(ValueLayout.JAVA_BYTE);");
		w.endBlock();

		w.endBlock();
		return w.render();
	}

	private void emitRowViewAttributeMethods(JavaSourceWriter w, GraphManifest.AttributeDef attr) {
		String base = safeRowFieldName(attr.name());
		String pascal = CodegenNames.toPascalCase(attr.name());
		String javaType = CodegenNames.rowViewJavaType(attr);
		boolean opt = CodegenNames.isOptionalAttribute(attr);

		if (opt) {
			w.deprecatedIfPresent(attr.deprecated());
			w.beginBlock("public boolean has" + pascal + "()");
			w.line("return " + base + "Present;");
			w.endBlock();
			w.blankLine();
		}

		// Nullability predicate
		w.deprecatedIfPresent(attr.deprecated());
		w.beginBlock("public boolean is" + pascal + "Null()");
		w.line("return !" + base + "Present || isBitNull(" + base + "Validity, " + base + "Nullable, currentDenseId);");
		w.endBlock();
		w.blankLine();

		// Vector zero-allocation copy overload
		if (CodegenNames.isVectorAttribute(attr)) {
			int dim = attr.dimension();
			w.deprecatedIfPresent(attr.deprecated());
			w.beginBlock("public void " + base + "(" + javaType + " dest)");
			if (opt) {
				emitOptionalGuard(w, base, attr.name());
			}
			w.line("Objects.requireNonNull(dest, \"dest must not be null\");");
			w.beginBlock("if (dest.length < " + dim + ")");
			w.line("throw new IllegalArgumentException(\"Destination array length must be >= " + dim
					+ ", got \" + dest.length);");
			w.endBlock();
			emitVectorCopyLoop(w, base, attr.type(), dim);
			w.endBlock();
			w.blankLine();

			// Allocating convenience getter for toRecord() / callers
			w.deprecatedIfPresent(attr.deprecated());
			w.beginBlock("public " + javaType + " " + base + "()");
			if (opt) {
				emitOptionalGuard(w, base, attr.name());
			}
			w.line(javaType + " out = new " + javaType.substring(0, javaType.length() - 2) + "[" + dim + "];");
			w.line(base + "(out);");
			w.line("return out;");
			w.endBlock();
			w.blankLine();
		} else {
			// Scalar / string / bytes getter
			w.deprecatedIfPresent(attr.deprecated());
			w.beginBlock("public " + javaType + " " + base + "()");
			if (opt) {
				emitOptionalGuard(w, base, attr.name());
			}
			emitScalarRead(w, base, attr);
			w.endBlock();
			w.blankLine();
		}

		// Optional fallback helper: <attr>OrDefault(fallback)
		if (opt) {
			w.deprecatedIfPresent(attr.deprecated());
			w.beginBlock("public " + javaType + " " + base + "OrDefault(" + javaType + " fallback)");
			w.beginBlock("if (!" + base + "Present || is" + pascal + "Null())");
			w.line("return fallback;");
			w.endBlock();
			w.line("return " + base + "();");
			w.endBlock();
			w.blankLine();
		}
	}

	private void emitOptionalGuard(JavaSourceWriter w, String base, String attrName) {
		w.beginBlock("if (!" + base + "Present)");
		w.line("throw new IllegalStateException(\"Optional attribute '" + CodegenNames.escapeJavaString(attrName)
				+ "' is not present in mounted snapshot\");");
		w.endBlock();
	}

	private void emitVectorCopyLoop(JavaSourceWriter w, String base, GraphManifest.AttributeType type, int dim) {
		w.line("long baseOffset = currentDenseId * " + dim + "L;");
		w.beginBlock("for (int i = 0; i < " + dim + "; i++)");
		switch (type) {
			case INT8, BOOL -> w.line("dest[i] = " + base + "Data.get(ValueLayout.JAVA_BYTE, baseOffset + i);");
			case INT16 -> w.line("dest[i] = " + base + "Data.get(LE_SHORT, (baseOffset + i) * 2L);");
			case INT32 -> w.line("dest[i] = " + base + "Data.get(LE_INT, (baseOffset + i) * 4L);");
			case INT64, TIMESTAMP_MS, TIMESTAMP_NS, INTERVAL_SEC_32 ->
				w.line("dest[i] = " + base + "Data.get(LE_LONG, (baseOffset + i) * 8L);");
			case FLOAT16 ->
				w.line("dest[i] = Float.float16ToFloat(" + base + "Data.get(LE_SHORT, (baseOffset + i) * 2L));");
			case FLOAT32 -> w.line("dest[i] = " + base + "Data.get(LE_FLOAT, (baseOffset + i) * 4L);");
			case FLOAT64 -> w.line("dest[i] = " + base + "Data.get(LE_DOUBLE, (baseOffset + i) * 8L);");
			default -> w.line("dest[i] = " + base + "Data.get(ValueLayout.JAVA_BYTE, baseOffset + i);");
		}
		w.endBlock();
	}

	private void emitScalarRead(JavaSourceWriter w, String base, GraphManifest.AttributeDef attr) {
		switch (attr.type()) {
			case INT8 -> w.line("return " + base + "Data.get(ValueLayout.JAVA_BYTE, currentDenseId);");
			case BOOL -> w.line("return " + base + "Data.get(ValueLayout.JAVA_BYTE, currentDenseId) != 0;");
			case INT16 -> w.line("return " + base + "Data.get(LE_SHORT, currentDenseId * 2L);");
			case INT32 -> w.line("return " + base + "Data.get(LE_INT, currentDenseId * 4L);");
			case INT64, TIMESTAMP_MS, TIMESTAMP_NS, INTERVAL_SEC_32 ->
				w.line("return " + base + "Data.get(LE_LONG, currentDenseId * 8L);");
			case FLOAT16 -> w.line("return Float.float16ToFloat(" + base + "Data.get(LE_SHORT, currentDenseId * 2L));");
			case FLOAT32 -> w.line("return " + base + "Data.get(LE_FLOAT, currentDenseId * 4L);");
			case FLOAT64 -> w.line("return " + base + "Data.get(LE_DOUBLE, currentDenseId * 8L);");
			case VAR_STRING -> w.line("return readVarString(" + base + "Data, " + base + "Offsets, currentDenseId);");
			case VAR_BYTES -> w.line("return readVarBytes(" + base + "Data, " + base + "Offsets, currentDenseId);");
			case FIXED_STRING ->
				w.line("return readFixedString(" + base + "Data, currentDenseId, " + attr.dimension() + ");");
			case FIXED_BYTES ->
				w.line("return readFixedBytes(" + base + "Data, currentDenseId, " + attr.dimension() + ");");
			case INTERVAL_MS_64 -> w.line("return readFixedBytes(" + base + "Data, currentDenseId, 16);");
		}
	}

	private static String safeRowFieldName(String attrName) {
		String camel = CodegenNames.toCamelCase(attrName);
		if ("denseId".equals(camel) || "id".equals(camel) || "toRecord".equals(camel) || "positionAt".equals(camel)) {
			return camel + "Attr";
		}
		return camel;
	}

	// =========================================================================
	// Artifact 5: Typed Streaming Snapshot Writer (<GraphName>SnapshotWriter.java)
	// =========================================================================

	private String emitSnapshotWriter() {
		JavaSourceWriter w = new JavaSourceWriter(packageName);
		w.addImport("java.io.IOException");
		w.addImport("java.nio.file.Path");
		w.addImport("java.util.Map");
		w.addImport("java.util.Objects");
		w.addImport("org.impulsegraph.builder.api.CompressionScheme");
		w.addImport("org.impulsegraph.builder.api.DomainDefinition");
		w.addImport("org.impulsegraph.builder.api.PrimitiveWidth");
		w.addImport("org.impulsegraph.builder.api.RelationDefinition");
		w.addImport("org.impulsegraph.builder.api.SnapshotBuilder");
		w.addImport("org.impulsegraph.builder.api.Topology");
		w.addImport("org.impulsegraph.builder.spi.AttributeDataSource");
		w.addImport("org.impulsegraph.builder.spi.RelationDataSource");

		String cls = graphClassPrefix + "SnapshotWriter";
		String snapCls = graphClassPrefix + "Snapshot";

		w.line("/** Typed streaming snapshot writer wrapper for {@code " + manifest.graphName() + "}. */");
		w.beginBlock("public final class " + cls);
		w.blankLine();
		w.line("private final SnapshotBuilder builder;");
		w.blankLine();

		w.beginBlock("private " + cls + "()");
		w.line("this.builder = SnapshotBuilder.create().withSchemaProvenance(" + snapCls + ".SCHEMA.graphName(), "
				+ snapCls + ".SCHEMA.version(), " + snapCls + ".SCHEMA.schemaFingerprintSha256());");
		if (packageName != null && !packageName.isBlank()) {
			w.line("this.builder.addFooterMetadata(\"impulse.schema.package\", \""
					+ CodegenNames.escapeJavaString(packageName) + "\");");
		}
		w.endBlock();
		w.blankLine();

		w.beginBlock("public static " + cls + " create()");
		w.line("return new " + cls + "();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " withStagingDirectory(Path tempDir)");
		w.line("builder.withStagingDirectory(tempDir);");
		w.line("return this;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " withStagingMemoryLimit(long bytes)");
		w.line("builder.withStagingMemoryLimit(bytes);");
		w.line("return this;");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public " + cls + " addFooterMetadata(String key, String value)");
		w.line("builder.addFooterMetadata(key, value);");
		w.line("return this;");
		w.endBlock();
		w.blankLine();

		// Typed domain methods
		for (Map.Entry<String, GraphManifest.DomainDef> entry : manifest.domains().entrySet()) {
			String domainName = entry.getKey();
			GraphManifest.DomainDef domain = entry.getValue();
			String domClass = domainClassNames.get(domainName);
			String widthEnum = "PrimitiveWidth." + domain.idWidth().name();

			w.deprecatedIfPresent(domain.deprecated());
			w.beginBlock("public " + cls + " domain" + domClass + "(long cardinality)");
			w.line("return domain" + domClass + "(cardinality, null, Map.of());");
			w.endBlock();
			w.blankLine();

			w.deprecatedIfPresent(domain.deprecated());
			w.beginBlock("public " + cls + " domain" + domClass
					+ "(long cardinality, Map<String, AttributeDataSource> attributes)");
			w.line("return domain" + domClass + "(cardinality, null, attributes);");
			w.endBlock();
			w.blankLine();

			w.deprecatedIfPresent(domain.deprecated());
			w.beginBlock("public " + cls + " domain" + domClass
					+ "(long cardinality, String primaryKeyAttribute, Map<String, AttributeDataSource> attributes)");
			w.line("DomainDefinition.Builder def = DomainDefinition.builder(cardinality).idWidth(" + widthEnum + ");");
			w.beginBlock("if (primaryKeyAttribute != null && !primaryKeyAttribute.isBlank())");
			w.line("def.withPrimaryKeyIndex(true).primaryKeyAttribute(primaryKeyAttribute);");
			w.endBlock();
			w.beginBlock("if (attributes != null)");
			w.beginBlock("for (Map.Entry<String, AttributeDataSource> e : attributes.entrySet())");
			w.line("def.addAttribute(e.getKey(), e.getValue());");
			w.endBlock();
			w.endBlock();
			w.line("builder.addDomain(\"" + CodegenNames.escapeJavaString(domainName) + "\", def.build());");
			w.line("return this;");
			w.endBlock();
			w.blankLine();
		}

		// Typed relation methods
		for (Map.Entry<String, GraphManifest.RelationDef> entry : manifest.relations().entrySet()) {
			String relName = entry.getKey();
			GraphManifest.RelationDef rel = entry.getValue();
			String relPascal = CodegenNames.toPascalCase(relName);
			boolean needsCsc = rel.directions().contains(GraphManifest.Direction.IN) || rel.symmetric();

			w.deprecatedIfPresent(rel.deprecated());
			w.beginBlock("public " + cls + " relation" + relPascal + "(RelationDataSource dataSource)");
			w.line("return relation" + relPascal + "(dataSource, Map.of());");
			w.endBlock();
			w.blankLine();

			w.deprecatedIfPresent(rel.deprecated());
			w.beginBlock("public " + cls + " relation" + relPascal
					+ "(RelationDataSource dataSource, Map<String, AttributeDataSource> attributes)");
			w.line("Objects.requireNonNull(dataSource, \"dataSource must not be null\");");
			w.line("RelationDefinition.Builder def = RelationDefinition.builder(\""
					+ CodegenNames.escapeJavaString(rel.source()) + "\", \""
					+ CodegenNames.escapeJavaString(rel.target())
					+ "\").addTopology(Topology.CSR, CompressionScheme.RAW).dataSource(dataSource);");
			if (needsCsc) {
				w.line("def.addTopology(Topology.CSC, CompressionScheme.RAW);");
			}
			w.beginBlock("if (attributes != null)");
			w.beginBlock("for (Map.Entry<String, AttributeDataSource> e : attributes.entrySet())");
			w.line("def.addAttribute(e.getKey(), e.getValue());");
			w.endBlock();
			w.endBlock();
			w.line("builder.addRelation(\"" + CodegenNames.escapeJavaString(relName) + "\", def.build());");
			w.line("return this;");
			w.endBlock();
			w.blankLine();
		}

		w.beginBlock("public void writeTo(Path path) throws IOException");
		w.line("builder.writeTo(path);");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public byte[] toByteArray() throws IOException");
		w.line("return builder.toByteArray();");
		w.endBlock();
		w.blankLine();

		w.beginBlock("public SnapshotBuilder underlying()");
		w.line("return builder;");
		w.endBlock();

		w.endBlock();
		return w.render();
	}
}
