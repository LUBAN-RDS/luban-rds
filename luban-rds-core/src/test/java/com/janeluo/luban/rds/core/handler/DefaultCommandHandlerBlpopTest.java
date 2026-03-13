package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DefaultCommandHandlerBlpopTest {

    private DefaultCommandHandler commandHandler;
    private MemoryStore memoryStore;
    private int database = 0;

    @Before
    public void setUp() {
        commandHandler = new DefaultCommandHandler();
        memoryStore = new DefaultMemoryStore();
    }

    @Test
    public void testBlpopCommandIsRegistered() {
        // 首先添加元素到列表
        memoryStore.lpush(database, "testList", "value1");
        
        // 测试 BLPOP 命令
        String[] args = {"BLPOP", "testList", "0"};
        Object result = commandHandler.handle("BLPOP", database, args, memoryStore);
        
        System.out.println("BLPOP result: " + result);
        
        // 应该返回一个包含 key 和 value 的数组
        assertNotNull("BLPOP should return a result", result);
        String resultStr = result.toString();
        assertTrue("Result should be an array", resultStr.startsWith("*"));
        assertTrue("Result should contain testList", resultStr.contains("testList"));
        assertTrue("Result should contain value1", resultStr.contains("value1"));
    }

    @Test
    public void testBrpopCommandIsRegistered() {
        // 首先添加元素到列表
        memoryStore.rpush(database, "testList", "value1");
        
        // 测试 BRPOP 命令
        String[] args = {"BRPOP", "testList", "0"};
        Object result = commandHandler.handle("BRPOP", database, args, memoryStore);
        
        System.out.println("BRPOP result: " + result);
        
        // 应该返回一个包含 key 和 value 的数组
        assertNotNull("BRPOP should return a result", result);
        String resultStr = result.toString();
        assertTrue("Result should be an array", resultStr.startsWith("*"));
        assertTrue("Result should contain testList", resultStr.contains("testList"));
        assertTrue("Result should contain value1", resultStr.contains("value1"));
    }
}