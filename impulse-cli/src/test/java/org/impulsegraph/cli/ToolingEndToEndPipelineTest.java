package org.impulsegraph.cli;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.SnapshotResolver;
import org.impulsegraph.api.lifecycle.SwapResult;
import org.impulsegraph.storage.csr.ImpulseHealthIndicator;
import org.impulsegraph.vm.lifecycle.SnapshotLifecycleManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("v0.11 Java Tooling End-to-End Pipeline Tests")
class ToolingEndToEndPipelineTest {

	private record CliResult(int exitCode, String out, String err) {
	}

	private record CompilationOutcome(boolean success, String diagnostics) {
	}

	private static CliResult exec(String... args) {
		CommandLine cmd = ImpulseCliMain.createCommandLine();
		StringWriter outSw = new StringWriter();
		StringWriter errSw = new StringWriter();
		cmd.setOut(new PrintWriter(outSw));
		cmd.setErr(new PrintWriter(errSw));
		int code = cmd.execute(args);
		return new CliResult(code, outSw.toString(), errSw.toString());
	}

	// =========================================================================
	// 1. Full Multi-Domain Build -> Extract -> Codegen -> javac -> Mount ->
	// Blue/Green Swap Pipeline
	// =========================================================================

	@Test
	@DisplayName("1. Full multi-domain CSV/TSV/JSONL build -> validate/inspect/stats -> extract -> codegen -> javac -> blue/green swap + breaking rejections")
	void fullMultiDomainBuildCodegenAndBlueGreenSwapPipeline(@TempDir Path tmp) throws Exception {
		// 1a. Create v1.0 multi-domain CSV + TSV + JSONL dataset
		Path v1Dir = tmp.resolve("v1_data");
		Path v1Manifest = writeV1Dataset(v1Dir);
		Path v1Imps = tmp.resolve("social-v1.0.imps");

		CliResult buildV1 = exec("build", "-m", v1Manifest.toString(), "-o", v1Imps.toString());
		assertThat(buildV1.exitCode()).withFailMessage("build v1 failed: %s\n%s", buildV1.out(), buildV1.err())
				.isEqualTo(0);
		assertThat(v1Imps).exists();

		// 1b. Validate (--strict-alignment --deep-audit), inspect (--format json
		// --page-map, --anonymize), stats (--format json --verbose)
		CliResult valRes = exec("snapshot", "validate", v1Imps.toString(), "--strict-alignment", "--deep-audit");
		assertThat(valRes.exitCode()).withFailMessage("validate failed: %s\n%s", valRes.out(), valRes.err())
				.isEqualTo(0);
		assertThat(valRes.out()).contains("Status : PASS").contains("Deep audit CSC transpose consistency verified");

		CliResult inspectJson = exec("inspect", v1Imps.toString(), "--format", "json", "--page-map");
		assertThat(inspectJson.exitCode()).isEqualTo(0);
		assertThat(inspectJson.out()).contains("\"magic\": \"0x494D5053\"").contains("\"pageMap\"").contains("\"User\"")
				.contains("\"Post\"").contains("\"userFollowsUser\"").contains("\"userPostsPost\"");

		CliResult inspectAnon = exec("inspect", v1Imps.toString(), "--anonymize", "--domain", "User", "--relation",
				"userFollowsUser");
		assertThat(inspectAnon.exitCode()).isEqualTo(0);
		assertThat(inspectAnon.out()).contains("domain_0").contains("rel_0").doesNotContain("User")
				.doesNotContain("Post").doesNotContain("userFollowsUser").doesNotContain("userPostsPost");

		CliResult inspectAnonJson = exec("inspect", v1Imps.toString(), "--anonymize", "--format", "json");
		assertThat(inspectAnonJson.exitCode()).isEqualTo(0);
		assertThat(inspectAnonJson.out()).contains("domain_0").contains("rel_0").doesNotContain("User")
				.doesNotContain("Post").doesNotContain("userFollowsUser").doesNotContain("userPostsPost");

		CliResult statsJson = exec("stats", v1Imps.toString(), "--format", "json", "--verbose");
		assertThat(statsJson.exitCode()).isEqualTo(0);
		assertThat(statsJson.out()).contains("\"userFollowsUser\"").contains("\"userPostsPost\"")
				.contains("\"edgeCount\": 4").contains("\"p50Degree\"");

		// 1c. schema extract -> schema validate --snapshot -> codegen from extracted
		// YAML, consumer YAML (with inverseAlias), and binary .imps
		Path extractedYaml = tmp.resolve("extracted-v1.imps.schema.yaml");
		CliResult extractRes = exec("schema", "extract", v1Imps.toString(), "-o", extractedYaml.toString());
		assertThat(extractRes.exitCode()).withFailMessage("extract failed: %s", extractRes.err()).isEqualTo(0);
		assertThat(extractedYaml).exists();
		assertThat(Files.readString(extractedYaml)).contains("User:").contains("Post:").contains("userFollowsUser:")
				.contains("userPostsPost:");

		CliResult schemaValRes = exec("schema", "validate", extractedYaml.toString(), "--snapshot", v1Imps.toString());
		assertThat(schemaValRes.exitCode())
				.withFailMessage("schema validate failed: %s\n%s", schemaValRes.out(), schemaValRes.err()).isEqualTo(0);
		assertThat(schemaValRes.out()).contains("Status     : PASS");

		// Consumer schema with inverseAlias: followedBy on userFollowsUser
		Path consumerYaml = tmp.resolve("consumer-v1.yaml");
		Files.writeString(consumerYaml, """
				graphName: SocialApp
				version: "1.0.0"
				package: org.impulsegraph.e2e.social
				domains:
				  User:
				    keyType: string
				    attributes:
				      reputation: { type: float32, nullable: false }
				      email: { type: var_string, nullable: true }
				      embedding: { type: "float32[4]", nullable: false }
				  Post:
				    keyType: int64
				    attributes:
				      title: { type: var_string, nullable: false }
				relations:
				  userFollowsUser:
				    source: User
				    target: User
				    directions: [out, in]
				    inverseAlias: followedBy
				    attributes:
				      weight: { type: float32, nullable: false }
				  userPostsPost:
				    source: User
				    target: Post
				    directions: [out]
				""", StandardCharsets.UTF_8);

		CliResult consumerVal = exec("schema", "validate", consumerYaml.toString(), "--snapshot", v1Imps.toString());
		assertThat(consumerVal.exitCode())
				.withFailMessage("consumer schema validate failed: %s\n%s", consumerVal.out(), consumerVal.err())
				.isEqualTo(0);

		Path genConsumerSrc = tmp.resolve("gen-consumer-src");
		Path genExtractedSrc = tmp.resolve("gen-extracted-src");
		Path genBinarySrc = tmp.resolve("gen-binary-src");

		CliResult cgConsumer = exec("codegen", "--schema", consumerYaml.toString(), "--out", genConsumerSrc.toString(),
				"--package", "org.impulsegraph.e2e.social");
		assertThat(cgConsumer.exitCode()).withFailMessage("codegen consumer failed: %s", cgConsumer.err()).isEqualTo(0);

		CliResult cgExtracted = exec("codegen", "--schema", extractedYaml.toString(), "--out",
				genExtractedSrc.toString(), "--package", "org.impulsegraph.e2e.extracted");
		assertThat(cgExtracted.exitCode()).withFailMessage("codegen extracted failed: %s", cgExtracted.err())
				.isEqualTo(0);

		CliResult cgBinary = exec("codegen", "--schema", v1Imps.toString(), "--out", genBinarySrc.toString(),
				"--package", "org.impulsegraph.e2e.fromimps");
		assertThat(cgBinary.exitCode()).withFailMessage("codegen binary failed: %s", cgBinary.err()).isEqualTo(0);

		// 1d. Compile all 3 generated Java source trees via
		// ToolProvider.getSystemJavaCompiler()
		Path genConsumerClasses = tmp.resolve("gen-consumer-classes");
		CompilationOutcome compConsumer = compileDirectory(genConsumerSrc, genConsumerClasses);
		assertThat(compConsumer.success()).as("consumer codegen javac errors:\n%s", compConsumer.diagnostics())
				.isTrue();

		CompilationOutcome compExtracted = compileDirectory(genExtractedSrc, tmp.resolve("gen-extracted-classes"));
		assertThat(compExtracted.success()).as("extracted codegen javac errors:\n%s", compExtracted.diagnostics())
				.isTrue();

		CompilationOutcome compBinary = compileDirectory(genBinarySrc, tmp.resolve("gen-binary-classes"));
		assertThat(compBinary.success()).as("binary .imps codegen javac errors:\n%s", compBinary.diagnostics())
				.isTrue();

		// 1e. Build additive + shifted column index v1.1 snapshot
		Path v1_1Imps = tmp.resolve("social-v1.1-shifted.imps");
		Path v1_1Manifest = writeV1_1AdditiveShiftedDataset(tmp.resolve("v1_1_data"));
		CliResult buildV1_1 = exec("build", "-m", v1_1Manifest.toString(), "-o", v1_1Imps.toString());
		assertThat(buildV1_1.exitCode()).withFailMessage("build v1.1 failed: %s", buildV1_1.err()).isEqualTo(0);

		// 1f. Build 4 breaking v2.0 snapshots
		Path breakMissingAttr = buildBreakingSnapshot(tmp, "break-missing-attr", BreakingKind.MISSING_REQUIRED_ATTR);
		Path breakNullability = buildBreakingSnapshot(tmp, "break-nullability", BreakingKind.NULLABILITY_WIDENED);
		Path breakMissingCsc = buildBreakingSnapshot(tmp, "break-missing-csc", BreakingKind.MISSING_CSC);
		Path breakTargetDomain = buildBreakingSnapshot(tmp, "break-target-domain", BreakingKind.CHANGED_TARGET_DOMAIN);

		// 1g. Load compiled SocialAppSnapshot via URLClassLoader & run
		// SnapshotLifecycleManager blue/green swaps
		try (URLClassLoader loader = new URLClassLoader(new URL[]{genConsumerClasses.toUri().toURL()},
				getClass().getClassLoader())) {
			Class<?> snapCls = loader.loadClass("org.impulsegraph.e2e.social.SocialAppSnapshot");

			SnapshotResolver initialResolver = ctx -> Optional.of(new CandidateSnapshotRef("rev-1.0", v1Imps, false));
			try (@SuppressWarnings("unchecked")
			SnapshotLifecycleManager<Object> mgr = (SnapshotLifecycleManager<Object>) snapCls
					.getMethod("createLifecycleManager", SnapshotResolver.class).invoke(null, initialResolver)) {

				assertThat(mgr.activeRevision()).contains("rev-1.0");
				assertThat(mgr.healthIndicator().readiness().status()).isEqualTo(ImpulseHealthIndicator.Status.UP);

				// Verify initial v1.0 reads (reputation, nullable email, float32[4] embedding,
				// inverseAlias followedBy, outUserPostsPost)
				mgr.withSnapshot(snap -> {
					try {
						Object users = snapCls.getMethod("users").invoke(snap);
						assertThat(users.getClass().getMethod("nodeCount").invoke(users)).isEqualTo(3L);

						Object allUsers = users.getClass().getMethod("all").invoke(users);
						List<?> userRecords = (List<?>) allUsers.getClass().getMethod("toRecords").invoke(allUsers);
						assertThat(userRecords).hasSize(3);
						assertThat((float) userRecords.get(0).getClass().getMethod("reputation")
								.invoke(userRecords.get(0))).isEqualTo(98.5f);
						assertThat(userRecords.get(0).getClass().getMethod("email").invoke(userRecords.get(0)))
								.isEqualTo("alice@example.com");
						assertThat(userRecords.get(1).getClass().getMethod("email").invoke(userRecords.get(1)))
								.isNull();
						assertThat((float[]) userRecords.get(0).getClass().getMethod("embedding")
								.invoke(userRecords.get(0))).containsExactly(1.0f, 0.5f, 0.25f, 0.0f);

						// Reverse traversal via inverseAlias followedBy(): user 2 is followed by {0, 1}
						Object u2 = users.getClass().getMethod("from", long.class).invoke(users, 2L);
						Object followers = u2.getClass().getMethod("followedBy").invoke(u2);
						assertThat((long[]) followers.getClass().getMethod("toDenseIds").invoke(followers))
								.containsExactly(0L, 1L);

						// Forward cross-domain traversal: user 0 posts {0, 1}
						Object u0 = users.getClass().getMethod("from", long.class).invoke(users, 0L);
						Object posts = u0.getClass().getMethod("outUserPostsPost").invoke(u0);
						List<?> postRecords = (List<?>) posts.getClass().getMethod("toRecords").invoke(posts);
						assertThat(postRecords).hasSize(2);
						assertThat(postRecords.get(0).getClass().getMethod("title").invoke(postRecords.get(0)))
								.isEqualTo("Intro to Impulse");
						return true;
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});

				// Concurrent reader threads during additive + shifted column index swap
				AtomicBoolean keepReading = new AtomicBoolean(true);
				AtomicInteger readIterations = new AtomicInteger(0);
				List<Throwable> readerErrors = new CopyOnWriteArrayList<>();
				CountDownLatch readersStarted = new CountDownLatch(4);
				ExecutorService pool = Executors.newFixedThreadPool(4);
				List<Future<?>> futures = new ArrayList<>();
				for (int t = 0; t < 4; t++) {
					futures.add(pool.submit(() -> {
						readersStarted.countDown();
						while (keepReading.get()) {
							try {
								mgr.withSnapshot(snap -> {
									try {
										Object users = snapCls.getMethod("users").invoke(snap);
										Object all = users.getClass().getMethod("all").invoke(users);
										List<?> recs = (List<?>) all.getClass().getMethod("toRecords").invoke(all);
										if (recs.size() < 3) {
											throw new AssertionError("Expected >= 3 users, got " + recs.size());
										}
										float rep0 = (float) recs.get(0).getClass().getMethod("reputation")
												.invoke(recs.get(0));
										if (rep0 != 98.5f && rep0 != 199.5f) {
											throw new AssertionError("Unexpected reputation: " + rep0);
										}
										readIterations.incrementAndGet();
										return rep0;
									} catch (Exception e) {
										throw new RuntimeException(e);
									}
								});
							} catch (Throwable ex) {
								readerErrors.add(ex);
							}
						}
					}));
				}

				assertThat(readersStarted.await(5, TimeUnit.SECONDS)).isTrue();
				Thread.sleep(30);

				// Perform additive + shifted column index swap to v1.1
				SwapResult<Object> swapV1_1 = mgr.trySwap(new CandidateSnapshotRef("rev-1.1", v1_1Imps, false));
				assertThat(swapV1_1).isInstanceOf(SwapResult.Swapped.class);
				assertThat(mgr.activeRevision()).contains("rev-1.1");
				assertThat(mgr.isSwapDegraded()).isFalse();

				// Verify shifted attributes read exact expected v1.1 values!
				mgr.withSnapshot(snap -> {
					try {
						Object users = snapCls.getMethod("users").invoke(snap);
						assertThat(users.getClass().getMethod("nodeCount").invoke(users)).isEqualTo(4L);
						Object allUsers = users.getClass().getMethod("all").invoke(users);
						List<?> recs = (List<?>) allUsers.getClass().getMethod("toRecords").invoke(allUsers);
						assertThat(recs).hasSize(4);
						assertThat((float) recs.get(0).getClass().getMethod("reputation").invoke(recs.get(0)))
								.isEqualTo(199.5f);
						assertThat(recs.get(0).getClass().getMethod("email").invoke(recs.get(0)))
								.isEqualTo("alice-v11@example.com");
						assertThat(recs.get(1).getClass().getMethod("email").invoke(recs.get(1))).isNull();
						assertThat((float[]) recs.get(0).getClass().getMethod("embedding").invoke(recs.get(0)))
								.containsExactly(9.0f, 8.0f, 7.0f, 6.0f);
						return true;
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});

				// Attempt all 4 breaking v2.0 swaps while concurrent readers continue hitting
				// active Epoch A (rev-1.1)
				List<Path> breakingSnapshots = List.of(breakMissingAttr, breakNullability, breakMissingCsc,
						breakTargetDomain);
				long expectedRejections = 0;
				for (int i = 0; i < breakingSnapshots.size(); i++) {
					SwapResult<Object> rejected = mgr
							.trySwap(new CandidateSnapshotRef("rev-2.0-break-" + i, breakingSnapshots.get(i), false));
					expectedRejections++;
					assertThat(rejected).as("Breaking snapshot #%d must be rejected", i)
							.isInstanceOf(SwapResult.Rejected.class);
					SwapResult.Rejected<Object> rej = (SwapResult.Rejected<Object>) rejected;
					assertThat(rej.violations()).isNotEmpty();

					// Active epoch remains rev-1.1; K8s readiness stays UP with swapDegraded=true
					assertThat(mgr.activeRevision()).contains("rev-1.1");
					assertThat(mgr.swapRejectedCount()).isEqualTo(expectedRejections);
					assertThat(mgr.isSwapDegraded()).isTrue();
					ImpulseHealthIndicator.HealthReport readiness = mgr.healthIndicator().readiness();
					assertThat(readiness.status()).isEqualTo(ImpulseHealthIndicator.Status.UP);
					assertThat(readiness.details()).containsEntry("swapDegraded", true);
					assertThat(readiness.details()).containsEntry("swapRejectedCount", expectedRejections);
				}

				Thread.sleep(30);
				keepReading.set(false);
				for (Future<?> f : futures) {
					f.get(5, TimeUnit.SECONDS);
				}
				pool.shutdownNow();

				assertThat(readerErrors).as("Concurrent readers must experience 0 errors across swaps and rejections")
						.isEmpty();
				assertThat(readIterations.get()).isGreaterThan(0);
			}
		}
	}

	// =========================================================================
	// 2. Negative Corruption & Validation Tests
	// =========================================================================

	@Test
	@DisplayName("2. Negative corruption tests: corrupt magic, truncated file, flipped payload SHA-256, and invalid manifest/missing CSV")
	void negativeCorruptionAndValidationTests(@TempDir Path tmp) throws IOException {
		Path validImps = tmp.resolve("valid.imps");
		Path manifest = writeV1Dataset(tmp.resolve("valid_data"));
		CliResult buildRes = exec("build", "-m", manifest.toString(), "-o", validImps.toString());
		assertThat(buildRes.exitCode()).isEqualTo(0);
		byte[] validBytes = Files.readAllBytes(validImps);

		// (a) Corrupt magic bytes
		Path corruptMagic = tmp.resolve("corrupt-magic.imps");
		byte[] badMagicBytes = validBytes.clone();
		badMagicBytes[0] = (byte) 0xDE;
		badMagicBytes[1] = (byte) 0xAD;
		badMagicBytes[2] = (byte) 0xBE;
		badMagicBytes[3] = (byte) 0xEF;
		Files.write(corruptMagic, badMagicBytes);

		CliResult valMagic = exec("snapshot", "validate", corruptMagic.toString());
		assertThat(valMagic.exitCode()).isNotEqualTo(0);
		assertThat(valMagic.out() + valMagic.err()).containsIgnoringCase("magic");

		CliResult inspMagic = exec("inspect", corruptMagic.toString());
		assertThat(inspMagic.exitCode()).isNotEqualTo(0);

		// (b) Truncate file mid-section
		Path truncated = tmp.resolve("truncated.imps");
		int truncLen = Math.min(validBytes.length / 2, 4200);
		byte[] truncBytes = new byte[truncLen];
		System.arraycopy(validBytes, 0, truncBytes, 0, truncLen);
		Files.write(truncated, truncBytes);

		CliResult valTrunc = exec("snapshot", "validate", truncated.toString());
		assertThat(valTrunc.exitCode()).isNotEqualTo(0);
		assertThat(valTrunc.out() + valTrunc.err()).containsIgnoringCase("FAIL");

		CliResult inspTrunc = exec("inspect", truncated.toString());
		assertThat(inspTrunc.exitCode()).isNotEqualTo(0);

		// (c) Flip a payload byte to break SHA-256 checksum
		Path flippedChecksum = tmp.resolve("flipped-sha256.imps");
		byte[] flippedBytes = validBytes.clone();
		int midPayload = Math.min(4096 + 64, flippedBytes.length - 65);
		flippedBytes[midPayload] ^= (byte) 0xFF;
		Files.write(flippedChecksum, flippedBytes);

		CliResult valChecksum = exec("snapshot", "validate", flippedChecksum.toString());
		assertThat(valChecksum.exitCode()).isNotEqualTo(0);
		assertThat(valChecksum.out() + valChecksum.err()).containsIgnoringCase("FAIL");

		// (d) Invalid manifest / missing CSV
		Path badManifest = tmp.resolve("missing-csv-manifest.yaml");
		Files.writeString(badManifest, """
				name: BrokenGraph
				version: "1.0"
				domains:
				  - id: 0
				    name: User
				    key_type: string
				    file: does_not_exist.csv
				relations: []
				""", StandardCharsets.UTF_8);

		CliResult buildBad = exec("build", "-m", badManifest.toString(), "-o", tmp.resolve("never.imps").toString());
		assertThat(buildBad.exitCode()).isNotEqualTo(0);
		assertThat(buildBad.err() + buildBad.out()).isNotBlank();
	}

	// =========================================================================
	// 3. Multi-Consumer K8s Rolling-Upgrade schema check-compat Matrix
	// =========================================================================

	@Test
	@DisplayName("3. Multi-consumer K8s rolling-upgrade schema check-compat JSON matrix (pass + breaking failure)")
	void multiConsumerRollingUpgradeCheckCompatMatrix(@TempDir Path tmp) throws IOException {
		Path v1_1Manifest = writeV1_1AdditiveShiftedDataset(tmp.resolve("v1_1_data"));
		Path v1_1Imps = tmp.resolve("v1_1.imps");
		assertThat(exec("build", "-m", v1_1Manifest.toString(), "-o", v1_1Imps.toString()).exitCode()).isEqualTo(0);

		Path v1Consumer = tmp.resolve("v1.yaml");
		Files.writeString(v1Consumer, """
				graphName: ConsumerV1
				version: "1.0.0"
				domains:
				  User:
				    attributes:
				      reputation: { type: float32, nullable: false }
				      email: { type: var_string, nullable: true }
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

		Path v1OptionalConsumer = tmp.resolve("v1_optional.yaml");
		Files.writeString(v1OptionalConsumer, """
				graphName: ConsumerV1Optional
				version: "1.1.0"
				domains:
				  User:
				    attributes:
				      reputation: { type: float32, nullable: false }
				      tier: { type: int32, nullable: false }
				      future_badge: { type: int32, optional: true }
				relations:
				  userFollowsUser:
				    source: User
				    target: User
				    directions: [out]
				""", StandardCharsets.UTF_8);

		CliResult passRes = exec("schema", "check-compat", "--snapshot", v1_1Imps.toString(), "--consumer",
				v1Consumer.toString(), "--consumer", v1OptionalConsumer.toString(), "--format", "json");
		assertThat(passRes.exitCode()).withFailMessage("compat pass failed: %s\n%s", passRes.out(), passRes.err())
				.isEqualTo(0);
		assertThat(passRes.out()).contains("\"compatible\": true").contains("ConsumerV1")
				.contains("ConsumerV1Optional");

		Path v2BreakingConsumer = tmp.resolve("v2_breaking.yaml");
		Files.writeString(v2BreakingConsumer, """
				graphName: ConsumerV2Breaking
				version: "2.0.0"
				domains:
				  User:
				    attributes:
				      nonexistent_credit_score: { type: float64, nullable: false }
				relations:
				  userPostsPost:
				    source: User
				    target: Post
				    directions: [out, in]
				""", StandardCharsets.UTF_8);

		CliResult failRes = exec("schema", "check-compat", "--snapshot", v1_1Imps.toString(), "--consumer",
				v1Consumer.toString(), "--consumer", v2BreakingConsumer.toString(), "--format", "json");
		assertThat(failRes.exitCode()).isNotEqualTo(0);
		assertThat(failRes.out()).contains("\"compatible\": false").contains("v2_breaking.yaml")
				.contains("MISSING_ATTRIBUTE").contains("MISSING_DIRECTION");
	}

	// =========================================================================
	// 4. Compiler, Assembler, Disassembler, Explainer & VM Run Pipeline
	// =========================================================================

	@Test
	@DisplayName("4. Compiler (.cypher/.impk/.implog/.impscm), schema validation failures, explain, assemble/disassemble, and VM run")
	void compilerAssemblerDisassemblerExplainAndVmRunPipeline(@TempDir Path tmp) throws IOException {
		Path v1Manifest = writeV1Dataset(tmp.resolve("v1_data"));
		Path v1Imps = tmp.resolve("v1.imps");
		assertThat(exec("build", "-m", v1Manifest.toString(), "-o", v1Imps.toString()).exitCode()).isEqualTo(0);

		Path schemaYaml = tmp.resolve("extracted.yaml");
		assertThat(exec("schema", "extract", v1Imps.toString(), "-o", schemaYaml.toString()).exitCode()).isEqualTo(0);

		// 4a. Four frontend languages -> compile --schema ... --emit-ir & explain
		Path cypherFile = tmp.resolve("q.cypher");
		Files.writeString(cypherFile, "MATCH (u:User)-[:userFollowsUser]->(f:User) WHERE u.id = 0 RETURN f");

		Path impkFile = tmp.resolve("q.impk");
		Files.writeString(impkFile, "p = pagerank(\"userFollowsUser\", x0, 0.85);\nt = topk(p, 3);\n");

		Path implogFile = tmp.resolve("q.implog");
		Files.writeString(implogFile,
				"reach(x, y) :- userFollowsUser(x, y).\nreach(x, y) :- reach(x, z), userFollowsUser(z, y).\n");

		Path impscmFile = tmp.resolve("q.impscm");
		Files.writeString(impscmFile, "(program (walk \"userFollowsUser\") (collect-bitset))");

		for (Path qFile : List.of(cypherFile, impkFile, implogFile, impscmFile)) {
			Path irOut = tmp.resolve(qFile.getFileName() + ".ir.impscm");
			CliResult compRes = exec("compile", qFile.toString(), "--schema", schemaYaml.toString(), "--emit-ir", "-o",
					irOut.toString());
			assertThat(compRes.exitCode())
					.withFailMessage("compile %s failed: %s\n%s", qFile.getFileName(), compRes.out(), compRes.err())
					.isEqualTo(0);
			assertThat(irOut).exists();
			assertThat(Files.readString(irOut)).contains("userFollowsUser");

			CliResult explainRes = exec("explain", qFile.toString(), "--schema", schemaYaml.toString(), "--snapshot",
					v1Imps.toString());
			assertThat(explainRes.exitCode()).withFailMessage("explain %s failed: %s\n%s", qFile.getFileName(),
					explainRes.out(), explainRes.err()).isEqualTo(0);
			assertThat(explainRes.out()).contains("IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT");
		}

		// 4b. Negative schema validation failures (unknown relation & unknown
		// attribute)
		Path badRelQuery = tmp.resolve("bad-rel.cypher");
		Files.writeString(badRelQuery, "MATCH (u:User)-[:noSuchRelation]->(f:User) WHERE u.id = 0 RETURN f");
		CliResult badRelRes = exec("compile", badRelQuery.toString(), "--schema", schemaYaml.toString(), "--emit-ir");
		assertThat(badRelRes.exitCode()).isNotEqualTo(0);
		assertThat(badRelRes.err() + badRelRes.out()).contains("noSuchRelation");

		Path badAttrQuery = tmp.resolve("bad-attr.cypher");
		Files.writeString(badAttrQuery,
				"MATCH (u:User)-[r:userFollowsUser]->(f:User) WHERE u.id = 0 AND r.nonexistent_attr > 10 RETURN f");
		CliResult badAttrRes = exec("explain", badAttrQuery.toString(), "--schema", schemaYaml.toString(), "--snapshot",
				v1Imps.toString());
		assertThat(badAttrRes.exitCode()).isNotEqualTo(0);
		assertThat(badAttrRes.err() + badAttrRes.out()).contains("nonexistent_attr");

		// 4c. Assemble .impas -> .impb -> disassemble -> run --verify --input-val 0
		Path asmFile = tmp.resolve("walk.impas");
		Files.writeString(asmFile, """
				OP_INIT_INPUT_NODE dst=R0
				OP_CSR_WALK dst=R1, payload=0x00000000
				OP_COLLECT_BITSET dst=R1
				OP_HALT
				""");
		Path impbFile = tmp.resolve("walk.impb");
		CliResult asmRes = exec("assemble", "-i", asmFile.toString(), "-o", impbFile.toString());
		assertThat(asmRes.exitCode()).withFailMessage("assemble failed: %s", asmRes.err()).isEqualTo(0);
		assertThat(Files.size(impbFile)).isEqualTo(32L);

		CliResult disRes = exec("disassemble", "-i", impbFile.toString());
		assertThat(disRes.exitCode()).isEqualTo(0);
		assertThat(disRes.out()).contains("OP_INIT_INPUT_NODE").contains("OP_CSR_WALK").contains("OP_HALT");

		CliResult runRes = exec("run", "--snapshot", v1Imps.toString(), "--bytecode", impbFile.toString(), "--verify",
				"--input-val", "0", "--format", "json");
		assertThat(runRes.exitCode()).withFailMessage("run failed: %s\n%s", runRes.out(), runRes.err()).isEqualTo(0);
		assertThat(runRes.out()).contains("\"verified\": true").contains("\"cardinality\": 2");

		// 4c-ii. Scalar R63 return value (OP_CSR_DEGREE -> R63, no OP_COLLECT_BITSET)
		Path degAsm = tmp.resolve("degree.impas");
		Files.writeString(degAsm, """
				OP_INIT_INPUT_NODE dst=R1
				OP_CSR_DEGREE R63, R1, 0
				OP_HALT
				""");
		Path degImpb = tmp.resolve("degree.impb");
		CliResult degAsmRes = exec("assemble", "-i", degAsm.toString(), "-o", degImpb.toString());
		assertThat(degAsmRes.exitCode()).withFailMessage("assemble degree failed: %s", degAsmRes.err()).isEqualTo(0);

		CliResult degRunText = exec("run", "--snapshot", v1Imps.toString(), "--bytecode", degImpb.toString(),
				"--verify", "--input-val", "0");
		assertThat(degRunText.exitCode())
				.withFailMessage("run degree text failed: %s\n%s", degRunText.out(), degRunText.err()).isEqualTo(0);
		assertThat(degRunText.out()).contains("Result Type  : SCALAR_INT").contains("Scalar Return Value: 2");

		CliResult degRunJson = exec("run", "--snapshot", v1Imps.toString(), "--bytecode", degImpb.toString(),
				"--verify", "--input-val", "0", "--format", "json");
		assertThat(degRunJson.exitCode()).isEqualTo(0);
		assertThat(degRunJson.out()).contains("\"resultType\": \"SCALAR_INT\"").contains("\"scalarValue\": 2");

		// 4d. Negative: invalid .impas and truncated .impb
		Path badAsm = tmp.resolve("bad.impas");
		Files.writeString(badAsm, "OP_TOTALLY_BOGUS_OPCODE dst=R99\n");
		CliResult badAsmRes = exec("assemble", "-i", badAsm.toString(), "-o", tmp.resolve("bad.impb").toString());
		assertThat(badAsmRes.exitCode()).isNotEqualTo(0);

		Path truncImpb = tmp.resolve("trunc.impb");
		Files.write(truncImpb, new byte[]{1, 2, 3, 4, 5});
		CliResult badDisRes = exec("disassemble", "-i", truncImpb.toString());
		assertThat(badDisRes.exitCode()).isNotEqualTo(0);

		CliResult badRunRes = exec("run", "--snapshot", v1Imps.toString(), "--bytecode", truncImpb.toString(),
				"--verify", "--input-val", "0");
		assertThat(badRunRes.exitCode()).isNotEqualTo(0);
	}

	// =========================================================================
	// 5. Real Subprocess Execution of ./src/main/scripts/impulse-j
	// =========================================================================

	@Test
	@DisplayName("5. Real subprocess execution of ./src/main/scripts/impulse-j with shaded -all.jar")
	void realSubprocessLauncherExecution(@TempDir Path tmp) throws Exception {
		Path script = Path.of("src/main/scripts/impulse-j").toAbsolutePath().normalize();
		Assumptions.assumeTrue(Files.isRegularFile(script), "impulse-j launcher script not found at " + script);

		Path shadedJar = findShadedJar(Path.of("target").toAbsolutePath().normalize());
		Assumptions.assumeTrue(shadedJar != null && Files.isRegularFile(shadedJar),
				"Shaded impulse-cli-*-all.jar not present in target/");

		Path v1Manifest = writeV1Dataset(tmp.resolve("sub_data"));
		Path v1Imps = tmp.resolve("sub.imps");
		assertThat(exec("build", "-m", v1Manifest.toString(), "-o", v1Imps.toString()).exitCode()).isEqualTo(0);

		// --version
		CliResult verRes = execSubprocess(script, shadedJar, "--version");
		assertThat(verRes.exitCode()).withFailMessage("--version failed: %s\n%s", verRes.out(), verRes.err())
				.isEqualTo(0);
		assertThat(verRes.out().trim()).matches("\\d+\\.\\d+\\.\\d+.*");

		// --help
		CliResult helpRes = execSubprocess(script, shadedJar, "--help");
		assertThat(helpRes.exitCode()).isEqualTo(0);
		assertThat(helpRes.out()).contains("inspect").contains("snapshot").contains("schema").contains("codegen");

		// inspect --format json
		CliResult inspRes = execSubprocess(script, shadedJar, "inspect", v1Imps.toString(), "--format", "json");
		assertThat(inspRes.exitCode()).withFailMessage("inspect failed: %s\n%s", inspRes.out(), inspRes.err())
				.isEqualTo(0);
		assertThat(inspRes.out().trim()).startsWith("{").endsWith("}").contains("\"magic\": \"0x494D5053\"");

		// snapshot validate
		CliResult valRes = execSubprocess(script, shadedJar, "snapshot", "validate", v1Imps.toString());
		assertThat(valRes.exitCode()).withFailMessage("validate failed: %s\n%s", valRes.out(), valRes.err())
				.isEqualTo(0);
		assertThat(valRes.out()).contains("Status : PASS");

		// assemble + disassemble
		Path asmIn = tmp.resolve("sub.impas");
		Files.writeString(asmIn, "OP_INIT_INPUT_NODE dst=R0\nOP_HALT\n");
		Path impbOut = tmp.resolve("sub.impb");
		CliResult asmRes = execSubprocess(script, shadedJar, "assemble", "-i", asmIn.toString(), "-o",
				impbOut.toString());
		assertThat(asmRes.exitCode()).withFailMessage("assemble failed: %s", asmRes.err()).isEqualTo(0);

		CliResult disRes = execSubprocess(script, shadedJar, "disassemble", "-i", impbOut.toString());
		assertThat(disRes.exitCode()).isEqualTo(0);
		assertThat(disRes.out()).contains("OP_INIT_INPUT_NODE").contains("OP_HALT");

		// Invalid input -> non-zero OS exit code
		Path corrupt = tmp.resolve("corrupt.imps");
		Files.write(corrupt, new byte[64]);
		CliResult badRes = execSubprocess(script, shadedJar, "snapshot", "validate", corrupt.toString());
		assertThat(badRes.exitCode()).isNotEqualTo(0);
	}

	@Test
	@DisplayName("6. Regression: BinarySnapshotLoader & impulse-j accept Rust _domain_index (index_type=4, attrIdx=0) on 0-attribute domain, while still rejecting non-MPHF out-of-bounds attrIdx")
	void domainKeyMphfIndexOnZeroAttributeDomainLoadsCleanly(@TempDir Path tmp) throws Exception {
		// Build a valid v0.10 snapshot with a 0-attribute domain via SnapshotBuilder
		Path baseImps = tmp.resolve("zero-attr-base.imps");
		org.impulsegraph.builder.api.SnapshotBuilder.create().withVersion((short) 10)
				.addDomain("User", org.impulsegraph.builder.api.DomainDefinition.builder(4L).build()).writeTo(baseImps);

		byte[] baseBytes = Files.readAllBytes(baseImps);
		// Locate Section 2.6 start in the v0.10 catalog directory
		java.nio.ByteBuffer hdr = java.nio.ByteBuffer.wrap(baseBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		long dataOff = Integer.toUnsignedLong(hdr.getInt(6));
		long footerDirOff = hdr.getLong(30);
		long dirOff = footerDirOff > 0 ? footerDirOff : dataOff;
		int strBytes = hdr.getInt((int) dirOff);
		long pos = 4L + strBytes;
		if (pos % 128 != 0) {
			pos += 128 - (pos % 128);
		}
		// 1 v0.10 domain entry (32 bytes, 0 attributes) aligned to 128, 0 relations
		pos += 32L;
		if (pos % 128 != 0) {
			pos += 128 - (pos % 128);
		}
		int idxStart = (int) (dirOff + pos);

		// Inject a 64-byte Section 2.6 entry: index_type = 4 (IMP_INDEX_MPHF /
		// _domain_index), domId = 0, relId = 0xFFFF, attrIdx = 0 on 0-attribute domain.
		// Insert 128 bytes at idxStart and bump footerDirBytes (offset 38) so trailing
		// footer metadata stays intact.
		byte[] mphfBytes = new byte[baseBytes.length + 128];
		System.arraycopy(baseBytes, 0, mphfBytes, 0, idxStart);
		System.arraycopy(baseBytes, idxStart, mphfBytes, idxStart + 128, baseBytes.length - idxStart);
		java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(mphfBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		if (footerDirOff > 0) {
			bb.putLong(38, bb.getLong(38) + 128L); // footerDirBytes += 128
		}
		bb.putShort(0x40, (short) 1); // index_count = 1
		bb.putInt(idxStart, 0); // index_id
		bb.putShort(idxStart + 4, (short) 0); // domId = 0
		bb.putShort(idxStart + 6, (short) 0xFFFF); // relId = 0xFFFF (domain index)
		bb.putShort(idxStart + 8, (short) 0); // attrIdx = 0 (on 0-attr domain)
		bb.put(idxStart + 10, (byte) 4); // index_type = 4 (IMP_INDEX_MPHF)
		bb.put(idxStart + 11, (byte) 0);
		bb.putInt(idxStart + 12, 0); // name_offset
		bb.putLong(idxStart + 16, 0L); // dataOff = 0
		bb.putLong(idxStart + 24, 0L); // dataBytes = 0
		bb.putLong(idxStart + 32, 0L); // featureMask

		Path mphfImps = tmp.resolve("rust-domain-index-mphf.imps");
		Files.write(mphfImps, mphfBytes);

		try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
			org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedSnapshot loaded = org.impulsegraph.storage.csr.BinarySnapshotLoader
					.loadSnapshot(mphfImps, arena, false);
			assertThat(loaded.domainCount()).isEqualTo(1);
			assertThat(loaded.getDomain("User").attributes()).isEmpty();
			assertThat(loaded.indices()).hasSize(1);
			assertThat(loaded.indices().get(0).indexType()).isEqualTo((byte) 4);
		}

		CliResult inspRes = exec("inspect", mphfImps.toString(), "--format", "json");
		assertThat(inspRes.exitCode()).withFailMessage("inspect failed: %s\n%s", inspRes.out(), inspRes.err())
				.isEqualTo(0);
		assertThat(inspRes.out()).contains("\"User\"");

		CliResult valRes = exec("snapshot", "validate", mphfImps.toString(), "--quick-check");
		assertThat(valRes.exitCode()).withFailMessage("validate failed: %s\n%s", valRes.out(), valRes.err())
				.isEqualTo(0);

		// Non-MPHF index_type = 1 with attrIdx = 0 on a 0-attribute domain MUST still
		// be rejected
		byte[] badBtreeBytes = mphfBytes.clone();
		badBtreeBytes[idxStart + 10] = (byte) 1;
		Path badBtreeImps = tmp.resolve("bad-btree-zero-attr.imps");
		Files.write(badBtreeImps, badBtreeBytes);

		CliResult badVal = exec("snapshot", "validate", badBtreeImps.toString(), "--quick-check");
		assertThat(badVal.exitCode()).isNotEqualTo(0);
		assertThat(badVal.out() + badVal.err()).contains("out of bounds for domain");
	}

	// =========================================================================
	// Dataset & Manifest Fixtures
	// =========================================================================

	private static Path writeV1Dataset(Path dir) throws IOException {
		Path domains = dir.resolve("domains");
		Path rels = dir.resolve("relations");
		Files.createDirectories(domains);
		Files.createDirectories(rels);

		// User domain: CSV with string key, float32 reputation, nullable email?,
		// float32[4] embedding
		Files.writeString(domains.resolve("users.csv"), """
				id,reputation,email,embedding
				u_alice,98.5,alice@example.com,"[1.0, 0.5, 0.25, 0.0]"
				u_bob,42.0,,"[0.0, 1.0, 0.5, 0.25]"
				u_carol,77.25,carol@example.com,"[0.25, 0.0, 1.0, 0.5]"
				""", StandardCharsets.UTF_8);

		// Post domain: JSONL with int64 key and varstring title
		Files.writeString(domains.resolve("posts.jsonl"), """
				{"id": 1001, "title": "Intro to Impulse"}
				{"id": 1002, "title": "Zero-Copy Graphs"}
				{"id": 1003, "title": "Blue-Green Epochs"}
				""", StandardCharsets.UTF_8);

		// userFollowsUser: TSV with [out, in] (direction: bidirectional) + float32
		// weight
		Files.writeString(rels.resolve("follows.tsv"), "src\ttgt\tweight\n" + "u_alice\tu_bob\t1.0\n"
				+ "u_alice\tu_carol\t0.75\n" + "u_bob\tu_carol\t0.5\n" + "u_carol\tu_alice\t0.9\n",
				StandardCharsets.UTF_8);

		// userPostsPost: CSV with [out]-only
		Files.writeString(rels.resolve("user_posts.csv"), """
				src,tgt
				u_alice,1001
				u_alice,1002
				u_carol,1003
				""", StandardCharsets.UTF_8);

		Path manifest = dir.resolve("manifest.yaml");
		Files.writeString(manifest, """
				schema_name: SocialApp
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

	private static Path writeV1_1AdditiveShiftedDataset(Path dir) throws IOException {
		Path domains = dir.resolve("domains");
		Path rels = dir.resolve("relations");
		Files.createDirectories(domains);
		Files.createDirectories(rels);

		// Additive + SHIFTED column indices: 'tier' inserted BEFORE reputation, email,
		// embedding!
		Files.writeString(domains.resolve("users.csv"), """
				id,tier,reputation,email,embedding
				u_alice,1,199.5,alice-v11@example.com,"[9.0, 8.0, 7.0, 6.0]"
				u_bob,2,55.0,,"[0.0, 1.0, 0.5, 0.25]"
				u_carol,1,88.0,carol-v11@example.com,"[0.25, 0.0, 1.0, 0.5]"
				u_dave,3,12.5,dave@example.com,"[1.0, 1.0, 1.0, 1.0]"
				""", StandardCharsets.UTF_8);

		Files.writeString(domains.resolve("posts.jsonl"), """
				{"id": 1001, "title": "Intro to Impulse v1.1"}
				{"id": 1002, "title": "Zero-Copy Graphs"}
				{"id": 1003, "title": "Blue-Green Epochs"}
				""", StandardCharsets.UTF_8);

		Files.writeString(domains.resolve("tags.csv"), "id,label\nt1,java\nt2,graphs\n", StandardCharsets.UTF_8);

		Files.writeString(
				rels.resolve("follows.tsv"), "src\ttgt\tweight\n" + "u_alice\tu_bob\t1.0\n" + "u_alice\tu_carol\t0.75\n"
						+ "u_bob\tu_carol\t0.5\n" + "u_carol\tu_alice\t0.9\n" + "u_dave\tu_alice\t0.4\n",
				StandardCharsets.UTF_8);

		Files.writeString(rels.resolve("user_posts.csv"), """
				src,tgt
				u_alice,1001
				u_alice,1002
				u_carol,1003
				""", StandardCharsets.UTF_8);

		Files.writeString(rels.resolve("post_tags.csv"), "src,tgt\n1001,t1\n1002,t2\n", StandardCharsets.UTF_8);

		Path manifest = dir.resolve("manifest.yaml");
		Files.writeString(manifest, """
				schema_name: SocialApp
				schema_version: "1.1.0"
				domains:
				  - id: 0
				    name: User
				    key_type: string
				    id_column: id
				    file: domains/users.csv
				    attributes:
				      - name: tier
				        type: int32
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
				  - id: 2
				    name: Tag
				    key_type: string
				    id_column: id
				    file: domains/tags.csv
				    attributes:
				      - name: label
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
				  - id: 2
				    name: postHasTag
				    from: Post
				    to: Tag
				    include_csc: false
				    file: relations/post_tags.csv
				""", StandardCharsets.UTF_8);
		return manifest;
	}

	private enum BreakingKind {
		MISSING_REQUIRED_ATTR, NULLABILITY_WIDENED, MISSING_CSC, CHANGED_TARGET_DOMAIN
	}

	private static Path buildBreakingSnapshot(Path tmp, String name, BreakingKind kind) throws IOException {
		Path dir = tmp.resolve(name + "_data");
		Path manifest = writeV1Dataset(dir);
		String yaml = Files.readString(manifest);
		switch (kind) {
			case MISSING_REQUIRED_ATTR -> yaml = yaml.replace("""
					      - name: reputation
					        type: float32
					""", "");
			case NULLABILITY_WIDENED -> yaml = yaml.replace("""
					      - name: reputation
					        type: float32
					""", """
					      - name: reputation
					        type: float32?
					""");
			case MISSING_CSC -> yaml = yaml.replace("direction: bidirectional", "include_csc: false");
			case CHANGED_TARGET_DOMAIN -> {
				Files.writeString(dir.resolve("relations/user_posts.csv"), "src,tgt\nu_alice,u_bob\n",
						StandardCharsets.UTF_8);
				yaml = yaml.replace("""
						  - id: 1
						    name: userPostsPost
						    from: User
						    to: Post
						""", """
						  - id: 1
						    name: userPostsPost
						    from: User
						    to: User
						""");
			}
		}
		Files.writeString(manifest, yaml, StandardCharsets.UTF_8);
		Path out = tmp.resolve(name + ".imps");
		CliResult res = exec("build", "-m", manifest.toString(), "-o", out.toString());
		assertThat(res.exitCode()).withFailMessage("building breaking snapshot %s failed: %s", name, res.err())
				.isEqualTo(0);
		return out;
	}

	// =========================================================================
	// In-memory javac & Subprocess helpers
	// =========================================================================

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

	private static Path findShadedJar(Path targetDir) throws IOException {
		if (!Files.isDirectory(targetDir)) {
			return null;
		}
		try (Stream<Path> list = Files.list(targetDir)) {
			return list.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith("impulse-cli-")
					&& p.getFileName().toString().endsWith("-all.jar")).findFirst().orElse(null);
		}
	}

	private static CliResult execSubprocess(Path script, Path shadedJar, String... args) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add("sh");
		cmd.add(script.toString());
		cmd.addAll(List.of(args));

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
		pb.environment().put("IMPULSE_J_JAR", shadedJar.toAbsolutePath().toString());
		Process proc = pb.start();
		String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
		boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
			throw new AssertionError("Subprocess timed out: " + cmd);
		}
		return new CliResult(proc.exitValue(), out, err);
	}
}
