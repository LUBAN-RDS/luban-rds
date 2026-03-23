package com.janeluo.luban.rds.core.acl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACL 管理器
 * 
 * <p>负责管理所有 ACL 用户、权限检查和权限缓存。
 * 提供 Redis 7.2 ACL 规范兼容的完整 ACL 功能。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ACLManager.class);
    
    /**
     * 用户映射表
     */
    private final Map<String, ACLUser> users;
    
    /**
     * 权限检查器
     */
    private final ACLPermissionChecker permissionChecker;
    
    /**
     * 默认用户名
     */
    private static final String DEFAULT_USERNAME = "default";
    
    /**
     * ACL 审计日志记录器
     */
    private final ACLAuditLogger auditLogger;
    
    /**
     * 创建 ACL 管理器
     */
    public ACLManager() {
        this.users = new ConcurrentHashMap<>();
        this.permissionChecker = new ACLPermissionChecker();
        this.auditLogger = new ACLAuditLogger();
        
        // 创建默认用户
        initDefaultUser();
    }
    
    /**
     * 初始化默认用户
     */
    private void initDefaultUser() {
        ACLUser defaultUser = new ACLUser(DEFAULT_USERNAME);
        defaultUser.setEnabled(true);
        defaultUser.setNoPassword(true);
        defaultUser.addAllowedCommandCategory("@all");
        defaultUser.addKeyPattern("*");
        defaultUser.addChannelPattern("*");
        
        users.put(DEFAULT_USERNAME, defaultUser);
    }
    
    // ==================== 用户管理 ====================
    
    /**
     * 创建或更新用户
     *
     * @param username 用户名
     * @param rules 规则字符串
     * @return 创建的用户
     */
    public ACLUser setUser(String username, String rules) {
        ACLUser user = ACLUser.fromRules(username, rules);
        users.put(username, user);
        
        logger.info("User '{}' has been created/updated", username);
        return user;
    }
    
    /**
     * 获取用户
     *
     * @param username 用户名
     * @return 用户对象，如果不存在返回 null
     */
    public ACLUser getUser(String username) {
        return users.get(username);
    }
    
    /**
     * 删除用户
     *
     * @param username 用户名
     * @return 是否删除成功
     */
    public boolean deleteUser(String username) {
        if (DEFAULT_USERNAME.equals(username)) {
            logger.warn("Cannot delete default user");
            return false;
        }
        
        ACLUser removed = users.remove(username);
        if (removed != null) {
            logger.info("User '{}' has been deleted", username);
            return true;
        }
        return false;
    }
    
    /**
     * 获取所有用户名
     *
     * @return 用户名集合
     */
    public Set<String> getAllUsernames() {
        return Collections.unmodifiableSet(users.keySet());
    }
    
    /**
     * 获取所有用户
     *
     * @return 用户集合
     */
    public Collection<ACLUser> getAllUsers() {
        return Collections.unmodifiableCollection(users.values());
    }
    
    /**
     * 列出所有用户的规则
     *
     * @return 规则列表
     */
    public List<String> listUsers() {
        List<String> rules = new ArrayList<>();
        users.values().stream()
            .sorted(Comparator.comparing(ACLUser::getUsername))
            .forEach(user -> rules.add(user.toRuleString()));
        return rules;
    }
    
    /**
     * 验证用户密码
     *
     * @param username 用户名
     * @param password 密码
     * @return 是否验证成功
     */
    public boolean authenticate(String username, String password) {
        ACLUser user = users.get(username);
        if (user == null || !user.isEnabled()) {
            auditLogger.logAuthFailure(username, "user not found or disabled");
            return false;
        }
        
        boolean valid = user.validatePassword(password);
        if (valid) {
            auditLogger.logAuthSuccess(username);
        } else {
            auditLogger.logAuthFailure(username, "invalid password");
        }
        
        return valid;
    }
    
    // ==================== 权限检查 ====================
    
    /**
     * 检查命令权限
     *
     * @param username 用户名
     * @param command 命令名称
     * @param subcommands 子命令参数
     * @return 是否有权限
     */
    public boolean checkCommandPermission(String username, String command, List<String> subcommands) {
        ACLUser user = users.get(username);
        if (user == null) {
            user = users.get(DEFAULT_USERNAME);
        }
        
        boolean allowed = permissionChecker.checkCommand(user, command, subcommands);
        
        if (!allowed) {
            auditLogger.logPermissionDenied(username, "command", command);
        }
        
        return allowed;
    }
    
    /**
     * 检查键权限
     *
     * @param username 用户名
     * @param key 键名
     * @param accessType 访问类型
     * @return 是否有权限
     */
    public boolean checkKeyPermission(String username, String key, ACLPermissionChecker.KeyAccessType accessType) {
        ACLUser user = users.get(username);
        if (user == null) {
            user = users.get(DEFAULT_USERNAME);
        }
        
        boolean allowed = permissionChecker.checkKey(user, key, accessType);
        
        if (!allowed) {
            auditLogger.logPermissionDenied(username, "key", key);
        }
        
        return allowed;
    }
    
    /**
     * 检查频道权限
     *
     * @param username 用户名
     * @param channel 频道名
     * @param accessType 访问类型
     * @return 是否有权限
     */
    public boolean checkChannelPermission(String username, String channel, ACLPermissionChecker.ChannelAccessType accessType) {
        ACLUser user = users.get(username);
        if (user == null) {
            user = users.get(DEFAULT_USERNAME);
        }
        
        boolean allowed = permissionChecker.checkChannel(user, channel, accessType);
        
        if (!allowed) {
            auditLogger.logPermissionDenied(username, "channel", channel);
        }
        
        return allowed;
    }
    
    /**
     * 综合权限检查
     *
     * @param username 用户名
     * @param command 命令名称
     * @param subcommands 子命令参数
     * @param keys 键列表
     * @param keyAccessType 键访问类型
     * @return 是否有权限
     */
    public boolean checkPermission(String username, String command, List<String> subcommands,
                                   List<String> keys, ACLPermissionChecker.KeyAccessType keyAccessType) {
        ACLUser user = users.get(username);
        if (user == null) {
            user = users.get(DEFAULT_USERNAME);
        }
        
        boolean allowed = permissionChecker.checkPermission(user, command, subcommands, keys, keyAccessType);
        
        if (!allowed) {
            auditLogger.logPermissionDenied(username, "command with keys", command + " " + keys);
        }
        
        return allowed;
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 生成强密码
     *
     * @return 生成的密码（64 位十六进制字符串）
     */
    public String generatePassword() {
        return generatePassword(64);
    }
    
    /**
     * 生成强密码
     *
     * @param bits 位数（必须是 8 的倍数）
     * @return 生成的密码（十六进制字符串）
     */
    public String generatePassword(int bits) {
        if (bits % 8 != 0) {
            bits = ((bits / 8) + 1) * 8;
        }
        
        byte[] randomBytes = new byte[bits / 8];
        new java.security.SecureRandom().nextBytes(randomBytes);
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : randomBytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        
        return hexString.toString();
    }
    
    /**
     * 获取命令类别列表
     *
     * @return 类别列表
     */
    public Set<String> getCommandCategories() {
        return ACLCommandCategories.getAllCategories();
    }
    
    /**
     * 获取类别中的命令列表
     *
     * @param category 类别名称
     * @return 命令列表
     */
    public Set<String> getCategoryCommands(String category) {
        return ACLCommandCategories.getCategoryCommands(category);
    }
    
    /**
     * 获取审计日志记录器
     *
     * @return 审计日志记录器
     */
    public ACLAuditLogger getAuditLogger() {
        return auditLogger;
    }
}
