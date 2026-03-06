package com.janeluo.luban.rds.server;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pub/Sub管理器
 * 
 * <p>维护频道到订阅者以及订阅者到频道的双向映射，支持：
 * <ul>
 *   <li>普通频道订阅（SUBSCRIBE/UNSUBSCRIBE）</li>
 *   <li>模式频道订阅（PSUBSCRIBE/PUNSUBSCRIBE）</li>
 *   <li>流订阅（SSUBSCRIBE/SUNSUBSCRIBE）</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class PubSubManager {
    
    /**
     * 频道到订阅者集合的映射
     */
    private final Map<String, Set<Channel>> channelSubscribers = new ConcurrentHashMap<>();
    
    /**
     * 客户端到订阅频道集合的映射
     */
    private final Map<Channel, Set<String>> clientChannels = new ConcurrentHashMap<>();
    
    /**
     * 模式到订阅者集合的映射
     */
    private final Map<String, Set<Channel>> patternSubscribers = new ConcurrentHashMap<>();
    
    /**
     * 客户端到订阅模式集合的映射
     */
    private final Map<Channel, Set<String>> clientPatterns = new ConcurrentHashMap<>();
    
    /**
     * 流到订阅者集合的映射
     */
    private final Map<String, Set<Channel>> streamSubscribers = new ConcurrentHashMap<>();
    
    /**
     * 客户端到订阅流集合的映射
     */
    private final Map<Channel, Set<String>> clientStreams = new ConcurrentHashMap<>();

    public void subscribe(Channel channel, String topic) {
        channelSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(channel);
        clientChannels.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(topic);
    }

    public void psubscribe(Channel channel, String pattern) {
        patternSubscribers.computeIfAbsent(pattern, k -> new CopyOnWriteArraySet<>()).add(channel);
        clientPatterns.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(pattern);
    }

    public void unsubscribe(Channel channel, String topic) {
        Set<Channel> subs = channelSubscribers.get(topic);
        if (subs != null) {
            subs.remove(channel);
            if (subs.isEmpty()) {
                channelSubscribers.remove(topic);
            }
        }
        Set<String> topics = clientChannels.get(channel);
        if (topics != null) {
            topics.remove(topic);
            if (topics.isEmpty()) {
                clientChannels.remove(channel);
            }
        }
    }

    public void punsubscribe(Channel channel, String pattern) {
        Set<Channel> subs = patternSubscribers.get(pattern);
        if (subs != null) {
            subs.remove(channel);
            if (subs.isEmpty()) {
                patternSubscribers.remove(pattern);
            }
        }
        Set<String> patterns = clientPatterns.get(channel);
        if (patterns != null) {
            patterns.remove(pattern);
            if (patterns.isEmpty()) {
                clientPatterns.remove(channel);
            }
        }
    }

    public void ssubscribe(Channel channel, String stream) {
        streamSubscribers.computeIfAbsent(stream, k -> new CopyOnWriteArraySet<>()).add(channel);
        clientStreams.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(stream);
    }

    public void sunsubscribe(Channel channel, String stream) {
        Set<Channel> subs = streamSubscribers.get(stream);
        if (subs != null) {
            subs.remove(channel);
            if (subs.isEmpty()) {
                streamSubscribers.remove(stream);
            }
        }
        Set<String> streams = clientStreams.get(channel);
        if (streams != null) {
            streams.remove(stream);
            if (streams.isEmpty()) {
                clientStreams.remove(channel);
            }
        }
    }

    public int unsubscribeAll(Channel channel) {
        int count = 0;
        Set<String> topics = clientChannels.remove(channel);
        if (topics != null && !topics.isEmpty()) {
            count += topics.size();
            for (String t : topics) {
                Set<Channel> subs = channelSubscribers.get(t);
                if (subs != null) {
                    subs.remove(channel);
                    if (subs.isEmpty()) {
                        channelSubscribers.remove(t);
                    }
                }
            }
        }
        
        Set<String> patterns = clientPatterns.remove(channel);
        if (patterns != null && !patterns.isEmpty()) {
            for (String p : patterns) {
                Set<Channel> subs = patternSubscribers.get(p);
                if (subs != null) {
                    subs.remove(channel);
                    if (subs.isEmpty()) {
                        patternSubscribers.remove(p);
                    }
                }
            }
        }
        
        Set<String> streams = clientStreams.remove(channel);
        if (streams != null && !streams.isEmpty()) {
            count += streams.size();
            for (String s : streams) {
                Set<Channel> subs = streamSubscribers.get(s);
                if (subs != null) {
                    subs.remove(channel);
                    if (subs.isEmpty()) {
                        streamSubscribers.remove(s);
                    }
                }
            }
        }
        return count;
    }
    
    public int punsubscribeAll(Channel channel) {
        Set<String> patterns = clientPatterns.remove(channel);
        if (patterns == null || patterns.isEmpty()) {
            return 0;
        }
        for (String p : patterns) {
            Set<Channel> subs = patternSubscribers.get(p);
            if (subs != null) {
                subs.remove(channel);
                if (subs.isEmpty()) {
                    patternSubscribers.remove(p);
                }
            }
        }
        return patterns.size();
    }

    public int subscriptionCount(Channel channel) {
        Set<String> topics = clientChannels.get(channel);
        return topics == null ? 0 : topics.size();
    }

    public int patternSubscriptionCount(Channel channel) {
        Set<String> patterns = clientPatterns.get(channel);
        return patterns == null ? 0 : patterns.size();
    }

    public Set<String> subscriptions(Channel channel) {
        Set<String> topics = clientChannels.get(channel);
        if (topics == null) {
            return Collections.emptySet();
        }
        return topics;
    }

    public Set<String> patternSubscriptions(Channel channel) {
        Set<String> patterns = clientPatterns.get(channel);
        if (patterns == null) {
            return Collections.emptySet();
        }
        return patterns;
    }

    public int streamSubscriptionCount(Channel channel) {
        Set<String> streams = clientStreams.get(channel);
        return streams == null ? 0 : streams.size();
    }

    public Set<String> streamSubscriptions(Channel channel) {
        Set<String> streams = clientStreams.get(channel);
        if (streams == null) {
            return Collections.emptySet();
        }
        return streams;
    }

    public Collection<Channel> subscribers(String topic) {
        Set<Channel> subs = channelSubscribers.get(topic);
        if (subs == null) {
            return Collections.emptyList();
        }
        return subs;
    }

    public Collection<Channel> getStreamSubscribers(String stream) {
        Set<Channel> subs = streamSubscribers.get(stream);
        if (subs == null) {
            return Collections.emptyList();
        }
        return subs;
    }
    
    public Map<String, Collection<Channel>> patternSubscribers(String topic) {
        Map<String, Collection<Channel>> result = new java.util.HashMap<>();
        for (Map.Entry<String, Set<Channel>> entry : patternSubscribers.entrySet()) {
            String pattern = entry.getKey();
            if (match(pattern, topic)) {
                result.put(pattern, entry.getValue());
            }
        }
        return result;
    }
    
    // Simple glob matching: ? matches one char, * matches any sequence, [abc] matches one of chars, \ escapes
    private boolean match(String pattern, String string) {
        int pLen = pattern.length();
        int sLen = string.length();
        int pIdx = 0;
        int sIdx = 0;
        
        while (pIdx < pLen && sIdx < sLen) {
            char pChar = pattern.charAt(pIdx);
            if (pChar == '?') {
                pIdx++;
                sIdx++;
            } else if (pChar == '*') {
                if (pIdx + 1 == pLen) return true;
                // 优化*的匹配逻辑，确保能正确匹配包含:的字符串
                for (int i = sIdx; i <= sLen; i++) {
                    if (match(pattern.substring(pIdx + 1), string.substring(i))) {
                        return true;
                    }
                }
                return false;
            } else if (pChar == '[') {
                int end = pattern.indexOf(']', pIdx);
                if (end == -1) return false;
                boolean found = false;
                char sChar = string.charAt(sIdx);
                for (int i = pIdx + 1; i < end; i++) {
                    if (pattern.charAt(i) == sChar) {
                        found = true;
                        break;
                    }
                    if (i + 2 < end && pattern.charAt(i + 1) == '-') {
                        char start = pattern.charAt(i);
                        char rangeEnd = pattern.charAt(i + 2);
                        if (sChar >= start && sChar <= rangeEnd) {
                            found = true;
                            break;
                        }
                        i += 2;
                    }
                }
                if (!found) return false;
                pIdx = end + 1;
                sIdx++;
            } else if (pChar == '\\') {
                if (pIdx + 1 < pLen) {
                    pIdx++;
                    if (pattern.charAt(pIdx) != string.charAt(sIdx)) return false;
                    pIdx++;
                    sIdx++;
                } else {
                    return false;
                }
            } else {
                if (pChar != string.charAt(sIdx)) return false;
                pIdx++;
                sIdx++;
            }
        }
        
        while (pIdx < pLen && pattern.charAt(pIdx) == '*') {
            pIdx++;
        }
        
        return pIdx == pLen && sIdx == sLen;
    }
}
