package org.impulsegraph.storage.csr;

import org.impulsegraph.api.schema.GraphManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestYamlParserTest {

	@Test
	@DisplayName("Parse fully specified valid manifest with tablespaces, domains, relations, and virtual relations")
	void testParseCompleteValidManifest(@TempDir Path tempDir) throws IOException {
		String yaml = """
				# Full impulse graph manifest
				graphName: "EnterpriseKnowledgeGraph"
				version: "2.4"

				tablespaces:
				  core_ts:
				    file: core_storage.imps
				    description: Core identity tablespace
				    mode: read-only
				  analytics_ts:
				    file: analytics.imps
				    description: Secondary analytics data
				    mode: read-write

				domains:
				  User:
				    tablespace: core_ts
				    attributes:
				      userId: INT32
				      username: STRING
				      reputation: FLOAT64
				  Post:
				    tablespace: analytics_ts
				    attributes:
				      postId: INT64
				      views: INT32

				relations:
				  Follows:
				    source: User
				    target: User
				    tablespace: core_ts
				    attributes:
				      since: INT64
				  Authored:
				    source: User
				    target: Post
				    tablespace: analytics_ts
				    attributes:
				      timestamp: INT64

				virtual_relations:
				  FriendOfFriend:
				    components:
				      - "Follows"
				      - "Follows"
				""";

		Path manifestFile = tempDir.resolve("manifest.yaml");
		Files.writeString(manifestFile, yaml);

		GraphManifest manifest = ManifestYamlParser.parse(manifestFile);

		assertThat(manifest).isNotNull();
		assertThat(manifest.graphName()).isEqualTo("EnterpriseKnowledgeGraph");
		assertThat(manifest.version()).isEqualTo("2.4");

		// Tablespaces
		assertThat(manifest.tablespaces()).hasSize(2);
		GraphManifest.TablespaceDef coreTs = manifest.tablespaces().get("core_ts");
		assertThat(coreTs).isNotNull();
		assertThat(coreTs.file()).isEqualTo("core_storage.imps");
		assertThat(coreTs.description()).isEqualTo("Core identity tablespace");
		assertThat(coreTs.mode()).isEqualTo("read-only");

		// Domains
		assertThat(manifest.domains()).hasSize(2);
		GraphManifest.DomainDef userDomain = manifest.domains().get("User");
		assertThat(userDomain.tablespace()).isEqualTo("core_ts");
		assertThat(userDomain.attributes()).containsEntry("userId", "INT32").containsEntry("username", "STRING")
				.containsEntry("reputation", "FLOAT64");

		// Relations
		assertThat(manifest.relations()).hasSize(2);
		GraphManifest.RelationDef followsRel = manifest.relations().get("Follows");
		assertThat(followsRel.source()).isEqualTo("User");
		assertThat(followsRel.target()).isEqualTo("User");
		assertThat(followsRel.tablespace()).isEqualTo("core_ts");
		assertThat(followsRel.attributes()).containsEntry("since", "INT64");

		// Virtual Relations
		assertThat(manifest.virtualRelations()).hasSize(1);
		GraphManifest.VirtualRelationDef fof = manifest.virtualRelations().get("FriendOfFriend");
		assertThat(fof.components()).containsExactly("Follows", "Follows");
	}

	@Test
	@DisplayName("Defaults applied when optional fields are omitted")
	void testDefaultValuesWhenOmitted(@TempDir Path tempDir) throws IOException {
		String yaml = """
				tablespaces:
				  default_ts:
				    description: Default storage
				domains:
				  Device:
				    tablespace: default_ts
				""";

		Path manifestFile = tempDir.resolve("minimal.yaml");
		Files.writeString(manifestFile, yaml);

		GraphManifest manifest = ManifestYamlParser.parse(manifestFile);
		assertThat(manifest.graphName()).isEqualTo("ImpulseGraph");
		assertThat(manifest.version()).isEqualTo("1.0");

		GraphManifest.TablespaceDef ts = manifest.tablespaces().get("default_ts");
		assertThat(ts.file()).isEqualTo("default_ts.imps");
		assertThat(ts.mode()).isEqualTo("read-write");
	}

	@Test
	@DisplayName("Empty or comment-only YAML returns default empty manifest")
	void testEmptyAndCommentOnlyYaml(@TempDir Path tempDir) throws IOException {
		String yaml = """
				# Comment line 1
				# Comment line 2

				   # Indented comment
				""";

		Path manifestFile = tempDir.resolve("empty.yaml");
		Files.writeString(manifestFile, yaml);

		GraphManifest manifest = ManifestYamlParser.parse(manifestFile);
		assertThat(manifest.graphName()).isEqualTo("ImpulseGraph");
		assertThat(manifest.version()).isEqualTo("1.0");
		assertThat(manifest.tablespaces()).isEmpty();
		assertThat(manifest.domains()).isEmpty();
		assertThat(manifest.relations()).isEmpty();
		assertThat(manifest.virtualRelations()).isEmpty();
	}

	@Test
	@DisplayName("Non-existent file throws IOException")
	void testNonExistentFileThrows(@TempDir Path tempDir) {
		Path nonExistent = tempDir.resolve("missing_manifest.yaml");
		assertThatThrownBy(() -> ManifestYamlParser.parse(nonExistent)).isInstanceOf(IOException.class);
	}

	@Test
	@DisplayName("Negative syntax and domain traps: unknown root keys and malformed items handled gracefully")
	void testNegativeSyntaxAndDomainTraps(@TempDir Path tempDir) throws IOException {
		String yaml = """
				unknownRootProperty: 12345
				weird_section:
				  invalidItem:
				domains:
				  IsolatedDomain:
				    tablespace: unmapped_ts
				relations:
				  DanglingRelation:
				    source: NonExistentSrc
				    target: NonExistentTgt
				virtual_relations:
				  EmptyVirtualRel:
				    components:
				""";

		Path manifestFile = tempDir.resolve("traps.yaml");
		Files.writeString(manifestFile, yaml);

		GraphManifest manifest = ManifestYamlParser.parse(manifestFile);
		assertThat(manifest).isNotNull();
		assertThat(manifest.domains()).containsKey("IsolatedDomain");
		assertThat(manifest.domains().get("IsolatedDomain").tablespace()).isEqualTo("unmapped_ts");

		assertThat(manifest.relations()).containsKey("DanglingRelation");
		GraphManifest.RelationDef dang = manifest.relations().get("DanglingRelation");
		assertThat(dang.source()).isEqualTo("NonExistentSrc");
		assertThat(dang.target()).isEqualTo("NonExistentTgt");
		assertThat(dang.tablespace()).isNull();

		assertThat(manifest.virtualRelations()).containsKey("EmptyVirtualRel");
		assertThat(manifest.virtualRelations().get("EmptyVirtualRel").components()).isEmpty();
	}
}
