package com.janeluo.luban.rds.core.store;

/**
 * 对齐 Redis 7.x d2string/fpconv 的 double 输出：
 * nan / inf / -inf；0 与 -0.0 → "0"；整值（可转 long）→ 整数串；
 * 其余 → 最短往返表示，并将 Java 科学计数法归一化为 Redis 风格（小写 e、指数补零到 2 位）。
 */
public final class RedisDoubleFormatter {

    private RedisDoubleFormatter() {}

    public static String format(double value) {
        if (Double.isNaN(value)) return "nan";
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        if (value == 0.0) return "0";
        long lv = (long) value;
        if ((double) lv == value) {
            return Long.toString(lv);
        }
        String s = Double.toString(value);
        int e = s.indexOf('E');
        if (e < 0) {
            return s;
        }
        // "1.5E-5" → "1.5e-05"
        String mantissa = s.substring(0, e);
        String exp = s.substring(e + 1);
        boolean neg = exp.startsWith("-");
        String digits = neg ? exp.substring(1) : exp;
        if (digits.length() < 2) {
            digits = "0" + digits;
        }
        // 科学计数法下 mantissa 恒为一位非零数字加小数部分，如 "1.0E20" → "1e+20"（整值但超出 long 范围）
        if (mantissa.endsWith(".0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 2);
        }
        return mantissa + "e" + (neg ? "-" : "+") + digits;
    }
}
