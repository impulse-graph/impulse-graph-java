package org.impulsegraph.storage.schema;

import org.impulsegraph.api.schema.CompatibilityViolation;
import org.impulsegraph.api.schema.CompatibilityViolation.Category;
import org.impulsegraph.api.schema.CompatibilityViolation.Severity;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SnapshotCompatibilityException;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Load-time consumer contract validator enforcing structural subtyping between
 * a consumer's required {@link GraphManifest} and a mounted binary snapshot.
 */
public final class SnapshotContractValidator {

	private SnapshotContractValidator() {
	}

	/**
	 * Validates {@code candidate} against {@code consumerSchema} and binds
	 * pre-resolved domain, relation, and off-heap attribute handles.
	 *
	 * @throws SnapshotCompatibilityException
	 *             if one or more required contract invariants are violated
	 */
	public static BoundSchemaHandles validateAndBind(BinarySnapshotLoader.LoadedSnapshot candidate,
			GraphManifest consumerSchema) {
		Objects.requireNonNull(candidate, "candidate snapshot must not be null");
		Objects.requireNonNull(consumerSchema, "consumerSchema must not be null");

		List<CompatibilityViolation> violations = new ArrayList<>();
		Map<String, BoundSchemaHandles.BoundDomainHandle> boundDomains = new LinkedHashMap<>();
		Map<String, BoundSchemaHandles.BoundRelationHandle> boundRelations = new LinkedHashMap<>();

		// 1. Validate and bind domains
		for (Map.Entry<String, GraphManifest.DomainDef> domEntry : consumerSchema.domains().entrySet()) {
			String domainName = domEntry.getKey();
			GraphManifest.DomainDef domDef = domEntry.getValue();
			BinarySnapshotLoader.LoadedDomain loadedDomain = findDomain(candidate, domainName);

			if (loadedDomain == null) {
				if (domDef.optional()) {
					boundDomains.put(domainName, BoundSchemaHandles.BoundDomainHandle.absent(domainName, domDef));
				} else {
					violations.add(new CompatibilityViolation(Severity.ERROR, Category.MISSING_DOMAIN,
							"domains." + domainName, "domain '" + domainName + "'", "absent",
							"Include domain '" + domainName + "' in snapshot build or mark optional: true in schema"));
				}
				continue;
			}

			Map<String, BoundSchemaHandles.BoundAttributeHandle> boundAttrs = new LinkedHashMap<>();
			for (Map.Entry<String, GraphManifest.AttributeDef> attrEntry : domDef.attributes().entrySet()) {
				String attrName = attrEntry.getKey();
				GraphManifest.AttributeDef attrDef = attrEntry.getValue();
				String path = "domains." + domainName + ".attributes." + attrName;

				int attrIdx = findAttributeIndex(loadedDomain.attributes(), attrName);
				if (attrIdx < 0) {
					if (attrDef.optional()) {
						boundAttrs.put(attrName, BoundSchemaHandles.BoundAttributeHandle.absent(attrDef));
					} else {
						violations.add(new CompatibilityViolation(Severity.ERROR, Category.MISSING_ATTRIBUTE, path,
								attrDef.canonicalTypeString(), "absent", "Add attribute '" + attrName + "' to domain '"
										+ domainName + "' or mark optional: true"));
					}
					continue;
				}

				BinarySnapshotLoader.LoadedAttribute loadedAttr = loadedDomain.getAttribute(attrIdx);
				if (validateAttribute(path, attrDef, loadedAttr, violations)) {
					MemorySegment dataSeg = loadedDomain.getAttributeSegment(attrIdx);
					MemorySegment valSeg = loadedDomain.getValiditySegment(attrIdx);
					MemorySegment offSeg = loadedDomain.getOffsetsSegment(attrIdx);
					int dim = attrDef.type().isVariableLength() ? 1 : Math.max(1, loadedAttr.dimension());
					boundAttrs.put(attrName, new BoundSchemaHandles.BoundAttributeHandle(attrName, attrIdx,
							attrDef.type(), dim, dataSeg, valSeg, offSeg, true, loadedAttr.isNullable()));
				}
			}

			boundDomains.put(domainName, new BoundSchemaHandles.BoundDomainHandle(domainName, loadedDomain.domainId(),
					loadedDomain.nodeCount(), loadedDomain, true, boundAttrs));
		}

		// 2. Validate and bind relations
		for (Map.Entry<String, GraphManifest.RelationDef> relEntry : consumerSchema.relations().entrySet()) {
			String relName = relEntry.getKey();
			GraphManifest.RelationDef relDef = relEntry.getValue();
			RelationSnapshot relSnap = findRelation(candidate, relName);

			if (relSnap == null) {
				if (relDef.optional()) {
					boundRelations.put(relName, BoundSchemaHandles.BoundRelationHandle.absent(relName, relDef));
				} else {
					violations.add(new CompatibilityViolation(Severity.ERROR, Category.MISSING_RELATION,
							"relations." + relName, "relation '" + relName + "'", "absent",
							"Include relation '" + relName + "' in snapshot build or mark optional: true in schema"));
				}
				continue;
			}

			// Validate source and target domain endpoints
			BinarySnapshotLoader.LoadedDomain actualSrc = candidate.getDomain(relSnap.getSrcDomainId());
			BinarySnapshotLoader.LoadedDomain actualTgt = candidate.getDomain(relSnap.getTgtDomainId());
			String actualSrcName = actualSrc != null ? actualSrc.name() : ("domain_" + relSnap.getSrcDomainId());
			String actualTgtName = actualTgt != null ? actualTgt.name() : ("domain_" + relSnap.getTgtDomainId());

			if (relDef.source() != null && !relDef.source().equalsIgnoreCase(actualSrcName)) {
				violations.add(new CompatibilityViolation(Severity.ERROR, Category.ENDPOINT_MISMATCH,
						"relations." + relName + ".source", relDef.source(), actualSrcName,
						"Rebuild snapshot with relation '" + relName + "' originating from domain '" + relDef.source()
								+ "'"));
			}
			if (relDef.target() != null && !relDef.target().equalsIgnoreCase(actualTgtName)) {
				violations.add(new CompatibilityViolation(Severity.ERROR, Category.ENDPOINT_MISMATCH,
						"relations." + relName + ".target", relDef.target(), actualTgtName,
						"Rebuild snapshot with relation '" + relName + "' targeting domain '" + relDef.target() + "'"));
			}

			// Validate required directions (IN requires CSC)
			if (relDef.directions().contains(GraphManifest.Direction.IN) && !relSnap.hasCsc()) {
				violations.add(new CompatibilityViolation(Severity.ERROR, Category.MISSING_DIRECTION,
						"relations." + relName + ".directions", "IN (CSC reverse index)", "OUT only (no CSC)",
						"Enable CSC generation for relation '" + relName + "' during snapshot build"));
			}

			// Validate and bind relation attributes
			List<BinarySnapshotLoader.LoadedAttribute> relLoadedAttrs = resolveRelationAttributes(candidate, relSnap);
			List<MemorySegment> attrSegs = relSnap.getAttributeSegments();
			List<MemorySegment> valSegs = relSnap.getValiditySegments();
			List<MemorySegment> offSegs = relSnap.getOffsetsSegments();

			Map<String, BoundSchemaHandles.BoundAttributeHandle> boundAttrs = new LinkedHashMap<>();
			for (Map.Entry<String, GraphManifest.AttributeDef> attrEntry : relDef.attributes().entrySet()) {
				String attrName = attrEntry.getKey();
				GraphManifest.AttributeDef attrDef = attrEntry.getValue();
				String path = "relations." + relName + ".attributes." + attrName;

				int attrIdx = findAttributeIndex(relLoadedAttrs, attrName);
				if (attrIdx < 0) {
					if (attrDef.optional()) {
						boundAttrs.put(attrName, BoundSchemaHandles.BoundAttributeHandle.absent(attrDef));
					} else {
						violations.add(new CompatibilityViolation(Severity.ERROR, Category.MISSING_ATTRIBUTE, path,
								attrDef.canonicalTypeString(), "absent", "Add attribute '" + attrName
										+ "' to relation '" + relName + "' or mark optional: true"));
					}
					continue;
				}

				BinarySnapshotLoader.LoadedAttribute loadedAttr = relLoadedAttrs.get(attrIdx);
				if (validateAttribute(path, attrDef, loadedAttr, violations)) {
					MemorySegment dataSeg = attrIdx < attrSegs.size() ? attrSegs.get(attrIdx) : MemorySegment.NULL;
					MemorySegment valSeg = attrIdx < valSegs.size() ? valSegs.get(attrIdx) : MemorySegment.NULL;
					MemorySegment offSeg = attrIdx < offSegs.size() ? offSegs.get(attrIdx) : MemorySegment.NULL;
					int dim = attrDef.type().isVariableLength() ? 1 : Math.max(1, loadedAttr.dimension());
					boundAttrs.put(attrName, new BoundSchemaHandles.BoundAttributeHandle(attrName, attrIdx,
							attrDef.type(), dim, dataSeg, valSeg, offSeg, true, loadedAttr.isNullable()));
				}
			}

			boundRelations.put(relName, new BoundSchemaHandles.BoundRelationHandle(relName, relSnap.getRelationId(),
					relSnap, relSnap.hasCsc(), true, boundAttrs));
		}

		if (!violations.isEmpty()) {
			throw new SnapshotCompatibilityException(violations);
		}

		return new BoundSchemaHandles(consumerSchema, boundDomains, boundRelations);
	}

	private static boolean validateAttribute(String path, GraphManifest.AttributeDef expected,
			BinarySnapshotLoader.LoadedAttribute actual, List<CompatibilityViolation> violations) {
		boolean ok = true;

		if (!expected.type().matchesBinaryTypeCode(actual.typeCode())) {
			String actualTypeName;
			try {
				actualTypeName = GraphManifest.AttributeType.fromBinaryTypeCode(actual.typeCode()).yamlName();
			} catch (IllegalArgumentException e) {
				actualTypeName = "0x" + Integer.toHexString(actual.typeCode() & 0x7F);
			}
			violations.add(
					new CompatibilityViolation(Severity.ERROR, Category.TYPE_MISMATCH, path, expected.type().yamlName(),
							actualTypeName, "Align column type for '" + path + "' to " + expected.type().yamlName()));
			ok = false;
		}

		if (!expected.type().isVariableLength()) {
			int actualDim = Math.max(1, actual.dimension());
			if (expected.dimension() != actualDim) {
				violations.add(new CompatibilityViolation(Severity.ERROR, Category.DIMENSION_MISMATCH, path,
						String.valueOf(expected.dimension()), String.valueOf(actualDim),
						"Rebuild column '" + path + "' with dimension " + expected.dimension()));
				ok = false;
			}
		}

		// Consumer nullable=false CANNOT accept snapshot nullable=true
		// Consumer nullable=true CAN accept snapshot nullable=false
		if (!expected.nullable() && actual.isNullable()) {
			violations.add(new CompatibilityViolation(Severity.ERROR, Category.NULLABILITY_MISMATCH, path,
					"nullable=false", "nullable=true",
					"Make column '" + path + "' non-null in snapshot or mark nullable: true in consumer schema"));
			ok = false;
		}

		return ok;
	}

	private static BinarySnapshotLoader.LoadedDomain findDomain(BinarySnapshotLoader.LoadedSnapshot candidate,
			String name) {
		BinarySnapshotLoader.LoadedDomain exact = candidate.getDomain(name);
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, BinarySnapshotLoader.LoadedDomain> entry : candidate.domainsByName().entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static RelationSnapshot findRelation(BinarySnapshotLoader.LoadedSnapshot candidate, String name) {
		if (candidate.graph() == null) {
			return null;
		}
		return candidate.graph().getRelationSnapshot(name);
	}

	private static List<BinarySnapshotLoader.LoadedAttribute> resolveRelationAttributes(
			BinarySnapshotLoader.LoadedSnapshot candidate, RelationSnapshot relSnap) {
		List<BinarySnapshotLoader.LoadedAttribute> attrs = relSnap.getLoadedAttributes();
		if (attrs != null && !attrs.isEmpty()) {
			return attrs;
		}
		BinarySnapshotLoader.LoadedRelation lr = candidate.getRelation(relSnap.getRelationId());
		if (lr != null && lr.attributes() != null) {
			return lr.attributes();
		}
		return List.of();
	}

	private static int findAttributeIndex(List<BinarySnapshotLoader.LoadedAttribute> attributes, String name) {
		if (attributes == null || name == null) {
			return -1;
		}
		for (int i = 0; i < attributes.size(); i++) {
			if (name.equals(attributes.get(i).name())) {
				return i;
			}
		}
		for (int i = 0; i < attributes.size(); i++) {
			if (name.equalsIgnoreCase(attributes.get(i).name())) {
				return i;
			}
		}
		return -1;
	}
}
