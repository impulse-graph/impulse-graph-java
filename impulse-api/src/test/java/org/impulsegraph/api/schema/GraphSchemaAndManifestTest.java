package org.impulsegraph.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.util.*;
import java.util.function.Function;
import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.Reducer;
import org.impulsegraph.api.traversal.Traversal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GraphSchema, GraphManifest, CodeGen, and QueryBuilder Test Suite")
class GraphSchemaAndManifestTest {

	@Test
	@DisplayName("Test GraphSchema entities, relations, and lookup methods")
	void testGraphSchema() {
		// EntityDef validations
		assertThatThrownBy(() -> new GraphSchema.EntityDef(null, Map.of())).isInstanceOf(NullPointerException.class);
		GraphSchema.EntityDef eUser = new GraphSchema.EntityDef("User", Map.of("age", "int", "name", "string"));
		assertThat(eUser.name()).isEqualTo("User");
		assertThat(eUser.attributes()).containsEntry("age", "int");

		GraphSchema.EntityDef eSafe = new GraphSchema.EntityDef("Safe", null);
		assertThat(eSafe.attributes()).isEmpty();

		// RelationDef validations
		assertThatThrownBy(() -> new GraphSchema.RelationDef(null, "A", "B")).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GraphSchema.RelationDef("rel", null, "B"))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GraphSchema.RelationDef("rel", "A", null))
				.isInstanceOf(NullPointerException.class);
		GraphSchema.RelationDef r1 = new GraphSchema.RelationDef("FOLLOWS", "User", "User");
		assertThat(r1.name()).isEqualTo("FOLLOWS");
		assertThat(r1.sourceEntity()).isEqualTo("User");
		assertThat(r1.targetEntity()).isEqualTo("User");

		// GraphSchema
		GraphSchema.EntityDef eGroup = new GraphSchema.EntityDef("Group", Map.of());
		GraphSchema.RelationDef r2 = new GraphSchema.RelationDef("MEMBER_OF", "User", "Group");

		GraphSchema schema = new GraphSchema(List.of(eUser, eGroup), List.of(r1, r2));
		assertThat(schema.entities()).hasSize(2);
		assertThat(schema.relations()).hasSize(2);

		// Case-insensitive lookups
		assertThat(schema.getEntity("USER")).isEqualTo(eUser);
		assertThat(schema.getEntity("group")).isEqualTo(eGroup);
		assertThat(schema.getEntity("Unknown")).isNull();

		List<GraphSchema.RelationDef> outgoing = schema.getOutgoingRelations("user");
		assertThat(outgoing).containsExactly(r1, r2);
		assertThat(schema.getOutgoingRelations("group")).isEmpty();

		// Null collections default to empty
		GraphSchema emptySchema = new GraphSchema(null, null);
		assertThat(emptySchema.entities()).isEmpty();
		assertThat(emptySchema.relations()).isEmpty();
	}

	@Test
	@DisplayName("Test GraphManifest record hierarchy and properties")
	void testGraphManifest() {
		GraphManifest.TablespaceDef tb = new GraphManifest.TablespaceDef("users.bin", "User tablespace", "RO");
		assertThat(tb.file()).isEqualTo("users.bin");
		assertThat(tb.description()).isEqualTo("User tablespace");
		assertThat(tb.mode()).isEqualTo("RO");

		GraphManifest.DomainDef dd = new GraphManifest.DomainDef("ts1", Map.of("email", "string"));
		assertThat(dd.tablespace()).isEqualTo("ts1");
		assertThat(dd.attributes()).containsEntry("email", "string");

		GraphManifest.RelationDef rd = new GraphManifest.RelationDef("User", "Post", "ts1", Map.of("weight", "float"));
		assertThat(rd.source()).isEqualTo("User");
		assertThat(rd.target()).isEqualTo("Post");
		assertThat(rd.tablespace()).isEqualTo("ts1");
		assertThat(rd.attributes()).containsEntry("weight", "float");

		GraphManifest.VirtualRelationDef vrd = new GraphManifest.VirtualRelationDef(List.of("relA", "relB"));
		assertThat(vrd.components()).containsExactly("relA", "relB");

		GraphManifest manifest = new GraphManifest("social-graph", "0.9.0", Map.of("ts1", tb), Map.of("User", dd),
				Map.of("Authored", rd), Map.of("TwoHop", vrd));

		assertThat(manifest.graphName()).isEqualTo("social-graph");
		assertThat(manifest.version()).isEqualTo("0.9.0");
		assertThat(manifest.tablespaces()).hasSize(1);
		assertThat(manifest.domains()).hasSize(1);
		assertThat(manifest.relations()).hasSize(1);
		assertThat(manifest.virtualRelations()).hasSize(1);
	}

	@Test
	@DisplayName("Test SchemaCodeGenerator generating strongly-typed query builder classes")
	void testSchemaCodeGenerator() {
		assertThatThrownBy(() -> new SchemaCodeGenerator(null)).isInstanceOf(NullPointerException.class);

		SchemaCodeGenerator generator = new SchemaCodeGenerator("com.example.generated");
		assertThatThrownBy(() -> generator.generateClasses(null)).isInstanceOf(NullPointerException.class);

		Map<String, String> userAttrs = new LinkedHashMap<>();
		userAttrs.put("age", "int");
		userAttrs.put("score", "int32");
		userAttrs.put("balance", "long");
		userAttrs.put("delta", "int64");
		userAttrs.put("rating", "double");
		userAttrs.put("ratio", "float64");
		userAttrs.put("weight", "float");
		userAttrs.put("height", "float32");
		userAttrs.put("name", "string");

		GraphSchema.EntityDef eUser = new GraphSchema.EntityDef("user", userAttrs);
		GraphSchema.EntityDef ePost = new GraphSchema.EntityDef("post", Map.of("title", "string"));
		GraphSchema.RelationDef rAuthored = new GraphSchema.RelationDef("authored", "user", "post");

		GraphSchema schema = new GraphSchema(List.of(eUser, ePost), List.of(rAuthored));

		Map<String, String> generated = generator.generateClasses(schema);
		assertThat(generated).containsOnlyKeys("UserQueryBuilder", "PostQueryBuilder");

		String userSrc = generated.get("UserQueryBuilder");
		assertThat(userSrc).contains("package com.example.generated;")
				.contains("public class UserQueryBuilder<R> extends TypedQueryBuilder<Object, R>")
				.contains("public static UserQueryBuilder<Object> from(Object input)")
				.contains("public PostQueryBuilder<R> walkAuthored()")
				.contains("public UserQueryBuilder<R> filterAge(String op, int val)")
				.contains("public UserQueryBuilder<R> filterScore(String op, int val)")
				.contains("public UserQueryBuilder<R> filterBalance(String op, long val)")
				.contains("public UserQueryBuilder<R> filterDelta(String op, long val)")
				.contains("public UserQueryBuilder<R> filterRating(String op, double val)")
				.contains("public UserQueryBuilder<R> filterRatio(String op, double val)")
				.contains("public UserQueryBuilder<R> filterWeight(String op, float val)")
				.contains("public UserQueryBuilder<R> filterHeight(String op, float val)")
				.contains("public UserQueryBuilder<R> filterName(String op, Object val)");
	}

	@Test
	@DisplayName("Test TypedQueryBuilder delegation and query construction")
	void testTypedQueryBuilder() {
		assertThatThrownBy(() -> new TypedQueryBuilder<Object, Object>((ImpulseQueryBuilder<Object>) null))
				.isInstanceOf(NullPointerException.class);

		TypedQueryBuilder<Object, Object> builder = new TypedQueryBuilder<>("User", ArgType.SINGLE_NODE);
		assertThat(builder.getUnderlyingBuilder()).isNotNull();

		builder.filterWithCel("node.age > 21");
		builder.projectExpression("node.score", "+", "edge.weight");

		ImpulseGraphQuery<ImpulseBitSet> qBitset = builder.collectRoaringBitset();
		assertThat(qBitset).isNotNull();

		ImpulseGraphQuery<Long> qCount = builder.collectCount();
		assertThat(qCount).isNotNull();

		ImpulseGraphQuery<Double> qSum = builder.reduceSum();
		assertThat(qSum).isNotNull();

		ImpulseGraphQuery<Object> qFirst = builder.reduceFirst();
		assertThat(qFirst).isNotNull();

		String exported = builder.exportAst();
		assertThat(exported).contains("(program");
	}

	@Test
	@DisplayName("Test comprehensive ImpulseQueryBuilder pipeline methods and features")
	void testImpulseQueryBuilderComprehensive() {
		ImpulseQueryBuilder<Object> qb = new ImpulseQueryBuilder<>();

		qb.input("Account", ArgType.ROARING_BITSET);
		assertThat(qb.getEntityType()).isEqualTo("Account");
		assertThat(qb.getInputArgType()).isEqualTo(ArgType.ROARING_BITSET);

		qb.bindParameter("@minBal", 500.0);
		qb.bindParameters(Map.of("@maxBal", 10000.0, "@status", "ACTIVE"));
		assertThat(qb.getParameters()).containsEntry("@minBal", 500.0).containsEntry("@status", "ACTIVE");

		qb.walkEdge("transfers");
		qb.walkEdgeWithState("transfers", "state.fee = edge.fee");
		qb.projectState("state.net = state.bal - state.fee");
		qb.walkEdgeWithCel("transfers", "edge.amount > 100.0");
		qb.filterWithCel("node.active == true");
		qb.filter("node.verified == true");
		qb.walkEdgeFiltered("transfers", "DOMESTIC");
		qb.walkTarget("transfers");
		qb.walkReverse("transfers");
		qb.walkReverseWithCel("transfers", "edge.amount > 50.0");

		qb.repeat(sub -> sub.walkEdge("transfers"), 2);
		qb.repeat(3, sub -> sub.walkEdge("transfers"));
		qb.repeatUntilStable(sub -> sub.walkEdge("transfers"));

		qb.walkEdgeFilteredAttribute("transfers", "amount", ">", 10.0);
		qb.filterNodeAttribute("risk", "<=", 0.2);
		qb.projectExpression("risk", "*", "fee");

		// Extended operations
		qb.extended().islandDetect(0, 1);
		qb.extended().rebacCheck("canTransfer");
		qb.extended().motifMatch3();

		assertThat(qb.getSteps()).isNotEmpty();
		assertThat(ImpulseQueryBuilder.exportAst(null)).isEqualTo("()");

		// Reducers
		ImpulseGraphQuery<Double> qSum = qb.reduceSum();
		assertThat(qSum.getOperationName()).contains("QueryPipeline[Account->");
		assertThat(qSum.getAst()).isNotNull();
		assertThat(qSum.getParameters()).isNotEmpty();

		assertThat(qb.reduceArgMax()).isNotNull();
		assertThat(qb.reduceArgMin()).isNotNull();
		assertThat(qb.reduceMax()).isNotNull();
		assertThat(qb.reduceMin()).isNotNull();
		assertThat(qb.reduceAvg()).isNotNull();
		assertThat(qb.reduceFirst()).isNotNull();

		// Collectors
		assertThat(qb.collect(ReturnType.NODE_ARRAY)).isNotNull();
		assertThat(qb.collect(ReturnType.COUNT)).isNotNull();
		assertThat(qb.collectBitSet()).isNotNull();
		assertThat(qb.collectArray()).isNotNull();
		assertThat(qb.collectCount()).isNotNull();
		assertThat(qb.collectRoaringBitset()).isNotNull();
	}

	@Test
	@DisplayName("Test DomainView default traversal initiation methods")
	void testDomainViewDefaultMethods() {
		TestDomainView view = new TestDomainView("User", 0, 100L);

		// first(n)
		assertThat(view.first(0)).isNotNull();
		assertThat(view.lastSeedIds).isEmpty();

		assertThat(view.first(-5)).isNotNull();
		assertThat(view.lastSeedIds).isEmpty();

		view.first(5);
		assertThat(view.lastSeedIds).containsExactly(0L, 1L, 2L, 3L, 4L);

		view.first(200);
		assertThat(view.lastSeedIds).hasSize(100);

		// fromRandom(n)
		assertThat(view.fromRandom(0)).isNotNull();
		assertThat(view.lastSeedIds).isEmpty();

		assertThat(view.fromRandom(200)).isNotNull();
		assertThat(view.allCalled).isTrue();

		// Dense random sampling (n > max / 4 = 25)
		view.fromRandom(30);
		assertThat(view.lastSeedIds).hasSize(30);

		// Sparse random sampling (n <= max / 4 = 25)
		view.fromRandom(10);
		assertThat(view.lastSeedIds).hasSize(10);

		// fromKey and fromKeys
		view.fromKey("user_42");
		assertThat(view.lastSeedIds).containsExactly(42L);

		assertThatThrownBy(() -> view.fromKey("unknown")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Key not found");

		view.fromKeys("user_10", "user_20");
		assertThat(view.lastSeedIds).containsExactly(10L, 20L);

		assertThatThrownBy(() -> view.fromKeys("user_10", "unknown")).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Key not found");
	}

	@Test
	@DisplayName("Test Traversal default forwarding methods")
	void testTraversalDefaultMethods() {
		TestTraversal<ImpulseBitSet> trav = new TestTraversal<>();

		trav.out("friends", "edge.weight > 0.5");
		assertThat(trav.lastOutRel).isEqualTo("friends");
		assertThat(trav.lastFilter).isEqualTo("edge.weight > 0.5");

		trav.outWithState("friends", "edge.weight > 0.5", "state.fee = 1");
		assertThat(trav.lastOutStateRel).isEqualTo("friends");
		assertThat(trav.lastStateProj).isEqualTo("state.fee = 1");

		trav.in("friends", "edge.weight > 0.5");
		assertThat(trav.lastInRel).isEqualTo("friends");

		trav.withParam("longArray", new long[]{10L, 20L});
		assertThat(trav.lastParamKey).isEqualTo("longArray");

		trav.withParam("intArray", new int[]{1, 2});
		assertThat(trav.lastParamKey).isEqualTo("intArray");

		trav.withParam("strList", List.of("a", "b"));
		assertThat(trav.lastParamKey).isEqualTo("strList");
	}

	@Test
	@DisplayName("Test ReturnType, ArgType, and Reducer enums")
	void testEnums() {
		assertThat(ReturnType.values()).containsExactly(ReturnType.ROARING_BITSET, ReturnType.DENSE_BITSET,
				ReturnType.NODE_ARRAY, ReturnType.COUNT, ReturnType.EXISTS, ReturnType.SINGLE_NODE);
		assertThat(ReturnType.valueOf("COUNT")).isEqualTo(ReturnType.COUNT);

		assertThat(ArgType.values()).containsExactly(ArgType.SINGLE_NODE, ArgType.ROARING_BITSET, ArgType.DENSE_BITSET,
				ArgType.NODE_ARRAY);
		assertThat(ArgType.valueOf("SINGLE_NODE")).isEqualTo(ArgType.SINGLE_NODE);

		assertThat(Reducer.values()).contains(Reducer.OR, Reducer.AND, Reducer.MIN, Reducer.MAX, Reducer.SUM,
				Reducer.COUNT, Reducer.AVG, Reducer.ANY, Reducer.ARGMIN, Reducer.ARGMAX);
		assertThat(Reducer.valueOf("OR")).isEqualTo(Reducer.OR);
	}

	private static class TestDomainView implements DomainView {
		private final String domainName;
		private final int domainId;
		private final long nodeCount;
		long[] lastSeedIds;
		boolean allCalled = false;

		TestDomainView(String domainName, int domainId, long nodeCount) {
			this.domainName = domainName;
			this.domainId = domainId;
			this.nodeCount = nodeCount;
		}

		@Override
		public String domainName() {
			return domainName;
		}
		@Override
		public int domainId() {
			return domainId;
		}
		@Override
		public long nodeCount() {
			return nodeCount;
		}

		@Override
		public Traversal<ImpulseBitSet> all() {
			this.allCalled = true;
			return new TestTraversal<>();
		}

		@Override
		public Traversal<ImpulseBitSet> from(long nodeId) {
			return from(new long[]{nodeId});
		}

		@Override
		public Traversal<ImpulseBitSet> from(long... nodeIds) {
			this.lastSeedIds = nodeIds;
			return new TestTraversal<>();
		}

		@Override
		public Traversal<ImpulseBitSet> from(ImpulseBitSet bitset) {
			return new TestTraversal<>();
		}

		@Override
		public long toDenseId(String key) {
			if (key != null && key.startsWith("user_")) {
				return Long.parseLong(key.substring(5));
			}
			return -1;
		}

		@Override
		public String toKey(long denseId) {
			return "user_" + denseId;
		}
	}

	private static class TestTraversal<T> implements Traversal<T> {
		String lastFilter;
		String lastOutRel;
		String lastOutStateRel;
		String lastStateProj;
		String lastInRel;
		String lastParamKey;

		@Override
		public Traversal<T> filter(String celPredicate) {
			this.lastFilter = celPredicate;
			return this;
		}
		@Override
		public Traversal<T> project(String projectionExpr) {
			return this;
		}
		@Override
		public Traversal<T> out(String relation) {
			this.lastOutRel = relation;
			return this;
		}
		@Override
		public Traversal<T> out(String relation, Reducer reducer) {
			this.lastOutRel = relation;
			return this;
		}
		@Override
		public Traversal<T> outWithState(String relation, String stateProjections) {
			this.lastOutStateRel = relation;
			this.lastStateProj = stateProjections;
			return this;
		}
		@Override
		public Traversal<T> in(String relation) {
			this.lastInRel = relation;
			return this;
		}
		@Override
		public Traversal<T> in(String relation, Reducer reducer) {
			this.lastInRel = relation;
			return this;
		}
		@Override
		public Traversal<T> withParam(String key, Object value) {
			this.lastParamKey = key;
			return this;
		}
		@Override
		public Traversal<T> repeatUntilStable(Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
			return this;
		}
		@Override
		public Traversal<T> repeat(int iterations, Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
			return this;
		}
		@Override
		public T collect() {
			return null;
		}
		@Override
		public long count() {
			return 0;
		}
		@Override
		public List<Long> toList() {
			return List.of();
		}
		@Override
		public Set<Long> toSet() {
			return Set.of();
		}
		@Override
		public List<String> toKeyList() {
			return List.of();
		}
		@Override
		public Set<String> toKeySet() {
			return Set.of();
		}
		@Override
		public ImpulseBitSet toBitSet() {
			return null;
		}
		@Override
		public String toImpAsm() {
			return "";
		}
	}
}
