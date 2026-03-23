package com.janeluo.luban.rds.acl.handler;

import com.janeluo.luban.rds.acl.ACLManager;
import com.janeluo.luban.rds.acl.ACLUser;
import com.janeluo.luban.rds.core.handler.CommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ACL 命令处理器
 * 
 * <p>处理所有 ACL 相关的 Redis 命令，包括：
 * <ul>
 *   <li>ACL SETUSER - 创建或修改用户</li>
 *   <li>ACL DELUSER - 删除用户</li>
 *   <li>ACL GETUSER - 获取用户信息</li>
 *   <li>ACL LIST - 列出所有用户</li>
 *   <li>ACL CAT - 列出命令类别</li>
 *   <li>ACL GENPASS - 生成密码</li>
 *   <li>ACL WHOAMI - 获取当前用户</li>
 *   <li>ACL LOAD - 从文件加载 ACL</li>
 *   <li>ACL SAVE - 保存 ACL 到文件</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLCommandHandler implements CommandHandler {
    
    private final ACLManager aclManager;
    
    public ACLCommandHandler(ACLManager aclManager) {
        this.aclManager = aclManager;
    }
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'acl' command\r\n";
        }
        
        String subcommand = args[1].toUpperCase();
        
        switch (subcommand) {
            case "SETUSER":
                return handleSetUser(args);
            case "DELUSER":
                return handleDelUser(args);
            case "GETUSER":
                return handleGetUser(args);
            case "LIST":
                return handleList();
            case "CAT":
                return handleCat(args);
            case "GENPASS":
                return handleGenPass(args);
            case "WHOAMI":
                return handleWhoAmI(args);
            case "LOAD":
                return handleLoad(args);
            case "SAVE":
                return handleSave(args);
            case "LOG":
                return handleLog(args);
            case "HELP":
                return handleHelp();
            default:
                return "-ERR unknown subcommand '" + subcommand + "' for 'acl' command\r\n";
        }
    }
    
    @Override
    public Set<String> supportedCommands() {
        return java.util.Collections.singleton("ACL");
    }
    
    // ==================== 子命令处理 ====================
    
    /**
     * 处理 ACL SETUSER 命令
     * ACL SETUSER username [rules...]
     */
    private Object handleSetUser(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'acl|setuser' command\r\n";
        }
        
        String username = args[2];
        String rules = "";
        
        if (args.length > 3) {
            // 合并所有规则
            rules = Arrays.stream(args)
                .skip(3)
                .collect(Collectors.joining(" "));
        }
        
        try {
            ACLUser user = aclManager.setUser(username, rules);
            aclManager.getAuditLogger().logUserCreated(username);
            return "+OK\r\n";
        } catch (Exception e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }
    
    /**
     * 处理 ACL DELUSER 命令
     * ACL DELUSER username [username ...]
     */
    private Object handleDelUser(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'acl|deluser' command\r\n";
        }
        
        int deleted = 0;
        for (int i = 2; i < args.length; i++) {
            String username = args[i];
            if (aclManager.deleteUser(username)) {
                aclManager.getAuditLogger().logUserDeleted(username);
                deleted++;
            }
        }
        
        return ":" + deleted + "\r\n";
    }
    
    /**
     * 处理 ACL GETUSER 命令
     * ACL GETUSER username
     */
    private Object handleGetUser(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'acl|getuser' command\r\n";
        }
        
        String username = args[2];
        ACLUser user = aclManager.getUser(username);
        
        if (user == null) {
            return "$-1\r\n";
        }
        
        // 返回用户信息的 RESP 数组
        List<String> result = new ArrayList<>();
        
        // 用户名
        result.add("username");
        result.add(user.getUsername());
        
        // 标志
        result.add("flags");
        List<String> flags = new ArrayList<>();
        if (user.isEnabled()) flags.add("on");
        else flags.add("off");
        if (user.isNoPassword()) flags.add("nopass");
        result.add(Integer.toString(flags.size()));
        result.addAll(flags);
        
        // 密码
        result.add("passwords");
        result.add(Integer.toString(user.getPasswords().size()));
        // 不返回实际密码，只返回占位符
        
        // 命令
        result.add("commands");
        result.add(buildCommandString(user));
        
        // 键模式
        result.add("keys");
        List<String> keys = new ArrayList<>();
        for (String pattern : user.getKeyPatterns()) {
            keys.add("~" + pattern);
        }
        for (String pattern : user.getReadOnlyKeyPatterns()) {
            keys.add("%R~" + pattern);
        }
        for (String pattern : user.getWriteOnlyKeyPatterns()) {
            keys.add("%W~" + pattern);
        }
        result.add(Integer.toString(keys.size()));
        result.addAll(keys);
        
        // 频道模式
        result.add("channels");
        List<String> channels = new ArrayList<>();
        for (String pattern : user.getChannelPatterns()) {
            channels.add("&" + pattern);
        }
        result.add(Integer.toString(channels.size()));
        result.addAll(channels);
        
        return buildRespArray(result);
    }
    
    /**
     * 处理 ACL LIST 命令
     * ACL LIST
     */
    private Object handleList() {
        List<String> users = aclManager.listUsers();
        return buildRespArray(users);
    }
    
    /**
     * 处理 ACL CAT 命令
     * ACL CAT [category]
     */
    private Object handleCat(String[] args) {
        if (args.length < 3) {
            // 列出所有类别
            Set<String> categories = aclManager.getCommandCategories();
            List<String> sortedCategories = new ArrayList<>(categories);
            sortedCategories.sort(String::compareTo);
            return buildRespArray(sortedCategories);
        }
        
        // 列出类别中的命令
        String category = args[2].toLowerCase();
        Set<String> commands = aclManager.getCategoryCommands(category);
        
        if (commands.isEmpty()) {
            return "-ERR Unknown category '" + category + "'\r\n";
        }
        
        List<String> sortedCommands = new ArrayList<>(commands);
        sortedCommands.sort(String::compareTo);
        return buildRespArray(sortedCommands);
    }
    
    /**
     * 处理 ACL GENPASS 命令
     * ACL GENPASS [bits]
     */
    private Object handleGenPass(String[] args) {
        int bits = 256; // 默认 256 位
        
        if (args.length >= 3) {
            try {
                bits = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                return "-ERR invalid bits number\r\n";
            }
        }
        
        String password = aclManager.generatePassword(bits);
        return "+" + password + "\r\n";
    }
    
    /**
     * 处理 ACL WHOAMI 命令
     * ACL WHOAMI
     */
    private Object handleWhoAmI(String[] args) {
        // TODO: 从连接上下文获取当前用户
        return "+default\r\n";
    }
    
    /**
     * 处理 ACL LOAD 命令
     * ACL LOAD
     */
    private Object handleLoad(String[] args) {
        // TODO: 实现从文件加载
        return "-ERR ACL LOAD not implemented yet\r\n";
    }
    
    /**
     * 处理 ACL SAVE 命令
     * ACL SAVE
     */
    private Object handleSave(String[] args) {
        // TODO: 实现保存到文件
        return "-ERR ACL SAVE not implemented yet\r\n";
    }
    
    /**
     * 处理 ACL LOG 命令
     * ACL LOG [count]
     */
    private Object handleLog(String[] args) {
        // TODO: 实现审计日志查询
        return "-ERR ACL LOG not implemented yet\r\n";
    }
    
    /**
     * 处理 ACL HELP 命令
     * ACL HELP
     */
    private Object handleHelp() {
        List<String> help = new ArrayList<>();
        help.add("ACL SETUSER <username> [rule ...] - Create or modify a user");
        help.add("ACL DELUSER <username> [...] - Delete a user");
        help.add("ACL GETUSER <username> - Get user details");
        help.add("ACL LIST - List all users");
        help.add("ACL CAT [category] - List categories or commands in category");
        help.add("ACL GENPASS [bits] - Generate a secure password");
        help.add("ACL WHOAMI - Return the current user");
        help.add("ACL LOAD - Reload users from the ACL file");
        help.add("ACL SAVE - Save users to the ACL file");
        help.add("ACL LOG [count] - Show recent ACL security events");
        help.add("ACL HELP - Show this help");
        
        return buildRespArray(help);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 构建命令字符串
     */
    private String buildCommandString(ACLUser user) {
        StringBuilder sb = new StringBuilder();
        
        // 允许的命令类别
        for (String cat : user.getAllowedCommandCategories()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("+").append(cat);
        }
        
        // 拒绝的命令类别
        for (String cat : user.getDeniedCommandCategories()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("-").append(cat);
        }
        
        // 允许的命令
        for (String cmd : user.getAllowedCommands()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("+").append(cmd);
        }
        
        // 拒绝的命令
        for (String cmd : user.getDeniedCommands()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("-").append(cmd);
        }
        
        return sb.toString();
    }
    
    /**
     * 构建 RESP 数组
     */
    private String buildRespArray(List<String> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(elements.size()).append("\r\n");
        
        for (String element : elements) {
            if (element == null) {
                sb.append("$-1\r\n");
            } else {
                sb.append("$").append(element.length()).append("\r\n");
                sb.append(element).append("\r\n");
            }
        }
        
        return sb.toString();
    }
}
