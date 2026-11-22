package org.impulsegraph.storage.schema;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructs a normative {@link GraphManifest} (and canonical YAML) from any
 * mounted binary {@code .imps} snapshot ({@code v0.9} or {@code v0.10}).
 */
public final class SnapshotSchemaExtractor {

	private static final long SECTION_FEATURE_VIRTUAL = 0x04L;

	private SnapshotSchemaExtractor() {
	}

	public static GraphManifest extract(BinarySnapshotLoader.LoadedSnapshot loaded) {
		Objects.requireNonNull(loaded, "loaded snapshot must not be null");

		String graphName = loaded.getMetadata("impulse.schema.name");
		if (graphName == null || graphName.isBlank()) {
			graphName = "ImpulseGraph";
		}
		String version = loaded.getMetadata("impulse.schema.version");
		if (version == null || version.isBlank()) {
			version = "0." + loaded.version();
		}
		String packageName = loaded.getMetadata("impulse.schema.package");

		// Precompute indexed attributes by (domainId, relationId, attrIndex)
		Set<String> indexedDomainAttrs = new HashSet<>();
		Set<String> indexedRelationAttrs = new HashSet<>();
		for (BinarySnapshotLoader.LoadedIndex idx : loaded.indices()) {
			if (idx.relationId() == 0xFFFF || idx.relationId() < 0) {
				indexedDomainAttrs.add(idx.domainId() + ":" + idx.attributeIndex());
			} else {
				indexedRelationAttrs.add(idx.relationId() + ":" + idx.attributeIndex());
			}
		}

		// Map relationId -> name and relationId -> RelationSnapshot
		Map<Integer, String> relIdToName = new HashMap<>();
		Map<Integer, RelationSnapshot> relIdToSnap = new HashMap<>();
		if (loaded.graph() != null) {
			for (Map.Entry<String, org.impulsegraph.api.RelationSnapshot> entry : loaded.graph()
					.getAllRelationSnapshots().entrySet()) {
				if (entry.getValue() instanceof RelationSnapshot rs) {
					int rid = rs.getRelationId();
					if (rid >= 0) {
						relIdToName.putIfAbsent(rid, entry.getKey());
						relIdToSnap.putIfAbsent(rid, rs);
					}
				}
			}
		}

		// Infer per-domain ID width from relation nodeIdWidth where available
		Map<Integer, GraphManifest.IdWidth> domainIdWidths = new HashMap<>();
		for (BinarySnapshotLoader.LoadedRelation lr : loaded.relationsById().values()) {
			GraphManifest.IdWidth w = GraphManifest.IdWidth.fromByteWidth(lr.nodeIdWidth());
			domainIdWidths.merge(lr.tgtDomainId(), w, (a, b) -> b.byteWidth() > a.byteWidth() ? b : a);
			domainIdWidths.putIfAbsent(lr.srcDomainId(), w);
		}

		// Extract Domains in ascending DomainID order
		Map<String, GraphManifest.DomainDef> domains = new LinkedHashMap<>();
		List<BinarySnapshotLoader.LoadedDomain> sortedDomains = new ArrayList<>(loaded.domainsById().values());
		sortedDomains.sort(Comparator.comparingInt(BinarySnapshotLoader.LoadedDomain::domainId));

		for (BinarySnapshotLoader.LoadedDomain dom : sortedDomains) {
			GraphManifest.IdWidth idWidth = domainIdWidths.getOrDefault(dom.domainId(), GraphManifest.IdWidth.UINT32);
			GraphManifest.KeyType keyType = inferKeyType(dom);
			Map<String, GraphManifest.AttributeDef> attrs = new LinkedHashMap<>();
			List<BinarySnapshotLoader.LoadedAttribute> loadedAttrs = dom.attributes();
			for (int i = 0; i < loadedAttrs.size(); i++) {
				BinarySnapshotLoader.LoadedAttribute la = loadedAttrs.get(i);
				boolean indexed = indexedDomainAttrs.contains(dom.domainId() + ":" + i);
				attrs.put(la.name(), toAttributeDef(la, indexed));
			}
			domains.put(dom.name(), new GraphManifest.DomainDef(null, idWidth, keyType, false, null, null, attrs));
		}

		// Extract Relations & Virtual Relations in ascending RelationID order
		Map<String, GraphManifest.RelationDef> relations = new LinkedHashMap<>();
		Map<String, GraphManifest.VirtualRelationDef> virtualRelations = new LinkedHashMap<>();

		List<BinarySnapshotLoader.LoadedRelation> sortedRels = new ArrayList<>(loaded.relationsById().values());
		sortedRels.sort(Comparator.comparingInt(BinarySnapshotLoader.LoadedRelation::relationId));

		for (BinarySnapshotLoader.LoadedRelation lr : sortedRels) {
			int rid = lr.relationId();
			String relName = relIdToName.getOrDefault(rid, "rel_" + rid);
			RelationSnapshot rs = relIdToSnap.get(rid);
			boolean isVirtual = rs != null
					&& ((rs.getSectionFeatures() & SECTION_FEATURE_VIRTUAL) != 0 || rs.getComponentIds().length > 0);

			if (isVirtual) {
				List<String> components = new ArrayList<>();
				for (int compId : rs.getComponentIds()) {
					components.add(relIdToName.getOrDefault(compId, "rel_" + compId));
				}
				virtualRelations.put(relName, new GraphManifest.VirtualRelationDef(components, null));
				continue;
			}

			BinarySnapshotLoader.LoadedDomain srcDom = loaded.getDomain(lr.srcDomainId());
			BinarySnapshotLoader.LoadedDomain tgtDom = loaded.getDomain(lr.tgtDomainId());
			String srcName = srcDom != null ? srcDom.name() : ("domain_" + lr.srcDomainId());
			String tgtName = tgtDom != null ? tgtDom.name() : ("domain_" + lr.tgtDomainId());

			EnumSet<GraphManifest.Direction> dirs = EnumSet.of(GraphManifest.Direction.OUT);
			if (rs != null && rs.hasCsc()) {
				dirs.add(GraphManifest.Direction.IN);
			}

			Map<String, GraphManifest.AttributeDef> attrs = new LinkedHashMap<>();
			List<BinarySnapshotLoader.LoadedAttribute> relAttrs = lr.attributes() != null ? lr.attributes() : List.of();
			for (int i = 0; i < relAttrs.size(); i++) {
				BinarySnapshotLoader.LoadedAttribute la = relAttrs.get(i);
				boolean indexed = indexedRelationAttrs.contains(rid + ":" + i);
				attrs.put(la.name(), toAttributeDef(la, indexed));
			}

			relations.put(relName, new GraphManifest.RelationDef(srcName, tgtName, null, dirs,
					GraphManifest.Cardinality.MANY_TO_MANY, false, null, false, null, null, attrs));
		}

		// Handle snapshots where relations exist in GraphSnapshot without relationsById
		if (relations.isEmpty() && loaded.graph() != null) {
			for (Map.Entry<String, org.impulsegraph.api.RelationSnapshot> entry : loaded.graph()
					.getAllRelationSnapshots().entrySet()) {
				if (entry.getValue() instanceof RelationSnapshot rs) {
					BinarySnapshotLoader.LoadedDomain srcDom = loaded.getDomain(rs.getSrcDomainId());
					BinarySnapshotLoader.LoadedDomain tgtDom = loaded.getDomain(rs.getTgtDomainId());
					String srcName = srcDom != null ? srcDom.name() : ("domain_" + rs.getSrcDomainId());
					String tgtName = tgtDom != null ? tgtDom.name() : ("domain_" + rs.getTgtDomainId());
					EnumSet<GraphManifest.Direction> dirs = EnumSet.of(GraphManifest.Direction.OUT);
					if (rs.hasCsc()) {
						dirs.add(GraphManifest.Direction.IN);
					}
					Map<String, GraphManifest.AttributeDef> attrs = new LinkedHashMap<>();
					for (BinarySnapshotLoader.LoadedAttribute la : rs.getLoadedAttributes()) {
						attrs.put(la.name(), toAttributeDef(la, false));
					}
					relations.put(entry.getKey(), new GraphManifest.RelationDef(srcName, tgtName, null, dirs,
							GraphManifest.Cardinality.MANY_TO_MANY, false, null, false, null, null, attrs));
				}
			}
		}

		return new GraphManifest(packageName, graphName, version, Map.of(), domains, relations, virtualRelations);
	}

	public static String extractYaml(BinarySnapshotLoader.LoadedSnapshot loaded) {
		return SchemaYamlCodec.toYaml(extract(loaded));
	}

	private static GraphManifest.KeyType inferKeyType(BinarySnapshotLoader.LoadedDomain dom) {
		BinarySnapshotLoader.LoadedAttribute keyAttr = dom.getKeyAttribute();
		if (keyAttr != null) {
			byte code = (byte) (keyAttr.typeCode() & 0x7F);
			return switch (code) {
				case 0x0B -> GraphManifest.KeyType.STRING;
				case 0x03, 0x13 -> GraphManifest.KeyType.INT32;
				case 0x04, 0x14 -> GraphManifest.KeyType.INT64;
				case 0x0C -> GraphManifest.KeyType.UUID;
				case 0x0A -> keyAttr.dimension() == 16 ? GraphManifest.KeyType.UUID : GraphManifest.KeyType.BYTES;
				default -> GraphManifest.KeyType.fromBinaryCode(dom.keyType());
			};
		}
		return GraphManifest.KeyType.fromBinaryCode(dom.keyType());
	}

	private static GraphManifest.AttributeDef toAttributeDef(BinarySnapshotLoader.LoadedAttribute la, boolean indexed) {
		GraphManifest.AttributeType type = GraphManifest.AttributeType.fromBinaryTypeCode(la.typeCode());
		int dim = type.isVariableLength() ? 1 : Math.max(1, la.dimension());
		return new GraphManifest.AttributeDef(la.name(), type, dim, la.isNullable(), indexed, false, null, null);
	}
}
