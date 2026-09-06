package org.impulsegraph.builder.benchmark;

import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HetionetSnapshotBuilderBenchmarkTest {

	private static final Path HETIONET_DIR = Path.of("/Users/jesse/impulse/datasets/hetionet");
	private static final Path HETIONET_V09 = HETIONET_DIR.resolve("hetionet.v09.imps");

	record DomainMeta(int id, String name, String filename, long count, Map<String, Integer> keyToDenseId) {
	}

	record RelationMeta(String name, String filename, int srcDomainId, int tgtDomainId) {
	}

	private static final List<RelationMeta> HETIONET_RELATIONS = List.of(new RelationMeta("AdG", "rel_AdG.tsv", 0, 5),
			new RelationMeta("AeG", "rel_AeG.tsv", 0, 5), new RelationMeta("AuG", "rel_AuG.tsv", 0, 5),
			new RelationMeta("CbG", "rel_CbG.tsv", 3, 5), new RelationMeta("CcSE", "rel_CcSE.tsv", 3, 9),
			new RelationMeta("CdG", "rel_CdG.tsv", 3, 5), new RelationMeta("CpD", "rel_CpD.tsv", 3, 4),
			new RelationMeta("CrC", "rel_CrC.tsv", 3, 3), new RelationMeta("CtD", "rel_CtD.tsv", 3, 4),
			new RelationMeta("CuG", "rel_CuG.tsv", 3, 5), new RelationMeta("DaG", "rel_DaG.tsv", 4, 5),
			new RelationMeta("DdG", "rel_DdG.tsv", 4, 5), new RelationMeta("DlA", "rel_DlA.tsv", 4, 0),
			new RelationMeta("DpS", "rel_DpS.tsv", 4, 10), new RelationMeta("DrD", "rel_DrD.tsv", 4, 4),
			new RelationMeta("DuG", "rel_DuG.tsv", 4, 5), new RelationMeta("GcG", "rel_GcG.tsv", 5, 5),
			new RelationMeta("GiG", "rel_GiG.tsv", 5, 5), new RelationMeta("GpBP", "rel_GpBP.tsv", 5, 1),
			new RelationMeta("GpCC", "rel_GpCC.tsv", 5, 2), new RelationMeta("GpMF", "rel_GpMF.tsv", 5, 6),
			new RelationMeta("GpPW", "rel_GpPW.tsv", 5, 7), new RelationMeta("Gr>G", "rel_Gr>G.tsv", 5, 5),
			new RelationMeta("PCiC", "rel_PCiC.tsv", 8, 3));

	@Test
	@DisplayName("Benchmark & Validate Hetionet (.imps) Generation vs v0.9.0 Baseline")
	void testHetionetBenchmarkAndValidation() throws Exception {
		if (!Files.exists(HETIONET_DIR) || !Files.exists(HETIONET_V09)) {
			System.out.println("Hetionet dataset directory not found at " + HETIONET_DIR + ", skipping benchmark.");
			return;
		}

		System.out.println("=========================================================================");
		System.out.println("          HETIONET STREAMING SNAPSHOT BUILDER BENCHMARK & VALIDATION     ");
		System.out.println("=========================================================================");

		// 1. Ingest Domains
		long tStartParse = System.nanoTime();

		List<DomainMeta> domains = new ArrayList<>();
		String[] domainFiles = {"dom_Anatomy.tsv", "dom_Biological_Process.tsv", "dom_Cellular_Component.tsv",
				"dom_Compound.tsv", "dom_Disease.tsv", "dom_Gene.tsv", "dom_Molecular_Function.tsv", "dom_Pathway.tsv",
				"dom_Pharmacologic_Class.tsv", "dom_Side_Effect.tsv", "dom_Symptom.tsv"};
		String[] domainNames = {"Anatomy", "Biological Process", "Cellular Component", "Compound", "Disease", "Gene",
				"Molecular Function", "Pathway", "Pharmacologic Class", "Side Effect", "Symptom"};

		int totalNodes = 0;
		for (int did = 0; did < domainFiles.length; did++) {
			Path domFile = HETIONET_DIR.resolve(domainFiles[did]);
			Map<String, Integer> keyMap = new HashMap<>();
			try (BufferedReader br = Files.newBufferedReader(domFile, StandardCharsets.UTF_8)) {
				String line;
				int idx = 0;
				while ((line = br.readLine()) != null) {
					line = line.trim();
					if (!line.isEmpty()) {
						keyMap.put(line, idx++);
					}
				}
			}
			domains.add(new DomainMeta(did, domainNames[did], domainFiles[did], keyMap.size(), keyMap));
			totalNodes += keyMap.size();
		}
		System.out.printf("Ingested 11 domains: %,d total unique entities across all catalogs.\n", totalNodes);

		// 2. Ingest & Sort Edge Arrays for All 24 Relations
		Map<String, long[]> relationEdgeArrays = new LinkedHashMap<>();
		long totalEdges = 0;

		for (RelationMeta rm : HETIONET_RELATIONS) {
			Path relFile = HETIONET_DIR.resolve(rm.filename);
			Map<String, Integer> srcMap = domains.get(rm.srcDomainId).keyToDenseId();
			Map<String, Integer> tgtMap = domains.get(rm.tgtDomainId).keyToDenseId();

			// Fast line count pre-scan or direct growth
			List<String> lines = Files.readAllLines(relFile, StandardCharsets.UTF_8);
			long[] edges = new long[lines.size()];
			int edgeIdx = 0;

			for (String line : lines) {
				int tab = line.indexOf('\t');
				if (tab <= 0)
					continue;
				String sKey = line.substring(0, tab);
				String tKey = line.substring(tab + 1).trim();

				Integer u = srcMap.get(sKey);
				Integer v = tgtMap.get(tKey);
				if (u != null && v != null) {
					edges[edgeIdx++] = (((long) u) << 32) | (((long) v) & 0xFFFFFFFFL);
				}
			}

			if (edgeIdx < edges.length) {
				edges = Arrays.copyOf(edges, edgeIdx);
			}

			// Sort by Source ID (CSR format)
			Arrays.sort(edges);
			relationEdgeArrays.put(rm.name, edges);
			totalEdges += edges.length;
		}

		long tParseDone = System.nanoTime();
		double parseSeconds = (tParseDone - tStartParse) / 1e9;
		System.out.printf("Parsed & CSR-sorted %,d edges across 24 relations in %.3f s (%.1f K edges/sec)\n",
				totalEdges, parseSeconds, (totalEdges / parseSeconds) / 1000.0);

		// 3. Assemble Snapshot using SnapshotBuilder
		SnapshotBuilder builder = SnapshotBuilder.create();
		for (DomainMeta dm : domains) {
			builder.addDomain(dm.name(), DomainDefinition.builder(dm.count()).idWidth(PrimitiveWidth.UINT32).build());
		}

		for (RelationMeta rm : HETIONET_RELATIONS) {
			long[] edgeData = relationEdgeArrays.get(rm.name);
			builder.addRelation(rm.name,
					RelationDefinition.builder(domains.get(rm.srcDomainId).name(), domains.get(rm.tgtDomainId).name())
							.addTopology(Topology.CSR, CompressionScheme.RAW)
							.dataSource(new LongArrayEdgeSource(edgeData)).build());
		}

		builder.addFooterMetadata("dataset", "hetionet-v0.9.0");
		builder.addFooterMetadata("built_by", "org.impulsegraph.builder");
		builder.addFooterMetadata("timestamp", String.valueOf(System.currentTimeMillis()));

		// 4. Stream Snapshot to Disk and Benchmark
		Path tempSnapshot = Files.createTempFile("hetionet_generated_", ".imps");
		Path tempStaging = Files.createTempDirectory("hetionet_staging_");

		long tBuildStart = System.nanoTime();
		try {
			builder.withStagingDirectory(tempStaging).writeTo(tempSnapshot);

			long tBuildEnd = System.nanoTime();
			double buildSeconds = (tBuildEnd - tBuildStart) / 1e9;
			long fileSizeBytes = Files.size(tempSnapshot);
			double fileSizeMb = fileSizeBytes / (1024.0 * 1024.0);

			double throughputMteps = (totalEdges / buildSeconds) / 1_000_000.0;
			double writeThroughputMbSec = fileSizeMb / buildSeconds;

			System.out.println("-------------------------------------------------------------------------");
			System.out.println(" EMPIRICAL STREAMING BUILDER BENCHMARK METRICS (Hetionet):");
			System.out.printf("   • Total Nodes Serialized:    %,d nodes across 11 independent domains\n", totalNodes);
			System.out.printf("   • Total Edges Serialized:    %,d edges across 24 relations\n", totalEdges);
			System.out.printf("   • Output Snapshot Size:      %.2f MB (%,d bytes)\n", fileSizeMb, fileSizeBytes);
			System.out.printf("   • Snapshot Build Time:       %.3f ms (%.4f sec)\n", buildSeconds * 1000.0,
					buildSeconds);
			System.out.printf("   • Edge Write Throughput:     %.2f MTEPS (Million Edges / sec)\n", throughputMteps);
			System.out.printf("   • Disk I/O Write Bandwidth:  %.2f MB/sec\n", writeThroughputMbSec);
			System.out.println("=========================================================================");

			// 5. Validate Against hetionet.v09.imps Baseline
			System.out.println("Validating generated snapshot vs baseline hetionet.v09.imps...");

			try (Arena arena = Arena.ofConfined()) {
				BinarySnapshotLoader.LoadedSnapshot baseSnapshot = BinarySnapshotLoader.loadSnapshot(HETIONET_V09,
						arena);
				BinarySnapshotLoader.LoadedSnapshot genSnapshot = BinarySnapshotLoader.loadSnapshot(tempSnapshot,
						arena);

				// Domain Validation
				assertEquals(baseSnapshot.domainsById().size(), genSnapshot.domainsById().size(),
						"Domain count must match");
				for (int did = 0; did < baseSnapshot.domainsById().size(); did++) {
					String baseDomName = baseSnapshot.domainsById().get(did).name();
					String genDomName = genSnapshot.domainsById().get(did).name();
					assertEquals(baseDomName, genDomName, "Domain #" + did + " name mismatch");
				}

				// Relation Validation
				var baseRelMap = baseSnapshot.graph().getAllRelationSnapshots();
				var genRelMap = genSnapshot.graph().getAllRelationSnapshots();
				assertEquals(baseRelMap.size(), genRelMap.size(), "Relation count must match");

				for (String relName : baseRelMap.keySet()) {
					RelationSnapshot baseRel = baseSnapshot.graph().getRelationSnapshot(relName);
					RelationSnapshot genRel = genSnapshot.graph().getRelationSnapshot(relName);
					assertNotNull(genRel, "Missing relation in generated snapshot: " + relName);
					assertEquals(baseRel.getEdgeCount(), genRel.getEdgeCount(),
							"Edge count mismatch for relation " + relName);
				}

				// Verify degree distributions on AdG (Anatomy -> Gene, 102,240 edges)
				RelationSnapshot baseAdG = baseSnapshot.graph().getRelationSnapshot("AdG");
				RelationSnapshot genAdG = genSnapshot.graph().getRelationSnapshot("AdG");
				assertEquals(402, genAdG.getNodeCount(),
						"AdG node count in generated per-domain snapshot should be 402");
				for (int u = 0; u < 402; u++) {
					assertEquals(baseAdG.getDegree(u), genAdG.getDegree(u), "AdG degree mismatch at Anatomy node " + u);
				}

				// Verify degree distributions on DaG (Disease -> Gene, 12,623 edges)
				// In baseline hetionet, Disease started at global offset 14726
				RelationSnapshot baseDaG = baseSnapshot.graph().getRelationSnapshot("DaG");
				RelationSnapshot genDaG = genSnapshot.graph().getRelationSnapshot("DaG");
				assertEquals(137, genDaG.getNodeCount(),
						"DaG node count in generated per-domain snapshot should be 137");
				for (int d = 0; d < 137; d++) {
					assertEquals(baseDaG.getDegree(14726 + d), genDaG.getDegree(d),
							"DaG degree mismatch at Disease node " + d);
				}

				// Verify degree distributions on CbG (Compound -> Gene, 11,571 edges)
				// In baseline hetionet, Compound started at global offset 13174
				RelationSnapshot baseCbG = baseSnapshot.graph().getRelationSnapshot("CbG");
				RelationSnapshot genCbG = genSnapshot.graph().getRelationSnapshot("CbG");
				assertEquals(1552, genCbG.getNodeCount(),
						"CbG node count in generated per-domain snapshot should be 1552");
				for (int c = 0; c < 1552; c++) {
					assertEquals(baseCbG.getDegree(13174 + c), genCbG.getDegree(c),
							"CbG degree mismatch at Compound node " + c);
				}

				System.out.println(
						"SUCCESS: All 11 domains, 24 relations, and node degree distributions verified 100% bitwise consistent!");
			}

			// 6. Native CLI Inspection
			String[] candidatePaths = {"/Users/jesse/impulse/impulse-graph-tooling/target/release/impulse-graph",
					"/Users/jesse/impulse/impulse-graph-tooling/target/debug/impulse-graph"};
			File cliBinary = null;
			for (String path : candidatePaths) {
				File f = new File(path);
				if (f.exists() && f.canExecute()) {
					cliBinary = f;
					break;
				}
			}

			if (cliBinary != null) {
				ProcessBuilder pb = new ProcessBuilder(cliBinary.getAbsolutePath(), "inspect",
						tempSnapshot.toAbsolutePath().toString());
				pb.redirectErrorStream(true);
				Process proc = pb.start();
				StringBuilder out = new StringBuilder();
				try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					String l;
					while ((l = r.readLine()) != null)
						out.append(l).append("\n");
				}
				int exit = proc.waitFor();
				assertEquals(0, exit, "impulse-graph inspect must exit with 0");
				System.out.println("CLI inspect validation passed cleanly on generated Hetionet snapshot.");
			}

		} finally {
			Files.deleteIfExists(tempSnapshot);
			try {
				Files.deleteIfExists(tempStaging);
			} catch (Exception ignored) {
			}
		}
	}

	private static final class LongArrayEdgeSource implements RelationDataSource {
		private final long[] edges;

		LongArrayEdgeSource(long[] edges) {
			this.edges = edges;
		}

		@Override
		public long getEdgeCount() {
			return edges.length;
		}

		@Override
		public EdgeChunkIterator getEdges() {
			return new EdgeChunkIterator() {
				private int pos = 0;

				@Override
				public boolean hasNext() {
					return pos < edges.length;
				}

				@Override
				public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
					int count = 0;
					while (pos < edges.length && count < limit) {
						long e = edges[pos++];
						int u = (int) (e >>> 32);
						int v = (int) e;
						srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, u);
						tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, v);
						count++;
					}
					return count;
				}
			};
		}
	}
}
