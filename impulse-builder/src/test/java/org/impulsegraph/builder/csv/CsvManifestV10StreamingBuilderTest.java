package org.impulsegraph.builder.csv;

import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.builder.api.CompressionScheme;
import org.impulsegraph.builder.api.DataType;
import org.impulsegraph.builder.api.DomainDefinition;
import org.impulsegraph.builder.api.Nullability;
import org.impulsegraph.builder.api.PrimitiveWidth;
import org.impulsegraph.builder.api.RelationDefinition;
import org.impulsegraph.builder.api.SnapshotBuilder;
import org.impulsegraph.builder.api.Topology;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedAttribute;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedRelation;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Streaming Spec v0.10.0 CsvManifestSnapshotBuilder & Provenance Test Suite")
class CsvManifestV10StreamingBuilderTest {

	private static final ValueLayout.OfInt LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfLong LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfFloat LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfDouble LE_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);
	private static final ValueLayout.OfShort LE_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED
			.withOrder(ByteOrder.LITTLE_ENDIAN);

	@Test
	@DisplayName("CSV + JSON manifest: all types, nullable bitmaps, float32[4] vectors, unsorted edges with external sort spill, CSC, and provenance")
	void testCsvV10StreamingBuildWithVectorsNullsAndExternalSort(@TempDir Path tempDir) throws Exception {
		Path domainsDir = tempDir.resolve("domains");
		Path relsDir = tempDir.resolve("relations");
		Files.createDirectories(domainsDir);
		Files.createDirectories(relsDir);

		Files.writeString(domainsDir.resolve("users.csv"), """
				user_id,flag_i8,rank_i16,age_i32,karma_i64,score_f32,balance_f64,created_ms,opt_age,opt_bio,embedding
				u_alice,1,10,30,100000,0.75,123.45,1700000000000,30,Engineer,"[1.0, 2.0, 3.0, 4.0]"
				u_bob,0,20,25,200000,0.50,67.89,1700000001000,,,"[5.0, 6.0, 7.0, 8.0]"
				u_carol,1,30,41,300000,0.95,999.99,1700000002000,41,Architect,"[9.0, 10.0, 11.0, 12.0]"
				""", StandardCharsets.UTF_8);

		Files.writeString(domainsDir.resolve("items.csv"), """
				item_id,title
				101,Laptop
				102,Keyboard
				""", StandardCharsets.UTF_8);

		// Intentionally UNSORTED edges to verify external sort + edge attribute
		// alignment
		Files.writeString(relsDir.resolve("bought.csv"), """
				src,tgt,qty,price,opt_coupon
				u_carol,102,3,199.5,SAVE20
				u_alice,102,1,99.5,
				u_bob,101,2,1200.0,WELCOME
				u_alice,101,1,1150.0,VIP
				""", StandardCharsets.UTF_8);

		String manifestJson = """
				{
				  "schema_name": "commerce-v10",
				  "schema_version": "1.2.3",
				  "domains": [
				    {
				      "id": 0,
				      "name": "User",
				      "id_width": "uint32",
				      "key_type": "string",
				      "id_column": "user_id",
				      "file": "domains/users.csv",
				      "attributes": [
				        {"name": "flag_i8", "type": "int8"},
				        {"name": "rank_i16", "type": "int16"},
				        {"name": "age_i32", "type": "int32"},
				        {"name": "karma_i64", "type": "int64"},
				        {"name": "score_f32", "type": "float32"},
				        {"name": "balance_f64", "type": "float64"},
				        {"name": "created_ms", "type": "timestamp_ms"},
				        {"name": "opt_age", "type": "int32?"},
				        {"name": "opt_bio", "type": "string?"},
				        {"name": "embedding", "type": "float32[4]"}
				      ]
				    },
				    {
				      "id": 1,
				      "name": "Item",
				      "id_width": "uint16",
				      "key_type": "int32",
				      "id_column": "item_id",
				      "file": "domains/items.csv",
				      "attributes": [
				        {"name": "title", "type": "string"}
				      ]
				    }
				  ],
				  "relations": [
				    {
				      "id": 0,
				      "name": "BOUGHT",
				      "src_domain": "User",
				      "tgt_domain": "Item",
				      "include_csc": true,
				      "file": "relations/bought.csv",
				      "attributes": [
				        {"name": "qty", "type": "int32"},
				        {"name": "price", "type": "float64"},
				        {"name": "opt_coupon", "type": "string?"}
				      ]
				    }
				  ]
				}
				""";
		Path manifestPath = tempDir.resolve("manifest.json");
		Files.writeString(manifestPath, manifestJson, StandardCharsets.UTF_8);
		String expectedSha = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifestPath)));

		Path outImps = tempDir.resolve("commerce.v10.imps");
		Path stagingDir = tempDir.resolve("stage");
		// Tiny stagingMemoryLimit (64 KB) exercises external-sort staging path
		CsvManifestSnapshotBuilder.buildV10Snapshot(manifestPath, outImps, stagingDir, 64 * 1024L);

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImps, arena, true);
			assertEquals(10, loaded.version());
			assertEquals(2, loaded.domainCount());
			assertEquals(1, loaded.relationCount());

			// Footer schema provenance
			assertEquals("commerce-v10", loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_NAME));
			assertEquals("1.2.3", loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_VERSION));
			assertEquals(expectedSha, loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_SHA256));

			// Primary key round-trip via DomainView
			DomainView userView = loaded.graph().domain("User");
			assertEquals("u_alice", userView.toKey(0));
			assertEquals("u_bob", userView.toKey(1));
			assertEquals("u_carol", userView.toKey(2));
			assertEquals(0L, userView.toDenseId("u_alice"));
			assertEquals(1L, userView.toDenseId("u_bob"));
			assertEquals(2L, userView.toDenseId("u_carol"));
			assertNotNull(userView.fromKey("u_bob"));

			DomainView itemView = loaded.graph().domain("Item");
			assertEquals("101", itemView.toKey(0));
			assertEquals("102", itemView.toKey(1));
			assertEquals(0L, itemView.toDenseId("101"));
			assertEquals(1L, itemView.toDenseId("102"));

			// Verify inline domain attributes on User
			LoadedDomain userDom = loaded.getDomain("User");
			assertEquals(1, userDom.getAttributeSegment("flag_i8").get(ValueLayout.JAVA_BYTE, 0));
			assertEquals(0, userDom.getAttributeSegment("flag_i8").get(ValueLayout.JAVA_BYTE, 1));
			assertEquals((short) 20, userDom.getAttributeSegment("rank_i16").getAtIndex(LE_SHORT, 1));
			assertEquals(41, userDom.getAttributeSegment("age_i32").getAtIndex(LE_INT, 2));
			assertEquals(200000L, userDom.getAttributeSegment("karma_i64").getAtIndex(LE_LONG, 1));
			assertEquals(0.75f, userDom.getAttributeSegment("score_f32").getAtIndex(LE_FLOAT, 0), 1e-6f);
			assertEquals(999.99, userDom.getAttributeSegment("balance_f64").getAtIndex(LE_DOUBLE, 2), 1e-9);
			assertEquals(1700000001000L, userDom.getAttributeSegment("created_ms").getAtIndex(LE_LONG, 1));

			// Nullable int32: row 0 valid (30), row 1 NULL, row 2 valid (41)
			MemorySegment optAgeVal = userDom.getValiditySegment("opt_age");
			assertTrue(isBitSet(optAgeVal, 0));
			assertFalse(isBitSet(optAgeVal, 1));
			assertTrue(isBitSet(optAgeVal, 2));
			assertEquals(30, userDom.getAttributeSegment("opt_age").getAtIndex(LE_INT, 0));
			assertEquals(41, userDom.getAttributeSegment("opt_age").getAtIndex(LE_INT, 2));

			// Nullable VAR_STRING: row 0 "Engineer", row 1 NULL, row 2 "Architect"
			MemorySegment optBioVal = userDom.getValiditySegment("opt_bio");
			assertTrue(isBitSet(optBioVal, 0));
			assertFalse(isBitSet(optBioVal, 1));
			assertTrue(isBitSet(optBioVal, 2));
			assertEquals("Engineer",
					readVarString(userDom.getAttributeSegment("opt_bio"), userDom.getOffsetsSegment("opt_bio"), 0));
			assertEquals("Architect",
					readVarString(userDom.getAttributeSegment("opt_bio"), userDom.getOffsetsSegment("opt_bio"), 2));

			// Vector float32[4]: verify dimension == 4 and exact values
			LoadedAttribute embAttr = userDom.getAttribute("embedding");
			assertNotNull(embAttr);
			assertEquals(4, embAttr.dimension());
			MemorySegment embSeg = userDom.getAttributeSegment("embedding");
			for (int i = 0; i < 12; i++) {
				assertEquals((float) (i + 1), embSeg.getAtIndex(LE_FLOAT, i), 1e-6f);
			}

			// Verify CSR sorted order: (0,0), (0,1), (1,0), (2,1) and aligned edge
			// attributes
			RelationSnapshot boughtRel = (RelationSnapshot) loaded.graph().getRelationSnapshot("BOUGHT");
			assertNotNull(boughtRel);
			assertTrue(boughtRel.hasCsc());
			assertEquals(4, boughtRel.getEdgeCount());

			// Edge order after CSR sort:
			// e0: (u_alice=0, 101=0) -> qty=1, price=1150.0, opt_coupon="VIP"
			// e1: (u_alice=0, 102=1) -> qty=1, price=99.5, opt_coupon=NULL
			// e2: (u_bob=1, 101=0) -> qty=2, price=1200.0, opt_coupon="WELCOME"
			// e3: (u_carol=2, 102=1) -> qty=3, price=199.5, opt_coupon="SAVE20"
			LoadedRelation loadedBought = loaded.relationsById().get(0);
			assertEquals(3, loadedBought.attributes().size());
			MemorySegment qtySeg = boughtRel.getAttributeSegments().get(0);
			MemorySegment priceSeg = boughtRel.getAttributeSegments().get(1);
			MemorySegment couponSeg = boughtRel.getAttributeSegments().get(2);
			MemorySegment couponVal = boughtRel.getValiditySegments().get(2);
			MemorySegment couponOffs = boughtRel.getOffsetsSegments().get(2);

			assertEquals(1, qtySeg.getAtIndex(LE_INT, 0));
			assertEquals(1, qtySeg.getAtIndex(LE_INT, 1));
			assertEquals(2, qtySeg.getAtIndex(LE_INT, 2));
			assertEquals(3, qtySeg.getAtIndex(LE_INT, 3));

			assertEquals(1150.0, priceSeg.getAtIndex(LE_DOUBLE, 0), 1e-9);
			assertEquals(99.5, priceSeg.getAtIndex(LE_DOUBLE, 1), 1e-9);
			assertEquals(1200.0, priceSeg.getAtIndex(LE_DOUBLE, 2), 1e-9);
			assertEquals(199.5, priceSeg.getAtIndex(LE_DOUBLE, 3), 1e-9);

			assertTrue(isBitSet(couponVal, 0));
			assertFalse(isBitSet(couponVal, 1));
			assertTrue(isBitSet(couponVal, 2));
			assertTrue(isBitSet(couponVal, 3));
			assertEquals("VIP", readVarString(couponSeg, couponOffs, 0));
			assertEquals("WELCOME", readVarString(couponSeg, couponOffs, 2));
			assertEquals("SAVE20", readVarString(couponSeg, couponOffs, 3));
		}
	}

	@Test
	@DisplayName("TSV + YAML manifest: auto-detects .tsv, parses YAML schema, direction=bidirectional CSC, vectors & provenance")
	void testTsvAndYamlManifestV10Build(@TempDir Path tempDir) throws Exception {
		Files.writeString(
				tempDir.resolve("authors.tsv"), "id\tname\topt_orcid\tvec\n"
						+ "a1\tAda Lovelace\t0000-0001\t[0.1, 0.2, 0.3]\n" + "a2\tAlan Turing\t\t[0.4, 0.5, 0.6]\n",
				StandardCharsets.UTF_8);

		Files.writeString(tempDir.resolve("papers.tsv"), "id\tyear\n" + "p1\t1843\n" + "p2\t1936\n",
				StandardCharsets.UTF_8);

		Files.writeString(tempDir.resolve("wrote.tsv"), "src\ttgt\tposition\n" + "a2\tp2\t1\n" + "a1\tp1\t1\n",
				StandardCharsets.UTF_8);

		String yamlManifest = """
				# Citation graph manifest in YAML
				schema_name: citations-yaml
				schema_version: "2.0.0"
				domains:
				  - name: Author
				    key_type: string
				    id_width: uint32
				    file: authors.tsv
				    attributes:
				      - name: name
				        type: string
				      - name: opt_orcid
				        type: string?
				      - name: vec
				        type: float32[3]
				  - name: Paper
				    key_type: string
				    file: papers.tsv
				    attributes:
				      - name: year
				        type: int32
				relations:
				  - name: WROTE
				    from: Author
				    to: Paper
				    direction: bidirectional
				    file: wrote.tsv
				    attributes:
				      - name: position
				        type: int16
				""";
		Path manifestPath = tempDir.resolve("manifest.yaml");
		Files.writeString(manifestPath, yamlManifest, StandardCharsets.UTF_8);

		Path outImps = tempDir.resolve("citations.v10.imps");
		CsvManifestSnapshotBuilder.buildV10Snapshot(manifestPath, outImps);

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImps, arena, true);
			assertEquals(10, loaded.version());
			assertEquals("citations-yaml", loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_NAME));
			assertEquals("2.0.0", loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_VERSION));
			assertEquals(64, loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_SHA256).length());

			DomainView authorView = loaded.graph().domain("Author");
			assertEquals("a1", authorView.toKey(0));
			assertEquals("a2", authorView.toKey(1));
			assertEquals(1L, authorView.toDenseId("a2"));

			LoadedDomain authorDom = loaded.getDomain("Author");
			assertTrue(isBitSet(authorDom.getValiditySegment("opt_orcid"), 0));
			assertFalse(isBitSet(authorDom.getValiditySegment("opt_orcid"), 1));
			assertEquals(3, authorDom.getAttribute("vec").dimension());
			assertEquals(0.4f, authorDom.getAttributeSegment("vec").getAtIndex(LE_FLOAT, 3), 1e-6f);

			RelationSnapshot wroteRel = (RelationSnapshot) loaded.graph().getRelationSnapshot("WROTE");
			assertNotNull(wroteRel);
			assertTrue(wroteRel.hasCsc());
			assertEquals(2, wroteRel.getEdgeCount());
			assertEquals((short) 1, wroteRel.getAttributeSegments().get(0).getAtIndex(LE_SHORT, 0));
		}
	}

	@Test
	@DisplayName("JSONL + JSON manifest: flat JSON objects, JSON array vectors, null/missing fields, int64 keys, CSC")
	void testJsonlV10Build(@TempDir Path tempDir) throws Exception {
		Files.writeString(tempDir.resolve("nodes.jsonl"), """
				{"id": 9001, "label": "Alpha", "opt_score": 4.25, "emb": [1.5, 2.5]}
				{"id": 9002, "label": "Beta", "opt_score": null, "emb": [3.5, 4.5]}
				{"id": 9003, "label": "Gamma", "emb": [5.5, 6.5]}
				""", StandardCharsets.UTF_8);

		Files.writeString(tempDir.resolve("edges.jsonl"), """
				{"src": 9003, "tgt": 9001, "weight": 0.9, "note": "back"}
				{"src": 9001, "tgt": 9002, "weight": 0.5, "note": null}
				""", StandardCharsets.UTF_8);

		String manifestJson = """
				{
				  "schema_name": "jsonl-graph",
				  "schema_version": "0.10.0",
				  "domains": [
				    {
				      "name": "Node",
				      "key_type": "int64",
				      "file": "nodes.jsonl",
				      "attributes": [
				        {"name": "label", "type": "string"},
				        {"name": "opt_score", "type": "float64?"},
				        {"name": "emb", "type": "float32[2]"}
				      ]
				    }
				  ],
				  "relations": [
				    {
				      "name": "LINKS",
				      "src_domain": "Node",
				      "tgt_domain": "Node",
				      "include_csc": true,
				      "file": "edges.jsonl",
				      "attributes": [
				        {"name": "weight", "type": "float32"},
				        {"name": "note", "type": "string?"}
				      ]
				    }
				  ]
				}
				""";
		Path manifestPath = tempDir.resolve("manifest.json");
		Files.writeString(manifestPath, manifestJson, StandardCharsets.UTF_8);

		Path outImps = tempDir.resolve("jsonl.v10.imps");
		CsvManifestSnapshotBuilder.buildV10Snapshot(manifestPath, outImps);

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outImps, arena, true);
			assertEquals(10, loaded.version());
			assertEquals("jsonl-graph", loaded.getMetadata(SnapshotBuilder.FOOTER_SCHEMA_NAME));

			DomainView nodeView = loaded.graph().domain("Node");
			assertEquals("9001", nodeView.toKey(0));
			assertEquals("9002", nodeView.toKey(1));
			assertEquals("9003", nodeView.toKey(2));
			assertEquals(2L, nodeView.toDenseId("9003"));

			LoadedDomain nodeDom = loaded.getDomain("Node");
			MemorySegment scoreVal = nodeDom.getValiditySegment("opt_score");
			assertTrue(isBitSet(scoreVal, 0));
			assertFalse(isBitSet(scoreVal, 1));
			assertFalse(isBitSet(scoreVal, 2));
			assertEquals(4.25, nodeDom.getAttributeSegment("opt_score").getAtIndex(LE_DOUBLE, 0), 1e-9);
			assertEquals(6.5f, nodeDom.getAttributeSegment("emb").getAtIndex(LE_FLOAT, 5), 1e-6f);

			RelationSnapshot linksRel = (RelationSnapshot) loaded.graph().getRelationSnapshot("LINKS");
			assertTrue(linksRel.hasCsc());
			assertEquals(2, linksRel.getEdgeCount());
			// Sorted CSR order: (9001=0 -> 9002=1) then (9003=2 -> 9001=0)
			assertEquals(0.5f, linksRel.getAttributeSegments().get(0).getAtIndex(LE_FLOAT, 0), 1e-6f);
			assertEquals(0.9f, linksRel.getAttributeSegments().get(0).getAtIndex(LE_FLOAT, 1), 1e-6f);
			assertFalse(isBitSet(linksRel.getValiditySegments().get(1), 0));
			assertTrue(isBitSet(linksRel.getValiditySegments().get(1), 1));
			assertEquals("back",
					readVarString(linksRel.getAttributeSegments().get(1), linksRel.getOffsetsSegments().get(1), 1));
		}
	}

	@Test
	@DisplayName("SnapshotBuilder.withSchemaProvenance embeds canonical footer metadata keys")
	void testWithSchemaProvenanceDirectOnSnapshotBuilder(@TempDir Path tempDir) throws Exception {
		List<String> keys = List.of("k0", "k1");
		AttributeDataSource keySource = new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.STRING;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int idx = 0;
					@Override
					public boolean hasNext() {
						return idx < keys.size();
					}
					@Override
					public int nextChunk(MemorySegment destination, int limit) {
						throw new UnsupportedOperationException();
					}
					@Override
					public int nextVariableChunk(List<byte[]> destination, int limit) {
						int c = 0;
						while (idx < keys.size() && c < limit) {
							destination.add(keys.get(idx++).getBytes(StandardCharsets.UTF_8));
							c++;
						}
						return c;
					}
				};
			}
		};

		DomainDefinition dom = DomainDefinition.builder(2L).idWidth(PrimitiveWidth.UINT32).withPrimaryKeyIndex(true)
				.primaryKeyAttribute("id").addAttribute("id", keySource).build();

		Path out = tempDir.resolve("provenance.imps");
		SnapshotBuilder.create().withVersion((short) 10).addDomain("Entity", dom)
				.withSchemaProvenance("test-schema", "3.4.5", "abc123def456").writeTo(out);

		try (Arena arena = Arena.ofConfined()) {
			LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(out, arena, true);
			assertEquals("test-schema", loaded.getMetadata("impulse.schema.name"));
			assertEquals("3.4.5", loaded.getMetadata("impulse.schema.version"));
			assertEquals("abc123def456", loaded.getMetadata("impulse.schema.sha256"));
			assertEquals("k0", loaded.graph().domain("Entity").toKey(0));
			assertEquals("k1", loaded.graph().domain("Entity").toKey(1));
			assertEquals(1L, loaded.graph().domain("Entity").toDenseId("k1"));
		}
	}

	private static boolean isBitSet(MemorySegment validSeg, int index) {
		byte b = validSeg.get(ValueLayout.JAVA_BYTE, index >>> 3);
		return (b & (1 << (index & 7))) != 0;
	}

	private static String readVarString(MemorySegment dataSeg, MemorySegment offsSeg, int row) {
		long start = Integer.toUnsignedLong(offsSeg.get(LE_INT, row * 4L));
		long end = Integer.toUnsignedLong(offsSeg.get(LE_INT, (row + 1L) * 4L));
		byte[] bytes = new byte[(int) (end - start)];
		MemorySegment.copy(dataSeg, ValueLayout.JAVA_BYTE, start, bytes, 0, bytes.length);
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
