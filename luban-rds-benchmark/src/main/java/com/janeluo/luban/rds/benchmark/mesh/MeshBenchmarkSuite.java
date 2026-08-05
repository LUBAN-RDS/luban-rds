package com.janeluo.luban.rds.benchmark.mesh;

import com.janeluo.luban.rds.benchmark.mesh.comparison.MeshVsSingleGetBenchmark;
import com.janeluo.luban.rds.benchmark.mesh.comparison.MeshVsSingleSetBenchmark;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mesh 全栈性能基准测试套件（参考 {@code ClusterBenchmarkSuite} 风格）。
 * <p>
 * 进程内起 3 个 mesh-enabled server（真实 Raft 选举/复制/租约），客户端经
 * {@code ClusterAwareClient} 跟随 MOVED 直连 Leader。场景：
 * <ol>
 *   <li>SET：单进程 vs mesh-3；</li>
 *   <li>GET：单进程 vs mesh-3（租约本地读）；</li>
 *   <li>并发写扩展性（1/2/4/8/16 线程）；</li>
 *   <li>Leader 故障恢复时间（3 轮中位数）。</li>
 * </ol>
 * </p>
 */
public class MeshBenchmarkSuite {
    private static final Logger log = LoggerFactory.getLogger(MeshBenchmarkSuite.class);

    public static void main(String[] args) {
        log.info("====== mesh 全栈性能基准测试套件 ======");

        new MeshVsSingleSetBenchmark().run();
        new MeshVsSingleGetBenchmark().run();
        new MeshScaleBenchmark().run();
        new MeshFailoverBenchmark().run();

        log.info("====== 基准测试完成 ======");
        log.info("结果文件在 target/test-metrics/L3-mesh-*.json");
    }
}
