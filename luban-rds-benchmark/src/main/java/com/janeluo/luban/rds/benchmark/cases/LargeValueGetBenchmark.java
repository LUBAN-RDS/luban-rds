package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Pipeline;

/**
 * 大 value GET 场景：读取 {@link LargeValueSetBenchmark} 写入的大 string key。
 * 用于对照 HybridMemoryStore（堆外 ByteBuf → 堆上拷贝）与 DefaultMemoryStore（堆上直接引用）
 * 在大 string 读取路径的吞吐/延迟差异。键空间循环 1000 个，保证命中。
 */
public class LargeValueGetBenchmark extends AbstractBenchmark {

    @Override
    public String getName() {
        return "LARGE-GET";
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        // 与 LargeValueSetBenchmark 的 key 命名一致，循环 1000 个 key 提高命中率
        String key = config.getKeyPrefix() + "_large_" + threadId + "_" + (iteration % 1000);
        jedis.get(key);
    }

    @Override
    protected void executePipelineCommand(Pipeline pipeline, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_large_" + threadId + "_" + (iteration % 1000);
        pipeline.get(key);
    }
}
