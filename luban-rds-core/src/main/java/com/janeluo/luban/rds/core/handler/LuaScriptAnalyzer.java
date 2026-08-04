package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.acl.ACLCommandCategories;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lua 脚本只读性分析器。
 *
 * <p>用于集群从节点（slave）判定 EVAL/EVALSHA 脚本是否可在本节点执行。
 * 对齐 Redis 7 {@code evalGetCommandFlags} / {@code luaCreateFunction} 的脚本 flag 机制：
 * <ol>
 *   <li><b>shebang 声明优先</b>：脚本首行 {@code #!lua flags=...} 声明 {@code no-writes} /
 *       {@code allow-omit-writes} 时，直接判定为只读。</li>
 *   <li><b>静态扫描兜底</b>：正则提取所有 {@code redis.call} / {@code redis.pcall} 调用的
 *       命令名字面量，若全部属于只读命令集合则判定为只读；任一为写命令或无法识别则保守判为写。</li>
 * </ol>
 *
 * <p>判定结果按脚本 SHA1 缓存，避免每次 EVAL 重复扫描。
 *
 * <p><b>保守原则</b>：无法判定（空脚本、解析失败、动态命令名）一律判为写（返回 {@code false}），
 * 与 Redis 静态分析失败时的行为一致，避免误放行写脚本到从节点。
 *
 * @author janeluo
 * @since 1.0.14
 */
public final class LuaScriptAnalyzer {

    private LuaScriptAnalyzer() {
    }

    /** 按脚本 SHA1 缓存的只读判定结果。 */
    private static final ConcurrentHashMap<String, Boolean> READONLY_CACHE = new ConcurrentHashMap<>();

    /**
     * 只读补充集合：这些命令不修改数据，但未在 ACL 的 @read 类别中
     * （散落在 @keyspace/@connection/@server 等类别）。
     * 用于静态扫描兜底时的只读判定。
     */
    private static final Set<String> READONLY_EXTRA;
    static {
        Set<String> s = new HashSet<>();
        // 键空间只读探查（@keyspace 中的读子集）
        Collections.addAll(s,
                "EXISTS", "TYPE", "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME",
                "OBJECT", "MEMORY", "DUMP", "RANDOMKEY", "SCAN", "KEYS",
                "DBSIZE", "TOUCH", "SORT", "WAIT",
                // 位图只读
                "BITCOUNT", "BITPOS", "GETBIT", "BITFIELD_RO",
                // 字符串只读
                "STRLEN", "GETRANGE", "SUBSTR",
                // 连接/管理只读
                "PING", "ECHO", "INFO", "HELLO", "COMMAND"
        );
        READONLY_EXTRA = Collections.unmodifiableSet(s);
    }

    /** 匹配 redis.call / redis.pcall 调用，捕获命令名字面量。 */
    private static final Pattern REDIS_CALL_PATTERN = Pattern.compile(
            "redis\\.(?:p?call)\\s*\\(\\s*(['\"])([A-Za-z][A-Za-z0-9_]*)\\1",
            Pattern.CASE_INSENSITIVE);

    /** 匹配首行 shebang：#!lua flags=... 或 #!lua flags=... name=... */
    private static final Pattern SHEBANG_PATTERN = Pattern.compile(
            "^\\s*#!lua\\b.*\\bflags\\s*=\\s*([A-Za-z0-9_,-]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 判定脚本是否只读（不在从节点执行写操作）。
     *
     * @param script Lua 脚本文本，允许为 null
     * @return true 表示脚本只读，可在从节点执行；false 表示含写操作或无法判定（保守判为写）
     */
    public static boolean isReadOnlyScript(String script) {
        if (script == null || script.isEmpty()) {
            return false;
        }
        String sha1 = sha1Hex(script);
        Boolean cached = READONLY_CACHE.get(sha1);
        if (cached != null) {
            return cached;
        }
        boolean result = analyze(script);
        READONLY_CACHE.put(sha1, result);
        return result;
    }

    /**
     * 清空判定缓存（SCRIPT FLUSH 等场景调用，避免持有过期脚本引用）。
     */
    public static void invalidateCache() {
        READONLY_CACHE.clear();
    }

    /**
     * 实际分析逻辑：shebang 优先，静态扫描兜底。
     */
    private static boolean analyze(String script) {
        // ① shebang 声明优先
        Boolean shebang = evaluateShebang(script);
        if (shebang != null) {
            return shebang;
        }
        // ② 静态扫描兜底
        return staticAnalyzeCalls(script);
    }

    /**
     * 解析首行 #!lua flags=... 声明。
     *
     * @return Boolean.TRUE=声明只读，Boolean.FALSE=显式声明可写，
     *         null=无 shebang 或无相关 flag，继续静态分析
     */
    private static Boolean evaluateShebang(String script) {
        // shebang 必须在首行；取第一行判定
        int nl = script.indexOf('\n');
        String firstLine = nl > 0 ? script.substring(0, nl) : script;
        Matcher m = SHEBANG_PATTERN.matcher(firstLine);
        if (!m.find()) {
            return null;
        }
        String flags = m.group(1).toLowerCase();
        // allow-omit-writes：脚本声明不执行写（Redis 7.2 shebang flag，等价 no-writes）
        // 兼容历史拼写 "allow-om-writes"（Redis 早期文档笔误，宽容识别）
        if (flags.contains("allow-omit-writes") || flags.contains("allow-om-writes")
                || flags.contains("no-writes") || flags.contains("no_writes")) {
            return Boolean.TRUE;
        }
        // 存在 shebang 但未声明只读相关 flag：不强制，继续静态分析
        return null;
    }

    /**
     * 静态扫描所有 redis.call / redis.pcall 调用，判定命令读写性。
     *
     * <p>规则：
     * <ul>
     *   <li>无任何调用（纯计算脚本，如 {@code return 1}）→ 只读</li>
     *   <li>所有调用命令均属只读集合 → 只读</li>
     *   <li>任一命令属写集合，或无法识别 → 保守判为写</li>
     * </ul>
     */
    private static boolean staticAnalyzeCalls(String script) {
        Matcher m = REDIS_CALL_PATTERN.matcher(script);
        // 无任何 redis.call/pcall 调用（纯计算脚本，如 return 1）视为只读；
        // 存在调用时，循环内任一写/未知命令即提前返回 false，此处到达即全部命中只读集合。
        while (m.find()) {
            String command = m.group(2).toUpperCase();
            if (isWriteCommand(command)) {
                return false;
            }
            // 未知命令（既非已知只读、也非已知写）保守判为写
            if (!isKnownReadCommand(command)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 命令是否为已知写命令（复用 ACL @write 类别）。
     */
    private static boolean isWriteCommand(String upperCommand) {
        return ACLCommandCategories.isCommandInCategory(upperCommand, "@write");
    }

    /**
     * 命令是否为已知只读命令（ACL @read 类别 + 只读补充集合）。
     */
    private static boolean isKnownReadCommand(String upperCommand) {
        if (ACLCommandCategories.isCommandInCategory(upperCommand, "@read")) {
            return true;
        }
        return READONLY_EXTRA.contains(upperCommand);
    }

    /**
     * 计算脚本 SHA1（与 LuaCommandHandler.getSha1 口径一致，用于缓存键）。
     */
    private static String sha1Hex(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
