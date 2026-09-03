package org.impulsegraph.samples;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

/**
 * Demonstrates prepared statements for sub-microsecond parameterized query
 * execution.
 */
public class CompiledStatementSample {

	public static void main(String[] args) throws Exception {
		Path snapshotFile = Path.of("datasets/hetionet.imps");

		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotFile, arena);
			ImpulseGraphSnapshot snap = loaded.getGraph();

			// 1. Prepare declarative openCypher query once (compiles & optimizes to
			// bytecode)
			String cypher = """
					MATCH (c:Compound)-[:TREATS]->(d:Disease)
					WHERE c.id = $sourceCompound
					RETURN d.id
					""";

			try (ImpulseStatement stmt = snap.prepare(cypher)) {
				// 2. Bind parameter and execute
				stmt.bindNode("$sourceCompound", 0);
				try (RowReader rows = stmt.execute()) {
					int count = 0;
					while (rows.next()) {
						long diseaseId = rows.getNodeId(0);
						System.out.printf("Row %d: Disease Node ID = %d%n", ++count, diseaseId);
					}
					System.out.printf("Discovered %d matching diseases.%n", count);
				}

				// 3. Re-bind different parameter on same prepared statement (sub-microsecond)
				stmt.bindNode("$sourceCompound", 5);
				try (RowReader rows = stmt.execute()) {
					int count = 0;
					while (rows.next()) {
						count++;
					}
					System.out.printf("Second query execution results count: %d%n", count);
				}
			}
		}
	}
}
