package com.janeluo.luban.rds.core.store;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 对齐本机 Redis 7.0.12 的 double 输出（addReplyDouble 实测语义，等价于 C 的 %.17g 系实现）：
 * <ul>
 *   <li>nan / inf / -inf / 0 与 -0.0 → "nan" / "inf" / "-inf" / "0"</li>
 *   <li>|value| ≤ 2^62 且为整数 → 精确整数串（Redis 整数快路径，实测 4611686018427387392 完整保留）</li>
 *   <li>其余：按精确二进制值四舍五入到 17 位有效数字（HALF_EVEN，同 glibc），
 *       尾随零裁剪；|value| &gt; 2^62 或十进制指数 X &lt; -4 用科学计数法（小写 e、指数补零到 2 位），
 *       否则定点表示。</li>
 * </ul>
 */
public final class RedisDoubleFormatter {

    private RedisDoubleFormatter() {}

    private static final int PRECISION = 17;
    private static final MathContext MC = new MathContext(PRECISION, RoundingMode.HALF_EVEN);

    /** 2^62：实测本地 Redis 7.0.12 定点/科学计数法切换的绝对值上界。 */
    private static final double TWO_62 = 4611686018427387904.0;

    public static String format(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        if (value == 0.0) {
            return "0";
        }
        boolean negative = value < 0;
        BigDecimal abs = new BigDecimal(Math.abs(value));
        BigDecimal rounded = abs.round(MC);
        String fullDigits = rounded.unscaledValue().toString();
        int scale = rounded.scale();
        // 十进制指数 X：rounded = unscaled × 10^-scale，X = 有效位数 - 1 - scale
        int x = fullDigits.length() - 1 - scale;
        // 裁剪尾随零（对应 %g 行为）
        String s = fullDigits;
        int strip = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '0'; i--) {
            strip++;
        }
        if (strip > 0) {
            s = s.substring(0, s.length() - strip);
        }
        // Redis 整数快路径：|value| <= 2^62 且为整数 → 精确整数串
        if (Math.abs(value) <= TWO_62 && value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String body;
        if (Math.abs(value) > TWO_62 || x < -4) {
            // 科学计数法：1.5e-05 / 1e+21 / 9.9999999999999991e-22
            String mant = s.length() > 1 ? s.charAt(0) + "." + s.substring(1) : s;
            String esign = x < 0 ? "-" : "+";
            String edig = String.valueOf(Math.abs(x));
            if (edig.length() < 2) {
                edig = "0" + edig;
            }
            body = mant + "e" + esign + edig;
        } else if (x >= 0) {
            // 定点：123.456 / 1000000000000000000 / 0.00048999999999999998
            if (x + 1 >= s.length()) {
                body = s + "0".repeat(x + 1 - s.length());
            } else {
                body = s.substring(0, x + 1) + "." + s.substring(x + 1);
            }
        } else {
            body = "0." + "0".repeat(-1 - x) + s;
        }
        return negative ? "-" + body : body;
    }
}
