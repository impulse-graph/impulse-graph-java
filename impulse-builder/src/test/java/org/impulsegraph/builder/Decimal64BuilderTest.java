package org.impulsegraph.builder;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DECIMAL64 DataType & StreamingSnapshotWriter Tests")
class Decimal64BuilderTest {

	@Test
	@DisplayName("DataType.decimal64 validates precision and scale bounds")
	void testDataTypeDecimal64Validation() {
		DataType d1 = DataType.decimal64(10, 2);
		assertThat(d1.baseCode()).isEqualTo((byte) 0x15);
		assertThat(d1.typeCode(Nullability.NON_NULL)).isEqualTo((byte) 0x15);
		assertThat(d1.typeCode(Nullability.NULLABLE)).isEqualTo((byte) 0x95);
		assertThat(d1.elementSize()).isEqualTo(8);
		assertThat(d1.dimension()).isEqualTo(1);
		assertThat(d1.precision()).isEqualTo(10);
		assertThat(d1.scale()).isEqualTo(2);
		assertThat(d1.name()).isEqualTo("DECIMAL64(10,2)");

		// Boundary values
		assertThat(DataType.decimal64(1, 0).precision()).isEqualTo(1);
		assertThat(DataType.decimal64(1, 1).scale()).isEqualTo(1);
		assertThat(DataType.decimal64(18, 0).precision()).isEqualTo(18);
		assertThat(DataType.decimal64(18, 18).scale()).isEqualTo(18);

		// Invalid precision/scale
		assertThatThrownBy(() -> DataType.decimal64(0, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DataType.decimal64(19, 2)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DataType.decimal64(5, -1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> DataType.decimal64(5, 6)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("StreamingSnapshotWriter roundtrips non-nullable and nullable DECIMAL64(10, 2) attributes")
	void testStreamingWriterDecimal64Roundtrip(@TempDir Path tempDir) throws Exception {
		long[] prices = {1999L, 4500L, -100L, 0L};
		long[] discounts = {50L, 0L, 250L, 1000L};

		DomainDefinition itemDomain = DomainDefinition.builder(4L).idWidth(PrimitiveWidth.UINT32)
				.addAttribute("price", decimal64Source(10, 2, Nullability.NON_NULL, prices))
				.addAttribute("discount", decimal64Source(10, 2, Nullability.NULLABLE, discounts)).build();

		RelationDefinition related = RelationDefinition.builder("Item", "Item")
				.addTopology(Topology.CSR, CompressionScheme.RAW)
				.dataSource(new SimpleEdgeSource(List.of(new SimpleEdgeSource.Edge(0, 1)))).build();

		Path out = tempDir.resolve("decimal64_streaming.imps");
		SnapshotBuilder.create().withVersion((short) 11).withStagingDirectory(tempDir.resolve("stage"))
				.addDomain("Item", itemDomain).addRelation("RELATED", related).writeTo(out);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(out, arena);
			BinarySnapshotLoader.LoadedDomain dom = loaded.getDomain("Item");
			assertThat(dom).isNotNull();

			BinarySnapshotLoader.LoadedAttribute priceAttr = dom.getAttribute("price");
			assertThat(priceAttr.typeCode()).isEqualTo((byte) 0x15);
			assertThat(priceAttr.isNullable()).isFalse();
			assertThat(priceAttr.precision()).isEqualTo(10);
			assertThat(priceAttr.scale()).isEqualTo(2);
			assertThat(priceAttr.dataOffset() & 127L).isZero();

			BinarySnapshotLoader.LoadedAttribute discAttr = dom.getAttribute("discount");
			assertThat(discAttr.typeCode()).isEqualTo((byte) 0x15);
			assertThat(discAttr.isNullable()).isTrue();
			assertThat(discAttr.precision()).isEqualTo(10);
			assertThat(discAttr.scale()).isEqualTo(2);
			assertThat(discAttr.validityOffset() & 127L).isZero();
			assertThat(discAttr.dataOffset() & 127L).isZero();

			ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			MemorySegment priceSeg = dom.getAttributeSegment("price");
			MemorySegment discSeg = dom.getAttributeSegment("discount");
			for (int i = 0; i < prices.length; i++) {
				assertThat(priceSeg.get(leLong, i * 8L)).isEqualTo(prices[i]);
				assertThat(discSeg.get(leLong, i * 8L)).isEqualTo(discounts[i]);
			}
		}
	}

	private static AttributeDataSource decimal64Source(int precision, int scale, Nullability nullability,
			long[] values) {
		DataType dt = DataType.decimal64(precision, scale);
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return dt;
			}

			@Override
			public Nullability nullability() {
				return nullability;
			}

			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					private int idx = 0;

					@Override
					public boolean hasNext() {
						return idx < values.length;
					}

					@Override
					public int nextChunk(MemorySegment destination, int limit) {
						ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						int count = 0;
						while (idx < values.length && count < limit) {
							destination.set(leLong, (long) count * 8L, values[idx++]);
							count++;
						}
						return count;
					}
				};
			}
		};
	}
}
