package com.fh.model;

import java.util.ArrayList;
import java.util.List;

/** 一条地址的最终处理结果 */
public class AddressResult {

    public static final String STATUS_SUCCESS = "成功";
    public static final String STATUS_LOW = "低置信度";
    public static final String STATUS_FAILED = "失败";

    public int regionId;
    /** 输入的原始地址 */
    public String inputAddress;
    /** 接口返回的名称（多楼栋合并时用原始地址） */
    public String regionName;
    public Double centerLon;
    public Double centerLat;
    /** 多边形顶点（BD-09 经纬度），lon,lat 对 */
    public List<double[]> polygon = new ArrayList<>();
    public String placeType = "未知";
    public String status = STATUS_FAILED;
    /** 选中的候选名称（单楼栋时） */
    public String selectedName;
    /** 评分明细（人工复核用） */
    public String scoreDetail;
    /** 拆分出的子查询地址 */
    public List<String> subQueries = new ArrayList<>();
    /** 失败/备注信息 */
    public String message;

    public boolean ok() {
        return centerLon != null && centerLat != null;
    }
}
