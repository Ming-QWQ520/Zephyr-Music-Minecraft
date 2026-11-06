package com.zephyr.music.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.config.ZephyrConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网易云 API HTTP 客户端封装（基于 Java 11+ HttpClient）
 *
 * ★ 关键修复：
 *   1. Cookie 同时通过 URL 参数 cookie=... 和 Cookie 请求头发送
 *      （api-enhanced NeteaseCloudMusicApi 主要从 URL 参数读取 cookie，
 *       因为浏览器 CORS 限制无法设置 Cookie 头）
 *   2. Cookie 字符串在保存和发送前去除换行/控制字符，防止破坏 HTTP header
 *   3. 异步响应，不阻塞 Minecraft 主线程
 */
public class NeteaseHttpClient
{
    private static NeteaseHttpClient instance;

    private final HttpClient client;
    private final ExecutorService executor;

    private NeteaseHttpClient()
    {
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ZephyrMusic-Net");
            t.setDaemon(true);
            return t;
        });
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .executor(executor)
                // ★ 手动跟随重定向：HttpClient 自动重定向遇到 https→http 降级会抛
                //   "Invalid redirection"（/login/refresh 实测会触发），改为手动处理
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static synchronized NeteaseHttpClient getInstance()
    {
        if (instance == null)
        {
            instance = new NeteaseHttpClient();
        }
        return instance;
    }

    public String getApiBase()
    {
        String base = ZephyrConfig.API_BASE.get();
        if (base == null || base.isEmpty())
        {
            base = "https://musicapi.mingqwq.top";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String getCookie()
    {
        String c = com.zephyr.music.config.LoginStore.getCookie();
        return c == null ? "" : c;
    }

    /** 保存 cookie 时清理换行符和控制字符 */
    public void setCookie(String cookie)
    {
        com.zephyr.music.config.LoginStore.setCookie(sanitizeCookie(cookie));
    }

    public void clearCookie()
    {
        com.zephyr.music.config.LoginStore.clear();
    }

    /** 清理 cookie 中的换行/控制字符（防止破坏 HTTP header） */
    private static String sanitizeCookie(String s)
    {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            // 只保留可见 ASCII 字符（0x20-0x7E）和扩展字符（0x80-0xFF）
            if (c == 0x20 || (c >= 0x21 && c != 0x7F) || c >= 0x80)
            {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 构建查询参数 Map：自动加入 timestamp 和 cookie（如有）
     */
    private LinkedHashMap<String, String> buildParams(Map<String, String> params)
    {
        LinkedHashMap<String, String> all = new LinkedHashMap<>();
        if (params != null) all.putAll(params);
        all.put("timestamp", String.valueOf(System.currentTimeMillis()));
        // ★ 关键：cookie 通过 URL 参数传递（api-enhanced 主要从这里读取）
        String cookie = getCookie();
        if (cookie != null && !cookie.isEmpty())
        {
            all.put("cookie", cookie);
        }
        return all;
    }

    private String buildQueryString(Map<String, String> params)
    {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet())
        {
            if (!first) sb.append("&");
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
               .append("=")
               .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    /**
     * 发送请求：手动跟随重定向（最多 3 次，允许 https→http 降级）+ 连接失败自动重试。
     * 返回最终非重定向响应。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest.Builder rb, String method, String body)
            throws Exception
    {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(rb.build().uri())
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "ZephyrMusic-Mod/1.0")
                        .header("Accept", "application/json");
                String cookie = getCookie();
                if (cookie != null && !cookie.isEmpty())
                {
                    try { b.header("Cookie", cookie); }
                    catch (IllegalArgumentException e) { ZephyrMusic.LOGGER.warn("[Zephyr] Cookie header rejected: {}", e.getMessage()); }
                }
                if ("POST".equals(method))
                    b.header("Content-Type", "application/x-www-form-urlencoded")
                     .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                else b.GET();

                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if ((sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308))
                {
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc != null && !loc.isEmpty())
                    {
                        URI next = rb.build().uri().resolve(loc);
                        // 303/302/301 上 POST 降为 GET
                        if ("POST".equals(method) && (sc == 301 || sc == 302 || sc == 303))
                            return sendWithRetry(HttpRequest.newBuilder(next), "GET", null);
                        return sendWithRetry(HttpRequest.newBuilder(next), method, body);
                    }
                }
                return resp;
            }
            catch (java.io.IOException e)
            {
                last = e;
                if (attempt < 2)
                {
                    try { Thread.sleep(600L * (attempt + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                }
            }
        }
        throw last != null ? last : new java.io.IOException("request failed");
    }

    /**
     * 异步 GET 请求（带重定向跟随 + 重试）
     */
    public CompletableFuture<JsonObject> get(String path, Map<String, String> params)
    {
        StringBuilder url = new StringBuilder(getApiBase()).append(path);
        LinkedHashMap<String, String> allParams = buildParams(params);
        url.append("?").append(buildQueryString(allParams));
        final String furl = url.toString();

        return CompletableFuture.supplyAsync(() -> {
            try
            {
                HttpResponse<String> resp = sendWithRetry(HttpRequest.newBuilder(URI.create(furl)), "GET", null);
                return parseResponse(resp);
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] GET {} failed: {}", path, e.getMessage());
                JsonObject err = new JsonObject();
                err.addProperty("code", -1);
                err.addProperty("message", e.getMessage() == null ? "unknown" : e.getMessage());
                return err;
            }
        }, executor);
    }

    public CompletableFuture<JsonObject> get(String path)
    {
        return get(path, null);
    }

    /**
     * 异步 POST 请求（form-urlencoded，带重定向跟随 + 重试）
     */
    public CompletableFuture<JsonObject> post(String path, Map<String, String> params)
    {
        LinkedHashMap<String, String> allParams = buildParams(params);
        String body = buildQueryString(allParams);
        String url = getApiBase() + path;

        return CompletableFuture.supplyAsync(() -> {
            try
            {
                HttpResponse<String> resp = sendWithRetry(HttpRequest.newBuilder(URI.create(url)), "POST", body);
                return parseResponse(resp);
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] POST {} failed: {}", path, e.getMessage());
                JsonObject err = new JsonObject();
                err.addProperty("code", -1);
                err.addProperty("message", e.getMessage() == null ? "unknown" : e.getMessage());
                return err;
            }
        }, executor);
    }

    /**
     * 异步下载二进制（音频流）
     */
    public CompletableFuture<java.io.InputStream> downloadStream(String url)
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "ZephyrMusic-Mod/1.0")
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(HttpResponse::body)
                .exceptionally(e -> {
                    ZephyrMusic.LOGGER.error("[Zephyr] download failed: {}", e.getMessage());
                    return null;
                });
    }

    /**
     * 异步下载二进制为字节数组
     */
    public CompletableFuture<byte[]> downloadBytes(String url)
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "ZephyrMusic-Mod/1.0")
                .GET()
                .build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(HttpResponse::body)
                .exceptionally(e -> {
                    ZephyrMusic.LOGGER.error("[Zephyr] download failed: {}", e.getMessage());
                    return null;
                });
    }

    private JsonObject parseResponse(HttpResponse<String> resp)
    {
        try
        {
            String body = resp.body();
            if (body == null || body.isEmpty())
            {
                JsonObject o = new JsonObject();
                o.addProperty("code", resp.statusCode());
                o.addProperty("message", "empty body");
                return o;
            }
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();

            // 提取 Set-Cookie（用于登录后保存 cookie）
            String setCookie = resp.headers().firstValue("Set-Cookie").orElse(null);
            if (setCookie != null && !setCookie.isEmpty())
            {
                obj.addProperty("_setCookie", setCookie);
            }

            return obj;
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] parse response failed: {}", e.getMessage());
            JsonObject err = new JsonObject();
            err.addProperty("code", -2);
            err.addProperty("message", "parse error: " + e.getMessage());
            return err;
        }
    }
}
