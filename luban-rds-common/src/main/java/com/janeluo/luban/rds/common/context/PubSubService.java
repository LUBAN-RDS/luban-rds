package com.janeluo.luban.rds.common.context;

public interface PubSubService {
    /**
     * Publish a message to a channel.
     * @param channel The channel name
     * @param message The message content
     * @return The number of clients that received the message
     */
    int publish(String channel, String message);
}
