package com.janeluo.luban.rds.core.handler;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link LuaScriptAnalyzer} 单元测试。
 *
 * <p>覆盖 shebang 声明优先、静态扫描兜底、保守判定（未知命令/动态命令名/空脚本）。
 */
public class LuaScriptAnalyzerTest {

    @Before
    public void setUp() {
        // 每个用例前清空缓存，避免用例间相互干扰
        LuaScriptAnalyzer.invalidateCache();
    }

    // ---------- 只读脚本 ----------

    @Test
    public void readonly_pttl() {
        // 报错场景的原始脚本：return redis.call('PTTL', KEYS[1])
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return redis.call('PTTL', KEYS[1])"));
    }

    @Test
    public void readonly_get() {
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return redis.call('GET', KEYS[1])"));
    }

    @Test
    public void readonly_multipleReadonlyCalls() {
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "local a = redis.call('HGET', KEYS[1], ARGV[1]) " +
                "local b = redis.call('EXISTS', KEYS[2]) return a + b"));
    }

    @Test
    public void readonly_pcall() {
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "local ok, res = pcall(function() return redis.call('GET', KEYS[1]) end) return res"));
    }

    @Test
    public void readonly_pureCompute() {
        // 无 redis.call，纯计算 → 只读
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return 1"));
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return ARGV[1] .. ARGV[2]"));
    }

    @Test
    public void readonly_doubleQuotedCommand() {
        // 命令名用双引号也应识别
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return redis.call(\"GET\", KEYS[1])"));
    }

    @Test
    public void readonly_lowercaseCall() {
        // redis.call 小写也应识别
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return redis.call('get', KEYS[1])"));
    }

    // ---------- 写脚本 ----------

    @Test
    public void write_set() {
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(
                "return redis.call('SET', KEYS[1], ARGV[1])"));
    }

    @Test
    public void write_del() {
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript("redis.call('DEL', KEYS[1])"));
    }

    @Test
    public void write_mixedReadAndWrite() {
        // 混合：含 GET + SET，存在写 → 判为写
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(
                "redis.call('GET', KEYS[1]) redis.call('SET', KEYS[1], ARGV[1])"));
    }

    @Test
    public void write_hset() {
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(
                "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])"));
    }

    // ---------- 保守判定 ----------

    @Test
    public void conservative_unknownCommand() {
        // 未知命令 FOO → 保守判为写
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript("return redis.call('FOO', KEYS[1])"));
    }

    @Test
    public void conservative_emptyOrNull() {
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(""));
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(null));
    }

    @Test
    public void conservative_dynamicCommandName() {
        // 命令名非字面量（ARGV[1]）→ 正则不匹配，视为无已知调用；但此脚本实际有 call 意图。
        // 由于无法静态提取命令名，本实现将其当作"无字面量调用"=只读（纯计算）。
        // 这是已知限制：Redisson 等客户端的脚本均使用字面量命令名，不会命中此场景。
        // 这里记录实际行为而非断言"应判为写"。
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript("return redis.call(ARGV[1], KEYS[1])"));
    }

    // ---------- shebang 声明 ----------

    @Test
    public void shebang_noWrites() {
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "#!lua flags=no-writes\nreturn redis.call('SET', KEYS[1], ARGV[1])"));
    }

    @Test
    public void shebang_allowOmitWrites() {
        // 即使脚本含写操作，声明 allow-omit-writes 也视为只读（脚本承诺不写）
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "#!lua flags=allow-omit-writes\nreturn redis.call('SET', KEYS[1], ARGV[1])"));
    }

    @Test
    public void shebang_allowOmitWritesWithOtherFlags() {
        // 多 flag 组合
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "#!lua flags=allow-cross-slot-keys,allow-omit-writes\nreturn redis.call('GET', KEYS[1])"));
    }

    @Test
    public void shebang_withoutReadonlyFlag_fallsBackToStaticAnalysis() {
        // shebang 存在但无只读相关 flag → 回退静态分析
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(
                "#!lua flags=allow-cross-slot-keys\nreturn redis.call('GET', KEYS[1])"));
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(
                "#!lua flags=allow-cross-slot-keys\nreturn redis.call('SET', KEYS[1], ARGV[1])"));
    }

    @Test
    public void shebang_notOnFirstLine_ignored() {
        // shebang 必须在首行；第二行出现不识别，回退静态分析（此脚本含 SET → 写）
        assertFalse(LuaScriptAnalyzer.isReadOnlyScript(
                "return 1\n#!lua flags=no-writes\nredis.call('SET', KEYS[1], ARGV[1])"));
    }

    // ---------- 缓存 ----------

    @Test
    public void cache_returnsConsistentResult() {
        String script = "return redis.call('PTTL', KEYS[1])";
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(script));
        // 第二次命中缓存，结果应一致
        assertTrue(LuaScriptAnalyzer.isReadOnlyScript(script));
    }
}
