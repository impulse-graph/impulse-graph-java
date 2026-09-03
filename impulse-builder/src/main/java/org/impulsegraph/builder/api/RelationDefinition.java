package org.impulsegraph.builder.api;

import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.builder.spi.RelationDataSource;

import java.util.*;

/**
 * Definition of a Relation connecting a Source Domain to a Target Domain. CSR
 * topology is mandatory; CSC and COO topologies are optional.
 */
public final class RelationDefinition {

	private final String sourceDomain;
	private final String targetDomain;
	private final Map<Topology, CompressionScheme> topologies;
	private final Map<String, AttributeDataSource> attributes;
	private final RelationDataSource dataSource;

	private RelationDefinition(String sourceDomain, String targetDomain, Map<Topology, CompressionScheme> topologies,
			Map<String, AttributeDataSource> attributes, RelationDataSource dataSource) {
		this.sourceDomain = Objects.requireNonNull(sourceDomain, "sourceDomain cannot be null");
		this.targetDomain = Objects.requireNonNull(targetDomain, "targetDomain cannot be null");
		this.topologies = Collections.unmodifiableMap(new LinkedHashMap<>(topologies));
		this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
		this.dataSource = dataSource;
	}

	public static Builder builder(String sourceDomain, String targetDomain) {
		return new Builder(sourceDomain, targetDomain);
	}

	public String getSourceDomain() {
		return sourceDomain;
	}

	public String getTargetDomain() {
		return targetDomain;
	}

	public Map<Topology, CompressionScheme> getTopologies() {
		return topologies;
	}

	public boolean includesTopology(Topology topology) {
		return topologies.containsKey(topology);
	}

	public CompressionScheme getCompression(Topology topology) {
		return topologies.get(topology);
	}

	public Map<String, AttributeDataSource> getAttributes() {
		return attributes;
	}

	public RelationDataSource getDataSource() {
		return dataSource;
	}

	public static final class Builder {
		private final String sourceDomain;
		private final String targetDomain;
		private final Map<Topology, CompressionScheme> topologies = new EnumMap<>(Topology.class);
		private final Map<String, AttributeDataSource> attributes = new LinkedHashMap<>();
		private RelationDataSource dataSource;

		private Builder(String sourceDomain, String targetDomain) {
			this.sourceDomain = Objects.requireNonNull(sourceDomain, "sourceDomain cannot be null");
			this.targetDomain = Objects.requireNonNull(targetDomain, "targetDomain cannot be null");
			// CSR is mandatory by default
			topologies.put(Topology.CSR, CompressionScheme.RAW);
		}

		public Builder addTopology(Topology topology, CompressionScheme compression) {
			Objects.requireNonNull(topology, "topology cannot be null");
			Objects.requireNonNull(compression, "compression cannot be null");
			topologies.put(topology, compression);
			return this;
		}

		public Builder addAttribute(String name, AttributeDataSource source) {
			Objects.requireNonNull(name, "attribute name cannot be null");
			Objects.requireNonNull(source, "attribute data source cannot be null");
			attributes.put(name, source);
			return this;
		}

		public Builder dataSource(RelationDataSource dataSource) {
			this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
			return this;
		}

		public RelationDefinition build() {
			if (!topologies.containsKey(Topology.CSR)) {
				topologies.put(Topology.CSR, CompressionScheme.RAW);
			}
			return new RelationDefinition(sourceDomain, targetDomain, topologies, attributes, dataSource);
		}
	}
}
