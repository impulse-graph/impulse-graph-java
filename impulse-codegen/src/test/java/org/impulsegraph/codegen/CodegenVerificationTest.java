package org.impulsegraph.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("impulse-codegen v2 Verification Tests")
class CodegenVerificationTest {

	@Test
	@DisplayName("Generates all v2 artifacts from social-graph.yaml with zero third-party imports")
	void testSocialGraphGeneration(@TempDir Path tempDir) throws IOException {
		Path yaml = Paths.get("src/test/resources/schemas/social-graph.yaml");
		GeneratorMain.generate(yaml.toString(), tempDir.toString());

		Path pkgDir = tempDir.resolve("org/impulsegraph/example/social");
		assertThat(pkgDir.resolve("SocialGraphSnapshot.java")).exists();
		assertThat(pkgDir.resolve("SocialGraphSnapshotWriter.java")).exists();
		assertThat(pkgDir.resolve("UserId.java")).exists();
		assertThat(pkgDir.resolve("UserDomainView.java")).exists();
		assertThat(pkgDir.resolve("UserTraversal.java")).exists();
		assertThat(pkgDir.resolve("UserAttrs.java")).exists();
		assertThat(pkgDir.resolve("UserPredicate.java")).exists();
		assertThat(pkgDir.resolve("UserRowView.java")).exists();
		assertThat(pkgDir.resolve("UserRecord.java")).exists();
		assertThat(pkgDir.resolve("PostId.java")).exists();
		assertThat(pkgDir.resolve("PostDomainView.java")).exists();
		assertThat(pkgDir.resolve("PostTraversal.java")).exists();
		assertThat(pkgDir.resolve("PostAttrs.java")).exists();
		assertThat(pkgDir.resolve("PostPredicate.java")).exists();
		assertThat(pkgDir.resolve("PostRowView.java")).exists();
		assertThat(pkgDir.resolve("PostRecord.java")).exists();

		// Verify zero third-party imports across every generated file
		try (Stream<Path> files = Files.walk(tempDir)) {
			List<Path> javaFiles = files.filter(p -> p.toString().endsWith(".java")).toList();
			assertThat(javaFiles).hasSize(16);
			for (Path jf : javaFiles) {
				for (String line : Files.readAllLines(jf)) {
					if (line.startsWith("import ")) {
						assertThat(line).matches("^import (java\\.|org\\.impulsegraph\\.).*;$");
					}
				}
			}
		}

		// Verify direction-aware generation: userFollowsUser has [out, in] +
		// inverseAlias followedBy
		String userTraversal = Files.readString(pkgDir.resolve("UserTraversal.java"));
		assertThat(userTraversal).contains("outUserFollowsUser()");
		assertThat(userTraversal).contains("inUserFollowsUser()");
		assertThat(userTraversal).contains("followedBy()");
		assertThat(userTraversal).contains("outUserPostsPost()");

		// userPostsPost is [out]-only: PostTraversal must NOT have inUserPostsPost()
		String postTraversal = Files.readString(pkgDir.resolve("PostTraversal.java"));
		assertThat(postTraversal).doesNotContain("inUserPostsPost");

		// Verify vector search dimension check on UserDomainView
		String userDomainView = Files.readString(pkgDir.resolve("UserDomainView.java"));
		assertThat(userDomainView).contains("vectorSearchEmbedding(float[] queryVector, int topK)");
		assertThat(userDomainView).contains("queryVector.length != 768");
		assertThat(userDomainView).contains("fromKey(UUID key)");
	}

	@Test
	@DisplayName("Generates cleanly from minimal-graph.yaml with package override")
	void testMinimalGraphWithPackageOverride(@TempDir Path tempDir) throws IOException {
		Path yaml = Paths.get("src/test/resources/schemas/minimal-graph.yaml");
		GeneratorMain.generate(yaml.toString(), tempDir.toString(), "org.impulsegraph.custom.minimal");

		Path pkgDir = tempDir.resolve("org/impulsegraph/custom/minimal");
		assertThat(pkgDir.resolve("MinimalGraphSnapshot.java")).exists();
		assertThat(pkgDir.resolve("NodeId.java")).exists();
		assertThat(Files.readString(pkgDir.resolve("NodeId.java")))
				.startsWith("package org.impulsegraph.custom.minimal;");
	}

	@Test
	@DisplayName("Fails fast on invalid schema YAML (unknown relation endpoint, empty domains, missing file)")
	void testFailFastOnInvalidSchemas(@TempDir Path tempDir) throws IOException {
		Path badEndpoint = tempDir.resolve("bad-endpoint.yaml");
		Files.writeString(badEndpoint, """
				graphName: BrokenGraph
				version: 1.0
				domains:
				  User:
				    keyType: int64
				relations:
				  brokenRel:
				    source: User
				    target: GhostDomain
				""");
		assertThatThrownBy(() -> GeneratorMain.generate(badEndpoint, tempDir.resolve("out1"), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("GhostDomain");

		Path emptyYaml = tempDir.resolve("empty.yaml");
		Files.writeString(emptyYaml, "graphName: EmptyGraph\nversion: 1.0\n");
		assertThatThrownBy(() -> GeneratorMain.generate(emptyYaml, tempDir.resolve("out2"), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one domain");

		assertThatThrownBy(
				() -> GeneratorMain.generate(tempDir.resolve("does-not-exist.yaml"), tempDir.resolve("out3"), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
	}
}
