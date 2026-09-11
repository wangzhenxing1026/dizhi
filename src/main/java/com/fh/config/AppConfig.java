package com.fh.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 配置管理。优先读取工作目录下的 config.properties（便于直接编辑），
 * 找不到时回退到 classpath（src/main/resources/config.properties）。
 */
public class AppConfig {

    private final Properties props = new Properties();
    /** 配置文件实际来源；自动回写 auth 时写到这里（classpath 来源时写到 ./config.properties） */
    private final File backingFile;

    public AppConfig() {
        File local = new File("config.properties");
        if (local.isFile()) {
            backingFile = local;
            try (InputStream in = new FileInputStream(local)) {
                props.load(new java.io.InputStreamReader(in, "UTF-8"));
            } catch (Exception e) {
                throw new RuntimeException("读取 config.properties 失败: " + e.getMessage(), e);
            }
        } else {
            // classpath 回退；回写时落到 ./config.properties
            String path = System.getProperty("config.path", "/config.properties");
            backingFile = new File("config.properties");
            try (InputStream in = AppConfig.class.getResourceAsStream(path)) {
                if (in != null) {
                    props.load(new java.io.InputStreamReader(in, "UTF-8"));
                }
            } catch (Exception e) {
                throw new RuntimeException("读取内置 config.properties 失败: " + e.getMessage(), e);
            }
        }
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    public String getAuth() { return get("baidu.auth", ""); }
    public String getSeckey() { return get("baidu.seckey", ""); }
    public String getValidate() { return get("baidu.validate", ""); }
    public String getCookie() { return get("baidu.cookie", ""); }
    public String getCityCode() { return get("baidu.cityCode", "340"); }
    /** 搜索视野（墨卡托 (minX,minY;maxX,maxY)），默认深圳全域 */
    public String getViewBound() { return get("baidu.viewBound", "(12650000,2550000;12750000,2600000)"); }
    /** 结果坐标合理范围 lonMin,latMin,lonMax,latMax，超出视为跨市/异地错配 */
    public double[] getBounds() {
        String[] p = get("baidu.bounds", "113.75,22.40,114.65,22.88").split(",");
        try {
            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]), Double.parseDouble(p[3])};
        } catch (Exception e) {
            return new double[]{113.75, 22.40, 114.65, 22.88};
        }
    }
    public boolean isAutoRefresh() { return getBool("baidu.autoRefresh", true); }

    public int getEdgeDebugPort() { return getInt("edge.debugPort", 9222); }
    public boolean isEdgeAutoLaunch() { return getBool("edge.autoLaunch", true); }
    public int getEdgeCaptureSeconds() { return getInt("edge.captureSeconds", 25); }
    public int getMaxRefreshes() { return getInt("auth.maxRefreshes", 10); }
    public int getEmptyThreshold() { return getInt("auth.emptyThreshold", 3); }

    public String getInputPath() { return get("input.path", "dizhi.txt"); }
    public String getOutputPath() { return get("output.path", "output.txt"); }
    public String getHtmlPath() { return get("html.path", "map.html"); }
    public String getHtmlAk() { return get("html.ak", "【你的AK】"); }
    public String getCacheDir() { return get("cache.dir", "cache"); }
    public int getRegionIdStart() { return getInt("region.id.start", 10124); }

    public int getDelayMs() { return getInt("request.delay.ms", 300); }
    public int getConnectTimeoutMs() { return getInt("http.connectTimeoutMs", 10000); }
    public int getReadTimeoutMs() { return getInt("http.readTimeoutMs", 20000); }
    public int getRetries() { return getInt("http.retries", 2); }

    public int getScoreThreshold() { return getInt("match.score.threshold", 40); }
    public boolean isOffline() { return getBool("offline", false); }
    /** official=百度官方 MC2LL；simple=简化公式 */
    public String getCoordAlgorithm() { return get("coord.algorithm", "official"); }

    /** 鉴权刷新成功后回写配置文件，保证重启后仍有可用 key */
    public synchronized void saveAuth(String auth, String seckey, String validate, String cookie) {
        props.setProperty("baidu.auth", auth);
        props.setProperty("baidu.seckey", seckey);
        if (validate != null && !validate.isEmpty()) {
            props.setProperty("baidu.validate", validate);
        }
        if (cookie != null && !cookie.isEmpty()) {
            props.setProperty("baidu.cookie", cookie);
        }
        try (OutputStream out = new FileOutputStream(backingFile)) {
            props.store(new java.io.OutputStreamWriter(out, "UTF-8"),
                    "auto-updated auth at " + new java.util.Date());
        } catch (Exception e) {
            System.err.println("[警告] 回写 config.properties 失败: " + e.getMessage());
        }
    }
}
