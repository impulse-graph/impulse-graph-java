package org.impulsegraph.storage.schema;

import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable table of pre-resolved domain, relation, and off-heap attribute
 * handles produced by {@link SnapshotContractValidator#validateAndBind}.
 * Eliminates runtime string/index lookups on the hot query path.
 */
public final class BoundSchemaHandles {

	public record BoundAttributeHandle(String name, int attributeIndex, GraphManifest.AttributeType type, int dimension,
			MemorySegment dataSeg, MemorySegment validitySeg, MemorySegment offsetsSeg, boolean present,
			boolean nullableInSnapshot) {

		public BoundAttributeHandle {
			Objects.requireNonNull(name, "name must not be null");
			Objects.requireNonNull(type, "type must not be null");
			dataSeg = dataSeg != null ? dataSeg : MemorySegment.NULL;
			validitySeg = validitySeg != null ? validitySeg : MemorySegment.NULL;
			offsetsSeg = offsetsSeg != null ? offsetsSeg : MemorySegment.NULL;
		}

		public static BoundAttributeHandle absent(GraphManifest.AttributeDef def) {
			return new BoundAttributeHandle(def.name(), -1, def.type(), def.dimension(), MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, false, false);
		}
	}

	public record BoundDomainHandle(String name, int domainId, long nodeCount, BinarySnapshotLoader.LoadedDomain domain,
			boolean present, Map<String, BoundAttributeHandle> attributes) {

		public BoundDomainHandle {
			Objects.requireNonNull(name, "name must not be null");
			attributes = attributes != null ? Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : Map.of();
		}

		public BoundAttributeHandle attribute(String attrName) {
			return attributes.get(attrName);
		}

		public static BoundDomainHandle absent(String name, GraphManifest.DomainDef def) {
			Map<String, BoundAttributeHandle> absentAttrs = new LinkedHashMap<>();
			for (GraphManifest.AttributeDef attrDef : def.attributes().values()) {
				absentAttrs.put(attrDef.name(), BoundAttributeHandle.absent(attrDef));
			}
			return new BoundDomainHandle(name, -1, 0L, null, false, absentAttrs);
		}
	}

	public record BoundRelationHandle(String name, int relationId, RelationSnapshot relation, boolean hasCsc,
			boolean present, Map<String, BoundAttributeHandle> attributes) {

		public BoundRelationHandle {
			Objects.requireNonNull(name, "name must not be null");
			attributes = attributes != null ? Collections.unmodifiableMap(new LinkedHashMap<>(attributes)) : Map.of();
		}

		public BoundAttributeHandle attribute(String attrName) {
			return attributes.get(attrName);
		}

		public static BoundRelationHandle absent(String name, GraphManifest.RelationDef def) {
			Map<String, BoundAttributeHandle> absentAttrs = new LinkedHashMap<>();
			for (GraphManifest.AttributeDef attrDef : def.attributes().values()) {
				absentAttrs.put(attrDef.name(), BoundAttributeHandle.absent(attrDef));
			}
			return new BoundRelationHandle(name, -1, null, false, false, absentAttrs);
		}
	}

	private final GraphManifest consumerSchema;
	private final Map<String, BoundDomainHandle> domains;
	private final Map<String, BoundRelationHandle> relations;

	public BoundSchemaHandles(GraphManifest consumerSchema, Map<String, BoundDomainHandle> domains,
			Map<String, BoundRelationHandle> relations) {
		this.consumerSchema = Objects.requireNonNull(consumerSchema, "consumerSchema must not be null");
		this.domains = domains != null ? Collections.unmodifiableMap(new LinkedHashMap<>(domains)) : Map.of();
		this.relations = relations != null ? Collections.unmodifiableMap(new LinkedHashMap<>(relations)) : Map.of();
	}

	public GraphManifest consumerSchema() {
		return consumerSchema;
	}

	public Map<String, BoundDomainHandle> domains() {
		return domains;
	}

	public Map<String, BoundRelationHandle> relations() {
		return relations;
	}

	public BoundDomainHandle domain(String domainName) {
		return domains.get(domainName);
	}

	public BoundRelationHandle relation(String relationName) {
		return relations.get(relationName);
	}
}
