package com.janeluo.luban.rds.core.store;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OffHeapEntryTest {

    @Test
    void shouldHoldBufferAndMetadata() {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(10);
        buf.writeBytes("hello".getBytes());
        OffHeapEntry entry = new OffHeapEntry(buf, 5, 1000L, 2000L);
        assertEquals(5, entry.getLen());
        assertEquals(1000L, entry.getExpireTime());
        assertEquals(2000L, entry.getLastAccessTime());
        assertSame(buf, entry.getBuffer());
        buf.release();
    }

    @Test
    void isExpiredShouldCheckExpireTime() {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(1);
        OffHeapEntry noExpire = new OffHeapEntry(buf, 0, 0L, 0L);
        assertFalse(noExpire.isExpired());
        OffHeapEntry past = new OffHeapEntry(buf, 0, System.currentTimeMillis() - 1, 0L);
        assertTrue(past.isExpired());
        OffHeapEntry future = new OffHeapEntry(buf, 0, System.currentTimeMillis() + 10000, 0L);
        assertFalse(future.isExpired());
        buf.release();
    }

    @Test
    void updateAccessTimeShouldMutate() {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(1);
        OffHeapEntry entry = new OffHeapEntry(buf, 0, 0L, 0L);
        long now = System.currentTimeMillis();
        entry.updateAccessTime(now);
        assertEquals(now, entry.getLastAccessTime());
        buf.release();
    }

    @Test
    void hasExpireTimeShouldReflectExpireField() {
        ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(1);
        assertFalse(new OffHeapEntry(buf, 0, 0L, 0L).hasExpireTime());
        assertTrue(new OffHeapEntry(buf, 0, 1L, 0L).hasExpireTime());
        buf.release();
    }
}
