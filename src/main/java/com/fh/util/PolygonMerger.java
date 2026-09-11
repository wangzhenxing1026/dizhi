package com.fh.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 多边形合并：凸包（Andrew 单调链），合并后中心点取凸包质心 */
public final class PolygonMerger {

    private PolygonMerger() {
    }

    /**
     * 合并多个多边形为凸包。返回凸包顶点（逆时针、不重复首尾点）；
     * 输入顶点不足 3 个或共线时返回 null。
     */
    public static List<double[]> convexHull(List<double[]> points) {
        if (points == null || points.size() < 3) {
            return null;
        }
        List<double[]> pts = new ArrayList<>();
        // 去重
        for (double[] p : points) {
            boolean dup = false;
            for (double[] q : pts) {
                if (q[0] == p[0] && q[1] == p[1]) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                pts.add(p);
            }
        }
        if (pts.size() < 3) {
            return null;
        }
        pts.sort(Comparator.<double[]>comparingDouble(p -> p[0]).thenComparingDouble(p -> p[1]));

        int n = pts.size();
        double[] hullX = new double[2 * n];
        double[] hullY = new double[2 * n];
        int k = 0;

        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hullX[k - 2], hullY[k - 2], hullX[k - 1], hullY[k - 1], pts.get(i)[0], pts.get(i)[1]) <= 0) {
                k--;
            }
            hullX[k] = pts.get(i)[0];
            hullY[k] = pts.get(i)[1];
            k++;
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            while (k >= lower && cross(hullX[k - 2], hullY[k - 2], hullX[k - 1], hullY[k - 1], pts.get(i)[0], pts.get(i)[1]) <= 0) {
                k--;
            }
            hullX[k] = pts.get(i)[0];
            hullY[k] = pts.get(i)[1];
            k++;
        }
        // k-1 为首点重复
        if (k - 1 < 3) {
            return null;
        }
        List<double[]> hull = new ArrayList<>(k - 1);
        for (int i = 0; i < k - 1; i++) {
            hull.add(new double[]{hullX[i], hullY[i]});
        }
        return hull;
    }

    private static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    /**
     * 合并中心点：优先凸包质心；凸包不存在（如仅一个点集）时返回 null，
     * 由调用方退化为各楼栋中心点的平均值。
     */
    public static double[] mergeCenter(List<List<double[]>> polygons, List<double[]> fallbackCenters) {
        if (polygons != null && !polygons.isEmpty()) {
            List<double[]> all = new ArrayList<>();
            for (List<double[]> poly : polygons) {
                if (poly != null) {
                    all.addAll(poly);
                }
            }
            List<double[]> hull = convexHull(all);
            if (hull != null) {
                return PolygonUtils.centroid(hull);
            }
        }
        if (fallbackCenters != null && !fallbackCenters.isEmpty()) {
            double sx = 0, sy = 0;
            for (double[] p : fallbackCenters) {
                sx += p[0];
                sy += p[1];
            }
            return new double[]{sx / fallbackCenters.size(), sy / fallbackCenters.size()};
        }
        return null;
    }
}
