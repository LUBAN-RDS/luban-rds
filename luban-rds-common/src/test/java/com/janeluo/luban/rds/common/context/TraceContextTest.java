package com.janeluo.luban.rds.common.context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

public class TraceContextTest {

    @Before
    public void setUp() {
        MDC.clear();
    }

    @After
    public void tearDown() {
        MDC.clear();
    }

    @Test
    public void testGenerateTraceId() {
        String traceId = TraceContext.generateTraceId();
        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
        assertTrue(traceId.contains("-"));
        String[] parts = traceId.split("-");
        assertEquals(4, parts.length);
        assertTrue(parts[0].length() > 0);
        assertTrue(parts[1].length() == 8);
        assertTrue(parts[2].length() > 0);
        assertTrue(parts[3].length() == 6);
    }

    @Test
    public void testGenerateTraceIdUniqueness() {
        int count = 10000;
        List<String> traceIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            traceIds.add(TraceContext.generateTraceId());
        }
        long distinctCount = traceIds.stream().distinct().count();
        assertEquals(count, distinctCount);
    }

    @Test
    public void testSetAndGetTraceId() {
        String traceId = "test-trace-id-12345";
        TraceContext.setTraceId(traceId);
        assertEquals(traceId, TraceContext.getTraceId());
        assertEquals(traceId, MDC.get(TraceContext.TRACE_ID_KEY));
    }

    @Test
    public void testSetNullTraceId() {
        TraceContext.setTraceId(null);
        assertNull(TraceContext.getTraceId());
    }

    @Test
    public void testSetEmptyTraceId() {
        TraceContext.setTraceId("");
        assertNull(TraceContext.getTraceId());
    }

    @Test
    public void testSetLongTraceId() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("a");
        }
        String longTraceId = sb.toString();
        TraceContext.setTraceId(longTraceId);
        String actualTraceId = TraceContext.getTraceId();
        assertNotNull(actualTraceId);
        assertEquals(64, actualTraceId.length());
        assertEquals(longTraceId.substring(0, 64), actualTraceId);
    }

    @Test
    public void testClearTraceId() {
        String traceId = "test-trace-id";
        TraceContext.setTraceId(traceId);
        assertNotNull(TraceContext.getTraceId());
        TraceContext.clearTraceId();
        assertNull(TraceContext.getTraceId());
    }

    @Test
    public void testHasTraceId() {
        assertFalse(TraceContext.hasTraceId());
        TraceContext.setTraceId("test-trace-id");
        assertTrue(TraceContext.hasTraceId());
        TraceContext.clearTraceId();
        assertFalse(TraceContext.hasTraceId());
    }

    @Test
    public void testStartTrace() {
        String traceId = TraceContext.startTrace();
        assertNotNull(traceId);
        assertEquals(traceId, TraceContext.getTraceId());
    }

    @Test
    public void testStartTraceWithGivenId() {
        String givenTraceId = "given-trace-id-123";
        String actualTraceId = TraceContext.startTrace(givenTraceId);
        assertEquals(givenTraceId, actualTraceId);
        assertEquals(givenTraceId, TraceContext.getTraceId());
    }

    @Test
    public void testStartTraceWithNullId() {
        String traceId = TraceContext.startTrace(null);
        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
        assertEquals(traceId, TraceContext.getTraceId());
    }

    @Test
    public void testStartTraceWithEmptyId() {
        String traceId = TraceContext.startTrace("");
        assertNotNull(traceId);
        assertFalse(traceId.isEmpty());
        assertEquals(traceId, TraceContext.getTraceId());
    }

    @Test
    public void testEndTrace() {
        TraceContext.setTraceId("test-trace-id");
        assertNotNull(TraceContext.getTraceId());
        TraceContext.endTrace();
        assertNull(TraceContext.getTraceId());
    }

    @Test
    public void testGetTraceIdKey() {
        assertEquals("traceId", TraceContext.getTraceIdKey());
    }

    @Test
    public void testTraceableRunnable() throws InterruptedException {
        String expectedTraceId = "runnable-test-trace-id";
        TraceContext.setTraceId(expectedTraceId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        final String[] capturedTraceId = new String[1];
        Runnable task = () -> {
            capturedTraceId[0] = TraceContext.getTraceId();
            latch.countDown();
        };
        executor.submit(TraceableRunnable.wrap(task));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(expectedTraceId, capturedTraceId[0]);
        executor.shutdown();
    }

    @Test
    public void testTraceableRunnableWithoutTraceId() throws InterruptedException {
        TraceContext.clearTraceId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        final String[] capturedTraceId = new String[1];
        Runnable task = () -> {
            capturedTraceId[0] = TraceContext.getTraceId();
            latch.countDown();
        };
        executor.submit(TraceableRunnable.wrap(task));
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNull(capturedTraceId[0]);
        executor.shutdown();
    }

    @Test
    public void testTraceableCallable() throws Exception {
        String expectedTraceId = "callable-test-trace-id";
        TraceContext.setTraceId(expectedTraceId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final String[] capturedTraceId = new String[1];
        java.util.concurrent.Callable<String> task = () -> {
            capturedTraceId[0] = TraceContext.getTraceId();
            return "result";
        };
        String result = executor.submit(TraceableCallable.wrap(task)).get(5, TimeUnit.SECONDS);
        assertEquals("result", result);
        assertEquals(expectedTraceId, capturedTraceId[0]);
        executor.shutdown();
    }

    @Test
    public void testTraceableExecutor() throws InterruptedException {
        String expectedTraceId = "executor-test-trace-id";
        TraceContext.setTraceId(expectedTraceId);
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        Executor traceableExecutor = TraceableExecutor.wrap(rawExecutor);
        CountDownLatch latch = new CountDownLatch(1);
        final String[] capturedTraceId = new String[1];
        traceableExecutor.execute(() -> {
            capturedTraceId[0] = TraceContext.getTraceId();
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(expectedTraceId, capturedTraceId[0]);
        rawExecutor.shutdown();
    }

    @Test
    public void testMultiThreadTraceIsolation() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<String> traceIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    String traceId = TraceContext.startTrace("thread-" + index + "-trace");
                    Thread.sleep(10);
                    traceIds.add(TraceContext.getTraceId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    TraceContext.endTrace();
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(threadCount, traceIds.size());
        for (int i = 0; i < threadCount; i++) {
            assertEquals("thread-" + i + "-trace", traceIds.get(i));
        }
        executor.shutdown();
    }
}
