package org.impulsegraph.compiler.jmh;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

public class HetionetScreen1JmhTestRunner {

    private static final Path HETIONET_PATH = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");

    @Test
    @DisplayName("Run JMH Benchmark for Screen 1 All-Diseases Sweep")
    void runJmhBenchmark() throws Exception {
        if (!Files.exists(HETIONET_PATH)) {
            System.out.println("Dataset snapshot missing, skipping JMH.");
            return;
        }

        Options opt = new OptionsBuilder()
                .include(HetionetScreen1AllDiseasesJmhBenchmark.class.getSimpleName())
                .forks(0)
                .warmupIterations(3)
                .measurementIterations(5)
                .build();
        new Runner(opt).run();
    }
}
