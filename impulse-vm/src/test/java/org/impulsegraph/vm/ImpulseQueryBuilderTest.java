package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ReturnType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImpulseQueryBuilderTest {

    @Test
    @DisplayName("Build fluent AST query for user to group to role traversal")
    void testBuildUserToRoleQuery() {
        ImpulseGraphQuery<Object> query = ImpulseGraphQuery.builder()
                .input("USER", ArgType.ROARING_BITSET)
                .walkEdge("userToGroup")
                .walkEdge("groupToRole")
                .collect(ReturnType.ROARING_BITSET);

        assertNotNull(query);
        assertTrue(query.getOperationName().contains("USER"));
    }

    @Test
    @DisplayName("Build repeatUntilStable transitive closure query")
    void testBuildTransitiveClosureQuery() {
        ImpulseGraphQuery<Object> query = ImpulseGraphQuery.builder()
                .input("ORG", ArgType.SINGLE_NODE)
                .repeatUntilStable(step -> step.walkEdge("orgToParentOrg"))
                .collect(ReturnType.ROARING_BITSET);

        assertNotNull(query);
        assertTrue(query.getOperationName().contains("ORG"));
    }

    @Test
    @DisplayName("Build baseball batting average query using outWithState and projectState")
    void testBuildBattingAverageQuery() {
        ImpulseGraphQuery<Object> query = ImpulseGraphQuery.builder()
                .input("Player", ArgType.SINGLE_NODE)
                .walkEdgeWithState("PLAYED_IN", "state.total_hits:SUM = edge.hits, state.total_at_bats:SUM = edge.at_bats")
                .projectState("state.batting_avg = state.total_hits / math.max(1.0, state.total_at_bats)")
                .collect(ReturnType.NODE_ARRAY);

        assertNotNull(query);
        assertTrue(query.getOperationName().contains("Player"));
    }
}
