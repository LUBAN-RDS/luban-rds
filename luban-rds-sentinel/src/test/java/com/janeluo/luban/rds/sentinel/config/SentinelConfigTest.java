package com.janeluo.luban.rds.sentinel.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelConfig 测试类
 */
class SentinelConfigTest {
    
    @Test
    void testDefaultConfig() {
        SentinelConfig config = new SentinelConfig();
        
        assertEquals(SentinelConstants.DEFAULT_SENTINEL_PORT, config.getPort());
        assertEquals("0.0.0.0", config.getBind());
        assertEquals(SentinelConstants.DEFAULT_MONITOR_INTERVAL, config.getMonitorInterval());
        assertEquals(SentinelConstants.DEFAULT_DOWN_AFTER_MILLISECONDS, config.getDownAfterMilliseconds());
        assertEquals(SentinelConstants.DEFAULT_FAILOVER_TIMEOUT, config.getFailoverTimeout());
        assertEquals(SentinelConstants.DEFAULT_PARALLEL_SYNCS, config.getParallelSyncs());
    }
    
    @Test
    void testSettersAndGetters() {
        SentinelConfig config = new SentinelConfig();
        
        config.setPort(26380);
        assertEquals(26380, config.getPort());
        
        config.setBind("127.0.0.1");
        assertEquals("127.0.0.1", config.getBind());
        
        config.setMonitorInterval(2000);
        assertEquals(2000, config.getMonitorInterval());
        
        config.setDownAfterMilliseconds(60000);
        assertEquals(60000, config.getDownAfterMilliseconds());
        
        config.setFailoverTimeout(300000);
        assertEquals(300000, config.getFailoverTimeout());
        
        config.setParallelSyncs(2);
        assertEquals(2, config.getParallelSyncs());
    }
    
    @Test
    void testMasterConfig() {
        SentinelConfig config = new SentinelConfig();
        
        config.addMasterConfig("mymaster", "127.0.0.1", 6379, 2);
        
        SentinelConfig.MasterMonitorConfig masterConfig = config.getMasterConfig("mymaster");
        assertNotNull(masterConfig);
        assertEquals("mymaster", masterConfig.getName());
        assertEquals("127.0.0.1", masterConfig.getHost());
        assertEquals(6379, masterConfig.getPort());
        assertEquals(2, masterConfig.getQuorum());
    }
    
    @Test
    void testRemoveMasterConfig() {
        SentinelConfig config = new SentinelConfig();
        
        config.addMasterConfig("mymaster", "127.0.0.1", 6379, 2);
        assertNotNull(config.getMasterConfig("mymaster"));
        
        config.removeMasterConfig("mymaster");
        assertNull(config.getMasterConfig("mymaster"));
    }
    
    @Test
    void testMasterConfigSetters() {
        SentinelConfig config = new SentinelConfig();
        config.addMasterConfig("mymaster", "127.0.0.1", 6379, 2);
        
        SentinelConfig.MasterMonitorConfig masterConfig = config.getMasterConfig("mymaster");
        
        masterConfig.setDownAfterMilliseconds(45000);
        assertEquals(45000, masterConfig.getDownAfterMilliseconds());
        
        masterConfig.setFailoverTimeout(240000);
        assertEquals(240000, masterConfig.getFailoverTimeout());
        
        masterConfig.setParallelSyncs(3);
        assertEquals(3, masterConfig.getParallelSyncs());
    }
    
    @Test
    void testSentinelId() {
        SentinelConfig config = new SentinelConfig();
        
        config.setSentinelId("test-sentinel-id");
        assertEquals("test-sentinel-id", config.getSentinelId());
    }
    
    @Test
    void testAuthPassword() {
        SentinelConfig config = new SentinelConfig();
        
        config.setAuthPassword("mypassword");
        assertEquals("mypassword", config.getAuthPassword());
    }
}
