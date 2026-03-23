package com.janeluo.luban.rds.acl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACLPermissionChecker 测试类
 * 测试命令权限、键权限和模式匹配功能
 */
@DisplayName("ACLPermissionChecker 权限检查测试")
class ACLPermissionCheckerTest {

    private ACLUser user;
    private ACLPermissionChecker checker;

    @BeforeEach
    void setUp() {
        user = new ACLUser("testuser");
        checker = new ACLPermissionChecker();
    }

    // ==================== 命令权限测试 ====================

    @Test
    @DisplayName("命令权限检查 - 允许所有命令")
    void testCheckCommandAllowedAll() {
        user.addAllowedCommandCategory("@all");
        
        assertTrue(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "SET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "FLUSHALL", Collections.emptyList()));
    }

    @Test
    @DisplayName("命令权限检查 - 拒绝所有命令")
    void testCheckCommandDeniedAll() {
        user.addDeniedCommandCategory("@all");
        
        assertFalse(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "SET", Collections.emptyList()));
    }

    @Test
    @DisplayName("命令权限检查 - 允许特定命令")
    void testCheckCommandAllowedSpecific() {
        user.addAllowedCommand("GET");
        user.addAllowedCommand("SET");
        
        assertTrue(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "SET", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "DEL", Collections.emptyList()));
    }

    @Test
    @DisplayName("命令权限检查 - 拒绝特定命令")
    void testCheckCommandDeniedSpecific() {
        user.addAllowedCommandCategory("@all");
        user.addDeniedCommand("FLUSHALL");
        user.addDeniedCommand("FLUSHDB");
        
        assertTrue(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "SET", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "FLUSHALL", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "FLUSHDB", Collections.emptyList()));
    }

    @Test
    @DisplayName("命令权限检查 - 命令类别")
    void testCheckCommandCategory() {
        user.addAllowedCommandCategory("@read");
        
        assertTrue(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "MGET", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "SET", Collections.emptyList()));
        assertFalse(checker.checkCommand(user, "DEL", Collections.emptyList()));
    }

    @Test
    @DisplayName("命令权限检查 - 子命令权限")
    void testCheckCommandSubcommand() {
        user.addAllowedCommand("CONFIG");
        user.addAllowedSubcommand("CONFIG", "GET");
        
        assertTrue(checker.checkCommand(user, "CONFIG", Arrays.asList("GET", "maxmemory")));
        assertFalse(checker.checkCommand(user, "CONFIG", Arrays.asList("SET", "maxmemory", "100mb")));
    }

    @Test
    @DisplayName("命令权限检查 - 大小写不敏感")
    void testCheckCommandCaseInsensitive() {
        user.addAllowedCommand("get");
        
        assertTrue(checker.checkCommand(user, "GET", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "get", Collections.emptyList()));
        assertTrue(checker.checkCommand(user, "Get", Collections.emptyList()));
    }

    // ==================== 键权限测试 ====================

    @Test
    @DisplayName("键权限检查 - 允许所有键")
    void testCheckKeyAllowedAll() {
        user.addKeyPattern("*");
        
        assertTrue(checker.checkKey(user, "anykey", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "cache:user:123", ACLPermissionChecker.KeyAccessType.WRITE));
    }

    @Test
    @DisplayName("键权限检查 - 精确键名")
    void testCheckKeyExact() {
        user.addKeyPattern("mykey");
        
        assertTrue(checker.checkKey(user, "mykey", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "mykey2", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "mykey:sub", ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("键权限检查 - 通配符模式")
    void testCheckKeyPattern() {
        user.addKeyPattern("cache:*");
        user.addKeyPattern("user:*");
        
        assertTrue(checker.checkKey(user, "cache:123", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "cache:user:456", ACLPermissionChecker.KeyAccessType.WRITE));
        assertTrue(checker.checkKey(user, "user:789", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "session:abc", ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("键权限检查 - 多层通配符")
    void testCheckKeyPatternMultiLevel() {
        user.addKeyPattern("app:*:data");
        
        assertTrue(checker.checkKey(user, "app:user:data", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "app:cache:data", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "app:user:profile", ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("键权限检查 - 只读键")
    void testCheckKeyReadOnly() {
        user.addKeyPatternReadOnly("readonly:*");
        
        assertTrue(checker.checkKey(user, "readonly:data", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "readonly:data", ACLPermissionChecker.KeyAccessType.WRITE));
    }

    @Test
    @DisplayName("键权限检查 - 只写键")
    void testCheckKeyWriteOnly() {
        user.addKeyPatternWriteOnly("writeonly:*");
        
        assertFalse(checker.checkKey(user, "writeonly:data", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "writeonly:data", ACLPermissionChecker.KeyAccessType.WRITE));
    }

    @Test
    @DisplayName("键权限检查 - 混合读写权限")
    void testCheckKeyMixedPermissions() {
        user.addKeyPattern("*"); // 允许所有键读写
        user.addKeyPatternReadOnly("public:*"); // public:* 只读
        user.addKeyPatternWriteOnly("private:*"); // private:* 只写
        
        // public 键只能读
        assertTrue(checker.checkKey(user, "public:data", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "public:data", ACLPermissionChecker.KeyAccessType.WRITE));
        
        // private 键只能写
        assertFalse(checker.checkKey(user, "private:secret", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "private:secret", ACLPermissionChecker.KeyAccessType.WRITE));
        
        // 其他键可读写
        assertTrue(checker.checkKey(user, "other:key", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "other:key", ACLPermissionChecker.KeyAccessType.WRITE));
    }

    @Test
    @DisplayName("键权限检查 - 空键模式列表")
    void testCheckKeyEmptyPatterns() {
        // 没有键模式时，默认拒绝所有键
        assertFalse(checker.checkKey(user, "anykey", ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("键权限检查 - 多个模式匹配")
    void testCheckKeyMultiplePatterns() {
        user.addKeyPattern("cache:*");
        user.addKeyPattern("user:*");
        user.addKeyPattern("session:*");
        
        assertTrue(checker.checkKey(user, "cache:123", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "user:456", ACLPermissionChecker.KeyAccessType.READ));
        assertTrue(checker.checkKey(user, "session:abc", ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(checker.checkKey(user, "temp:xyz", ACLPermissionChecker.KeyAccessType.READ));
    }

    // ==================== 综合权限测试 ====================

    @Test
    @DisplayName("综合权限检查 - 命令和键都允许")
    void testCheckPermissionAllowed() {
        user.addAllowedCommandCategory("@all");
        user.addKeyPattern("*");
        
        assertTrue(checker.checkPermission(user, "GET", Collections.emptyList(), 
            Arrays.asList("mykey"), ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("综合权限检查 - 命令拒绝")
    void testCheckPermissionCommandDenied() {
        user.addDeniedCommand("GET");
        user.addKeyPattern("*");
        
        assertFalse(checker.checkPermission(user, "GET", Collections.emptyList(), 
            Arrays.asList("mykey"), ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("综合权限检查 - 键拒绝")
    void testCheckPermissionKeyDenied() {
        user.addAllowedCommandCategory("@all");
        user.addKeyPattern("allowed:*");
        
        assertFalse(checker.checkPermission(user, "GET", Collections.emptyList(), 
            Arrays.asList("denied:key"), ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("综合权限检查 - 多键操作")
    void testCheckPermissionMultipleKeys() {
        user.addAllowedCommandCategory("@all");
        user.addKeyPattern("allowed:*");
        
        // 所有键都允许
        assertTrue(checker.checkPermission(user, "MGET", Collections.emptyList(), 
            Arrays.asList("allowed:key1", "allowed:key2"), ACLPermissionChecker.KeyAccessType.READ));
        
        // 有一个键不允许，整体拒绝
        assertFalse(checker.checkPermission(user, "MGET", Collections.emptyList(), 
            Arrays.asList("allowed:key1", "denied:key2"), ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("综合权限检查 - 无键命令")
    void testCheckPermissionNoKeyCommand() {
        user.addAllowedCommand("INFO");
        
        // INFO 命令不需要键权限检查
        assertTrue(checker.checkPermission(user, "INFO", Collections.emptyList(), 
            Collections.emptyList(), ACLPermissionChecker.KeyAccessType.READ));
    }

    // ==================== Pub/Sub 频道权限测试 ====================

    @Test
    @DisplayName("频道权限检查 - 允许所有频道")
    void testCheckChannelAllowedAll() {
        user.addChannelPattern("*");
        
        assertTrue(checker.checkChannel(user, "news", ACLPermissionChecker.ChannelAccessType.SUBSCRIBE));
        assertTrue(checker.checkChannel(user, "alerts:critical", ACLPermissionChecker.ChannelAccessType.PUBLISH));
    }

    @Test
    @DisplayName("频道权限检查 - 模式匹配")
    void testCheckChannelPattern() {
        user.addChannelPattern("news:*");
        user.addChannelPattern("alerts:*");
        
        assertTrue(checker.checkChannel(user, "news:sports", ACLPermissionChecker.ChannelAccessType.SUBSCRIBE));
        assertTrue(checker.checkChannel(user, "alerts:critical", ACLPermissionChecker.ChannelAccessType.PUBLISH));
        assertFalse(checker.checkChannel(user, "updates", ACLPermissionChecker.ChannelAccessType.SUBSCRIBE));
    }

    @Test
    @DisplayName("频道权限检查 - 无权限")
    void testCheckChannelNoPermission() {
        // 没有设置任何频道模式
        assertFalse(checker.checkChannel(user, "anychannel", ACLPermissionChecker.ChannelAccessType.SUBSCRIBE));
    }

    // ==================== 性能相关测试 ====================

    @Test
    @DisplayName("性能测试 - 大量模式匹配")
    void testPerformanceManyPatterns() {
        // 添加大量模式
        for (int i = 0; i < 100; i++) {
            user.addKeyPattern("pattern" + i + ":*");
        }
        
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            checker.checkKey(user, "pattern50:test", ACLPermissionChecker.KeyAccessType.READ);
        }
        long elapsed = System.currentTimeMillis() - start;
        
        // 1000 次匹配应该在 100ms 内完成
        assertTrue(elapsed < 100, "Pattern matching took too long: " + elapsed + "ms");
    }
}
