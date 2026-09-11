package com.fh.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 多边形解析与几何计算（射线法、外接矩形、面积、质心） */
public final class PolygonUtils {

    private static final Pattern POLYGON_RE = Pattern.compile("POLYGON\\s*\\(\\(([^)]*)\\)\\)");

    private PolygonUtils() {
    }

    /** 解析 bud_geom：POLYGON ((x1 y1, x2 y2, ...));POLYGON (...) → 多边形列表 */
    public static List<List<double[]>> parseBudGeom(String budGeom) {
        List<List<double[]>> result = new ArrayList<>();
        if (budGeom == null || budGeom.isEmpty()) {
            return result;
        }
        Matcher m = POLYGON_RE.matcher(budGeom);
        while (m.find()) {
            String[] pairs = m.group(1).split(",");
            List<double[]> poly = new ArrayList<>(pairs.length);
            for (String pair : pairs) {
                String[] xy = pair.trim().split("\\s+");
                if (xy.length >= 2) {
                    try {
                        poly.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                    } catch (NumberFormatException ignore) {
                        // 脏点跳过
                    }
                }
            }
            if (poly.size() >= 3) {
                result.add(poly);
            }
        }
        return result;
    }

    /** 射线法判断点是否在多边形内 */
    public static boolean isPointInPolygon(double px, double py, List<double[]> polygon) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i)[0], yi = polygon.get(i)[1];
            double xj = polygon.get(j)[0], yj = polygon.get(j)[1];
            boolean intersect = ((yi > py) != (yj > py))
                    && (px < (xj - xi) * (py - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 外接矩形 [minX, minY, maxX, maxY] */
    public static double[] bbox(List<double[]> polygon) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : polygon) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    public static boolean inBbox(double px, double py, double[] bbox) {
        return px >= bbox[0] && px <= bbox[2] && py >= bbox[1] && py <= bbox[3];
    }

    /** 鞋带公式面积（绝对值的一半） */
    public static double area(List<double[]> polygon) {
        double s = 0;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            s += (polygon.get(j)[0] + polygon.get(i)[0]) * (polygon.get(j)[1] - polygon.get(i)[1]);
        }
        return Math.abs(s) / 2.0;
    }

    /** 多边形质心；退化（面积≈0）时取外接矩形中心 */
    public static double[] centroid(List<double[]> polygon) {
        double a = 0, cx = 0, cy = 0;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double cross = polygon.get(j)[0] * polygon.get(i)[1] - polygon.get(i)[0] * polygon.get(j)[1];
            a += cross;
            cx += (polygon.get(j)[0] + polygon.get(i)[0]) * cross;
            cy += (polygon.get(j)[1] + polygon.get(i)[1]) * cross;
        }
        if (Math.abs(a) < 1e-9) {
            double[] b = bbox(polygon);
            return new double[]{(b[0] + b[2]) / 2, (b[1] + b[3]) / 2};
        }
        a *= 0.5;
        return new double[]{cx / (6 * a), cy / (6 * a)};
    }

    /**
     * 从多边形列表中选出范围边界：优先包含中心点的；都没有则取面积最大的。
     * 找不到（空列表）返回 null。
     */
    public static List<double[]> selectPolygon(List<List<double[]>> polygons, double cx, double cy) {
        if (polygons == null || polygons.isEmpty()) {
            return null;
        }
        List<double[]> largest = null;
        double largestArea = -1;
        for (List<double[]> poly : polygons) {
            double[] bb = bbox(poly);
            if (inBbox(cx, cy, bb) && isPointInPolygon(cx, cy, poly)) {
                return poly;
            }
            double a = area(poly);
            if (a > largestArea) {
                largestArea = a;
                largest = poly;
            }
        }
        return largest;
    }
}
