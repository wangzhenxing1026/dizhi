package com.fh.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fh.model.AddressResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 生成 output.txt、failed.txt、review.txt、map.html */
public final class OutputFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutputFormatter() {
    }

    private static void writeFile(String path, String content) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        File f = new File(path);
        System.out.println("[输出] " + f.getAbsolutePath());
    }

    public static String centerJson(AddressResult r) {
        return "{\"lon\":" + Util.fmt14(r.centerLon) + ",\"lat\":" + Util.fmt14(r.centerLat) + "}";
    }

    public static String polygonJson(AddressResult r) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < r.polygon.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"lon\":").append(Util.fmt14(r.polygon.get(i)[0]))
              .append(",\"lat\":").append(Util.fmt14(r.polygon.get(i)[1])).append('}');
        }
        return sb.append(']').toString();
    }

    /** output.txt：region_id \t region_name \t center_lonlat \t address \t place_type */
    public static void writeOutputTxt(List<AddressResult> results, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (AddressResult r : results) {
            if (!r.ok()) {
                continue;
            }
            sb.append(r.regionId).append('\t')
              .append(r.regionName == null ? "" : r.regionName).append('\t')
              .append(centerJson(r)).append('\t')
              .append(polygonJson(r)).append('\t')
              .append(r.placeType == null ? "未知" : r.placeType).append("\r\n");
        }
        writeFile(path, sb.toString());
    }

    public static void writeFailedTxt(List<AddressResult> results, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (AddressResult r : results) {
            if (AddressResult.STATUS_FAILED.equals(r.status)) {
                sb.append(r.inputAddress).append('\t').append(r.message == null ? "" : r.message).append("\r\n");
            }
        }
        writeFile(path, sb.toString());
    }

    public static void writeReviewTxt(List<AddressResult> results, String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (AddressResult r : results) {
            if (AddressResult.STATUS_LOW.equals(r.status)) {
                sb.append(r.inputAddress).append('\t')
                  .append(r.selectedName == null ? "" : r.selectedName).append('\t')
                  .append(r.scoreDetail == null ? "" : r.scoreDetail).append('\t')
                  .append(r.message == null ? "" : r.message).append("\r\n");
            }
        }
        writeFile(path, sb.toString());
    }

    /** map.html：百度地图 JS API 3.0，数据内嵌，按状态着色，支持过滤与信息窗追溯 */
    public static void writeMapHtml(List<AddressResult> results, String path, String ak) throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        for (AddressResult r : results) {
            if (!r.ok()) {
                continue;
            }
            ObjectNode o = MAPPER.createObjectNode();
            o.put("regionId", r.regionId);
            o.put("regionName", r.regionName == null ? "" : r.regionName);
            o.put("inputAddress", r.inputAddress == null ? "" : r.inputAddress);
            o.put("selectedName", r.selectedName == null ? "" : r.selectedName);
            o.put("placeType", r.placeType == null ? "未知" : r.placeType);
            o.put("status", r.status);
            o.put("scoreDetail", r.scoreDetail == null ? "" : r.scoreDetail);
            o.put("subQueries", String.join(" | ", r.subQueries));
            ObjectNode center = o.putObject("center");
            center.put("lon", r.centerLon);
            center.put("lat", r.centerLat);
            ArrayNode poly = o.putArray("polygon");
            for (double[] p : r.polygon) {
                ObjectNode pt = poly.addObject();
                pt.put("lon", p[0]);
                pt.put("lat", p[1]);
            }
            arr.add(o);
        }
        String data = MAPPER.writeValueAsString(arr);

        String html = "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\"/>\n"
                + "<title>地址范围复核地图</title>\n"
                + "<script type=\"text/javascript\" src=\"https://api.map.baidu.com/api?v=3.0&ak=" + escapeHtml(ak) + "\"></script>\n"
                + "<style>\n"
                + "html,body{height:100%;margin:0;font-family:'Microsoft YaHei',sans-serif}\n"
                + "#container{width:100%;height:100%}\n"
                + "#panel{position:absolute;top:10px;left:10px;z-index:99;background:#fff;padding:10px 14px;"
                + "border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,.25);font-size:13px;line-height:1.9}\n"
                + "#panel button{margin-right:6px;padding:2px 10px;cursor:pointer;border:1px solid #ccc;border-radius:3px;background:#fff}\n"
                + "#panel button.active{background:#1E9FFF;color:#fff;border-color:#1E9FFF}\n"
                + ".dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin:0 4px 0 8px}\n"
                + "#listPanel{position:absolute;top:90px;left:10px;z-index:98;width:280px;bottom:20px;background:#fff;"
                + "border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,.25);display:flex;flex-direction:column;overflow:hidden}\n"
                + "#addrSearch{margin:8px;padding:5px 8px;border:1px solid #ccc;border-radius:4px;font-size:12px;outline:none}\n"
                + "#addrList{flex:1;overflow-y:auto;border-top:1px solid #eee}\n"
                + ".li{padding:5px 10px;cursor:pointer;font-size:12px;border-bottom:1px solid #f2f2f2;"
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n"
                + ".li:hover{background:#eef5ff}\n"
                + ".li .dot{margin-right:6px}\n"
                + "#copyTip{position:absolute;bottom:30px;left:50%;transform:translateX(-50%);z-index:999;background:rgba(0,0,0,.75);"
                + "color:#fff;padding:6px 16px;border-radius:4px;font-size:13px;display:none;max-width:70%;"
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n"
                + "</style>\n"
                + "</head>\n<body>\n"
                + "<div id=\"container\"></div>\n"
                + "<div id=\"panel\">\n"
                + "<b>复核过滤：</b><button class=\"active\" data-st=\"all\">全部</button>"
                + "<button data-st=\"成功\">成功</button>"
                + "<button data-st=\"低置信度\">低置信度</button>\n"
                + "<span class=\"dot\" style=\"background:#00b96b\"></span>成功"
                + "<span class=\"dot\" style=\"background:#ff9800\"></span>低置信度\n"
                + "</div>\n"
                + "<div id=\"listPanel\">\n"
                + "<input id=\"addrSearch\" type=\"text\" placeholder=\"搜索地址后点击列表项：跳转并复制名称\"/>\n"
                + "<div id=\"addrList\"></div>\n"
                + "</div>\n"
                + "<div id=\"copyTip\"></div>\n"
                + "<script type=\"text/javascript\">\n"
                + "var REGIONS = " + data + ";\n"
                + "var map = new BMap.Map('container');\n"
                + "map.enableScrollWheelZoom(true);\n"
                + "var markers = [], polygons = [];\n"
                + "function statusColor(st){ return st === '成功' ? '#00b96b' : '#ff9800'; }\n"
                + "function buildAll(){\n"
                + "  var pts = [];\n"
                + "  REGIONS.forEach(function(r, i){\n"
                + "    var color = statusColor(r.status);\n"
                + "    var pt = new BMap.Point(r.center.lon, r.center.lat);\n"
                + "    pts.push(pt);\n"
                + "    var icon = new BMap.Symbol(BMap_Symbol_SHAPE_CIRCLE, {scale: 6, fillColor: color, fillOpacity: 0.95, strokeColor: '#333', strokeWeight: 0.5});\n"
                + "    var mk = new BMap.Marker(pt, {icon: icon});\n"
                + "    var label = new BMap.Label(r.regionName, {offset: new BMap.Size(8, -8)});\n"
                + "    label.setStyle({fontSize: '11px', borderColor: color, color: '#333', padding: '1px 4px'});\n"
                + "    mk.setLabel(label);\n"
                + "    mk.addEventListener('click', function(){ openInfo(i, pt); });\n"
                + "    markers.push(mk);\n"
                + "    if (r.polygon && r.polygon.length >= 3){\n"
                + "      var bpts = [];\n"
                + "      r.polygon.forEach(function(p){ bpts.push(new BMap.Point(p.lon, p.lat)); });\n"
                + "      var pg = new BMap.Polygon(bpts, {strokeColor: color, strokeWeight: 1.5, strokeOpacity: 0.9, fillColor: color, fillOpacity: 0.25});\n"
                + "      pg.addEventListener('click', function(){ openInfo(i, pt); });\n"
                + "      polygons[i] = pg;\n"
                + "    }\n"
                + "  });\n"
                + "  map.setViewport(pts);\n"
                + "}\n"
                + "function openInfo(i, pt){\n"
                + "  var r = REGIONS[i];\n"
                + "  var html = '<div style=\"font-size:13px;line-height:1.8;max-width:320px\">'\n"
                + "    + '<b>' + esc(r.regionName) + '</b> (ID:' + r.regionId + ')<br/>'\n"
                + "    + '状态：<span style=\"color:' + statusColor(r.status) + '\">' + esc(r.status) + '</span><br/>'\n"
                + "    + '输入地址：' + esc(r.inputAddress) + '<br/>'\n"
                + "    + '选中候选：' + esc(r.selectedName || '-') + '<br/>'\n"
                + "    + '类型：' + esc(r.placeType) + '<br/>'\n"
                + "    + '子查询：' + esc(r.subQueries || '-') + '<br/>'\n"
                + "    + '评分：' + esc(r.scoreDetail || '-') + '<br/>'\n"
                + "    + '中心：' + r.center.lon.toFixed(6) + ', ' + r.center.lat.toFixed(6) + '<br/>'\n"
                + "    + '边界顶点数：' + (r.polygon ? r.polygon.length : 0)\n"
                + "    + '</div>';\n"
                + "  map.openInfoWindow(new BMap.InfoWindow(html), pt);\n"
                + "}\n"
                + "function esc(s){ return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }\n"
                + "function filter(st){\n"
                + "  REGIONS.forEach(function(r, i){\n"
                + "    var show = st === 'all' || r.status === st;\n"
                + "    if (show){ markers[i].show(); if (polygons[i]) polygons[i].show(); }\n"
                + "    else { markers[i].hide(); if (polygons[i]) polygons[i].hide(); }\n"
                + "  });\n"
                + "}\n"
                + "var btns = document.querySelectorAll('#panel button');\n"
                + "btns.forEach(function(b){\n"
                + "  b.addEventListener('click', function(){\n"
                + "    btns.forEach(function(x){ x.classList.remove('active'); });\n"
                + "    b.classList.add('active');\n"
                + "    filter(b.getAttribute('data-st'));\n"
                + "  });\n"
                + "});\n"
                + "function escAttr(s){ return esc(s).replace(/\\\"/g,'&quot;'); }\n"
                + "var listDiv = document.getElementById('addrList');\n"
                + "var tipDiv = document.getElementById('copyTip');\n"
                + "var tipTimer = null;\n"
                + "function showTip(t){\n"
                + "  tipDiv.textContent = t; tipDiv.style.display = 'block';\n"
                + "  if (tipTimer) clearTimeout(tipTimer);\n"
                + "  tipTimer = setTimeout(function(){ tipDiv.style.display = 'none'; }, 2500);\n"
                + "}\n"
                + "function copyText(t, done){\n"
                + "  function fb(){ var ta=document.createElement('textarea'); ta.value=t; ta.style.position='fixed'; ta.style.opacity='0'; document.body.appendChild(ta); ta.select(); try{document.execCommand('copy');}catch(e){} document.body.removeChild(ta); done(); }\n"
                + "  if (navigator.clipboard && navigator.clipboard.writeText){ navigator.clipboard.writeText(t).then(done, fb); } else { fb(); }\n"
                + "}\n"
                + "function jumpTo(i){\n"
                + "  var r = REGIONS[i];\n"
                + "  var pt = new BMap.Point(r.center.lon, r.center.lat);\n"
                + "  map.centerAndZoom(pt, 18);\n"
                + "  setTimeout(function(){ openInfo(i, pt); }, 300);\n"
                + "  copyText(r.regionName, function(){ showTip('已复制：' + r.regionName); });\n"
                + "}\n"
                + "function buildList(kw){\n"
                + "  var html = '';\n"
                + "  var kw2 = (kw || '').toLowerCase();\n"
                + "  REGIONS.forEach(function(r, i){\n"
                + "    if (kw2 && r.regionName.toLowerCase().indexOf(kw2) < 0 && r.inputAddress.toLowerCase().indexOf(kw2) < 0) return;\n"
                + "    html += '<div class=\"li\" data-i=\"' + i + '\"><span class=\"dot\" style=\"background:' + statusColor(r.status) + '\"></span>' + esc(r.regionName) + '</div>';\n"
                + "  });\n"
                + "  listDiv.innerHTML = html || '<div style=\"padding:10px;color:#999;font-size:12px\">无匹配地址</div>';\n"
                + "}\n"
                + "listDiv.addEventListener('click', function(e){\n"
                + "  var el = e.target.closest ? e.target.closest('.li') : null;\n"
                + "  if (!el) return;\n"
                + "  jumpTo(+el.getAttribute('data-i'));\n"
                + "});\n"
                + "document.getElementById('addrSearch').addEventListener('input', function(){ buildList(this.value.trim()); });\n"
                + "if (REGIONS.length === 0){\n"
                + "  map.centerAndZoom(new BMap.Point(114.05, 22.55), 12);\n"
                + "} else {\n"
                + "  buildAll();\n"
                + "  buildList('');\n"
                + "  REGIONS.forEach(function(r, i){ map.addOverlay(markers[i]); if (polygons[i]) map.addOverlay(polygons[i]); });\n"
                + "}\n"
                + "</script>\n</body>\n</html>\n";

        writeFile(path, html);
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
