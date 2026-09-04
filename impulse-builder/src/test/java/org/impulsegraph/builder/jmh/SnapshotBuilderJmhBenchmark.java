package org.impulsegraph.builder.jmh;

import org.impulsegraph.builder.api.DomainDefinition;
import org.impulsegraph.builder.api.RelationDefinition;
import org.impulsegraph.builder.api.SnapshotBuilder;
import org.impulsegraph.builder.spi.EdgeChunkIterator;
import org.impulsegraph.builder.spi.RelationDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH Microbenchmark suite measuring the raw throughput and latency of the
 * single-pass SnapshotBuilder streaming pipeline.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SnapshotBuilderJmhBenchmark {

	private static final Path HETIONET_DIR = Path.of("/Users/jesse/impulse/datasets/hetionet");

	private Map<String, Integer> domainCounts;
	private Map<String, EdgeData> relationData;
	private Path tempOutputPath;

	private record EdgeData(int[] srcIds, int[] tgtIds, int count, String srcDomain, String tgtDomain) {
	}

	@Setup(Level.Trial)
	public void setup() throws Exception {
		domainCounts = new LinkedHashMap<>();
		relationData = new LinkedHashMap<>();
		tempOutputPath = Files.createTempFile("jmh_hetionet_builder_", ".imps");

		// Load Domains
		Map<String, Map<String, Integer>> domainKeyMaps = new HashMap<>();
		try (var stream = Files.list(HETIONET_DIR)) {
			List<Path> domFiles = stream.filter(
					p -> p.getFileName().toString().startsWith("dom_") && p.getFileName().toString().endsWith(".tsv"))
					.sorted().toList();

			for (Path domFile : domFiles) {
				String fileName = domFile.getFileName().toString();
				String domainName = fileName.substring("dom_".length(), fileName.length() - ".tsv".length());
				Map<String, Integer> keyMap = new HashMap<>();
				List<String> lines = Files.readAllLines(domFile);
				int denseId = 0;
				for (String line : lines) {
					String key = line.trim();
					if (!key.isEmpty() && !keyMap.containsKey(key)) {
						keyMap.put(key, denseId++);
					}
				}
				domainCounts.put(domainName, denseId);
				domainKeyMaps.put(domainName, keyMap);
			}
		}

		// Load Relations
		try (var stream = Files.list(HETIONET_DIR)) {
			List<Path> relFiles = stream.filter(
					p -> p.getFileName().toString().startsWith("rel_") && p.getFileName().toString().endsWith(".tsv"))
					.sorted().toList();

			for (Path relFile : relFiles) {
				String fileName = relFile.getFileName().toString();
				String relName = fileName.substring("rel_".length(), fileName.length() - ".tsv".length());

				String srcDom = null;
				String tgtDom = null;
				for (String d : domainCounts.keySet()) {
					if (relName.startsWith(d.substring(0, Math.min(2, d.length())))) {
						if (srcDom == null)
							srcDom = d;
					}
				}
				if (srcDom == null)
					srcDom = "Gene";
				tgtDom = "Gene";

				List<String> lines = Files.readAllLines(relFile);
				int[] srcArr = new int[lines.size()];
				int[] tgtArr = new int[lines.size()];
				int count = 0;

				Map<String, Integer> srcMap = domainKeyMaps.get(srcDom);
				Map<String, Integer> tgtMap = domainKeyMaps.get(tgtDom);

				for (String line : lines) {
					String[] parts = line.split("\t");
					if (parts.length >= 2) {
						Integer u = srcMap != null ? srcMap.get(parts[0].trim()) : null;
						Integer v = tgtMap != null ? tgtMap.get(parts[1].trim()) : null;
						if (u == null)
							u = count % 1000;
						if (v == null)
							v = (count + 1) % 1000;
						srcArr[count] = u;
						tgtArr[count] = v;
						count++;
					}
				}
				relationData.put(relName, new EdgeData(srcArr, tgtArr, count, srcDom, tgtDom));
			}
		}
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		Files.deleteIfExists(tempOutputPath);
	}

	private SnapshotBuilder createPopulatedBuilder() {
		SnapshotBuilder builder = SnapshotBuilder.create();

		for (var entry : domainCounts.entrySet()) {
			builder.addDomain(entry.getKey(), DomainDefinition.builder(entry.getValue()).build());
		}

		for (var entry : relationData.entrySet()) {
			EdgeData data = entry.getValue();
			RelationDataSource rds = new RelationDataSource() {
				@Override
				public long getEdgeCount() {
					return data.count;
				}

				@Override
				public EdgeChunkIterator getEdges() {
					return new EdgeChunkIterator() {
						private int cursor = 0;

						@Override
						public boolean hasNext() {
							return cursor < data.count;
						}

						@Override
						public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
							int n = Math.min(limit, data.count - cursor);
							for (int i = 0; i < n; i++) {
								srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, data.srcIds[cursor + i]);
								tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i, data.tgtIds[cursor + i]);
							}
							cursor += n;
							return n;
						}
					};
				}
			};

			builder.addRelation(entry.getKey(),
					RelationDefinition.builder(data.srcDomain, data.tgtDomain).dataSource(rds).build());
		}
		return builder;
	}

	@Benchmark
	public void benchmarkHetionetInMemorySerialization(Blackhole bh) throws Exception {
		SnapshotBuilder builder = createPopulatedBuilder();
		byte[] bytes = builder.toByteArray();
		bh.consume(bytes);
	}

	@Benchmark
	public void benchmarkHetionetDirectDiskWrite(Blackhole bh) throws Exception {
		SnapshotBuilder builder = createPopulatedBuilder();
		builder.writeTo(tempOutputPath);
		bh.consume(tempOutputPath.toFile().length());
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
				.include(SnapshotBuilderJmhBenchmark.class.getSimpleName())
				.forks(0)
				.warmupIterations(2)
				.measurementIterations(3)
				.build();

		new Runner(opt).run();
	}
}
