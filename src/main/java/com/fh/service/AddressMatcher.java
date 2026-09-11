package com.fh.service;

import com.fh.model.Candidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多结果匹配度评分与择优（满分 100）：
 * 名称匹配 40 / 地址关键词 25 / 楼栋单元 20 / 类型 10 / 多边形可用性 5。
 * 并列决胜：有多边形优先 → 百度原始排序（index）靠前优先 → 名称更长优先。
 * （决胜顺序与需求 5.3 略有调整：实测"名称更长优先"会让同名异地长名称候选
 *   压过百度排名第一的正确候选，故先按百度相关性排序决胜。）
 */
public final class AddressMatcher {

    /** 输入中的关键词：连续汉字串（≥2 字）或 编号+楼栋后缀 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");
    private static final Pattern UNIT_TOKEN = Pattern.compile("[0-9A-Za-z]{1,4}(栋|座|楼|幢|号楼|单元)");
    private static final Pattern CJK_NUM_UNIT = Pattern.compile("[一二三四五六七八九十]{1,3}单元?");
    private static final String UNIT_CHARS = "栋座楼幢号楼单元";
    private static final Pattern RESIDENTIAL_HINT = Pattern.compile("栋|座|单元|楼|花园|苑|村|公寓|家园|小区|府|阁|居|庭|台|轩|庄");
    /** 片区关键词 → 所属行政区，用于地址匹配的区域锚定 */
    private static final String[][] AREA_DISTRICTS = {
            {"香蜜湖", "福田区"}, {"华强北", "福田区"}, {"梅林", "福田区"}, {"车公庙", "福田区"}, {"上下沙", "福田区"},
            {"前海", "南山区"}, {"蛇口", "南山区"}, {"西丽", "南山区"}, {"科技园", "南山区"}, {"南油", "南山区"},
            {"华侨城", "南山区"}, {"沙河", "南山区"},
            {"石岩", "宝安区"}, {"西乡", "宝安区"}, {"福永", "宝安区"}, {"沙井", "宝安区"}, {"松岗", "宝安区"}, {"坪洲", "宝安区"},
            {"布吉", "龙岗区"}, {"平湖", "龙岗区"},
            {"葵涌", "大鹏新区"}, {"大鹏", "大鹏新区"},
    };
    private static final String[] DISTRICTS = {"福田区", "罗湖区", "南山区", "盐田区", "宝安区", "龙岗区", "龙华区", "坪山区", "光明区", "大鹏新区"};
    /** 通用词：单独出现不具备实体区分度，不得作为名称/地址匹配证据 */
    private static final String[] GENERIC_WORDS = {
            "宿舍", "宿舍楼", "教学楼", "办公楼", "项目部", "工业园", "工业区", "商业楼", "综合楼",
            "家属楼", "住宅楼", "大楼", "厂房", "仓库", "基地", "停车场", "大门", "食堂"
    };

    private AddressMatcher() {
    }

    public static class Score {
        public final Candidate candidate;
        public final double total;
        public final String detail;

        Score(Candidate candidate, double total, String detail) {
            this.candidate = candidate;
            this.total = total;
            this.detail = detail;
        }
    }

    /** 对所有候选评分，返回按总分降序（含决胜）排列的列表 */
    public static List<Score> scoreAll(List<Candidate> candidates, String input) {
        List<Score> scores = new ArrayList<>();
        for (Candidate c : candidates) {
            scores.add(score(c, input));
        }
        scores.sort(Comparator
                .comparingDouble((Score s) -> s.total).reversed()
                .thenComparing(s -> s.candidate.hasPolygons() ? 0 : 1)
                .thenComparingInt(s -> s.candidate.index)
                .thenComparing(s -> -s.candidate.name.length()));
        return scores;
    }

    /** 商业业态标签：住宅类输入命中此类候选时，名称/地址证据减半（店铺只是位于小区内） */
    private static final Pattern COMMERCIAL_TAG = Pattern.compile(
            "餐饮|美食|小吃|快餐|蛋糕|购物|超市|便利店|公司|企业|酒店|宾馆|生活服务|美容|健身|药店|金融");

    public static Score score(Candidate c, String input) {
        double nameScore = nameScore(c.name, input);
        double addrScore = addrScore(c.addr, input);
        boolean residentialInput = RESIDENTIAL_HINT.matcher(input).find();
        if (residentialInput && COMMERCIAL_TAG.matcher(c.tagAll()).find()) {
            nameScore *= 0.5;
            addrScore *= 0.5;
        }
        double unitScore = unitScore(c, input);
        double typeScore = typeScore(c, input);
        double polyScore = c.hasPolygons() ? 5 : 0;
        double total = nameScore + addrScore + unitScore + typeScore + polyScore;
        String detail = String.format("名称%.0f/地址%.0f/楼栋%.0f/类型%.0f/多边形%.0f=%.0f",
                nameScore, addrScore, unitScore, typeScore, polyScore, total);
        return new Score(c, total, detail);
    }

    /**
     * 名称匹配（40）：完全一致 40；候选含输入 30；输入含候选且候选含≥2个汉字 30
     * （防"B2栋"这类纯编号后缀白拿分）；输入是候选名的子序列 28（防插字如"项目经理部"）；
     * 其余按关键词命中比例，且输入带楼栋号而候选完全不带时减半。
     */
    static double nameScore(String candName, String input) {
        if (candName == null || candName.isEmpty() || input == null) {
            return 0;
        }
        String in = input.replace(" ", "");
        String cn = candName.replace(" ", "");
        if (cn.equals(in)) {
            return 40;
        }
        boolean genericName = isGeneric(cn);
        if (cn.contains(in)) {
            // 候选名含输入但自身带通用词（如"XX广场地下停车场"）：只是同设施家族的附属物，降权
            return genericName ? 15 : 30;
        }
        List<String> unitTokens = unitTokens(input);
        if (!genericName && in.contains(cn) && cjkLen(cn) >= 2) {
            return 30;
        }
        if (!genericName && in.length() >= 5 && isSubsequence(in, cn)) {
            return 28;
        }
        List<String> runs = effectiveRuns(input);
        if (runs.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String r : runs) {
            if (cn.contains(r)) {
                hits++;
            }
        }
        if (hits == 0) {
            return 0;
        }
        double score = 40.0 * hits / runs.size();
        if (!unitTokens.isEmpty() && !containsAny(cn, unitTokens)) {
            score *= 0.5;
        }
        return score;
    }

    /**
     * 地址关键词匹配（25）：输入关键词在候选地址中的命中比例，叠加区域锚定——
     * 输入提及片区（如香蜜湖）时，候选地址含同区 +10（封顶25），含其他区 -15（保底0）。
     */
    static double addrScore(String candAddr, String input) {
        if (candAddr == null || candAddr.isEmpty()) {
            return 0;
        }
        List<String> runs = effectiveRuns(input);
        double score = 0;
        if (!runs.isEmpty()) {
            int hits = 0;
            for (String r : runs) {
                if (candAddr.contains(r)) {
                    hits++;
                }
            }
            score = 25.0 * hits / runs.size();
        }
        String district = inputDistrict(input);
        if (district != null) {
            if (candAddr.contains(district)) {
                score = Math.min(25, score + 10);
            } else {
                for (String d : DISTRICTS) {
                    if (!d.equals(district) && candAddr.contains(d)) {
                        score = Math.max(0, score - 15);
                        break;
                    }
                }
            }
        }
        return score;
    }

    /** 输入提及的片区/行政区 → 行政区名；未提及返回 null */
    static String inputDistrict(String input) {
        for (String[] ad : AREA_DISTRICTS) {
            if (input.contains(ad[0])) {
                return ad[1];
            }
        }
        for (String d : DISTRICTS) {
            if (input.contains(d.substring(0, d.length() - 1))) {
                return d;
            }
        }
        return null;
    }

    private static int cjkLen(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x4e00 && s.charAt(i) <= 0x9fa5) {
                n++;
            }
        }
        return n;
    }

    /** a 的所有字符是否按顺序出现在 b 中 */
    static boolean isSubsequence(String a, String b) {
        int i = 0;
        for (int j = 0; j < b.length() && i < a.length(); j++) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
            }
        }
        return i == a.length();
    }

    private static boolean containsAny(String hay, List<String> tokens) {
        for (String t : tokens) {
            if (hay.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** 楼栋/单元匹配（20）：相同标识 20；不同标识 0；输入无标识 10。编号带左边界（"3栋"不得命中"13栋"） */
    static double unitScore(Candidate c, String input) {
        List<String> tokens = unitTokens(input);
        if (tokens.isEmpty()) {
            return 10;
        }
        String hay = nvl(c.name) + " " + nvl(c.addr);
        for (String t : tokens) {
            Matcher m = Pattern.compile("(?<![0-9A-Za-z])" + Pattern.quote(t)).matcher(hay);
            if (m.find()) {
                return 20;
            }
        }
        // 候选带其他楼栋标识 → 视为不同标识
        for (char ch : UNIT_CHARS.toCharArray()) {
            if (hay.indexOf(ch) >= 0) {
                return 0;
            }
        }
        return 10;
    }

    /** 类型匹配（10）：输入疑似住宅且候选类型吻合 10；输入非住宅 5；输入住宅但类型不符 0 */
    static double typeScore(Candidate c, String input) {
        boolean residentialInput = RESIDENTIAL_HINT.matcher(input).find();
        String tag = c.tagAll();
        boolean residentialTag = tag.contains("住宅") || tag.contains("楼栋") || tag.contains("居住") || tag.contains("小区");
        if (residentialTag) {
            return residentialInput ? 10 : 5;
        }
        return residentialInput ? 0 : 5;
    }

    static List<String> cjkRuns(String s) {
        List<String> runs = new ArrayList<>();
        if (s == null) {
            return runs;
        }
        Matcher m = CJK_RUN.matcher(s);
        while (m.find()) {
            runs.add(m.group());
        }
        return runs;
    }

    /** 过滤通用词后的关键词：短泛词（≤4字且含宿舍/项目部等）不作为匹配证据 */
    static List<String> effectiveRuns(String s) {
        List<String> runs = new ArrayList<>();
        for (String r : cjkRuns(s)) {
            if (r.length() <= 4 && isGeneric(r)) {
                continue;
            }
            runs.add(r);
        }
        return runs;
    }

    static boolean isGeneric(String s) {
        for (String g : GENERIC_WORDS) {
            if (s.contains(g)) {
                return true;
            }
        }
        return false;
    }

    static List<String> unitTokens(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null) {
            return tokens;
        }
        Matcher m = UNIT_TOKEN.matcher(input);
        while (m.find()) {
            tokens.add(m.group());
        }
        m = CJK_NUM_UNIT.matcher(input);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
