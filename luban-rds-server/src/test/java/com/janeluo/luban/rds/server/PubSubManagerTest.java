package com.janeluo.luban.rds.server;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Assert;
import org.junit.Test;

public class PubSubManagerTest {
    @Test
    public void testSubscribeAndUnsubscribe() {
        PubSubManager manager = new PubSubManager();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();

        manager.subscribe(ch1, "news");
        manager.subscribe(ch2, "news");
        manager.subscribe(ch1, "sports");

        Assert.assertEquals(2, manager.subscriptionCount(ch1));
        Assert.assertEquals(1, manager.subscriptionCount(ch2));
        Assert.assertEquals(2, manager.subscribers("news").size());
        Assert.assertEquals(1, manager.subscribers("sports").size());

        manager.unsubscribe(ch1, "news");
        Assert.assertEquals(1, manager.subscriptionCount(ch1));
        Assert.assertEquals(1, manager.subscribers("news").size());

        int removed = manager.unsubscribeAll(ch2);
        Assert.assertEquals(1, removed);
        Assert.assertEquals(0, manager.subscriptionCount(ch2));
    }
}
