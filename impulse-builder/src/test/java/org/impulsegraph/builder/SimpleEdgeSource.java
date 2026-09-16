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
				int sWidth = limit > 0 ? (int) (srcIds.byteSize() / limit) : 4;
				int tWidth = limit > 0 ? (int) (tgtIds.byteSize() / limit) : 4;
				while (index < edges.size() && count < limit) {
					Edge e = edges.get(index++);
					if (sWidth == 2) {
						srcIds.setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, count, (short) e.src());
					} else if (sWidth == 8) {
						srcIds.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, count, e.src());
					} else {
						srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.src());
					}

					if (tWidth == 2) {
						tgtIds.setAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, count, (short) e.tgt());
					} else if (tWidth == 8) {
						tgtIds.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, count, e.tgt());
					} else {
						tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) e.tgt());
					}
					count++;
				}
				return count;
			}
		};
	}
}
