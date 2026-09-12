package com.janeluo.luban.rds.common.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

/**
 * Task 13：follower 读配置链（mesh-read-from-follower / -max-wait-ms / -cache-ms）。
 * <p>本模块测试依赖为 JUnit 4，故用 {@link TemporaryFolder} 而非 Jupiter 的 {@code @TempDir}。</p>
 */
public class MeshFollowerReadConfigTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private RdsConfig load(String extra) throws Exception {
        File conf = folder.newFile("luban-rds.conf");
        String content = """
                port 9736
                mesh-enabled yes
                mesh-peers nodeA@127.0.0.1:19736,nodeB@127.0.0.1:19737,nodeC@127.0.0.1:19738
                """ + extra;
        Files.writeString(conf.toPath(), content, StandardCharsets.UTF_8);
        return ConfigLoader.load(conf.getAbsolutePath());
    }

    @Test
    public void defaults_off_andCache100() throws Exception {
        RdsConfig c = load("");
        assertEquals("off", c.getMeshReadFromFollower());
        assertEquals(500, c.getMeshFollowerReadMaxWaitMs());
        assertEquals(100, c.getMeshFollowerReadCacheMs());
    }

    @Test
    public void parsesExplicitValues() throws Exception {
        RdsConfig c = load("""
                mesh-read-from-follower readindex
                mesh-follower-read-max-wait-ms 800
                mesh-follower-read-cache-ms 0
                """);
        assertEquals("readindex", c.getMeshReadFromFollower());
        assertEquals(800, c.getMeshFollowerReadMaxWaitMs());
        assertEquals(0, c.getMeshFollowerReadCacheMs());
    }

    @Test
    public void modeIsCaseInsensitive() throws Exception {
        RdsConfig c = load("mesh-read-from-follower ReadIndex\n");
        assertEquals("readindex", c.getMeshReadFromFollower());
    }

    @Test
    public void invalidMode_fallsBackToOff() throws Exception {
        RdsConfig c = load("mesh-read-from-follower nonsense\n");
        assertEquals("非法模式必须回退 off（不得静默当开启）", "off", c.getMeshReadFromFollower());
    }

    @Test
    public void nonPositiveMaxWait_fallsBackToDefault() throws Exception {
        RdsConfig c = load("mesh-follower-read-max-wait-ms 0\n");
        assertEquals(500, c.getMeshFollowerReadMaxWaitMs());
    }

    @Test
    public void negativeCacheMs_fallsBackToDefault() throws Exception {
        RdsConfig c = load("mesh-follower-read-cache-ms -5\n");
        assertEquals("负数窗口非法，回退默认", 100L, c.getMeshFollowerReadCacheMs());
    }
}
