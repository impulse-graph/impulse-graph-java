package org.impulsegraph.api.spi;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.traversal.DomainView;

/**
 * Service Provider Interface (SPI) for instantiating execution engine
 * components.
 */
public interface ImpulseEngineProvider {

	/**
	 * Creates or obtains a domain view anchor for initiating fluent graph
	 * traversals.
	 *
	 * @param snapshot
	 *            the graph snapshot context
	 * @param domainName
	 *            domain name
	 * @param domainId
	 *            domain numeric identifier
	 * @param nodeCount
	 *            domain entity node count
	 * @return the domain view instance
	 */
	DomainView createDomainView(ImpulseGraphSnapshot snapshot, String domainName, int domainId, long nodeCount);

	/**
	 * Prepares a parameterized graph statement for repeated query execution.
	 *
	 * @param snapshot
	 *            the graph snapshot context
	 * @param query
	 *            the query text
	 * @return the compiled or prepared statement
	 */
	ImpulseStatement createStatement(ImpulseGraphSnapshot snapshot, String query);

	/**
	 * Loads a graph snapshot from a file path using the specified arena.
	 *
	 * @param path
	 *            the file path
	 * @param arena
	 *            the FFM arena for memory allocation
	 * @return the loaded snapshot
	 */
	default ImpulseGraphSnapshot loadSnapshot(java.nio.file.Path path, java.lang.foreign.Arena arena) {
		throw new UnsupportedOperationException("Not implemented by this engine provider");
	}
}
