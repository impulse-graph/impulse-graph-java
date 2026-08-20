package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.schema.GraphSchema;
import org.impulsegraph.api.schema.SchemaCodeGenerator;
import org.impulsegraph.api.schema.TypedQueryBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GeneratedQueryBuilderTest {

    @Test
    public void testSchemaCodeGeneratorSourceOutput() {
        GraphSchema.EntityDef user = new GraphSchema.EntityDef("User", Map.of("fuelSurcharge", "DOUBLE"));
        GraphSchema.EntityDef group = new GraphSchema.EntityDef("Group", Map.of());
        GraphSchema.RelationDef userToGroup = new GraphSchema.RelationDef("userToGroup", "User", "Group");

        GraphSchema schema = new GraphSchema(List.of(user, group), List.of(userToGroup));
        SchemaCodeGenerator generator = new SchemaCodeGenerator("org.impulsegraph.generated");

        Map<String, String> classes = generator.generateClasses(schema);

        assertTrue(classes.containsKey("UserQueryBuilder"));
        assertTrue(classes.containsKey("GroupQueryBuilder"));

        String userCode = classes.get("UserQueryBuilder");
        assertTrue(userCode.contains("public GroupQueryBuilder<R> walkUserToGroup()"));
        assertTrue(userCode.contains("public UserQueryBuilder<R> filterFuelSurcharge(String op, double val)"));
    }

    // --- Mock Generated Typed Builder representing compile-time generated class ---

    public static class MockUserQueryBuilder<R> extends TypedQueryBuilder<Object, R> {
        public MockUserQueryBuilder(ImpulseQueryBuilder<R> builder) {
            super(builder);
        }

        public static MockUserQueryBuilder<Object> from(Object input) {
            ImpulseQueryBuilder<Object> b = new ImpulseQueryBuilder<>();
            b.input("User", ArgType.SINGLE_NODE);
            return new MockUserQueryBuilder<>(b);
        }

        public MockGroupQueryBuilder<R> walkUserToGroup() {
            this.builder.walkEdge("userToGroup");
            return new MockGroupQueryBuilder<>(this.builder);
        }
    }

    public static class MockGroupQueryBuilder<R> extends TypedQueryBuilder<Object, R> {
        public MockGroupQueryBuilder(ImpulseQueryBuilder<R> builder) {
            super(builder);
        }

        public ImpulseGraphQuery<ImpulseBitSet> walkGroupToRole() {
            this.builder.walkEdge("groupToRole");
            return this.collectRoaringBitset();
        }
    }

    @Test
    public void testStronglyTypedGeneratedQueryExecution() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment u2gOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
            MemorySegment u2gTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 10);
            RelationSnapshot u2g = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, u2gOffsets, u2gTargets);

            MemorySegment g2rOffsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1);
            MemorySegment g2rTargets = arena.allocateFrom(ValueLayout.JAVA_INT, 5);
            RelationSnapshot g2r = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 12, 1, g2rOffsets, g2rTargets);

            ImpulseGraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", u2g, "groupToRole", g2r));

            // Execute strongly-typed generated builder query with IDE auto-complete methods!
            ImpulseGraphQuery<ImpulseBitSet> query = MockUserQueryBuilder.from(0)
                    .walkUserToGroup()
                    .walkGroupToRole();

            ImpulseBitSet roleIds = query.execute(graph, 0);
            assertNotNull(roleIds);
            assertTrue(roleIds.get(5), "Strongly-typed query MUST reach Role ID 5 via impulse-vm execution engine");

            String astTree = query.exportAst();
            assertTrue(astTree.contains("userToGroup"));
            assertTrue(astTree.contains("groupToRole"));
        }
    }
}
