package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SQLite-Style ImpulseStatement API Tests")
public class StatementApiTest {

    @Test
    @DisplayName("Prepared Statement Parameter Binding and Execution")
    public void testPreparedStatementExecution() {
        try (Arena arena = Arena.ofShared()) {
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 4, 5, 5);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 2, 3, 3);
            RelationSnapshot rel = new RelationSnapshot(arena, 4, 5, offsets, targets);

            ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("knows", rel));

            try (ImpulseStatement stmt = snap.prepare("FROM User WHERE id = $id -> out('knows')")) {
                stmt.bindNode("$id", 0);
                assertEquals(2, stmt.count());

                try (RowReader reader = stmt.execute()) {
                    List<Long> results = new ArrayList<>();
                    while (reader.next()) {
                        results.add(reader.getNodeId(0));
                    }
                    assertEquals(List.of(1L, 2L), results);
                }

                // Re-bind to node 1
                stmt.bindNode("$id", 1);
                assertEquals(2, stmt.count());

                try (RowReader reader = stmt.execute()) {
                    List<Long> results = new ArrayList<>();
                    while (reader.next()) {
                        results.add(reader.getNodeId(0));
                    }
                    assertEquals(List.of(2L, 3L), results);
                }
            }
        }
    }
}
