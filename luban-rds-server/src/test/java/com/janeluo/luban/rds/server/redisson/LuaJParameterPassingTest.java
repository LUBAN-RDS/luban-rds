package com.janeluo.luban.rds.server.redisson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LuaJ parameter passing test")
class LuaJParameterPassingTest {

    @Test
    @DisplayName("Test parameter passing through Lua script")
    void testParameterPassing() {
        System.out.println("=== Testing parameter passing through Lua script ===\n");
        
        // 序列化字段名 "key1"
        byte[] binaryField = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        
        // 使用 LuaString.valueOf 创建 LuaString
        LuaString luaField = LuaString.valueOf(binaryField);
        
        // 检查 LuaString 内部字节
        byte[] checkBytes = new byte[luaField.length()];
        luaField.copyInto(0, checkBytes, 0, checkBytes.length);
        
        System.out.println("Original bytes: " + bytesToHex(binaryField));
        System.out.println("LuaString internal bytes: " + bytesToHex(checkBytes));
        System.out.println("Match: " + java.util.Arrays.equals(binaryField, checkBytes));
        
        // 模拟 Lua 脚本调用 redis.call
        // 创建一个简单的 Lua 脚本来打印参数
        String script = "return ARGV[1]";
        
        // 设置 ARGV
        LuaTable argvTable = new LuaTable();
        argvTable.set(1, luaField);  // 使用 LuaString
        Globals globals = JsePlatform.standardGlobals();
        globals.set("ARGV", argvTable);
        
        // 加载并执行脚本
        LuaValue chunk = globals.load(script);
        LuaValue result = chunk.call();
        
        // 检查返回结果
        if (result.isstring()) {
            LuaString resultStr = result.checkstring();
            byte[] resultBytes = new byte[resultStr.length()];
            resultStr.copyInto(0, resultBytes, 0, resultBytes.length);
            
            System.out.println("\nResult from Lua script:");
            System.out.println("Result bytes: " + bytesToHex(resultBytes));
            System.out.println("Match: " + java.util.Arrays.equals(binaryField, resultBytes));
            
            assertArrayEquals(binaryField, resultBytes, "Binary data should be preserved through Lua");
        } else {
            System.out.println("\nResult is not a string: " + result.typename());
            fail("Expected string result");
        }
    }

    @Test
    @DisplayName("Test KEYS and ARGV through Lua")
    void testKeysAndArgv() {
        System.out.println("\n=== Testing KEYS and ARGV through Lua ===\n");
        
        byte[] binaryKey = new byte[]{(byte) 0xAC, (byte) 0xED, 0x00, 0x05};
        byte[] binaryArg = new byte[]{(byte) 0x80, (byte) 0x81, (byte) 0x9F};
        
        // 创建 KEYS 表
        LuaTable keysTable = new LuaTable();
        keysTable.set(1, LuaString.valueOf(binaryKey));
        Globals globals = JsePlatform.standardGlobals();
        globals.set("KEYS", keysTable);
        
        // 创建 ARGV 表
        LuaTable argvTable = new LuaTable();
        argvTable.set(1, LuaString.valueOf(binaryArg));
        globals.set("ARGV", argvTable);
        
        // 脚本返回 KEYS[1] 和 ARGV[1]
        String script = "return KEYS[1], ARGV[1]";
        LuaValue chunk = globals.load(script);
        Varargs result = chunk.invoke();
        
        // 检查 KEYS[1]
        LuaValue keyResult = result.arg(1);
        if (keyResult.isstring()) {
            LuaString keyStr = keyResult.checkstring();
            byte[] keyBytes = new byte[keyStr.length()];
            keyStr.copyInto(0, keyBytes, 0, keyBytes.length);
            
            System.out.println("KEYS[1]:");
            System.out.println("  Original: " + bytesToHex(binaryKey));
            System.out.println("  Result: " + bytesToHex(keyBytes));
            System.out.println("  Match: " + java.util.Arrays.equals(binaryKey, keyBytes));
            
            assertArrayEquals(binaryKey, keyBytes);
        }
        
        // 检查 ARGV[1]
        LuaValue argResult = result.arg(2);
        if (argResult.isstring()) {
            LuaString argStr = argResult.checkstring();
            byte[] argBytes = new byte[argStr.length()];
            argStr.copyInto(0, argBytes, 0, argBytes.length);
            
            System.out.println("\nARGV[1]:");
            System.out.println("  Original: " + bytesToHex(binaryArg));
            System.out.println("  Result: " + bytesToHex(argBytes));
            System.out.println("  Match: " + java.util.Arrays.equals(binaryArg, argBytes));
            
            assertArrayEquals(binaryArg, argBytes);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}