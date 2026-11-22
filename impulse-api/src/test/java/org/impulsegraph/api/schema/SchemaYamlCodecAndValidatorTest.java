package org.impulsegraph.api.schema;

import org.impulsegraph.api.lifecycle.ConsumerSchemaContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SchemaYamlCodec, SchemaValidator, and ConsumerSchemaContext Tests")
class SchemaYamlCodecAndValidatorTest {

	@Test
	@DisplayName("Parse social-graph.yaml shape with nodes:, package:, denseId:, flow maps, flow lists, vectors, and inverseAlias")
	void testParseSocialGraphYaml() {
		String yaml = """
				graphName: SocialGraph
				version: 1.0
				package: org.impulsegraph.example.social

				nodes:
				  User:
				    key: uuid
				    denseId: int32
				    attributes:
				      username: { type: string, length: 15, nullable: false, indexed: true }
				      email: { type: varstring, nullable: false }
				      embedding: { type: "float32[768]" }
				  Post:
				    key: int64
				    denseId: int64
				    attributes:
				      title: { type: varstring, nullable: false }

				relations:
				  userFollowsUser:
				    source: User
				    target: User
				    direction: [out, in]
				    inverseAlias: followedBy
				    cardinality: many_to_many
				  userPostsPost:
				    source: User
				    target: Post
				    direction: [out]
				    cardinality: one_to_many
				""";

		GraphManifest manifest = SchemaYamlCodec.parse(yaml);

		assertThat(manifest.graphName()).isEqualTo("SocialGraph");
		assertThat(manifest.version()).isEqualTo("1.0");
		assertThat(manifest.packageName()).isEqualTo("org.impulsegraph.example.social");

		GraphManifest.DomainDef user = manifest.domains().get("User");
		assertThat(user).isNotNull();
		assertThat(user.keyType()).isEqualTo(GraphManifest.KeyType.UUID);
		assertThat(user.idWidth()).isEqualTo(GraphManifest.IdWidth.UINT32);

		GraphManifest.AttributeDef username = user.attributes().get("username");
		assertThat(username.type()).isEqualTo(GraphManifest.AttributeType.FIXED_STRING);
		assertThat(username.dimension()).isEqualTo(15);
		assertThat(username.nullable()).isFalse();
		assertThat(username.indexed()).isTrue();

		GraphManifest.AttributeDef email = user.attributes().get("email");
		assertThat(email.type()).isEqualTo(GraphManifest.AttributeType.VAR_STRING);
		assertThat(email.nullable()).isFalse();

		GraphManifest.AttributeDef embedding = user.attributes().get("embedding");
		assertThat(embedding.type()).isEqualTo(GraphManifest.AttributeType.FLOAT32);
		assertThat(embedding.dimension()).isEqualTo(768);
		assertThat(embedding.nullable()).isFalse();

		GraphManifest.DomainDef post = manifest.domains().get("Post");
		assertThat(post.keyType()).isEqualTo(GraphManifest.KeyType.INT64);
		assertThat(post.idWidth()).isEqualTo(GraphManifest.IdWidth.UINT64);

		GraphManifest.RelationDef follows = manifest.relations().get("userFollowsUser");
		assertThat(follows.source()).isEqualTo("User");
		assertThat(follows.target()).isEqualTo("User");
		assertThat(follows.directions()).containsExactlyInAnyOrder(GraphManifest.Direction.OUT,
				GraphManifest.Direction.IN);
		assertThat(follows.inverseAlias()).isEqualTo("followedBy");
		assertThat(follows.cardinality()).isEqualTo(GraphManifest.Cardinality.MANY_TO_MANY);

		GraphManifest.RelationDef posts = manifest.relations().get("userPostsPost");
		assertThat(posts.directions()).containsExactly(GraphManifest.Direction.OUT);
		assertThat(posts.cardinality()).isEqualTo(GraphManifest.Cardinality.ONE_TO_MANY);

		assertThat(SchemaValidator.validate(manifest)).isEmpty();
	}

	@Test
	@DisplayName("Parse 4-space indentation, inline comments, shorthand nullable/vector/fixed types, optional/since/deprecated")
	void testFourSpaceCommentsAndShorthandTypes(@TempDir Path tempDir) throws IOException {
		String yaml = """
				# Header comment
				graphName: AnalyticsGraph # inline comment
				version: "2.3.1"

				domains:
				    Account:
				        idWidth: uint16 # compact catalog
				        keyType: string
				        attributes:
				            age: "Int16?" # nullable int16
				            vec: "Float32[128]"
				            code: "FixedString(16)"
				            digest: "FixedBytes(32)?"
				            active: bool
				            created_at: timestamp_ms
				            window: interval_sec_32
				            beta_flag: { type: bool, optional: true, since: "2.2", deprecated: "2.4" }
				    LegacyNode:
				        optional: true
				        deprecated: "2.3"

				relations:
				    co_occurs:
				        source: Account
				        target: Account
				        directions: [out, in]
				        symmetric: true
				        cardinality: many_to_many
				        optional: true
				        since: "2.0"
				        attributes:
				            weight: float32
				            note: { type: var_string, nullable: true }

				virtual_relations:
				    all_links:
				        deprecated: "3.0"
				        components:
				            - co_occurs
				""";

		Path file = tempDir.resolve("analytics.imps.schema.yaml");
		Files.writeString(file, yaml);

		GraphManifest parsed = SchemaYamlCodec.parse(file);
		assertThat(parsed.graphName()).isEqualTo("AnalyticsGraph");
		assertThat(parsed.version()).isEqualTo("2.3.1");

		GraphManifest.DomainDef account = parsed.domains().get("Account");
		assertThat(account.idWidth()).isEqualTo(GraphManifest.IdWidth.UINT16);
		assertThat(account.keyType()).isEqualTo(GraphManifest.KeyType.STRING);

		assertThat(account.attributes().get("age").type()).isEqualTo(GraphManifest.AttributeType.INT16);
		assertThat(account.attributes().get("age").nullable()).isTrue();
		assertThat(account.attributes().get("vec").type()).isEqualTo(GraphManifest.AttributeType.FLOAT32);
		assertThat(account.attributes().get("vec").dimension()).isEqualTo(128);
		assertThat(account.attributes().get("code").type()).isEqualTo(GraphManifest.AttributeType.FIXED_STRING);
		assertThat(account.attributes().get("code").dimension()).isEqualTo(16);
		assertThat(account.attributes().get("digest").type()).isEqualTo(GraphManifest.AttributeType.FIXED_BYTES);
		assertThat(account.attributes().get("digest").dimension()).isEqualTo(32);
		assertThat(account.attributes().get("digest").nullable()).isTrue();
		assertThat(account.attributes().get("active").type()).isEqualTo(GraphManifest.AttributeType.BOOL);
		assertThat(account.attributes().get("created_at").type()).isEqualTo(GraphManifest.AttributeType.TIMESTAMP_MS);
		assertThat(account.attributes().get("window").type()).isEqualTo(GraphManifest.AttributeType.INTERVAL_SEC_32);
		assertThat(account.attributes().get("beta_flag").optional()).isTrue();
		assertThat(account.attributes().get("beta_flag").since()).isEqualTo("2.2");
		assertThat(account.attributes().get("beta_flag").deprecated()).isEqualTo("2.4");

		assertThat(parsed.domains().get("LegacyNode").optional()).isTrue();
		assertThat(parsed.relations().get("co_occurs").symmetric()).isTrue();
		assertThat(parsed.relations().get("co_occurs").optional()).isTrue();
		assertThat(parsed.virtualRelations().get("all_links").components()).containsExactly("co_occurs");
		assertThat(parsed.virtualRelations().get("all_links").deprecated()).isEqualTo("3.0");

		assertThat(SchemaValidator.validate(parsed)).isEmpty();

		// Canonical YAML round-trip produces identical AST and fingerprint
		String canonicalYaml = SchemaYamlCodec.toYaml(parsed);
		GraphManifest roundTripped = SchemaYamlCodec.parse(canonicalYaml);
		assertThat(roundTripped.schemaFingerprintSha256()).isEqualTo(parsed.schemaFingerprintSha256());
	}

	@Test
	@DisplayName("Canonical YAML fingerprint is deterministic regardless of map insertion order")
	void testDeterministicFingerprint() {
		Map<String, GraphManifest.AttributeDef> attrsA = new LinkedHashMap<>();
		attrsA.put("b", new GraphManifest.AttributeDef("b", GraphManifest.AttributeType.INT64));
		attrsA.put("a", new GraphManifest.AttributeDef("a", GraphManifest.AttributeType.FLOAT32));

		Map<String, GraphManifest.AttributeDef> attrsB = new LinkedHashMap<>();
		attrsB.put("a", new GraphManifest.AttributeDef("a", GraphManifest.AttributeType.FLOAT32));
		attrsB.put("b", new GraphManifest.AttributeDef("b", GraphManifest.AttributeType.INT64));

		GraphManifest m1 = new GraphManifest("pkg", "G", "1.0", Map.of(), Map.of("User",
				new GraphManifest.DomainDef(null, GraphManifest.IdWidth.UINT32, GraphManifest.KeyType.INT64, attrsA)),
				Map.of(), Map.of());
		GraphManifest m2 = new GraphManifest("pkg", "G", "1.0", Map.of(), Map.of("User",
				new GraphManifest.DomainDef(null, GraphManifest.IdWidth.UINT32, GraphManifest.KeyType.INT64, attrsB)),
				Map.of(), Map.of());

		assertThat(m1.schemaFingerprintSha256()).isEqualTo(m2.schemaFingerprintSha256());
		assertThat(m1.schemaFingerprintSha256()).hasSize(64);
	}

	@Test
	@DisplayName("SchemaValidator reports all semantic errors on invalid schemas")
	void testSchemaValidatorDetectsAllErrors() {
		Map<String, GraphManifest.DomainDef> domains = new LinkedHashMap<>();
		domains.put("User",
				new GraphManifest.DomainDef(null, GraphManifest.IdWidth.UINT32, GraphManifest.KeyType.INT64,
						Map.of("badFixed", new GraphManifest.AttributeDef("badFixed",
								GraphManifest.AttributeType.FIXED_STRING, 0, false, false))));
		domains.put("Post",
				new GraphManifest.DomainDef(null, GraphManifest.IdWidth.UINT32, GraphManifest.KeyType.INT64,
						Map.of("badBytes", new GraphManifest.AttributeDef("badBytes",
								GraphManifest.AttributeType.FIXED_BYTES, -4, false, false))));

		Map<String, GraphManifest.RelationDef> relations = new LinkedHashMap<>();
		// Missing source & target domains
		relations.put("brokenEndpoints",
				new GraphManifest.RelationDef("GhostSrc", "GhostTgt", null, EnumSet.of(GraphManifest.Direction.OUT),
						GraphManifest.Cardinality.MANY_TO_MANY, false, null, false, null, null, Map.of()));
		// symmetric: true with source != target
		relations.put("badSymmetric",
				new GraphManifest.RelationDef("User", "Post", null, EnumSet.of(GraphManifest.Direction.OUT),
						GraphManifest.Cardinality.MANY_TO_MANY, true, "dupAlias", false, null, null, Map.of()));
		// Duplicate inverseAlias on same target domain Post
		relations.put("alsoTargetsPost",
				new GraphManifest.RelationDef("User", "Post", null, EnumSet.of(GraphManifest.Direction.OUT),
						GraphManifest.Cardinality.ONE_TO_MANY, false, "dupAlias", false, null, null, Map.of()));

		Map<String, GraphManifest.VirtualRelationDef> virtualRelations = new LinkedHashMap<>();
		virtualRelations.put("badVirtual", new GraphManifest.VirtualRelationDef(List.of("nonExistentRel"), null));

		GraphManifest invalid = new GraphManifest(null, "Broken", "1.0", Map.of(), domains, relations,
				virtualRelations);

		List<String> errors = SchemaValidator.validate(invalid);
		assertThat(errors).hasSize(7);
		assertThat(errors).anyMatch(e -> e.contains("badFixed") && e.contains("dimension > 0"));
		assertThat(errors).anyMatch(e -> e.contains("badBytes") && e.contains("dimension > 0"));
		assertThat(errors).anyMatch(e -> e.contains("GhostSrc"));
		assertThat(errors).anyMatch(e -> e.contains("GhostTgt"));
		assertThat(errors).anyMatch(e -> e.contains("badSymmetric") && e.contains("symmetric=true"));
		assertThat(errors).anyMatch(e -> e.contains("Duplicate inverseAlias 'dupAlias'"));
		assertThat(errors).anyMatch(e -> e.contains("nonExistentRel"));
	}

	@Test
	@DisplayName("ConsumerSchemaContext extracts majorVersion cleanly")
	void testConsumerSchemaContextMajorVersion() {
		GraphManifest m1 = SchemaYamlCodec.parse("graphName: G\nversion: \"2.4.1\"\n");
		assertThat(ConsumerSchemaContext.of(m1, Optional.empty()).majorVersion()).isEqualTo(2);

		GraphManifest m2 = SchemaYamlCodec.parse("graphName: G\nversion: \"v3.0\"\n");
		assertThat(ConsumerSchemaContext.of(m2, Optional.of("rev-1")).majorVersion()).isEqualTo(3);

		GraphManifest m3 = SchemaYamlCodec.parse("graphName: G\nversion: \"snapshot\"\n");
		assertThat(ConsumerSchemaContext.of(m3, Optional.empty()).majorVersion()).isEqualTo(0);
	}
}
