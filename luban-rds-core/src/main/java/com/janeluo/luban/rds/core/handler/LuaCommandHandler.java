package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;

/**
 * 基于 LuaJ 的 Lua 脚本命令处理器。
 * <p>
 * 支持处理 EVAL、EVALSHA、SCRIPT 等命令，向脚本注入 KEYS、ARGV，
 * 并提供 redis.call / redis.pcall / redis.error_reply / redis.status_reply 接口，
 * 以模拟 Redis 原生 Lua 脚本行为。
 */
public class LuaCommandHandler implements CommandHandler {
    /** 支持的 Lua 相关命令集合。 */
    private final Set<String> supportedCommands = Sets.newHashSet(
            "EVAL", "EVALSHA", "SCRIPT"
    );
    private volatile Thread currentScriptThread;
    private volatile boolean scriptRunning = false;
    private volatile long scriptTimeoutMs = 5000L;
    
    /** 脚本缓存，key 为脚本 SHA1，value 为脚本文本。 */
    private final Map<String, String> scriptCache = Maps.newConcurrentMap();
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }

    /**
     * 脚本执行忙态检查（用于测试）。
     */
    boolean isScriptBusy() {
        Thread t = currentScriptThread;
        return t != null && t.isAlive();
    }
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 1) {
            return "-ERR wrong number of arguments for 'lua' command\r\n";
        }
        
        String command = args[0].toUpperCase();
        
        switch (command) {
            case "EVAL":
                return handleEval(database, args, store);
            case "EVALSHA":
                return handleEvalSha(database, args, store);
            case "SCRIPT":
                return handleScript(database, args, store);
            default:
                return "-ERR unknown subcommand for '" + command + "'\r\n";
        }
    }
    
    /**
     * 处理 EVAL 命令。
     * <p>命令格式：EVAL script numkeys key [key ...] arg [arg ...]</p>
     */
    private Object handleEval(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'eval' command\r\n";
        }
        
        String script = args[1];
        int numkeys;
        try {
            numkeys = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        if (numkeys < 0) {
            return "-ERR Number of keys can't be negative\r\n";
        }
        
        if (args.length < 3 + numkeys) {
            return "-ERR wrong number of arguments for 'eval' command\r\n";
        }
        
        String[] keys = new String[numkeys];
        String[] argv = new String[args.length - 3 - numkeys];
        
        System.arraycopy(args, 3, keys, 0, numkeys);
        System.arraycopy(args, 3 + numkeys, argv, 0, argv.length);
        
        return executeScript(database, script, keys, argv, store);
    }
    
    /**
     * 处理 EVALSHA 命令。
     * <p>命令格式：EVALSHA sha1 numkeys key [key ...] arg [arg ...]</p>
     */
    private Object handleEvalSha(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'evalsha' command\r\n";
        }
        
        String sha1 = args[1];
        String script = scriptCache.get(sha1);
        
        if (script == null) {
            return "-NOSCRIPT No matching script. Please use EVAL.\r\n";
        }
        
        int numkeys;
        try {
            numkeys = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        if (numkeys < 0) {
            return "-ERR Number of keys can't be negative\r\n";
        }
        
        if (args.length < 3 + numkeys) {
            return "-ERR wrong number of arguments for 'evalsha' command\r\n";
        }
        
        String[] keys = new String[numkeys];
        String[] argv = new String[args.length - 3 - numkeys];
        
        System.arraycopy(args, 3, keys, 0, numkeys);
        System.arraycopy(args, 3 + numkeys, argv, 0, argv.length);
        
        return executeScript(database, script, keys, argv, store);
    }
    
    /**
     * 处理 SCRIPT 命令分发。
     * 根据子命令调用 LOAD / EXISTS / FLUSH / KILL 等实现。
     */
    private Object handleScript(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'script' command\r\n";
        }
        
        String subcommand = args[1].toUpperCase();
        
        switch (subcommand) {
            case "LOAD":
                return handleScriptLoad(database, args, store);
            case "EXISTS":
                return handleScriptExists(database, args, store);
            case "FLUSH":
                return handleScriptFlush(database, args, store);
            case "KILL":
                return handleScriptKill(database, args, store);
            default:
                return "-ERR unknown subcommand for 'script' command\r\n";
        }
    }
    
    /**
     * 处理 SCRIPT LOAD 子命令。
     * <p>SCRIPT LOAD script：加载并缓存脚本并返回其 SHA1。</p>
     */
    private Object handleScriptLoad(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'script load' command\r\n";
        }
        
        String script = args[2];
        try {
            int sbytes = script.getBytes("UTF-8").length;
            long max = RuntimeConfig.getLuaMaxScriptBytes();
            if (sbytes > max) {
                return "-ERR Script exceeds max size\r\n";
            }
        } catch (java.io.UnsupportedEncodingException ignored) {}
        String sha1 = getSha1(script);
        if (!scriptCache.containsKey(sha1)) {
            scriptCache.put(sha1, script);
            RuntimeConfig.incrementCachedScriptsCount();
            try {
                int bytes = script.getBytes("UTF-8").length;
                RuntimeConfig.addCachedScriptsBytes(bytes);
            } catch (java.io.UnsupportedEncodingException ignored) {}
        } else {
            scriptCache.put(sha1, script);
        }
        
        return "$" + sha1.length() + "\r\n" + sha1 + "\r\n";
    }
    
    /**
     * 处理 SCRIPT EXISTS 子命令。
     * <p>SCRIPT EXISTS sha1 [sha1 ...]：判断一个或多个脚本是否已缓存。</p>
     */
    private Object handleScriptExists(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'script exists' command\r\n";
        }
        
        StringBuilder response = new StringBuilder();
        response.append("*")
               .append(args.length - 2)
               .append("\r\n");
        
        for (int i = 2; i < args.length; i++) {
            String sha1 = args[i];
            boolean exists = scriptCache.containsKey(sha1);
            response.append(":")
                   .append(exists ? 1 : 0)
                   .append("\r\n");
        }
        
        return response.toString();
    }
    
    /**
     * 处理 SCRIPT FLUSH 子命令，清空脚本缓存。
     */
    private Object handleScriptFlush(int database, String[] args, MemoryStore store) {
        scriptCache.clear();
        RuntimeConfig.resetCachedScriptsCount();
        RuntimeConfig.resetCachedScriptsBytes();
        return "+OK\r\n";
    }
    
    /**
     * 处理 SCRIPT KILL 子命令。
     * <p>当前实现不跟踪执行中的脚本，始终返回 OK。</p>
     */
    private Object handleScriptKill(int database, String[] args, MemoryStore store) {
        Thread t = currentScriptThread;
        if (t != null) {
            try {
                if (t.isAlive()) {
                    t.interrupt();
                }
            } finally {
                scriptRunning = false;
                currentScriptThread = null;
            }
            RuntimeConfig.incScriptKills();
            return "+OK\r\n";
        }
        return "-NOTBUSY No scripts in execution right now.\r\n";
    }
    
    /**
     * 执行 Lua 脚本。
     * <p>构造 Lua 环境，注入 KEYS / ARGV 以及 redis 库，然后执行脚本并返回 RESP 编码结果。</p>
     */
    private Object executeScript(int database, String script, String[] keys, String[] argv, MemoryStore store) {
        this.scriptTimeoutMs = RuntimeConfig.getLuaScriptTimeoutMs();
        final java.util.concurrent.atomic.AtomicReference<Object> resp = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try {
                // 检查线程中断状态
                if (Thread.currentThread().isInterrupted()) {
                    resp.set("-ERR Script execution interrupted\r\n");
                    return;
                }
                
                org.luaj.vm2.Globals globals = JsePlatform.standardGlobals();
                if (RuntimeConfig.isLuaSandboxEnabled()) {
                    if (!RuntimeConfig.isModuleAllowed("os")) {
                        globals.set("os", LuaValue.NIL);
                    }
                    if (!RuntimeConfig.isModuleAllowed("io")) {
                        globals.set("io", LuaValue.NIL);
                    }
                    if (!RuntimeConfig.isModuleAllowed("package")) {
                        globals.set("package", LuaValue.NIL);
                    }
                    if (!RuntimeConfig.isModuleAllowed("luajava")) {
                        globals.set("luajava", LuaValue.NIL);
                    }
                    String blocked = RuntimeConfig.getLuaBlockedFunctions();
                    if (blocked != null && !blocked.isEmpty()) {
                        String[] pairs = blocked.split(",");
                        for (String pair : pairs) {
                            String p = pair.trim();
                            int dot = p.indexOf('.');
                            if (dot > 0 && dot < p.length() - 1) {
                                String mod = p.substring(0, dot);
                                String fn = p.substring(dot + 1);
                                LuaValue tbl = globals.get(mod);
                                if (tbl.istable()) {
                                    tbl.set(fn, LuaValue.NIL);
                                }
                            }
                        }
                    }
                } else {
                    // 沙箱模式禁用时，不执行任何模块禁用逻辑，所有模块都可用
                }

                // 加载 struct 库以支持 Redisson 等客户端
                globals.load(new StructLib());
                
                // 注册 cjson 库
                LuaTable cjsonTable = new LuaTable();
                cjsonTable.set("encode", new CJsonEncodeFunction());
                cjsonTable.set("decode", new CJsonDecodeFunction());
                // 创建一个特殊的null值，用于标识JSON null
                LuaValue nullValue = new LuaUserdata(null) {
                    @Override
                    public boolean isnil() {
                        return true;
                    }
                };
                cjsonTable.set("null", nullValue);
                globals.set("cjson", cjsonTable);

                // Polyfill for table.getn (Lua 5.1 compatibility)
                LuaValue tableLib = globals.get("table");
                if (tableLib.get("getn").isnil()) {
                    tableLib.set("getn", new OneArgFunction() {
                        @Override
                        public LuaValue call(LuaValue arg) {
                             return arg.len();
                        }
                    });
                }
                
                // Polyfill for unpack (Lua 5.1 compatibility, LuaJ 5.2 uses table.unpack)
                if (globals.get("unpack").isnil()) {
                    globals.set("unpack", tableLib.get("unpack"));
                }

// Use raw bytes to create LuaString, preserving binary data exactly
                // LuaValue.valueOf(String) would UTF-8 encode characters 0x80-0x9F, corrupting binary data
                LuaTable keysTable = new LuaTable();
                for (int i = 0; i < keys.length; i++) {
                    byte[] keyBytes = keys[i].getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    LuaValue luaKey = org.luaj.vm2.LuaString.valueOf(keyBytes);
                    keysTable.set(i + 1, luaKey);
                }
                globals.set("KEYS", keysTable);
                LuaTable argvTable = new LuaTable();
                for (int i = 0; i < argv.length; i++) {
                    byte[] argvBytes = argv[i].getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    LuaValue luaArg = org.luaj.vm2.LuaString.valueOf(argvBytes);
                    argvTable.set(i + 1, luaArg);
                }
                globals.set("ARGV", argvTable);
                LuaTable redisTable = new LuaTable();
                redisTable.set("call", new RedisCallFunction(database, store));
                redisTable.set("pcall", new RedisPCallFunction(database, store));
                redisTable.set("error_reply", new RedisErrorReplyFunction());
                redisTable.set("status_reply", new RedisStatusReplyFunction());
                redisTable.set("sha1hex", new OneArgFunction() {
                    @Override
                    public LuaValue call(LuaValue arg) {
                        String s = arg.tojstring();
                        return LuaValue.valueOf(getSha1(s));
                    }
                });
                globals.set("redis", redisTable);
                long maxScriptBytes = RuntimeConfig.getLuaMaxScriptBytes();
                try {
                    int sbytes = script.getBytes("UTF-8").length;
                    if (sbytes > maxScriptBytes) {
                        resp.set("-ERR Script exceeds max size\r\n");
                        return;
                    }
                } catch (java.io.UnsupportedEncodingException ignored) {}
                
                // 检查线程中断状态
                if (Thread.currentThread().isInterrupted()) {
                    resp.set("-ERR Script execution interrupted\r\n");
                    return;
                }
                
                LuaValue chunk = globals.load(script);
                LuaValue result = chunk.call();
                resp.set(convertLuaValueToRedisResponse(result));
            } catch (Exception e) {
                // 捕获所有异常，避免线程崩溃
                resp.set("-ERR Error executing script: " + e.getMessage() + "\r\n");
            } finally {
                // 确保线程状态正确清理
                scriptRunning = false;
                currentScriptThread = null;
            }
        };
        Thread t = new Thread(task, "luban-rds-lua");
        currentScriptThread = t;
        scriptRunning = true;
        RuntimeConfig.incScriptExecutions();
        long start = System.currentTimeMillis();
        t.start();
        try {
            try {
                t.join(scriptTimeoutMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (t.isAlive()) {
                    t.interrupt();
                }
                RuntimeConfig.incScriptTimeouts();
                return "-ERR Script timed out\r\n";
            }
            if (t.isAlive()) {
                t.interrupt();
                RuntimeConfig.incScriptTimeouts();
                return "-ERR Script timed out\r\n";
            }
            long duration = System.currentTimeMillis() - start;
            RuntimeConfig.recordScriptDuration(duration);
            Object r = resp.get();
            if (r != null) {
                try {
                    String s = r.toString();
                    int bytes = s.getBytes("UTF-8").length;
                    long max = RuntimeConfig.getLuaMaxReturnBytes();
                    if (bytes > max) {
                        return "-ERR Script returned value too large\r\n";
                    }
                } catch (java.io.UnsupportedEncodingException ignored) {}
            }
            return r;
        } finally {
            // 确保线程状态正确清理
            scriptRunning = false;
            currentScriptThread = null;
        }
    }
    
    /**
     * 将 Lua 执行结果转换为 RESP 字符串，作为网络响应写回客户端。
     */
    private Object convertLuaValueToRedisResponse(LuaValue value) {
        if (value instanceof ErrorReplyValue) {
            ErrorReplyValue err = (ErrorReplyValue) value;
            return "-" + err.message + "\r\n";
        } else if (value instanceof StatusReplyValue) {
            StatusReplyValue status = (StatusReplyValue) value;
            return "+" + status.message + "\r\n";
        } else if (value.isnil()) {
            return "$-1\r\n";
        } else if (value.isboolean()) {
            return value.toboolean() ? ":1\r\n" : "$-1\r\n";
        } else if (value.isnumber()) {
            return ":" + value.toint() + "\r\n";
        } else if (value.isstring()) {
            String str = value.tojstring();
            byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            return "$" + bytes.length + "\r\n" + new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1) + "\r\n";
        } else if (value.istable()) {
            LuaTable table = (LuaTable) value;
            int length = table.length();
            StringBuilder response = new StringBuilder();
            response.append("*").append(length).append("\r\n");
            for (int i = 1; i <= length; i++) {
                LuaValue element = table.get(i);
                Object encoded = convertLuaValueToRedisResponse(element);
                response.append(encoded.toString());
            }
            return response.toString();
        } else {
            return "$-1\r\n";
        }
    }
    
    /**
     * 计算脚本内容的 SHA1，用于脚本缓存和 EVALSHA。
     */
    private String getSha1(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(script.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * redis.call 实现。
     * <p>支持 redis.call(cmd, arg1, arg2, ...) 以及 redis.call(cmd, {arg1, arg2, ...}) 两种调用方式。</p>
     */
    private static class RedisCallFunction extends VarArgFunction {
        private final int database;
        private final MemoryStore store;
        private long opsCounter = 0;
        
        public RedisCallFunction(int database, MemoryStore store) {
            this.database = database;
            this.store = store;
        }
        
        @Override
        public Varargs invoke(Varargs args) {
            opsCounter++;
            long maxOps = RuntimeConfig.getLuaMaxOpsPerScript();
            if (maxOps > 0 && opsCounter >= maxOps) {
                opsCounter = 0;
                long yieldMs = RuntimeConfig.getLuaYieldMs();
                if (yieldMs > 0) {
                    try { Thread.sleep(yieldMs); } catch (InterruptedException ignored) {}
                } else {
                    Thread.yield();
                }
            }
            LuaValue cmdValue = args.arg(1);
            String command = cmdValue.tojstring().toUpperCase();
            java.util.List<String> argList = new java.util.ArrayList<>();
            argList.add(command);
            
            int n = args.narg();
            if (n == 2 && args.arg(2).istable()) {
                LuaTable table = (LuaTable) args.arg(2);
                int length = table.length();
                for (int i = 1; i <= length; i++) {
                    LuaValue v = table.get(i);
                    if (v.isstring()) {
                        LuaString ls = v.checkstring();
                        byte[] bytes = new byte[ls.length()];
                        ls.copyInto(0, bytes, 0, bytes.length);
                        argList.add(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
                    } else {
                        argList.add(v.tojstring());
                    }
                }
            } else {
                for (int i = 2; i <= n; i++) {
                    LuaValue v = args.arg(i);
                    if (!v.isnil()) {
                        if (v.isstring()) {
                            LuaString ls = v.checkstring();
                            byte[] bytes = new byte[ls.length()];
                            ls.copyInto(0, bytes, 0, bytes.length);
                            argList.add(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
                        } else {
                            argList.add(v.tojstring());
                        }
                    }
                }
            }
            
            DefaultCommandHandler commandHandler = new DefaultCommandHandler();
            String[] argsArray = argList.toArray(new String[0]);
            Object result = commandHandler.handle(command, database, argsArray, store);
            
            return convertRedisResponseToLuaValue(result);
        }
    }
    
    /**
     * redis.pcall 实现。
     * <p>在 redis.call 的基础上捕获异常，并以表形式返回错误信息。</p>
     */
    private static class RedisPCallFunction extends VarArgFunction {
        private final int database;
        private final MemoryStore store;
        
        public RedisPCallFunction(int database, MemoryStore store) {
            this.database = database;
            this.store = store;
        }
        
        @Override
        public Varargs invoke(Varargs args) {
            try {
                RedisCallFunction callFunction = new RedisCallFunction(database, store);
                return callFunction.invoke(args);
            } catch (Exception e) {
                    LuaTable errorTable = new LuaTable();
                    errorTable.set(1, LuaValue.valueOf("err"));
                    errorTable.set(2, LuaValue.valueOf(e.getMessage()));
                    errorTable.set(LuaValue.valueOf("err"), LuaValue.valueOf(e.getMessage()));
                    return errorTable;
            }
        }
    }
    
    /**
     * 将服务器端 RESP 字符串解析为 LuaValue，用于 redis.call / redis.pcall 的返回值。
     */
    private static LuaValue convertRedisResponseToLuaValue(Object response) {
        if (response == null) {
            return LuaValue.FALSE;
        }
        String str = response.toString();
        if (str.isEmpty()) {
            return LuaValue.FALSE;
        }
        char prefix = str.charAt(0);
        if (prefix == '+') {
            int end = str.indexOf("\r\n");
            if (end <= 1) {
                return LuaValue.FALSE;
            }
            String value = str.substring(1, end);
            LuaTable t = new LuaTable();
            t.set("ok", LuaValue.valueOf(value));
            return t;
        } else if (prefix == ':') {
            int end = str.indexOf("\r\n");
            if (end <= 1) {
                return LuaValue.FALSE;
            }
            String numStr = str.substring(1, end);
            try {
                long v = Long.parseLong(numStr);
                return LuaValue.valueOf(v);
            } catch (NumberFormatException e) {
                return LuaValue.FALSE;
            }
        } else if (prefix == '$') {
            if (str.startsWith("$-1\r\n")) {
                return LuaValue.FALSE;
            }
            int lenEnd = str.indexOf("\r\n");
            if (lenEnd <= 1) {
                return LuaValue.FALSE;
            }
            String lenStr = str.substring(1, lenEnd);
            try {
                int length = Integer.parseInt(lenStr);
                int start = lenEnd + 2;
                if (length < 0) {
                    return LuaValue.FALSE;
                }
                if (start + length > str.length()) {
                    if (str.length() >= 2) {
                        String value = str.substring(start, str.length() - 2);
                        return LuaValue.valueOf(value);
                    } else {
                        return LuaValue.FALSE;
                    }
                } else {
                    String value = str.substring(start, start + length);
                    return LuaValue.valueOf(value);
                }
            } catch (NumberFormatException e) {
                return LuaValue.FALSE;
            }
        } else if (prefix == '*') {
            if (str.startsWith("*-1\r\n")) {
                return LuaValue.FALSE;
            }
            int lenEnd = str.indexOf("\r\n");
            if (lenEnd <= 1) {
                return LuaValue.FALSE;
            }
            String lenStr = str.substring(1, lenEnd);
            int count;
            try {
                count = Integer.parseInt(lenStr);
            } catch (NumberFormatException e) {
                return LuaValue.FALSE;
            }
            LuaTable table = new LuaTable();
            int index = lenEnd + 2;
            int elementIndex = 1;
            while (elementIndex <= count && index < str.length()) {
                char c = str.charAt(index);
                if (c == '+') {
                    int end = str.indexOf("\r\n", index);
                    if (end == -1) {
                        break;
                    }
                    String value = str.substring(index + 1, end);
                    table.set(elementIndex++, LuaValue.valueOf(value));
                    index = end + 2;
                } else if (c == ':') {
                    int end = str.indexOf("\r\n", index);
                    if (end == -1) {
                        break;
                    }
                    String numStr = str.substring(index + 1, end);
                    try {
                        long v = Long.parseLong(numStr);
                        table.set(elementIndex++, LuaValue.valueOf(v));
                    } catch (NumberFormatException e) {
                        table.set(elementIndex++, LuaValue.FALSE);
                    }
                    index = end + 2;
                } else if (c == '$') {
                    int lenPos = str.indexOf("\r\n", index);
                    if (lenPos == -1) {
                        break;
                    }
                    String lStr = str.substring(index + 1, lenPos);
                    int l;
                    try {
                        l = Integer.parseInt(lStr);
                    } catch (NumberFormatException e) {
                        table.set(elementIndex++, LuaValue.FALSE);
                        index = lenPos + 2;
                        continue;
                    }
                    if (l == -1) {
                        table.set(elementIndex++, LuaValue.FALSE);
                        index = lenPos + 2;
                    } else {
                        int start = lenPos + 2;
                        if (start + l > str.length()) {
                            if (str.length() >= 2) {
                                String value = str.substring(start, str.length() - 2);
                                table.set(elementIndex++, LuaValue.valueOf(value));
                            } else {
                                table.set(elementIndex++, LuaValue.FALSE);
                            }
                            index = str.length();
                        } else {
                            String value = str.substring(start, start + l);
                            table.set(elementIndex++, LuaValue.valueOf(value));
                            index = start + l + 2;
                        }
                    }
                } else if (c == '-') {
                    int end = str.indexOf("\r\n", index);
                    if (end == -1) {
                        break;
                    }
                    String msg = str.substring(index + 1, end);
                    throw new RuntimeException(msg);
                } else {
                    break;
                }
            }
            return table;
        } else if (prefix == '-') {
            int end = str.indexOf("\r\n");
            if (end <= 1) {
                throw new RuntimeException(str.substring(1));
            }
            String msg = str.substring(1, end);
            throw new RuntimeException(msg);
        } else {
            return LuaValue.FALSE;
        }
    }

    /**
     * 内部错误回复类型，对应 redis.error_reply。
     */
    private static class ErrorReplyValue extends LuaUserdata {
        private final String message;

        public ErrorReplyValue(String message) {
            super(message);
            this.message = message;
        }
    }

    /**
     * 内部状态回复类型，对应 redis.status_reply。
     */
    private static class StatusReplyValue extends LuaUserdata {
        private final String message;

        public StatusReplyValue(String message) {
            super(message);
            this.message = message;
        }
    }

    /**
     * redis.error_reply(message) 工厂函数。
     */
    private static class RedisErrorReplyFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            return new ErrorReplyValue(arg.tojstring());
        }
    }

    /**
     * redis.status_reply(message) 工厂函数。
     */
    private static class RedisStatusReplyFunction extends OneArgFunction {
        @Override
        public LuaValue call(LuaValue arg) {
            return new StatusReplyValue(arg.tojstring());
        }
    }
    
    /**
     * cjson.encode 函数实现。
     * 将 Lua 值转换为 JSON 字符串。
     */
    private static class CJsonEncodeFunction extends OneArgFunction {
        private static final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
        
        @Override
        public LuaValue call(LuaValue arg) {
            try {
                Object javaObj = convertLuaValueToJava(arg);
                String json = objectMapper.writeValueAsString(javaObj);
                return LuaValue.valueOf(json);
            } catch (Exception e) {
                throw new RuntimeException("cjson.encode error: " + e.getMessage());
            }
        }
        
        private Object convertLuaValueToJava(LuaValue value) {
            if (value.isnil()) {
                return null;
            } else if (value.isboolean()) {
                return value.toboolean();
            } else if (value.isnumber()) {
                return value.tojstring();
            } else if (value.isstring()) {
                return value.tojstring();
            } else if (value.istable()) {
                LuaTable table = (LuaTable) value;
                // 检查是否为数组
                boolean isArray = true;
                int length = 0;
                for (int i = 1; ; i++) {
                    if (table.get(i).isnil()) {
                        length = i - 1;
                        break;
                    }
                }
                // 检查是否有非数字键
                LuaValue key = LuaValue.NIL;
                while (true) {
                    Varargs next = table.next(key);
                    if (next.isnil(1)) break;
                    key = next.arg1();
                    if (!key.isnumber()) {
                        isArray = false;
                        break;
                    }
                }
                if (isArray && length > 0) {
                    java.util.List<Object> list = new java.util.ArrayList<>();
                    for (int i = 1; i <= length; i++) {
                        list.add(convertLuaValueToJava(table.get(i)));
                    }
                    return list;
                } else {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    key = LuaValue.NIL;
                    while (true) {
                        Varargs next = table.next(key);
                        if (next.isnil(1)) break;
                        key = next.arg(1);
                        LuaValue val = next.arg(2);
                        map.put(key.tojstring(), convertLuaValueToJava(val));
                    }
                    return map;
                }
            } else {
                return value.tojstring();
            }
        }
    }
    
    /**
     * cjson.decode 函数实现。
     * 将 JSON 字符串转换为 Lua 值。
     */
    private static class CJsonDecodeFunction extends OneArgFunction {
        private static final ObjectMapper objectMapper = new ObjectMapper();
        
        @Override
        public LuaValue call(LuaValue arg) {
            try {
                String json = arg.tojstring();
                JsonNode node = objectMapper.readTree(json);
                return convertJsonNodeToLua(node);
            } catch (Exception e) {
                throw new RuntimeException("cjson.decode error: " + e.getMessage());
            }
        }
        
        private LuaValue convertJsonNodeToLua(JsonNode node) {
            if (node == null || node.isNull()) {
                return LuaValue.NIL;
            } else if (node.isBoolean()) {
                return LuaValue.valueOf(node.asBoolean());
            } else if (node.isNumber()) {
                return LuaValue.valueOf(node.asText());
            } else if (node.isTextual()) {
                return LuaValue.valueOf(node.asText());
            } else if (node.isArray()) {
                ArrayNode arrayNode = (ArrayNode) node;
                LuaTable table = new LuaTable();
                for (int i = 0; i < arrayNode.size(); i++) {
                    table.set(i + 1, convertJsonNodeToLua(arrayNode.get(i)));
                }
                return table;
            } else if (node.isObject()) {
                ObjectNode objectNode = (ObjectNode) node;
                LuaTable table = new LuaTable();
                for (java.util.Iterator<String> it = objectNode.fieldNames(); it.hasNext(); ) {
                    String key = it.next();
                    table.set(key, convertJsonNodeToLua(objectNode.get(key)));
                }
                return table;
            } else {
                return LuaValue.NIL;
            }
        }
    }
}
