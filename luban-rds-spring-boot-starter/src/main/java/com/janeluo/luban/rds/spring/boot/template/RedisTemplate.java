package com.janeluo.luban.rds.spring.boot.template;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis模板类
 * 
 * <p>提供简化的Redis操作接口，类似于Spring Data Redis的RedisTemplate。
 * 直接操作MemoryStore，无需网络开销。
 * 
 * <p>支持的操作：
 * <ul>
 *   <li>字符串操作（set、get、incr、decr）</li>
 *   <li>哈希操作（hset、hget、hgetAll）</li>
 *   <li>列表操作（lpush、lpop、llen）</li>
 *   <li>集合操作（sadd、smembers、scard）</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
@Component
public class RedisTemplate {
    
    /**
     * 默认数据库索引
     */
    private static final int DEFAULT_DATABASE = 0;
    
    /**
     * 内存存储实例
     */
    private final MemoryStore memoryStore;
    
    @Autowired
    public RedisTemplate(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }
    
    // 字符串操作
    public void set(String key, String value) {
        memoryStore.set(DEFAULT_DATABASE, key, value);
    }
    
    public String get(String key) {
        Object value = memoryStore.get(DEFAULT_DATABASE, key);
        return value != null ? value.toString() : null;
    }
    
    public Long incr(String key) {
        String value = get(key);
        long num = value != null ? Long.parseLong(value) : 0;
        num++;
        set(key, String.valueOf(num));
        return num;
    }
    
    public Long decr(String key) {
        String value = get(key);
        long num = value != null ? Long.parseLong(value) : 0;
        num--;
        set(key, String.valueOf(num));
        return num;
    }
    
    // 哈希操作
    public void hset(String key, String field, String value) {
        Map<String, String> hash = (Map<String, String>) memoryStore.get(DEFAULT_DATABASE, key);
        if (hash == null) {
            hash = new java.util.HashMap<>();
        }
        hash.put(field, value);
        memoryStore.set(DEFAULT_DATABASE, key, hash);
    }
    
    public String hget(String key, String field) {
        Map<String, String> hash = (Map<String, String>) memoryStore.get(DEFAULT_DATABASE, key);
        return hash != null ? hash.get(field) : null;
    }
    
    public Map<String, String> hgetAll(String key) {
        Map<String, String> hash = (Map<String, String>) memoryStore.get(DEFAULT_DATABASE, key);
        return hash != null ? hash : new java.util.HashMap<>();
    }
    
    // 列表操作
    public Long lpush(String key, String... values) {
        List<String> list = (List<String>) memoryStore.get(DEFAULT_DATABASE, key);
        if (list == null) {
            list = new java.util.ArrayList<>();
        }
        for (String value : values) {
            list.add(0, value);
        }
        memoryStore.set(DEFAULT_DATABASE, key, list);
        return (long) list.size();
    }
    
    public String lpop(String key) {
        List<String> list = (List<String>) memoryStore.get(DEFAULT_DATABASE, key);
        if (list == null || list.isEmpty()) {
            return null;
        }
        String value = list.remove(0);
        memoryStore.set(DEFAULT_DATABASE, key, list);
        return value;
    }
    
    public Long llen(String key) {
        List<String> list = (List<String>) memoryStore.get(DEFAULT_DATABASE, key);
        return list != null ? (long) list.size() : 0;
    }
    
    // 集合操作
    public Long sadd(String key, String... members) {
        Set<String> set = (Set<String>) memoryStore.get(DEFAULT_DATABASE, key);
        if (set == null) {
            set = new java.util.HashSet<>();
        }
        int added = 0;
        for (String member : members) {
            if (set.add(member)) {
                added++;
            }
        }
        memoryStore.set(DEFAULT_DATABASE, key, set);
        return (long) added;
    }
    
    public Set<String> smembers(String key) {
        Set<String> set = (Set<String>) memoryStore.get(DEFAULT_DATABASE, key);
        return set != null ? set : new java.util.HashSet<>();
    }
    
    public Boolean sismember(String key, String member) {
        Set<String> set = (Set<String>) memoryStore.get(DEFAULT_DATABASE, key);
        return set != null && set.contains(member);
    }
    
    // 通用操作
    public Boolean exists(String key) {
        return memoryStore.exists(DEFAULT_DATABASE, key);
    }
    
    public Boolean del(String key) {
        return memoryStore.del(DEFAULT_DATABASE, key);
    }
    
    public Boolean expire(String key, long seconds) {
        return memoryStore.expire(DEFAULT_DATABASE, key, seconds);
    }
    
    public Long ttl(String key) {
        return memoryStore.ttl(DEFAULT_DATABASE, key);
    }
    
    public void flushAll() {
        memoryStore.flushAll();
    }
}
