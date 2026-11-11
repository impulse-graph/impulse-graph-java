package org.impulsegraph.storage.csr;

import org.impulsegraph.spec.v0_10.ImpulseLayoutsV0_10;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("v0.10.0 Domain Attributes and Key Resolution Test Suite")
class DomainAttributesV0_10Test {

	@Test
	@DisplayName("Round-trip v0.10.0 snapshot with scalar, string, and nullable domain attributes")
	void testDomainAttributesRoundTrip(@TempDir Path tempDir) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, new int[]{0, 1, 2, 3, 3}, new int[]{0, 1, 2});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel", rel));

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 10).withDomain(0, "User",
					(byte) 0x0B, 4L);

			// 1. Primary key string attribute: User IDs ["usr_001", "usr_002", "usr_003",
			// "usr_004"]
			List<String> userKeys = List.of("usr_001", "usr_002", "usr_003", "usr_004");
			builder.withDomainKeyAttribute(0, "user_id", (byte) 0x0B, userKeys);

			// 2. Integer scalar attribute: age [25, 30, 42, 19]
			ByteBuffer ageBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			ageBuf.putInt(25).putInt(30).putInt(42).putInt(19);
			builder.withDomainAttribute(0, "age", (byte) 0x03, 1, ageBuf.array(), null, null);

			// 3. Nullable float scalar attribute: score [3.5, 4.0, NULL, 5.0]
			// validity bitmap: bits 0, 1, 3 are 1, bit 2 is 0 -> 0b1011 = 0x0B
			byte[] scoreValidity = new byte[128];
			scoreValidity[0] = (byte) 0x0B;
			ByteBuffer scoreBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			scoreBuf.putFloat(3.5f).putFloat(4.0f).putFloat(0.0f).putFloat(5.0f);
			builder.withDomainAttribute(0, "score", (byte) (0x06 | 0x80), 1, scoreBuf.array(), null, scoreValidity);

			byte[] snapshotBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 10,
					graph, Map.of(), Map.of(), Map.of(), Map.of()));
			assertThat(snapshotBytes).isNotNull();

			Path snapshotPath = tempDir.resolve("domain_attrs_v010.imps");
			Files.write(snapshotPath, snapshotBytes);

			// Load via BinarySnapshotLoader
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
			assertThat(loaded.version()).isEqualTo((short) 10);
			assertThat(loaded.domainCount()).isEqualTo(1);

			BinarySnapshotLoader.LoadedDomain userDom = loaded.getDomain(0);
			assertThat(userDom).isNotNull();
			assertThat(userDom.name()).isEqualTo("User");
			assertThat(userDom.nodeCount()).isEqualTo(4L);
			assertThat(userDom.attrCount()).isEqualTo(3);
			assertThat(userDom.keyAttrIndex()).isEqualTo(0);

			// Verify §5.1 O(1) Key Resolution
			assertThat(userDom.resolveKey(0)).isEqualTo("usr_001");
			assertThat(userDom.resolveKey(1)).isEqualTo("usr_002");
			assertThat(userDom.resolveKey(2)).isEqualTo("usr_003");
			assertThat(userDom.resolveKey(3)).isEqualTo("usr_004");
			assertThat(userDom.resolveDenseId("usr_001")).isEqualTo(0L);
			assertThat(userDom.resolveDenseId("usr_002")).isEqualTo(1L);
			assertThat(userDom.resolveDenseId("usr_003")).isEqualTo(2L);
			assertThat(userDom.resolveDenseId("usr_004")).isEqualTo(3L);
			assertThat(userDom.resolveDenseId("usr_unknown")).isEqualTo(-1L);

			// Verify Domain Attributes metadata
			BinarySnapshotLoader.LoadedAttribute attrKey = userDom.getAttribute(0);
			assertThat(attrKey.name()).isEqualTo("user_id");
			assertThat(attrKey.typeCode()).isEqualTo((byte) 0x0B);

			BinarySnapshotLoader.LoadedAttribute attrAge = userDom.getAttribute(1);
			assertThat(attrAge.name()).isEqualTo("age");
			assertThat(attrAge.typeCode()).isEqualTo((byte) 0x03);

			BinarySnapshotLoader.LoadedAttribute attrScore = userDom.getAttribute(2);
			assertThat(attrScore.name()).isEqualTo("score");
			assertThat(attrScore.isNullable()).isTrue();

			// Verify Attribute Segments
			MemorySegment ageSeg = userDom.getAttributeSegment("age");
			assertThat(ageSeg).isNotNull().isNotEqualTo(MemorySegment.NULL);
			assertThat(ageSeg.get(ValueLayout.JAVA_INT_UNALIGNED, 0L)).isEqualTo(25);
			assertThat(ageSeg.get(ValueLayout.JAVA_INT_UNALIGNED, 4L)).isEqualTo(30);
			assertThat(ageSeg.get(ValueLayout.JAVA_INT_UNALIGNED, 8L)).isEqualTo(42);
			assertThat(ageSeg.get(ValueLayout.JAVA_INT_UNALIGNED, 12L)).isEqualTo(19);

			MemorySegment scoreSeg = userDom.getAttributeSegment("score");
			assertThat(scoreSeg).isNotNull().isNotEqualTo(MemorySegment.NULL);
			assertThat(scoreSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 0L)).isEqualTo(3.5f);
			assertThat(scoreSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 4L)).isEqualTo(4.0f);
			assertThat(scoreSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, 12L)).isEqualTo(5.0f);

			MemorySegment scoreVal = userDom.getValiditySegment("score");
			assertThat(scoreVal).isNotNull().isNotEqualTo(MemorySegment.NULL);
			assertThat(scoreVal.get(ValueLayout.JAVA_BYTE, 0L)).isEqualTo((byte) 0x0B);

			// Verify GraphSnapshot domain attribute methods
			GraphSnapshot loadedGraph = loaded.graph();
			assertThat(loadedGraph.getDomainAttributeNames("User")).containsExactly("user_id", "age", "score");
			assertThat(loadedGraph.getDomainKeyAttrIndex("User")).isEqualTo(0);
			assertThat(loadedGraph.getDomainAttributeSegment("User", "age")).isNotNull();

			BinarySnapshotLoader.LoadedDomain domFromGraph = loadedGraph.getDomain("User");
			assertThat(domFromGraph).isNotNull();
			assertThat(domFromGraph.resolveKey(0)).isEqualTo("usr_001");
			assertThat(domFromGraph.resolveKey(2)).isEqualTo("usr_003");
			assertThat(domFromGraph.resolveDenseId("usr_002")).isEqualTo(1L);
		}
	}

	@Test
	@DisplayName("Verify backward compatibility loading v0.9.0 snapshot synthesizes empty domain attributes")
	void testBackwardCompatibilityV09(@TempDir Path tempDir) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{1});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel", rel));

			// Explicitly request version 9 (v0.9.0 format)
			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 9).withDomain(0, "User",
					(byte) 0x03, 2L);

			byte[] snapshotBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 9,
					graph, Map.of(), Map.of(), Map.of(), Map.of()));
			Path snapshotPath = tempDir.resolve("v09_backward_compat.imps");
			Files.write(snapshotPath, snapshotBytes);

			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
			assertThat(loaded.version()).isEqualTo((short) 9);

			BinarySnapshotLoader.LoadedDomain userDom = loaded.getDomain(0);
			assertThat(userDom).isNotNull();
			assertThat(userDom.name()).isEqualTo("User");
			assertThat(userDom.nodeCount()).isEqualTo(2L);
			assertThat(userDom.attrCount()).isEqualTo(0);
			assertThat(userDom.keyAttrIndex()).isEqualTo(0xFFFF);
			assertThat(userDom.attributes()).isEmpty();
			assertThat(userDom.attributeSegments()).isEmpty();
		}
	}
}
