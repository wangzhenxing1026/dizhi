package com.fh;

import com.fh.config.AppConfig;
import com.fh.model.AddressResult;
import com.fh.model.Candidate;
import com.fh.service.AddressMatcher;
import com.fh.service.AddressSplitter;
import com.fh.service.BaiduAuthManager;
import com.fh.service.BaiduMapService;
import com.fh.service.ResponseParser;
import com.fh.util.CoordinateConverter;
import com.fh.util.OutputFormatter;
import com.fh.util.PolygonMerger;
import com.fh.util.PolygonUtils;
import com.fh.util.Util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 百度地图地址查询与格式化工具。
 * 用法：
 *   java com.fh.Main            正常运行（读 dizhi.txt，输出 output.txt / map.html / failed.txt / review.txt）
 *   java com.fh.Main --offline  离线重放：只读 cache/ 目录，不联网
 *   java com.fh.Main --selftest 离线自测（坐标转换/解析/拆分/评分/合并）
 */
public class Main {

    public static void main(String[] args) throws Exception {
        boolean offline = false;
        for (String a : args) {
            if ("--offline".equals(a)) {
                offline = true;
            }
            if ("--selftest".equals(a)) {
                System.exit(SelfTest.run() ? 0 : 1);
                return;
            }
        }
        new Runner(new AppConfig(), offline).run();
    }

    static class Runner {
        private final AppConfig config;
        private final boolean offline;
        private final BaiduAuthManager authManager;
        private final BaiduMapService service;
        private final boolean officialCoord;
        /** 连续 content 为空的地址数（判定鉴权失效用） */
        private int consecutiveEmpty = 0;

        Runner(AppConfig config, boolean offline) {
            this.config = config;
            this.offline = offline;
            this.authManager = new BaiduAuthManager(config);
            this.service = new BaiduMapService(config, authManager);
            this.officialCoord = "official".equalsIgnoreCase(config.getCoordAlgorithm());
        }

        void run() {
            List<String> addresses = readAddresses();
            if (addresses.isEmpty()) {
                System.err.println("没有可处理的地址（input.path=" + config.getInputPath() + "）");
                return;
            }
            System.out.println((offline ? "[离线重放] " : "[在线] ") + "共 " + addresses.size() + " 个地址，坐标算法="
                    + config.getCoordAlgorithm() + "，评分阈值=" + config.getScoreThreshold());

            int regionId = config.getRegionIdStart();
            List<AddressResult> results = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            for (int lineNo = 0; lineNo < addresses.size(); lineNo++) {
                String address = addresses.get(lineNo);
                if (!seen.add(address)) {
                    System.out.println("[跳过] 第" + (lineNo + 1) + "行重复地址: " + address);
                    continue;
                }
                AddressResult r = new AddressResult();
                r.inputAddress = address;
                r.regionId = regionId;
                try {
                    processOne(r, address);
                } catch (Exception e) {
                    r.status = AddressResult.STATUS_FAILED;
                    r.message = "异常: " + e.getMessage();
                }
                if (r.ok()) {
                    regionId++;
                    String tag = AddressResult.STATUS_LOW.equals(r.status) ? "低置信" : "成功";
                    System.out.println(String.format("[%s] %s -> %s, %s", tag, address,
                            Util.fmt14(r.centerLon), Util.fmt14(r.centerLat)));
                } else {
                    System.out.println("[失败] " + address + " -> " + r.message);
                }
                results.add(r);
                sleepDelay();
            }

            writeOutputs(results);

            int ok = 0, low = 0, fail = 0, noPoly = 0;
            for (AddressResult r : results) {
                if (r.ok()) {
                    if (AddressResult.STATUS_LOW.equals(r.status)) {
                        low++;
                    } else {
                        ok++;
                    }
                    if (r.polygon.isEmpty()) {
                        noPoly++;
                    }
                } else {
                    fail++;
                }
            }
            System.out.println(String.format("[汇总] 共 %d 条：成功 %d，低置信度 %d，失败 %d，无多边形 %d",
                    results.size(), ok, low, fail, noPoly));
        }

        private void processOne(AddressResult r, String address) {
            List<String> subs = AddressSplitter.split(address);
            r.subQueries.addAll(subs);
            boolean multi = subs.size() > 1;

            List<double[]> centersMc = new ArrayList<>();
            List<List<double[]>> polysMc = new ArrayList<>();
            String firstType = null;
            String firstName = null;
            List<String> details = new ArrayList<>();
            boolean lowConfidence = false;
            List<String> notes = new ArrayList<>();

            for (String sub : subs) {
                QueryOutcome q = queryBest(sub);
                if (q.best == null) {
                    if (multi) {
                        System.out.println("  [部分失败] 子地址无结果: " + sub);
                    }
                    continue;
                }
                centersMc.add(new double[]{q.best.centerX, q.best.centerY});
                List<double[]> poly = q.best.hasPolygons()
                        ? PolygonUtils.selectPolygon(q.best.polygons, q.best.centerX, q.best.centerY)
                        : null;
                if (poly != null) {
                    polysMc.add(poly);
                }
                if (firstType == null) {
                    firstType = pickType(q.best);
                    firstName = q.best.name;
                }
                details.add("[" + q.best.name + "] " + q.detail);
                if (q.lowConfidence) {
                    lowConfidence = true;
                }
                if (q.note != null) {
                    notes.add(q.note);
                }
                if (!multi) {
                    r.selectedName = q.best.name;
                    r.regionName = q.best.name == null || q.best.name.isEmpty() ? address : q.best.name;
                    r.scoreDetail = "[" + q.best.name + "] " + q.detail;
                }
            }

            if (multi) {
                r.selectedName = firstName;
                r.regionName = address; // 多楼栋合并输出用原始地址（需求 4.2 步骤5）
                r.scoreDetail = String.join(" || ", details);
            }
            r.placeType = firstType == null ? "未知" : firstType;

            if (centersMc.isEmpty()) {
                r.status = AddressResult.STATUS_FAILED;
                r.message = notes.isEmpty() ? "所有查询均无有效结果" : String.join("; ", notes);
                return;
            }
            if (centersMc.size() < subs.size()) {
                notes.add("部分子地址失败（" + centersMc.size() + "/" + subs.size() + "）");
            }

            // 中心点与多边形（墨卡托）
            double[] centerMc;
            if (multi && polysMc.size() >= 1) {
                centerMc = PolygonMerger.mergeCenter(polysMc, centersMc);
                List<double[]> mergedMc = PolygonMerger.convexHull(flat(polysMc));
                if (mergedMc != null) {
                    r.polygon = toLonLat(mergedMc);
                } else {
                    notes.add("合并多边形为空");
                }
            } else {
                centerMc = centersMc.get(0);
            }
            if (r.polygon.isEmpty() && !polysMc.isEmpty()) {
                r.polygon = toLonLat(polysMc.get(0));
            }
            if (centerMc == null) {
                r.status = AddressResult.STATUS_FAILED;
                r.message = "无法确定中心点";
                return;
            }

            double[] ll = CoordinateConverter.mcToBd09(centerMc[0], centerMc[1], officialCoord);
            r.centerLon = ll[0];
            r.centerLat = ll[1];
            if (lowConfidence) {
                r.status = AddressResult.STATUS_LOW;
                r.message = String.join("; ", notes);
            } else {
                r.status = AddressResult.STATUS_SUCCESS;
                r.message = notes.isEmpty() ? null : String.join("; ", notes);
                if (r.message != null && !r.message.isEmpty()) {
                    r.status = AddressResult.STATUS_LOW;
                }
            }
        }

        /** 单个子地址查询结果 */
        static class QueryOutcome {
            Candidate best;
            String detail;
            boolean lowConfidence;
            String note;
        }

        /** 请求（含鉴权失效自动刷新重试）→ 解析 → 评分择优 */
        private QueryOutcome queryBest(String sub) {
            QueryOutcome out = new QueryOutcome();
            String raw = service.fetch(sub);
            if (raw == null) {
                if (offline) {
                    out.note = "缓存未命中";
                }
                return out;
            }
            ResponseParser.Outcome outcome = ResponseParser.parse(raw);
            if (outcome.authError) {
                boolean recovered = false;
                // 轻量恢复：直接采用响应携带的 anti_auth
                if (outcome.antiAuth != null && authManager.adoptAntiAuth(outcome.antiAuth)) {
                    raw = service.fetch(sub, true);
                    outcome = raw == null ? null : ResponseParser.parse(raw);
                    recovered = outcome != null && !outcome.authError;
                }
                // 完整恢复：Edge CDP 刷新 auth/seckey/cookie
                if (!recovered) {
                    boolean refreshed = authManager.refresh(outcome.errorInfo);
                    if (refreshed) {
                        raw = service.fetch(sub, true); // 强制在线重试
                        outcome = raw == null ? null : ResponseParser.parse(raw);
                    }
                    if (outcome == null || outcome.authError) {
                        out.note = "鉴权失效且未能恢复";
                        return out;
                    }
                }
            }
            if (outcome.candidates.isEmpty()) {
                consecutiveEmpty++;
                if (consecutiveEmpty >= config.getEmptyThreshold()) {
                    consecutiveEmpty = 0;
                    if (authManager.refresh("连续 " + config.getEmptyThreshold() + " 个地址无结果")) {
                        raw = service.fetch(sub, true);
                        if (raw != null) {
                            outcome = ResponseParser.parse(raw);
                        }
                    }
                }
                if (outcome == null || outcome.candidates.isEmpty()) {
                    return out;
                }
            } else {
                consecutiveEmpty = 0;
            }
            List<AddressMatcher.Score> scores = AddressMatcher.scoreAll(outcome.candidates, sub);
            // 城市与坐标范围守卫：跨市/异地候选跳过，取范围内最高分；全部越界则该子地址按失败处理
            double[] bounds = config.getBounds();
            int skipped = 0;
            AddressMatcher.Score chosen = null;
            for (AddressMatcher.Score s : scores) {
                Candidate c = s.candidate;
                if (c.cityName != null && !c.cityName.isEmpty() && !"深圳市".equals(c.cityName)) {
                    skipped++;
                    continue;
                }
                if (c.hasCenter) {
                    double[] ll = CoordinateConverter.mcToBd09(c.centerX, c.centerY, officialCoord);
                    if (ll[0] < bounds[0] || ll[0] > bounds[2] || ll[1] < bounds[1] || ll[1] > bounds[3]) {
                        skipped++;
                        continue;
                    }
                }
                chosen = s;
                break;
            }
            if (chosen == null) {
                out.note = "所有候选均为市外/越界结果";
                return out;
            }
            out.best = chosen.candidate;
            out.detail = chosen.detail + (skipped > 0 ? "（已跳过" + skipped + "个市外/越界候选）" : "");
            if (chosen.total < config.getScoreThreshold()) {
                out.lowConfidence = true;
                out.note = "「" + sub + "」最高分 " + String.format("%.0f", chosen.total)
                        + " 低于阈值 " + config.getScoreThreshold();
            }
            // 打印候选评分明细（供人工复核）
            for (AddressMatcher.Score s : scores) {
                System.out.println("    候选[" + s.candidate.index + "] " + s.candidate.name + " : " + s.detail);
            }
            return out;
        }

        private String pickType(Candidate c) {
            String tag = c.stdTag;
            if (tag == null || tag.isEmpty()) {
                tag = c.showTag;
            }
            if (tag == null || tag.isEmpty()) {
                return "未知";
            }
            // std_tag 形如 "房地产;内部楼栋"，取第二段更有区分度
            int semi = tag.lastIndexOf(';');
            return semi >= 0 && semi < tag.length() - 1 ? tag.substring(semi + 1) : tag;
        }

        private List<double[]> flat(List<List<double[]>> polys) {
            List<double[]> all = new ArrayList<>();
            for (List<double[]> p : polys) {
                all.addAll(p);
            }
            return all;
        }

        private List<double[]> toLonLat(List<double[]> mc) {
            List<double[]> out = new ArrayList<>(mc.size());
            for (double[] p : mc) {
                out.add(CoordinateConverter.mcToBd09(p[0], p[1], officialCoord));
            }
            // 闭合多边形
            if (out.size() >= 3) {
                double[] first = out.get(0);
                double[] last = out.get(out.size() - 1);
                if (first[0] != last[0] || first[1] != last[1]) {
                    out.add(new double[]{first[0], first[1]});
                }
            }
            return out;
        }

        private List<String> readAddresses() {
            List<String> out = new ArrayList<>();
            String path = config.getInputPath();
            try {
                List<String> lines;
                Path p = Paths.get(path);
                if (Files.isRegularFile(p)) {
                    lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                } else {
                    InputStream in = Main.class.getResourceAsStream("/" + path);
                    if (in == null) {
                        System.err.println("找不到输入文件: " + path);
                        return out;
                    }
                    lines = readLines(in);
                }
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    // dizhi.txt 首行"具体位置描述"是标题行
                    if ("具体位置描述".equals(line)) {
                        continue;
                    }
                    out.add(line);
                }
            } catch (Exception e) {
                System.err.println("读取输入文件失败: " + e.getMessage());
            }
            return out;
        }

        private List<String> readLines(InputStream in) throws Exception {
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            br.close();
            return lines;
        }

        private void writeOutputs(List<AddressResult> results) {
            try {
                OutputFormatter.writeOutputTxt(results, config.getOutputPath());
            } catch (Exception e) {
                System.err.println("输出 output.txt 失败: " + e.getMessage());
            }
            try {
                OutputFormatter.writeMapHtml(results, config.getHtmlPath(), config.getHtmlAk());
            } catch (Exception e) {
                System.err.println("输出 map.html 失败: " + e.getMessage());
            }
            try {
                OutputFormatter.writeFailedTxt(results, "failed.txt");
            } catch (Exception e) {
                System.err.println("输出 failed.txt 失败: " + e.getMessage());
            }
            try {
                OutputFormatter.writeReviewTxt(results, "review.txt");
            } catch (Exception e) {
                System.err.println("输出 review.txt 失败: " + e.getMessage());
            }
        }

        private void sleepDelay() {
            if (offline) {
                return;
            }
            try {
                Thread.sleep(config.getDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
