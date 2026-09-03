package org.impulsegraph.builder;

import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Optional;

/**
 * Convenient test edge source backed by in-memory edge pairs.
 */
public final class SimpleEdgeSource implements RelationDataSource {

	public record Edge(long src, long tgt) {
	}

	private final List<Edge> edges;

	public SimpleEdgeSource(List<Edge> edges) {
		this.edges = List.copyOf(edges);
	}

	@Override
	public long getEdgeCount() {
		return edges.size();
	}

	@Override
	public EdgeChunkIterator getEdges() {
		return new EdgeChunkIterator() {
			private int index = 0;

			@Override
			public boolean hasNext() {
				return index < edges.size();
			}

			@Override
			public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
				int count = 0;
				while (index < edges.size() && count < limit) {
					Edge e = edges.get(index++);
					srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.src());
					tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.tgt());
					count++;
				}
				return count;
			}
		};
	}
}
