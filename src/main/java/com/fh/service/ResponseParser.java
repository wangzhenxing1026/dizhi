package com.fh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fh.model.Candidate;
import com.fh.util.PolygonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 qt=s 接口返回的 JSON，提取候选 POI 列表 */
public final class ResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern GEO_PAIR = Pattern.compile("(-?\\d+\\.?\\d*),(-?\\d+\\.?\\d*)");

    /** 解析结果 */
    public static class Outcome {
        /** result.error != 0 或触发反爬（need_recaptcha），判定为鉴权/风控异常 */
        public boolean authError;
        public int errorCode;
        public String errorInfo;
        /** 响应携带的新 auth（anti_auth），可直接采用 */
        public String antiAuth;
        public List<Candidate> candidates = new ArrayList<>();
    }

    private ResponseParser() {
    }

    public static Outcome parse(String raw) {
        Outcome out = new Outcome();
        if (raw == null || raw.isEmpty()) {
            out.authError = true;
            out.errorInfo = "响应为空";
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            out.authError = true;
            out.errorInfo = "JSON 解析失败: " + e.getMessage();
            return out;
        }
        JsonNode result = root.path("result");
        int err = result.path("error").asInt(0);
        // 反爬检测：need_recaptcha 时 content 恒为空数组，需更新 cookie/auth
        boolean recaptcha = result.path("anti_session").path("need_recaptcha").asBoolean(false);
        if (err != 0 || recaptcha) {
            out.authError = true;
            out.errorCode = err;
            out.errorInfo = recaptcha
                    ? "触发反爬(need_recaptcha)，请更新 baidu.cookie"
                    : "result.error=" + err + " " + result.path("msg").asText("");
            out.antiAuth = root.path("anti_auth").asText(null);
            return out;
        }
        JsonNode content = root.path("content");
        if (content.isArray()) {
            for (int i = 0; i < content.size(); i++) {
                Candidate c = parseCandidate(content.get(i), i);
                if (c != null) {
                    out.candidates.add(c);
                }
            }
        }
        return out;
    }

    private static Candidate parseCandidate(JsonNode n, int index) {
        if (n == null || !n.isObject()) {
            return null;
        }
        Candidate c = new Candidate();
        c.index = index;
        c.name = n.path("name").asText(null);
        // 实测响应中地址字段为 addr（需求文档写的 poi_address 实为规范名，兼容两者）
        c.addr = n.path("addr").asText(n.path("poi_address").asText(null));
        c.stdTag = n.path("std_tag").asText(null);
        c.cityName = n.path("city_name").asText(null);
        // tag_info 是内嵌 JSON 字符串
        String tagInfoStr = n.path("tag_info").asText(null);
        if (tagInfoStr != null && tagInfoStr.startsWith("{")) {
            try {
                JsonNode ti = MAPPER.readTree(tagInfoStr);
                c.showTag = ti.path("classification").path("show_tag").asText(null);
                if (c.stdTag == null) {
                    c.stdTag = ti.path("classification").path("std_tag").asText(null);
                }
            } catch (Exception ignore) {
                // tag_info 非法不影响主体
            }
        }
        if (c.showTag == null) {
            JsonNode st = n.path("show_tag");
            if (st.isArray() && st.size() > 0) {
                c.showTag = st.get(0).asText(null);
            }
        }

        double[] center = extractCenter(n);
        if (center != null) {
            c.centerX = center[0];
            c.centerY = center[1];
            c.hasCenter = true;
        }

        String budGeom = n.path("ext").path("detail_info").path("guoke_geo_bud").path("bud_geom").asText(null);
        if (budGeom != null && !budGeom.isEmpty()) {
            c.polygons = PolygonUtils.parseBudGeom(budGeom);
        }
        return c;
    }

    /**
     * 中心点提取优先级（BD-09MC）：
     * ext.detail_info.navi_xy.diPoint.x/y（字符串，米）
     * → diPointX/diPointY（整数，×100，需除以 100）
     * → geo 字段第一对坐标。
     */
    static double[] extractCenter(JsonNode n) {
        JsonNode diPoint = n.path("ext").path("detail_info").path("navi_xy").path("diPoint");
        if (diPoint.isObject()) {
            double x = diPoint.path("x").asDouble(Double.NaN);
            double y = diPoint.path("y").asDouble(Double.NaN);
            if (!Double.isNaN(x) && !Double.isNaN(y)) {
                return new double[]{x, y};
            }
        }
        JsonNode dpx = n.path("diPointX"), dpy = n.path("diPointY");
        if (dpx.canConvertToLong() && dpy.canConvertToLong() && dpx.asLong() != 0) {
            return new double[]{dpx.asDouble() / 100.0, dpy.asDouble() / 100.0};
        }
        JsonNode geo = n.path("geo");
        if (geo.isTextual()) {
            Matcher m = GEO_PAIR.matcher(geo.asText());
            if (m.find()) {
                try {
                    return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return null;
    }
}
