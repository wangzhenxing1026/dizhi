package com.fh.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 小工具 */
public final class Util {

    private Util() {
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // MD5 必然存在
            return String.valueOf(s.hashCode());
        }
    }

    /** 双精度格式化为最多 14 位小数（去除末尾多余 0），避免科学计数法 */
    public static String fmt14(double v) {
        String s = String.format("%.14f", v);
        // 去尾部 0
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }
}
