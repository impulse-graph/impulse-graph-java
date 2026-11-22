package org.impulsegraph.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpulseMavenPluginTest {

	@Test
	void generateAndGenerateTestMojos(@TempDir Path tmp) throws Exception {
		Path schema = tmp.resolve("schema.yaml");
		Files.writeString(schema, """
				packageName: com.example.gen
				graphName: Demo
				domains:
				  User:
				    attributes:
				      age: INT32
				relations:
				  KNOWS:
				    source: User
				    target: User
				""");

		MavenProject project = new MavenProject();
		File outDir = tmp.resolve("generated-sources").toFile();

		GenerateQueryBuildersMojo mojo = new GenerateQueryBuildersMojo();
		mojo.schemaFile = schema.toFile();
		mojo.outputDirectory = outDir;
		mojo.packageName = "com.example.gen";
		mojo.skipIfMissing = false;
		mojo.project = project;
		mojo.execute();

		assertThat(outDir.toPath().resolve("com/example/gen/DemoSnapshot.java")).exists();
		assertThat(project.getCompileSourceRoots()).contains(outDir.getAbsolutePath());

		// Fail-fast when schemaFile is missing
		GenerateQueryBuildersMojo failMojo = new GenerateQueryBuildersMojo();
		failMojo.schemaFile = tmp.resolve("missing.yaml").toFile();
		failMojo.outputDirectory = outDir;
		failMojo.skipIfMissing = false;
		assertThatThrownBy(failMojo::execute).isInstanceOf(MojoExecutionException.class)
				.hasMessageContaining("Required schemaFile does not exist");

		// Skip when skipIfMissing=true
		failMojo.skipIfMissing = true;
		failMojo.execute();

		// Test sources Mojo
		File testOutDir = tmp.resolve("generated-test-sources").toFile();
		GenerateTestQueryBuildersMojo testMojo = new GenerateTestQueryBuildersMojo();
		testMojo.schemaFile = schema.toFile();
		testMojo.outputDirectory = testOutDir;
		testMojo.packageName = "com.example.gen.test";
		testMojo.project = project;
		testMojo.execute();
		assertThat(testOutDir.toPath().resolve("com/example/gen/test/DemoSnapshot.java")).exists();
		assertThat(project.getTestCompileSourceRoots()).contains(testOutDir.getAbsolutePath());
	}

	@Test
	void aotCompileQueriesPositiveAndNegative(@TempDir Path tmp) throws Exception {
		Path schema = tmp.resolve("schema.yaml");
		Files.writeString(schema, """
				graphName: Social
				version: "1.0"
				domains:
				  User:
				    attributes:
				      age: int32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out]
				""");

		Path queriesDir = tmp.resolve("queries");
		Files.createDirectories(queriesDir);
		Files.writeString(queriesDir.resolve("valid.cypher"),
				"MATCH (u:User)-[:FOLLOWS]->(f:User) WHERE u.id = 0 RETURN f");

		Path outDir = tmp.resolve("aot-out");
		AotCompileQueriesMojo mojo = new AotCompileQueriesMojo();
		mojo.queriesDirectory = queriesDir.toFile();
		mojo.schemaFile = schema.toFile();
		mojo.outputDirectory = outDir.toFile();
		mojo.execute();

		assertThat(outDir.resolve("valid.impscm")).exists();
		assertThat(Files.readString(outDir.resolve("valid.impscm"))).contains("FOLLOWS");

		// Add invalid query referencing unknown relation -> must fail build!
		Files.writeString(queriesDir.resolve("broken.cypher"),
				"MATCH (u:User)-[:UNKNOWN_EDGE]->(f:User) WHERE u.id = 0 RETURN f");

		assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class).hasMessageContaining("UNKNOWN_EDGE");
	}

	@Test
	void buildExtractValidateAndCheckCompatMojos(@TempDir Path tmp) throws Exception {
		Path manifest = writeSampleManifest(tmp);
		File snapshotFile = tmp.resolve("test.imps").toFile();

		// 1. build-snapshot
		BuildSnapshotMojo buildMojo = new BuildSnapshotMojo();
		buildMojo.manifestFile = manifest.toFile();
		buildMojo.outputFile = snapshotFile;
		buildMojo.execute();
		assertThat(snapshotFile).exists();

		// 2. extract-schema
		File extractedSchema = tmp.resolve("extracted.imps.schema.yaml").toFile();
		ExtractSchemaMojo extractMojo = new ExtractSchemaMojo();
		extractMojo.snapshotFile = snapshotFile;
		extractMojo.outputFile = extractedSchema;
		extractMojo.execute();
		assertThat(extractedSchema).exists();
		assertThat(Files.readString(extractedSchema.toPath())).contains("User:").contains("FOLLOWS:");

		// 3. validate (deepAudit + strictAlignment + schemaFile)
		ValidateSnapshotMojo validateMojo = new ValidateSnapshotMojo();
		validateMojo.snapshotFile = snapshotFile;
		validateMojo.schemaFile = extractedSchema;
		validateMojo.strictAlignment = true;
		validateMojo.deepAudit = true;
		validateMojo.execute();

		// 4. check-compat passing
		File consumerV1 = tmp.resolve("v1.yaml").toFile();
		Files.writeString(consumerV1.toPath(), """
				graphName: V1
				version: "1.0"
				domains:
				  User:
				    attributes:
				      age: int32
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out, in]
				""");

		CheckCompatMojo compatMojo = new CheckCompatMojo();
		compatMojo.snapshotFile = snapshotFile;
		compatMojo.consumerSchemas = List.of(extractedSchema, consumerV1);
		compatMojo.execute();

		// 5. check-compat failing when a consumer requires a missing attribute
		File breakingConsumer = tmp.resolve("breaking.yaml").toFile();
		Files.writeString(breakingConsumer.toPath(), """
				graphName: Breaking
				version: "2.0"
				domains:
				  User:
				    attributes:
				      missing_col: float64
				relations:
				  FOLLOWS:
				    source: User
				    target: User
				    directions: [out]
				""");

		compatMojo.consumerSchemas = List.of(consumerV1, breakingConsumer);
		assertThatThrownBy(compatMojo::execute).isInstanceOf(MojoFailureException.class)
				.hasMessageContaining("MISSING_ATTRIBUTE");
	}

	private static Path writeSampleManifest(Path dir) throws IOException {
		Path domains = dir.resolve("domains");
		Path rels = dir.resolve("relations");
		Files.createDirectories(domains);
		Files.createDirectories(rels);

		Files.writeString(domains.resolve("users.csv"), "id,age\nu0,21\nu1,34\nu2,45\n");
		Files.writeString(rels.resolve("follows.csv"), "src,tgt\nu0,u1\nu1,u2\n");

		Path manifest = dir.resolve("manifest.yaml");
		Files.writeString(manifest, """
				name: PluginTestGraph
				version: "1.0"
				domains:
				  - id: 0
				    name: User
				    key_type: string
				    id_column: id
				    file: domains/users.csv
				    attributes:
				      - name: age
				        type: int32
				relations:
				  - id: 0
				    name: FOLLOWS
				    from: User
				    to: User
				    include_csc: true
				    file: relations/follows.csv
				""");
		return manifest;
	}
}
