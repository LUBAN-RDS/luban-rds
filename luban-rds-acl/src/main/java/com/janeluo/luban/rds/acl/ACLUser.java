package com.janeluo.luban.rds.acl;

import java.util.HashSet;
import java.util.Set;
import java.util.Collections;

/**
 * ACL 用户实体类
 * 
 * <p>表示 Redis ACL 系统中的一个用户，包含用户的所有权限规则。
 * 遵循 Redis 7.2 ACL 规范。
 * 
 * <p>权限规则包括：
 * <ul>
 *   <li>用户状态（enabled/disabled）</li>
 *   <li>密码（明文或 SHA-256 哈希）</li>
 *   <li>命令权限（允许/拒绝特定命令或命令类别）</li>
 *   <li>键模式权限（读/写/读写）</li>
 *   <li>Pub/Sub 频道权限</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLUser implements Cloneable {
    
    /**
     * 用户名
     */
    private final String username;
    
    /**
     * 用户是否启用
     */
    private boolean enabled;
    
    /**
     * 是否无需密码（nopass）
     */
    private boolean noPassword;
    
    /**
     * 用户密码列表（明文密码）
     */
    private final Set<String> passwords;
    
    /**
     * 用户密码哈希列表（SHA-256）
     */
    private final Set<String> passwordHashes;
    
    /**
     * 允许的命令集合
     */
    private final Set<String> allowedCommands;
    
    /**
     * 拒绝的命令集合
     */
    private final Set<String> deniedCommands;
    
    /**
     * 允许的命令类别集合
     */
    private final Set<String> allowedCommandCategories;
    
    /**
     * 拒绝的命令类别集合
     */
    private final Set<String> deniedCommandCategories;
    
    /**
     * 允许的子命令映射（命令 -> 子命令集合）
     */
    private final java.util.Map<String, Set<String>> allowedSubcommands;
    
    /**
     * 键模式列表（读写权限）
     */
    private final Set<String> keyPatterns;
    
    /**
     * 只读键模式列表
     */
    private final Set<String> readOnlyKeyPatterns;
    
    /**
     * 只写键模式列表
     */
    private final Set<String> writeOnlyKeyPatterns;
    
    /**
     * Pub/Sub 频道模式列表
     */
    private final Set<String> channelPatterns;
    
    /**
     * 创建用户
     *
     * @param username 用户名
     */
    public ACLUser(String username) {
        this.username = username;
        this.enabled = false;
        this.noPassword = false;
        this.passwords = new HashSet<>();
        this.passwordHashes = new HashSet<>();
        this.allowedCommands = new HashSet<>();
        this.deniedCommands = new HashSet<>();
        this.allowedCommandCategories = new HashSet<>();
        this.deniedCommandCategories = new HashSet<>();
        this.allowedSubcommands = new java.util.HashMap<>();
        this.keyPatterns = new HashSet<>();
        this.readOnlyKeyPatterns = new HashSet<>();
        this.writeOnlyKeyPatterns = new HashSet<>();
        this.channelPatterns = new HashSet<>();
    }
    
    // ==================== Getters ====================
    
    public String getUsername() {
        return username;
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public boolean isNoPassword() {
        return noPassword;
    }
    
    public Set<String> getPasswords() {
        return Collections.unmodifiableSet(passwords);
    }
    
    public Set<String> getPasswordHashes() {
        return Collections.unmodifiableSet(passwordHashes);
    }
    
    public Set<String> getAllowedCommands() {
        return Collections.unmodifiableSet(allowedCommands);
    }
    
    public Set<String> getDeniedCommands() {
        return Collections.unmodifiableSet(deniedCommands);
    }
    
    public Set<String> getAllowedCommandCategories() {
        return Collections.unmodifiableSet(allowedCommandCategories);
    }
    
    public Set<String> getDeniedCommandCategories() {
        return Collections.unmodifiableSet(deniedCommandCategories);
    }
    
    public Set<String> getKeyPatterns() {
        return Collections.unmodifiableSet(keyPatterns);
    }
    
    public Set<String> getReadOnlyKeyPatterns() {
        return Collections.unmodifiableSet(readOnlyKeyPatterns);
    }
    
    public Set<String> getWriteOnlyKeyPatterns() {
        return Collections.unmodifiableSet(writeOnlyKeyPatterns);
    }
    
    public Set<String> getChannelPatterns() {
        return Collections.unmodifiableSet(channelPatterns);
    }
    
    // ==================== Setters ====================
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void setNoPassword(boolean noPassword) {
        this.noPassword = noPassword;
        if (noPassword) {
            this.passwords.clear();
            this.passwordHashes.clear();
        }
    }
    
    // ==================== 密码管理 ====================
    
    /**
     * 添加密码（明文）
     *
     * @param password 明文密码
     */
    public void addPassword(String password) {
        if (password != null && !password.isEmpty()) {
            this.noPassword = false;
            this.passwords.add(password);
        }
    }
    
    /**
     * 移除密码
     *
     * @param password 要移除的密码
     */
    public void removePassword(String password) {
        this.passwords.remove(password);
    }
    
    /**
     * 添加密码哈希（SHA-256）
     *
     * @param hash SHA-256 哈希值
     */
    public void addPasswordHash(String hash) {
        if (hash != null && !hash.isEmpty()) {
            this.noPassword = false;
            this.passwordHashes.add(hash.toLowerCase());
        }
    }
    
    /**
     * 移除密码哈希
     *
     * @param hash 要移除的哈希值
     */
    public void removePasswordHash(String hash) {
        this.passwordHashes.remove(hash.toLowerCase());
    }
    
    /**
     * 验证密码
     *
     * @param password 待验证的密码
     * @return 是否验证通过
     */
    public boolean validatePassword(String password) {
        if (noPassword) {
            return true;
        }
        
        if (password == null) {
            return false;
        }
        
        // 检查明文密码
        if (passwords.contains(password)) {
            return true;
        }
        
        // 检查密码哈希
        String hash = hashPassword(password);
        return passwordHashes.contains(hash);
    }
    
    /**
     * 重置密码
     */
    public void resetPassword() {
        this.passwords.clear();
        this.passwordHashes.clear();
        this.noPassword = false;
    }
    
    // ==================== 命令权限管理 ====================
    
    /**
     * 添加允许的命令
     *
     * @param command 命令名称
     */
    public void addAllowedCommand(String command) {
        this.allowedCommands.add(command.toUpperCase());
    }
    
    /**
     * 添加拒绝的命令
     *
     * @param command 命令名称
     */
    public void addDeniedCommand(String command) {
        this.deniedCommands.add(command.toUpperCase());
    }
    
    /**
     * 添加允许的命令类别
     *
     * @param category 命令类别（如 @read, @write）
     */
    public void addAllowedCommandCategory(String category) {
        this.allowedCommandCategories.add(category.toLowerCase());
    }
    
    /**
     * 添加拒绝的命令类别
     *
     * @param category 命令类别
     */
    public void addDeniedCommandCategory(String category) {
        this.deniedCommandCategories.add(category.toLowerCase());
    }
    
    /**
     * 添加允许的子命令
     *
     * @param command 主命令
     * @param subcommand 子命令
     */
    public void addAllowedSubcommand(String command, String subcommand) {
        allowedSubcommands.computeIfAbsent(command.toUpperCase(), k -> new HashSet<>())
            .add(subcommand.toUpperCase());
    }
    
    /**
     * 获取命令的允许子命令集合
     *
     * @param command 主命令
     * @return 子命令集合
     */
    public Set<String> getAllowedSubcommands(String command) {
        return allowedSubcommands.getOrDefault(command.toUpperCase(), Collections.emptySet());
    }
    
    // ==================== 键模式管理 ====================
    
    /**
     * 添加键模式（读写权限）
     *
     * @param pattern 键模式
     */
    public void addKeyPattern(String pattern) {
        this.keyPatterns.add(pattern);
    }
    
    /**
     * 添加只读键模式
     *
     * @param pattern 键模式
     */
    public void addKeyPatternReadOnly(String pattern) {
        this.readOnlyKeyPatterns.add(pattern);
    }
    
    /**
     * 添加只写键模式
     *
     * @param pattern 键模式
     */
    public void addKeyPatternWriteOnly(String pattern) {
        this.writeOnlyKeyPatterns.add(pattern);
    }
    
    /**
     * 重置键模式
     */
    public void resetKeys() {
        this.keyPatterns.clear();
        this.readOnlyKeyPatterns.clear();
        this.writeOnlyKeyPatterns.clear();
    }
    
    // ==================== 频道模式管理 ====================
    
    /**
     * 添加频道模式
     *
     * @param pattern 频道模式
     */
    public void addChannelPattern(String pattern) {
        this.channelPatterns.add(pattern);
    }
    
    /**
     * 重置频道模式
     */
    public void resetChannels() {
        this.channelPatterns.clear();
    }
    
    // ==================== 重置操作 ====================
    
    /**
     * 重置用户到初始状态
     */
    public void reset() {
        this.enabled = false;
        resetPassword();
        this.allowedCommands.clear();
        this.deniedCommands.clear();
        this.allowedCommandCategories.clear();
        this.deniedCommandCategories.clear();
        this.allowedSubcommands.clear();
        resetKeys();
        resetChannels();
    }
    
    // ==================== 规则解析 ====================
    
    /**
     * 从规则字符串创建用户
     *
     * @param username 用户名
     * @param rules 规则字符串
     * @return ACLUser 实例
     */
    public static ACLUser fromRules(String username, String rules) {
        ACLUser user = new ACLUser(username);
        if (rules == null || rules.trim().isEmpty()) {
            return user;
        }
        
        // 去掉开头的 "user <username>" 部分
        String ruleStr = rules.trim();
        if (ruleStr.startsWith("user ")) {
            ruleStr = ruleStr.substring(5).trim();
            // 跳过用户名
            int spaceIndex = ruleStr.indexOf(' ');
            if (spaceIndex > 0) {
                ruleStr = ruleStr.substring(spaceIndex + 1);
            } else {
                return user;
            }
        }
        
        // 解析规则
        String[] parts = ruleStr.split("\\s+");
        for (String part : parts) {
            parseRule(user, part);
        }
        
        return user;
    }
    
    /**
     * 解析单个规则
     */
    private static void parseRule(ACLUser user, String rule) {
        if (rule.isEmpty()) {
            return;
        }
        
        switch (rule) {
            case "on":
                user.setEnabled(true);
                break;
            case "off":
                user.setEnabled(false);
                break;
            case "nopass":
                user.setNoPassword(true);
                break;
            case "resetpass":
                user.resetPassword();
                break;
            case "resetkeys":
                user.resetKeys();
                break;
            case "resetchannels":
                user.resetChannels();
                break;
            case "allkeys":
                user.addKeyPattern("*");
                break;
            case "allchannels":
                user.addChannelPattern("*");
                break;
            case "allcommands":
                user.addAllowedCommandCategory("@all");
                break;
            case "nocommands":
                user.addDeniedCommandCategory("@all");
                break;
            case "clearselectors":
                // TODO: 实现选择器
                break;
            case "reset":
                user.reset();
                break;
            default:
                parseComplexRule(user, rule);
                break;
        }
    }
    
    /**
     * 解析复杂规则（带前缀的规则）
     */
    private static void parseComplexRule(ACLUser user, String rule) {
        if (rule.length() < 2) {
            return;
        }
        
        char prefix = rule.charAt(0);
        String value = rule.substring(1);
        
        switch (prefix) {
            case '>':
                user.addPassword(value);
                break;
            case '<':
                user.removePassword(value);
                break;
            case '#':
                user.addPasswordHash(value);
                break;
            case '!':
                user.removePasswordHash(value);
                break;
            case '+':
                parseAddRule(user, value);
                break;
            case '-':
                parseRemoveRule(user, value);
                break;
            case '~':
                parseKeyPattern(user, value);
                break;
            case '&':
                user.addChannelPattern(value);
                break;
            case '%':
                parseKeyPermission(user, value);
                break;
        }
    }
    
    /**
     * 解析添加规则
     */
    private static void parseAddRule(ACLUser user, String value) {
        if (value.startsWith("@")) {
            user.addAllowedCommandCategory(value);
        } else if (value.contains("|")) {
            String[] parts = value.split("\\|", 2);
            user.addAllowedCommand(parts[0]);
            user.addAllowedSubcommand(parts[0], parts[1]);
        } else {
            user.addAllowedCommand(value);
        }
    }
    
    /**
     * 解析移除规则
     */
    private static void parseRemoveRule(ACLUser user, String value) {
        if (value.startsWith("@")) {
            user.addDeniedCommandCategory(value);
        } else {
            user.addDeniedCommand(value);
        }
    }
    
    /**
     * 解析键模式（不带权限前缀）
     */
    private static void parseKeyPattern(ACLUser user, String value) {
        user.addKeyPattern(value);
    }
    
    /**
     * 解析键权限规则（带 % 前缀）
     */
    private static void parseKeyPermission(ACLUser user, String value) {
        if (value.startsWith("R~")) {
            user.addKeyPatternReadOnly(value.substring(2));
        } else if (value.startsWith("W~")) {
            user.addKeyPatternWriteOnly(value.substring(2));
        } else if (value.startsWith("RW~")) {
            user.addKeyPattern(value.substring(3));
        }
    }
    
    // ==================== 转换为规则字符串 ====================
    
    /**
     * 转换为规则字符串
     *
     * @return 规则字符串
     */
    public String toRuleString() {
        StringBuilder sb = new StringBuilder();
        sb.append("user ").append(username);
        
        // 用户状态
        sb.append(enabled ? " on" : " off");
        
        // 密码
        if (noPassword) {
            sb.append(" nopass");
        } else {
            for (String pass : passwords) {
                sb.append(" >").append(pass);
            }
            for (String hash : passwordHashes) {
                sb.append(" #").append(hash);
            }
        }
        
        // 键模式
        for (String pattern : keyPatterns) {
            sb.append(" ~").append(pattern);
        }
        for (String pattern : readOnlyKeyPatterns) {
            sb.append(" %R~").append(pattern);
        }
        for (String pattern : writeOnlyKeyPatterns) {
            sb.append(" %W~").append(pattern);
        }
        
        // 频道模式
        for (String pattern : channelPatterns) {
            sb.append(" &").append(pattern);
        }
        
        // 命令权限
        for (String category : allowedCommandCategories) {
            sb.append(" +").append(category);
        }
        for (String category : deniedCommandCategories) {
            sb.append(" -").append(category);
        }
        for (String command : allowedCommands) {
            sb.append(" +").append(command);
        }
        for (String command : deniedCommands) {
            sb.append(" -").append(command);
        }
        
        return sb.toString();
    }
    
    // ==================== 克隆 ====================
    
    @Override
    public ACLUser clone() {
        ACLUser cloned = new ACLUser(this.username);
        cloned.enabled = this.enabled;
        cloned.noPassword = this.noPassword;
        cloned.passwords.addAll(this.passwords);
        cloned.passwordHashes.addAll(this.passwordHashes);
        cloned.allowedCommands.addAll(this.allowedCommands);
        cloned.deniedCommands.addAll(this.deniedCommands);
        cloned.allowedCommandCategories.addAll(this.allowedCommandCategories);
        cloned.deniedCommandCategories.addAll(this.deniedCommandCategories);
        this.allowedSubcommands.forEach((cmd, subs) -> 
            cloned.allowedSubcommands.put(cmd, new HashSet<>(subs)));
        cloned.keyPatterns.addAll(this.keyPatterns);
        cloned.readOnlyKeyPatterns.addAll(this.readOnlyKeyPatterns);
        cloned.writeOnlyKeyPatterns.addAll(this.writeOnlyKeyPatterns);
        cloned.channelPatterns.addAll(this.channelPatterns);
        return cloned;
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 计算密码的 SHA-256 哈希
     */
    private String hashPassword(String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().toLowerCase();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    @Override
    public String toString() {
        return "ACLUser{" +
                "username='" + username + '\'' +
                ", enabled=" + enabled +
                ", noPassword=" + noPassword +
                '}';
    }
}
