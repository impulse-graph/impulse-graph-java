package org.impulsegraph.api.schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Semantic validator for {@link GraphManifest} schema definitions.
 */
public final class SchemaValidator {

	private SchemaValidator() {
	}

	/**
	 * Validates structural and semantic invariants of a {@link GraphManifest}.
	 *
	 * @return list of human-readable validation error messages (empty if valid)
	 */
	public static List<String> validate(GraphManifest manifest) {
		Objects.requireNonNull(manifest, "manifest must not be null");
		List<String> errors = new ArrayList<>();

		// Validate domain attributes
		for (Map.Entry<String, GraphManifest.DomainDef> domEntry : manifest.domains().entrySet()) {
			String domainName = domEntry.getKey();
			GraphManifest.DomainDef domain = domEntry.getValue();
			validateAttributes("domains." + domainName, domain.attributes(), errors);
		}

		// Track inverse aliases per target domain to detect collisions
		Map<String, Map<String, String>> inverseAliasesByTarget = new HashMap<>();

		for (Map.Entry<String, GraphManifest.RelationDef> relEntry : manifest.relations().entrySet()) {
			String relName = relEntry.getKey();
			GraphManifest.RelationDef rel = relEntry.getValue();

			if (rel.source() == null || rel.source().isBlank()) {
				errors.add("Relation '" + relName + "' is missing required 'source' domain");
			} else if (!manifest.domains().containsKey(rel.source())) {
				errors.add("Relation '" + relName + "' references unknown source domain '" + rel.source() + "'");
			}

			if (rel.target() == null || rel.target().isBlank()) {
				errors.add("Relation '" + relName + "' is missing required 'target' domain");
			} else if (!manifest.domains().containsKey(rel.target())) {
				errors.add("Relation '" + relName + "' references unknown target domain '" + rel.target() + "'");
			}

			if (rel.symmetric() && rel.source() != null && rel.target() != null && !rel.source().equals(rel.target())) {
				errors.add("Relation '" + relName + "' is marked symmetric=true but source '" + rel.source()
						+ "' differs from target '" + rel.target() + "'");
			}

			if (rel.inverseAlias() != null && !rel.inverseAlias().isBlank() && rel.target() != null) {
				Map<String, String> targetAliases = inverseAliasesByTarget.computeIfAbsent(rel.target(),
						k -> new HashMap<>());
				String existing = targetAliases.putIfAbsent(rel.inverseAlias(), relName);
				if (existing != null) {
					errors.add("Duplicate inverseAlias '" + rel.inverseAlias() + "' on target domain '" + rel.target()
							+ "' (declared by both '" + existing + "' and '" + relName + "')");
				}
			}

			validateAttributes("relations." + relName, rel.attributes(), errors);
		}

		// Validate virtual relations
		for (Map.Entry<String, GraphManifest.VirtualRelationDef> vrEntry : manifest.virtualRelations().entrySet()) {
			String vrName = vrEntry.getKey();
			GraphManifest.VirtualRelationDef vr = vrEntry.getValue();
			if (vr.components().isEmpty()) {
				errors.add("Virtual relation '" + vrName + "' must declare at least one component relation");
			}
			for (String component : vr.components()) {
				if (!manifest.relations().containsKey(component)) {
					errors.add("Virtual relation '" + vrName + "' references unknown component relation '" + component
							+ "'");
				}
			}
		}

		return List.copyOf(errors);
	}

	private static void validateAttributes(String parentPath, Map<String, GraphManifest.AttributeDef> attributes,
			List<String> errors) {
		for (Map.Entry<String, GraphManifest.AttributeDef> attrEntry : attributes.entrySet()) {
			String attrName = attrEntry.getKey();
			GraphManifest.AttributeDef attr = attrEntry.getValue();
			if (attr.type() == GraphManifest.AttributeType.FIXED_STRING
					|| attr.type() == GraphManifest.AttributeType.FIXED_BYTES) {
				if (attr.dimension() <= 0) {
					errors.add("Attribute '" + parentPath + "." + attrName + "' of type " + attr.type()
							+ " must have dimension > 0 (got " + attr.dimension() + ")");
				}
			} else if (!attr.type().isVariableLength() && attr.dimension() <= 0) {
				errors.add("Attribute '" + parentPath + "." + attrName + "' of type " + attr.type()
						+ " must have dimension >= 1 (got " + attr.dimension() + ")");
			}
		}
	}
}
