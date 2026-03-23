package com.janeluo.luban.rds.acl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ACL 性能测试
 * 测试 ACL 系统的性能指标
 */
@DisplayName("ACL 性能测试")
class ACLPerformanceTest {

    private ACLManager aclManager;

    @BeforeEach
    void setUp() {
        aclManager = new ACLManager();
    }

    @Test
    @DisplayName("性能测试 - 用户创建")
    void testUserCreationPerformance() {
        long start = System.nanoTime();
        
        int count = 1000;
        for (int i = 0; i < count; i++) {
            aclManager.setUser("user" + i, 
                "on >pass" + i + " ~user" + i + ":* +@read +@write");
        }
        
        long elapsed = System.nanoTime() - start;
        double avgMs = elapsed / 1_000_000.0 / count;
        
        System.out.println(String.format(
            "用户创建性能: %d 个用户，平均 %.3f ms/个", count, avgMs));
        
        // 断言：平均每个用户创建时间应小于 1ms
        assertTrue(avgMs < 1.0, "用户创建性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 权限检查")
    void testPermissionCheckPerformance() {
        // 创建测试用户
        aclManager.setUser("perftest", "on >perfpass ~cache:* ~user:* +@read +@write");
        
        // 预热
        for (int i = 0; i < 100; i++) {
            aclManager.checkCommandPermission("perftest", "GET", Collections.emptyList());
        }
        
        // 性能测试
        int count = 10000;
        long start = System.nanoTime();
        
        for (int i = 0; i < count; i++) {
            aclManager.checkCommandPermission("perftest", "GET", Collections.emptyList());
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNs = elapsed / (double) count;
        double avgUs = avgNs / 1000.0;
        
        System.out.println(String.format(
            "权限检查性能: %d 次检查，平均 %.3f μs/次", count, avgUs));
        
        // 断言：平均每次权限检查应小于 10μs
        assertTrue(avgUs < 10.0, "权限检查性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 键模式匹配")
    void testKeyPatternMatchingPerformance() {
        // 创建包含多个键模式的用户
        aclManager.setUser("keytest", "on >keypass ~cache:* ~user:* ~session:* ~temp:* +@all");
        
        // 预热
        for (int i = 0; i < 100; i++) {
            aclManager.checkKeyPermission("keytest", "cache:test" + i, 
                ACLPermissionChecker.KeyAccessType.READ);
        }
        
        // 性能测试
        int count = 10000;
        long start = System.nanoTime();
        
        for (int i = 0; i < count; i++) {
            aclManager.checkKeyPermission("keytest", "cache:test" + i, 
                ACLPermissionChecker.KeyAccessType.READ);
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNs = elapsed / (double) count;
        double avgUs = avgNs / 1000.0;
        
        System.out.println(String.format(
            "键模式匹配性能: %d 次匹配，平均 %.3f μs/次", count, avgUs));
        
        // 断言：平均每次键模式匹配应小于 20μs
        assertTrue(avgUs < 20.0, "键模式匹配性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 大量键模式")
    void testManyKeyPatternsPerformance() {
        // 创建包含大量键模式的用户
        StringBuilder rules = new StringBuilder("on >manypass +@all");
        for (int i = 0; i < 50; i++) {
            rules.append(" ~pattern").append(i).append(":*");
        }
        
        aclManager.setUser("manypatterns", rules.toString());
        
        // 性能测试
        int count = 5000;
        long start = System.nanoTime();
        
        for (int i = 0; i < count; i++) {
            aclManager.checkKeyPermission("manypatterns", "pattern25:test" + i, 
                ACLPermissionChecker.KeyAccessType.READ);
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNs = elapsed / (double) count;
        double avgUs = avgNs / 1000.0;
        
        System.out.println(String.format(
            "大量键模式性能: %d 次匹配（50个模式），平均 %.3f μs/次", count, avgUs));
        
        // 断言：即使有 50 个模式，平均匹配时间也应小于 100μs
        assertTrue(avgUs < 100.0, "大量键模式性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 并发权限检查")
    void testConcurrentPermissionCheck() throws InterruptedException {
        aclManager.setUser("concurrent", "on >concurrentpass ~* +@all");
        
        int threadCount = 10;
        int operationsPerThread = 1000;
        Thread[] threads = new Thread[threadCount];
        
        long start = System.nanoTime();
        
        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    aclManager.checkCommandPermission("concurrent", "GET", 
                        Collections.emptyList());
                }
            });
            threads[t].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        long elapsed = System.nanoTime() - start;
        int totalOperations = threadCount * operationsPerThread;
        double throughput = totalOperations / (elapsed / 1_000_000_000.0);
        
        System.out.println(String.format(
            "并发性能: %d 线程，%d 次操作，吞吐量 %.0f ops/sec", 
            threadCount, totalOperations, throughput));
        
        // 断言：吞吐量应大于 50000 ops/sec
        assertTrue(throughput > 50000, "并发性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 用户认证")
    void testAuthenticationPerformance() {
        aclManager.setUser("authtest", "on >authpass123 ~* +@all");
        
        // 预热
        for (int i = 0; i < 100; i++) {
            aclManager.authenticate("authtest", "authpass123");
        }
        
        // 性能测试
        int count = 10000;
        long start = System.nanoTime();
        
        for (int i = 0; i < count; i++) {
            aclManager.authenticate("authtest", "authpass123");
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNs = elapsed / (double) count;
        double avgUs = avgNs / 1000.0;
        
        System.out.println(String.format(
            "认证性能: %d 次认证，平均 %.3f μs/次", count, avgUs));
        
        // 断言：平均每次认证应小于 50μs
        assertTrue(avgUs < 50.0, "认证性能不符合预期");
    }

    @Test
    @DisplayName("性能测试 - 审计日志写入")
    void testAuditLogPerformance() {
        // 性能测试
        int count = 10000;
        long start = System.nanoTime();
        
        for (int i = 0; i < count; i++) {
            aclManager.getAuditLogger().logAuthSuccess("testuser" + i);
        }
        
        long elapsed = System.nanoTime() - start;
        double avgNs = elapsed / (double) count;
        double avgUs = avgNs / 1000.0;
        
        System.out.println(String.format(
            "审计日志性能: %d 次写入，平均 %.3f μs/次", count, avgUs));
        
        // 断言：平均每次日志写入应小于 5μs
        assertTrue(avgUs < 5.0, "审计日志性能不符合预期");
    }

    private void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
