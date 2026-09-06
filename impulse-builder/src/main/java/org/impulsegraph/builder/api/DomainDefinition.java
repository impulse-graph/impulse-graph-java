package org.impulsegraph.builder.api;

import org.impulsegraph.builder.spi.AttributeDataSource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Definition of a Node Domain within the Impulse graph schema. Represents an
 * independent dense ID space (0 .. cardinality - 1).
 */
public final class DomainDefinition {

	private final long cardinality;
	private final PrimitiveWidth idWidth;
	private final boolean hasPrimaryKeyIndex;
	private final Map<String, AttributeDataSource> attributes;

	private DomainDefinition(long cardinality, PrimitiveWidth idWidth, boolean hasPrimaryKeyIndex,
			Map<String, AttributeDataSource> attributes) {
		this.cardinality = cardinality;
		this.idWidth = idWidth;
		this.hasPrimaryKeyIndex = hasPrimaryKeyIndex;
		this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
	}

	public static Builder builder(long cardinality) {
		return new Builder(cardinality);
	}

	/**
	 * Returns the exact physical node count in this domain (not a capacity bound).
	 */
	public long getCardinality() {
		return cardinality;
	}

	/**
	 * Returns the primitive integer width for node IDs in this domain.
	 */
	public PrimitiveWidth getIdWidth() {
		return idWidth;
	}

	/**
	 * Returns true if a primary key reverse lookup index (String/UUID -> Dense ID)
	 * is materialized.
	 */
	public boolean hasPrimaryKeyIndex() {
		return hasPrimaryKeyIndex;
	}

	/**
	 * Returns an immutable map of attribute names to their chunked data sources.
	 */
	public Map<String, AttributeDataSource> getAttributes() {
		return attributes;
	}

	public static final class Builder {
		private final long cardinality;
		private PrimitiveWidth idWidth = PrimitiveWidth.UINT32;
		private boolean hasPrimaryKeyIndex = false;
		private final Map<String, AttributeDataSource> attributes = new LinkedHashMap<>();

		private Builder(long cardinality) {
			if (cardinality < 0) {
				throw new IllegalArgumentException("Cardinality cannot be negative: " + cardinality);
			}
			this.cardinality = cardinality;
		}

		public Builder idWidth(PrimitiveWidth idWidth) {
			this.idWidth = Objects.requireNonNull(idWidth, "idWidth cannot be null");
			return this;
		}

		public Builder withPrimaryKeyIndex(boolean enable) {
			this.hasPrimaryKeyIndex = enable;
			return this;
		}

		public Builder addAttribute(String name, AttributeDataSource source) {
			Objects.requireNonNull(name, "Attribute name cannot be null");
			Objects.requireNonNull(source, "AttributeDataSource cannot be null");
			attributes.put(name, source);
			return this;
		}

		public DomainDefinition build() {
			return new DomainDefinition(cardinality, idWidth, hasPrimaryKeyIndex, attributes);
		}
	}
}
