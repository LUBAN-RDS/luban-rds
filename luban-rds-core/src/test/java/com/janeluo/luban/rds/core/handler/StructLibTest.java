package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * StructLib 单元测试
 * 
 * <p>覆盖以下测试场景：
 * <ul>
 *   <li>基础类型打包/解包</li>
 *   <li>边界检查</li>
 *   <li>错误处理</li>
 *   <li>字节序处理</li>
 *   <li>Redisson 兼容性</li>
 * </ul>
 */
public class StructLibTest {
    
    private LuaCommandHandler luaCommandHandler;
    private MemoryStore memoryStore;
    private int database;

    @Before
    public void setUp() {
        luaCommandHandler = new LuaCommandHandler();
        memoryStore = new DefaultMemoryStore();
        database = 0;
    }

    /* ==================== 基础类型测试 ==================== */

    @Test
    public void testPackUnpackDouble() {
        String script = 
            "local packed = struct.pack('d', 123.456) " +
            "local val = struct.unpack('d', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 123.456", resp.contains("123.456"));
    }

    @Test
    public void testPackUnpackUnsignedLong() {
        String script = 
            "local packed = struct.pack('L', 4294967295) " +
            "local val = struct.unpack('L', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 4294967295 (max uint32)", resp.contains("4294967295"));
    }

    @Test
    public void testPackUnpackSignedLong() {
        String script = 
            "local packed = struct.pack('l', -123456) " +
            "local val = struct.unpack('l', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain -123456", resp.contains("-123456"));
    }

    @Test
    public void testPackUnpackUnsignedShort() {
        String script = 
            "local packed = struct.pack('H', 65535) " +
            "local val = struct.unpack('H', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 65535 (max uint16)", resp.contains("65535"));
    }

    @Test
    public void testPackUnpackSignedShort() {
        String script = 
            "local packed = struct.pack('h', -1000) " +
            "local val = struct.unpack('h', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain -1000", resp.contains("-1000"));
    }

    @Test
    public void testPackUnpackByte() {
        String script = 
            "local packed = struct.pack('B', 255) " +
            "local val = struct.unpack('B', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 255", resp.contains("255"));
    }

    @Test
    public void testPackUnpackSignedByte() {
        String script = 
            "local packed = struct.pack('b', -128) " +
            "local val = struct.unpack('b', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain -128", resp.contains("-128"));
    }

    @Test
    public void testPackUnpackFixedString() {
        String script = 
            "local packed = struct.pack('c10', 'hello') " +
            "local val = struct.unpack('c10', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 'hello'", resp.contains("hello"));
    }

    @Test
    public void testPackUnpackVariableString() {
        String script = 
            "local packed = struct.pack('c0', 'hello world') " +
            "local val = struct.unpack('c0', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 'hello world'", resp.contains("hello world"));
    }

    /* ==================== 边界检查测试 ==================== */

    @Test
    public void testUnpackInsufficientDataForDouble() {
        String script = 
            "local packed = struct.pack('i', 123) " +
            "local val = struct.unpack('d', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // 应该返回 nil (数据不足)
        assertEquals("Should return nil for insufficient data", "$-1\r\n", resp);
    }

    @Test
    public void testUnpackInsufficientDataForLong() {
        String script = 
            "local packed = struct.pack('h', 123) " +
            "local val = struct.unpack('L', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertEquals("Should return nil for insufficient data", "$-1\r\n", resp);
    }

    @Test
    public void testUnpackInsufficientDataForShort() {
        String script = 
            "local packed = struct.pack('b', 123) " +
            "local val = struct.unpack('H', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertEquals("Should return nil for insufficient data", "$-1\r\n", resp);
    }

    @Test
    public void testUnpackEmptyData() {
        String script = 
            "local packed = '' " +
            "local val = struct.unpack('d', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertEquals("Should return nil for empty data", "$-1\r\n", resp);
    }

    @Test
    public void testUnpackFixedStringInsufficientData() {
        String script = 
            "local packed = struct.pack('c5', 'abc') " +
            "local val = struct.unpack('c10', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertEquals("Should return nil for insufficient string data", "$-1\r\n", resp);
    }

    @Test
    public void testUnpackVariableStringEmptyData() {
        String script = 
            "local packed = '' " +
            "local val = struct.unpack('c0', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // c0 应该返回空字符串
        assertTrue("Should return empty string for c0 with empty data", resp.startsWith("$0\r\n"));
    }

    /* ==================== 字节序测试 ==================== */

    @Test
    public void testBigEndian() {
        String script = 
            "local packed = struct.pack('>i', 0x12345678) " +
            "local val = struct.unpack('>i', packed) " +
            "return string.format('0x%08X', val)";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 0x12345678", resp.contains("0x12345678"));
    }

    @Test
    public void testLittleEndian() {
        String script = 
            "local packed = struct.pack('<i', 0x12345678) " +
            "local val = struct.unpack('<i', packed) " +
            "return string.format('0x%08X', val)";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should contain 0x12345678", resp.contains("0x12345678"));
    }

    @Test
    public void testMixedEndian() {
        String script = 
            "local packed = struct.pack('>d<i', 123.456, 1000) " +
            "local d, i = struct.unpack('>d<i', packed) " +
            "return {d, i}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should return array with 2 elements", resp.startsWith("*2\r\n"));
        assertTrue("Should contain 123.456", resp.contains("123.456"));
        assertTrue("Should contain 1000", resp.contains("1000"));
    }

    /* ==================== struct.size 测试 ==================== */

    @Test
    public void testSizeBasic() {
        String script = "return struct.size('dLc10')";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // d=8, L=4, c10=10, total=22
        assertTrue("Size should be 22", resp.contains(":22\r\n"));
    }

    @Test
    public void testSizeVariableString() {
        String script = "return struct.size('dLc0')";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // c0 has variable size, should return nil
        assertEquals("Should return nil for variable size format (c0)", "$-1\r\n", resp);
    }

/* ==================== Redisson 兼容性测试 ==================== */

@Test
    public void testRedissonLc0Lc0CombinedFormat() {
        String script = 
            "local packed = struct.pack('Lc0', 'mykey') " +
            "local results = {struct.unpack('Lc0', packed)} " +
            "return {#results, results[1]}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("testRedissonLc0Lc0CombinedFormat result: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array with 2 elements", resp.startsWith("*2\r\n"));
        assertTrue("First element should be 1 (count)", resp.contains(":1\r\n"));
        assertTrue("Should contain 'mykey'", resp.contains("mykey"));
    }

    @Test
    public void testRedissonDLc0ReturnCount() {
        String script = 
            "local packed = struct.pack('dLc0', 12345.678, 'helloWorld') " +
            "local results = {struct.unpack('dLc0', packed)} " +
            "return #results";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertEquals("struct.unpack('dLc0', ...) should return 2 values (d + Lc0 combined)", ":2\r\n", resp);
    }

    @Test
    public void testRedissonLc0Format() {
        String script = 
            "local packed = struct.pack('Lc0', 'mykey') " +
            "local str = struct.unpack('Lc0', packed) " +
            "return {#str, str}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("testRedissonLc0Format result: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array with 2 elements", resp.startsWith("*2\r\n"));
        assertTrue("Should contain length 5", resp.contains(":5\r\n"));
        assertTrue("Should contain string", resp.contains("mykey"));
    }

    /* ==================== 复合格式测试 ==================== */

    @Test
    public void testComplexFormat() {
        String script = 
            "local packed = struct.pack('dLc0', 1.5, 'abc') " +
            "local d, c = struct.unpack('dLc0', packed) " +
            "return {d, c}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("testComplexFormat result: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array with 2 elements", resp.startsWith("*2\r\n"));
        assertTrue("Should contain double", resp.contains("1.5"));
        assertTrue("Should contain string", resp.contains("abc"));
    }

    @Test
    public void testMultipleValues() {
        String script = 
            "local packed = struct.pack('iii', 100, 200, 300) " +
            "local a, b, c = struct.unpack('iii', packed) " +
            "return {a, b, c}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue("Should return array with 3 elements", resp.startsWith("*3\r\n"));
        assertTrue("Should contain 100", resp.contains("100"));
        assertTrue("Should contain 200", resp.contains("200"));
        assertTrue("Should contain 300", resp.contains("300"));
    }

    /* ==================== 错误处理测试 ==================== */

    @Test
    public void testUnpackWithPosition() {
        String script = 
            "local packed = struct.pack('iiii', 1, 2, 3, 4) " +
            "local val = struct.unpack('i', packed, 5) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // Position 5 (1-based) = byte offset 4, should read the second int
        assertTrue("Should contain 2 (second integer)", resp.contains("2"));
    }

    @Test
    public void testUnpackWithInvalidPosition() {
        String script = 
            "local packed = struct.pack('i', 123) " +
            "local val = struct.unpack('d', packed, 100) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // Position out of bounds should return nil
        assertEquals("Should return nil for position out of bounds", "$-1\r\n", resp);
    }

    @Test
    public void testPackStringTruncation() {
        String script = 
            "local packed = struct.pack('c5', 'helloworld') " +
            "local val = struct.unpack('c5', packed) " +
            "return val";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // String should be truncated to 5 characters
        assertTrue("Should contain 'hello' (truncated)", resp.contains("hello"));
        assertFalse("Should not contain 'world'", resp.contains("world"));
    }

    @Test
    public void testPackStringPadding() {
        String script = 
            "local packed = struct.pack('c10', 'abc') " +
            "local val = struct.unpack('c10', packed) " +
            "return string.len(val)";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        // String should be padded to 10 characters (with null bytes)
        assertTrue("String length should be 10", resp.contains(":10\r\n"));
    }

    /* ==================== 性能测试 ==================== */

    @Test
    public void testBatchUnpack() {
        // 测试批量解包性能
        StringBuilder fmt = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            fmt.append("i");
        }
        
        String script = 
            "local packed = struct.pack('" + fmt.toString() + "', " +
            "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20," +
            "21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40," +
            "41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60," +
            "61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,77,78,79,80," +
            "81,82,83,84,85,86,87,88,89,90,91,92,93,94,95,96,97,98,99,100) " +
            "local results = {struct.unpack('" + fmt.toString() + "', packed)} " +
            "return #results";
        
        long start = System.currentTimeMillis();
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        long elapsed = System.currentTimeMillis() - start;
        
        String resp = result.toString();
        assertTrue("Should unpack 100 integers", resp.contains(":100\r\n"));
        System.out.println("Batch unpack 100 integers took " + elapsed + "ms");
    }
}