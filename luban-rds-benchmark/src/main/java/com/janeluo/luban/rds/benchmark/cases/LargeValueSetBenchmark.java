package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import com.janeluo.luban.rds.benchmark.util.DataGenerator;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

/**
 * 大 value SET 场景：强制 value >= 1024B，覆盖 hybrid 模式 offheap threshold（默认 256B）。
 * 用于对照 HybridMemoryStore（堆外）与 DefaultMemoryStore（堆上）在大 string 写入路径的吞吐/GC 差异。
 * 与 {@link LargeValueGetBenchmark} 配对：后者读取本场景写入的 key。
 */
public class LargeValueSetBenchmark extends AbstractBenchmark {
    private String value;

    @Override
    public String getName() {
        return "LARGE-SET";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        super.setup(config);
        // 强制大 value：取配置值与 1024 的最大值，确保稳定进堆外引擎
        this.value = DataGenerator.generateValue(Math.max(config.getValueSize(), 1024));
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_large_" + threadId + "_" + iteration;
        jedis.set(key, value);
    }

    @Override
    protected void executePipelineCommand(Pipeline pipeline, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_large_" + threadId + "_" + iteration;
        pipeline.set(key, value);
    }
}
