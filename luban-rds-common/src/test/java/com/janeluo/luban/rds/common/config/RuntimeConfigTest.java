package com.janeluo.luban.rds.common.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeConfigTest {

    @Test
    public void testLuaScriptTimeout() {
        long originalTimeout = RuntimeConfig.getLuaScriptTimeoutMs();
        RuntimeConfig.setLuaScriptTimeoutMs(1000);
        assertEquals(1000, RuntimeConfig.getLuaScriptTimeoutMs());
        RuntimeConfig.setLuaScriptTimeoutMs(-1);
        assertEquals(5000L, RuntimeConfig.getLuaScriptTimeoutMs());
        RuntimeConfig.setLuaScriptTimeoutMs(originalTimeout);
    }

    @Test
    public void testLuaMaxReturnBytes() {
        long originalValue = RuntimeConfig.getLuaMaxReturnBytes();
        RuntimeConfig.setLuaMaxReturnBytes(512);
        assertEquals(512, RuntimeConfig.getLuaMaxReturnBytes());
        RuntimeConfig.setLuaMaxReturnBytes(-1);
        assertEquals(1048576, RuntimeConfig.getLuaMaxReturnBytes());
        RuntimeConfig.setLuaMaxReturnBytes(originalValue);
    }

    @Test
    public void testLuaMaxScriptBytes() {
        long originalValue = RuntimeConfig.getLuaMaxScriptBytes();
        RuntimeConfig.setLuaMaxScriptBytes(32768);
        assertEquals(32768, RuntimeConfig.getLuaMaxScriptBytes());
        RuntimeConfig.setLuaMaxScriptBytes(-1);
        assertEquals(65536, RuntimeConfig.getLuaMaxScriptBytes());
        RuntimeConfig.setLuaMaxScriptBytes(originalValue);
    }

    @Test
    public void testLuaSandboxEnabled() {
        boolean originalValue = RuntimeConfig.isLuaSandboxEnabled();
        RuntimeConfig.setLuaSandboxEnabled(false);
        assertFalse(RuntimeConfig.isLuaSandboxEnabled());
        RuntimeConfig.setLuaSandboxEnabled(true);
        assertTrue(RuntimeConfig.isLuaSandboxEnabled());
        RuntimeConfig.setLuaSandboxEnabled(originalValue);
    }

    @Test
    public void testLuaAllowedModules() {
        String originalValue = RuntimeConfig.getLuaAllowedModules();
        RuntimeConfig.setLuaAllowedModules("os,io");
        assertEquals("os,io", RuntimeConfig.getLuaAllowedModules());
        assertTrue(RuntimeConfig.isModuleAllowed("os"));
        assertTrue(RuntimeConfig.isModuleAllowed("io"));
        assertFalse(RuntimeConfig.isModuleAllowed("package"));
        RuntimeConfig.setLuaAllowedModules(originalValue);
    }

    @Test
    public void testLuaMaxOpsPerScript() {
        long originalValue = RuntimeConfig.getLuaMaxOpsPerScript();
        RuntimeConfig.setLuaMaxOpsPerScript(500);
        assertEquals(500, RuntimeConfig.getLuaMaxOpsPerScript());
        RuntimeConfig.setLuaMaxOpsPerScript(-1);
        assertEquals(1000, RuntimeConfig.getLuaMaxOpsPerScript());
        RuntimeConfig.setLuaMaxOpsPerScript(originalValue);
    }

    @Test
    public void testLuaYieldMs() {
        long originalValue = RuntimeConfig.getLuaYieldMs();
        RuntimeConfig.setLuaYieldMs(10);
        assertEquals(10, RuntimeConfig.getLuaYieldMs());
        RuntimeConfig.setLuaYieldMs(-1);
        assertEquals(0, RuntimeConfig.getLuaYieldMs());
        RuntimeConfig.setLuaYieldMs(originalValue);
    }

    @Test
    public void testLuaBlockedFunctions() {
        String originalValue = RuntimeConfig.getLuaBlockedFunctions();
        RuntimeConfig.setLuaBlockedFunctions("os.exit,io.open");
        assertEquals("os.exit,io.open", RuntimeConfig.getLuaBlockedFunctions());
        RuntimeConfig.setLuaBlockedFunctions(originalValue);
    }

    @Test
    public void testMetricsEnabled() {
        boolean originalValue = RuntimeConfig.isMetricsEnabled();
        RuntimeConfig.setMetricsEnabled(false);
        assertFalse(RuntimeConfig.isMetricsEnabled());
        RuntimeConfig.setMetricsEnabled(true);
        assertTrue(RuntimeConfig.isMetricsEnabled());
        RuntimeConfig.setMetricsEnabled(originalValue);
    }

    @Test
    public void testResetStats() {
        RuntimeConfig.incScriptExecutions();
        RuntimeConfig.incScriptTimeouts();
        RuntimeConfig.incScriptKills();
        RuntimeConfig.recordScriptDuration(100);
        RuntimeConfig.incKeyspaceHits();
        RuntimeConfig.incKeyspaceMisses();
        RuntimeConfig.incErrorRepliesTotal();
        RuntimeConfig.incErrorRepliesOom();
        
        RuntimeConfig.resetStats();
        
        assertEquals(0, RuntimeConfig.getScriptExecutions());
        assertEquals(0, RuntimeConfig.getScriptTimeouts());
        assertEquals(0, RuntimeConfig.getScriptKills());
        assertEquals(0, RuntimeConfig.getScriptTotalTimeMs());
        assertEquals(0, RuntimeConfig.getScriptMaxTimeMs());
        assertEquals(0, RuntimeConfig.getKeyspaceHits());
        assertEquals(0, RuntimeConfig.getKeyspaceMisses());
        assertEquals(0, RuntimeConfig.getErrorRepliesTotal());
        assertEquals(0, RuntimeConfig.getErrorRepliesOom());
    }
}
