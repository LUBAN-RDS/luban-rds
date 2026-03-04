package com.janeluo.luban.rds.common.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigLoaderTest {

    @Test
    public void testLoadFromNonExistentFile() {
        // 测试从不存在的文件加载配置
        RdsConfig config = ConfigLoader.load("non-existent-config.conf");
        assertNotNull(config);
        assertEquals(9736, config.getPort());
        assertEquals("0.0.0.0", config.getBind());
    }

    @Test
    public void testLoadFromClasspath() {
        // 测试从类路径加载配置
        RdsConfig config = ConfigLoader.loadFromClasspath("luban-rds.conf");
        assertNotNull(config);
        // 即使文件不存在，也应该返回默认配置
        assertEquals(9736, config.getPort());
    }

    @Test
    public void testDefaultConfigValues() {
        // 测试默认配置值
        RdsConfig config = new RdsConfig();
        assertNotNull(config);
        
        // 网络配置
        assertEquals("0.0.0.0", config.getBind());
        assertEquals(9736, config.getPort());
        assertEquals(511, config.getTcpBacklog());
        assertEquals(0, config.getTimeout());
        assertEquals(300, config.getTcpKeepalive());
        
        // 通用配置
        assertFalse(config.isDaemonize());
        assertEquals("notice", config.getLoglevel());
        assertEquals("", config.getLogfile());
        assertEquals(16, config.getDatabases());
        
        // 持久化配置
        assertEquals("rdb", config.getPersistMode());
        assertEquals("./data", config.getDir());
        assertEquals("dump.rdb", config.getDbfilename());
        assertEquals(60, config.getRdbSaveInterval());
        assertEquals("appendonly.aof", config.getAppendfilename());
        assertEquals("everysec", config.getAppendfsync());
        assertEquals(1, config.getAofFsyncInterval());
        
        // 内存管理
        assertEquals(0, config.getMaxmemory());
        assertEquals("noeviction", config.getMaxmemoryPolicy());
        
        // 安全配置
        assertEquals("", config.getRequirepass());
    }
}
