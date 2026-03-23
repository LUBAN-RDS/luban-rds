package com.janeluo.luban.rds.core.acl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACL 集成测试
 * 测试 ACL 系统的核心功能
 */
@DisplayName("ACL 系统集成测试")
class ACLIntegrationTest {

    private ACLManager aclManager;

    @BeforeEach
    void setUp() {
        aclManager = new ACLManager();
    }

    @Test
    @DisplayName("默认用户应该存在且拥有所有权限")
    void testDefaultUser() {
        ACLUser defaultUser = aclManager.getUser("default");
        
        assertNotNull(defaultUser);
        assertEquals("default", defaultUser.getUsername());
        assertTrue(defaultUser.isEnabled());
        assertTrue(defaultUser.isNoPassword());
        assertTrue(defaultUser.getAllowedCommandCategories().contains("@all"));
        assertTrue(defaultUser.getKeyPatterns().contains("*"));
    }

    @Test
    @DisplayName("创建用户并验证密码")
    void testCreateUserAndPassword() {
        aclManager.setUser("testuser", "on >testpass123 ~* +@all");
        
        ACLUser user = aclManager.getUser("testuser");
        assertNotNull(user);
        assertEquals("testuser", user.getUsername());
        assertTrue(user.isEnabled());
        assertTrue(user.validatePassword("testpass123"));
        assertFalse(user.validatePassword("wrongpass"));
    }

    @Test
    @DisplayName("创建只读用户")
    void testCreateReadOnlyUser() {
        aclManager.setUser("readonly", "on >readonlypass ~cache:* +@read +info");
        
        ACLUser user = aclManager.getUser("readonly");
        assertNotNull(user);
        
        // 验证权限
        assertTrue(aclManager.checkCommandPermission("readonly", "GET", Collections.emptyList()));
        assertTrue(aclManager.checkCommandPermission("readonly", "INFO", Collections.emptyList()));
        assertFalse(aclManager.checkCommandPermission("readonly", "SET", Collections.emptyList()));
        
        // 验证键权限
        assertTrue(aclManager.checkKeyPermission("readonly", "cache:123", 
            ACLPermissionChecker.KeyAccessType.READ));
        assertFalse(aclManager.checkKeyPermission("readonly", "user:123", 
            ACLPermissionChecker.KeyAccessType.READ));
    }

    @Test
    @DisplayName("删除用户")
    void testDeleteUser() {
        aclManager.setUser("tempuser", "on >temppass ~* +@all");
        
        assertNotNull(aclManager.getUser("tempuser"));
        
        assertTrue(aclManager.deleteUser("tempuser"));
        assertNull(aclManager.getUser("tempuser"));
        
        // 不能删除 default 用户
        assertFalse(aclManager.deleteUser("default"));
    }

    @Test
    @DisplayName("列出所有用户")
    void testListUsers() {
        aclManager.setUser("user1", "on >pass1 ~* +@read");
        aclManager.setUser("user2", "on >pass2 ~* +@write");
        
        List<String> users = aclManager.listUsers();
        
        assertTrue(users.size() >= 3); // default + user1 + user2
        assertTrue(users.stream().anyMatch(u -> u.contains("default")));
        assertTrue(users.stream().anyMatch(u -> u.contains("user1")));
        assertTrue(users.stream().anyMatch(u -> u.contains("user2")));
    }

    @Test
    @DisplayName("生成密码")
    void testGeneratePassword() {
        String password1 = aclManager.generatePassword();
        String password2 = aclManager.generatePassword(128);
        
        assertNotNull(password1);
        assertEquals(64, password1.length()); // 256 bits = 64 hex chars
        
        assertNotNull(password2);
        assertEquals(32, password2.length()); // 128 bits = 32 hex chars
        
        // 两次生成的密码应该不同
        assertNotEquals(password1, aclManager.generatePassword());
    }

    @Test
    @DisplayName("获取命令类别")
    void testGetCommandCategories() {
        var categories = aclManager.getCommandCategories();
        
        assertNotNull(categories);
        assertTrue(categories.contains("@read"));
        assertTrue(categories.contains("@write"));
        assertTrue(categories.contains("@admin"));
        assertTrue(categories.contains("@dangerous"));
    }

    @Test
    @DisplayName("获取类别中的命令")
    void testGetCategoryCommands() {
        var readCommands = aclManager.getCategoryCommands("@read");
        
        assertNotNull(readCommands);
        assertTrue(readCommands.contains("GET"));
        assertTrue(readCommands.contains("HGET"));
        
        var dangerousCommands = aclManager.getCategoryCommands("@dangerous");
        assertTrue(dangerousCommands.contains("FLUSHALL"));
        assertTrue(dangerousCommands.contains("KEYS"));
    }

    @Test
    @DisplayName("审计日志记录")
    void testAuditLog() {
        aclManager.setUser("audituser", "on >auditpass ~* +@all");
        
        // 认证成功
        aclManager.authenticate("audituser", "auditpass");
        
        // 认证失败
        aclManager.authenticate("audituser", "wrongpass");
        
        // 权限拒绝
        aclManager.checkCommandPermission("audituser", "FLUSHALL", Collections.emptyList());
        
        var events = aclManager.getAuditLogger().getAllEvents();
        assertTrue(events.size() > 0);
    }

    @Test
    @DisplayName("综合权限检查")
    void testComprehensivePermissionCheck() {
        // 创建受限用户
        aclManager.setUser("limited", "on >limitedpass ~cache:* +@read -keys +info");
        
        // 允许的操作
        assertTrue(aclManager.checkPermission("limited", "GET", Collections.emptyList(),
            List.of("cache:123"), ACLPermissionChecker.KeyAccessType.READ));
        
        // 拒绝的命令
        assertFalse(aclManager.checkPermission("limited", "SET", Collections.emptyList(),
            List.of("cache:123"), ACLPermissionChecker.KeyAccessType.WRITE));
        
        // 拒绝的键
        assertFalse(aclManager.checkPermission("limited", "GET", Collections.emptyList(),
            List.of("user:123"), ACLPermissionChecker.KeyAccessType.READ));
        
        // KEYS 命令被拒绝
        assertFalse(aclManager.checkCommandPermission("limited", "KEYS", Collections.emptyList()));
        
        // INFO 命令被允许
        assertTrue(aclManager.checkCommandPermission("limited", "INFO", Collections.emptyList()));
    }

    @Test
    @DisplayName("nopass 用户认证")
    void testNoPassUser() {
        aclManager.setUser("anonymous", "on nopass ~* +@read");
        
        ACLUser user = aclManager.getUser("anonymous");
        assertTrue(user.isNoPassword());
        assertTrue(user.validatePassword(""));
        assertTrue(user.validatePassword(null));
        assertTrue(user.validatePassword("anything"));
    }
}
