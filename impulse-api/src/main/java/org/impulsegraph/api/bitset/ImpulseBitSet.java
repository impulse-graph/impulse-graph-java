package org.impulsegraph.api.bitset;

public interface ImpulseBitSet extends Iterable<Integer> {
	void set(int bitIndex);
	boolean get(int bitIndex);
	void clear(int bitIndex);
	void clear();
	boolean isEmpty();
	long cardinality();
	int nextSetBit(int fromIndex);

	void or(ImpulseBitSet set);
	void and(ImpulseBitSet set);
	void andNot(ImpulseBitSet set);

	/**
	 * Executes the given action for each set bit in this bitset.
	 */
	default void forEachSetBit(java.util.function.IntConsumer action) {
		for (int i = nextSetBit(0); i >= 0; i = nextSetBit(i + 1)) {
			action.accept(i);
		}
	}

	/**
	 * Returns an IntStream of indices for which this bitset contains a bit in the
	 * set state.
	 */
	default java.util.stream.IntStream toStream() {
		return java.util.stream.StreamSupport
				.intStream(java.util.Spliterators.spliteratorUnknownSize(new java.util.PrimitiveIterator.OfInt() {
					int next = nextSetBit(0);
					@Override
					public boolean hasNext() {
						return next >= 0;
					}
					@Override
					public int nextInt() {
						if (next < 0)
							throw new java.util.NoSuchElementException();
						int current = next;
						next = nextSetBit(next + 1);
						return current;
					}
				}, java.util.Spliterator.ORDERED | java.util.Spliterator.DISTINCT | java.util.Spliterator.SORTED),
						false);
	}

	@Override
	default java.util.Iterator<Integer> iterator() {
		return new java.util.Iterator<>() {
			int next = nextSetBit(0);

			@Override
			public boolean hasNext() {
				return next >= 0;
			}

			@Override
			public Integer next() {
				if (next < 0)
					throw new java.util.NoSuchElementException();
				int current = next;
				next = nextSetBit(next + 1);
				return current;
			}
		};
	}
}
