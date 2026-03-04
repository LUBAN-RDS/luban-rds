package com.janeluo.luban.rds.benchmark.core;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkRunner.class);
    private final List<Benchmark> benchmarks = new ArrayList<>();
    private BenchmarkConfig config;

    public BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
    }

    public void addBenchmark(Benchmark benchmark) {
        benchmarks.add(benchmark);
    }

    public void run() {
        System.out.println("Starting Benchmark Suite...");
        System.out.println(config);
        System.out.println("===================================================================================");

        for (Benchmark benchmark : benchmarks) {
            try {
                System.out.println("Running " + benchmark.getName() + "...");
                benchmark.setup(config);
                BenchmarkResult result = benchmark.run(config);
                System.out.println(result);
                benchmark.teardown();
            } catch (Exception e) {
                logger.error("Benchmark {} failed", benchmark.getName(), e);
                System.err.println("Benchmark " + benchmark.getName() + " failed: " + e.getMessage());
            }
        }
        System.out.println("===================================================================================");
        System.out.println("Benchmark Suite Completed.");
    }
}
