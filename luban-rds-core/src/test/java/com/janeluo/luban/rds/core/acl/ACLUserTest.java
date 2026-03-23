package com.janeluo.luban.rds.core.acl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACLUser 测试类
 * 测试用户的创建、权限规则解析和管理
 */
@DisplayName("ACLUser 用户管理测试")
class ACLUserTest {

    private ACLUser user;

    @BeforeEach
    void setUp() {
        user = new ACLUser("testuser");
    }

    @Test
    @DisplayName("创建用户 - 默认状态")
    void testCreateUserDefault() {
        ACLUser defaultUser = new ACLUser("default");
        
        assertEquals("default", defaultUser.getUsername());
        assertFalse(defaultUser.isEnabled());
        assertFalse(defaultUser.isNoPassword());
        assertTrue(defaultUser.getPasswords().isEmpty());
        assertTrue(defaultUser.getAllowedCommands().isEmpty());
        assertTrue(defaultUser.getDeniedCommands().isEmpty());
        assertTrue(defaultUser.getKeyPatterns().isEmpty());
    }

    @Test
    @DisplayName("启用/禁用用户")
    void testEnableDisable() {
        user.setEnabled(true);
        assertTrue(user.isEnabled());
        
        user.setEnabled(false);
        assertFalse(user.isEnabled());
    }

    @Test
    @DisplayName("设置密码")
    void testAddPassword() {
        user.addPassword("mypassword");
        
        assertFalse(user.isNoPassword());
        assertEquals(1, user.getPasswords().size());
        assertTrue(user.getPasswords().contains("mypassword"));
    }

    @Test
    @DisplayName("设置多个密码")
    void testAddMultiplePasswords() {
        user.addPassword("password1");
        user.addPassword("password2");
        
        assertEquals(2, user.getPasswords().size());
        assertTrue(user.getPasswords().contains("password1"));
        assertTrue(user.getPasswords().contains("password2"));
    }

    @Test
    @DisplayName("移除密码")
    void testRemovePassword() {
        user.addPassword("password1");
        user.addPassword("password2");
        
        user.removePassword("password1");
        
        assertEquals(1, user.getPasswords().size());
        assertFalse(user.getPasswords().contains("password1"));
        assertTrue(user.getPasswords().contains("password2"));
    }

    @Test
    @DisplayName("设置 nopass")
    void testNoPassword() {
        user.setNoPassword(true);
        
        assertTrue(user.isNoPassword());
        assertTrue(user.getPasswords().isEmpty());
    }

    @Test
    @DisplayName("添加允许的命令")
    void testAddAllowedCommand() {
        user.addAllowedCommand("GET");
        user.addAllowedCommand("SET");
        
        Set<String> allowed = user.getAllowedCommands();
        assertTrue(allowed.contains("GET"));
        assertTrue(allowed.contains("SET"));
        assertEquals(2, allowed.size());
    }

    @Test
    @DisplayName("添加拒绝的命令")
    void testAddDeniedCommand() {
        user.addDeniedCommand("FLUSHALL");
        user.addDeniedCommand("FLUSHDB");
        
        Set<String> denied = user.getDeniedCommands();
        assertTrue(denied.contains("FLUSHALL"));
        assertTrue(denied.contains("FLUSHDB"));
        assertEquals(2, denied.size());
    }

    @Test
    @DisplayName("添加命令类别")
    void testAddCommandCategory() {
        user.addAllowedCommandCategory("@read");
        user.addDeniedCommandCategory("@dangerous");
        
        assertTrue(user.getAllowedCommandCategories().contains("@read"));
        assertTrue(user.getDeniedCommandCategories().contains("@dangerous"));
    }

    @Test
    @DisplayName("添加键模式 - 读写权限")
    void testAddKeyPattern() {
        user.addKeyPattern("*");
        user.addKeyPattern("cache:*");
        user.addKeyPattern("user:*");
        
        assertEquals(3, user.getKeyPatterns().size());
        assertTrue(user.getKeyPatterns().contains("*"));
        assertTrue(user.getKeyPatterns().contains("cache:*"));
    }

    @Test
    @DisplayName("添加键模式 - 只读权限")
    void testAddKeyPatternReadOnly() {
        user.addKeyPatternReadOnly("readonly:*");
        
        assertTrue(user.getReadOnlyKeyPatterns().contains("readonly:*"));
        assertFalse(user.getKeyPatterns().contains("readonly:*"));
    }

    @Test
    @DisplayName("添加键模式 - 只写权限")
    void testAddKeyPatternWriteOnly() {
        user.addKeyPatternWriteOnly("writeonly:*");
        
        assertTrue(user.getWriteOnlyKeyPatterns().contains("writeonly:*"));
        assertFalse(user.getKeyPatterns().contains("writeonly:*"));
    }

    @Test
    @DisplayName("添加 Pub/Sub 频道模式")
    void testAddChannelPattern() {
        user.addChannelPattern("news:*");
        user.addChannelPattern("alerts:*");
        
        assertEquals(2, user.getChannelPatterns().size());
        assertTrue(user.getChannelPatterns().contains("news:*"));
    }

    @Test
    @DisplayName("重置键模式")
    void testResetKeys() {
        user.addKeyPattern("*");
        user.addKeyPattern("cache:*");
        
        user.resetKeys();
        
        assertTrue(user.getKeyPatterns().isEmpty());
    }

    @Test
    @DisplayName("重置所有密码")
    void testResetPassword() {
        user.addPassword("password1");
        user.addPassword("password2");
        
        user.resetPassword();
        
        assertTrue(user.getPasswords().isEmpty());
        assertFalse(user.isNoPassword());
    }

    @Test
    @DisplayName("验证密码 - 成功")
    void testValidatePasswordSuccess() {
        user.addPassword("mypassword");
        
        assertTrue(user.validatePassword("mypassword"));
    }

    @Test
    @DisplayName("验证密码 - 失败")
    void testValidatePasswordFailure() {
        user.addPassword("mypassword");
        
        assertFalse(user.validatePassword("wrongpassword"));
    }

    @Test
    @DisplayName("验证密码 - nopass 模式")
    void testValidatePasswordNoPass() {
        user.setNoPassword(true);
        
        assertTrue(user.validatePassword(""));
        assertTrue(user.validatePassword(null));
    }

    @Test
    @DisplayName("重置用户到初始状态")
    void testReset() {
        user.setEnabled(true);
        user.addPassword("password");
        user.addAllowedCommand("GET");
        user.addKeyPattern("*");
        
        user.reset();
        
        assertEquals("testuser", user.getUsername());
        assertFalse(user.isEnabled());
        assertTrue(user.getPasswords().isEmpty());
        assertFalse(user.isNoPassword());
        assertTrue(user.getAllowedCommands().isEmpty());
        assertTrue(user.getKeyPatterns().isEmpty());
    }

    @Test
    @DisplayName("从规则字符串解析 - 基本规则")
    void testParseFromRulesBasic() {
        ACLUser parsedUser = ACLUser.fromRules("admin", "on >adminpass ~* +@all");
        
        assertEquals("admin", parsedUser.getUsername());
        assertTrue(parsedUser.isEnabled());
        assertTrue(parsedUser.getPasswords().contains("adminpass"));
        assertTrue(parsedUser.getKeyPatterns().contains("*"));
        assertTrue(parsedUser.getAllowedCommandCategories().contains("@all"));
    }

    @Test
    @DisplayName("从规则字符串解析 - 复杂规则")
    void testParseFromRulesComplex() {
        ACLUser parsedUser = ACLUser.fromRules("readonly", 
            "on >readonlypass ~cache:* ~user:* +@read -@dangerous +info");
        
        assertEquals("readonly", parsedUser.getUsername());
        assertTrue(parsedUser.isEnabled());
        assertTrue(parsedUser.getPasswords().contains("readonlypass"));
        assertTrue(parsedUser.getKeyPatterns().contains("cache:*"));
        assertTrue(parsedUser.getKeyPatterns().contains("user:*"));
        assertTrue(parsedUser.getAllowedCommandCategories().contains("@read"));
        assertTrue(parsedUser.getDeniedCommandCategories().contains("@dangerous"));
        assertTrue(parsedUser.getAllowedCommands().contains("INFO"));
    }

    @Test
    @DisplayName("从规则字符串解析 - nopass 用户")
    void testParseFromRulesNoPass() {
        ACLUser parsedUser = ACLUser.fromRules("anonymous", "on nopass ~* +@read");
        
        assertTrue(parsedUser.isEnabled());
        assertTrue(parsedUser.isNoPassword());
        assertTrue(parsedUser.getKeyPatterns().contains("*"));
    }

    @Test
    @DisplayName("转换为规则字符串")
    void testToRuleString() {
        user.setEnabled(true);
        user.addPassword("mypass");
        user.addKeyPattern("*");
        user.addAllowedCommandCategory("@all");
        
        String ruleString = user.toRuleString();
        
        assertTrue(ruleString.contains("user testuser"));
        assertTrue(ruleString.contains("on"));
        assertTrue(ruleString.contains(">mypass"));
        assertTrue(ruleString.contains("~*"));
        assertTrue(ruleString.contains("+@all"));
    }

    @Test
    @DisplayName("克隆用户")
    void testClone() {
        user.setEnabled(true);
        user.addPassword("password");
        user.addAllowedCommand("GET");
        user.addKeyPattern("*");
        
        ACLUser cloned = user.clone();
        
        assertEquals(user.getUsername(), cloned.getUsername());
        assertEquals(user.isEnabled(), cloned.isEnabled());
        assertEquals(user.getPasswords(), cloned.getPasswords());
        assertEquals(user.getAllowedCommands(), cloned.getAllowedCommands());
        assertEquals(user.getKeyPatterns(), cloned.getKeyPatterns());
        
        // 修改克隆不影响原对象
        cloned.addAllowedCommand("SET");
        assertFalse(user.getAllowedCommands().contains("SET"));
    }
}
