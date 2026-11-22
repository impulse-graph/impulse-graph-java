package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.GraphSchema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight, zero-dependency, snapshot-agnostic graph schema view used by
 * {@link SchemaBindValidatorPass} for build-time query validation without
 * requiring a physical {@code .imps} snapshot on disk.
 */
public record SchemaView(Map<String, DomainInfo> domains, Map<String, RelationInfo> relations,
		Map<String, List<String>> virtualRelations) {

	/**
	 * Metadata for a single node or edge attribute column.
	 */
	public record AttributeInfo(String name, String typeName) {
		public AttributeInfo {
			Objects.requireNonNull(name, "name must not be null");
			typeName = typeName != null ? typeName.trim() : "unknown";
		}

		public boolean isNumeric() {
			String t = normalizedType();
			return t.equals("int") || t.equals("int8") || t.equals("int16") || t.equals("int32") || t.equals("int64")
					|| t.equals("uint8") || t.equals("uint16") || t.equals("uint32") || t.equals("uint64")
					|| t.equals("i8") || t.equals("i16") || t.equals("i32") || t.equals("i64") || t.equals("u8")
					|| t.equals("u16") || t.equals("u32") || t.equals("u64") || t.equals("long") || t.equals("short")
					|| t.equals("byte") || t.equals("float") || t.equals("float32") || t.equals("float64")
					|| t.equals("f32") || t.equals("f64") || t.equals("double") || t.equals("number")
					|| t.equals("timestamp") || t.equals("datetime") || t.equals("date");
		}

		public boolean isString() {
			String t = normalizedType();
			return t.equals("string") || t.equals("str") || t.equals("utf8") || t.equals("varchar") || t.equals("text")
					|| t.equals("char");
		}

		public boolean isBoolean() {
			String t = normalizedType();
			return t.equals("bool") || t.equals("boolean");
		}

		public boolean isFloatVector() {
			String t = normalizedType();
			return t.equals("float[]") || t.equals("float32[]") || t.equals("f32[]") || t.equals("float_vector")
					|| t.equals("vector") || t.equals("embedding") || t.startsWith("vector<") || t.startsWith("float[")
					|| t.startsWith("float32[") || t.startsWith("f32[");
		}

		private String normalizedType() {
			return typeName.toLowerCase(Locale.ROOT).replace(" ", "");
		}
	}

	/**
	 * Metadata for a node domain and its declared attribute columns.
	 */
	public record DomainInfo(String name, Map<String, AttributeInfo> attributes) {
		public DomainInfo {
			Objects.requireNonNull(name, "name must not be null");
			attributes = attributes != null ? Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : Map.of();
		}

		public static DomainInfo of(String name, Map<String, String> attributeTypes) {
			Map<String, AttributeInfo> attrs = new LinkedHashMap<>();
			if (attributeTypes != null) {
				for (var entry : attributeTypes.entrySet()) {
					attrs.put(entry.getKey(), new AttributeInfo(entry.getKey(), entry.getValue()));
				}
			}
			return new DomainInfo(name, attrs);
		}

		public AttributeInfo attribute(String attrName) {
			if (attrName == null) {
				return null;
			}
			AttributeInfo exact = attributes.get(attrName);
			if (exact != null) {
				return exact;
			}
			for (var entry : attributes.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(attrName)) {
					return entry.getValue();
				}
			}
			return null;
		}
	}

	/**
	 * Metadata for a directed graph relation, its endpoint domains, supported
	 * traversal directions, and edge attribute columns.
	 */
	public record RelationInfo(String name, String sourceDomain, String targetDomain, boolean hasOutDirection,
			boolean hasInDirection, Map<String, AttributeInfo> attributes) {

		public RelationInfo {
			Objects.requireNonNull(name, "name must not be null");
			sourceDomain = sourceDomain != null ? sourceDomain : "";
			targetDomain = targetDomain != null ? targetDomain : "";
			attributes = attributes != null ? Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : Map.of();
		}

		public static RelationInfo of(String name, String sourceDomain, String targetDomain, boolean hasOutDirection,
				boolean hasInDirection, Map<String, String> attributeTypes) {
			Map<String, AttributeInfo> attrs = new LinkedHashMap<>();
			if (attributeTypes != null) {
				for (var entry : attributeTypes.entrySet()) {
					attrs.put(entry.getKey(), new AttributeInfo(entry.getKey(), entry.getValue()));
				}
			}
			return new RelationInfo(name, sourceDomain, targetDomain, hasOutDirection, hasInDirection, attrs);
		}

		public AttributeInfo attribute(String attrName) {
			if (attrName == null) {
				return null;
			}
			AttributeInfo exact = attributes.get(attrName);
			if (exact != null) {
				return exact;
			}
			for (var entry : attributes.entrySet()) {
				if (entry.getKey().equalsIgnoreCase(attrName)) {
					return entry.getValue();
				}
			}
			return null;
		}
	}

	public SchemaView {
		domains = domains != null ? Collections.unmodifiableMap(new LinkedHashMap<>(domains)) : Map.of();
		relations = relations != null ? Collections.unmodifiableMap(new LinkedHashMap<>(relations)) : Map.of();
		Map<String, List<String>> vrCopy = new LinkedHashMap<>();
		if (virtualRelations != null) {
			for (var entry : virtualRelations.entrySet()) {
				vrCopy.put(entry.getKey(), entry.getValue() != null ? List.copyOf(entry.getValue()) : List.of());
			}
		}
		virtualRelations = Collections.unmodifiableMap(vrCopy);
	}

	public boolean hasDomain(String name) {
		return domain(name) != null;
	}

	public DomainInfo domain(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		DomainInfo exact = domains.get(name);
		if (exact != null) {
			return exact;
		}
		for (var entry : domains.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public boolean hasRelation(String name) {
		return relation(name) != null;
	}

	public RelationInfo relation(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		RelationInfo exact = relations.get(name);
		if (exact != null) {
			return exact;
		}
		for (var entry : relations.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public boolean hasVirtualRelation(String name) {
		return virtualRelationComponents(name) != null;
	}

	public List<String> virtualRelationComponents(String name) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		List<String> exact = virtualRelations.get(name);
		if (exact != null) {
			return exact;
		}
		for (var entry : virtualRelations.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Constructs a {@link SchemaView} from a build-time {@link GraphManifest}.
	 */
	public static SchemaView fromGraphManifest(GraphManifest manifest) {
		Objects.requireNonNull(manifest, "manifest must not be null");
		Map<String, DomainInfo> domainMap = new LinkedHashMap<>();
		if (manifest.domains() != null) {
			for (var entry : manifest.domains().entrySet()) {
				GraphManifest.DomainDef def = entry.getValue();
				domainMap.put(entry.getKey(),
						DomainInfo.of(entry.getKey(), def != null ? def.attributeTypeNames() : Map.of()));
			}
		}

		Map<String, RelationInfo> relMap = new LinkedHashMap<>();
		if (manifest.relations() != null) {
			for (var entry : manifest.relations().entrySet()) {
				GraphManifest.RelationDef def = entry.getValue();
				if (def != null) {
					boolean hasOut = def.directions().contains(GraphManifest.Direction.OUT);
					boolean hasIn = def.directions().contains(GraphManifest.Direction.IN) || def.symmetric();
					relMap.put(entry.getKey(), RelationInfo.of(entry.getKey(), def.source(), def.target(), hasOut,
							hasIn, def.attributeTypeNames()));
				}
			}
		}

		Map<String, List<String>> vrelMap = new LinkedHashMap<>();
		if (manifest.virtualRelations() != null) {
			for (var entry : manifest.virtualRelations().entrySet()) {
				GraphManifest.VirtualRelationDef def = entry.getValue();
				vrelMap.put(entry.getKey(), def != null && def.components() != null ? def.components() : List.of());
			}
		}

		return new SchemaView(domainMap, relMap, vrelMap);
	}

	/**
	 * Constructs a {@link SchemaView} from a loaded {@link ImpulseGraphSnapshot}.
	 */
	public static SchemaView fromSnapshot(ImpulseGraphSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Map<String, DomainInfo> domainMap = new LinkedHashMap<>();
		if (snapshot.getDomainNames() != null) {
			for (String domainName : snapshot.getDomainNames()) {
				Map<String, AttributeInfo> attrs = new LinkedHashMap<>();
				GraphSchema schema = snapshot.getSchema(domainName);
				if (schema != null) {
					GraphSchema.EntityDef entity = schema.getEntity(domainName);
					if (entity != null) {
						for (var entry : entity.attributes().entrySet()) {
							attrs.put(entry.getKey(), new AttributeInfo(entry.getKey(), entry.getValue()));
						}
					}
				}
				List<String> attrNames = snapshot.getDomainAttributeNames(domainName);
				if (attrNames != null) {
					for (String attrName : attrNames) {
						attrs.putIfAbsent(attrName, new AttributeInfo(attrName, "unknown"));
					}
				}
				domainMap.put(domainName, new DomainInfo(domainName, attrs));
			}
		}

		Map<String, RelationInfo> relMap = new LinkedHashMap<>();
		Map<String, RelationSnapshot> allRels = snapshot.getAllRelationSnapshots();
		if (allRels != null) {
			for (var entry : allRels.entrySet()) {
				String relName = entry.getKey();
				RelationSnapshot relSnap = entry.getValue();
				String srcDomain = snapshot.getRelationSourceDomainName(relName);
				String tgtDomain = snapshot.getRelationTargetDomainName(relName);
				boolean hasOut = relSnap == null || relSnap.hasCsr();
				boolean hasIn = relSnap != null && relSnap.hasCsc();
				relMap.put(relName, new RelationInfo(relName, srcDomain != null ? srcDomain : "",
						tgtDomain != null ? tgtDomain : "", hasOut, hasIn, Map.of()));
			}
		}

		return new SchemaView(domainMap, relMap, Map.of());
	}

	/**
	 * Returns a fluent builder for constructing a {@link SchemaView}
	 * programmatically.
	 */
	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private final Map<String, DomainInfo> domains = new LinkedHashMap<>();
		private final Map<String, RelationInfo> relations = new LinkedHashMap<>();
		private final Map<String, List<String>> virtualRelations = new LinkedHashMap<>();

		public Builder domain(String name, Map<String, String> attributeTypes) {
			domains.put(name, DomainInfo.of(name, attributeTypes));
			return this;
		}

		public Builder domain(DomainInfo domain) {
			Objects.requireNonNull(domain, "domain must not be null");
			domains.put(domain.name(), domain);
			return this;
		}

		public Builder relation(String name, String sourceDomain, String targetDomain, boolean hasOutDirection,
				boolean hasInDirection, Map<String, String> attributeTypes) {
			relations.put(name,
					RelationInfo.of(name, sourceDomain, targetDomain, hasOutDirection, hasInDirection, attributeTypes));
			return this;
		}

		public Builder relation(RelationInfo relation) {
			Objects.requireNonNull(relation, "relation must not be null");
			relations.put(relation.name(), relation);
			return this;
		}

		public Builder virtualRelation(String name, List<String> components) {
			virtualRelations.put(name, components != null ? List.copyOf(components) : List.of());
			return this;
		}

		public SchemaView build() {
			return new SchemaView(domains, relations, virtualRelations);
		}
	}
}
