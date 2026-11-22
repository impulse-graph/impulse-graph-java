package org.impulsegraph.codegen;

import org.impulsegraph.api.lifecycle.CandidateSnapshotRef;
import org.impulsegraph.api.lifecycle.SnapshotResolver;
import org.impulsegraph.builder.api.DataType;
import org.impulsegraph.builder.api.Nullability;
import org.impulsegraph.builder.spi.AttributeChunkIterator;
import org.impulsegraph.builder.spi.AttributeDataSource;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;
import org.impulsegraph.vm.lifecycle.SnapshotLifecycleManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("impulse-codegen v2 End-to-End Compile, Run, Reverse-Codegen, and Negative Type-Safety Tests")
class CodegenEndToEndCompileAndRunTest {

	private static final String RICH_SCHEMA_YAML = """
			graphName: RichSocial
			version: "1.1.0"
			package: org.impulsegraph.testgen.rich
			domains:
			  User:
			    idWidth: uint16
			    keyType: uuid
			    attributes:
			      user_key: { type: fixed_bytes, dimension: 16, nullable: false }
			      username: { type: fixed_string, dimension: 16, nullable: false }
			      reputation: { type: int32, nullable: false }
			      bio: { type: var_string, nullable: true }
			      embedding: { type: "float32[4]", nullable: false }
			      badge: { type: int32, optional: true, since: "1.1.0" }
			      legacy_tag: { type: int32, optional: true, deprecated: "Use badge instead" }
			  Post:
			    idWidth: uint32
			    keyType: int64
			    attributes:
			      post_id: { type: int64, nullable: false }
			      title: { type: var_string, nullable: false }
			  Tag:
			    idWidth: uint16
			    keyType: string
			    attributes:
			      label: { type: var_string, nullable: false }
			relations:
			  userFollowsUser:
			    source: User
			    target: User
			    directions: [out, in]
			    inverseAlias: followedBy
			    cardinality: many_to_many
			  userPostsPost:
			    source: User
			    target: Post
			    directions: [out]
			    cardinality: one_to_many
			  userLikesPost:
			    source: User
			    target: Post
			    directions: [out]
			    deprecated: "Use userInteractsPost"
			    attributes:
			      weight: { type: float32, nullable: false }
			virtual_relations:
			  userInteractsPost:
			    deprecated: "Legacy union"
			    components:
			      - userPostsPost
			      - userLikesPost
			""";

	@Test
	@DisplayName("Generated sources for social-graph.yaml and minimal-graph.yaml compile cleanly with javac")
	void testResourceSchemasCompileCleanly(@TempDir Path tempDir) throws IOException {
		Path socialSrc = tempDir.resolve("social-src");
		Path socialClasses = tempDir.resolve("social-classes");
		GeneratorMain.generate(Paths.get("src/test/resources/schemas/social-graph.yaml"), socialSrc, null);
		CompilationOutcome socialResult = compileDirectory(socialSrc, socialClasses);
		assertThat(socialResult.success()).as("social-graph.yaml compilation errors:\n%s", socialResult.diagnostics())
				.isTrue();

		Path minimalSrc = tempDir.resolve("minimal-src");
		Path minimalClasses = tempDir.resolve("minimal-classes");
		GeneratorMain.generate(Paths.get("src/test/resources/schemas/minimal-graph.yaml"), minimalSrc, null);
		CompilationOutcome minimalResult = compileDirectory(minimalSrc, minimalClasses);
		assertThat(minimalResult.success())
				.as("minimal-graph.yaml compilation errors:\n%s", minimalResult.diagnostics()).isTrue();
	}

	@Test
	@DisplayName("Full E2E: generate -> javac -> write .imps -> mount -> traverse/CEL/RowView/Lifecycle -> reverse codegen -> negative javac tests")
	void testEndToEndCompileAndRun(@TempDir Path tempDir) throws Exception {
		Path schemaPath = tempDir.resolve("rich-social.yaml");
		Files.writeString(schemaPath, RICH_SCHEMA_YAML, StandardCharsets.UTF_8);

		Path genSrcDir = tempDir.resolve("gen-src");
		Path genClassesDir = tempDir.resolve("gen-classes");
		GeneratorMain.generate(schemaPath, genSrcDir, null);

		// 1. Compile all generated sources in-memory via
		// ToolProvider.getSystemJavaCompiler()
		CompilationOutcome compiled = compileDirectory(genSrcDir, genClassesDir);
		assertThat(compiled.success()).as("Generated RichSocial compilation errors:\n%s", compiled.diagnostics())
				.isTrue();

		// Verify @Deprecated emitted on deprecated attribute, relation, and virtual
		// relation
		Path pkgSrc = genSrcDir.resolve("org/impulsegraph/testgen/rich");
		assertThat(Files.readString(pkgSrc.resolve("UserRowView.java"))).contains("@Deprecated");
		assertThat(Files.readString(pkgSrc.resolve("UserTraversal.java"))).contains("@Deprecated")
				.contains("outUserInteractsPost()");

		Path impsPath = tempDir.resolve("rich-social.imps");
		UUID u0Key = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID u1Key = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID u2Key = UUID.fromString("00000000-0000-0000-0000-000000000003");

		try (URLClassLoader loader = new URLClassLoader(new URL[]{genClassesDir.toUri().toURL()},
				getClass().getClassLoader())) {
			Class<?> writerCls = loader.loadClass("org.impulsegraph.testgen.rich.RichSocialSnapshotWriter");
			Class<?> snapCls = loader.loadClass("org.impulsegraph.testgen.rich.RichSocialSnapshot");
			Class<?> userIdCls = loader.loadClass("org.impulsegraph.testgen.rich.UserId");
			Class<?> userAttrsCls = loader.loadClass("org.impulsegraph.testgen.rich.UserAttrs");
			Class<?> userPredCls = loader.loadClass("org.impulsegraph.testgen.rich.UserPredicate");

			// 2. Write a real .imps snapshot via generated RichSocialSnapshotWriter
			Object writer = writerCls.getMethod("create").invoke(null);

			Map<String, AttributeDataSource> userAttrs = new LinkedHashMap<>();
			userAttrs.put("user_key", uuidAttr(u0Key, u1Key, u2Key));
			userAttrs.put("username", fixedStringAttr(16, "alice", "bob", "charlie"));
			userAttrs.put("reputation", int32Attr(100, 40, 85));
			userAttrs.put("bio", nullableVarStringAttr("Alice bio", null, "Charlie bio"));
			userAttrs.put("embedding", float32VectorAttr(4, new float[]{1f, 0f, 0f, 0f}, new float[]{0f, 1f, 0f, 0f},
					new float[]{0f, 0f, 1f, 0f}));
			// Note: optional 'badge' and 'legacy_tag' intentionally omitted from snapshot!

			Map<String, AttributeDataSource> postAttrs = new LinkedHashMap<>();
			postAttrs.put("post_id", int64Attr(1001L, 1002L, 1003L));
			postAttrs.put("title", varStringAttr("Hello World", "Graph Analytics", "Impulse VM"));

			Map<String, AttributeDataSource> tagAttrs = new LinkedHashMap<>();
			tagAttrs.put("label", varStringAttr("java", "graphs"));

			writerCls.getMethod("domainUser", long.class, String.class, Map.class).invoke(writer, 3L, "user_key",
					userAttrs);
			writerCls.getMethod("domainPost", long.class, String.class, Map.class).invoke(writer, 3L, "post_id",
					postAttrs);
			writerCls.getMethod("domainTag", long.class, String.class, Map.class).invoke(writer, 2L, "label", tagAttrs);

			// userFollowsUser (uint16 -> uint16, CSR + CSC): 0->1, 0->2, 1->2
			writerCls.getMethod("relationUserFollowsUser", RelationDataSource.class).invoke(writer,
					edges(2, 2, new int[][]{{0, 1}, {0, 2}, {1, 2}}));
			// userPostsPost (uint16 -> uint32, CSR): 0->0, 0->1, 2->2
			writerCls.getMethod("relationUserPostsPost", RelationDataSource.class).invoke(writer,
					edges(2, 4, new int[][]{{0, 0}, {0, 1}, {2, 2}}));
			// userLikesPost (uint16 -> uint32, CSR, edge attr weight): 0->2, 1->0
			Map<String, AttributeDataSource> likeAttrs = Map.of("weight", float32Attr(0.9f, 0.4f));
			writerCls.getMethod("relationUserLikesPost", RelationDataSource.class, Map.class).invoke(writer,
					edges(2, 4, new int[][]{{0, 2}, {1, 0}}), likeAttrs);

			writerCls.getMethod("writeTo", Path.class).invoke(writer, impsPath);
			assertThat(impsPath).exists();

			// 3. Mount via RichSocialSnapshot.open(path, arena)
			Arena arena = Arena.ofConfined();
			Object snap = snapCls.getMethod("open", Path.class, Arena.class).invoke(null, impsPath, arena);
			try {
				Object usersView = snapCls.getMethod("users").invoke(snap);
				assertThat(usersView.getClass().getMethod("nodeCount").invoke(usersView)).isEqualTo(3L);
				assertThat(usersView.getClass().getMethod("hasBadge").invoke(usersView)).isEqualTo(false);
				assertThat(usersView.getClass().getMethod("toDenseId", UUID.class).invoke(usersView, u0Key))
						.isEqualTo(0L);
				assertThat(usersView.getClass().getMethod("toKey", long.class).invoke(usersView, 1L)).isEqualTo(u1Key);

				// Kleisli traversal: users().from(UserId(0)).outUserPostsPost() -> Posts {0, 1}
				Object userId0 = userIdCls.getConstructor(long.class).newInstance(0L);
				Object u0Trav = usersView.getClass().getMethod("from", userIdCls).invoke(usersView, userId0);
				Object postsTrav = u0Trav.getClass().getMethod("outUserPostsPost").invoke(u0Trav);
				assertThat(postsTrav.getClass().getMethod("count").invoke(postsTrav)).isEqualTo(2L);
				long[] postDenseIds = (long[]) postsTrav.getClass().getMethod("toDenseIds").invoke(postsTrav);
				assertThat(postDenseIds).containsExactly(0L, 1L);

				List<?> postRecords = (List<?>) postsTrav.getClass().getMethod("toRecords").invoke(postsTrav);
				assertThat(postRecords).hasSize(2);
				assertThat(postRecords.get(0).getClass().getMethod("title").invoke(postRecords.get(0)))
						.isEqualTo("Hello World");
				assertThat(postRecords.get(1).getClass().getMethod("title").invoke(postRecords.get(1)))
						.isEqualTo("Graph Analytics");

				// Reverse traversal: users().from(2L).followedBy() -> Users {0, 1}
				Object u2Trav = usersView.getClass().getMethod("from", long.class).invoke(usersView, 2L);
				Object followers = u2Trav.getClass().getMethod("followedBy").invoke(u2Trav);
				assertThat((long[]) followers.getClass().getMethod("toDenseIds").invoke(followers)).containsExactly(0L,
						1L);

				// Virtual relation: users().from(0L).outUserInteractsPost() -> Posts {0, 1, 2}
				Object u0Fresh = usersView.getClass().getMethod("from", long.class).invoke(usersView, 0L);
				Object u0Interacts = u0Fresh.getClass().getMethod("outUserInteractsPost").invoke(u0Fresh);
				assertThat((long[]) u0Interacts.getClass().getMethod("toDenseIds").invoke(u0Interacts))
						.containsExactly(0L, 1L, 2L);

				// Engine-native CEL where(...) filtering: reputation >= 50 -> Users {0, 2}
				Object repAttr = userAttrsCls.getField("REPUTATION").get(null);
				Object ge50Pred = repAttr.getClass().getMethod("ge", long.class).invoke(repAttr, 50L);
				Object allUsersForFilter = usersView.getClass().getMethod("all").invoke(usersView);
				Object highRepUsers = allUsersForFilter.getClass().getMethod("where", userPredCls)
						.invoke(allUsersForFilter, ge50Pred);
				assertThat((long[]) highRepUsers.getClass().getMethod("toDenseIds").invoke(highRepUsers))
						.containsExactly(0L, 2L);

				// Zero-allocation flyweight forEachRow verification (same RowView instance
				// reused!)
				Set<Object> seenInstances = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
				List<String> usernames = new ArrayList<>();
				float[] reusableEmb = new float[4];
				Consumer<Object> rowConsumer = row -> {
					try {
						seenInstances.add(row);
						usernames.add((String) row.getClass().getMethod("username").invoke(row));
						assertThat(row.getClass().getMethod("hasBadge").invoke(row)).isEqualTo(false);
						assertThat(row.getClass().getMethod("badgeOrDefault", int.class).invoke(row, 42)).isEqualTo(42);
						// Unchecked optional getter must fail fast with IllegalStateException
						assertThatThrownBy(() -> {
							try {
								row.getClass().getMethod("badge").invoke(row);
							} catch (InvocationTargetException ite) {
								throw ite.getCause();
							}
						}).isInstanceOf(IllegalStateException.class);
						// Zero-alloc vector copy into reusable buffer
						row.getClass().getMethod("embedding", float[].class).invoke(row, (Object) reusableEmb);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				};
				highRepUsers.getClass().getMethod("forEachRow", Consumer.class).invoke(highRepUsers, rowConsumer);
				assertThat(seenInstances).hasSize(1); // single flyweight reused across all rows!
				assertThat(usernames).containsExactly("alice", "charlie");
				assertThat(reusableEmb).containsExactly(0f, 0f, 1f, 0f);

				// Verify nullable bio on User 1 (null) vs User 0 ("Alice bio")
				Object freshAllUsers = usersView.getClass().getMethod("all").invoke(usersView);
				List<?> allRecords = (List<?>) freshAllUsers.getClass().getMethod("toRecords").invoke(freshAllUsers);
				assertThat(allRecords).hasSize(3);
				assertThat(allRecords.get(0).getClass().getMethod("bio").invoke(allRecords.get(0)))
						.isEqualTo("Alice bio");
				assertThat(allRecords.get(1).getClass().getMethod("bio").invoke(allRecords.get(1))).isNull();
				assertThat(allRecords.get(2).getClass().getMethod("bio").invoke(allRecords.get(2)))
						.isEqualTo("Charlie bio");
			} finally {
				snapCls.getMethod("close").invoke(snap);
			}

			// 4. Verify SnapshotLifecycleManager creation & epoch execution
			SnapshotResolver resolver = ctx -> Optional.of(new CandidateSnapshotRef("rev-1", impsPath, false));
			try (@SuppressWarnings("unchecked")
			SnapshotLifecycleManager<Object> mgr = (SnapshotLifecycleManager<Object>) snapCls
					.getMethod("createLifecycleManager", SnapshotResolver.class).invoke(null, resolver)) {
				assertThat(mgr.activeRevision()).contains("rev-1");
				long count = mgr.withSnapshot(s -> {
					try {
						Object uv = snapCls.getMethod("users").invoke(s);
						return (Long) uv.getClass().getMethod("nodeCount").invoke(uv);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
				assertThat(count).isEqualTo(3L);
			}
		}

		// 5. Reverse codegen directly from .imps binary -> compiles cleanly!
		Path reverseSrc = tempDir.resolve("reverse-src");
		Path reverseClasses = tempDir.resolve("reverse-classes");
		GeneratorMain.generate(impsPath, reverseSrc, "org.impulsegraph.testgen.reverse");
		CompilationOutcome reverseCompiled = compileDirectory(reverseSrc, reverseClasses);
		assertThat(reverseCompiled.success())
				.as("Reverse codegen from .imps compilation errors:\n%s", reverseCompiled.diagnostics()).isTrue();

		// 6. Negative Compile-Time Tests
		// (a) Passing PostId to UserDomainView.from(...) MUST fail javac
		Path negDir1 = tempDir.resolve("neg-domain-id");
		Files.createDirectories(negDir1);
		Path badDomainIdFile = negDir1.resolve("BadDomainIdUsage.java");
		Files.writeString(badDomainIdFile, """
				package org.impulsegraph.testgen.neg;
				import org.impulsegraph.testgen.rich.PostId;
				import org.impulsegraph.testgen.rich.RichSocialSnapshot;
				public class BadDomainIdUsage {
					void fail(RichSocialSnapshot snap) {
						snap.users().from(new PostId(0L));
					}
				}
				""");
		CompilationOutcome neg1 = compileFiles(List.of(badDomainIdFile), tempDir.resolve("neg-out1"), genClassesDir);
		assertThat(neg1.success()).as("Expected javac to reject PostId passed to UserDomainView.from(...)").isFalse();

		// (b) Calling inUserPostsPost() on [out]-only relation MUST fail javac
		Path negDir2 = tempDir.resolve("neg-direction");
		Files.createDirectories(negDir2);
		Path badDirFile = negDir2.resolve("BadDirectionUsage.java");
		Files.writeString(badDirFile, """
				package org.impulsegraph.testgen.neg;
				import org.impulsegraph.testgen.rich.RichSocialSnapshot;
				public class BadDirectionUsage {
					void fail(RichSocialSnapshot snap) {
						snap.posts().all().inUserPostsPost();
					}
				}
				""");
		CompilationOutcome neg2 = compileFiles(List.of(badDirFile), tempDir.resolve("neg-out2"), genClassesDir);
		assertThat(neg2.success()).as("Expected javac to reject inUserPostsPost() on [out]-only relation").isFalse();
	}

	// =========================================================================
	// In-memory javac helpers
	// =========================================================================

	private record CompilationOutcome(boolean success, String diagnostics) {
	}

	private static CompilationOutcome compileDirectory(Path srcDir, Path outDir) throws IOException {
		List<Path> files;
		try (Stream<Path> walk = Files.walk(srcDir)) {
			files = walk.filter(p -> p.toString().endsWith(".java")).toList();
		}
		assertThat(files).isNotEmpty();
		return compileFiles(files, outDir, null);
	}

	private static CompilationOutcome compileFiles(List<Path> sourceFiles, Path outDir, Path extraClasspath)
			throws IOException {
		Files.createDirectories(outDir);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("JDK ToolProvider.getSystemJavaCompiler() must be available").isNotNull();

		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
			Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sourceFiles);
			String cp = System.getProperty("java.class.path");
			if (extraClasspath != null) {
				cp = extraClasspath.toAbsolutePath() + System.getProperty("path.separator") + cp;
			}
			List<String> options = List.of("-d", outDir.toAbsolutePath().toString(), "-cp", cp, "--add-modules",
					"jdk.incubator.vector");
			boolean ok = Boolean.TRUE.equals(compiler.getTask(null, fm, diagnostics, options, null, units).call());
			StringBuilder sb = new StringBuilder();
			for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
				sb.append(d.getKind()).append(": ").append(d.getSource() != null ? d.getSource().getName() : "")
						.append(':').append(d.getLineNumber()).append(' ').append(d.getMessage(null)).append('\n');
			}
			return new CompilationOutcome(ok, sb.toString());
		}
	}

	// =========================================================================
	// Test DataSource helpers for SnapshotBuilder
	// =========================================================================

	private static AttributeDataSource int32Attr(int... values) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.I32;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < values.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, values.length - pos);
						ValueLayout.OfInt le = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						for (int i = 0; i < n; i++) {
							dest.set(le, i * 4L, values[pos++]);
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource int64Attr(long... values) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.I64;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < values.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, values.length - pos);
						ValueLayout.OfLong le = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						for (int i = 0; i < n; i++) {
							dest.set(le, i * 8L, values[pos++]);
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource float32Attr(float... values) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.F32;
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < values.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, values.length - pos);
						ValueLayout.OfFloat le = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						for (int i = 0; i < n; i++) {
							dest.set(le, i * 4L, values[pos++]);
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource float32VectorAttr(int dim, float[]... vectors) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.vector(DataType.F32, dim);
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < vectors.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, vectors.length - pos);
						ValueLayout.OfFloat le = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						for (int i = 0; i < n; i++) {
							float[] v = vectors[pos++];
							for (int d = 0; d < dim; d++) {
								dest.set(le, ((long) i * dim + d) * 4L, v[d]);
							}
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource uuidAttr(UUID... uuids) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.fixedBytes(16);
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < uuids.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, uuids.length - pos);
						ValueLayout.OfLong be = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
						for (int i = 0; i < n; i++) {
							UUID u = uuids[pos++];
							dest.set(be, i * 16L, u.getMostSignificantBits());
							dest.set(be, i * 16L + 8L, u.getLeastSignificantBits());
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource fixedStringAttr(int width, String... values) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.fixedBytes(width);
			}
			@Override
			public Nullability nullability() {
				return Nullability.NON_NULL;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < values.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						int n = Math.min(limit, values.length - pos);
						for (int i = 0; i < n; i++) {
							byte[] raw = values[pos++].getBytes(StandardCharsets.UTF_8);
							byte[] padded = Arrays.copyOf(raw, width);
							MemorySegment.copy(padded, 0, dest, ValueLayout.JAVA_BYTE, (long) i * width, width);
						}
						return n;
					}
				};
			}
		};
	}

	private static AttributeDataSource varStringAttr(String... values) {
		return varStringAttr(Nullability.NON_NULL, values);
	}

	private static AttributeDataSource nullableVarStringAttr(String... values) {
		return varStringAttr(Nullability.NULLABLE, values);
	}

	private static AttributeDataSource varStringAttr(Nullability nullability, String... values) {
		return new AttributeDataSource() {
			@Override
			public DataType dataType() {
				return DataType.STRING;
			}
			@Override
			public Nullability nullability() {
				return nullability;
			}
			@Override
			public AttributeChunkIterator iterator() {
				return new AttributeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < values.length;
					}
					@Override
					public int nextChunk(MemorySegment dest, int limit) {
						throw new UnsupportedOperationException();
					}
					@Override
					public int nextVariableChunk(List<byte[]> dest, int limit) {
						int n = Math.min(limit, values.length - pos);
						for (int i = 0; i < n; i++) {
							String s = values[pos++];
							dest.add(s == null ? null : s.getBytes(StandardCharsets.UTF_8));
						}
						return n;
					}
				};
			}
		};
	}

	private static RelationDataSource edges(int srcBytes, int tgtBytes, int[][] pairs) {
		return new RelationDataSource() {
			@Override
			public long getEdgeCount() {
				return pairs.length;
			}
			@Override
			public EdgeChunkIterator getEdges() {
				return new EdgeChunkIterator() {
					int pos = 0;
					@Override
					public boolean hasNext() {
						return pos < pairs.length;
					}
					@Override
					public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
						int n = Math.min(limit, pairs.length - pos);
						ValueLayout.OfShort le16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						ValueLayout.OfInt le32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						ValueLayout.OfLong le64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
						for (int i = 0; i < n; i++) {
							int[] e = pairs[pos++];
							writeId(srcIds, i, e[0], srcBytes, le16, le32, le64);
							writeId(tgtIds, i, e[1], tgtBytes, le16, le32, le64);
						}
						return n;
					}
				};
			}
		};
	}

	private static void writeId(MemorySegment seg, int idx, int val, int widthBytes, ValueLayout.OfShort le16,
			ValueLayout.OfInt le32, ValueLayout.OfLong le64) {
		switch (widthBytes) {
			case 2 -> seg.set(le16, idx * 2L, (short) val);
			case 4 -> seg.set(le32, idx * 4L, val);
			case 8 -> seg.set(le64, idx * 8L, val);
			default -> throw new IllegalArgumentException("Unsupported width: " + widthBytes);
		}
	}
}
