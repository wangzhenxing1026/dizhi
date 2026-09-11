package com.fh.model;

import java.util.ArrayList;
import java.util.List;

/** 搜索接口返回的一个候选 POI（坐标为 BD-09 墨卡托，米） */
public class Candidate {

    /** 在 content 数组中的序号，用于并列决胜 */
    public int index;
    public String name;
    public String addr;
    public String stdTag;
    public String showTag;
    /** 候选所属城市（city_name，如 深圳市/广州市）；空表示响应未提供 */
    public String cityName;
    /** 中心点（墨卡托） */
    public double centerX;
    public double centerY;
    public boolean hasCenter;
    /** bud_geom 解析出的多边形列表（墨卡托） */
    public List<List<double[]>> polygons = new ArrayList<>();

    public boolean hasPolygons() {
        return polygons != null && !polygons.isEmpty();
    }

    public String tagAll() {
        StringBuilder sb = new StringBuilder();
        if (stdTag != null) sb.append(stdTag);
        if (showTag != null) sb.append(';').append(showTag);
        return sb.toString();
    }
}
