package com.fh.service;

import com.fh.config.AppConfig;
import com.fh.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 百度地图搜索请求（qt=s，GET 简化方案，见需求 3.4）。
 * - 每次成功响应先落盘 cache/（文件名 = 查询词 MD5），再交给解析；
 * - offline=true 时只读缓存不联网（离线重放，支撑程序停止后的复核与调参）。
 */
public class BaiduMapService {

    private static final String URL_TEMPLATE =
            "https://map.baidu.com/?newmap=1&reqflag=pcmap&biz=1&from=webmap&da_par=direct&pcevaname=pc4.1"
                    + "&qt=s&da_src=searchBox.button&wd=%s&c=%s&src=0&wd2=&pn=0&sug=0&l=20"
                    + "&b=%s"
                    + "&from=webmap&biz_forward={\"scaler\":2,\"styles\":\"pl\"}&sug_forward="
                    + "&auth=%s&seckey=%s&device_ratio=2&tn=B_NORMAL_MAP&nn=0&u_loc=12702971,2562630"
                    + "&ie=utf-8&t=%d&newfrom=zhuzhan_webmap";

    /** HAR 原始模板的 b 是领秀花园附近约 200 米的小视野，会把结果限制在框内（如"大剧院"返回 0 条），必须用城市级视野 */
    private static final String DEFAULT_VIEW_BOUND = "(12650000,2550000;12750000,2600000)";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final AppConfig config;
    private final BaiduAuthManager authManager;

    public BaiduMapService(AppConfig config, BaiduAuthManager authManager) {
        this.config = config;
        this.authManager = authManager;
    }

    private File cacheFile(String query) {
        return new File(config.getCacheDir(), Util.md5(query) + ".json");
    }

    /** 读缓存；miss 返回 null */
    public String readCache(String query) {
        File f = cacheFile(query);
        if (f.isFile()) {
            try {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("[警告] 读缓存失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 获取查询结果原始 JSON。
     * 在线：先查缓存（命中且非强制在线则直接用），未命中发请求并落盘。
     * 离线：只读缓存。
     *
     * @param forceOnline true 时忽略缓存强制请求（鉴权刷新后的重试）
     */
    public String fetch(String query, boolean forceOnline) {
        if (!forceOnline) {
            String cached = readCache(query);
            if (cached != null) {
                return cached;
            }
            if (config.isOffline()) {
                return null;
            }
        } else if (config.isOffline()) {
            return readCache(query);
        }

        String body = null;
        int retries = Math.max(0, config.getRetries());
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                body = httpGet(buildUrl(query));
                if (body != null && !body.isEmpty()) {
                    // 鉴权/风控异常响应不落盘：避免污染离线缓存，且支持后续重试
                    if (!ResponseParser.parse(body).authError) {
                        writeCache(query, body);
                    }
                    break;
                }
            } catch (Exception e) {
                System.err.println(String.format("[警告] 请求失败(第%d次) %s: %s", attempt + 1, query, e.getMessage()));
                body = null;
                if (attempt < retries) {
                    sleep(500L * (attempt + 1));
                }
            }
        }
        return body;
    }

    public String fetch(String query) {
        return fetch(query, false);
    }

    private void writeCache(String query, String body) {
        File dir = new File(config.getCacheDir());
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("[警告] 创建缓存目录失败: " + dir.getAbsolutePath());
            return;
        }
        try {
            Files.write(cacheFile(query).toPath(), body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[警告] 写缓存失败: " + e.getMessage());
        }
    }

    private String buildUrl(String query) throws Exception {
        String wd = URLEncoder.encode(query, "UTF-8");
        return String.format(URL_TEMPLATE, wd, config.getCityCode(), config.getViewBound(),
                enc(authManager.getAuth()), enc(authManager.getSeckey()), System.currentTimeMillis());
    }

    private String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(config.getConnectTimeoutMs());
        conn.setReadTimeout(config.getReadTimeoutMs());
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Referer", "https://map.baidu.com/");
        conn.setRequestProperty("Accept", "*/*");
        String cookie = config.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        conn.disconnect();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
