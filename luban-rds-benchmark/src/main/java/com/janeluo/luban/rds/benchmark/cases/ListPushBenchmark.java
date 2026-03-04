package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import com.janeluo.luban.rds.benchmark.util.DataGenerator;
import redis.clients.jedis.Jedis;

public class ListPushBenchmark extends AbstractBenchmark {
    private String value;

    @Override
    public String getName() {
        return "LPUSH";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        super.setup(config);
        this.value = DataGenerator.generateValue(config.getValueSize());
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_list_" + threadId;
        jedis.lpush(key, value);
    }
}
