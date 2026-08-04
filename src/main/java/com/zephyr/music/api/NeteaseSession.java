package com.zephyr.music.api;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.config.ZephyrConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云音乐会话状态：保存登录用户、歌单缓存
 */
public class NeteaseSession
{
    private static NeteaseSession instance;
    private final NeteaseApi api = new NeteaseApi();

    private NeteaseUser currentUser;
    private List<NeteasePlaylist> cachedPlaylists = new ArrayList<>();
    private long lastPlaylistFetchTime = 0;
    private boolean loggedIn = false;

    private NeteaseSession() {}

    public static synchronized NeteaseSession getInstance()
    {
        if (instance == null) instance = new NeteaseSession();
        return instance;
    }

    public NeteaseApi getApi()
    {
        return api;
    }

    public boolean isLoggedIn()
    {
        return loggedIn;
    }

    public NeteaseUser getCurrentUser()
    {
        return currentUser;
    }

    public List<NeteasePlaylist> getCachedPlaylists()
    {
        return cachedPlaylists;
    }

    /**
     * 用已有 cookie 检查登录状态
     */
    public CompletableFuture<Boolean> checkLoginStatus()
    {
        if (ZephyrConfig.COOKIE.get() == null || ZephyrConfig.COOKIE.get().isEmpty())
        {
            loggedIn = false;
            currentUser = null;
            return CompletableFuture.completedFuture(false);
        }
        return api.loginStatus().thenApply(resp -> {
            try
            {
                NeteaseUser u = NeteaseApi.parseUser(resp);
                if (u != null && u.userId != 0)
                {
                    currentUser = u;
                    loggedIn = true;
                    ZephyrMusic.LOGGER.info("[Zephyr] Logged in as {} ({})", u.nickname, u.userId);
                    return true;
                }
                loggedIn = false;
                return false;
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] checkLoginStatus failed", e);
                loggedIn = false;
                return false;
            }
        });
    }

    /**
     * 处理登录响应：提取 cookie 并保存
     */
    public boolean handleLoginResponse(JsonObject resp)
    {
        try
        {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            if (code != 200 && code != 803)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr] login failed: code={}, msg={}",
                        code, resp.has("message") ? resp.get("message").getAsString()
                                : (resp.has("msg") ? resp.get("msg").getAsString() : ""));
                return false;
            }
            String cookie = NeteaseApi.extractCookie(resp);
            if (cookie != null && !cookie.isEmpty())
            {
                ZephyrConfig.COOKIE.set(cookie);
                loggedIn = true;
                ZephyrMusic.LOGGER.info("[Zephyr] Login cookie saved");
            }
            else if (resp.has("_setCookie") && !resp.get("_setCookie").isJsonNull())
            {
                // 从响应头提取的 Set-Cookie
                String sc = resp.get("_setCookie").getAsString();
                if (sc != null && !sc.isEmpty())
                {
                    // 简化处理：只保留第一段，并尝试拼接完整
                    ZephyrConfig.COOKIE.set(parseSetCookieHeader(sc));
                    loggedIn = true;
                    ZephyrMusic.LOGGER.info("[Zephyr] Login cookie saved from header");
                }
            }
            if (loggedIn)
            {
                NeteaseUser u = NeteaseApi.parseUser(resp);
                if (u != null) currentUser = u;
            }
            return loggedIn;
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] handleLoginResponse failed", e);
            return false;
        }
    }

    /** 从 Set-Cookie 头提取 cookie 字段 */
    private String parseSetCookieHeader(String header)
    {
        // Set-Cookie: MUSIC_U=xxx; Path=/; ...
        if (header == null) return "";
        String[] parts = header.split(";");
        if (parts.length > 0)
        {
            String first = parts[0].trim();
            // 还需拼接 __csrf 等字段，简化处理：直接返回首段 + 提示
            return first;
        }
        return header;
    }

    /**
     * 获取用户歌单（带简单缓存）
     */
    public CompletableFuture<List<NeteasePlaylist>> fetchUserPlaylists()
    {
        if (currentUser == null)
        {
            return checkLoginStatus().thenCompose(ok -> {
                if (!ok || currentUser == null)
                {
                    return CompletableFuture.completedFuture(new ArrayList<>());
                }
                return fetchUserPlaylistsInternal();
            });
        }
        return fetchUserPlaylistsInternal();
    }

    private CompletableFuture<List<NeteasePlaylist>> fetchUserPlaylistsInternal()
    {
        if (!cachedPlaylists.isEmpty() && System.currentTimeMillis() - lastPlaylistFetchTime < 5 * 60 * 1000)
        {
            return CompletableFuture.completedFuture(cachedPlaylists);
        }
        return api.userPlaylist(currentUser.userId, 100).thenApply(resp -> {
            List<NeteasePlaylist> list = NeteaseApi.parsePlaylists(resp);
            cachedPlaylists = list;
            lastPlaylistFetchTime = System.currentTimeMillis();
            return list;
        });
    }

    /**
     * 退出登录
     */
    public CompletableFuture<Void> logout()
    {
        return api.logout().thenAccept(resp -> {
            ZephyrConfig.COOKIE.set("");
            loggedIn = false;
            currentUser = null;
            cachedPlaylists.clear();
            ZephyrMusic.LOGGER.info("[Zephyr] Logged out");
        });
    }

    /**
     * 刷新登录态（重新拉取用户信息）
     */
    public CompletableFuture<Boolean> refreshUser()
    {
        return checkLoginStatus();
    }
}
