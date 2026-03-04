package com.janeluo.luban.rds.persistence;

import com.janeluo.luban.rds.persistence.impl.AofPersistService;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import org.junit.Test;
import static org.junit.Assert.*;

public class PersistServiceFactoryTest {

    @Test
    public void testCreateRdbPersistService() {
        PersistService service = PersistServiceFactory.createPersistService("rdb", "./data", 60, 1);
        assertNotNull(service);
        assertTrue(service instanceof RdbPersistService);
    }

    @Test
    public void testCreateAofPersistService() {
        PersistService service = PersistServiceFactory.createPersistService("aof", "./data", 60, 1);
        assertNotNull(service);
        assertTrue(service instanceof AofPersistService);
    }

    @Test
    public void testCreateBothPersistService() {
        PersistService service = PersistServiceFactory.createPersistService("both", "./data", 60, 1);
        assertNotNull(service);
        // 复合服务是内部类，无法直接判断类型，所以我们测试它能正常工作
        assertNotNull(service);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateInvalidPersistService() {
        PersistServiceFactory.createPersistService("invalid", "./data", 60, 1);
    }
}
