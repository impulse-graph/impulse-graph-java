package org.impulsegraph.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("v0.11 Impulse Maven Plugin End-to-End Lifecycle & Failure Gate Tests")
class MavenPluginEndToEndTest {

	private record CompilationOutcome(boolean success, String diagnostics) {
	}

	@Test
	@DisplayName("1. Full 7-Mojo Maven lifecycle: build-snapshot -> extract-schema -> generate + javac -> generate-test + javac -> aot-compile (4 DSLs) -> validate -> check-compat")
	void fullSevenMojoLifecycleSequence(@TempDir Path tmp) throws Exception {
		Path manifest = writeMultiDomainDataset(tmp.resolve("dataset"));
		File snapshotFile = tmp.resolve("target/social.imps").toFile();

		// 1. build-snapshot
		BuildSnapshotMojo buildMojo = new BuildSnapshotMojo();
		buildMojo.manifestFile = manifest.toFile();
		buildMojo.outputFile = snapshotFile;
		buildMojo.skipIfMissing = false;
		buildMojo.execute();
		assertThat(snapshotFile).exists();

		// 2. extract-schema
		File extractedSchema = tmp.resolve("target/extracted.imps.schema.yaml").toFile();
		ExtractSchemaMojo extractMojo = new ExtractSchemaMojo();
		extractMojo.snapshotFile = snapshotFile;
		extractMojo.outputFile = extractedSchema;
		extractMojo.execute();
		assertThat(extractedSchema).exists();
		assertThat(Files.readString(extractedSchema.toPath())).contains("User:").contains("Post:")
				.contains("userFollowsUser:").contains("userPostsPost:");

		// 3. generate -> compile generated .java sources with javac
		MavenProject project = new MavenProject();
		File genSrcDir = tmp.resolve("target/generated-sources/impulse").toFile();
		GenerateQueryBuildersMojo genMojo = new GenerateQueryBuildersMojo();
		genMojo.schemaFile = extractedSchema;
		genMojo.outputDirectory = genSrcDir;
		genMojo.packageName = "org.impulsegraph.maven.e2e.main";
		genMojo.skipIfMissing = false;
		genMojo.project = project;
		genMojo.execute();

		assertThat(project.getCompileSourceRoots()).contains(genSrcDir.getAbsolutePath());
		CompilationOutcome mainCompile = compileDirectory(genSrcDir.toPath(), tmp.resolve("target/classes-main"));
		assertThat(mainCompile.success()).as("generate Mojo javac errors:\n%s", mainCompile.diagnostics()).isTrue();

		// 4. generate-test -> compile generated test .java sources with javac
		File genTestSrcDir = tmp.resolve("target/generated-test-sources/impulse").toFile();
		GenerateTestQueryBuildersMojo genTestMojo = new GenerateTestQueryBuildersMojo();
		genTestMojo.schemaFile = extractedSchema;
		genTestMojo.outputDirectory = genTestSrcDir;
		genTestMojo.packageName = "org.impulsegraph.maven.e2e.test";
		genTestMojo.skipIfMissing = false;
		genTestMojo.project = project;
		genTestMojo.execute();

		assertThat(project.getTestCompileSourceRoots()).contains(genTestSrcDir.getAbsolutePath());
		CompilationOutcome testCompile = compileDirectory(genTestSrcDir.toPath(), tmp.resolve("target/classes-test"));
		assertThat(testCompile.success()).as("generate-test Mojo javac errors:\n%s", testCompile.diagnostics())
				.isTrue();

		// 5. aot-compile across multi-file directory with .cypher, .impk, .implog,
		// .impscm (including nested subdir)
		Path queriesDir = tmp.resolve("src/main/impulse/queries");
		Files.createDirectories(queriesDir.resolve("nested"));
		Files.writeString(queriesDir.resolve("friends.cypher"),
				"MATCH (u:User)-[:userFollowsUser]->(f:User) WHERE u.id = 0 RETURN f", StandardCharsets.UTF_8);
		Files.writeString(queriesDir.resolve("rank.impk"), "p = pagerank(\"userFollowsUser\", x0, 0.85);\n",
				StandardCharsets.UTF_8);
		Files.writeString(queriesDir.resolve("reach.implog"),
				"reach(x, y) :- userFollowsUser(x, y).\nreach(x, y) :- reach(x, z), userFollowsUser(z, y).\n",
				StandardCharsets.UTF_8);
		Files.writeString(queriesDir.resolve("nested/posts.impscm"),
				"(program (walk \"userPostsPost\") (collect-bitset))", StandardCharsets.UTF_8);

		Path aotOutDir = tmp.resolve("target/generated-resources/impulse/queries");
		AotCompileQueriesMojo aotMojo = new AotCompileQueriesMojo();
		aotMojo.queriesDirectory = queriesDir.toFile();
		aotMojo.schemaFile = extractedSchema;
		aotMojo.outputDirectory = aotOutDir.toFile();
		aotMojo.skipIfMissing = false;
		aotMojo.execute();

		assertThat(aotOutDir.resolve("friends.impscm")).exists();
		assertThat(aotOutDir.resolve("rank.impscm")).exists();
		assertThat(aotOutDir.resolve("reach.impscm")).exists();
		assertThat(aotOutDir.resolve("nested/posts.impscm")).exists();
		assertThat(Files.readString(aotOutDir.resolve("friends.impscm"))).contains("userFollowsUser");
		assertThat(Files.readString(aotOutDir.resolve("rank.impscm"))).contains("userFollowsUser");
		assertThat(Files.readString(aotOutDir.resolve("reach.impscm"))).contains("userFollowsUser");
		assertThat(Files.readString(aotOutDir.resolve("nested/posts.impscm"))).contains("userPostsPost");

		// 6. validate (deepAudit = true, strictAlignment = true, schemaFile =
		// extractedSchema)
		ValidateSnapshotMojo validateMojo = new ValidateSnapshotMojo();
		validateMojo.snapshotFile = snapshotFile;
		validateMojo.schemaFile = extractedSchema;
		validateMojo.strictAlignment = true;
		validateMojo.deepAudit = true;
		validateMojo.quickCheck = false;
		validateMojo.execute();

		// 7. check-compat: multi-consumer pass + breaking consumer failure
		File consumerV1 = tmp.resolve("consumers/v1.yaml").toFile();
		Files.createDirectories(consumerV1.toPath().getParent());
		Files.writeString(consumerV1.toPath(), """
				graphName: ConsumerV1
				version: "1.0.0"
				domains:
				  User:
				    attributes:
				      reputation: { type: float32, nullable: false }
				      email: { type: var_string, nullable: true }
				relations:
				  userFollowsUser:
				    source: User
				    target: User
				    directions: [out, in]
				""", StandardCharsets.UTF_8);

		File consumerV1Optional = tmp.resolve("consumers/v1-optional.yaml").toFile();
		Files.writeString(consumerV1Optional.toPath(), """
				graphName: ConsumerV1Opt
				version: "1.1.0"
				domains:
				  User:
				    attributes:
				      reputation: { type: float32, nullable: false }
				      badge: { type: int32, optional: true }
				  Post:
				    attributes:
				      title: { type: var_string, nullable: false }
				relations:
				  userPostsPost:
				    source: User
				    target: Post
				    directions: [out]
				""", StandardCharsets.UTF_8);

		CheckCompatMojo compatMojo = new CheckCompatMojo();
		compatMojo.snapshotFile = snapshotFile;
		compatMojo.consumerSchemas = List.of(extractedSchema, consumerV1, consumerV1Optional);
		compatMojo.execute();

		File breakingConsumer = tmp.resolve("consumers/v2-breaking.yaml").toFile();
		Files.writeString(breakingConsumer.toPath(), """
				graphName: ConsumerV2Breaking
				version: "2.0.0"
				domains:
				  User:
				    attributes:
				      missing_tier: { type: int32, nullable: false }
				relations:
				  userPostsPost:
				    source: User
				    target: Post
				    directions: [out, in]
				""", StandardCharsets.UTF_8);

		compatMojo.consumerSchemas = List.of(consumerV1, breakingConsumer);
		assertThatThrownBy(compatMojo::execute).isInstanceOf(MojoFailureException.class)
				.hasMessageContaining("MISSING_ATTRIBUTE").hasMessageContaining("MISSING_DIRECTION");
	}

	@Test
	@DisplayName("2. Negative Maven build-failure gates: missing schemaFile, unknown attr in aot-compile, [out]-only reverse walk, corrupted .imps")
	void negativeMavenBuildFailureGates(@TempDir Path tmp) throws Exception {
		// 2a. generate with missing schemaFile and skipIfMissing = false ->
		// MojoExecutionException
		GenerateQueryBuildersMojo missingSchemaMojo = new GenerateQueryBuildersMojo();
		missingSchemaMojo.schemaFile = tmp.resolve("nonexistent-schema.yaml").toFile();
		missingSchemaMojo.outputDirectory = tmp.resolve("out").toFile();
		missingSchemaMojo.skipIfMissing = false;
		assertThatThrownBy(missingSchemaMojo::execute).isInstanceOf(MojoExecutionException.class)
				.hasMessageContaining("Required schemaFile does not exist");

		// Prepare schema for AOT negative tests
		File schemaFile = tmp.resolve("schema.yaml").toFile();
		Files.writeString(schemaFile.toPath(), """
				graphName: GateGraph
				version: "1.0.0"
				domains:
				  User:
				    attributes:
				      reputation: { type: float32, nullable: false }
				  Post:
				    attributes:
				      title: { type: var_string, nullable: false }
				relations:
				  userFollowsUser:
				    source: User
				    target: User
				    directions: [out, in]
				  userPostsPost:
				    source: User
				    target: Post
				    directions: [out]
				""", StandardCharsets.UTF_8);

		// 2b. aot-compile with unknown attribute -> MojoFailureException with schema
		// violation
		Path badAttrDir = tmp.resolve("queries-bad-attr");
		Files.createDirectories(badAttrDir);
		Files.writeString(badAttrDir.resolve("bad_attr.cypher"),
				"MATCH (u:User)-[r:userFollowsUser]->(f:User) WHERE u.id = 0 AND r.unknown_score > 10 RETURN f",
				StandardCharsets.UTF_8);

		AotCompileQueriesMojo badAttrMojo = new AotCompileQueriesMojo();
		badAttrMojo.queriesDirectory = badAttrDir.toFile();
		badAttrMojo.schemaFile = schemaFile;
		badAttrMojo.outputDirectory = tmp.resolve("aot-out-bad-attr").toFile();
		assertThatThrownBy(badAttrMojo::execute).isInstanceOf(MojoFailureException.class)
				.hasMessageContaining("unknown_score");

		// 2c. aot-compile with reverse walk on [out]-only relation ->
		// MojoFailureException with schema violation
		Path badRevDir = tmp.resolve("queries-bad-rev");
		Files.createDirectories(badRevDir);
		Files.writeString(badRevDir.resolve("bad_reverse.impscm"),
				"(program (csc-walk \"userPostsPost\") (collect-bitset))", StandardCharsets.UTF_8);

		AotCompileQueriesMojo badRevMojo = new AotCompileQueriesMojo();
		badRevMojo.queriesDirectory = badRevDir.toFile();
		badRevMojo.schemaFile = schemaFile;
		badRevMojo.outputDirectory = tmp.resolve("aot-out-bad-rev").toFile();
		assertThatThrownBy(badRevMojo::execute).isInstanceOf(MojoFailureException.class)
				.hasMessageContaining("userPostsPost").hasMessageContaining("reverse");

		// 2d. validate on corrupted .imps -> MojoFailureException
		File corruptImps = tmp.resolve("corrupt.imps").toFile();
		Files.write(corruptImps.toPath(), new byte[128]);
		ValidateSnapshotMojo badValidateMojo = new ValidateSnapshotMojo();
		badValidateMojo.snapshotFile = corruptImps;
		badValidateMojo.deepAudit = true;
		assertThatThrownBy(badValidateMojo::execute).isInstanceOf(MojoFailureException.class)
				.hasMessageContaining("Snapshot validation failed");
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static Path writeMultiDomainDataset(Path dir) throws IOException {
		Path domains = dir.resolve("domains");
		Path rels = dir.resolve("relations");
		Files.createDirectories(domains);
		Files.createDirectories(rels);

		Files.writeString(domains.resolve("users.csv"), """
				id,reputation,email,embedding
				u0,95.0,u0@example.com,"[1.0, 0.0, 0.0, 0.0]"
				u1,50.5,,"[0.0, 1.0, 0.0, 0.0]"
				u2,82.25,u2@example.com,"[0.0, 0.0, 1.0, 0.0]"
				""", StandardCharsets.UTF_8);

		Files.writeString(domains.resolve("posts.jsonl"), """
				{"id": 5001, "title": "Maven Plugin E2E"}
				{"id": 5002, "title": "AOT Query Validation"}
				""", StandardCharsets.UTF_8);

		Files.writeString(rels.resolve("follows.tsv"),
				"src\ttgt\tweight\n" + "u0\tu1\t1.0\n" + "u1\tu2\t0.8\n" + "u2\tu0\t0.6\n", StandardCharsets.UTF_8);

		Files.writeString(rels.resolve("user_posts.csv"), "src,tgt\nu0,5001\nu2,5002\n", StandardCharsets.UTF_8);

		Path manifest = dir.resolve("manifest.yaml");
		Files.writeString(manifest, """
				schema_name: MavenE2EGraph
				schema_version: "1.0.0"
				domains:
				  - id: 0
				    name: User
				    key_type: string
				    id_column: id
				    file: domains/users.csv
				    attributes:
				      - name: reputation
				        type: float32
				      - name: email
				        type: string?
				      - name: embedding
				        type: float32[4]
				  - id: 1
				    name: Post
				    key_type: int64
				    id_column: id
				    file: domains/posts.jsonl
				    attributes:
				      - name: title
				        type: string
				relations:
				  - id: 0
				    name: userFollowsUser
				    from: User
				    to: User
				    direction: bidirectional
				    file: relations/follows.tsv
				    attributes:
				      - name: weight
				        type: float32
				  - id: 1
				    name: userPostsPost
				    from: User
				    to: Post
				    include_csc: false
				    file: relations/user_posts.csv
				""", StandardCharsets.UTF_8);
		return manifest;
	}

	private static CompilationOutcome compileDirectory(Path srcDir, Path outDir) throws IOException {
		List<Path> files;
		try (Stream<Path> walk = Files.walk(srcDir)) {
			files = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertThat(files).isNotEmpty();
		Files.createDirectories(outDir);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("JDK ToolProvider.getSystemJavaCompiler() must be available").isNotNull();

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
			List<String> options = new ArrayList<>(List.of("-d", outDir.toAbsolutePath().toString(), "-cp",
					System.getProperty("java.class.path"), "--add-modules", "jdk.incubator.vector"));
			if (Runtime.version().feature() == 21) {
				options.add("--enable-preview");
				options.add("--release");
				options.add("21");
			}
			boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fm, diagnostics, options, null, units).call());
			StringBuilder sb = new StringBuilder();
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append(d.getKind()).append(": ").append(d.getSource() != null ? d.getSource().getName() : "")
						.append(':').append(d.getLineNumber()).append(' ').append(d.getMessage(null)).append('\n');
			}
			return new CompilationOutcome(ok, sb.toString());
		}
	}
}
