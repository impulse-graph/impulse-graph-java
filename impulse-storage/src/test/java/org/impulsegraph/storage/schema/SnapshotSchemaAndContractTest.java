package org.impulsegraph.storage.schema;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.ConsumerSchemaContext;
import org.impulsegraph.api.schema.CompatibilityViolation;
import org.impulsegraph.api.schema.CompatibilityViolation.Category;
import org.impulsegraph.api.schema.GraphManifest;
import org.impulsegraph.api.schema.SchemaYamlCodec;
import org.impulsegraph.api.schema.SnapshotCompatibilityException;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.storage.lifecycle.LocalPathSnapshotResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SnapshotSchemaExtractor, SnapshotContractValidator, and LocalPathSnapshotResolver Tests")
class SnapshotSchemaAndContractTest {

	@Test
	@DisplayName("SnapshotSchemaExtractor round-trips mounted v0.10 .imps binary to GraphManifest and canonical YAML")
	void testSchemaExtractorRoundTrip(@TempDir Path tempDir) throws IOException {
		Path snapPath = buildSampleSnapshot(tempDir.resolve("social.imps"), true, false, false, false);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapPath, arena);

			GraphManifest extracted = SnapshotSchemaExtractor.extract(loaded);
			assertThat(extracted.graphName()).isEqualTo("SocialGraph");
			assertThat(extracted.version()).isEqualTo("1.2.0");
			assertThat(extracted.domains()).containsOnlyKeys("User", "Post");

			GraphManifest.DomainDef user = extracted.domains().get("User");
			assertThat(user.keyType()).isEqualTo(GraphManifest.KeyType.STRING);
			assertThat(user.attributes()).containsKeys("user_id", "age", "embedding", "nickname_score");
			assertThat(user.attributes().get("age").type()).isEqualTo(GraphManifest.AttributeType.INT32);
			assertThat(user.attributes().get("age").nullable()).isFalse();
			assertThat(user.attributes().get("embedding").type()).isEqualTo(GraphManifest.AttributeType.FLOAT32);
			assertThat(user.attributes().get("embedding").dimension()).isEqualTo(4);
			assertThat(user.attributes().get("nickname_score").type()).isEqualTo(GraphManifest.AttributeType.FLOAT32);
			assertThat(user.attributes().get("nickname_score").nullable()).isTrue();

			GraphManifest.RelationDef follows = extracted.relations().get("FOLLOWS");
			assertThat(follows).isNotNull();
			assertThat(follows.source()).isEqualTo("User");
			assertThat(follows.target()).isEqualTo("User");
			assertThat(follows.directions()).containsExactlyInAnyOrder(GraphManifest.Direction.OUT,
					GraphManifest.Direction.IN);

			// Round-trip through canonical YAML and re-validate against the binary
			String yaml = SnapshotSchemaExtractor.extractYaml(loaded);
			GraphManifest reparsed = SchemaYamlCodec.parse(yaml);
			assertThat(reparsed.schemaFingerprintSha256()).isEqualTo(extracted.schemaFingerprintSha256());

			BoundSchemaHandles handles = SnapshotContractValidator.validateAndBind(loaded, reparsed);
			assertThat(handles.domain("User").present()).isTrue();
			assertThat(handles.relation("FOLLOWS").hasCsc()).isTrue();
		}
	}

	@Test
	@DisplayName("SnapshotContractValidator PASS cases: extra columns/domains, shifted indices, idWidth change, nullable widening, optional absent")
	void testContractValidatorPassTruthTable(@TempDir Path tempDir) throws IOException {
		// Snapshot has extra domain ("ExtraDom"), shifted DomainIDs/AttributeIndices,
		// non-null 'age', and CSC on FOLLOWS
		Path snapPath = buildSampleSnapshot(tempDir.resolve("evolved.imps"), true, true, true, false);

		String consumerYaml = """
				graphName: SocialGraph
				version: 1.0
				domains:
				  User:
				    idWidth: uint64
				    keyType: string
				    attributes:
				      age: { type: int32, nullable: true }
				      embedding: { type: "float32[4]" }
				      optional_bio: { type: var_string, optional: true }
				  OptionalOrg:
				    optional: true
				    attributes:
				      org_name: var_string
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out, in]
				  OPTIONAL_LIKES:
				    source: User
				    target: User
				    optional: true
				""";
		GraphManifest consumerSchema = SchemaYamlCodec.parse(consumerYaml);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapPath, arena);

			BoundSchemaHandles handles = SnapshotContractValidator.validateAndBind(loaded, consumerSchema);

			// Shifted DomainID (User is domain 1 in evolved snapshot) resolved cleanly
			BoundSchemaHandles.BoundDomainHandle userHandle = handles.domain("User");
			assertThat(userHandle.present()).isTrue();
			assertThat(userHandle.domainId()).isEqualTo(1);
			assertThat(userHandle.nodeCount()).isEqualTo(4L);

			// Shifted AttributeIndex resolved cleanly with live MemorySegment
			BoundSchemaHandles.BoundAttributeHandle ageHandle = userHandle.attribute("age");
			assertThat(ageHandle.present()).isTrue();
			assertThat(ageHandle.nullableInSnapshot()).isFalse();
			assertThat(ageHandle.dataSeg()).isNotEqualTo(MemorySegment.NULL);
			assertThat(ageHandle.dataSeg().get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0L))
					.isEqualTo(25);

			// Optional absent attribute, domain, and relation bound cleanly as absent
			assertThat(userHandle.attribute("optional_bio").present()).isFalse();
			assertThat(userHandle.attribute("optional_bio").dataSeg()).isEqualTo(MemorySegment.NULL);
			assertThat(handles.domain("OptionalOrg").present()).isFalse();
			assertThat(handles.relation("OPTIONAL_LIKES").present()).isFalse();

			// Relation bound cleanly with CSC
			assertThat(handles.relation("FOLLOWS").present()).isTrue();
			assertThat(handles.relation("FOLLOWS").hasCsc()).isTrue();
		}
	}

	@Test
	@DisplayName("SnapshotContractValidator REJECT cases: missing domain/relation/attr, nullable narrowing, missing CSC, endpoint mismatch, type & dimension mismatch")
	void testContractValidatorRejectTruthTable(@TempDir Path tempDir) throws IOException {
		// Build snapshot WITHOUT CSC and with swapped relation target (User -> Post
		// instead of User -> User)
		Path snapPath = buildSampleSnapshot(tempDir.resolve("breaking.imps"), false, false, false, true);

		String consumerYaml = """
				graphName: SocialGraph
				version: 1.0
				domains:
				  User:
				    attributes:
				      age: { type: int64, nullable: false }
				      embedding: { type: "float32[8]" }
				      nickname_score: { type: float32, nullable: false }
				      missing_required_col: { type: int32 }
				  MissingDomain:
				    attributes:
				      x: int32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out, in]
				  MISSING_REL:
				    source: User
				    target: User
				""";
		GraphManifest consumerSchema = SchemaYamlCodec.parse(consumerYaml);

		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapPath, arena);

			assertThatThrownBy(() -> SnapshotContractValidator.validateAndBind(loaded, consumerSchema))
					.isInstanceOf(SnapshotCompatibilityException.class).satisfies(ex -> {
						SnapshotCompatibilityException sce = (SnapshotCompatibilityException) ex;
						List<CompatibilityViolation> violations = sce.violations();
						assertThat(violations).extracting(CompatibilityViolation::category).contains(
								Category.TYPE_MISMATCH, Category.DIMENSION_MISMATCH, Category.NULLABILITY_MISMATCH,
								Category.MISSING_ATTRIBUTE, Category.MISSING_DOMAIN, Category.ENDPOINT_MISMATCH,
								Category.MISSING_DIRECTION, Category.MISSING_RELATION);
					});
		}
	}

	@Test
	@DisplayName("LocalPathSnapshotResolver detects initial file, returns empty when unchanged, and detects updates in METADATA and SHA256 modes")
	void testLocalPathSnapshotResolver(@TempDir Path tempDir) throws IOException {
		Path snapFile = tempDir.resolve("graph.imps");
		GraphManifest schema = SchemaYamlCodec.parse("graphName: G\nversion: 1.0\n");

		LocalPathSnapshotResolver metaResolver = new LocalPathSnapshotResolver(snapFile,
				LocalPathSnapshotResolver.RevisionMode.METADATA);
		LocalPathSnapshotResolver shaResolver = new LocalPathSnapshotResolver(snapFile,
				LocalPathSnapshotResolver.RevisionMode.SHA256);

		// Missing file returns empty
		assertThat(metaResolver.resolveNext(ConsumerSchemaContext.of(schema, Optional.empty()))).isEmpty();

		Files.writeString(snapFile, "v1-payload");
		Optional<CandidateSnapshotRef> first = metaResolver
				.resolveNext(ConsumerSchemaContext.of(schema, Optional.empty()));
		assertThat(first).isPresent();
		String rev1 = first.get().revisionId();

		// Unchanged returns empty
		assertThat(metaResolver.resolveNext(ConsumerSchemaContext.of(schema, Optional.of(rev1)))).isEmpty();

		// SHA256 mode detects content change even with same size
		Optional<CandidateSnapshotRef> shaFirst = shaResolver
				.resolveNext(ConsumerSchemaContext.of(schema, Optional.empty()));
		assertThat(shaFirst).isPresent();
		String sha1 = shaFirst.get().revisionId();
		assertThat(shaResolver.resolveNext(ConsumerSchemaContext.of(schema, Optional.of(sha1)))).isEmpty();

		Files.writeString(snapFile, "v2-payload");
		Optional<CandidateSnapshotRef> shaSecond = shaResolver
				.resolveNext(ConsumerSchemaContext.of(schema, Optional.of(sha1)));
		assertThat(shaSecond).isPresent();
		assertThat(shaSecond.get().revisionId()).isNotEqualTo(sha1);
	}

	private static Path buildSampleSnapshot(Path outPath, boolean includeCsc, boolean shiftIdsAndAttrs,
			boolean addExtraDomain, boolean swapFollowsTargetToPost) throws IOException {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot follows = new RelationSnapshot(arena, 4, 3, new int[]{0, 1, 2, 3, 3}, new int[]{1, 2, 3});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("FOLLOWS", follows));

			int userDomId = shiftIdsAndAttrs ? 1 : 0;
			int postDomId = shiftIdsAndAttrs ? 0 : 1;

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 10).withCsc(includeCsc)
					.withMetadata("impulse.schema.name", "SocialGraph").withMetadata("impulse.schema.version", "1.2.0");

			if (shiftIdsAndAttrs) {
				builder.withDomain(postDomId, "Post", (byte) 0x04, 4L);
				builder.withDomain(userDomId, "User", (byte) 0x0B, 4L);
			} else {
				builder.withDomain(userDomId, "User", (byte) 0x0B, 4L);
				builder.withDomain(postDomId, "Post", (byte) 0x04, 4L);
			}
			if (addExtraDomain) {
				builder.withDomain(2, "ExtraDom", (byte) 0x03, 2L);
			}

			if (shiftIdsAndAttrs) {
				// Insert an extra attribute first to shift indices of age and embedding
				ByteBuffer extraBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
				extraBuf.putInt(1).putInt(2).putInt(3).putInt(4);
				builder.withDomainAttribute(userDomId, "extra_prefix_col", (byte) 0x03, 1, extraBuf.array(), null,
						null);
			}

			builder.withDomainKeyAttribute(userDomId, "user_id", (byte) 0x0B, List.of("u0", "u1", "u2", "u3"));

			ByteBuffer ageBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			ageBuf.putInt(25).putInt(30).putInt(42).putInt(19);
			builder.withDomainAttribute(userDomId, "age", (byte) 0x03, 1, ageBuf.array(), null, null);

			// 4-dim float32 embedding (4 nodes * 4 floats * 4 bytes = 64 bytes)
			ByteBuffer embBuf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < 16; i++) {
				embBuf.putFloat(i * 0.5f);
			}
			builder.withDomainAttribute(userDomId, "embedding", (byte) 0x06, 4, embBuf.array(), null, null);

			// Nullable float32 attribute
			byte[] validity = new byte[128];
			validity[0] = (byte) 0x0B; // bits 0,1,3 valid; bit 2 null
			ByteBuffer scoreBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			scoreBuf.putFloat(1.0f).putFloat(2.0f).putFloat(0.0f).putFloat(4.0f);
			builder.withDomainAttribute(userDomId, "nickname_score", (byte) (0x06 | 0x80), 1, scoreBuf.array(), null,
					validity);

			int tgtDomId = swapFollowsTargetToPost ? postDomId : userDomId;
			builder.withRelationDomain("FOLLOWS", userDomId, tgtDomId);

			byte[] bytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 10, graph,
					Map.of(), Map.of(), Map.of(), Map.of()));
			Files.write(outPath, bytes);
			return outPath;
		}
	}
}
