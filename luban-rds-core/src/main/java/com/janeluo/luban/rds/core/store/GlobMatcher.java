package com.janeluo.luban.rds.core.store;

import java.util.regex.Pattern;

/**
 * Redis glob 模式匹配（stringmatchlen 语义）。
 * 支持 *（任意序列）、?（单字符）、[...]（字符类，^ 取反、a-z 区间）、\ 转义；
 * 未闭合的 [ 按字面处理；空类 [] 永不匹配、[^] 匹配任意单字符；
 * 其余字符全部字面匹配。
 */
public final class GlobMatcher {

    private GlobMatcher() {}

    /** null / "*" 视为匹配全部。 */
    public static boolean match(String text, String pattern) {
        if (pattern == null || "*".equals(pattern)) {
            return true;
        }
        return compile(pattern).matcher(text).matches();
    }

    private static Pattern compile(String pattern) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*':
                    re.append(".*");
                    i++;
                    break;
                case '?':
                    re.append('.');
                    i++;
                    break;
                case '[': {
                    // 查找闭合 ']'（首个字符为 ^ 时从 ^ 之后找）
                    int j = i + 1;
                    boolean negate = j < pattern.length() && pattern.charAt(j) == '^';
                    if (negate) j++;
                    int close = pattern.indexOf(']', j);
                    if (close < 0) {
                        // 未闭合：按字面 '['
                        re.append("\\[");
                        i++;
                    } else if (close == j) {
                        // 空类：[] 永不匹配；[^] 匹配任意单字符（与 Redis 一致）
                        re.append(negate ? "[\\s\\S]" : "(?!)");
                        i = close + 1;
                    } else {
                        // 归一化类体：保留 a-z 区间语义（- 不转义）；反向区间 [z-a] 交换边界
                        StringBuilder body = new StringBuilder();
                        for (int k = j; k < close; ) {
                            char cc = pattern.charAt(k);
                            if (cc == '\\') {
                                // 转义序列原样保留
                                body.append(cc);
                                if (k + 1 < close) {
                                    body.append(pattern.charAt(k + 1));
                                }
                                k += 2;
                            } else if (k + 2 < close && pattern.charAt(k + 1) == '-'
                                    && pattern.charAt(k + 2) != '\\') {
                                // plain - plain 区间；边界逆序时交换（与 Redis 一致）
                                char lo = cc;
                                char hi = pattern.charAt(k + 2);
                                if (lo > hi) {
                                    char t = lo;
                                    lo = hi;
                                    hi = t;
                                }
                                body.append(lo).append('-').append(hi);
                                k += 3;
                            } else {
                                body.append(cc);
                                k++;
                            }
                        }
                        re.append('[');
                        if (negate) re.append('^');
                        for (int n = 0; n < body.length(); n++) {
                            char bc = body.charAt(n);
                            if (bc == '\\' || bc == ']' || (bc == '^' && n > 0)) {
                                re.append('\\').append(bc);
                            } else {
                                re.append(bc);
                            }
                        }
                        re.append(']');
                        i = close + 1;
                    }
                    break;
                }
                case '\\':
                    if (i + 1 < pattern.length()) {
                        re.append(Pattern.quote(String.valueOf(pattern.charAt(i + 1))));
                        i += 2;
                    } else {
                        re.append("\\\\");
                        i++;
                    }
                    break;
                default:
                    re.append(Pattern.quote(String.valueOf(c)));
                    i++;
            }
        }
        // DOTALL：? 与 * 需匹配换行符（Redis 匹配任意字节）
        return Pattern.compile(re.toString(), Pattern.DOTALL);
    }
}
