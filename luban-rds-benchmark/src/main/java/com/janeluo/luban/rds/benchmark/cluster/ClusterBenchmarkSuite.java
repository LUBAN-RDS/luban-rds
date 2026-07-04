package com.janeluo.luban.rds.benchmark.cluster;

import com.janeluo.luban.rds.benchmark.cluster.comparison.ClusterScaleBenchmark;
import com.janeluo.luban.rds.benchmark.cluster.comparison.ClusterVsSingleGetBenchmark;
import com.janeluo.luban.rds.benchmark.cluster.comparison.ClusterVsSingleSetBenchmark;
import com.janeluo.luban.rds.benchmark.cluster.comparison.RedirectOverheadBenchmark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterBenchmarkSuite {
    private static final Logger log = LoggerFactory.getLogger(ClusterBenchmarkSuite.class);

    public static void main(String[] args) {
        log.info("====== 集群性能基准测试套件 ======");

        new ClusterVsSingleGetBenchmark().run();
        new ClusterVsSingleSetBenchmark().run();
        new ClusterScaleBenchmark().run();
        new RedirectOverheadBenchmark().run();

        log.info("====== 基准测试完成 ======");
        log.info("结果文件在 target/test-metrics/L3-*.json");
    }
}
