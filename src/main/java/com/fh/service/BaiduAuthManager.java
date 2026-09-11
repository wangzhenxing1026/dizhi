package com.fh.service;

import com.fh.config.AppConfig;

/** 鉴权参数管理：失效检测后的自动刷新调度 + 配置回写 */
public class BaiduAuthManager {

    private final AppConfig config;
    private final AuthRefresher refresher;
    private volatile String auth;
    private volatile String seckey;
    private volatile String validate;
    private volatile String cookie;
    private int refreshCount = 0;

    public BaiduAuthManager(AppConfig config) {
        this.config = config;
        this.auth = config.getAuth();
        this.seckey = config.getSeckey();
        this.validate = config.getValidate();
        this.cookie = config.getCookie();
        this.refresher = new AuthRefresher(config);
    }

    public String getAuth() { return auth; }
    public String getSeckey() { return seckey; }
    public String getValidate() { return validate; }
    public String getCookie() { return cookie; }

    /** 直接采用响应中携带的 anti_auth（轻量恢复，优先于完整刷新流程） */
    public synchronized boolean adoptAntiAuth(String antiAuth) {
        if (antiAuth == null || antiAuth.isEmpty()) {
            return false;
        }
        this.auth = antiAuth;
        System.err.println("[鉴权] 已采用响应携带的 anti_auth 作为新 auth。auth=" + abbreviate(auth));
        return true;
    }

    /**
     * 鉴权失效时通过 Edge CDP 自动刷新。
     *
     * @return 是否刷新成功
     */
    public synchronized boolean refresh(String reason) {
        if (!config.isAutoRefresh()) {
            System.err.println("[鉴权] 已失效但自动刷新未开启（baidu.autoRefresh=false），请手动抓包更新 config.properties。原因: " + reason);
            return false;
        }
        if (refreshCount >= config.getMaxRefreshes()) {
            System.err.println("[鉴权] 自动刷新次数已达上限 " + config.getMaxRefreshes() + "，停止刷新。");
            return false;
        }
        refreshCount++;
        System.err.println("[鉴权] 检测到失效，尝试通过 Edge 浏览器自动刷新（第 " + refreshCount + " 次）。原因: " + reason);
        String[] triple = refresher.refresh();
        if (triple != null && triple.length >= 2) {
            this.auth = triple[0];
            this.seckey = triple[1];
            if (triple.length >= 3 && triple[2] != null && !triple[2].isEmpty()) {
                this.cookie = triple[2];
            }
            config.saveAuth(auth, seckey, validate, cookie);
            System.err.println("[鉴权] 刷新成功，新 auth/cookie 已回写 config.properties。auth=" + abbreviate(auth));
            return true;
        }
        System.err.println("[鉴权] 自动刷新失败。请确认 Edge 以 --remote-debugging-port="
                + config.getEdgeDebugPort() + " 启动，或手动抓包更新 config.properties。");
        return false;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 20 ? s : s.substring(0, 20) + "...";
    }
}
