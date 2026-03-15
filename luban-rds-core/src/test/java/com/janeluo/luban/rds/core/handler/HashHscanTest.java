package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HashHscanTest {
    private MemoryStore store;
    private HashCommandHandler handler;
    private final int db = 0;

    @Before
    public void setUp() {
        store = new DefaultMemoryStore();
        handler = new HashCommandHandler();
    }

    @Test
    public void testHscanBasic() {
        store.hset(db, "hk", "a1", "v1");
        store.hset(db, "hk", "a2", "v2");
        store.hset(db, "hk", "b1", "v3");
        
        String[] args = new String[]{RdsCommandConstant.HSCAN, "hk", "0", "MATCH", "a*", "COUNT", "10"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        assertTrue(s.startsWith("*2\r\n"));
        assertTrue(s.contains("$1\r\n0\r\n"));
        assertTrue(s.contains("$2\r\na1\r\n"));
        assertTrue(s.contains("$2\r\nv1\r\n"));
        assertTrue(s.contains("$2\r\na2\r\n"));
        assertTrue(s.contains("$2\r\nv2\r\n"));
    }

    @Test
    public void testHscanWithChineseChars() {
        String json = "{\"name\":\"测试用户\",\"runtime\":\"1 天 0 小时\",\"disk\":\"本地磁盘\"}";
        
        store.hset(db, "serverInfo:monitor", "server_1", json);
        
        String[] args = new String[]{RdsCommandConstant.HSCAN, "serverInfo:monitor", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        
        assertTrue("Should start with *2", s.startsWith("*2\r\n"));
        assertTrue("Should contain complete JSON", s.contains(json));
        
        int jsonStart = s.indexOf(json);
        assertTrue("JSON should be in response", jsonStart > 0);
        
        int dollarPos = s.lastIndexOf("$", jsonStart);
        assertTrue("Should find $ before JSON", dollarPos >= 0);
        
        String lengthPart = s.substring(dollarPos + 1, s.indexOf("\r\n", dollarPos));
        int declaredLength = Integer.parseInt(lengthPart);
        
        int actualLength = json.getBytes(StandardCharsets.ISO_8859_1).length;
        
        assertEquals("Length should match", actualLength, declaredLength);
    }
    
    @Test
    public void testHscanWithLargeJson() {
        StringBuilder largeJson = new StringBuilder();
        largeJson.append("{\"@class\":\"com.deliverik.infogovernor.core.monitor.bo.ServerInfo\",");
        largeJson.append("\"cpu\":{\"cpuModel\":\"Intel Core i7-10700K\",\"cpuNum\":8},");
        largeJson.append("\"jvm\":{\"runTime\":\"1 天 0 小时 0 分\",\"version\":\"1.8.0_291\"},");
        largeJson.append("\"sysFiles\":[\"java.util.ArrayList\",[");
        largeJson.append("{\"dirName\":\"C:\\\\\",\"typeName\":\"本地磁盘\"},");
        largeJson.append("{\"dirName\":\"D:\\\\\",\"typeName\":\"本地磁盘\"}");
        largeJson.append("]]}");
        
        String json = largeJson.toString();
        
        store.hset(db, "test:large", "field1", json);
        
        String[] args = new String[]{RdsCommandConstant.HSCAN, "test:large", "0", "COUNT", "100"};
        Object resp = handler.handle(db, args, store);
        String s = String.valueOf(resp);
        
        assertTrue("Should contain complete JSON", s.contains(json));
        assertTrue("Should end with \\r\\n", s.endsWith("\r\n"));
    }
}