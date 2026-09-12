package com.janeluo.luban.rds.mesh.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * readIndex 短窗口缓存 + single-flight（fix-mesh-follower-read）。
 *
 * <h3>语义与陈旧上界</h3>
 * <p>
 * 缓存项以<b>请求发出时刻</b>为年龄基准（不是响应到达时刻），因此"读点陈旧上界"
 * = 缓存有效期 + 网络往返，可被审计与压测断言。窗口内的 Follower 读是<b>有界陈旧读</b>：
 * 可能读不到一个"在窗口内已返回 +OK"的写。这是已记录并接受的语义代价；
 * {@code cacheMs <= 0} 时完全关闭缓存，每次读取新读点，恢复严格线性一致。
 * </p>
 *
 * <h3>线程模型</h3>
 * <p>
 * 调用方是业务线程；RPC 回调（{@code rpcCall}）内部会阻塞等应答。single-flight 只让
 * 第一个到达者真正发 RPC，其余挂到同一 future，避免缓存失效瞬间的帧风暴。
 * </p>
 */
public class ReadIndexCache {

    private static final Logger logger = LoggerFactory.getLogger(ReadIndexCache.class);

    /** 读点缓存项。 */
    public static final class Entry {
        public final long readIndex;
        public final long term;
        /** 读点请求的发出时刻（年龄基准）。 */
        public final long takenAtMs;

        Entry(long readIndex, long term, long takenAtMs) {
            this.readIndex = readIndex;
            this.term = term;
            this.takenAtMs = takenAtMs;
        }
    }

    private volatile Entry cached;
    private final AtomicReference<CompletableFuture<Entry>> inFlight = new AtomicReference<>();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger invalidations = new AtomicInteger();
    private final AtomicInteger coalesced = new AtomicInteger();

    /**
     * 取读点。
     *
     * @param cacheMs      缓存有效期（&lt;=0 = 关闭缓存）
     * @param rpcTimeoutMs RPC 等待上限（透传给 {@code rpcCall} 的实现）
     * @param rpcCall      真正取读点的调用；返回 {@code null} 表示不可用
     *                     （返回 {@code Long} 而非 {@code long}，失败须能以 null 表达）
     * @param currentTerm  本节点当前 term（缓存随 term 变化失效）
     * @return 可用读点；不可用返回 {@code null}（调用方回落 MOVED）
     */
    public Entry get(long cacheMs, long rpcTimeoutMs, Supplier<Long> rpcCall, long currentTerm) {
        long now = System.currentTimeMillis();
        Entry hit = cached;
        if (cacheMs > 0 && hit != null
                && hit.term == currentTerm
                && (now - hit.takenAtMs) <= cacheMs) {
            cacheHits.incrementAndGet();
            return hit;
        }
        if (cacheMs <= 0) {
            // 关闭缓存：每次取新读点（不复用、不写缓存）
            Long v = rpcCall.get();
            return v == null ? null : new Entry(v, currentTerm, now);
        }
        if (hit != null && hit.term != currentTerm) {
            invalidate();
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> running = inFlight.compareAndExchange(null, mine);
        if (running != null) {
            // 已有在途请求：挂到同一 future（single-flight）
            coalesced.incrementAndGet();
            try {
                return running.get(rpcTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.debug("readIndex 合并等待失败: {}", e.toString());
                return null;
            }
        }
        try {
            Long v = rpcCall.get();
            if (v == null) {
                mine.completeExceptionally(new IllegalStateException("readIndex unavailable"));
                return null;
            }
            Entry entry = new Entry(v, currentTerm, now);   // 年龄基准 = 请求发出时刻
            cached = entry;
            mine.complete(entry);
            return entry;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            return null;
        } finally {
            inFlight.compareAndSet(mine, null);
        }
    }

    /** 立即整体失效（term/Leader 变更、apply halt）。 */
    public void invalidate() {
        cached = null;
        invalidations.incrementAndGet();
    }

    public int cacheHits() {
        return cacheHits.get();
    }

    public int invalidations() {
        return invalidations.get();
    }

    public int coalesced() {
        return coalesced.get();
    }
}
