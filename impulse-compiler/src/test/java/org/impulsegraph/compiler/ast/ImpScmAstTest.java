package org.impulsegraph.compiler.ast;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.ast.parser.ImpScmSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ImpScheme sealed AST representation, S-expression parser, and serializer.
 */
public class ImpScmAstTest {

    @Test
    @DisplayName("ImpScheme S-Expression Round-Trip Serialization & Parsing")
    void testRoundTripSerialization() {
        ScmProgram original = ScmProgram.of(
                ScmWalk.forward("userToGroup"),
                ScmVectorFilter.of(ScmList.of(ScmSymbol.of("vec-cmp-gte"), ScmSymbol.of(":age"), ScmLiteral.ofInt(21))),
                ScmCollect.bitset()
        );

        String serialized = ImpScmSerializer.serialize(original);
        assertNotNull(serialized);
        assertTrue(serialized.contains("csr-walk"));
        assertTrue(serialized.contains("vector-filter"));
        assertTrue(serialized.contains("collect-bitset"));

        ImpScmNode parsed = ImpScmParser.parse(serialized);
        assertNotNull(parsed);
        assertInstanceOf(ScmProgram.class, parsed);

        ScmProgram parsedProg = (ScmProgram) parsed;
        assertEquals(3, parsedProg.steps().size());
        assertInstanceOf(ScmWalk.class, parsedProg.steps().get(0));
        assertInstanceOf(ScmVectorFilter.class, parsedProg.steps().get(1));
        assertInstanceOf(ScmCollect.class, parsedProg.steps().get(2));
    }

    @Test
    @DisplayName("ImpScheme Reduce and Literal Types")
    void testReduceAndLiterals() {
        ScmProgram prog = ScmProgram.of(
                ScmWalk.reverse("deviceToNetwork"),
                ScmReduce.sum()
        );

        String scm = prog.toScmString();
        assertTrue(scm.contains("csc-walk"));
        assertTrue(scm.contains("reduce-sum"));

        ImpScmNode parsed = ImpScmParser.parse(scm);
        assertNotNull(parsed);
        assertInstanceOf(ScmProgram.class, parsed);
    }
}
