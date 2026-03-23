package com.janeluo.luban.rds.acl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ACL 审计日志记录器
 * 
 * <p>记录所有 ACL 相关的操作和权限检查结果，用于安全审计。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLAuditLogger {
    
    private static final Logger logger = LoggerFactory.getLogger(ACLAuditLogger.class);
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 审计日志队列
     */
    private final ConcurrentLinkedQueue<ACLEvent> events;
    
    /**
     * 最大日志条数
     */
    private final int maxEvents;
    
    /**
     * 创建审计日志记录器
     */
    public ACLAuditLogger() {
        this(10000);
    }
    
    /**
     * 创建审计日志记录器
     *
     * @param maxEvents 最大日志条数
     */
    public ACLAuditLogger(int maxEvents) {
        this.maxEvents = maxEvents;
        this.events = new ConcurrentLinkedQueue<>();
    }
    
    // ==================== 日志记录方法 ====================
    
    /**
     * 记录认证成功
     *
     * @param username 用户名
     */
    public void logAuthSuccess(String username) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.AUTH_SUCCESS,
            username,
            null,
            "Authentication successful"
        );
        addEvent(event);
        
        logger.info("ACL Auth Success: user={}", username);
    }
    
    /**
     * 记录认证失败
     *
     * @param username 用户名
     * @param reason 失败原因
     */
    public void logAuthFailure(String username, String reason) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.AUTH_FAILURE,
            username,
            null,
            "Authentication failed: " + reason
        );
        addEvent(event);
        
        logger.warn("ACL Auth Failure: user={}, reason={}", username, reason);
    }
    
    /**
     * 记录权限拒绝
     *
     * @param username 用户名
     * @param resourceType 资源类型
     * @param resourceName 资源名称
     */
    public void logPermissionDenied(String username, String resourceType, String resourceName) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.PERMISSION_DENIED,
            username,
            resourceType + ":" + resourceName,
            "Permission denied"
        );
        addEvent(event);
        
        logger.warn("ACL Permission Denied: user={}, type={}, resource={}", 
            username, resourceType, resourceName);
    }
    
    /**
     * 记录用户创建
     *
     * @param username 用户名
     */
    public void logUserCreated(String username) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.USER_CREATED,
            username,
            null,
            "User created"
        );
        addEvent(event);
        
        logger.info("ACL User Created: user={}", username);
    }
    
    /**
     * 记录用户删除
     *
     * @param username 用户名
     */
    public void logUserDeleted(String username) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.USER_DELETED,
            username,
            null,
            "User deleted"
        );
        addEvent(event);
        
        logger.info("ACL User Deleted: user={}", username);
    }
    
    /**
     * 记录用户修改
     *
     * @param username 用户名
     * @param changes 变更内容
     */
    public void logUserModified(String username, String changes) {
        ACLEvent event = new ACLEvent(
            LocalDateTime.now(),
            ACLEventType.USER_MODIFIED,
            username,
            null,
            "User modified: " + changes
        );
        addEvent(event);
        
        logger.info("ACL User Modified: user={}, changes={}", username, changes);
    }
    
    // ==================== 日志查询方法 ====================
    
    /**
     * 获取所有审计日志
     *
     * @return 日志列表
     */
    public List<ACLEvent> getAllEvents() {
        return new ArrayList<>(events);
    }
    
    /**
     * 获取指定用户的审计日志
     *
     * @param username 用户名
     * @return 日志列表
     */
    public List<ACLEvent> getEventsByUser(String username) {
        List<ACLEvent> userEvents = new ArrayList<>();
        for (ACLEvent event : events) {
            if (username.equals(event.getUsername())) {
                userEvents.add(event);
            }
        }
        return userEvents;
    }
    
    /**
     * 获取指定类型的审计日志
     *
     * @param type 事件类型
     * @return 日志列表
     */
    public List<ACLEvent> getEventsByType(ACLEventType type) {
        List<ACLEvent> typeEvents = new ArrayList<>();
        for (ACLEvent event : events) {
            if (type == event.getType()) {
                typeEvents.add(event);
            }
        }
        return typeEvents;
    }
    
    /**
     * 清空审计日志
     */
    public void clear() {
        events.clear();
        logger.info("ACL audit log cleared");
    }
    
    /**
     * 获取日志条数
     *
     * @return 日志条数
     */
    public int size() {
        return events.size();
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 添加事件
     */
    private void addEvent(ACLEvent event) {
        events.add(event);
        
        // 如果超过最大条数，移除最早的事件
        while (events.size() > maxEvents) {
            events.poll();
        }
    }
    
    // ==================== 内部类 ====================
    
    /**
     * ACL 事件类型枚举
     */
    public enum ACLEventType {
        AUTH_SUCCESS,
        AUTH_FAILURE,
        PERMISSION_DENIED,
        USER_CREATED,
        USER_DELETED,
        USER_MODIFIED
    }
    
    /**
     * ACL 事件类
     */
    public static class ACLEvent {
        private final LocalDateTime timestamp;
        private final ACLEventType type;
        private final String username;
        private final String resource;
        private final String message;
        
        public ACLEvent(LocalDateTime timestamp, ACLEventType type, 
                       String username, String resource, String message) {
            this.timestamp = timestamp;
            this.type = type;
            this.username = username;
            this.resource = resource;
            this.message = message;
        }
        
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
        
        public ACLEventType getType() {
            return type;
        }
        
        public String getUsername() {
            return username;
        }
        
        public String getResource() {
            return resource;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s - user: %s, resource: %s, message: %s",
                timestamp.format(FORMATTER), type, username, resource, message);
        }
    }
}
