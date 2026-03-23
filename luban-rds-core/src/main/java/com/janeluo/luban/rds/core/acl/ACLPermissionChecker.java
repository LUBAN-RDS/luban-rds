package com.janeluo.luban.rds.core.acl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * ACL 权限检查器
 * 
 * <p>负责检查用户的命令权限、键权限和频道权限。
 * 支持 Redis 7.2 ACL 规范中的所有权限检查功能。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLPermissionChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(ACLPermissionChecker.class);
    
    /**
     * 键访问类型枚举
     */
    public enum KeyAccessType {
        READ,
        WRITE
    }
    
    /**
     * 频道访问类型枚举
     */
    public enum ChannelAccessType {
        SUBSCRIBE,
        PUBLISH
    }
    
    /**
     * 检查命令权限
     *
     * @param user 用户
     * @param command 命令名称
     * @param subcommands 子命令参数列表
     * @return 是否有权限执行该命令
     */
    public boolean checkCommand(ACLUser user, String command, List<String> subcommands) {
        if (user == null || command == null) {
            return false;
        }
        
        String upperCommand = command.toUpperCase();
        
        // 检查是否明确拒绝该命令
        if (user.getDeniedCommands().contains(upperCommand)) {
            logDenial(user, "command", upperCommand);
            return false;
        }
        
        // 检查命令类别是否被拒绝（包括 -@all）
        for (String category : user.getDeniedCommandCategories()) {
            if (isCommandInCategory(upperCommand, category)) {
                logDenial(user, "command category", upperCommand + " in " + category);
                return false;
            }
        }
        
        // 检查是否有子命令权限限制
        if (!subcommands.isEmpty()) {
            String subcommand = subcommands.get(0).toUpperCase();
            Set<String> allowedSubs = user.getAllowedSubcommands(upperCommand);
            if (!allowedSubs.isEmpty() && !allowedSubs.contains(subcommand)) {
                logDenial(user, "subcommand", upperCommand + "|" + subcommand);
                return false;
            }
        }
        
        // 检查是否明确允许该命令
        if (user.getAllowedCommands().contains(upperCommand)) {
            return true;
        }
        
        // 检查命令类别是否被允许（包括 @all）
        for (String category : user.getAllowedCommandCategories()) {
            if (isCommandInCategory(upperCommand, category)) {
                return true;
            }
        }
        
        // 如果用户有任何允许的命令或类别，但该命令不在其中，则拒绝
        if (!user.getAllowedCommands().isEmpty() || !user.getAllowedCommandCategories().isEmpty()) {
            logDenial(user, "command not allowed", upperCommand);
            return false;
        }
        
        // 如果没有任何权限设置，默认拒绝
        logDenial(user, "no permission", upperCommand);
        return false;
    }
    
    /**
     * 检查键权限
     *
     * @param user 用户
     * @param key 键名
     * @param accessType 访问类型
     * @return 是否有权限访问该键
     */
    public boolean checkKey(ACLUser user, String key, KeyAccessType accessType) {
        if (user == null || key == null) {
            return false;
        }
        
        // 检查读写权限的键模式
        for (String pattern : user.getKeyPatterns()) {
            if (matchesPattern(key, pattern)) {
                return true;
            }
        }
        
        // 检查只读键模式
        if (accessType == KeyAccessType.READ) {
            for (String pattern : user.getReadOnlyKeyPatterns()) {
                if (matchesPattern(key, pattern)) {
                    return true;
                }
            }
        }
        
        // 检查只写键模式
        if (accessType == KeyAccessType.WRITE) {
            for (String pattern : user.getWriteOnlyKeyPatterns()) {
                if (matchesPattern(key, pattern)) {
                    return true;
                }
            }
        }
        
        // 没有匹配的模式，拒绝访问
        logDenial(user, "key", key);
        return false;
    }
    
    /**
     * 检查频道权限
     *
     * @param user 用户
     * @param channel 频道名
     * @param accessType 访问类型
     * @return 是否有权限访问该频道
     */
    public boolean checkChannel(ACLUser user, String channel, ChannelAccessType accessType) {
        if (user == null || channel == null) {
            return false;
        }
        
        for (String pattern : user.getChannelPatterns()) {
            if (matchesPattern(channel, pattern)) {
                return true;
            }
        }
        
        logDenial(user, "channel", channel);
        return false;
    }
    
    /**
     * 综合权限检查
     *
     * @param user 用户
     * @param command 命令名称
     * @param subcommands 子命令参数
     * @param keys 涉及的键列表
     * @param keyAccessType 键访问类型
     * @return 是否有权限执行该操作
     */
    public boolean checkPermission(ACLUser user, String command, List<String> subcommands,
                                   List<String> keys, KeyAccessType keyAccessType) {
        // 检查命令权限
        if (!checkCommand(user, command, subcommands)) {
            return false;
        }
        
        // 检查键权限
        for (String key : keys) {
            if (!checkKey(user, key, keyAccessType)) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 判断命令是否属于某个类别
     *
     * @param command 命令名称
     * @param category 类别名称
     * @return 是否属于该类别
     */
    private boolean isCommandInCategory(String command, String category) {
        return ACLCommandCategories.isCommandInCategory(command, category);
    }
    
    /**
     * 模式匹配
     * 
     * <p>支持 Redis 风格的通配符：
     * <ul>
     *   <li>* 匹配任意字符（包括空）</li>
     *   <li>? 匹配单个字符</li>
     *   <li>[abc] 匹配字符集合</li>
     * </ul>
     *
     * @param key 键名
     * @param pattern 模式
     * @return 是否匹配
     */
    private boolean matchesPattern(String key, String pattern) {
        // 精确匹配
        if (!pattern.contains("*") && !pattern.contains("?") && !pattern.contains("[")) {
            return key.equals(pattern);
        }
        
        // 转换为正则表达式
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append(".");
                    break;
                case '[':
                    // 查找对应的 ]
                    int end = pattern.indexOf(']', i);
                    if (end > i) {
                        regex.append(pattern.substring(i, end + 1));
                        i = end;
                    } else {
                        regex.append("\\[");
                    }
                    break;
                case '.':
                case '^':
                case '$':
                case '|':
                case '+':
                case '(':
                case ')':
                case '{':
                case '}':
                case '\\':
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
                    break;
            }
        }
        
        try {
            return key.matches(regex.toString());
        } catch (Exception e) {
            logger.warn("Invalid pattern {}: {}", pattern, e.getMessage());
            return false;
        }
    }
    
    /**
     * 记录权限拒绝日志
     */
    private void logDenial(ACLUser user, String type, String resource) {
        if (logger.isDebugEnabled()) {
            logger.debug("ACL denial: user={}, type={}, resource={}", 
                user.getUsername(), type, resource);
        }
    }
}
