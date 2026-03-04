package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import redis.clients.jedis.Jedis;

public class ListRangeBenchmark extends AbstractBenchmark {

    @Override
    public String getName() {
        return "LRANGE";
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_list_" + threadId;
        jedis.lrange(key, 0, 10);
    }
}
