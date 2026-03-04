package com.janeluo.luban.rds.common.config;

public class RuntimeConfig {
    private static volatile long luaScriptTimeoutMs = 5000L;
    private static final java.util.concurrent.atomic.AtomicLong luaMaxReturnBytes = new java.util.concurrent.atomic.AtomicLong(1048576);
    private static final java.util.concurrent.atomic.AtomicLong luaMaxScriptBytes = new java.util.concurrent.atomic.AtomicLong(65536);
    private static final java.util.concurrent.atomic.AtomicInteger cachedScriptsCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicLong cachedScriptsBytes = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong scriptExecutions = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong scriptTimeouts = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong scriptKills = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong scriptTotalTimeMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong scriptMaxTimeMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong keyspaceHits = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong keyspaceMisses = new java.util.concurrent.atomic.AtomicLong(0);
    private static volatile boolean metricsEnabled = true;
    private static final java.util.concurrent.atomic.AtomicLong lastResetTimeMs = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong errorRepliesTotal = new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong errorRepliesOom = new java.util.concurrent.atomic.AtomicLong(0);
    private static volatile boolean luaSandboxEnabled = true;
    private static volatile String luaAllowedModules = "";
    private static final java.util.concurrent.atomic.AtomicLong luaMaxOpsPerScript = new java.util.concurrent.atomic.AtomicLong(1000);
    private static volatile long luaYieldMs = 1;
    private static volatile String luaBlockedFunctions = "";
    
    // SlowLog configuration
    private static volatile long slowlogLogSlowerThan = 10000; // 10000 microseconds (10ms)
    private static volatile long slowlogMaxLen = 128;
    
    // Monitor configuration
    private static volatile int monitorMaxClients = 100;

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

    public static void setLuaMaxReturnBytes(long v) {
        if (v <= 0) v = 1048576;
        luaMaxReturnBytes.set(v);
    }

    public static long getLuaMaxScriptBytes() {
        return luaMaxScriptBytes.get();
    }

    public static void setLuaMaxScriptBytes(long v) {
        if (v <= 0) v = 65536;
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
        if (ms < 0) return;
        if (!metricsEnabled) return;
        scriptTotalTimeMs.addAndGet(ms);
        long prev;
        do {
            prev = scriptMaxTimeMs.get();
            if (ms <= prev) break;
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
        if (csv == null) csv = "";
        luaAllowedModules = csv.trim();
    }
    
    public static String getLuaAllowedModules() {
        return luaAllowedModules;
    }
    
    public static boolean isModuleAllowed(String name) {
        if (luaAllowedModules == null || luaAllowedModules.isEmpty()) return false;
        String[] parts = luaAllowedModules.split(",");
        for (String p : parts) {
            if (p.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
    
    public static long getLuaMaxOpsPerScript() {
        return luaMaxOpsPerScript.get();
    }
    
    public static void setLuaMaxOpsPerScript(long v) {
        if (v <= 0) v = 1000;
        luaMaxOpsPerScript.set(v);
    }
    
    public static long getLuaYieldMs() {
        return luaYieldMs;
    }
    
    public static void setLuaYieldMs(long ms) {
        if (ms < 0) ms = 0;
        luaYieldMs = ms;
    }
    
    public static void setLuaBlockedFunctions(String csv) {
        if (csv == null) csv = "";
        luaBlockedFunctions = csv.trim();
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
        if (len < 0) len = 0;
        slowlogMaxLen = len;
    }

    public static int getMonitorMaxClients() {
        return monitorMaxClients;
    }

    public static void setMonitorMaxClients(int maxClients) {
        if (maxClients < 0) maxClients = 0;
        monitorMaxClients = maxClients;
    }
}
