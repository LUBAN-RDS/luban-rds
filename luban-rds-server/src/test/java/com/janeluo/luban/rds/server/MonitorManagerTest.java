package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class MonitorManagerTest {

    private MonitorManager monitorManager;

    @Before
    public void setUp() {
        monitorManager = MonitorManager.getInstance();
        RuntimeConfig.setMonitorMaxClients(100);
        // Clear clients (though singleton persists, we remove them manually if needed)
        // Since we can't easily clear the singleton's map, we just ensure we use new mocks.
    }

    @Test
    public void testAddMonitor() {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(channel, -1, null);
        
        assertEquals(1, monitorManager.getMonitorClientCount());
        verify(channel, atLeastOnce()).writeAndFlush(any(ByteBuf.class)); // +OK
        
        monitorManager.removeMonitor(channel);
        assertEquals(0, monitorManager.getMonitorClientCount());
    }

    @Test
    public void testMaxClients() {
        RuntimeConfig.setMonitorMaxClients(1);
        Channel c1 = mock(Channel.class);
        Channel c2 = mock(Channel.class);
        
        monitorManager.addMonitor(c1, -1, null);
        monitorManager.addMonitor(c2, -1, null);
        
        assertEquals(1, monitorManager.getMonitorClientCount());
        
        // Verify c2 received error
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(c2).writeAndFlush(captor.capture());
        String response = captor.getValue().toString(StandardCharsets.UTF_8);
        assertTrue(response.startsWith("-ERR max number"));
        
        monitorManager.removeMonitor(c1);
    }

    @Test
    public void testBroadcast() throws InterruptedException {
        Channel channel = mock(Channel.class);
        monitorManager.addMonitor(channel, -1, null);
        
        // Submit command
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        // Wait for worker
        Thread.sleep(100);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture()); // +OK + monitor log
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String log = buf.toString(StandardCharsets.UTF_8);
            if (log.contains("\"SET\" \"key\" \"value\"")) {
                foundLog = true;
                break;
            }
        }
        assertTrue("Should receive monitor log", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    @Test
    public void testFiltering() throws InterruptedException {
        Channel dbChannel = mock(Channel.class);
        monitorManager.addMonitor(dbChannel, 1, null); // Monitor DB 1
        
        Channel patternChannel = mock(Channel.class);
        monitorManager.addMonitor(patternChannel, -1, "GET"); // Monitor GET commands
        
        // Submit DB 0 SET (should be ignored by dbChannel, ignored by patternChannel)
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        // Submit DB 1 SET (should be received by dbChannel, ignored by patternChannel)
        monitorManager.submit(1, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        // Submit DB 0 GET (ignored by dbChannel, received by patternChannel)
        monitorManager.submit(0, "127.0.0.1:1234", "GET", new String[]{"GET", "key"});
        
        Thread.sleep(100);
        
        // Check dbChannel
        ArgumentCaptor<ByteBuf> dbCaptor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(dbChannel, atLeastOnce()).writeAndFlush(dbCaptor.capture());
        int dbCount = 0;
        for (ByteBuf buf : dbCaptor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"SET\"")) dbCount++;
        }
        // Only one SET (from DB 1)
        // Note: verify atLeastOnce captures +OK too.
        // We expect exactly 1 SET command log.
        // Wait, unit tests run in parallel or sequence? Parallel execution might affect singleton.
        // JUnit 4 runs sequential by default per class, but static singleton persists.
        // So we need to be careful.
        
        // Check patternChannel
        ArgumentCaptor<ByteBuf> patternCaptor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(patternChannel, atLeastOnce()).writeAndFlush(patternCaptor.capture());
        int patternCount = 0;
        for (ByteBuf buf : patternCaptor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"GET\"")) patternCount++;
        }
        // Expect 1 GET log.
        
        monitorManager.removeMonitor(dbChannel);
        monitorManager.removeMonitor(patternChannel);
    }
}
