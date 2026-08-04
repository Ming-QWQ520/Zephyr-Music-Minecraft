package com.zephyr.music.net;

import com.google.gson.Gson;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网易云 API HTTP 客户端封装（基于 Java 11+ HttpClient）
 *
 * - GET/POST 请求，自动带 Cookie
 * - 异步响应，不阻塞 Minecraft 主线程
 * - 自动解包 api-enhanced 的 data 字段
 */
public class NeteaseHttpClient
{
    private static final Gson GSON = new Gson();
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
                .followRedirects(HttpClient.Redirect.NORMAL)
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
        // 去掉末尾斜杠
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public String getCookie()
    {
        String c = ZephyrConfig.COOKIE.get();
        return c == null ? "" : c;
    }

    public void setCookie(String cookie)
    {
        ZephyrConfig.COOKIE.set(cookie == null ? "" : cookie);
    }

    public void clearCookie()
    {
        ZephyrConfig.COOKIE.set("");
    }

    /**
     * 异步 GET 请求
     *
     * @param path   接口路径，例如 /login/qr/key
     * @param params 查询参数
     * @return CompletableFuture<JsonObject> 解包后的 JSON 响应
     */
    public CompletableFuture<JsonObject> get(String path, Map<String, String> params)
    {
        StringBuilder url = new StringBuilder(getApiBase()).append(path);
        if (params != null && !params.isEmpty())
        {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet())
            {
                if (!first) url.append("&");
                url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                   .append("=")
                   .append(URLEncoder.encode(sanitize(e.getValue()), StandardCharsets.UTF_8));
                first = false;
            }
        }
        // 添加时间戳避免缓存
        url.append(params == null || params.isEmpty() ? "?" : "&")
           .append("timestamp=").append(System.currentTimeMillis());

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ZephyrMusic-Mod/1.0")
                .header("Accept", "application/json")
                .GET();

        String cookie = getCookie();
        if (cookie != null && !cookie.isEmpty())
        {
            rb.header("Cookie", cookie);
        }

        return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse)
                .exceptionally(e -> {
                    ZephyrMusic.LOGGER.error("[Zephyr] GET {} failed: {}", path, e.getMessage());
                    JsonObject err = new JsonObject();
                    err.addProperty("code", -1);
                    err.addProperty("message", e.getMessage() == null ? "unknown" : e.getMessage());
                    return err;
                });
    }

    public CompletableFuture<JsonObject> get(String path)
    {
        return get(path, null);
    }

    /**
     * 异步 POST 请求（form-urlencoded）
     */
    public CompletableFuture<JsonObject> post(String path, Map<String, String> params)
    {
        StringBuilder form = new StringBuilder();
        if (params != null && !params.isEmpty())
        {
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet())
            {
                if (!first) form.append("&");
                form.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(sanitize(e.getValue()), StandardCharsets.UTF_8));
                first = false;
            }
        }
        form.append(params == null || params.isEmpty() ? "" : "&")
            .append("timestamp=").append(System.currentTimeMillis());

        String body = form.toString();
        String url = getApiBase() + path;

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "ZephyrMusic-Mod/1.0")
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        String cookie = getCookie();
        if (cookie != null && !cookie.isEmpty())
        {
            rb.header("Cookie", cookie);
        }

        return client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(this::parseResponse)
                .exceptionally(e -> {
                    ZephyrMusic.LOGGER.error("[Zephyr] POST {} failed: {}", path, e.getMessage());
                    JsonObject err = new JsonObject();
                    err.addProperty("code", -1);
                    err.addProperty("message", e.getMessage() == null ? "unknown" : e.getMessage());
                    return err;
                });
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

    /** 去掉 ASCII 控制字符 */
    private static String sanitize(String s)
    {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray())
        {
            if (c >= 0x20 && c != 0x7F)
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
