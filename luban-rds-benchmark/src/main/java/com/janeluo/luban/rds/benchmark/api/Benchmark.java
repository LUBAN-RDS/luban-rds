package com.janeluo.luban.rds.benchmark.api;

public interface Benchmark {
    /**
     * Get the name of the benchmark
     */
    String getName();

    /**
     * Setup the benchmark (e.g., prepare data)
     */
    void setup(BenchmarkConfig config) throws Exception;

    /**
     * Run the benchmark
     */
    BenchmarkResult run(BenchmarkConfig config) throws Exception;

    /**
     * Cleanup after benchmark
     */
    void teardown() throws Exception;
}
