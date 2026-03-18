package com.janeluo.luban.rds.cluster.node;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ClusterLink 单元测试
 */
public class ClusterLinkTest {

    private ClusterLink link;

    @Before
    public void setUp() {
        link = new ClusterLink();
    }

    @Test
    public void testDefaultConstructor() {
        assertNotNull(link);
        assertFalse(link.isConnected());
        assertTrue(link.getLastInteractionTime() > 0);
        assertEquals(0, link.getOutboundBufferSize());
    }

    @Test
    public void testParameterizedConstructor() {
        long time = System.currentTimeMillis();
        ClusterLink customLink = new ClusterLink(true, time, 1024);

        assertTrue(customLink.isConnected());
        assertEquals(time, customLink.getLastInteractionTime());
        assertEquals(1024, customLink.getOutboundBufferSize());
    }

    @Test
    public void testSettersAndGetters() {
        link.setConnected(true);
        assertTrue(link.isConnected());

        long time = System.currentTimeMillis();
        link.setLastInteractionTime(time);
        assertEquals(time, link.getLastInteractionTime());

        link.setOutboundBufferSize(2048);
        assertEquals(2048, link.getOutboundBufferSize());
    }

    @Test
    public void testUpdateInteractionTime() throws InterruptedException {
        long before = link.getLastInteractionTime();
        Thread.sleep(10);

        link.updateInteractionTime();
        long after = link.getLastInteractionTime();

        assertTrue(after > before);
    }

    @Test
    public void testReset() {
        link.setConnected(true);
        link.setOutboundBufferSize(1024);

        link.reset();

        assertFalse(link.isConnected());
        assertEquals(0, link.getOutboundBufferSize());
    }

    @Test
    public void testToString() {
        link.setConnected(true);
        link.setOutboundBufferSize(512);

        String str = link.toString();
        assertTrue(str.contains("connected=true"));
        assertTrue(str.contains("outboundBufferSize=512"));
    }
}
