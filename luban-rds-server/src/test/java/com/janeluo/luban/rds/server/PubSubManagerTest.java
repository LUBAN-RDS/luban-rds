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

    @Test
    public void testSsubscribeAndSunsubscribe() {
        PubSubManager manager = new PubSubManager();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        EmbeddedChannel ch2 = new EmbeddedChannel();

        manager.ssubscribe(ch1, "stream1");
        manager.ssubscribe(ch2, "stream1");
        manager.ssubscribe(ch1, "stream2");

        Assert.assertEquals(2, manager.streamSubscriptionCount(ch1));
        Assert.assertEquals(1, manager.streamSubscriptionCount(ch2));
        Assert.assertEquals(2, manager.getStreamSubscribers("stream1").size());
        Assert.assertEquals(1, manager.getStreamSubscribers("stream2").size());

        manager.sunsubscribe(ch1, "stream1");
        Assert.assertEquals(1, manager.streamSubscriptionCount(ch1));
        Assert.assertEquals(1, manager.getStreamSubscribers("stream1").size());

        int removed = manager.unsubscribeAll(ch2);
        Assert.assertEquals(1, removed);
        Assert.assertEquals(0, manager.streamSubscriptionCount(ch2));
    }

    @Test
    public void testMixedSubscriptions() {
        PubSubManager manager = new PubSubManager();
        EmbeddedChannel ch1 = new EmbeddedChannel();

        manager.subscribe(ch1, "channel1");
        manager.psubscribe(ch1, "pattern*");
        manager.ssubscribe(ch1, "stream1");

        Assert.assertEquals(1, manager.subscriptionCount(ch1));
        Assert.assertEquals(1, manager.patternSubscriptionCount(ch1));
        Assert.assertEquals(1, manager.streamSubscriptionCount(ch1));

        int removed = manager.unsubscribeAll(ch1);
        Assert.assertEquals(3, removed);
        Assert.assertEquals(0, manager.subscriptionCount(ch1));
        Assert.assertEquals(0, manager.patternSubscriptionCount(ch1));
        Assert.assertEquals(0, manager.streamSubscriptionCount(ch1));
    }
}
