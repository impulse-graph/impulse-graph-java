package org.impulsegraph.builder.spi;

import org.impulsegraph.builder.api.DataType;
import org.impulsegraph.builder.api.Nullability;

import java.util.Optional;

/**
 * Data source providing an ordered stream of attribute values for a domain or
 * relation.
 */
public interface AttributeDataSource {

	/**
	 * Data type of the attribute.
	 */
	DataType dataType();

	/**
	 * Declares whether the attribute is nullable or non-null.
	 */
	Nullability nullability();

	/**
	 * Iterator yielding chunks of values.
	 */
	AttributeChunkIterator iterator();

	/**
	 * Optional statistics collector hook for computing distribution metrics into
	 * the footer.
	 */
	default Optional<StatCollector> statCollector() {
		return Optional.empty();
	}
}
