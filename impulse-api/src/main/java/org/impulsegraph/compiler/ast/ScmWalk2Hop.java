package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Fused 2-Hop CSR Graph Traversal Step: (csr-walk-2hop rel1 rel2).
 * Fuses two sequential forward CSR traversals into a single hardware register execution loop,
 * eliminating intermediate frontier bitset materialization.
 */
public record ScmWalk2Hop(
        String relation1Name,
        int relation1Id,
        String relation2Name,
        int relation2Id
) implements ImpScmNode {

    public ScmWalk2Hop(String relation1Name, int relation1Id, String relation2Name, int relation2Id) {
        this.relation1Name = Objects.requireNonNullElse(relation1Name, "");
        this.relation1Id = relation1Id;
        this.relation2Name = Objects.requireNonNullElse(relation2Name, "");
        this.relation2Id = relation2Id;
    }

    public static ScmWalk2Hop of(String rel1, String rel2) {
        return new ScmWalk2Hop(rel1, -1, rel2, -1);
    }

    public ScmWalk2Hop withPhysicalIds(int id1, int id2) {
        return new ScmWalk2Hop(relation1Name, id1, relation2Name, id2);
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitWalk2Hop(this);
    }

    @Override
    public String toScmString() {
        String r1 = relation1Id >= 0 ? String.valueOf(relation1Id) : "\"" + relation1Name + "\"";
        String r2 = relation2Id >= 0 ? String.valueOf(relation2Id) : "\"" + relation2Name + "\"";
        return "(csr-walk-2hop " + r1 + " " + r2 + ")";
    }
}
