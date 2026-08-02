package org.impulsegraph.core.csr;

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
        assertTrue(query.getOperationName().contains("QueryPipeline[USER->4Steps]"));
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
}
