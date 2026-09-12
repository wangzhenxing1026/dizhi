package com.fh.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 边界扩展工具：把已有边界向外扩展指定距离，或围绕中心点生成圆形边界。
 *
 * 坐标约定：本类在 BD-09 经纬度 [lon, lat] 上工作；内部先用局部平面米坐标
 * （以参考点为中心的东向/北向）做几何运算，再转回经纬度。在数公里尺度内
 * 该近似误差可忽略。
 */
public final class BufferUtils {

    /** 每度纬度对应的大致米数 */
    private static final double METERS_PER_DEGREE = 111320.0;

    /** 圆形边界的边数（仅中心点兜底时使用） */
    private static final int CIRCLE_SIDES = 36;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BufferUtils() {
    }

    /**
     * 生成一行 output.txt 的扩展范围列（BD-09 经纬度 JSON，首尾闭合）。
     * 有有效边界（去重后 ≥3 顶点）时按边界外扩 bufferMeters 米；
     * 否则围绕中心点生成 bufferMeters 米半径的近似圆。
     */
    public static String expandRegion(String centerLonlatJson, String boundariesJson, double bufferMeters) throws Exception {
        List<double[]> polygon = new ArrayList<>();
        if (boundariesJson != null && !boundariesJson.trim().isEmpty()) {
            JsonNode arr = MAPPER.readTree(boundariesJson);
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    polygon.add(new double[]{n.path("lon").asDouble(), n.path("lat").asDouble()});
                }
            }
        }
        List<double[]> expanded = expandPolygon(polygon, bufferMeters);
        if (expanded.isEmpty()) {
            JsonNode c = MAPPER.readTree(centerLonlatJson);
            expanded = circle(new double[]{c.path("lon").asDouble(), c.path("lat").asDouble()}, bufferMeters, CIRCLE_SIDES);
        }
        // 闭合（与 area_boundaries 列格式一致：首尾相同）
        if (expanded.size() >= 3) {
            double[] first = expanded.get(0);
            double[] last = expanded.get(expanded.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                expanded.add(new double[]{first[0], first[1]});
            }
        }
        return polygonJson(expanded);
    }

    /**
     * 把经纬度多边形向外扩展 bufferMeters 米，返回新的经纬度顶点（不闭合）。
     * 若输入退化（去重后不足 3 个顶点）则返回空列表，由调用方兜底。
     */
    public static List<double[]> expandPolygon(List<double[]> lonLatPolygon, double bufferMeters) {
        List<double[]> clean = cleanPolygon(lonLatPolygon);
        if (clean.size() < 3) {
            return new ArrayList<>();
        }
        double[] ref = centroid(clean);
        List<double[]> local = toLocal(clean, ref);
        List<double[]> offset = offsetPolygon(local, bufferMeters);
        return toLonLat(offset, ref);
    }

    /**
     * 围绕中心点生成 radiusMeters 米半径的近似圆（n 边形），返回经纬度顶点（不闭合）。
     */
    public static List<double[]> circle(double[] centerLonLat, double radiusMeters, int n) {
        double mLon = METERS_PER_DEGREE * Math.cos(Math.toRadians(centerLonLat[1]));
        List<double[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double ang = 2.0 * Math.PI * i / n;
            double east = radiusMeters * Math.cos(ang);
            double north = radiusMeters * Math.sin(ang);
            out.add(new double[]{centerLonLat[0] + east / mLon,
                    centerLonLat[1] + north / METERS_PER_DEGREE});
        }
        return out;
    }

    // ================= 内部实现 =================

    /** 去重、去掉首尾重复的闭合点（bud_geom 的 POLYGON 通常已闭合）。 */
    private static List<double[]> cleanPolygon(List<double[]> pts) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : pts) {
            if (p == null) {
                continue;
            }
            if (out.isEmpty()) {
                out.add(p);
                continue;
            }
            double[] last = out.get(out.size() - 1);
            if (p[0] == last[0] && p[1] == last[1]) {
                continue;
            }
            out.add(p);
        }
        if (out.size() > 1) {
            double[] first = out.get(0);
            double[] last = out.get(out.size() - 1);
            if (first[0] == last[0] && first[1] == last[1]) {
                out.remove(out.size() - 1);
            }
        }
        return out;
    }

    private static double[] centroid(List<double[]> pts) {
        double sx = 0, sy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
        }
        return new double[]{sx / pts.size(), sy / pts.size()};
    }

    private static List<double[]> toLocal(List<double[]> lonLat, double[] ref) {
        double mLon = METERS_PER_DEGREE * Math.cos(Math.toRadians(ref[1]));
        List<double[]> out = new ArrayList<>(lonLat.size());
        for (double[] p : lonLat) {
            out.add(new double[]{(p[0] - ref[0]) * mLon, (p[1] - ref[1]) * METERS_PER_DEGREE});
        }
        return out;
    }

    private static List<double[]> toLonLat(List<double[]> local, double[] ref) {
        double mLon = METERS_PER_DEGREE * Math.cos(Math.toRadians(ref[1]));
        List<double[]> out = new ArrayList<>(local.size());
        for (double[] p : local) {
            out.add(new double[]{ref[0] + p[0] / mLon, ref[1] + p[1] / METERS_PER_DEGREE});
        }
        return out;
    }

    /** 多边形向外偏移 d 米（斜接 join），统一按逆时针处理。 */
    private static List<double[]> offsetPolygon(List<double[]> pts, double d) {
        List<double[]> poly = (signedArea(pts) < 0) ? reverse(pts) : pts;
        int n = poly.size();
        List<double[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double[] a = poly.get((i - 1 + n) % n);
            double[] b = poly.get(i);
            double[] c = poly.get((i + 1) % n);
            double[] e1 = {b[0] - a[0], b[1] - a[1]};
            double[] e2 = {c[0] - b[0], c[1] - b[1]};
            double[] n1 = rightNormal(e1);
            double[] n2 = rightNormal(e2);
            double[] p1 = {a[0] + n1[0] * d, a[1] + n1[1] * d};
            double[] p2 = {b[0] + n2[0] * d, b[1] + n2[1] * d};
            double[] inter = intersect(p1, e1, p2, e2);
            if (inter == null) {
                // 共线边（平行偏移线无交点），取两偏移点中点
                inter = new double[]{(p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2};
            }
            // 斜接长度限制：过长则改用斜切，避免凹尖角产生异常大的顶点
            double miterLen = Math.hypot(inter[0] - b[0], inter[1] - b[1]);
            if (miterLen > 3.0 * d) {
                inter = new double[]{(p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2};
            }
            out.add(inter);
        }
        return out;
    }

    private static double signedArea(List<double[]> pts) {
        double sum = 0;
        int n = pts.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += pts.get(j)[0] * pts.get(i)[1] - pts.get(i)[0] * pts.get(j)[1];
        }
        return sum / 2;
    }

    private static List<double[]> reverse(List<double[]> pts) {
        List<double[]> out = new ArrayList<>(pts.size());
        for (int i = pts.size() - 1; i >= 0; i--) {
            out.add(pts.get(i));
        }
        return out;
    }

    /** 逆时针多边形向右的外法线：对方向 (dx,dy) 取 (dy,-dx)/len。 */
    private static double[] rightNormal(double[] e) {
        double len = Math.hypot(e[0], e[1]);
        if (len == 0) {
            return new double[]{0, 0};
        }
        return new double[]{e[1] / len, -e[0] / len};
    }

    /** 两直线（点 + 方向）求交；平行/共线返回 null。 */
    private static double[] intersect(double[] p1, double[] d1, double[] p2, double[] d2) {
        double denom = d1[0] * d2[1] - d1[1] * d2[0];
        if (Math.abs(denom) < 1e-9) {
            return null;
        }
        double t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / denom;
        return new double[]{p1[0] + t * d1[0], p1[1] + t * d1[1]};
    }

    /** 与 OutputFormatter.polygonJson 同格式：{"lon":...,"lat":...} 数组 */
    private static String polygonJson(List<double[]> poly) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < poly.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"lon\":").append(Util.fmt14(poly.get(i)[0]))
              .append(",\"lat\":").append(Util.fmt14(poly.get(i)[1])).append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * 命令行入口：为 output.txt 追加"扩展范围"列（第 7 列）。
     * 用法：java com.fh.util.BufferUtils [inputPath [bufferMeters [outputPath]]]
     * 缺省：inputPath=output.txt，bufferMeters=1000，outputPath=同 inputPath（原地更新）。
     */
    public static void main(String[] args) throws Exception {
        String inPath = args.length > 0 ? args[0] : "output.txt";
        double bufferMeters = args.length > 1 ? Double.parseDouble(args[1]) : 1000.0;
        String outPath = args.length > 2 ? args[2] : inPath;

        List<String> outLines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(inPath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                if (c.length < 6) {
                    throw new IllegalStateException("列数不足(" + c.length + "): " + line.substring(0, Math.min(60, line.length())));
                }
                String expanded = expandRegion(c[2], c[3], bufferMeters);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    if (i > 0) {
                        sb.append('\t');
                    }
                    sb.append(c[i]);
                }
                // 第 7 列固定为扩展范围：重复运行时覆盖旧值
                sb.append('\t').append(expanded);
                outLines.add(sb.toString());
            }
        }
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outPath), StandardCharsets.UTF_8))) {
            for (String l : outLines) {
                bw.write(l);
                bw.write("\r\n");
            }
        }
        System.out.println("[输出] " + outPath + " 共 " + outLines.size() + " 行，扩展 " + (int) bufferMeters + " 米");
    }
}
