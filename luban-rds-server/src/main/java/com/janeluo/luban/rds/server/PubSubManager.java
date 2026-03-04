package com.janeluo.luban.rds.server;

import io.netty.channel.Channel;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Pub/Sub 管理器：维护频道到订阅者以及订阅者到频道的映射
 */
public class PubSubManager {
    private final Map<String, Set<Channel>> channelSubscribers = new ConcurrentHashMap<>();
    private final Map<Channel, Set<String>> clientChannels = new ConcurrentHashMap<>();

    public void subscribe(Channel channel, String topic) {
        channelSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(channel);
        clientChannels.computeIfAbsent(channel, k -> new CopyOnWriteArraySet<>()).add(topic);
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

    public int unsubscribeAll(Channel channel) {
        Set<String> topics = clientChannels.remove(channel);
        if (topics == null || topics.isEmpty()) {
            return 0;
        }
        for (String t : topics) {
            Set<Channel> subs = channelSubscribers.get(t);
            if (subs != null) {
                subs.remove(channel);
                if (subs.isEmpty()) {
                    channelSubscribers.remove(t);
                }
            }
        }
        return topics.size();
    }

    public int subscriptionCount(Channel channel) {
        Set<String> topics = clientChannels.get(channel);
        return topics == null ? 0 : topics.size();
    }

    public Set<String> subscriptions(Channel channel) {
        Set<String> topics = clientChannels.get(channel);
        if (topics == null) {
            return Collections.emptySet();
        }
        return topics;
    }

    public Collection<Channel> subscribers(String topic) {
        Set<Channel> subs = channelSubscribers.get(topic);
        if (subs == null) {
            return Collections.emptyList();
        }
        return subs;
    }
}
