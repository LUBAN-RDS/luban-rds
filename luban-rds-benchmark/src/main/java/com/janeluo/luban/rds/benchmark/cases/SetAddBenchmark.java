package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import com.janeluo.luban.rds.benchmark.util.DataGenerator;
import redis.clients.jedis.Jedis;

public class SetAddBenchmark extends AbstractBenchmark {
    private String valuePrefix;

    @Override
    public String getName() {
        return "SADD";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        super.setup(config);
        this.valuePrefix = DataGenerator.generateValue(config.getValueSize());
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_setcollection_" + threadId;
        jedis.sadd(key, valuePrefix + "_" + iteration);
    }
}
