package com.janeluo.luban.rds.mesh.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * {@code cacheMs <= 0} 时完全关闭缓存，每次读取新读点。注意：关闭缓存只是严格线性一致的
 * <b>必要条件</b>——读点取得后仍须由 gate 等待本地 apply 追平该读点（apply 屏障），
 * 二者共同决定是否严格线性一致。
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

    private final AtomicReference<Entry> cached = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Entry>> inFlight = new AtomicReference<>();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger invalidations = new AtomicInteger();
    private final AtomicInteger coalesced = new AtomicInteger();

    /**
     * 失效纪元：{@link #invalidate()} 自增。在途 RPC 发起前捕获，返回后仅当纪元未变才写缓存，
     * 防止"取点途中发生 term/Leader 变更/apply halt"的读点在失效后被回填（陈旧缓存污染）。
     */
    private final AtomicLong epoch = new AtomicLong();

    /**
     * 取读点。
     *
     * @param cacheMs      缓存有效期（&lt;=0 = 关闭缓存）
     * @param rpcTimeoutMs RPC 等待上限（透传给 {@code rpcCall} 的实现）
     * @param rpcCall      真正取读点的调用；返回 {@code null} 表示不可用
     *                     （返回 {@code Long} 而非 {@code long}，失败须能以 null 表达）
     * @param currentTerm  本节点当前 term（缓存随 term 变化失效）
     * @return 可用读点；不可用返回 {@code null}（调用方回落 MOVED）。注意：合并等待者
     *         （coalesced）可能因共享读点的 term 与自身 {@code currentTerm} 不一致而拿到
     *         {@code null}——此时同样回落 MOVED，而非采信 term 不一致的读点。
     */
    public Entry get(long cacheMs, long rpcTimeoutMs, Supplier<Long> rpcCall, long currentTerm) {
        long now = System.currentTimeMillis();
        Entry hit = cached.get();
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
                Entry shared = running.get(rpcTimeoutMs, TimeUnit.MILLISECONDS);
                // single-flight 只共享"取点动作"，不共享 term：owner 可能在本调用方的 term
                // 之前发起。读点 term 若与本调用方当前 term 不一致则不采信（返回 null 回落
                // MOVED，绝不返回 term 不一致的读点，保持 entry.term == currentTerm 不变式）。
                if (shared == null || shared.term != currentTerm) {
                    return null;
                }
                return shared;
            } catch (Exception e) {
                logger.debug("readIndex 合并等待失败: {}", e.toString());
                return null;
            }
        }
        long epochAtStart = epoch.get();
        try {
            Long v = rpcCall.get();
            if (v == null) {
                mine.completeExceptionally(new IllegalStateException("readIndex unavailable"));
                return null;
            }
            Entry entry = new Entry(v, currentTerm, now);   // 年龄基准 = 请求发出时刻
            // 失效竞态防护：RPC 在途期间若发生 invalidate（term/Leader 变更、apply halt），
            // 不得把失效前的读点写回缓存——否则后续调用会持续拿到陈旧读点。本次调用方仍可
            // 采信该读点（取自租约有效的 Leader），此处只阻断"污染缓存"，不阻断本次回答。
            if (epoch.get() == epochAtStart) {
                cached.set(entry);
            }
            mine.complete(entry);
            return entry.term == currentTerm ? entry : null;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            return null;
        } finally {
            inFlight.compareAndSet(mine, null);
        }
    }

    /**
     * 立即整体失效（term/Leader 变更、apply halt）。
     * <p>两件事<b>分开处理</b>：</p>
     * <ul>
     *   <li><b>纪元必然递增</b>、缓存项必然清空——纪元是阻断「在途 RPC 返回后回填失效前读点」
     *       的唯一手段，绝不能因有无缓存项而条件化；</li>
     *   <li>用户可见的 {@code invalidations} 计数<b>仅在确实清掉一个非 null 缓存项时</b>递增
     *       ——开关关闭（OFF）的节点 gate 从不写缓存，失效计数因此保持 0，符合
     *       「计数在开关关闭时不增长」的规格；同时让计数语义收敛为「确实丢弃了一个缓存读点」。</li>
     * </ul>
     */
    public void invalidate() {
        Entry prev = cached.getAndSet(null);
        epoch.incrementAndGet();   // 纪元递增：在途 RPC 返回后不再回填失效前的读点
        if (prev != null) {
            invalidations.incrementAndGet();
        }
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
