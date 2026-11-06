package com.zephyr.music.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.*;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.glass.BlurManager;
import com.zephyr.music.client.glass.GlassRenderer;
import com.zephyr.music.client.hud.MusicHudOverlay;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 全集成播放器界面 —— 1:1 移植 Sorihiko/DangoMusic 的液态玻璃播放器（GPL-3.0，设计参考 common/ui）
 *
 * 与 DangoMusic 完全对齐的实现：
 *   逻辑分辨率 1280×800 统一缩放（Ui.scale）· 窗口边距 26/20 · 圆角 30 玻璃窗体
 *   侧栏 224（搜索框 32 高 r10 + 分组导航 30 行高 + 果冻选中块 220/380ms + 我的歌单 + 设置胶囊 + 账号栏 46）
 *   底部播放栏 74（封面 48 · 播放键 34 · 进度条 4 高红填充 + 悬停圆钮 · 音量 110×4 + 喇叭图标）
 *   网格页（间距 22 · 160 基准列 · 悬停外圈 + 右下角红色播放钮）
 *   歌曲列表页（表头 #/TITLE/TIME · 44 行高 · 玻璃选中行 · 右侧滚动条 + 拖动计数气泡 · 定位闪烁）
 *   封面墙（中心 240 · 两侧 0.62/0.30 偏移 · 0.15 递减缩放 · 边缘 smoothstep 淡出）
 *   入场动画（标题右滑 12px/320ms · 条目上升 18px/380ms · 逐个错开 35ms）
 *   弹簧动画（Spring k/d，1/240s 步进）· 搜索框红晕聚焦 · 播放/暂停图标形变
 */
public class PlayerScreen extends Screen
{
    public enum Tab { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, LYRICS, SETTINGS, ACCOUNT }

    // ==================== 主题（DangoMusic Theme.kt / Ui.kt 1:1） ====================
    /** 网易云红（DangoMusic RED #FF375F） */
    private static final int RED = 0xFFFF375F;
    private static final int TEXT_PRIMARY = 0xF5FFFFFF;
    private static final int TEXT_SECONDARY = 0x9EEBEBF5;
    private static final int TEXT_TERTIARY = 0x61EBEBF5;
    private static final int CARD = 0x0EFFFFFF;
    private static final int CARD_HOVER = 0x16FFFFFF;
    private static final int HAIRLINE = 0x14FFFFFF;
    private static final int SEPARATOR = 0x12FFFFFF;
    private static final int TRACK_C = 0x4D787880;
    private static final int GREEN = 0xFF30D158;
    private static final int GLASS_SEL = 0x21FFFFFF;
    /** GLASS_WINDOW tint：rgba(0.10,0.10,0.13,0.18) */
    private static final int WINDOW_TINT = 0x2E1A1A21;
    private static final int SIDEBAR_TINT = 0x0DFFFFFF;

    // ==================== 逻辑布局（DangoMusic drawWindow） ====================
    private static final float WIN_MX = 26f, WIN_MY = 20f, PAD = 10f;
    private static final float SIDE_W = 224f, BAR_H = 74f;
    private static final float ROW_H = 44f, GRID_GAP = 22f;

    // ==================== 基础类型 ====================
    static final class Rect
    {
        float x, y, w, h;
        Rect() {}
        Rect(float x, float y, float w, float h) { set(x, y, w, h); }
        void set(float x, float y, float w, float h) { this.x = x; this.y = y; this.w = w; this.h = h; }
        void set(Rect o) { set(o.x, o.y, o.w, o.h); }
        boolean isEmpty() { return w <= 0f || h <= 0f; }
        float right() { return x + w; }
        float bottom() { return y + h; }
        boolean contains(float px, float py) { return px >= x && px < x + w && py >= y && py < y + h; }
        Rect expand(float ex, float ey) { return new Rect(x - ex, y - ey, w + ex * 2, h + ey * 2); }
        Rect scaled(float k) { return new Rect(x + w * (1 - k) / 2f, y + h * (1 - k) / 2f, w * k, h * k); }
        Rect intersect(Rect o)
        {
            float x1 = Math.max(x, o.x), y1 = Math.max(y, o.y);
            float x2 = Math.min(right(), o.right()), y2 = Math.min(bottom(), o.bottom());
            return new Rect(x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
        }
    }

    /** 简单弹簧（DangoMusic Spring.kt 1:1：v += ((t-x)*k - v*d)*h，1/240s 步进） */
    static final class Spring
    {
        float x, v;
        private final float k, d;
        private long last = 0L;
        Spring(float k, float d) { this.k = k; this.d = d; }
        Spring(float k, float d, float init) { this.k = k; this.d = d; this.x = init; }
        float tick(float target)
        {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            if (last == 0L || dt > 0.25f) { x = target; v = 0f; }
            else step(target, dt);
            last = now;
            return x;
        }
        void step(float target, float dt)
        {
            while (dt > 0f)
            {
                float h = Math.min(dt, 1f / 240f);
                v += ((target - x) * k - v * d) * h;
                x += v * h;
                dt -= h;
            }
            if (Math.abs(target - x) < 0.0005f && Math.abs(v) < 0.001f) { x = target; v = 0f; }
        }
    }

    /** 玻璃输入框（替代原版 EditBox，DangoMusic 搜索框/筛选框同款） */
    static class Field
    {
        String text = "";
        boolean focused = false;
        final String hint;
        final int maxLen;
        final boolean password;
        final Rect rect = new Rect();
        final Spring focus = new Spring(260f, 17f);
        final Spring press = new Spring(420f, 13f);
        Field(String hint, int maxLen, boolean password) { this.hint = hint; this.maxLen = maxLen; this.password = password; }
        void append(String s) { text = (text + s.replace("\r", " ").replace("\n", " ")); if (text.length() > maxLen) text = text.substring(0, maxLen); }
        void backspace() { if (!text.isEmpty()) text = text.substring(0, text.length() - 1); }
        void clear() { text = ""; }
    }

    // ==================== 帧状态（DangoMusic Ui） ====================
    private float dScale = 1f;
    private float lmxRaw, lmyRaw;
    private float uiAlpha = 1f;
    private long openedAt = 0L;
    private final List<Object[]> hits = new ArrayList<>(); // {Rect, Runnable}

    // ==================== 侧栏状态 ====================
    private final Field searchField = new Field("搜索歌曲...", 80, false);
    private int sbScroll = 0;
    private float sbScrollPos = 0f;
    private Rect sbListArea = new Rect();
    private final Spring settingsOn = new Spring(240f, 24f);
    private final Spring settingsPress = new Spring(420f, 13f);
    // 果冻选中块（220ms / 380ms 双边）
    private String pillKey = "";
    private float pillTop = 0f, pillBot = 0f, pillFromTop = 0f, pillFromBot = 0f, pillAlpha = 0f;
    private long pillAt = 0L;
    private boolean pillDown = false;
    private String selKey = "";
    private Rect selTarget = null;

    // ==================== 页面状态 ====================
    private Tab currentTab = Tab.HOME;
    private Tab backTab = Tab.HOME;
    private long pageAt = 0L;
    private double scroll = 0;
    private float listPos = 0f;
    private int listRows = 10;
    private String statusLine = "";

    // 首页 / 排行榜
    private final List<NeteasePlaylist> recommendPls = new ArrayList<>();
    private boolean recommendLoading = false;
    private final List<NeteasePlaylist> chartPls = new ArrayList<>();
    private boolean chartsLoading = false;
    private Rect dailyBtnRect = null;
    private float gridTarget = 0f, gridPos = 0f, gridMax = 0f;

    // 搜索
    private final List<NeteaseSong> searchResults = new ArrayList<>();
    private boolean searching = false;
    private String searchError = "";
    private String lastQuery = "";

    // 歌单
    private final List<NeteasePlaylist> playlists = new ArrayList<>();
    private final List<NeteaseSong> playlistSongs = new ArrayList<>();
    private boolean playlistLoading = false;
    private NeteasePlaylist openedPlaylist = null;
    private String filterCacheKey = "";
    private List<NeteaseSong> filterCache = new ArrayList<>();
    private final Field filterField = new Field("筛选本列表...", 40, false);
    private long flashId = -1;
    private long flashAt = 0L;
    private boolean coverView = false;
    private float cfPos = 0f;
    private int cfTarget = 0;
    private final Spring viewPill = new Spring(300f, 23f);
    private final Spring viewFade = new Spring(210f, 24f, 1f);
    private boolean lastCoverState = false;
    private Boolean viewFrom = null;
    private Spring loadBar = new Spring(160f, 24f);
    private long loadKey = -1L;
    private Rect sbTrackRect = new Rect();
    private int sbTotal = 0;
    private boolean sbDragging = false;
    private float sbGrab = 0f;

    // 播放栏动画
    private static class Anim
    {
        final Spring hover = new Spring(300f, 26f);
        final Spring press = new Spring(420f, 13f);
        final Spring on = new Spring(240f, 24f);
    }
    private final Anim prevA = new Anim(), playA = new Anim(), nextA = new Anim(),
            queueA = new Anim(), shuffleA = new Anim(), repeatA = new Anim(), likeA = new Anim();
    private final Spring playMorph = new Spring(320f, 26f);
    private final Spring progKnob = new Spring(300f, 26f);
    private final Rect progRect = new Rect();
    private final Rect volRect = new Rect();
    private boolean progDrag = false, volDrag = false;

    // 账号 / 登录
    private NeteaseUser userDetail = null;
    private boolean loginVerifyStarted = false;
    private int loginMode = 0; // 0=选择, 1=扫码, 2=手机, 3=邮箱
    private String loginStatus = "";
    private int loginStatusColor = TEXT_SECONDARY;
    private final Field phoneField = new Field("手机号", 20, false);
    private final Field countryCodeField = new Field("86", 5, false);
    private final Field captchaField = new Field("验证码", 8, false);
    private final Field emailField = new Field("邮箱", 64, false);
    private final Field emailPwdField = new Field("密码", 64, true);
    private final List<Field> loginFields = new ArrayList<>();
    private String qrKey = null;
    private java.util.Timer qrTimer = null;
    private java.awt.image.BufferedImage qrImage = null;
    private ResourceLocation qrTexture = null;
    private Rect errBtnVerify = null, errBtnRelogin = null, qrBackRect = null;

    // 设置开关弹簧
    private final Map<String, Spring> switchAnims = new HashMap<>();
    private Rect settingsSliderHit = new Rect();

    public PlayerScreen() { super(Component.literal("Zephyr Music")); }

    public void setCurrentTab(Tab tab) { this.currentTab = tab; }

    @Override
    protected void init()
    {
        if (openedAt == 0L) openedAt = System.currentTimeMillis();
        BlurManager.requestBlur();
        if (pageAt == 0L) pageAt = openedAt;
        if (loginFields.isEmpty())
        {
            loginFields.add(countryCodeField);
            loginFields.add(phoneField);
            loginFields.add(captchaField);
            loginFields.add(emailField);
            loginFields.add(emailPwdField);
        }
        fetchPlaylists();
        switch (currentTab)
        {
            case HOME -> loadRecommend();
            case CHARTS -> loadCharts();
            case PLAYLIST -> { if (openedPlaylist != null && playlistSongs.isEmpty() && !playlistLoading) loadPlaylistSongs(); }
            default -> {}
        }
        if (currentTab == Tab.ACCOUNT && userDetail == null)
        {
            NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
            if (base != null && base.userId != 0)
                NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                    NeteaseUser ud = NeteaseApi.parseUserDetail(resp, base);
                    if (ud != null) NeteaseSession.getInstance().updateCurrentUser(ud);
                    userDetail = ud;
                });
            else if (com.zephyr.music.config.LoginStore.hasCookie()) ensureLoginVerified();
            else userDetail = base;
        }
        if (NeteaseSession.getInstance().isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie())
            NeteaseSession.getInstance().ensureLikeList();
    }

    @Override
    public void removed()
    {
        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
    }

    @Override
    public void tick()
    {
        // 封面墙平滑追目标（approach 90ms）
        float target = cfTarget;
        cfPos += (target - cfPos) * (1f - (float) Math.exp(-frameDt / 90f));
        if (Math.abs(target - cfPos) < 0.001f) cfPos = target;
        // 网格平滑滚动（approach 70ms）
        gridPos += (gridTarget - gridPos) * (1f - (float) Math.exp(-frameDt / 70f));
        if (!sbDragging)
        {
            float t2 = (float) scroll;
            if (Math.abs(t2 - listPos) > listRows * 3f) listPos = t2;
            else listPos += (t2 - listPos) * (1f - (float) Math.exp(-frameDt / 70f));
        }
    }

    private long lastFrameNs = 0L;
    private float frameDt = 16f;
    private void updateFrameDt()
    {
        long now = System.nanoTime();
        if (lastFrameNs == 0L) lastFrameNs = now;
        float dt = (now - lastFrameNs) / 1e6f;
        lastFrameNs = now;
        frameDt = Math.max(1f, Math.min(60f, dt));
    }

    // ==================== 登录态（沿用既有修复：五态验证 + 自动续期） ====================

    private void ensureLoginVerified()
    {
        if (loginVerifyStarted) return;
        loginVerifyStarted = true;
        NeteaseSession s = NeteaseSession.getInstance();
        s.checkLoginStatus().whenComplete((ok, ex) -> Minecraft.getInstance().execute(() -> {
            userDetail = ok && s.getCurrentUser() != null ? s.getCurrentUser() : null;
            errBtnVerify = null;
            errBtnRelogin = null;
        }));
    }

    private void relogin()
    {
        com.zephyr.music.config.LoginStore.clear();
        NeteaseSession.getInstance().resetLocalState();
        userDetail = null;
        loginVerifyStarted = false;
        loginMode = 0;
        errBtnVerify = null;
        errBtnRelogin = null;
        playlists.clear();
    }

    // ==================== 导航 ====================

    private void switchTab(Tab tab)
    {
        if (currentTab == tab && tab != Tab.PLAYLIST) return;
        if (currentTab != Tab.SETTINGS) backTab = currentTab;
        currentTab = tab;
        scroll = 0;
        listPos = 0;
        pageAt = System.currentTimeMillis();
        if (tab != Tab.PLAYLIST && tab != Tab.SEARCH && tab != Tab.QUEUE) coverView = false;
        cfTarget = 0;
        cfPos = 0;
        gridTarget = 0;
        gridPos = 0;
        filterField.clear();
        filterField.focused = false;
        blurFields();
        searchField.focused = false;
        statusLine = "";
        switch (tab)
        {
            case HOME -> loadRecommend();
            case CHARTS -> loadCharts();
            case PLAYLIST -> { if (openedPlaylist != null && playlistSongs.isEmpty() && !playlistLoading) loadPlaylistSongs(); }
            default -> {}
        }
    }

    /** 歌单详情 → 上一页（Backspace / 返回圆钮） */
    private void goBackPage()
    {
        if (currentTab == Tab.PLAYLIST && openedPlaylist != null)
        {
            openedPlaylist = null;
            playlistSongs.clear();
            filterField.clear();
            scroll = 0;
            pageAt = System.currentTimeMillis();
        }
        else if (currentTab == Tab.SEARCH || currentTab == Tab.QUEUE)
        {
            switchTab(backTab == currentTab ? Tab.HOME : backTab);
        }
    }

    // ==================== 数据加载 ====================

    private void loadRecommend()
    {
        if (recommendLoading || !recommendPls.isEmpty()) return;
        recommendLoading = true;
        NeteaseSession.getInstance().getApi().personalized(30).thenAccept(resp -> {
            List<NeteasePlaylist> l = NeteaseApi.parsePlaylists(resp);
            Minecraft.getInstance().execute(() -> {
                recommendPls.clear();
                recommendPls.addAll(l);
                recommendLoading = false;
            });
        }).exceptionally(e -> { Minecraft.getInstance().execute(() -> recommendLoading = false); return null; });
    }

    private void loadCharts()
    {
        if (chartsLoading || !chartPls.isEmpty()) return;
        chartsLoading = true;
        NeteaseSession.getInstance().getApi().toplist().thenAccept(resp -> {
            List<NeteasePlaylist> l = NeteaseApi.parsePlaylists(resp);
            Minecraft.getInstance().execute(() -> {
                chartPls.clear();
                chartPls.addAll(l);
                chartsLoading = false;
            });
        }).exceptionally(e -> { Minecraft.getInstance().execute(() -> chartsLoading = false); return null; });
    }

    private void fetchPlaylists()
    {
        NeteaseSession s = NeteaseSession.getInstance();
        if (s.isLoggedIn())
            s.fetchUserPlaylists().thenAccept(list -> Minecraft.getInstance().execute(() -> {
                playlists.clear();
                playlists.addAll(list);
            }));
        else if (com.zephyr.music.config.LoginStore.hasCookie())
        {
            ensureLoginVerified();
            s.fetchUserPlaylists().thenAccept(list -> Minecraft.getInstance().execute(() -> {
                playlists.clear();
                playlists.addAll(list);
            }));
        }
    }

    /** 打开歌单详情（id=-1 表示每日推荐） */
    private void openPlaylist(NeteasePlaylist pl)
    {
        if (openedPlaylist == null || openedPlaylist.id != pl.id) { filterField.clear(); filterCacheKey = ""; filterCache.clear(); }
        openedPlaylist = pl;
        currentTab = Tab.PLAYLIST;
        backTab = Tab.HOME;
        playlistSongs.clear();
        scroll = 0;
        listPos = 0;
        pageAt = System.currentTimeMillis();
        cfTarget = 0;
        cfPos = 0;
        coverView = false;
        loadPlaylistSongs();
    }

    private void loadPlaylistSongs()
    {
        if (openedPlaylist == null || playlistLoading) return;
        playlistLoading = true;
        loadKey = openedPlaylist.id;
        loadBar = new Spring(160f, 24f);
        if (openedPlaylist.id == -1)
        {
            NeteaseSession.getInstance().getApi().recommendSongs().thenAccept(resp -> {
                List<NeteaseSong> songs = NeteaseApi.parseDailySongs(resp);
                Minecraft.getInstance().execute(() -> {
                    playlistSongs.clear();
                    playlistSongs.addAll(songs);
                    playlistLoading = false;
                    scroll = 0;
                });
            }).exceptionally(e -> { Minecraft.getInstance().execute(() -> playlistLoading = false); return null; });
            return;
        }
        NeteaseSession.getInstance().getApi().playlistTrackAll(openedPlaylist.id, 300, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            if (songs.isEmpty() && resp.has("playlist"))
            {
                JsonObject p = resp.getAsJsonObject("playlist");
                if (p.has("tracks")) songs = NeteaseApi.parseSongs(p, "tracks");
            }
            final List<NeteaseSong> fs = songs;
            Minecraft.getInstance().execute(() -> {
                playlistSongs.clear();
                playlistSongs.addAll(fs);
                playlistLoading = false;
                scroll = 0;
            });
        }).exceptionally(e -> { Minecraft.getInstance().execute(() -> playlistLoading = false); return null; });
    }

    private void openDaily()
    {
        NeteasePlaylist daily = new NeteasePlaylist();
        daily.id = -1;
        daily.name = "每日推荐";
        daily.subtitle = "根据你的听歌口味生成 · 30 首";
        openPlaylist(daily);
    }

    // ==================== 筛选 / 定位 ====================

    private List<NeteaseSong> shownSongs()
    {
        List<NeteaseSong> all = playlistSongs;
        String key = filterField.text.trim().toLowerCase();
        if (key.isEmpty()) return all;
        if (key.equals(filterCacheKey)) return filterCache;
        List<NeteaseSong> out = new ArrayList<>();
        for (NeteaseSong s : all)
            if (s.name.toLowerCase().contains(key) || s.getDisplayArtist().toLowerCase().contains(key)) out.add(s);
        filterCacheKey = key;
        filterCache = out;
        return out;
    }

    private void locateCurrent()
    {
        List<NeteaseSong> shown = shownSongs();
        NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
        if (cur == null || shown.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == cur.id) { idx = i; break; }
        if (idx < 0) return;
        scroll = Math.max(0, idx - listRows / 2);
        cfTarget = idx;
        flashId = cur.id;
        flashAt = System.currentTimeMillis();
    }

    private boolean currentInList()
    {
        NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
        if (cur == null) return false;
        List<NeteaseSong> all = currentTracks();
        if (all == null) return false;
        for (NeteaseSong s : all) if (s.id == cur.id) return true;
        return false;
    }

    // ==================== 搜索 ====================

    private void doSearch()
    {
        String kw = searchField.text.trim();
        if (kw.isEmpty()) return;
        searchField.focused = false;
        filterField.clear();
        if (currentTab != Tab.SEARCH) { backTab = currentTab; currentTab = Tab.SEARCH; }
        scroll = 0;
        listPos = 0;
        pageAt = System.currentTimeMillis();
        cfTarget = 0;
        cfPos = 0;
        coverView = false;
        lastQuery = kw;
        searching = true;
        searchError = "";
        searchResults.clear();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            Minecraft.getInstance().execute(() -> {
                searchResults.clear();
                searchResults.addAll(songs);
                searching = false;
                scroll = 0;
                if (code == -1) searchError = "网络连接失败，请稍后重试";
                else if (songs.isEmpty()) searchError = "无结果";
                else searchError = "";
            });
        }).exceptionally(e -> {
            Minecraft.getInstance().execute(() -> { searching = false; searchError = "网络连接失败，请稍后重试"; });
            return null;
        });
    }

    // ==================== 登录 ====================

    private void doLogin()
    {
        if (loginMode == 2)
        {
            String phone = phoneField.text.trim();
            String ct = countryCodeField.text.trim();
            String cap = captchaField.text.trim();
            if (phone.isEmpty()) { loginStatus = "请输入手机号"; loginStatusColor = 0xFFFF453A; return; }
            loginStatus = "登录中...";
            loginStatusColor = TEXT_SECONDARY;
            NeteaseSession.getInstance().getApi().loginCellphone(phone, "", cap, ct).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = 0xFFFF453A; }
                });
            });
        }
        else if (loginMode == 3)
        {
            String email = emailField.text.trim();
            String pwd = emailPwdField.text;
            if (email.isEmpty() || pwd.isEmpty()) { loginStatus = "请输入邮箱和密码"; loginStatusColor = 0xFFFF453A; return; }
            loginStatus = "登录中...";
            loginStatusColor = TEXT_SECONDARY;
            NeteaseSession.getInstance().getApi().loginEmail(email, pwd).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = 0xFFFF453A; }
                });
            });
        }
    }

    private void startQrLogin()
    {
        loginStatus = "生成二维码...";
        loginStatusColor = TEXT_SECONDARY;
        qrImage = null;
        qrTexture = null;
        qrKey = null;
        NeteaseSession.getInstance().getApi().qrKey().thenCompose(resp -> {
            String key = null;
            if (resp.has("data") && resp.getAsJsonObject("data").has("unikey") && !resp.getAsJsonObject("data").get("unikey").isJsonNull())
                key = resp.getAsJsonObject("data").get("unikey").getAsString();
            else if (resp.has("unikey") && !resp.get("unikey").isJsonNull())
                key = resp.get("unikey").getAsString();
            if (key == null || key.isEmpty())
            { loginStatus = "获取二维码key失败"; loginStatusColor = 0xFFFF453A; return CompletableFuture.completedFuture(new JsonObject()); }
            qrKey = key;
            return NeteaseSession.getInstance().getApi().qrCreate(key);
        }).thenAccept(resp -> {
            String qrimg = "";
            if (resp.has("data") && resp.getAsJsonObject("data").has("qrimg") && !resp.getAsJsonObject("data").get("qrimg").isJsonNull())
                qrimg = resp.getAsJsonObject("data").get("qrimg").getAsString();
            else if (resp.has("qrimg") && !resp.get("qrimg").isJsonNull())
                qrimg = resp.get("qrimg").getAsString();
            if (qrimg.isEmpty()) { loginStatus = "API未返回二维码"; loginStatusColor = 0xFFFF453A; return; }
            if (qrimg.startsWith("data:image"))
            {
                int comma = qrimg.indexOf(",");
                if (comma > 0) qrimg = qrimg.substring(comma + 1);
            }
            try
            {
                byte[] bytes = Base64.getDecoder().decode(qrimg);
                final java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (img != null && img.getWidth() > 0)
                    Minecraft.getInstance().execute(() -> {
                        qrImage = img;
                        loginStatus = "请使用网易云App扫码";
                        loginStatusColor = 0xFF7DD3FC;
                        startQrPolling();
                    });
                else Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败"; loginStatusColor = 0xFFFF453A; });
            }
            catch (Exception e)
            {
                Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败"; loginStatusColor = 0xFFFF453A; });
            }
        }).exceptionally(e -> {
            Minecraft.getInstance().execute(() -> { loginStatus = "二维码生成失败"; loginStatusColor = 0xFFFF453A; });
            return null;
        });
    }

    private void startQrPolling()
    {
        if (qrTimer != null) qrTimer.cancel();
        qrTimer = new java.util.Timer("Zephyr-QR", true);
        qrTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            int count = 0;
            public void run()
            {
                if (qrKey == null || qrTimer == null) return;
                if (++count > 60) { loginStatus = "二维码过期"; loginStatusColor = 0xFFFF453A; qrTimer.cancel(); qrTimer = null; return; }
                NeteaseSession.getInstance().getApi().qrCheck(qrKey).thenAccept(resp -> {
                    int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                    if (code == 800) { loginStatus = "二维码过期"; loginStatusColor = 0xFFFF453A; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } }
                    else if (code == 801) { loginStatus = "等待扫码..."; loginStatusColor = TEXT_SECONDARY; }
                    else if (code == 802) { loginStatus = "待确认..."; loginStatusColor = 0xFFFFFFAA; }
                    else if (code == 803)
                    {
                        String cookie = NeteaseApi.extractCookie(resp);
                        if (cookie != null && !cookie.isEmpty())
                        {
                            com.zephyr.music.net.NeteaseHttpClient.getInstance().setCookie(cookie);
                            if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
                            NeteaseSession.getInstance().refreshUser();
                            Minecraft.getInstance().execute(() -> onLoginSuccess());
                        }
                    }
                });
            }
        }, 1000, 1500);
    }

    private void onLoginSuccess()
    {
        loginMode = 0;
        loginStatus = "";
        qrImage = null;
        qrTexture = null;
        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
        loginVerifyStarted = false;
        userDetail = null;
        NeteaseSession.getInstance().ensureLikeList();
        playlists.clear();
        NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
        if (base != null && base.userId != 0)
            NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                NeteaseUser ud = NeteaseApi.parseUserDetail(resp, base);
                if (ud != null) NeteaseSession.getInstance().updateCurrentUser(ud);
                userDetail = ud;
            });
        fetchPlaylists();
    }

    // ================================================================
    //  绘制基元（DangoMusic Ui / Gfx 等价实现，全部走逻辑坐标 + uiAlpha）
    // ================================================================

    /** uiAlpha 乘到颜色 alpha 上（pushOpacity 等价） */
    private int mulA(int argb)
    {
        int a = (int) (((argb >>> 24) & 0xFF) * uiAlpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private static int ri(float v) { return Math.round(v); }

    private void fill(Rect r, int argb, float radius)
    {
        if (r.w < 0.5f || r.h < 0.5f) return;
        if (GlassRenderer.shadersReady())
            GlassRenderer.drawSdfRoundRect(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), radius, mulA(argb));
        else
            com.zephyr.music.client.gui.ModernUI.fillRound(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), (int) radius, mulA(argb));
    }

    private void fillG(Rect r, int argb, float radius) { fill(r, argb, radius); }

    private void stroke(Rect r, int argb, float radius, float thickness)
    {
        if (r.w < 0.5f || r.h < 0.5f) return;
        GlassRenderer.drawSdfRoundRect(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), radius, 0, mulA(argb), 0f);
    }

    /** 毛玻璃面板（blurBehind 仅窗体使用） */
    private void glassP(Rect r, float radius, int tint, float sheen, boolean blurBehind)
    {
        if (r.w < 0.5f || r.h < 0.5f) return;
        GlassRenderer.glassPanel(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), radius, mulA(tint), sheen, blurBehind);
    }

    /** 文字：y 为顶部，size 为 Dango 逻辑字号（MC 字号 9 → 缩放 size/9） */
    private void text(String s, float x, float y, float size, int color) { text(s, x, y, size, color, false); }

    private void text(String s, float x, float y, float size, int color, boolean bold)
    {
        if (s == null || s.isEmpty() || uiAlpha < 0.01f) return;
        float k = size / 9f;
        gRef.pose().pushPose();
        gRef.pose().translate(x, y, 0);
        gRef.pose().scale(k, k, 1f);
        Component c = Component.literal(s);
        if (bold) c = c.copy().withStyle(ChatFormatting.BOLD);
        gRef.drawString(this.font, c, 0, 0, mulA(color), false);
        gRef.pose().popPose();
    }

    /** 水平居中文字 */
    private void textC(String s, float cx, float y, float size, int color, boolean bold)
    {
        text(s, cx - tw(s, size, bold) / 2f, y, size, color, bold);
    }

    private void textC(String s, float cx, float y, float size, int color) { textC(s, cx, y, size, color, false); }

    /** 逻辑字宽 */
    private float tw(String s, float size, boolean bold)
    {
        if (s == null || s.isEmpty()) return 0;
        float w = font.width(s) * (size / 9f);
        return bold ? w + s.length() * (size / 9f) : w;
    }

    /** 图标符号（按中心点绘制） */
    private void icon(String glyph, float cx, float cy, float size, int color)
    {
        float k = size / 9f;
        float w = font.width(glyph) * k;
        gRef.pose().pushPose();
        gRef.pose().translate(cx - w / 2f, cy - size / 2f, 0);
        gRef.pose().scale(k, k, 1f);
        gRef.drawString(this.font, Component.literal(glyph), 0, 0, mulA(color), false);
        gRef.pose().popPose();
    }

    /** 截断加省略号 */
    private String trim(String s, float maxW, float size) { return trim(s, maxW, size, false); }

    private String trim(String s, float maxW, float size, boolean bold)
    {
        if (s == null) return "";
        if (tw(s, size, bold) <= maxW) return s;
        while (s.length() > 1 && tw(s + "…", size, bold) > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** 换行成最多 2 行 */
    private List<String> wrap2(String s, float maxW, float size)
    {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        if (tw(s, size, false) <= maxW) { out.add(s); return out; }
        String cur = "";
        for (int i = 0; i < s.length(); i++)
        {
            String next = cur + s.charAt(i);
            if (tw(next, size, false) > maxW)
            {
                out.add(cur);
                if (out.size() == 2)
                {
                    String last = out.get(1);
                    while (!last.isEmpty() && tw(last + "…", size, false) > maxW) last = last.substring(0, last.length() - 1);
                    out.set(1, last + "…");
                    return out;
                }
                cur = String.valueOf(s.charAt(i));
            }
            else cur = next;
        }
        if (!cur.isEmpty() && out.size() < 2) out.add(cur);
        return out;
    }

    /** 圆角封面（GlassRenderer.drawSdfCover；未加载时画占位块） */
    private void cover(Rect r, String url, float radius)
    {
        if (r.w < 0.5f || r.h < 0.5f) return;
        ResourceLocation tex = (url == null || url.isEmpty()) ? null
                : CoverTextureManager.getInstance().getCover(url, null);
        if (tex != null)
            GlassRenderer.drawSdfCover(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), radius, tex);
        else fill(r, TRACK_C, radius);
    }

    /** 圆角封面（BufferedImage，扫码登录二维码） */
    private void coverImg(Rect r, java.awt.image.BufferedImage img, float radius)
    {
        if (qrTexture == null)
        {
            // BufferedImage → NativeImage（1.20.1 DynamicTexture 只接受 NativeImage）
            com.mojang.blaze3d.pipeline.NativeImage ni =
                    new com.mojang.blaze3d.pipeline.NativeImage(img.getWidth(), img.getHeight(), true);
            for (int py = 0; py < img.getHeight(); py++)
                for (int px2 = 0; px2 < img.getWidth(); px2++)
                {
                    int argb = img.getRGB(px2, py);
                    int a = (argb >>> 24) & 0xFF, rr2 = (argb >>> 16) & 0xFF, gg2 = (argb >>> 8) & 0xFF, bb2 = argb & 0xFF;
                    ni.setPixelRGBA(px2, py, (a << 24) | (bb2 << 16) | (gg2 << 8) | rr2);
                }
            DynamicTexture dt = new DynamicTexture(ni);
            qrTexture = Minecraft.getInstance().getTextureManager().register("zephyr_qr", dt);
        }
        GlassRenderer.drawSdfCover(gRef, ri(r.x), ri(r.y), ri(r.w), ri(r.h), radius, qrTexture);
    }

    private void hit(Rect r, Runnable action) { hits.add(new Object[]{r, action}); }
    private void hitFirst(Rect r, Runnable action) { hits.add(0, new Object[]{r, action}); }

    private float easeOut(float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private float pageProgress(float delayMs, float durMs)
    {
        return easeOut((System.currentTimeMillis() - pageAt - delayMs) / durMs);
    }

    /** 内容位移 + 透明度（DangoMusic shifted） */
    private void shifted(float dx, float dy, float alpha, Runnable block)
    {
        if (dx == 0f && dy == 0f && alpha >= 1f) { block.run(); return; }
        gRef.pose().pushPose();
        gRef.pose().translate(dx, dy, 0);
        float old = uiAlpha;
        uiAlpha = old * alpha;
        block.run();
        uiAlpha = old;
        gRef.pose().popPose();
    }

    private void headerAnim(Runnable block)
    {
        float p = pageProgress(0f, 320f);
        shifted((1 - p) * 12f, 0f, p, block);
    }

    private void itemAnim(int i, Runnable block)
    {
        float p = pageProgress(Math.min(i, 14) * 35f, 380f);
        shifted(0f, (1 - p) * 18f, p, block);
    }

    /** 进度条：fraction==null 时来回滑动（DangoMusic progressBar） */
    private void progressBar(Rect r, Float fraction)
    {
        fill(r, TRACK_C, r.h / 2f);
        if (fraction != null)
        {
            float w = r.w * Math.max(0f, Math.min(1f, fraction));
            if (w > 0.5f) fill(new Rect(r.x, r.y, Math.max(r.h, w), r.h), RED, r.h / 2f);
            return;
        }
        float t = (System.currentTimeMillis() % 1400L) / 1400f;
        float e = t < 0.5f ? 2f * t * t : 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f;
        float seg = r.w * 0.35f;
        float start = -seg + (r.w + seg) * e;
        float l = Math.max(0f, start), rr = Math.min(r.w, start + seg);
        if (rr - l > 0.5f) fill(new Rect(r.x + l, r.y, rr - l, r.h), RED, r.h / 2f);
    }

    /** iOS 开关（弹簧 + 液体拉伸，DangoMusic toggleSwitch） */
    private void toggleSwitch(Rect b, boolean on, String key, Runnable action)
    {
        Spring anim = switchAnims.computeIfAbsent(key, k2 -> new Spring(380f, 24f, on ? 1f : 0f));
        float p = anim.tick(on ? 1f : 0f);
        float pc = Math.max(0f, Math.min(1f, p));
        int off = hover(b) ? 0x3DFFFFFF : 0x29FFFFFF;
        int c = mixColor(off, 0xD9FF375F, pc);
        fill(b, c, b.h / 2f);
        float k = b.h - 4f;
        float stretch = Math.min(6f, Math.abs(anim.v) * 0.9f);
        float travel = b.w - 4f - k;
        float kx = b.x + 2f + travel * Math.max(-0.08f, Math.min(1.08f, p)) - (anim.v > 0f ? stretch : 0f);
        fill(new Rect(kx, b.y + 2f, k + stretch, k), 0xFFFFFFFF, k / 2f);
        hit(b.expand(4f, 4f), action);
    }

    private static int mixColor(int a, int b, float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
        int rr = (int) (((a >>> 16) & 0xFF) + (((b >>> 16) & 0xFF) - ((a >>> 16) & 0xFF)) * t);
        int gg = (int) (((a >>> 8) & 0xFF) + (((b >>> 8) & 0xFF) - ((a >>> 8) & 0xFF)) * t);
        int bb = (int) (((a) & 0xFF) + (((b) & 0xFF) - ((a) & 0xFF)) * t);
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    private boolean hover(Rect r) { return r.contains(lmxRaw, lmyRaw); }

    private String fmtTime(double sec)
    {
        if (sec < 0) sec = 0;
        int t = (int) sec;
        return String.format("%d:%02d", t / 60, t % 60);
    }

    // ================================================================
    //  主渲染（DangoMusic render / drawWindow）
    // ================================================================

    private GuiGraphics gRef;

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta)
    {
        gRef = g;
        updateFrameDt();
        BlurManager.runPending();

        dScale = Math.max(0.5f, Math.min(this.width / 1280f, this.height / 800f));
        float lw = this.width / dScale, lh = this.height / dScale;
        lmxRaw = mx / dScale;
        lmyRaw = my / dScale;

        // 背景：模糊世界 + 暗色渐变（毛玻璃底）
        if (BlurManager.hasBlur()) BlurManager.drawBlurredRegion(0, 0, this.width, this.height);
        g.fillGradient(0, 0, this.width, this.height, 0x70101013, 0x9E101013);

        g.pose().pushPose();
        g.pose().scale(dScale, dScale, 1f);

        float openA = easeOut((System.currentTimeMillis() - openedAt) / 180f);
        uiAlpha = openA;
        hits.clear();

        float winY = WIN_MY + (1 - openA) * 12f;
        Rect win = new Rect(WIN_MX, winY, lw - WIN_MX * 2, lh - winY - WIN_MY);
        glassP(win, 30f, WINDOW_TINT, 0.55f, true);

        Rect side = new Rect(win.x + PAD, win.y + PAD, SIDE_W, win.h - PAD * 2);
        float barX = side.right() + PAD;
        Rect bar = new Rect(barX, win.bottom() - PAD - BAR_H, win.right() - PAD - barX, BAR_H);
        Rect content = new Rect(side.right() + PAD + 8f, win.y + PAD + 14f,
                win.right() - PAD - 8f - (side.right() + PAD + 8f), bar.y - 10f - (win.y + PAD + 14f));

        drawSidebar(g, side);
        drawContent(g, content);
        drawPlayerBar(g, bar);

        uiAlpha = 1f;
        g.pose().popPose();
    }

    // ================================================================
    //  侧栏（DangoMusic Sidebar.kt 1:1）
    // ================================================================

    private void drawSidebar(GuiGraphics g, Rect side)
    {
        glassP(side, 22f, SIDEBAR_TINT, 0.9f, false);
        drawSelectionPill(side);

        float x = side.x + 12f;
        float iw = side.w - 24f;
        float y = side.y + 12f;

        // ---- 搜索框 ----
        Rect searchRect = new Rect(x, y, iw, 32f);
        searchField.rect.set(searchRect);
        float f = searchField.focus.tick(searchField.focused ? 1f : 0f);
        searchField.press.step(0f, frameDt / 1000f);
        float fc = Math.max(0f, Math.min(1f, f));
        float grow = f * 2f + searchField.press.x * 3f;
        Rect box = new Rect(searchRect.x - grow, searchRect.y - grow * 0.5f, searchRect.w + grow * 2f, 32f + grow);
        float br = 10f + grow * 0.5f;
        if (fc > 0.01f) fill(box.expand(3f * fc, 3f * fc), (int) (0x1F * fc) << 24 | 0xFF375F & 0xFFFFFF, br + 3f * fc);
        fill(box, mixColor(CARD, CARD_HOVER, fc), br);
        if (fc > 0.01f) stroke(box, (int) (0xFF * fc) << 24 | (RED & 0xFFFFFF), br, 1f + 0.4f * fc);
        int glassC = mixColor(TEXT_SECONDARY, RED, fc);
        float my2 = box.y + box.h / 2f;
        // 放大镜（圆 + 柄）
        stroke(new Rect(x + 11f, my2 - 6f, 9f, 9f), glassC, 4.5f, 1.4f);
        fill(new Rect(x + 18.5f, my2 + 2f, 4.5f, 1.6f), glassC, 0.8f);
        boolean caretOn = searchField.focused && (System.currentTimeMillis() / 500) % 2 == 0L;
        float maxW = searchRect.right() - 10f - (x + 30f);
        if (searchField.text.isEmpty())
        {
            float fade = Math.max(0f, Math.min(1f, 1f - fc * 1.3f));
            if (fade > 0.01f)
            {
                int a = (int) (0x61 * fade);
                text(searchField.hint, x + 30f + 12f * fc, my2 - 6f, 12f, (a << 24) | 0xEBEBF5);
            }
            if (caretOn) text("|", x + 30f, my2 - 6f, 12f, TEXT_PRIMARY);
        }
        else
        {
            String visible = searchField.text + (caretOn ? "|" : "");
            while (visible.length() > 1 && tw(visible, 12f, false) > maxW) visible = visible.substring(1);
            text(visible, x + 30f, my2 - 6f, 12f, TEXT_PRIMARY);
        }
        hit(searchRect, () -> { if (!searchField.focused) searchField.press.v -= 6f; searchField.focused = true; });
        y += 42f;

        // ---- 导航 ----
        y = sidebarSection(g, x, y, "ZEPHYR MUSIC");
        String[][] navs = {
                {"⌂", "首页"}, {"▤", "排行榜"}, {"♫", "正在播放"}, {"♪", "歌词"}
        };
        Tab[] tabs = {Tab.HOME, Tab.CHARTS, Tab.QUEUE, Tab.LYRICS};
        for (int i = 0; i < navs.length; i++)
        {
            Rect row = new Rect(side.x + 6f, y, side.w - 12f, 30f);
            boolean active = currentTab == tabs[i];
            if (active) { selKey = "nav:" + tabs[i]; selTarget = row; }
            else if (hover(row)) fill(row, CARD_HOVER, 9f);
            icon(navs[i][0], row.x + 18f, row.y + 15f, 12f, active ? RED : TEXT_SECONDARY);
            text(navs[i][1], row.x + 34f, row.y + 10f, 12f, active ? TEXT_PRIMARY : TEXT_SECONDARY, active);
            final Tab t = tabs[i];
            hit(row, () -> switchTab(t));
            y += 32f;
        }
        y += 4f;
        y = sidebarSection(g, x, y, "我的歌单");

        // ---- 我的歌单列表（平滑滚动） ----
        float rowH = 30f, footerH = 92f;
        sbListArea = new Rect(side.x + 6f, y, side.w - 12f, side.bottom() - footerH - y);
        int rows = Math.max(1, (int) (sbListArea.h / rowH));
        sbScroll = Math.max(0, Math.min(sbScroll, Math.max(0, playlists.size() - rows)));
        if (Math.abs(sbScroll - sbScrollPos) > rows * 3f) sbScrollPos = sbScroll;
        else sbScrollPos += (sbScroll - sbScrollPos) * (1f - (float) Math.exp(-frameDt / 70f));
        int first = (int) Math.floor(sbScrollPos);
        float frac = sbScrollPos - first;
        Rect clip = new Rect(sbListArea.x - 2f, sbListArea.y - 2f, sbListArea.w + 4f, sbListArea.h + 4f);
        scissorPush(clip);
        for (int r2 = 0; r2 <= rows; r2++)
        {
            int i = first + r2;
            if (i < 0 || i >= playlists.size()) continue;
            NeteasePlaylist p = playlists.get(i);
            Rect row = new Rect(sbListArea.x, sbListArea.y + (r2 - frac) * rowH, sbListArea.w, rowH - 2f);
            boolean active = currentTab == Tab.PLAYLIST && openedPlaylist != null && openedPlaylist.id == p.id;
            if (active) { selKey = "pl:" + p.id; selTarget = row; }
            else if (hover(row)) fill(row, CARD_HOVER, 9f);
            boolean liked = i == 0;
            icon(liked ? "♥" : "≡", row.x + 18f, row.y + 15f, 12f, liked || active ? RED : TEXT_SECONDARY);
            text(trim(p.name, row.right() - 8f - (row.x + 34f), 12f), row.x + 34f, row.y + 9f, 12f,
                    active ? TEXT_PRIMARY : TEXT_SECONDARY);
            final NeteasePlaylist fp = p;
            hit(row.intersect(clip), () -> openPlaylist(fp));
        }
        scissorPop();
        if (playlists.isEmpty())
        {
            NeteaseSession s = NeteaseSession.getInstance();
            String msg = s.getLoginState() == NeteaseSession.LoginState.VERIFYING ? "加载中..."
                    : (s.isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie()) ? "暂无歌单" : "登录后查看你的歌单";
            text(msg, x + 2f, sbListArea.y + 6f, 11f, TEXT_TERTIARY);
            if (s.getLoginState() == NeteaseSession.LoginState.VERIFYING)
                progressBar(new Rect(x + 2f, sbListArea.y + 24f, iw - 4f, 3f), null);
        }
        // ---- 设置胶囊 ----
        float settingsY = side.bottom() - footerH + 8f;
        Rect sbBtn = new Rect(x, settingsY, iw, 32f);
        float o = settingsOn.tick(currentTab == Tab.SETTINGS ? 1f : 0f);
        settingsPress.step(0f, frameDt / 1000f);
        float k = 1f + settingsPress.x * 0.12f;
        Rect b2 = sbBtn.scaled(k);
        int base = hover(sbBtn) ? CARD_HOVER : CARD;
        fill(b2, mixColor(base, 0x38FF375F, o), 10f * k);
        if (o > 0.01f) stroke(b2, (int) (0x99 * o) << 24 | (RED & 0xFFFFFF), 10f * k, 1f);
        int sCol = mixColor(TEXT_PRIMARY, RED, o);
        float sw = 14f * k + 6f * k + tw("设置", 12f * k, true);
        float sx = b2.x + (b2.w - sw) / 2f;
        float cy = b2.y + b2.h / 2f;
        icon("⚙", sx + 7f * k, cy, 14f * k, sCol);
        text("设置", sx + 20f * k, cy - 12f * k * 0.39f - 0.5f, 12f * k, sCol, true);
        hit(sbBtn, () -> { settingsPress.v -= 7f; switchTab(currentTab == Tab.SETTINGS ? backTab : Tab.SETTINGS); });

        // ---- 账号栏 ----
        drawAccountBar(g, side);
    }

    private float sidebarSection(GuiGraphics g, float x, float y, String label)
    {
        text(label, x + 2f, y, 10f, TEXT_TERTIARY, true);
        return y + 16f;
    }

    /** 果冻选中块：上下两条边分开动（220ms / 380ms，DangoMusic drawSelectionPill） */
    private void drawSelectionPill(Rect side)
    {
        Rect tgt = selTarget;
        if (tgt != null)
        {
            if (pillAlpha < 0.02f || pillKey.isEmpty())
            {
                pillTop = tgt.y; pillBot = tgt.bottom(); pillKey = selKey; pillAt = 0L;
            }
            else if (!selKey.equals(pillKey))
            {
                pillFromTop = pillTop; pillFromBot = pillBot;
                pillDown = tgt.y > pillTop;
                pillAt = System.currentTimeMillis();
                pillKey = selKey;
            }
            if (pillAt != 0L)
            {
                float e = (System.currentTimeMillis() - pillAt);
                float pt = e / (pillDown ? 380f : 220f);
                float pb = e / (pillDown ? 220f : 380f);
                pillTop = pillFromTop + (tgt.y - pillFromTop) * easeOut(pt);
                pillBot = pillFromBot + (tgt.bottom() - pillFromBot) * easeOut(pb);
                if (pt >= 1f && pb >= 1f) pillAt = 0L;
            }
            else { pillTop = tgt.y; pillBot = tgt.bottom(); }
            pillAlpha = Math.min(1f, pillAlpha + frameDt / 120f);
        }
        else
        {
            pillAlpha = Math.max(0f, pillAlpha - frameDt / 120f);
            if (pillAlpha <= 0f) pillKey = "";
        }
        selKey = ""; selTarget = null; // 每帧由绘制重新登记
        if (pillAlpha > 0.01f && pillBot - pillTop > 2f)
        {
            float old = uiAlpha;
            uiAlpha = old * pillAlpha;
            fillG(new Rect(side.x + 6f, pillTop, side.w - 12f, pillBot - pillTop), GLASS_SEL, 12f);
            uiAlpha = old;
        }
    }

    private void drawAccountBar(GuiGraphics g, Rect side)
    {
        Rect ub = new Rect(side.x + 6f, side.bottom() - 46f, side.w - 12f, 40f);
        Rect av = new Rect(ub.x + 6f, ub.y + 6f, 28f, 28f);
        NeteaseSession s = NeteaseSession.getInstance();
        boolean loggedIn = s.isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie();
        String nick = userDetail != null && !userDetail.nickname.isEmpty() ? userDetail.nickname
                : s.getCurrentUser() != null ? s.getCurrentUser().nickname : "";
        if (loggedIn && !nick.isEmpty())
        {
            cover(av, userDetail != null && !userDetail.avatarUrl.isEmpty() ? userDetail.avatarUrl
                    : s.getCurrentUser() != null ? s.getCurrentUser().avatarUrl : "", 14f);
            text(trim(nick, av.right() + 10f - ub.right() - 8f, 12f), av.right() + 10f, ub.y + 14f, 12f, TEXT_PRIMARY);
        }
        else
        {
            fill(av, TRACK_C, 14f);
            icon("?", av.x + 14f, av.y + 14f, 12f, TEXT_SECONDARY);
            text("登录", av.right() + 10f, ub.y + 14f, 12f, RED, true);
        }
        hit(ub, () -> switchTab(Tab.ACCOUNT));
    }

    // ================================================================
    //  内容区（DangoMusic drawContent）
    // ================================================================

    private Rect gridAreaRect = new Rect();
    private Rect tracksAreaRect = new Rect();
    private Rect settingsAreaRect = new Rect();
    private float settingsMax = 0f;
    private boolean sliderDrag = false;

    private void drawContent(GuiGraphics g, Rect area)
    {
        gridAreaRect = new Rect();
        tracksAreaRect = new Rect();
        settingsAreaRect = new Rect();
        Rect body = area;
        switch (currentTab)
        {
            case HOME ->
            {
                List<NeteasePlaylist> items = recommendPls;
                String label = "推荐歌单";
                if (items.isEmpty() && !recommendLoading) { items = playlists; label = "你的歌单"; }
                drawGridPage(g, body, "欢迎来到 Zephyr Music!", label + " · " + items.size(), items,
                        recommendLoading && items.isEmpty());
            }
            case CHARTS -> drawGridPage(g, body, "排行榜", chartPls.size() + " 个榜单", chartPls, chartsLoading);
            case PLAYLIST ->
            {
                if (openedPlaylist != null) drawTracksPage(g, body);
                else drawGridPage(g, body, "我的歌单", playlists.size() + " 个歌单", playlists, false);
            }
            case SEARCH, QUEUE -> drawTracksPage(g, body);
            case SETTINGS -> drawSettingsPage(g, body);
            case ACCOUNT -> drawAccountPage(g, body);
            case LYRICS -> drawLyricsPage(g, body);
        }
    }

    // ================================================================
    //  网格页（DangoMusic GridPage.kt 1:1）
    // ================================================================

    private void drawGridPage(GuiGraphics g, Rect area, String title, String sub,
                              List<NeteasePlaylist> items, boolean loading)
    {
        headerAnim(() -> {
            text(title, area.x, area.y, 22f, TEXT_PRIMARY, true);
            text(loading ? "加载中..." : sub, area.x, area.y + 30f, 11f, TEXT_SECONDARY, true);
            if (loading) progressBar(new Rect(area.x, area.y + 48f, 200f, 3f), null);
            if (currentTab == Tab.HOME && (NeteaseSession.getInstance().isLoggedIn()))
            {
                String dl = "每日推荐";
                float bw = tw(dl, 12f, false) + 34f;
                Rect db = new Rect(area.right() - bw, area.y + 2f, bw, 26f);
                fill(db, hover(db) ? CARD_HOVER : CARD, 13f);
                icon("◎", db.x + 14f, db.y + 13f, 11f, RED);
                text(dl, db.x + 25f, db.y + 8f, 11f, TEXT_PRIMARY);
                dailyBtnRect = db;
                hit(db, this::openDaily);
            }
            else dailyBtnRect = null;
        });
        Rect grid = new Rect(area.x, area.y + 54f, area.w, area.bottom() - (area.y + 54f));
        gridAreaRect = grid;
        drawPlaylistGrid(g, grid, items);
    }

    private void drawPlaylistGrid(GuiGraphics g, Rect area, List<NeteasePlaylist> items)
    {
        float gap = GRID_GAP;
        int cols = Math.max(2, (int) ((area.w + gap) / (160f + gap)));
        float cw = (area.w - gap * (cols - 1)) / cols;
        float rowH = cw + 46f;
        int totalRows = (items.size() + cols - 1) / cols;
        gridMax = Math.max(0f, totalRows * rowH - area.h);
        gridTarget = Math.max(0f, Math.min(gridTarget, gridMax));
        gridPos = Math.max(0f, Math.min(gridPos, gridMax));
        Rect clip = new Rect(area.x - 6f, area.y - 6f, area.w + 12f, area.h + 6f);
        scissorPush(clip);
        int firstRow = Math.max(0, (int) (gridPos / rowH));
        int lastRow = Math.min(totalRows - 1, (int) ((gridPos + area.h) / rowH));
        for (int r = firstRow; r <= lastRow; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                int i = r * cols + c;
                if (i >= items.size()) break;
                NeteasePlaylist p = items.get(i);
                float x = area.x + c * (cw + gap);
                float y = area.y + r * rowH - gridPos;
                final int idx = (r - firstRow) * cols + c;
                itemAnim(idx, () -> {
                    Rect coverR = new Rect(x, y, cw, cw);
                    Rect cell = new Rect(x, y, cw, rowH - 6f);
                    boolean hov = hover(cell) && clip.contains(lmxRaw, lmyRaw);
                    if (hov) fill(new Rect(x - 5f, y - 5f, cw + 10f, rowH + 2f), CARD_HOVER, 14f);
                    cover(coverR, p.coverImgUrl, 12f);
                    if (hov)
                    {
                        Rect pb = new Rect(coverR.right() - 40f, coverR.bottom() - 40f, 30f, 30f);
                        fill(pb, RED, 15f);
                        icon("▶", pb.x + 16f, pb.y + 15f, 12f, TEXT_PRIMARY);
                    }
                    List<String> lines = wrap2(p.name, cw - 4f, 12f);
                    for (int li = 0; li < lines.size(); li++)
                        text(lines.get(li), x + 2f, coverR.bottom() + 8f + li * 15f, 12f, TEXT_PRIMARY, true);
                    final NeteasePlaylist fp = p;
                    hit(cell.intersect(area), () -> openPlaylist(fp));
                });
            }
        }
        scissorPop();
    }

    // ================================================================
    //  歌曲列表页（DangoMusic TracksPage.kt 1:1：表头/列表/滚动条/封面墙/筛选/定位/切换）
    // ================================================================

    private String tracksTitle()
    {
        return switch (currentTab)
        {
            case SEARCH -> "\"" + lastQuery + "\"";
            case QUEUE -> "正在播放";
            default -> openedPlaylist != null ? openedPlaylist.name : "";
        };
    }

    private List<NeteaseSong> currentTracks()
    {
        return switch (currentTab)
        {
            case SEARCH -> searchResults;
            case QUEUE -> MusicPlayer.getInstance().getQueue();
            case PLAYLIST -> openedPlaylist != null ? playlistSongs : null;
            default -> null;
        };
    }

    private void playFrom(List<NeteaseSong> all, int index)
    {
        MusicPlayer mp = MusicPlayer.getInstance();
        if (currentTab == Tab.QUEUE) { mp.jumpTo(index); return; }
        mp.setQueue(new ArrayList<>(all), index);
        mp.setCurrentSourcePlaylistId(openedPlaylist != null ? openedPlaylist.id : 0);
        mp.playSong(all.get(index));
    }

    private void playAll(List<NeteaseSong> tracks)
    {
        MusicPlayer mp = MusicPlayer.getInstance();
        int start = mp.isShuffleMode() ? new Random().nextInt(tracks.size()) : 0;
        mp.setQueue(new ArrayList<>(tracks), start);
        mp.setCurrentSourcePlaylistId(openedPlaylist != null ? openedPlaylist.id : 0);
        mp.playSong(tracks.get(start));
    }

    private void drawTracksPage(GuiGraphics g, Rect area)
    {
        List<NeteaseSong> tracks = currentTracks();
        float[] headerH = {62f};
        headerAnim(() -> headerH[0] = drawTracksHeader(g, area, tracks));
        Rect body = new Rect(area.x, area.y + headerH[0], area.w, area.bottom() - (area.y + headerH[0]));
        tracksAreaRect = body;
        if (tracks == null) return;
        List<NeteaseSong> list = shownSongs2(tracks);
        if (list.isEmpty() && !filterField.text.isEmpty())
        {
            text("没有匹配 \"" + filterField.text + "\" 的歌曲", body.x + 12f, body.y + 20f, 12f, TEXT_TERTIARY);
            return;
        }
        drawTrackViews(g, body, tracks, list);
    }

    private List<NeteaseSong> shownSongs2(List<NeteaseSong> in)
    {
        if (currentTab != Tab.PLAYLIST || filterField.text.trim().isEmpty()) return in;
        return shownSongs();
    }

    private float drawTracksHeader(GuiGraphics g, Rect page, List<NeteaseSong> tracks)
    {
        float x = page.x;
        float y = page.y;
        float headerH = 62f;

        // 返回圆钮
        Rect bb = new Rect(x, y + 2f, 28f, 28f);
        fill(bb, hover(bb) ? CARD_HOVER : CARD, 14f);
        icon("◀", bb.x + 13.5f, bb.y + 14f, 10f, TEXT_PRIMARY);
        hit(bb, this::goBackPage);
        x += 40f;

        // 歌单封面
        if (currentTab == Tab.PLAYLIST && openedPlaylist != null)
        {
            Rect cv = new Rect(x, y, 96f, 96f);
            cover(cv, openedPlaylist.coverImgUrl, 12f);
            x = cv.right() + 18f;
            headerH = 112f;
        }

        float segW = 132f;
        text(trim(tracksTitle(), page.right() - segW - 24f - x, 22f, true), x, y + 4f, 22f, TEXT_PRIMARY, true);
        String sub;
        if (currentTab == Tab.SEARCH && searching) sub = "搜索中...";
        else if (currentTab == Tab.SEARCH && tracks != null && tracks.isEmpty()) sub = searchError.isEmpty() ? "无结果" : "搜索失败: " + searchError;
        else if (currentTab == Tab.QUEUE && (tracks == null || tracks.isEmpty())) sub = "队列里还没有歌曲";
        else if (tracks == null) sub = "加载中...";
        else if (!filterField.text.isEmpty()) sub = shownSongs2(tracks).size() + " / " + tracks.size() + " 首歌曲";
        else sub = tracks.size() + " 首歌曲";
        text(sub, x, y + 34f, 11f, TEXT_SECONDARY, true);
        if (currentTab == Tab.PLAYLIST && openedPlaylist != null && playlistLoading && tracks != null && tracks.isEmpty())
            progressBar(new Rect(x, y + 54f, 220f, 4f), null);

        // 全部播放（红色胶囊）
        if (tracks != null && !tracks.isEmpty() && currentTab != Tab.QUEUE)
        {
            String label = "全部播放";
            float pw = tw(label, 12f, true) + 44f;
            Rect pb = new Rect(x, y + (headerH > 100f ? 64f : 52f) - 6f, pw, 30f);
            fill(pb, hover(pb) ? 0xD9FF375F : RED, 15f);
            icon("▶", pb.x + 17f, pb.y + 15f, 11f, TEXT_PRIMARY);
            text(label, pb.x + 30f, pb.y + 9f, 12f, TEXT_PRIMARY, true);
            final List<NeteaseSong> all = tracks;
            hit(pb, () -> playAll(all));
            if (headerH < 100f) headerH = 92f;
        }

        // 列表 ↔ 封面墙
        Rect seg = new Rect(page.right() - segW, y + 4f, segW, 26f);
        fill(seg, CARD, 13f);
        Rect half = new Rect(seg.x, seg.y, segW / 2f, seg.h);
        Rect other = new Rect(seg.x + segW / 2f, seg.y, segW / 2f, seg.h);
        if (hover(coverView ? half : other)) fill(coverView ? half : other, CARD_HOVER, 13f);
        float pill = viewPill.tick(coverView ? 1f : 0f);
        float stretch = Math.min(14f, Math.abs(viewPill.v) * 5f);
        float px = seg.x + Math.max(-0.06f, Math.min(1.06f, pill)) * half.w - (viewPill.v > 0f ? stretch : 0f);
        fill(new Rect(px, seg.y, half.w + stretch, seg.h), RED, 13f);
        float on = Math.max(0f, Math.min(1f, pill));
        textC("列表", half.x + half.w / 2f, half.y + 8f, 12f, mixColor(TEXT_SECONDARY, TEXT_PRIMARY, 1 - on), false);
        textC("封面", other.x + other.w / 2f, other.y + 8f, 12f, mixColor(TEXT_SECONDARY, TEXT_PRIMARY, on), false);
        hit(half, () -> { if (coverView) toggleCoverView(); });
        hit(other, () -> { if (!coverView) toggleCoverView(); });

        // 定位
        float rightX = seg.x - 10f;
        if (currentInList())
        {
            String label = "定位";
            float lw = tw(label, 11f, false) + 34f;
            Rect lb = new Rect(rightX - lw, y + 4f, lw, 26f);
            fill(lb, hover(lb) ? CARD_HOVER : CARD, 13f);
            icon("◎", lb.x + 14f, lb.y + 13f, 11f, RED);
            text(label, lb.x + 25f, lb.y + 8f, 11f, TEXT_PRIMARY);
            hit(lb, this::locateCurrent);
        }
        return headerH;
    }

    private void toggleCoverView()
    {
        coverView = !coverView;
        if (coverView)
        {
            List<NeteaseSong> tracks = currentTracks();
            if (tracks != null)
            {
                NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
                int idx = 0;
                if (cur != null) for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id == cur.id) { idx = i; break; }
                cfTarget = idx;
                cfPos = idx;
            }
        }
    }

    private void drawTrackViews(GuiGraphics g, Rect area, List<NeteaseSong> tracks, List<NeteaseSong> list)
    {
        if (coverView != lastCoverState)
        {
            viewFrom = lastCoverState;
            lastCoverState = coverView;
            viewFade.x = 0f;
            viewFade.v = 0f;
        }
        float t = viewFade.tick(1f);
        Boolean from = viewFrom;
        if (from == null || t >= 0.999f)
        {
            viewFrom = null;
            if (coverView) itemAnim(0, () -> drawCoverflow(g, area, tracks, list));
            else drawTrackList(g, area, tracks, list);
            return;
        }
        float dir = coverView ? 1f : -1f;
        float out = Math.max(0f, Math.min(1f, 1f - t * 1.6f));
        if (out > 0.01f)
        {
            int keep = hits.size();
            Boolean ff = from;
            shifted(-dir * 36f * Math.min(1f, t), 0f, out, () -> { if (ff) drawTrackViewRaw(g, area, tracks, list, ff); });
            while (hits.size() > keep) hits.remove(hits.size() - 1);
        }
        float tt = Math.max(0f, Math.min(1f, t));
        shifted(dir * 36f * (1 - tt), 0f, tt, () -> drawTrackViewRaw(g, area, tracks, list, coverView));
    }

    private void drawTrackViewRaw(GuiGraphics g, Rect area, List<NeteaseSong> tracks, List<NeteaseSong> list, boolean cover)
    {
        if (cover) drawCoverflow(g, area, tracks, list);
        else drawTrackList(g, area, tracks, list);
    }

    // ---- 列表 + 滚动条 ----

    private Rect thumbOf(Rect track, int total, int rows)
    {
        float h = Math.max(28f, track.h * rows / Math.max(1, total));
        int maxScroll = Math.max(1, total - rows);
        float pos = sbDragging ? (float) scroll : listPos;
        float y = track.y + (track.h - h) * Math.max(0f, Math.min(1f, pos / maxScroll));
        return new Rect(track.x, y, track.w, h);
    }

    private void drawScrollbar(Rect track, int total, int rows)
    {
        sbTrackRect = track;
        sbTotal = total;
        if (total <= rows)
        {
            sbTrackRect = new Rect();
            return;
        }
        boolean hot = sbDragging || track.expand(8f, 0f).contains(lmxRaw, lmyRaw);
        fill(track, (hot ? 0x1A : 0x0D) << 24 | 0xFFFFFF, track.w / 2f);
        Rect th = thumbOf(track, total, rows);
        Rect thumb = hot ? new Rect(th.x - 1.5f, th.y, th.w + 3f, th.h) : th;
        int thumbC = sbDragging ? RED : (hot ? 0x8CFFFFFF : 0x4DFFFFFF);
        fill(thumb, thumbC, thumb.w / 2f);
        if (sbDragging)
        {
            String label = (Math.min(total, (int) scroll + 1)) + " / " + total;
            float lw = tw(label, 11f, true) + 18f;
            Rect lb = new Rect(track.x - lw - 10f, th.y + th.h / 2f - 12f, lw, 24f);
            fillG(lb, GLASS_SEL, 12f);
            text(label, lb.x + 9f, lb.y + 7f, 11f, TEXT_PRIMARY, true);
        }
    }

    private void scrollbarTo(float ly)
    {
        Rect track = sbTrackRect;
        if (sbTotal <= listRows || track.h <= 0f) return;
        Rect th = thumbOf(track, sbTotal, listRows);
        float frac = ((ly - sbGrab) - track.y) / Math.max(1f, track.h - th.h);
        scroll = Math.max(0, Math.min((int) (Math.max(0f, Math.min(1f, frac)) * (sbTotal - listRows)), sbTotal - listRows));
    }

    private void drawTrackList(GuiGraphics g, Rect area, List<NeteaseSong> all, List<NeteaseSong> tracks)
    {
        // 表头
        text("#", area.x + 12f, area.y, 10f, TEXT_TERTIARY, true);
        text("标题", area.x + 84f, area.y, 10f, TEXT_TERTIARY, true);
        float sbW = 16f;
        text("时长", area.right() - sbW - 12f - tw("时长", 10f, true), area.y, 10f, TEXT_TERTIARY, true);
        fill(new Rect(area.x, area.y + 16f, area.w - sbW, 1f), SEPARATOR, 0f);
        Rect body = new Rect(area.x, area.y + 22f, area.w - sbW, area.h - 22f);

        float rowH = ROW_H;
        int rows = Math.max(1, (int) (body.h / rowH));
        listRows = rows;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, tracks.size() - rows)));
        if (!sbDragging)
        {
            if (Math.abs(scroll - listPos) > rows * 3f) listPos = (float) scroll;
            else listPos += (scroll - listPos) * (1f - (float) Math.exp(-frameDt / 70f));
        }
        drawScrollbar(new Rect(area.right() - 9f, body.y + 2f, 5f, rows * rowH - 8f), tracks.size(), rows);
        float flash = Math.max(0f, 1f - (System.currentTimeMillis() - flashAt) / 1400f);
        NeteaseSong playing = MusicPlayer.getInstance().getCurrentSong();
        int first = (int) Math.floor(listPos);
        float frac = listPos - first;
        Rect clip = new Rect(body.x - 4f, body.y - 2f, body.w + 8f, rows * rowH);
        scissorPush(clip);
        for (int r = 0; r <= rows; r++)
        {
            int vi = first + r;
            if (vi < 0 || vi >= tracks.size()) continue;
            NeteaseSong t = tracks.get(vi);
            final int index = vi;
            final int rf = r;
            final float fr = frac;
            itemAnim(r, () -> {
                Rect row = new Rect(body.x, body.y + (rf - fr) * rowH, body.w, rowH - 4f);
                boolean isCur = playing != null && playing.id == t.id;
                if (isCur) fillG(row, GLASS_SEL, 10f);
                else if (hover(row)) fill(row, CARD_HOVER, 10f);
                if (flash > 0f && t.id == flashId)
                    stroke(row, (int) (flash * 255f) << 24 | (RED & 0xFFFFFF), 10f, 1.5f);
                if (isCur && !MusicPlayer.getInstance().isPaused())
                    icon("♫", row.x + 18f, row.y + 20f, 12f, RED);
                else
                    text(String.valueOf(index + 1), row.x + 12f, row.y + 15f, 11f, TEXT_TERTIARY);
                Rect thumb = new Rect(row.x + 46f, row.y + 4f, row.h - 8f, row.h - 8f);
                cover(thumb, t.picUrl, 6f);
                float tx = thumb.right() + 12f;
                float durW = 44f;
                float avail = row.right() - durW - 12f - tx;
                text(trim(t.name, avail, 13f), tx, row.y + 7f, 13f, isCur ? RED : TEXT_PRIMARY);
                text(trim(t.getDisplayArtist(), avail, 11f), tx, row.y + 23f, 11f, TEXT_SECONDARY);
                String d = fmtTime(t.duration / 1000.0);
                text(d, row.right() - 12f - tw(d, 11f, false), row.y + 15f, 11f, TEXT_TERTIARY);
                hit(row.intersect(clip), () -> playFrom(all, index));
            });
        }
        scissorPop();
    }

    // ---- 封面墙（DangoMusic drawCoverflow 1:1） ----

    private void drawCoverflow(GuiGraphics g, Rect area, List<NeteaseSong> all, List<NeteaseSong> tracks)
    {
        if (tracks.isEmpty()) return;
        cfTarget = Math.max(0, Math.min(cfTarget, tracks.size() - 1));

        float size = Math.min(area.h * 0.66f, 240f);
        float cx = area.x + area.w / 2f;
        float blockH = size + 66f;
        float cy = area.y + Math.max(8f, (area.h - blockH) * 0.45f) + size / 2f;
        int first = Math.max(0, cfTarget - 6);
        int last = Math.min(tracks.size() - 1, cfTarget + 6);

        class Item { int i; float d; Rect b; }
        List<Item> items = new ArrayList<>();
        for (int i = first; i <= last; i++)
        {
            Item it = new Item();
            it.i = i;
            it.d = i - cfPos;
            float ad = Math.abs(it.d);
            float sign = it.d < 0 ? -1f : 1f;
            float offset = ad < 1f ? ad * size * 0.62f : size * 0.62f + (ad - 1f) * size * 0.30f;
            float scale = 1f - Math.min(ad, 3f) * 0.15f;
            float s = size * scale;
            it.b = new Rect(cx + sign * offset - s / 2f, cy - s / 2f, s, s);
            items.add(it);
        }
        items.removeIf(it -> it.b.right() <= area.x || it.b.x >= area.right());

        float fadeW = area.w * 0.22f;
        java.util.function.Function<Rect, Float> edgeFade = b ->
        {
            float mid = b.x + b.w / 2f;
            float e = Math.max(0f, Math.min(1f, Math.min(mid - area.x, area.right() - mid) / fadeW));
            return e * e * (3f - 2f * e);
        };
        Rect clip = new Rect(area.x, area.y - 12f, area.w, area.h + 12f);
        scissorPush(clip);
        items.sort((a, b2) -> Float.compare(Math.abs(b2.d), Math.abs(a.d)));
        for (Item it : items)
        {
            float ad = Math.abs(it.d);
            float a = Math.max(0.15f, 1f - Math.min(ad, 4f) * 0.2f) * edgeFade.apply(it.b);
            if (a < 0.01f) continue;
            shifted(0, 0, a, () -> {
                cover(it.b, tracks.get(it.i).picUrl, 12f);
                if (ad > 0.5f) fill(it.b, (int) (Math.min(0.45f, ad * 0.18f) * 255f) << 24, 2f);
            });
        }
        scissorPop();
        items.sort((a, b2) -> Float.compare(Math.abs(a.d), Math.abs(b2.d)));
        for (Item it : items)
        {
            if (edgeFade.apply(it.b) < 0.2f) continue;
            int i = it.i;
            hit(it.b.intersect(clip), () -> {
                if (i == cfTarget) playFrom(all, i);
                else cfTarget = i;
            });
        }

        NeteaseSong t = tracks.get(cfTarget);
        boolean isCur = MusicPlayer.getInstance().getCurrentSong() != null
                && MusicPlayer.getInstance().getCurrentSong().id == t.id;
        float ty = cy + size / 2f + 14f;
        String name = trim(t.name, area.w - 40f, 16f, true);
        textC(name, cx, ty, 16f, isCur ? RED : TEXT_PRIMARY, true);
        String artist = trim(t.getDisplayArtist(), area.w - 40f, 12f);
        textC(artist, cx, ty + 21f, 12f, TEXT_SECONDARY);
        String idx = (cfTarget + 1) + " / " + tracks.size();
        textC(idx, cx, ty + 38f, 11f, TEXT_TERTIARY);
    }

    // ================================================================
    //  设置页（iOS 风格分组卡片 + 弹簧开关 + 滑杆）
    // ================================================================

    private static class SettingItem
    {
        final String name;
        final java.util.function.Supplier<String> value;
        final boolean slider;
        final java.util.function.Supplier<Double> ratio;
        final java.util.function.DoubleConsumer onSlide;
        final Runnable onClick;

        SettingItem(String name, java.util.function.Supplier<String> value, Runnable onClick)
        {
            this.name = name; this.value = value; this.slider = false;
            this.ratio = null; this.onSlide = null; this.onClick = onClick;
        }

        SettingItem(String name, java.util.function.Supplier<String> value,
                    java.util.function.Supplier<Double> ratio, java.util.function.DoubleConsumer onSlide,
                    Runnable unused)
        {
            this.name = name; this.value = value;
            this.slider = ratio != null; this.ratio = ratio; this.onSlide = onSlide; this.onClick = null;
        }
    }

    private static class SettingSection
    {
        final String title;
        final List<SettingItem> items = new ArrayList<>();
        SettingSection(String t) { title = t; }
    }

    private final List<SettingSection> settingSections = buildSettingSections();

    private List<SettingSection> buildSettingSections()
    {
        List<SettingSection> out = new ArrayList<>();
        MusicPlayer mp = MusicPlayer.getInstance();

        SettingSection play = new SettingSection("播放");
        play.items.add(new SettingItem("洞穴混响",
                () -> ZephyrConfig.PLAYBACK_CAVE_REVERB.get() ? "ON" : "OFF",
                () -> { boolean v = !ZephyrConfig.PLAYBACK_CAVE_REVERB.get();
                        ZephyrConfig.PLAYBACK_CAVE_REVERB.set(v); mp.setCaveReverb(v); }));
        play.items.add(new SettingItem("随机播放",
                () -> mp.isShuffleMode() ? "ON" : "OFF",
                () -> mp.setShuffleMode(!mp.isShuffleMode())));
        play.items.add(new SettingItem("单曲循环",
                () -> mp.isLoopMode() ? "ON" : "OFF",
                () -> mp.setLoopMode(!mp.isLoopMode())));
        play.items.add(new SettingItem("音质",
                () -> { String q = ZephyrConfig.DEFAULT_QUALITY.get();
                        return "standard".equals(q) ? "标准" : "higher".equals(q) ? "较高"
                                : "exhigh".equals(q) ? "极高" : "lossless".equals(q) ? "无损" : "Hi-Res"; },
                () -> { String q = ZephyrConfig.DEFAULT_QUALITY.get();
                        ZephyrConfig.DEFAULT_QUALITY.set("standard".equals(q) ? "higher" : "higher".equals(q) ? "exhigh" : "exhigh".equals(q) ? "lossless" : "lossless".equals(q) ? "hires" : "standard"); }));
        play.items.add(new SettingItem("音量",
                () -> String.format("%d%%", (int) (ZephyrConfig.HUD_VOLUME.get() * 100)),
                () -> ZephyrConfig.HUD_VOLUME.get(),
                r -> { ZephyrConfig.HUD_VOLUME.set(r); mp.setVolume((float) (double) r); }, null));
        play.items.add(new SettingItem("听歌打卡",
                () -> ZephyrConfig.SCROBBLE_ENABLED.get() ? "ON" : "OFF",
                () -> ZephyrConfig.SCROBBLE_ENABLED.set(!ZephyrConfig.SCROBBLE_ENABLED.get())));
        out.add(play);

        SettingSection hud = new SettingSection("HUD");
        hud.items.add(new SettingItem("显示 HUD",
                () -> ZephyrConfig.HUD_ENABLED.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_ENABLED.set(!ZephyrConfig.HUD_ENABLED.get())));
        hud.items.add(new SettingItem("灵动岛模式",
                () -> ZephyrConfig.HUD_ISLAND_MODE.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_ISLAND_MODE.set(!ZephyrConfig.HUD_ISLAND_MODE.get())));
        hud.items.add(new SettingItem("灵动岛缩放",
                () -> String.format("%.2f", ZephyrConfig.HUD_ISLAND_SCALE.get()),
                () -> (ZephyrConfig.HUD_ISLAND_SCALE.get() - 0.8) / 0.7,
                r -> ZephyrConfig.HUD_ISLAND_SCALE.set(0.8 + r * 0.7), null));
        hud.items.add(new SettingItem("悬浮面板模式",
                () -> ZephyrConfig.HUD_FLOAT_MODE.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_FLOAT_MODE.set(!ZephyrConfig.HUD_FLOAT_MODE.get())));
        hud.items.add(new SettingItem("面板控制键",
                () -> ZephyrConfig.HUD_FLOAT_CONTROLS.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_FLOAT_CONTROLS.set(!ZephyrConfig.HUD_FLOAT_CONTROLS.get())));
        hud.items.add(new SettingItem("编辑模式",
                () -> "按 F8",
                () -> MusicHudOverlay.toggleEditMode()));
        hud.items.add(new SettingItem("显示封面",
                () -> ZephyrConfig.HUD_SHOW_COVER.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_SHOW_COVER.set(!ZephyrConfig.HUD_SHOW_COVER.get())));
        hud.items.add(new SettingItem("显示进度条",
                () -> ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_SHOW_PROGRESS_BAR.set(!ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get())));
        hud.items.add(new SettingItem("显示歌词",
                () -> ZephyrConfig.HUD_SHOW_LYRICS.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_SHOW_LYRICS.set(!ZephyrConfig.HUD_SHOW_LYRICS.get())));
        hud.items.add(new SettingItem("歌词行数",
                () -> String.valueOf(ZephyrConfig.HUD_LYRICS_LINES.get()),
                () -> (ZephyrConfig.HUD_LYRICS_LINES.get() - 1) / 11.0,
                r -> ZephyrConfig.HUD_LYRICS_LINES.set((int) Math.round(1 + r * 11)), null));
        hud.items.add(new SettingItem("面板宽度",
                () -> String.valueOf(ZephyrConfig.HUD_WIDTH.get()),
                () -> (ZephyrConfig.HUD_WIDTH.get() - 120) / 360.0,
                r -> ZephyrConfig.HUD_WIDTH.set((int) Math.round(120 + r * 360)), null));
        hud.items.add(new SettingItem("封面大小",
                () -> String.valueOf(ZephyrConfig.HUD_COVER_SIZE.get()),
                () -> (ZephyrConfig.HUD_COVER_SIZE.get() - 16) / 240.0,
                r -> ZephyrConfig.HUD_COVER_SIZE.set((int) Math.round(16 + r * 240)), null));
        hud.items.add(new SettingItem("锚点",
                () -> ZephyrConfig.HUD_ANCHOR.get(),
                () -> { String[] a = {"top_left", "top_right", "bottom_left", "bottom_right"};
                        int i = 0; for (int j = 0; j < 4; j++) if (a[j].equals(ZephyrConfig.HUD_ANCHOR.get())) i = j;
                        ZephyrConfig.HUD_ANCHOR.set(a[(i + 1) % 4]); }));
        hud.items.add(new SettingItem("菜单打开时暂停",
                () -> ZephyrConfig.HUD_PAUSE_ON_MENU.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_PAUSE_ON_MENU.set(!ZephyrConfig.HUD_PAUSE_ON_MENU.get())));
        out.add(hud);

        SettingSection ui = new SettingSection("界面 · 毛玻璃");
        ui.items.add(new SettingItem("背景模糊",
                () -> ZephyrConfig.UI_GLASS_BLUR.get() ? "ON" : "OFF",
                () -> { ZephyrConfig.UI_GLASS_BLUR.set(!ZephyrConfig.UI_GLASS_BLUR.get()); BlurManager.invalidate(); }));
        ui.items.add(new SettingItem("模糊强度",
                () -> "×" + ZephyrConfig.UI_BLUR_STRENGTH.get(),
                () -> (ZephyrConfig.UI_BLUR_STRENGTH.get() - 1) / 3.0,
                r -> { ZephyrConfig.UI_BLUR_STRENGTH.set((int) Math.round(1 + r * 3)); BlurManager.invalidate(); }, null));
        ui.items.add(new SettingItem("经典背景不透明度",
                () -> String.format("%d%%", (int) (ZephyrConfig.HUD_BG_OPACITY.get() * 100)),
                () -> ZephyrConfig.HUD_BG_OPACITY.get(),
                r -> ZephyrConfig.HUD_BG_OPACITY.set(r), null));
        ui.items.add(new SettingItem("经典边框",
                () -> ZephyrConfig.HUD_SHOW_BORDER.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_SHOW_BORDER.set(!ZephyrConfig.HUD_SHOW_BORDER.get())));
        out.add(ui);

        SettingSection lyric = new SettingSection("歌词");
        lyric.items.add(new SettingItem("卡拉OK 逐字",
                () -> ZephyrConfig.LYRIC_KARAOKE.get() ? "ON" : "OFF",
                () -> ZephyrConfig.LYRIC_KARAOKE.set(!ZephyrConfig.LYRIC_KARAOKE.get())));
        lyric.items.add(new SettingItem("歌词模式",
                () -> ZephyrConfig.LYRIC_MODE.get(),
                () -> { String m = ZephyrConfig.LYRIC_MODE.get();
                        ZephyrConfig.LYRIC_MODE.set("yrc".equals(m) ? "lrc" : "lrc".equals(m) ? "off" : "yrc"); }));
        lyric.items.add(new SettingItem("字号",
                () -> String.valueOf(ZephyrConfig.LYRIC_FONT_SIZE.get()),
                () -> (ZephyrConfig.LYRIC_FONT_SIZE.get() - 6) / 10.0,
                r -> ZephyrConfig.LYRIC_FONT_SIZE.set((int) Math.round(6 + r * 10)), null));
        out.add(lyric);
        return out;
    }

    private final List<Object[]> sliderRects = new ArrayList<>();
    private Object[] sliderActive = null;

    private void drawSettingsPage(GuiGraphics g, Rect area)
    {
        settingsAreaRect = area;
        headerAnim(() -> {
            text("设置", area.x, area.y, 22f, TEXT_PRIMARY, true);
            text("播放 · HUD · 界面 · 歌词", area.x, area.y + 30f, 11f, TEXT_SECONDARY, true);
        });
        float rowH = 30f, secHead = 24f, secGap = 14f;
        float totalH = 58f;
        for (SettingSection s : settingSections) totalH += secHead + s.items.size() * rowH + 10 + secGap;
        settingsMax = Math.max(0f, totalH - area.h);
        scroll = Math.max(0, Math.min(scroll, (int) settingsMax));

        Rect clip = new Rect(area.x, area.y, area.w, area.h);
        scissorPush(clip);
        float y = area.y + 58f - (float) scroll;
        sliderRects.clear();
        for (SettingSection sec : settingSections)
        {
            text(sec.title, area.x + 2f, y, 10f, TEXT_TERTIARY, true);
            y += secHead;
            float cardH = sec.items.size() * rowH + 8f;
            fill(new Rect(area.x, y, area.w, cardH), CARD, 10f);
            float ry = y + 4f;
            for (int ii = 0; ii < sec.items.size(); ii++)
            {
                SettingItem item = sec.items.get(ii);
                Rect row = new Rect(area.x + 2f, ry, area.w - 4f, rowH);
                final SettingItem fItem = item;
                text(item.name, area.x + 14f, ry + 9f, 12f, TEXT_PRIMARY);
                float ctrlRight = area.right() - 14f;
                if (item.slider)
                {
                    float sw2 = 140f;
                    Rect sl = new Rect(ctrlRight - sw2, ry + rowH / 2f - 2f, sw2, 4f);
                    double ratio = Math.max(0, Math.min(1, item.ratio.get()));
                    fill(sl, TRACK_C, 2f);
                    float fw = Math.max(4f, sl.w * (float) ratio);
                    fill(new Rect(sl.x, sl.y, fw, sl.h), RED, 2f);
                    float kx = sl.x + fw;
                    fill(new Rect(kx - 5f, sl.y - 3f, 10f, 10f), 0xFFFFFFFF, 5f);
                    sliderRects.add(new Object[]{sl.expand(8f, 10f), item});
                    String val = item.value.get();
                    text(val, sl.x - 12f - tw(val, 11f, false), ry + 9f, 11f, TEXT_SECONDARY);
                }
                else if ("ON".equals(item.value.get()) || "OFF".equals(item.value.get()))
                {
                    boolean on = "ON".equals(item.value.get());
                    Rect tg = new Rect(ctrlRight - 36f, ry + rowH / 2f - 10.5f, 36f, 21f);
                    toggleSwitch(tg, on, sec.title + "." + item.name, fItem.onClick);
                }
                else
                {
                    String val = item.value.get();
                    text(val, ctrlRight - tw(val, 12f, false), ry + 9f, 12f, TEXT_SECONDARY);
                    if (item.onClick != null) hit(row, fItem.onClick);
                }
                ry += rowH;
            }
            y += cardH + secGap;
        }
        scissorPop();
    }

    private void applySettingsSlider(float lx)
    {
        if (sliderActive == null) return;
        Rect sl = (Rect) sliderActive[0];
        SettingItem item = (SettingItem) sliderActive[1];
        if (item.onSlide != null)
            item.onSlide.accept(Math.max(0, Math.min(1, (lx - sl.x) / Math.max(1f, sl.w))));
    }

    // ================================================================
    //  账号页（登录态五态展示 + 扫码/手机/邮箱登录）
    // ================================================================

    private void drawAccountPage(GuiGraphics g, Rect area)
    {
        settingsAreaRect = new Rect();
        headerAnim(() -> {
            text("账号", area.x, area.y, 22f, TEXT_PRIMARY, true);
            text("网易云音乐", area.x, area.y + 30f, 11f, TEXT_SECONDARY, true);
        });
        NeteaseSession sess = NeteaseSession.getInstance();
        boolean hasCookie = com.zephyr.music.config.LoginStore.hasCookie();
        float cx = area.x + area.w / 2f;
        float y = area.y + 64f;

        // 有 cookie 但未验证出用户：按登录态分支（保留既有修复）
        if (hasCookie && userDetail == null && sess.getCurrentUser() == null)
        {
            if (sess.getLoginState() == NeteaseSession.LoginState.VERIFYING)
            {
                textC("正在验证登录态...", cx, y + 30f, 13f, TEXT_PRIMARY);
                progressBar(new Rect(cx - 90f, y + 56f, 180f, 4f), null);
                return;
            }
            if (sess.getLoginState() == NeteaseSession.LoginState.UNKNOWN)
            {
                textC("无法获取用户信息", cx, y + 30f, 13f, TEXT_PRIMARY, true);
                textC("网络异常或服务暂不可用", cx, y + 52f, 11f, TEXT_SECONDARY);
                Rect vb = new Rect(cx - 160f, y + 76f, 150f, 30f);
                fill(vb, hover(vb) ? CARD_HOVER : CARD, 15f);
                textC("重新验证", vb.x + vb.w / 2f, vb.y + 9f, 12f, TEXT_PRIMARY, true);
                errBtnVerify = vb;
                hit(vb, () -> { userDetail = null; loginVerifyStarted = false; sess.resetLocalState(); ensureLoginVerified(); });
                return;
            }
            // INVALID / NOT_LOGGED_IN
            textC("登录态已失效", cx, y + 30f, 13f, TEXT_PRIMARY, true);
            textC("登录凭证过期或已被服务端拒绝，需要重新登录", cx, y + 52f, 11f, TEXT_SECONDARY);
            Rect rb = new Rect(cx - 160f, y + 76f, 150f, 30f);
            fill(rb, hover(rb) ? 0xD9FF375F : RED, 15f);
            textC("重新登录", rb.x + rb.w / 2f, rb.y + 9f, 12f, TEXT_PRIMARY, true);
            errBtnRelogin = rb;
            hit(rb, this::relogin);
            Rect vb2 = new Rect(cx + 10f, y + 76f, 150f, 30f);
            fill(vb2, hover(vb2) ? CARD_HOVER : CARD, 15f);
            textC("重新验证", vb2.x + vb2.w / 2f, vb2.y + 9f, 12f, TEXT_PRIMARY, true);
            errBtnVerify = vb2;
            hit(vb2, () -> { userDetail = null; loginVerifyStarted = false; sess.resetLocalState(); ensureLoginVerified(); });
            return;
        }

        // 已登录：账号卡片
        NeteaseUser accUser = userDetail != null ? userDetail : sess.getCurrentUser();
        if (sess.isLoggedIn() && accUser != null)
        {
            float cardW = 360f, cardH = 210f;
            Rect card = new Rect(cx - cardW / 2f, y, cardW, cardH);
            fill(card, CARD, 14f);
            Rect av = new Rect(cx - 32f, y + 18f, 64f, 64f);
            cover(av, accUser.avatarUrl, 16f);
            textC(accUser.nickname, cx, y + 92f, 16f, TEXT_PRIMARY, true);
            String meta = "UID " + accUser.userId + " · 等级 " + accUser.level;
            textC(meta, cx, y + 114f, 11f, TEXT_SECONDARY);
            String listened = "累计听歌 " + accUser.listenSongs + " 首";
            textC(listened, cx, y + 130f, 11f, TEXT_TERTIARY);
            Rect lb = new Rect(cx - 60f, y + cardH - 46f, 120f, 30f);
            fill(lb, hover(lb) ? 0x2EFF375F : 0x1AFF375F, 15f);
            stroke(lb, 0x66FF375F, 15f, 1f);
            textC("退出登录", cx, lb.y + 9f, 12f, RED, true);
            hit(lb, () -> NeteaseSession.getInstance().logout().thenAccept(v ->
                    Minecraft.getInstance().execute(() -> { userDetail = null; loginMode = 0; playlists.clear(); })));
            return;
        }

        // 未登录
        if (loginMode == 0)
        {
            String[] labels = {"扫码登录", "手机验证码登录", "邮箱登录"};
            for (int i = 0; i < 3; i++)
            {
                Rect b = new Rect(cx - 90f, y + i * 44f, 180f, 34f);
                boolean primary = i == 0;
                fill(b, hover(b) ? (primary ? 0xD9FF375F : CARD_HOVER) : (primary ? RED : CARD), 17f);
                textC(labels[i], cx, b.y + 10f, 12f, primary ? TEXT_PRIMARY : TEXT_PRIMARY, primary);
                final int mode = i + 1;
                hit(b, () -> { loginMode = mode; if (mode == 1) startQrLogin(); blurFields(); });
            }
            if (!loginStatus.isEmpty()) textC(loginStatus, cx, y + 148f, 11f, loginStatusColor);
        }
        else if (loginMode == 1)
        {
            Rect card = new Rect(cx - 90f, y, 180f, 196f);
            fill(card, 0xF0FFFFFF, 12f);
            if (qrImage != null)
                coverImg(new Rect(cx - 68f, y + 10f, 136f, 136f), qrImage, 6f);
            else
                fill(new Rect(cx - 68f, y + 10f, 136f, 136f), TRACK_C, 6f);
            if (!loginStatus.isEmpty()) textC(trim(loginStatus, card.w - 16f, 11f), cx, y + 160f, 11f, 0xFF6E6E73);
            Rect back = new Rect(cx - 45f, y + 210f, 90f, 26f);
            fill(back, hover(back) ? CARD_HOVER : CARD, 13f);
            textC("返回", cx, back.y + 7f, 11f, TEXT_PRIMARY);
            qrBackRect = back;
            hit(back, () -> { loginMode = 0; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } qrImage = null; });
        }
        else
        {
            boolean phone = loginMode == 2;
            float fw = 240f;
            float fx = cx - fw / 2f;
            float fy = y;
            if (phone)
            {
                drawField(g, countryCodeField, fx, fy, 56f, 26f, 8f);
                drawField(g, phoneField, fx + 66f, fy, fw - 66f, 26f, 8f);
                drawField(g, captchaField, fx, fy + 36f, fw, 26f, 8f);
                textC("验证码发送后请在网易云App或短信中查看", cx, fy + 70f, 10f, TEXT_TERTIARY);
            }
            else
            {
                drawField(g, emailField, fx, fy, fw, 26f, 8f);
                drawField(g, emailPwdField, fx, fy + 36f, fw, 26f, 8f);
            }
            Rect login = new Rect(cx - 70f, fy + 100f, 140f, 30f);
            fill(login, hover(login) ? 0xD9FF375F : RED, 15f);
            textC("登录", cx, login.y + 9f, 12f, TEXT_PRIMARY, true);
            hit(login, this::doLogin);
            Rect back2 = new Rect(cx - 70f, fy + 140f, 140f, 26f);
            fill(back2, hover(back2) ? CARD_HOVER : CARD, 13f);
            textC("返回", cx, back2.y + 7f, 11f, TEXT_PRIMARY);
            hit(back2, () -> { loginMode = 0; blurFields(); });
            if (!loginStatus.isEmpty()) textC(loginStatus, cx, fy + 176f, 11f, loginStatusColor);
        }
    }

    /** 玻璃输入框绘制（DangoMusic 搜索框风格） */
    private void drawField(GuiGraphics g, Field f, float x, float y, float w, float h, float radius)
    {
        f.rect.set(x, y, w, h);
        float fc = Math.max(0f, Math.min(1f, f.focus.tick(f.focused ? 1f : 0f)));
        fill(f.rect, mixColor(CARD, CARD_HOVER, fc), radius);
        if (fc > 0.01f) stroke(f.rect, (int) (0xFF * fc) << 24 | (RED & 0xFFFFFF), radius, 1f);
        boolean caretOn = f.focused && (System.currentTimeMillis() / 500) % 2 == 0L;
        String body = f.password ? "*".repeat(f.text.length()) : f.text;
        String shown = body + (caretOn ? "|" : "");
        float maxW = w - 20f;
        while (shown.length() > 1 && tw(shown, 12f, false) > maxW) shown = shown.substring(1);
        if (f.text.isEmpty() && !f.focused)
            text(f.hint, x + 10f, y + h / 2f - 6f, 12f, TEXT_TERTIARY);
        else
            text(shown, x + 10f, y + h / 2f - 6f, 12f, TEXT_PRIMARY);
        hit(f.rect, () -> { f.focused = true; });
    }

    private void blurFields()
    {
        searchField.focused = false;
        filterField.focused = false;
        for (Field f : loginFields) f.focused = false;
    }

    private Field focusedField()
    {
        if (searchField.focused) return searchField;
        if (filterField.focused) return filterField;
        for (Field f : loginFields) if (f.focused) return f;
        return null;
    }

    // ================================================================
    //  歌词页（卡拉OK 逐字）
    // ================================================================

    private void drawLyricsPage(GuiGraphics g, Rect area)
    {
        headerAnim(() -> {
            text("歌词", area.x, area.y, 22f, TEXT_PRIMARY, true);
            text("逐字歌词 · 卡拉OK", area.x, area.y + 30f, 11f, TEXT_SECONDARY, true);
        });
        MusicPlayer mp = MusicPlayer.getInstance();
        List<LyricLine> lines = mp.getCurrentLyrics();
        if (lines == null || lines.isEmpty())
        {
            textC("暂无歌词", area.x + area.w / 2f, area.y + area.h / 2f - 8f, 14f, TEXT_TERTIARY);
            return;
        }
        double pos = mp.getPositionSec();
        int cur = -1;
        for (int i = 0; i < lines.size(); i++) { if (lines.get(i).time <= pos) cur = i; else break; }
        float cy = area.y + area.h / 2f - 30f;
        if (ZephyrConfig.LYRIC_KARAOKE.get() && cur >= 0 && !lines.get(cur).words.isEmpty())
        {
            drawKaraoke(g, lines.get(cur), area.x + area.w / 2f, cy, area.w - 60f, pos, 2.0f);
        }
        else if (cur >= 0)
        {
            String t = lineText(lines.get(cur));
            textC(trim(t, area.w - 60f, 19f, true), area.x + area.w / 2f, cy, 19f, TEXT_PRIMARY, true);
        }
        if (cur >= 0 && cur + 1 < lines.size())
            textC(trim(lineText(lines.get(cur + 1)), area.w - 80f, 13f), area.x + area.w / 2f, cy + 44f, 13f, TEXT_SECONDARY);
        if (cur >= 0 && cur + 2 < lines.size())
            textC(trim(lineText(lines.get(cur + 2)), area.w - 80f, 12f), area.x + area.w / 2f, cy + 68f, 12f, TEXT_TERTIARY);
        if (cur > 0)
            textC(trim(lineText(lines.get(cur - 1)), area.w - 80f, 12f), area.x + area.w / 2f, cy - 34f, 12f, TEXT_TERTIARY);
    }

    private String lineText(LyricLine l)
    {
        if (!l.words.isEmpty())
        {
            StringBuilder sb = new StringBuilder();
            for (LyricWord w : l.words) sb.append(w.text);
            return sb.toString();
        }
        return l.text;
    }

    private void drawKaraoke(GuiGraphics g, LyricLine line, float cx, float y, float maxW, double pos, float scale)
    {
        StringBuilder sb = new StringBuilder();
        for (LyricWord w : line.words) sb.append(w.text);
        String all = sb.toString();
        float total = tw(all, 15f * scale, true);
        float sx = cx - Math.min(total, maxW) / 2f;
        if (total > maxW) sx = cx - maxW / 2f;
        float x = sx;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            float ww = tw(w.text, 15f * scale, true);
            if (x + ww > sx + maxW) break;
            int color = w.isFinished(pos) ? ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get()
                    : w.isPlayingAt(pos) ? ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get()
                    : ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();
            text(w.text, x, y, 15f * scale, color, true);
            x += ww;
        }
    }

    // ================================================================
    //  底部播放栏（DangoMusic PlayerBar.kt 1:1）
    // ================================================================

    private void drawPlayerBar(GuiGraphics g, Rect bar)
    {
        fill(bar, CARD, 16f);
        MusicPlayer mp = MusicPlayer.getInstance();
        NeteaseSong t = mp.getCurrentSong();

        // 左：封面 + 歌名/歌手
        float cs = 48f;
        Rect coverBox = new Rect(bar.x + 13f, bar.y + (bar.h - cs) / 2f, cs, cs);
        cover(coverBox, t != null ? t.picUrl : "", 8f);
        float infoX = coverBox.right() + 12f;
        float infoW = 220f;
        String title = t != null ? t.name : "未在播放";
        text(trim(title, infoW, 14f, true), infoX, coverBox.y + 9f, 14f, TEXT_PRIMARY, true);
        String sub = t == null ? "-" : t.getDisplayArtist();
        text(trim(sub, infoW, 11f), infoX, coverBox.y + 28f, 11f, TEXT_SECONDARY);

        // 红心
        if (t != null && (NeteaseSession.getInstance().isLoggedIn()))
        {
            Rect likeB = new Rect(infoX + infoW + 8f, coverBox.y + cs / 2f - 14f, 28f, 28f);
            float hk = buttonAnim(likeB, likeA);
            float on = likeA.on.tick(NeteaseSession.getInstance().isLiked(t.id) ? 1f : 0f);
            icon("♥", likeB.x + 14f, likeB.y + 14f, 15f * hk, mixColor(TEXT_SECONDARY, RED, Math.max(0f, Math.min(1f, on))));
            hit(likeB, () -> {
                likeA.press.v -= 7f;
                NeteaseSession.getInstance().toggleLike(t.id, !NeteaseSession.getInstance().isLiked(t.id));
            });
        }

        // 中：上一首/播放/下一首/队列
        float cx = bar.x + bar.w / 2f;
        float cy = bar.y + 25f;
        Rect playB = new Rect(cx - 17f, cy - 17f, 34f, 34f);
        Rect prevB = new Rect(cx - 58f - 14f, cy - 14f, 28f, 28f);
        Rect nextB = new Rect(cx + 58f - 14f, cy - 14f, 28f, 28f);
        Rect queueB = new Rect(cx + 112f - 14f, cy - 14f, 28f, 28f);
        float kPrev = buttonAnim(prevB, prevA);
        float kPlay = buttonAnim(playB, playA);
        float kNext = buttonAnim(nextB, nextA);
        float kQueue = buttonAnim(queueB, queueA);
        // 播放 ↔ 暂停形变
        float p = playMorph.tick(mp.isPaused() ? 0f : 1f);
        p = Math.max(0f, Math.min(1f, p));
        if (p > 0.01f)
        {
            float old = uiAlpha;
            uiAlpha = old * p;
            float bh = 16f * kPlay * (0.55f + 0.45f * p);
            fill(new Rect(cx - 6f * kPlay, cy - bh / 2f, 4f * kPlay, bh), TEXT_PRIMARY, 1.5f);
            fill(new Rect(cx + 2f * kPlay, cy - bh / 2f, 4f * kPlay, bh), TEXT_PRIMARY, 1.5f);
            uiAlpha = old;
        }
        if (p < 0.99f)
        {
            float old = uiAlpha;
            uiAlpha = old * (1f - p);
            icon("▶", cx + 1.5f * kPlay, cy, 18f * kPlay * (1f - 0.35f * p), TEXT_PRIMARY);
            uiAlpha = old;
        }
        // ⏮ / ⏭
        float pc = prevB.x + 14f;
        fill(new Rect(pc - 6f * kPrev, cy - 5.5f * kPrev, 2.2f * kPrev, 11f * kPrev), TEXT_PRIMARY, 1f);
        icon("◀", pc + 2.5f * kPrev, cy, 12f * kPrev, TEXT_PRIMARY);
        float nc = nextB.x + 14f;
        icon("▶", nc - 2.5f * kNext, cy, 12f * kNext, TEXT_PRIMARY);
        fill(new Rect(nc + 3.8f * kNext, cy - 5.5f * kNext, 2.2f * kNext, 11f * kNext), TEXT_PRIMARY, 1f);
        float q = queueA.on.tick(currentTab == Tab.QUEUE ? 1f : 0f);
        icon("≡", queueB.x + 14f, cy, 14f * kQueue, mixColor(TEXT_PRIMARY, RED, Math.max(0f, Math.min(1f, q))));
        hit(prevB, () -> { prevA.press.v -= 7f; mp.prev(); });
        hit(playB, () -> { playA.press.v -= 7f; if (mp.getCurrentSong() == null) return; if (mp.isPaused()) mp.resume(); else mp.pause(); });
        hit(nextB, () -> { nextA.press.v -= 7f; mp.next(); });
        hit(queueB, () -> { queueA.press.v -= 7f; if (currentTab == Tab.QUEUE) switchTab(backTab == Tab.QUEUE ? Tab.HOME : backTab); else switchTab(Tab.QUEUE); });

        // 进度条
        double durSec = t != null ? t.duration / 1000.0 : 0;
        double posSec = mp.getPositionSec();
        float pw = Math.min(440f, bar.w * 0.4f);
        progRect.set(cx - pw / 2f, bar.y + 54f, pw, 4f);
        String l = fmtTime(posSec), rgt = fmtTime(durSec);
        text(l, progRect.x - 10f - tw(l, 10f, false), progRect.y - 3f, 10f, TEXT_SECONDARY);
        text(rgt, progRect.right() + 10f, progRect.y - 3f, 10f, TEXT_SECONDARY);
        fill(progRect, TRACK_C, 2f);
        if (durSec > 0)
        {
            float frac = (float) Math.max(0, Math.min(1.0, posSec / durSec));
            fill(new Rect(progRect.x, progRect.y, Math.max(4f, progRect.w * frac), progRect.h), RED, 2f);
            float kn = progKnob.tick(progRect.expand(0f, 6f).contains(lmxRaw, lmyRaw) ? 1f : 0f);
            kn = Math.max(0f, Math.min(1.2f, kn));
            if (kn > 0.01f)
            {
                float kx = progRect.x + progRect.w * frac;
                float ks = 10f * kn;
                fill(new Rect(kx - ks / 2f, progRect.y + 2f - ks / 2f, ks, ks), TEXT_PRIMARY, ks / 2f);
            }
        }

        // 右：随机/单曲循环 + 音量
        float vy = bar.y + bar.h / 2f;
        volRect.set(bar.right() - 30f - 110f, vy - 2f, 110f, 4f);
        speaker(volRect.x - 22f, vy, false);
        speaker(volRect.right() + 9f, vy, true);
        fill(volRect, TRACK_C, 2f);
        float vol = mp.getVolume();
        fill(new Rect(volRect.x, volRect.y, Math.max(4f, volRect.w * vol), volRect.h), RED, 2f);
        float knobX = volRect.x + volRect.w * vol;
        fill(new Rect(knobX - 5f, volRect.y - 3f, 10f, 10f), TEXT_PRIMARY, 5f);

        Rect shB = new Rect(volRect.x - 72f, vy - 14f, 32f, 28f);
        toggleButton(shB, mp.isShuffleMode(), shuffleA, "⇄", () -> mp.setShuffleMode(!mp.isShuffleMode()));
        Rect rpB = new Rect(shB.x - 40f, vy - 14f, 32f, 28f);
        toggleButton(rpB, mp.isLoopMode(), repeatA, "↻", () -> mp.setLoopMode(!mp.isLoopMode()));
    }

    private float buttonAnim(Rect b, Anim a)
    {
        float h = a.hover.tick(hover(b) ? 1f : 0f);
        float k = 1f + a.press.tick(0f) * 0.14f + Math.max(0f, Math.min(1f, h)) * 0.04f;
        if (h > 0.01f)
        {
            Rect d = b.scaled(k);
            int c = (int) ((0x16 * Math.min(1f, h))) << 24 | 0xFFFFFF;
            fillG(d, c, d.h / 2f);
        }
        return k;
    }

    private void toggleButton(Rect b, boolean on, Anim a, String glyph, Runnable action)
    {
        float o = a.on.tick(on ? 1f : 0f);
        float h = a.hover.tick(hover(b) ? 1f : 0f);
        float k = 1f + a.press.tick(0f) * 0.14f;
        int base = mixColor(0x12FFFFFF, CARD_HOVER, Math.max(0f, Math.min(1f, h)));
        fill(b.scaled(k), mixColor(base, 0xD9FF375F, Math.max(0f, Math.min(1f, o))), 9f * k);
        icon(glyph, b.x + 16f, b.y + b.h / 2f, 14f * k, TEXT_PRIMARY);
        hit(b, () -> { a.press.v -= 7f; action.run(); });
    }

    private void speaker(float x, float cy, boolean loud)
    {
        fill(new Rect(x, cy - 2.5f, 3.5f, 5f), TEXT_SECONDARY, 1f);
        fill(new Rect(x + 3f, cy - 5f, 3.5f, 10f), TEXT_SECONDARY, 1.2f);
        if (loud)
        {
            fill(new Rect(x + 8.5f, cy - 2.5f, 1.4f, 5f), TEXT_SECONDARY, 0.7f);
            fill(new Rect(x + 11.5f, cy - 4.5f, 1.4f, 9f), TEXT_SECONDARY, 0.7f);
        }
    }

    // ================================================================
    //  裁剪（逻辑坐标 → 屏幕像素）
    // ================================================================

    private void scissorPush(Rect r)
    {
        gRef.enableScissor(ri(r.x * dScale), ri(r.y * dScale), ri(r.right() * dScale), ri(r.bottom() * dScale));
    }

    private void scissorPop() { gRef.disableScissor(); }

    // ================================================================
    //  输入处理
    // ================================================================

    @Override
    public boolean mouseClicked(double mx, double my, int btn)
    {
        float lx = (float) (mx / dScale), ly = (float) (my / dScale);
        lmxRaw = lx;
        lmyRaw = ly;

        // 进度条 / 音量条（最高优先级）
        if (progRect.w > 0 && progRect.expand(0f, 6f).contains(lx, ly))
        { progDrag = true; seekTo(lx); return true; }
        if (volRect.w > 0 && volRect.expand(6f, 8f).contains(lx, ly))
        { volDrag = true; setVolumeAt(lx); return true; }

        // 滚动条
        if (!sbTrackRect.isEmpty() && sbTrackRect.expand(8f, 0f).contains(lx, ly) && sbTotal > listRows)
        {
            Rect th = thumbOf(sbTrackRect, sbTotal, listRows);
            sbGrab = (ly >= th.y && ly <= th.bottom()) ? ly - th.y : th.h / 2f;
            sbDragging = true;
            scrollbarTo(ly);
            return true;
        }

        // 设置滑杆
        if (currentTab == Tab.SETTINGS)
        {
            for (Object[] sr : sliderRects)
            {
                Rect sl = (Rect) sr[0];
                if (sl.contains(lx, ly)) { sliderActive = sr; sliderDrag = true; applySettingsSlider(lx); return true; }
            }
        }

        // 点击空白处取消输入框焦点
        Field ff = focusedField();
        if (ff != null && !ff.rect.expand(4f, 4f).contains(lx, ly)) ff.focused = false;

        // 命中测试（按登记顺序）
        for (Object[] h : hits)
        {
            Rect r = (Rect) h[0];
            if (r.contains(lx, ly)) { ((Runnable) h[1]).run(); return true; }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy)
    {
        float lx = (float) (mx / dScale), ly = (float) (my / dScale);
        if (progDrag) { seekTo(lx); return true; }
        if (volDrag) { setVolumeAt(lx); return true; }
        if (sbDragging) { scrollbarTo(ly); return true; }
        if (sliderDrag) { applySettingsSlider(lx); return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn)
    {
        progDrag = false;
        volDrag = false;
        sbDragging = false;
        sliderDrag = false;
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta)
    {
        float lx = (float) (mx / dScale), ly = (float) (my / dScale);
        // 封面墙
        if (coverView && tracksAreaRect.contains(lx, ly))
        {
            List<NeteaseSong> songs = currentTracks();
            if (songs != null && !songs.isEmpty())
            {
                cfTarget = Math.max(0, Math.min(cfTarget + (delta > 0 ? -1 : 1), songs.size() - 1));
                return true;
            }
        }
        // 侧栏歌单列表
        if (sbListArea.contains(lx, ly) && !playlists.isEmpty())
        { sbScroll += delta > 0 ? -1 : 1; return true; }
        // 网格页
        if (gridAreaRect.contains(lx, ly) && gridMax > 0)
        { gridTarget = Math.max(0f, Math.min(gridTarget + (delta > 0 ? -120f : 120f), gridMax)); return true; }
        // 歌曲列表
        if (tracksAreaRect.contains(lx, ly) && !coverView)
        {
            List<NeteaseSong> songs = currentTracks();
            int total = songs == null ? 0 : songs.size();
            if (total > listRows)
            { scroll = Math.max(0, Math.min(scroll + (delta > 0 ? -2 : 2), total - listRows)); return true; }
            return true;
        }
        // 设置页
        if (currentTab == Tab.SETTINGS && settingsAreaRect.contains(lx, ly) && settingsMax > 0)
        { scroll = Math.max(0, Math.min(scroll + (delta > 0 ? -40 : 40), (int) settingsMax)); return true; }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        Field f = focusedField();
        if (f != null)
        {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { f.focused = false; return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
            {
                f.press.v -= 6f;
                if (f == searchField) doSearch();
                else if (loginMode == 2 || loginMode == 3) doLogin();
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) { f.backspace(); return true; }
            boolean ctrl = (modifiers & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
            if (ctrl && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_V)
            {
                String clip = this.minecraft.keyboardHandler.getClipboard();
                f.append(clip);
                return true;
            }
            if (ctrl && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_A) { f.clear(); return true; }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB)
                return true;
            return true; // 普通按键由 charTyped 处理，这里统一吃掉防止触发游戏热键
        }
        // 全局快捷键
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)
        {
            MusicPlayer mp = MusicPlayer.getInstance();
            if (mp.getCurrentSong() != null) { if (mp.isPaused()) mp.resume(); else mp.pause(); }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)
        {
            if (coverView && tracksAreaRect != null)
            {
                List<NeteaseSong> songs = currentTracks();
                if (songs != null && !songs.isEmpty()) cfTarget = Math.min(songs.size() - 1, cfTarget + 1);
            }
            else MusicPlayer.getInstance().seekTo(MusicPlayer.getInstance().getPositionSec() + 5);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT)
        {
            if (coverView && tracksAreaRect != null) cfTarget = Math.max(0, cfTarget - 1);
            else MusicPlayer.getInstance().seekTo(Math.max(0, MusicPlayer.getInstance().getPositionSec() - 5));
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) { goBackPage(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers)
    {
        Field f = focusedField();
        if (f != null && ch >= ' ' && ch != 127) { f.append(String.valueOf(ch)); return true; }
        return super.charTyped(ch, modifiers);
    }

    private void seekTo(float lx)
    {
        MusicPlayer mp = MusicPlayer.getInstance();
        NeteaseSong s = mp.getCurrentSong();
        if (s == null || s.duration <= 0 || progRect.w <= 0) return;
        double r = Math.max(0, Math.min(1, (lx - progRect.x) / progRect.w));
        mp.seekTo(r * (s.duration / 1000.0));
    }

    private void setVolumeAt(float lx)
    {
        if (volRect.w <= 0) return;
        float v = Math.max(0f, Math.min(1f, (lx - volRect.x) / volRect.w));
        MusicPlayer.getInstance().setVolume(v);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
