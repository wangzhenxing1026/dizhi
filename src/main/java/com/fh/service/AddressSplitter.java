package com.fh.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多楼栋地址拆分。
 * 策略（保守，宁可整串查询也不误拆）：
 * 1. 去掉括号别名后，若按 、，,；; 切分出的所有片段都是"楼栋片段"（带栋/座/楼/幢/单元/号楼后缀，
 *    或为纯编号/字母/中文数字），则提取公共前缀，逐片段生成"前缀+片段"查询；
 *    片段缺后缀时补上兄弟片段的后缀（如 A,B栋 → A栋、B栋）。
 * 2. 无分隔符时，识别 "AB栋"、"1/2座"、"1-5单元" 这类内嵌编号组并展开。
 * 3. 无法可靠拆分时返回整串地址（单元素列表）。
 */
public final class AddressSplitter {

    private static final Pattern SEP = Pattern.compile("[、，,；;]+");
    /** 楼栋片段：编号(+字母/中文数字)+楼栋后缀，或纯编号/字母/中文数字 */
    private static final Pattern UNIT_FRAGMENT = Pattern.compile(
            "[0-9A-Za-z一二三四五六七八九十]{1,4}(栋|座|楼|幢|号楼|单元)([A-Za-z一二三四五六七八九十]{0,3}(单元)?)?");
    private static final Pattern PURE_TOKEN = Pattern.compile("[0-9A-Za-z]{1,4}|[一二三四五六七八九十]{1,3}");
    private static final String UNIT_SUFFIX = "栋座楼幢号楼单元";
    /** 内嵌编号组：AB栋 / 1/2座 / 1-5单元 / 248,249.250栋 */
    private static final Pattern GROUP = Pattern.compile(
            "([0-9A-Za-z]{1,4}(?:[/／,，、.．\\-—－][0-9A-Za-z]{1,4})+)(栋|座|楼|幢|号楼|单元)");
    /** 连写字母：AB栋 */
    private static final Pattern LETTERS = Pattern.compile("([A-Za-z]{2,4})(栋|座|楼|幢|单元|号楼)");

    private AddressSplitter() {
    }

    public static List<String> split(String address) {
        List<String> out = new ArrayList<>();
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) {
            return out;
        }
        // 去掉括号别名（如 （华日阁、华东阁）、（连体）），避免其中的 、 干扰
        String stripped = a.replaceAll("[（(][^（()）]*[)）]", "").trim();
        if (stripped.isEmpty()) {
            stripped = a;
        }

        // 数字间的小数点当分隔符（创业花园248,249.250栋）
        String norm = stripped.replaceAll("(?<=\\d)\\.(?=\\d)", "、");

        List<String> bySep = trySplitBySeparator(norm);
        if (bySep != null) {
            out.addAll(bySep);
            return out;
        }
        List<String> byGroup = tryExpandGroup(norm);
        if (byGroup != null) {
            out.addAll(byGroup);
            return out;
        }
        out.add(a);
        return out;
    }

    /** 分隔符拆分：所有片段都是楼栋片段（首片段 = 公共前缀 + 楼栋片段）才拆 */
    private static List<String> trySplitBySeparator(String s) {
        if (!SEP.matcher(s).find()) {
            return null;
        }
        String[] parts = SEP.split(s);
        if (parts.length < 2) {
            return null;
        }
        // 首片段 = 公共前缀 + 尾部楼栋片段，取尾部最短楼栋 token 剥离出前缀
        String prefix = stripTrailingUnit(parts[0]);
        if (prefix == null || prefix.length() < 2) {
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            if (!isUnitFragment(parts[i])) {
                return null;
            }
        }
        // 找一个明确的楼栋后缀，用于给纯编号片段补后缀
        String unit = null;
        for (String p : parts) {
            Matcher m = UNIT_FRAGMENT.matcher(p).region(Math.max(0, p.length() - 6), p.length());
            if (m.matches()) {
                unit = m.group(1);
                break;
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            // 首片段取剥离后剩下的楼栋 token，避免重复拼前缀
            String token = i == 0 ? parts[0].substring(prefix.length()) : parts[i];
            if (PURE_TOKEN.matcher(token).matches() && unit != null) {
                token = token + unit;
            }
            out.add(prefix + token);
        }
        return out;
    }

    /** 从片段尾部剥离楼栋 token，返回剩余前缀；剥离失败返回 null */
    private static String stripTrailingUnit(String frag) {
        if (frag == null || frag.isEmpty()) {
            return null;
        }
        // 优先剥最短的纯编号尾部（佳兆业中心A → 佳兆业中心；香蜜新村5栋一 → 香蜜新村5栋）
        Matcher pm = PURE_TOKEN.matcher(frag);
        int start = -1;
        while (pm.find()) {
            if (pm.end() == frag.length()) {
                start = pm.start();
            }
        }
        if (start > 0) {
            return frag.substring(0, start);
        }
        // 再按楼栋片段剥（统建楼办公楼1栋 → 统建楼办公楼；安华工业区9栋A单元 → 安华工业区）
        Matcher um = UNIT_FRAGMENT.matcher(frag);
        while (um.find()) {
            if (um.end() == frag.length() && um.start() > 0) {
                return frag.substring(0, um.start());
            }
        }
        return null;
    }

    private static boolean isUnitFragment(String f) {
        if (f == null || f.isEmpty()) {
            return false;
        }
        return UNIT_FRAGMENT.matcher(f).matches() || PURE_TOKEN.matcher(f).matches();
    }

    /** 内嵌编号组展开：AB栋 / 1/2座 / 1-5单元 / 248,249.250栋 */
    private static List<String> tryExpandGroup(String s) {
        // 连写字母：韵动家园AB栋、香江西苑AB座
        Matcher lm = LETTERS.matcher(s);
        if (lm.find()) {
            String prefix = s.substring(0, lm.start());
            if (prefix.length() >= 2) {
                List<String> out = new ArrayList<>();
                for (char c : lm.group(1).toCharArray()) {
                    out.add(prefix + c + lm.group(2));
                }
                return out;
            }
        }
        // 编号列表/范围：1/2座、1-5单元
        Matcher gm = GROUP.matcher(s);
        if (gm.find()) {
            String prefix = s.substring(0, gm.start());
            if (prefix.length() >= 2) {
                List<String> tokens = expandTokens(gm.group(1));
                if (tokens != null && !tokens.isEmpty()) {
                    List<String> out = new ArrayList<>();
                    for (String t : tokens) {
                        out.add(prefix + t + gm.group(2));
                    }
                    return out;
                }
            }
        }
        return null;
    }

    /** "A/B/C" → [A,B,C]；"1-5" 数字范围 → [1..5]（跨度≤12）；超限返回 null（不拆） */
    private static List<String> expandTokens(String group) {
        String[] raw = group.split("[/／,，、.．\\-—－]");
        if (raw.length < 2) {
            return null;
        }
        // 纯数字且首尾构成范围（如 1-5）
        boolean allNum = true;
        for (String r : raw) {
            if (!r.matches("\\d{1,4}")) {
                allNum = false;
                break;
            }
        }
        if (allNum && raw.length == 2) {
            try {
                int a = Integer.parseInt(raw[0]);
                int b = Integer.parseInt(raw[1]);
                if (b > a && b - a <= 12) {
                    List<String> out = new ArrayList<>();
                    for (int i = a; i <= b; i++) {
                        out.add(String.valueOf(i));
                    }
                    return out;
                }
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (r.isEmpty() || r.length() > 4) {
                return null;
            }
            out.add(r);
        }
        return out;
    }
}
