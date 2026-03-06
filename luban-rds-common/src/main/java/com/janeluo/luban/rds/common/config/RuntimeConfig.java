package com.janeluo.luban.rds.common.config;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时配置类
 * 管理服务器运行时的各种配置参数和统计信息
 */
public final class RuntimeConfig {

    /** Lua 脚本执行超时时间（毫秒） */
    private static volatile long luaScriptTimeoutMs = 5000L;

    /** Lua 脚本最大返回字节数 */
    private static final AtomicLong luaMaxReturnBytes = new AtomicLong(1048576);

    /** Lua 脚本最大字节数 */
    private static final AtomicLong luaMaxScriptBytes = new AtomicLong(65536);

    /** 缓存的脚本数量 */
    private static final AtomicInteger cachedScriptsCount = new AtomicInteger(0);

    /** 缓存的脚本字节数 */
    private static final AtomicLong cachedScriptsBytes = new AtomicLong(0);

    /** 脚本执行次数 */
    private static final AtomicLong scriptExecutions = new AtomicLong(0);

    /** 脚本超时次数 */
    private static final AtomicLong scriptTimeouts = new AtomicLong(0);

    /** 脚本终止次数 */
    private static final AtomicLong scriptKills = new AtomicLong(0);

    /** 脚本执行总时间（毫秒） */
    private static final AtomicLong scriptTotalTimeMs = new AtomicLong(0);

    /** 脚本最大执行时间（毫秒） */
    private static final AtomicLong scriptMaxTimeMs = new AtomicLong(0);

    /** 键空间命中次数 */
    private static final AtomicLong keyspaceHits = new AtomicLong(0);

    /** 键空间未命中次数 */
    private static final AtomicLong keyspaceMisses = new AtomicLong(0);

    /** 是否启用指标统计 */
    private static volatile boolean metricsEnabled = true;

    /** 上次重置时间（毫秒） */
    private static final AtomicLong lastResetTimeMs = new AtomicLong(0);

    /** 错误响应总数 */
    private static final AtomicLong errorRepliesTotal = new AtomicLong(0);

    /** OOM 错误响应数 */
    private static final AtomicLong errorRepliesOom = new AtomicLong(0);

    /** 是否启用 Lua 沙箱模式 */
    private static volatile boolean luaSandboxEnabled = true;

    /** Lua 允许的模块列表（逗号分隔） */
    private static volatile String luaAllowedModules = "";

    /** Lua 脚本最大操作数 */
    private static final AtomicLong luaMaxOpsPerScript = new AtomicLong(1000);

    /** Lua 脚本让步间隔（毫秒） */
    private static volatile long luaYieldMs = 1;

    /** Lua 阻止的函数列表（逗号分隔） */
    private static volatile String luaBlockedFunctions = "";

    /** 慢查询日志阈值（微秒），默认 10000 微秒（10ms） */
    private static volatile long slowlogLogSlowerThan = 10000;

    /** 慢查询日志最大长度，默认 128 */
    private static volatile long slowlogMaxLen = 128;

    /** 监控最大客户端连接数，默认 100 */
    private static volatile int monitorMaxClients = 100;

    private RuntimeConfig() {
    }

    public static long getLuaScriptTimeoutMs() {
        return luaScriptTimeoutMs;
    }

    public static void setLuaScriptTimeoutMs(long timeoutMs) {
        if (timeoutMs <= 0) {
            luaScriptTimeoutMs = 5000L;
        } else {
            luaScriptTimeoutMs = timeoutMs;
        }
    }

    public static long getLuaMaxReturnBytes() {
        return luaMaxReturnBytes.get();
    }

    public static void setLuaMaxReturnBytes(long value) {
        long v = value;
        if (v <= 0) {
            v = 1048576;
        }
        luaMaxReturnBytes.set(v);
    }

    public static long getLuaMaxScriptBytes() {
        return luaMaxScriptBytes.get();
    }

    public static void setLuaMaxScriptBytes(long value) {
        long v = value;
        if (v <= 0) {
            v = 65536;
        }
        luaMaxScriptBytes.set(v);
    }

    public static int getCachedScriptsCount() {
        return cachedScriptsCount.get();
    }

    public static void incrementCachedScriptsCount() {
        cachedScriptsCount.incrementAndGet();
    }

    public static void resetCachedScriptsCount() {
        cachedScriptsCount.set(0);
    }

    public static long getCachedScriptsBytes() {
        return cachedScriptsBytes.get();
    }

    public static void addCachedScriptsBytes(long bytes) {
        if (bytes > 0) {
            cachedScriptsBytes.addAndGet(bytes);
        }
    }

    public static void resetCachedScriptsBytes() {
        cachedScriptsBytes.set(0);
    }

    public static void incScriptExecutions() {
        if (metricsEnabled) {
            scriptExecutions.incrementAndGet();
        }
    }

    public static void incScriptTimeouts() {
        if (metricsEnabled) {
            scriptTimeouts.incrementAndGet();
        }
    }

    public static long getScriptExecutions() {
        return scriptExecutions.get();
    }

    public static long getScriptTimeouts() {
        return scriptTimeouts.get();
    }

    public static void incScriptKills() {
        if (metricsEnabled) {
            scriptKills.incrementAndGet();
        }
    }

    public static long getScriptKills() {
        return scriptKills.get();
    }

    public static void recordScriptDuration(long ms) {
        if (ms < 0) {
            return;
        }
        if (!metricsEnabled) {
            return;
        }
        scriptTotalTimeMs.addAndGet(ms);
        long prev;
        do {
            prev = scriptMaxTimeMs.get();
            if (ms <= prev) {
                break;
            }
        } while (!scriptMaxTimeMs.compareAndSet(prev, ms));
    }

    public static long getScriptTotalTimeMs() {
        return scriptTotalTimeMs.get();
    }

    public static long getScriptMaxTimeMs() {
        return scriptMaxTimeMs.get();
    }

    public static void incKeyspaceHits() {
        if (metricsEnabled) {
            keyspaceHits.incrementAndGet();
        }
    }

    public static void incKeyspaceMisses() {
        if (metricsEnabled) {
            keyspaceMisses.incrementAndGet();
        }
    }

    public static long getKeyspaceHits() {
        return keyspaceHits.get();
    }

    public static long getKeyspaceMisses() {
        return keyspaceMisses.get();
    }

    public static void resetStats() {
        scriptExecutions.set(0);
        scriptTimeouts.set(0);
        scriptKills.set(0);
        scriptTotalTimeMs.set(0);
        scriptMaxTimeMs.set(0);
        keyspaceHits.set(0);
        keyspaceMisses.set(0);
        errorRepliesTotal.set(0);
        errorRepliesOom.set(0);
        lastResetTimeMs.set(System.currentTimeMillis());
    }

    public static void setMetricsEnabled(boolean enabled) {
        metricsEnabled = enabled;
    }

    public static boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public static long getLastResetTimeMs() {
        return lastResetTimeMs.get();
    }

    public static void incErrorRepliesTotal() {
        if (metricsEnabled) {
            errorRepliesTotal.incrementAndGet();
        }
    }

    public static void incErrorRepliesOom() {
        if (metricsEnabled) {
            errorRepliesOom.incrementAndGet();
            errorRepliesTotal.incrementAndGet();
        }
    }

    public static long getErrorRepliesTotal() {
        return errorRepliesTotal.get();
    }

    public static long getErrorRepliesOom() {
        return errorRepliesOom.get();
    }

    public static boolean isLuaSandboxEnabled() {
        return luaSandboxEnabled;
    }

    public static void setLuaSandboxEnabled(boolean enabled) {
        luaSandboxEnabled = enabled;
    }

    public static void setLuaAllowedModules(String csv) {
        String value = csv;
        if (value == null) {
            value = "";
        }
        luaAllowedModules = value.trim();
    }

    public static String getLuaAllowedModules() {
        return luaAllowedModules;
    }

    public static boolean isModuleAllowed(String name) {
        if (luaAllowedModules == null || luaAllowedModules.isEmpty()) {
            return false;
        }
        String[] parts = luaAllowedModules.split(",");
        for (String part : parts) {
            if (part.trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public static long getLuaMaxOpsPerScript() {
        return luaMaxOpsPerScript.get();
    }

    public static void setLuaMaxOpsPerScript(long value) {
        long v = value;
        if (v <= 0) {
            v = 1000;
        }
        luaMaxOpsPerScript.set(v);
    }

    public static long getLuaYieldMs() {
        return luaYieldMs;
    }

    public static void setLuaYieldMs(long ms) {
        long value = ms;
        if (value < 0) {
            value = 0;
        }
        luaYieldMs = value;
    }

    public static void setLuaBlockedFunctions(String csv) {
        String value = csv;
        if (value == null) {
            value = "";
        }
        luaBlockedFunctions = value.trim();
    }

    public static String getLuaBlockedFunctions() {
        return luaBlockedFunctions;
    }

    public static long getSlowlogLogSlowerThan() {
        return slowlogLogSlowerThan;
    }

    public static void setSlowlogLogSlowerThan(long microseconds) {
        slowlogLogSlowerThan = microseconds;
    }

    public static long getSlowlogMaxLen() {
        return slowlogMaxLen;
    }

    public static void setSlowlogMaxLen(long len) {
        long value = len;
        if (value < 0) {
            value = 0;
        }
        slowlogMaxLen = value;
    }

    public static int getMonitorMaxClients() {
        return monitorMaxClients;
    }

    public static void setMonitorMaxClients(int maxClients) {
        int value = maxClients;
        if (value < 0) {
            value = 0;
        }
        monitorMaxClients = value;
    }
}
