package com.zephyr.music.api;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.config.ZephyrConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 网易云音乐会话状态：保存登录用户、歌单缓存
 *
 * ★ v0.1.1 登录态修复：
 * - 引入 LoginState 三态：UNKNOWN(未验证/网络异常) / LOGGED_IN / NOT_LOGGED_IN(无cookie)
 *   / INVALID(有cookie但服务端不认，过期或失效)
 * - 之前：网络超时或 cookie 失效都会把 loggedIn 置 false，而 UI 又把"有cookie"当作
 *   已登录，导致"我的"页面永远卡在"加载中..."。现在状态可区分，UI 能给出正确的
 *   重新登录入口。
 * - cookie 被服务端拒绝时先尝试 /login/refresh 自动续期（MUSIC_U 过期场景），
 *   续期成功则重新验证并保存新 cookie。
 */
public class NeteaseSession
{
    /** 登录状态 */
    public enum LoginState
    {
        /** 尚未验证（启动后未检查，或网络异常无法判断） */
        UNKNOWN,
        /** 正在验证 */
        VERIFYING,
        /** 已登录 */
        LOGGED_IN,
        /** 未登录（本地没有 cookie） */
        NOT_LOGGED_IN,
        /** 登录态失效：本地有 cookie 但服务端不认可（过期/被顶号），需重新登录 */
        INVALID
    }

    private static NeteaseSession instance;
    private final NeteaseApi api = new NeteaseApi();

    private NeteaseUser currentUser;
    private List<NeteasePlaylist> cachedPlaylists = new ArrayList<>();
    private long lastPlaylistFetchTime = 0;
    private boolean loggedIn = false;
    private LoginState state = LoginState.UNKNOWN;

    /** 进行中的登录验证（去重，避免多界面同时触发多次请求） */
    private CompletableFuture<Boolean> verifyFuture = null;

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

    public LoginState getLoginState()
    {
        return state;
    }

    public boolean isVerifying()
    {
        return state == LoginState.VERIFYING && verifyFuture != null && !verifyFuture.isDone();
    }

    public NeteaseUser getCurrentUser()
    {
        return currentUser;
    }

    /** 更新当前用户信息 */
    public void updateCurrentUser(NeteaseUser user)
    {
        if (user != null) this.currentUser = user;
    }

    public List<NeteasePlaylist> getCachedPlaylists()
    {
        return cachedPlaylists;
    }

    /**
     * 用已有 cookie 检查登录状态。
     * 返回 true 表示已登录（currentUser 可用）。
     * 结果可通过 getLoginState() 细分：
     *  - LOGGED_IN：已登录
     *  - INVALID：本地有 cookie 但服务端不认可（需要重新登录）
     *  - UNKNOWN：网络异常，无法判断（不要据此清除登录态）
     *  - NOT_LOGGED_IN：本地没有 cookie
     * 同一时刻的重复调用共享同一次验证。
     */
    public synchronized CompletableFuture<Boolean> checkLoginStatus()
    {
        if (!com.zephyr.music.config.LoginStore.hasCookie())
        {
            loggedIn = false;
            currentUser = null;
            state = LoginState.NOT_LOGGED_IN;
            return CompletableFuture.completedFuture(false);
        }
        if (isVerifying()) return verifyFuture;

        logCookieFieldNamesOnce();
        state = LoginState.VERIFYING;
        verifyFuture = doCheck(1);
        return verifyFuture;
    }

    /** 单次验证流程（含网络异常重试与 /login/refresh 自动续期） */
    private CompletableFuture<Boolean> doCheck(int attempt)
    {
        return api.loginStatus().thenCompose(resp -> {
            try
            {
                int code = resp.has("code") ? resp.get("code").getAsInt() : 0;
                JsonObject data = resp.has("data") && resp.get("data").isJsonObject()
                        ? resp.getAsJsonObject("data") : resp;
                int innerCode = data.has("code") ? data.get("code").getAsInt() : 0;

                // 网络异常（NeteaseHttpClient 出错时返回 code=-1/-2 的占位对象）
                if (code == -1 || code == -2 || innerCode == -1 || innerCode == -2)
                {
                    ZephyrMusic.LOGGER.warn("[Zephyr] checkLoginStatus: network error (attempt {})", attempt);
                    if (attempt < 2) return doCheck(attempt + 1);
                    // 网络不通时不判定为未登录，保持 UNKNOWN
                    loggedIn = false;
                    state = LoginState.UNKNOWN;
                    return CompletableFuture.completedFuture(false);
                }

                NeteaseUser u = NeteaseApi.parseUser(resp);
                if (u != null && u.userId != 0)
                {
                    currentUser = u;
                    loggedIn = true;
                    state = LoginState.LOGGED_IN;
                    ZephyrMusic.LOGGER.info("[Zephyr] Logged in as {} ({})", u.nickname, u.userId);
                    // 如果昵称是 token 格式，获取真实昵称
                    if (u.nickname != null && u.nickname.contains("_") && u.nickname.length() > 20)
                    {
                        api.userDetail(u.userId).thenAccept(detail -> {
                            NeteaseUser detailed = NeteaseApi.parseUserDetail(detail, u);
                            if (detailed != null && detailed.nickname != null && !detailed.nickname.contains("_"))
                            { currentUser = detailed; ZephyrMusic.LOGGER.info("[Zephyr] Updated nickname: {}", detailed.nickname); }
                        });
                    }
                    return CompletableFuture.completedFuture(true);
                }

                // ★ account/profile 均为 null：服务端不认可此 cookie（可能已过期）
                ZephyrMusic.LOGGER.info("[Zephyr] loginStatus rejected cookie (code={}), trying /login/refresh...", innerCode);
                return api.loginRefresh().thenCompose(rf -> {
                    int rfCode = rf.has("code") ? rf.get("code").getAsInt() : 0;
                    String newCookie = NeteaseApi.extractCookie(rf);
                    if (rfCode == 200 && newCookie != null && !newCookie.isEmpty())
                    {
                        ZephyrMusic.LOGGER.info("[Zephyr] /login/refresh succeeded, cookie renewed, re-checking...");
                        com.zephyr.music.config.LoginStore.setCookie(newCookie.trim());
                        if (attempt < 3) return doCheck(attempt + 1);
                    }
                    ZephyrMusic.LOGGER.warn("[Zephyr] checkLoginStatus: cookie invalid (refresh code={})", rfCode);
                    loggedIn = false;
                    currentUser = null;
                    state = LoginState.INVALID;
                    return CompletableFuture.completedFuture(false);
                });
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] checkLoginStatus failed", e);
                loggedIn = false;
                state = attempt < 2 ? state : LoginState.UNKNOWN;
                return CompletableFuture.completedFuture(false);
            }
        });
    }

    /** 只打印一次 cookie 字段名（不含值），便于远程诊断登录态问题 */
    private static boolean cookieNamesLogged = false;
    private static void logCookieFieldNamesOnce()
    {
        if (cookieNamesLogged) return;
        cookieNamesLogged = true;
        try
        {
            String c = com.zephyr.music.config.LoginStore.getCookie();
            StringBuilder names = new StringBuilder();
            for (String seg : c.split(";"))
            {
                String seg0 = seg.trim();
                int eq = seg0.indexOf('=');
                if (eq > 0)
                {
                    if (names.length() > 0) names.append(", ");
                    names.append(seg0, 0, eq);
                }
            }
            boolean hasMusicU = c.contains("MUSIC_U=");
            ZephyrMusic.LOGGER.info("[Zephyr] cookie fields (names only): [{}], contains MUSIC_U: {}",
                    names, hasMusicU);
        }
        catch (Exception ignored) {}
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
                com.zephyr.music.config.LoginStore.setCookie(cookie);
                loggedIn = true;
                state = LoginState.LOGGED_IN;
                ZephyrMusic.LOGGER.info("[Zephyr] Login cookie saved");
            }
            else if (resp.has("_setCookie") && !resp.get("_setCookie").isJsonNull())
            {
                // 从响应头提取的 Set-Cookie
                String sc = resp.get("_setCookie").getAsString();
                if (sc != null && !sc.isEmpty())
                {
                    // 简化处理：只保留第一段，并尝试拼接完整
                    com.zephyr.music.config.LoginStore.setCookie(parseSetCookieHeader(sc));
                    loggedIn = true;
                    state = LoginState.LOGGED_IN;
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

    /** 清除本地会话状态（cookie 的清理由调用方决定） */
    public void resetLocalState()
    {
        loggedIn = false;
        currentUser = null;
        cachedPlaylists.clear();
        lastPlaylistFetchTime = 0;
        state = com.zephyr.music.config.LoginStore.hasCookie()
                ? LoginState.UNKNOWN : LoginState.NOT_LOGGED_IN;
    }

    /**
     * 退出登录
     */
    public CompletableFuture<Void> logout()
    {
        return api.logout().thenAccept(resp -> {
            com.zephyr.music.config.LoginStore.clear();
            resetLocalState();
            state = LoginState.NOT_LOGGED_IN;
            ZephyrMusic.LOGGER.info("[Zephyr] Logged out");
        });
    }

    /**
     * 刷新登录态（重新拉取用户信息）
     */
    public CompletableFuture<Boolean> refreshUser()
    {
        resetLocalState();
        return checkLoginStatus();
    }
}
