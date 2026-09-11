package com.fh.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fh.config.AppConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 Edge 浏览器 CDP（Chrome DevTools Protocol）自动获取新的 auth/seckey。
 *
 * 前提：Edge 必须以 --remote-debugging-port=9222 启动（日常双击打开的实例无法被接管）。
 * 若端口不可用且 edge.autoLaunch=true，会以独立 user-data-dir 拉起一个新的 Edge 实例。
 *
 * 流程：新建标签页打开百度地图搜索页 → WebSocket 连接该页 → Network.requestWillBeSent
 * 事件中捕获 qt=s 请求 → 从 URL 提取 auth/seckey → 关闭标签页。
 */
public class AuthRefresher {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern URL_FIELD = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]*qt=s&[^\"]*)\"");
    private static final Pattern AUTH_P = Pattern.compile("[?&]auth=([^&\"]+)");
    private static final Pattern SECKEY_P = Pattern.compile("[?&]seckey=([^&\"]+)");
    private static final String[] EDGE_PATHS = {
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"
    };

    private final AppConfig config;
    /** 测试地址：触发一次真实搜索以产生 qt=s 请求 */
    private final String probeAddress = System.getProperty("probe.address", "领秀花园E栋");

    public AuthRefresher(AppConfig config) {
        this.config = config;
    }

    /** @return [auth, seckey, cookie]，失败返回 null */
    public String[] refresh() {
        int port = config.getEdgeDebugPort();
        String base = "http://127.0.0.1:" + port;
        try {
            if (!pingBase(base)) {
                if (!config.isEdgeAutoLaunch() || !launchEdge(port)) {
                    System.err.println("[CDP] 无法连接 127.0.0.1:" + port + "，且未能自动拉起 Edge。");
                    return null;
                }
                if (!waitBase(base, 20000)) {
                    System.err.println("[CDP] Edge 已拉起但调试端口 " + port + " 未就绪。");
                    return null;
                }
            }

            String searchUrl = "https://map.baidu.com/?newmap=1&ie=utf-8&wd="
                    + URLEncoder.encode(probeAddress, "UTF-8");
            String targetId = null;
            JsonNode target = createTarget(base, searchUrl);
            String wsUrl = target == null ? null : target.path("webSocketDebuggerUrl").asText(null);
            targetId = target == null ? null : target.path("id").asText(null);

            String[] pair = null;
            if (wsUrl != null) {
                pair = captureFromPage(wsUrl, searchUrl, config.getEdgeCaptureSeconds() * 1000);
            } else {
                System.err.println("[CDP] 创建标签页失败（/json/new 无响应）。");
            }
            if (targetId != null) {
                closeTarget(base, targetId);
            }
            return pair;
        } catch (Exception e) {
            System.err.println("[CDP] 刷新过程异常: " + e.getMessage());
            return null;
        }
    }

    /** 连接页面 WebSocket，监听网络事件直到捕获 qt=s 请求或超时 */
    private String[] captureFromPage(String wsUrl, String searchUrl, long timeoutMs) {
        WsClient ws = null;
        try {
            ws = new WsClient(wsUrl, 5000);
            ws.connect();
            ws.sendText("{\"id\":1,\"method\":\"Network.enable\"}");
            ws.sendText("{\"id\":2,\"method\":\"Page.enable\"}");
            ws.sendText("{\"id\":3,\"method\":\"Page.navigate\",\"params\":{\"url\":\""
                    + searchUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}");
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String msg = ws.readMessage();
                if (msg == null) {
                    break;
                }
                if (!msg.contains("Network.requestWillBeSent") || !msg.contains("qt=s&")) {
                    continue;
                }
                Matcher um = URL_FIELD.matcher(msg);
                if (!um.find()) {
                    continue;
                }
                String url = um.group(1);
                Matcher am = AUTH_P.matcher(url);
                Matcher sm = SECKEY_P.matcher(url);
                if (am.find() && sm.find()) {
                    String cookie = extractCookieHeader(msg);
                    return new String[]{URLDecoder.decode(am.group(1), "UTF-8"),
                            URLDecoder.decode(sm.group(1), "UTF-8"), cookie};
                }
            }
            System.err.println("[CDP] " + (timeoutMs / 1000) + " 秒内未捕获到 qt=s 请求（页面可能未完成搜索）。");
        } catch (Exception e) {
            System.err.println("[CDP] 监听异常: " + e.getMessage());
        } finally {
            if (ws != null) {
                ws.close();
            }
        }
        return null;
    }

    /** 从 Network.requestWillBeSent 事件 JSON 中提取请求头 Cookie */
    static String extractCookieHeader(String eventJson) {
        try {
            JsonNode n = MAPPER.readTree(eventJson);
            JsonNode cookie = n.path("params").path("request").path("headers").path("Cookie");
            if (cookie.isMissingNode()) {
                cookie = n.path("params").path("request").path("headers").path("cookie");
            }
            return cookie.isMissingNode() ? null : cookie.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean pingBase(String base) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "/json/version").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            int code = c.getResponseCode();
            c.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitBase(String base, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pingBase(base)) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** 以独立 profile 拉起 Edge 实例（不影响日常使用的 Edge 进程） */
    private boolean launchEdge(int port) {
        String edge = findEdge();
        if (edge == null) {
            System.err.println("[CDP] 未找到 msedge.exe，请手动以 --remote-debugging-port=" + port + " 启动 Edge。");
            return false;
        }
        try {
            File profile = new File("edge-cdp-profile");
            if (!profile.exists()) {
                profile.mkdirs();
            }
            new ProcessBuilder(edge,
                    "--remote-debugging-port=" + port,
                    "--user-data-dir=" + profile.getAbsolutePath(),
                    "--no-first-run", "--no-default-browser-check",
                    "https://map.baidu.com/")
                    .start();
            System.err.println("[CDP] 已拉起独立 Edge 实例（调试端口 " + port + "，profile: " + profile.getAbsolutePath() + "）。");
            return true;
        } catch (Exception e) {
            System.err.println("[CDP] 启动 Edge 失败: " + e.getMessage());
            return false;
        }
    }

    private String findEdge() {
        for (String p : EDGE_PATHS) {
            if (new File(p).isFile()) {
                return p;
            }
        }
        String pf86 = System.getenv("ProgramFiles(x86)");
        if (pf86 != null) {
            String p = pf86 + "\\Microsoft\\Edge\\Application\\msedge.exe";
            if (new File(p).isFile()) {
                return p;
            }
        }
        return null;
    }

    /** 新建标签页。新版 Edge 要求 PUT，旧版只支持 GET，两者都试 */
    private JsonNode createTarget(String base, String url) throws Exception {
        String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8.name());
        String body = tryHttp(base + "/json/new?" + encoded, "PUT");
        if (body == null) {
            body = tryHttp(base + "/json/new?" + encoded, "GET");
        }
        if (body == null) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private void closeTarget(String base, String targetId) {
        tryHttp(base + "/json/close/" + targetId, "GET");
    }

    private String tryHttp(String url, String method) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? null : readAll(in);
            c.disconnect();
            if (code == 200 && body != null && !body.isEmpty()) {
                return body;
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 极简 WebSocket 客户端（仅满足 CDP 需要）：文本帧收发、分帧聚合、ping/pong、关闭帧。
     * 客户端→服务端帧必须带掩码。
     */
    static class WsClient {
        private final String host;
        private final int port;
        private final String path;
        private final int soTimeout;
        private Socket socket;
        private BufferedInputStream in;
        private BufferedOutputStream out;
        private final SecureRandom random = new SecureRandom();

        WsClient(String wsUrl, int soTimeoutMs) throws Exception {
            // ws://127.0.0.1:9222/devtools/page/XXXX
            String rest = wsUrl.startsWith("ws://") ? wsUrl.substring(5) : wsUrl;
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            this.path = slash < 0 ? "/" : rest.substring(slash);
            int colon = hostPort.indexOf(':');
            this.host = colon < 0 ? hostPort : hostPort.substring(0, colon);
            this.port = colon < 0 ? 80 : Integer.parseInt(hostPort.substring(colon + 1));
            this.soTimeout = soTimeoutMs;
        }

        void connect() throws Exception {
            socket = new Socket(host, port);
            socket.setSoTimeout(soTimeout);
            OutputStream os = socket.getOutputStream();
            byte[] keyBytes = new byte[16];
            random.nextBytes(keyBytes);
            String key = Base64.getEncoder().encodeToString(keyBytes);
            String handshake = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            os.write(handshake.getBytes(StandardCharsets.US_ASCII));
            os.flush();
            in = new BufferedInputStream(socket.getInputStream());
            // 读到空行为止（101 响应头结束）
            StringBuilder head = new StringBuilder();
            int prev = -1, c;
            while ((c = in.read()) != -1) {
                head.append((char) c);
                if (prev == '\r' && c == '\n' && head.length() >= 4
                        && head.charAt(head.length() - 4) == '\r' && head.charAt(head.length() - 3) == '\n') {
                    break;
                }
                prev = c;
            }
            if (!head.toString().contains(" 101 ")) {
                throw new IllegalStateException("WebSocket 握手失败: " + head);
            }
            out = new BufferedOutputStream(socket.getOutputStream());
        }

        void sendText(String text) throws Exception {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
            frame.write(0x81); // FIN + text
            int len = payload.length;
            if (len < 126) {
                frame.write(0x80 | len);
            } else if (len < 65536) {
                frame.write(0x80 | 126);
                frame.write((len >> 8) & 0xFF);
                frame.write(len & 0xFF);
            } else {
                frame.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((len >>> (i * 8)) & 0xFF);
                }
            }
            frame.write(mask);
            for (int i = 0; i < len; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            synchronized (out) {
                out.write(frame.toByteArray());
                out.flush();
            }
        }

        /** 读一条完整文本消息；连接关闭返回 null */
        String readMessage() throws Exception {
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 == -1) {
                    return acc.size() > 0 ? acc.toString("UTF-8") : null;
                }
                int b1 = in.read();
                if (b1 == -1) {
                    return acc.size() > 0 ? acc.toString("UTF-8") : null;
                }
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (in.read() & 0xFF);
                    }
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }
                switch (opcode) {
                    case 0x1: // text
                    case 0x0: // continuation
                        acc.write(payload);
                        if (fin) {
                            return acc.toString("UTF-8");
                        }
                        break;
                    case 0x9: // ping → pong
                        sendControl(0x8A, payload);
                        break;
                    case 0x8: // close
                        return acc.size() > 0 ? acc.toString("UTF-8") : null;
                    default:
                        // 二进制等忽略
                        break;
                }
            }
        }

        private void sendControl(int opcode, byte[] payload) throws Exception {
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            frame.write(0x80 | payload.length);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            synchronized (out) {
                out.write(frame.toByteArray());
                out.flush();
            }
        }

        private void readFully(byte[] buf) throws Exception {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n == -1) {
                    throw new IllegalStateException("WebSocket 连接中断");
                }
                off += n;
            }
        }

        void close() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignore) {
            }
        }
    }
}
