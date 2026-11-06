package com.zephyr.music.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.*;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.glass.BlurManager;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.client.hud.MusicHudOverlay;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 全集成播放器界面 —— 全面借鉴 Sorihiko/DangoMusic 的布局与交互（GPL-3.0）
 *
 * 单窗口三段式布局（DangoMusic MusicScreen 架构）：
 *   左侧玻璃侧栏（常驻搜索框 + 分组导航 + 我的歌单快捷列表 + 底部账号）
 *   右侧固定页头（标题 + 操作胶囊，不随内容滚动）+ 滚动正文
 *   底部播放栏（封面/歌名 + 走带控制 + 红心 + 音量 + 进度条）
 *
 * 页面：首页（推荐歌单网格 + 每日推荐）、排行榜、歌单（列表 ↔ 封面墙 Coverflow）、
 *       搜索、正在播放、歌词、设置、账号
 * 交互：歌单内筛选、定位当前歌曲、Space 播放暂停、←/→ 快进快退、
 *       入场动画（标题滑入 / 条目上浮）、灵动岛 HUD（MusicHudOverlay）
 */
public class PlayerScreen extends Screen
{
    public enum Tab { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, LYRICS, SETTINGS, ACCOUNT }

    // ===== 布局常量（DangoMusic：侧栏 / 固定页头 / 底部播放栏） =====
    private static final int SIDEBAR_W = 150;
    private static final int HEADER_H = 42;
    private static final int CONTROL_H = 58;
    private static final int ROW_H = 40;
    private static final int NAV_ROW_H = 24, NAV_GAP = 2, NAV_TOP = 36;
    /** 网格卡片尺寸（DangoMusic GridPage：方形封面 + 两行标题） */
    private static final int GRID_GAP = 10;

    // ===== 侧栏 =====
    private final List<Object[]> navRowRects = new ArrayList<>(); // {int[]{x,y,w,h}, Tab}
    private final List<Object[]> sidebarPlRects = new ArrayList<>(); // {int[]{x,y,w,h}, NeteasePlaylist}
    private int sidebarScroll = 0;
    /** 侧栏搜索框内容（跨 init 保留） */
    private String searchTextCache = "";

    // ===== 页面状态 =====
    private Tab currentTab = Tab.HOME;
    private double mouseX, mouseY;
    private double scroll = 0;
    private double scrollMax = 0;
    /** 当前页入场动画起点（借鉴 DangoMusic 页面切换动画） */
    private long pageOpenAt = 0L;

    // ===== 首页 / 排行榜 =====
    private final List<NeteasePlaylist> recommendPls = new ArrayList<>();
    private boolean recommendLoading = false;
    private final List<NeteasePlaylist> chartPls = new ArrayList<>();
    private boolean chartsLoading = false;
    /** 首页网格命中区（点击打开歌单） */
    private final List<Object[]> gridHitRects = new ArrayList<>(); // {int[]{x,y,w,h}, NeteasePlaylist}
    private int[] dailyBtn = null;

    // ===== 搜索 =====
    private EditBox searchBox;
    private final List<NeteaseSong> searchResults = new ArrayList<>();
    private String searchStatus = "";
    private int searchStatusColor = 0xFFAAAAAA;
    private int[] searchNextBtn = null;

    // ===== 歌单 =====
    private final List<NeteasePlaylist> playlists = new ArrayList<>();
    private final List<NeteaseSong> playlistSongs = new ArrayList<>();
    private boolean playlistLoading = false;
    private NeteasePlaylist currentPlaylist;
    private boolean showPlaylistSongs = false;
    private int[] playAllBtn = null;
    private int[] backBtn = null;
    /** 歌单内筛选框（DangoMusic：Filter this list...） */
    private EditBox filterBox;
    private String filterCacheKey = "";
    private List<NeteaseSong> filterCache = new ArrayList<>();
    /** 定位后当前行闪烁 */
    private long flashId = -1;
    private long flashUntil = 0L;
    private int[] locateBtn = null;
    /** 列表 ↔ 封面墙切换（DangoMusic List/Covers 分段控件） */
    private boolean coverView = false;
    private float cfPos = 0f;
    private int cfTarget = 0;
    private static final float CF_SPEED = 0.20f;

    // ===== 滚动条/拖动 =====
    private boolean progDrag = false, volDrag = false;
    private int progBarX, progBarY, progBarW;
    private int volBarX, volBarY, volBarW;

    // ===== 播放栏 =====
    private int[] ctrlBtnXs = new int[5];
    private int[] likeBtn = null;

    // ===== 账号/登录 =====
    private NeteaseUser userDetail;
    private int loginMode = 0; // 0=选择界面, 1=扫码, 2=手机, 3=邮箱
    private String loginStatus = "";
    private int loginStatusColor = 0xFFCCCCCC;
    private EditBox phoneField, captchaField, countryCodeField, emailField, emailPwdField;
    private String qrKey;
    private java.util.Timer qrTimer;
    private java.awt.image.BufferedImage qrImage;

    public PlayerScreen() { super(Component.literal("Zephyr Music")); }

    @Override
    protected void init()
    {
        // ★ 毛玻璃：界面打开时对背后世界抓取一次模糊（面板静态，不逐帧模糊）
        BlurManager.requestBlur();
        pageOpenAt = System.currentTimeMillis();

        // ★ 侧栏常驻搜索框（借鉴 DangoMusic：搜索固定在侧栏顶部，回车即搜）
        searchBox = new EditBox(this.font, 26, 11, SIDEBAR_W - 52, 14, Component.literal(""));
        searchBox.setHint(Component.literal("搜索..."));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchTextCache);
        searchBox.setResponder(s -> searchTextCache = s);
        searchBox.visible = true;
        addRenderableWidget(searchBox);

        // ★ 歌单详情筛选框（DangoMusic：Filter this list...，按歌名/歌手过滤）
        boolean filterNeeded = currentTab == Tab.PLAYLIST && showPlaylistSongs;
        if (filterNeeded)
        {
            int fw = 110;
            filterBox = new EditBox(this.font, this.width - fw - 12, 9, fw, 14, Component.literal(""));
            filterBox.setHint(Component.literal("筛选本列表..."));
            filterBox.setMaxLength(40);
            filterBox.visible = true;
            addRenderableWidget(filterBox);
        }
        else filterBox = null;

        // 登录表单（ACCOUNT Tab 未登录时且需要输入框的模式才显示）
        if (currentTab == Tab.ACCOUNT && !(NeteaseSession.getInstance().isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie()) && (loginMode == 2 || loginMode == 3))
        {
            int contentW = this.width - SIDEBAR_W;
            int cx2 = SIDEBAR_W + contentW / 2;
            int fx = cx2 - 100;  // ★ 自适应：基于内容区域中心
            int fw = Math.min(200, contentW - 40);
            // 手机登录字段
            countryCodeField = new EditBox(this.font, fx, ACC_TOP + 44, 40, 16, Component.literal("86"));
            countryCodeField.setValue("86"); countryCodeField.setMaxLength(5);
            phoneField = new EditBox(this.font, fx + 44, ACC_TOP + 44, 156, 16, Component.literal(""));
            phoneField.setHint(Component.literal("手机号")); phoneField.setMaxLength(20);
            captchaField = new EditBox(this.font, fx, ACC_TOP + 68, 120, 16, Component.literal(""));
            captchaField.setHint(Component.literal("验证码")); captchaField.setMaxLength(8);
            // 邮箱登录字段
            emailField = new EditBox(this.font, fx, ACC_TOP + 44, fw, 16, Component.literal(""));
            emailField.setHint(Component.literal("邮箱")); emailField.setMaxLength(64);
            emailPwdField = new EditBox(this.font, fx, ACC_TOP + 68, fw, 16, Component.literal(""));
            emailPwdField.setHint(Component.literal("密码")); emailPwdField.setMaxLength(64);
            emailPwdField.setFormatter((s, i) -> net.minecraft.util.FormattedCharSequence.forward("*".repeat(s.length()), net.minecraft.network.chat.Style.EMPTY));

            // 可见性
            searchBox.visible = true;
            boolean showPhone = (loginMode == 2);
            boolean showEmail = (loginMode == 3);
            countryCodeField.visible = showPhone; phoneField.visible = showPhone; captchaField.visible = showPhone;
            emailField.visible = showEmail; emailPwdField.visible = showEmail;
            addRenderableWidget(countryCodeField); addRenderableWidget(phoneField); addRenderableWidget(captchaField);
            addRenderableWidget(emailField); addRenderableWidget(emailPwdField);

            // 登录 + 返回按钮（仅手机/邮箱模式）
            int btnX = cx2 - 40;
            addRenderableWidget(Button.builder(Component.literal("登录"), b -> doLogin())
                    .bounds(btnX, ACC_TOP + 92, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> { loginMode = 0; clearWidgets(); init(); })
                    .bounds(btnX, ACC_TOP + 116, 80, 16).build());
        }

        // Tab 切换时加载数据（借鉴 DangoMusic go(Page) 的懒加载）
        fetchPlaylists(); // ★ 侧栏“我的歌单”快捷列表在所有页都显示，开屏即拉取（带缓存）
        switch (currentTab)
        {
            case HOME -> loadRecommend();
            case CHARTS -> loadCharts();
            case PLAYLIST ->
            {
                if (showPlaylistSongs && playlistSongs.isEmpty() && !playlistLoading) loadPlaylistSongs();
            }
            default -> {}
        }
        if (currentTab == Tab.ACCOUNT && userDetail == null)
        {
            NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
            if (base != null && base.userId != 0)
                NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                    userDetail = NeteaseApi.parseUserDetail(resp, base);
                    if (userDetail != null) NeteaseSession.getInstance().updateCurrentUser(userDetail);
                });
            else if (com.zephyr.music.config.LoginStore.hasCookie())
            {
                // ★ 有 cookie 但还没验证出用户：自动验证登录态（含过期自动续期），
                //   完成后刷新界面，不再永远卡在"加载中..."
                ensureLoginVerified();
            }
            else userDetail = base;
        }
        // ★ 红心列表（借鉴 DangoMusic ♥）：登录后拉取一次（10 分钟缓存）
        if (NeteaseSession.getInstance().isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie())
            NeteaseSession.getInstance().ensureLikeList();
    }

    /** 内容区顶部（账号/设置/歌词页起始 y，正文坐标系） */
    private static final int ACC_TOP = HEADER_H + 6;

    /** 账号页自动登录验证去重标记 */
    private boolean loginVerifyStarted = false;
    /** 账号页错误态按钮命中区 {x,y,w,h}，绘制时更新 */
    private int[] errBtnVerify = null;
    private int[] errBtnRelogin = null;

    /** ★ 确保登录态已被验证（去重）；验证完成后回主线程刷新账号页 */
    private void ensureLoginVerified()
    {
        if (loginVerifyStarted) return;
        loginVerifyStarted = true;
        NeteaseSession s = NeteaseSession.getInstance();
        s.checkLoginStatus().whenComplete((ok, ex) -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen != this) return; // 屏幕已切换，不用刷新
            userDetail = ok && s.getCurrentUser() != null ? s.getCurrentUser() : null;
            errBtnVerify = null;
            errBtnRelogin = null;
            clearWidgets();
            init();
        }));
    }

    /** ★ 清除失效登录态并回到登录方式选择页 */
    private void relogin()
    {
        com.zephyr.music.config.LoginStore.clear();
        NeteaseSession.getInstance().resetLocalState();
        userDetail = null;
        loginVerifyStarted = false;
        loginMode = 0;
        errBtnVerify = null;
        errBtnRelogin = null;
        clearWidgets();
        init();
    }

    // ==================== 导航（借鉴 DangoMusic go(Page)） ====================

    private void switchTab(Tab tab)
    {
        currentTab = tab;
        scroll = 0; scrollMax = 0;
        if (tab != Tab.PLAYLIST) coverView = false; // ★ 封面墙只在歌单详情页保留，换页时复位
        pageOpenAt = System.currentTimeMillis();
        cfReset();
        clearWidgets(); init();
    }

    /** 歌单详情 → 歌单列表（Backspace / 返回胶囊） */
    private void goBackPage()
    {
        if (currentTab == Tab.PLAYLIST && showPlaylistSongs)
        {
            showPlaylistSongs = false;
            playlistSongs.clear();
            currentPlaylist = null;
            scroll = 0;
            pageOpenAt = System.currentTimeMillis();
            clearWidgets(); init();
        }
    }

    public void setCurrentTab(Tab tab) { this.currentTab = tab; }

    // ==================== 数据加载 ====================

    private void loadRecommend()
    {
        if (recommendLoading || !recommendPls.isEmpty()) return;
        recommendLoading = true;
        NeteaseSession.getInstance().getApi().personalized(30).thenAccept(resp -> {
            List<NeteasePlaylist> l = NeteaseApi.parsePlaylists(resp);
            Minecraft.getInstance().execute(() -> {
                recommendPls.clear(); recommendPls.addAll(l); recommendLoading = false;
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
                chartPls.clear(); chartPls.addAll(l); chartsLoading = false;
            });
        }).exceptionally(e -> { Minecraft.getInstance().execute(() -> chartsLoading = false); return null; });
    }

    private void fetchPlaylists()
    {
        NeteaseSession s = NeteaseSession.getInstance();
        if (s.isLoggedIn())
            s.fetchUserPlaylists().thenAccept(list -> Minecraft.getInstance().execute(() -> {
                playlists.clear(); playlists.addAll(list); }));
        else if (com.zephyr.music.config.LoginStore.hasCookie())
        {
            // F7 直开歌单页：先验证登录态（含过期自动续期）
            ensureLoginVerified();
            s.fetchUserPlaylists().thenAccept(list -> Minecraft.getInstance().execute(() -> {
                playlists.clear(); playlists.addAll(list); }));
        }
    }

    /** 打开歌单详情（id=-1 表示每日推荐，走 /recommend/songs） */
    private void openPlaylist(NeteasePlaylist pl)
    {
        currentPlaylist = pl;
        showPlaylistSongs = true;
        playlistSongs.clear();
        scroll = 0;
        pageOpenAt = System.currentTimeMillis();
        cfReset();
        clearWidgets(); init();
        loadPlaylistSongs();
    }

    private void loadPlaylistSongs()
    {
        if (currentPlaylist == null || playlistLoading) return;
        playlistLoading = true;
        if (currentPlaylist.id == -1)
        {
            // 每日推荐（需登录）
            NeteaseSession.getInstance().getApi().recommendSongs().thenAccept(resp -> {
                List<NeteaseSong> songs = NeteaseApi.parseDailySongs(resp);
                Minecraft.getInstance().execute(() -> {
                    playlistSongs.clear(); playlistSongs.addAll(songs);
                    playlistLoading = false; scroll = 0;
                });
            }).exceptionally(e -> { Minecraft.getInstance().execute(() -> playlistLoading = false); return null; });
            return;
        }
        NeteaseSession.getInstance().getApi().playlistTrackAll(currentPlaylist.id, 300, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            if (songs.isEmpty() && resp.has("playlist"))
            { JsonObject p = resp.getAsJsonObject("playlist"); if (p.has("tracks")) songs = NeteaseApi.parseSongs(p, "tracks"); }
            final List<NeteaseSong> fs = songs;
            Minecraft.getInstance().execute(() -> { playlistSongs.clear(); playlistSongs.addAll(fs); playlistLoading = false; scroll = 0; });
        }).exceptionally(e -> { Minecraft.getInstance().execute(() -> playlistLoading = false); return null; });
    }

    /** 首页"每日推荐"入口（合成歌单，id=-1） */
    private void openDaily()
    {
        NeteasePlaylist daily = new NeteasePlaylist();
        daily.id = -1;
        daily.name = "每日推荐";
        daily.subtitle = "根据你的听歌口味生成 · 30 首";
        openPlaylist(daily);
    }

    // ==================== 筛选 / 定位 / 封面墙 ====================

    /** 筛选后的歌曲列表（按歌名/歌手，借鉴 DangoMusic shown()） */
    private List<NeteaseSong> shownSongs()
    {
        String key = filterBox == null ? "" : filterBox.getValue().trim().toLowerCase();
        if (key.isEmpty()) return playlistSongs;
        if (key.equals(filterCacheKey)) return filterCache;
        List<NeteaseSong> out = new ArrayList<>();
        for (NeteaseSong s : playlistSongs)
            if (s.name.toLowerCase().contains(key) || s.getDisplayArtist().toLowerCase().contains(key)) out.add(s);
        filterCacheKey = key;
        filterCache = out;
        return out;
    }

    /** 跳到正在播放的那首：列表滚到居中并闪烁（借鉴 DangoMusic Locate） */
    private void locateCurrent()
    {
        List<NeteaseSong> shown = shownSongs();
        NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
        if (cur == null || shown.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).id == cur.id) { idx = i; break; }
        if (idx < 0) return;
        int bodyH = bodyHeight();
        scroll = Math.max(0, Math.min(idx * ROW_H - bodyH / 2.0, Math.max(0, shown.size() * ROW_H - bodyH)));
        flashId = cur.id;
        flashUntil = System.currentTimeMillis() + 1500;
        cfTarget = idx;
    }

    private void cfReset() { cfPos = 0f; cfTarget = 0; }

    private boolean usingCoverflow()
    {
        return coverView && currentTab == Tab.PLAYLIST && showPlaylistSongs
                || coverView && (currentTab == Tab.QUEUE || currentTab == Tab.SEARCH);
    }

    private int bodyTop() { return HEADER_H; }
    private int bodyBottom() { return this.height - CONTROL_H; }
    private int bodyHeight() { return Math.max(40, bodyBottom() - bodyTop()); }
    private int bodyWidth() { return this.width - SIDEBAR_W; }

    // ==================== 登录 ====================
    private void doLogin()
    {
        if (loginMode == 2)
        {
            String phone = phoneField.getValue().trim();
            String ct = countryCodeField.getValue().trim();
            String cap = captchaField.getValue().trim();
            if (phone.isEmpty()) { loginStatus = "请输入手机号"; loginStatusColor = ModernUI.TEXT_DANGER; return; }
            loginStatus = "登录中..."; loginStatusColor = 0xFFCCCCCC;
            NeteaseSession.getInstance().getApi().loginCellphone(phone, "", cap, ct).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = ModernUI.TEXT_DANGER; }
                });
            });
        }
        else if (loginMode == 3)
        {
            String email = emailField.getValue().trim();
            String pwd = emailPwdField.getValue();
            if (email.isEmpty() || pwd.isEmpty()) { loginStatus = "请输入邮箱和密码"; loginStatusColor = ModernUI.TEXT_DANGER; return; }
            loginStatus = "登录中..."; loginStatusColor = 0xFFCCCCCC;
            NeteaseSession.getInstance().getApi().loginEmail(email, pwd).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = ModernUI.TEXT_DANGER; }
                });
            });
        }
    }

    private void startQrLogin()
    {
        loginStatus = "生成二维码..."; loginStatusColor = 0xFFCCCCCC; qrImage = null; qrKey = null;
        NeteaseSession.getInstance().getApi().qrKey().thenCompose(resp -> {
            String key = null;
            if (resp.has("data") && resp.getAsJsonObject("data").has("unikey") && !resp.getAsJsonObject("data").get("unikey").isJsonNull())
                key = resp.getAsJsonObject("data").get("unikey").getAsString();
            else if (resp.has("unikey") && !resp.get("unikey").isJsonNull())
                key = resp.get("unikey").getAsString();
            if (key == null || key.isEmpty())
            { loginStatus = "获取二维码key失败"; loginStatusColor = ModernUI.TEXT_DANGER; return CompletableFuture.completedFuture(new JsonObject()); }
            qrKey = key;
            ZephyrMusic.LOGGER.info("[Zephyr] QR key: {}", key);
            return NeteaseSession.getInstance().getApi().qrCreate(key);
        }).thenAccept(resp -> {
            ZephyrMusic.LOGGER.info("[Zephyr] qrCreate response keys: {}", resp.keySet());
            String qrimg = "";
            if (resp.has("data") && resp.getAsJsonObject("data").has("qrimg") && !resp.getAsJsonObject("data").get("qrimg").isJsonNull())
                qrimg = resp.getAsJsonObject("data").get("qrimg").getAsString();
            else if (resp.has("qrimg") && !resp.get("qrimg").isJsonNull())
                qrimg = resp.get("qrimg").getAsString();
            if (qrimg.isEmpty()) { loginStatus = "API未返回二维码"; loginStatusColor = ModernUI.TEXT_DANGER; return; }
            if (qrimg.startsWith("data:image"))
            {
                int comma = qrimg.indexOf(",");
                if (comma > 0) qrimg = qrimg.substring(comma + 1);
            }
            final String base64 = qrimg;
            try {
                byte[] bytes = Base64.getDecoder().decode(base64);
                ZephyrMusic.LOGGER.info("[Zephyr] Decoded QR image: {} bytes", bytes.length);
                final java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                if (img != null && img.getWidth() > 0)
                {
                    ZephyrMusic.LOGGER.info("[Zephyr] QR image loaded: {}x{}", img.getWidth(), img.getHeight());
                    Minecraft.getInstance().execute(() -> {
                        qrImage = img;
                        loginStatus = "请使用网易云App扫码"; loginStatusColor = 0xFF7DD3FC;
                        startQrPolling();
                    });
                }
                else
                {
                    ZephyrMusic.LOGGER.error("[Zephyr] ImageIO.read returned null or empty image");
                    Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败(null)"; loginStatusColor = ModernUI.TEXT_DANGER; });
                }
            } catch (Exception e) {
                ZephyrMusic.LOGGER.error("[Zephyr] QR decode failed: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败: " + e.getMessage(); loginStatusColor = ModernUI.TEXT_DANGER; });
            }
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.error("[Zephyr] startQrLogin failed", e);
            Minecraft.getInstance().execute(() -> { loginStatus = "二维码生成失败"; loginStatusColor = ModernUI.TEXT_DANGER; });
            return null;
        });
    }

    private void startQrPolling()
    {
        if (qrTimer != null) qrTimer.cancel();
        qrTimer = new java.util.Timer("Zephyr-QR", true);
        qrTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            int count = 0;
            public void run() {
                if (qrKey == null || qrTimer == null) return;
                if (++count > 60) { loginStatus = "二维码过期"; loginStatusColor = ModernUI.TEXT_DANGER; qrTimer.cancel(); qrTimer = null; return; }
                NeteaseSession.getInstance().getApi().qrCheck(qrKey).thenAccept(resp -> {
                    int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                    if (code == 800) { loginStatus = "二维码过期"; loginStatusColor = ModernUI.TEXT_DANGER; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } }
                    else if (code == 801) { loginStatus = "等待扫码..."; loginStatusColor = 0xFFCCCCCC; }
                    else if (code == 802) { loginStatus = "待确认..."; loginStatusColor = 0xFFFFFFAA; }
                    else if (code == 803) {
                        String cookie = NeteaseApi.extractCookie(resp);
                        if (cookie != null && !cookie.isEmpty()) {
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
        loginMode = 0; loginStatus = ""; qrImage = null;
        if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; }
        // ★ 重新登录后强制重新验证登录态并刷新用户详情
        loginVerifyStarted = false;
        userDetail = null;
        // 红心列表
        NeteaseSession.getInstance().ensureLikeList();
        // 侧栏歌单缓存失效，重新拉取
        playlists.clear();
        // 加载用户详情
        NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
        if (base != null && base.userId != 0)
            NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                userDetail = NeteaseApi.parseUserDetail(resp, base);
                if (userDetail != null) NeteaseSession.getInstance().updateCurrentUser(userDetail);
            });
        clearWidgets(); init();
    }

    // ==================== 搜索 ====================
    private void doSearch()
    {
        if (searchBox == null) return;
        String kw = searchBox.getValue().trim();
        if (kw.isEmpty()) { searchStatus = "请输入关键词"; searchStatusColor = 0xFFFF453A; return; }
        // ★ 从侧栏发起搜索时自动跳到搜索页
        if (currentTab != Tab.SEARCH) switchTab(Tab.SEARCH);
        searchStatus = "搜索中..."; searchStatusColor = ModernUI.TEXT_SECONDARY; searchResults.clear();
        pageOpenAt = System.currentTimeMillis();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            Minecraft.getInstance().execute(() -> {
                searchResults.clear(); searchResults.addAll(songs); scroll = 0;
                searchStatus = songs.isEmpty() ? "无结果" : "找到 " + songs.size() + " 首";
                searchStatusColor = songs.isEmpty() ? ModernUI.TEXT_DANGER : ZephyrConfig.getAccentColor();
            });
        });
    }

    private void addSelectedToNext()
    {
        if (!searchResults.isEmpty())
        { MusicPlayer.getInstance().playNext(searchResults.get(0)); searchStatus = "已设为下一首"; searchStatusColor = ZephyrConfig.getAccentColor(); }
    }

    /** 侧栏 ⌕ 按钮命中区 */
    private boolean hitSidebarSearchBtn(double mx, double my)
    {
        return mx >= SIDEBAR_W - 26 && mx <= SIDEBAR_W - 8 && my >= 8 && my <= 28;
    }

    // ==================== 入场动画（借鉴 DangoMusic Ui.itemAnim/headerAnim） ====================

    /** easeOutCubic：t∈0..1 */
    private float easeOut(float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** 条目 i 的入场进度：从下方 12px 升起淡入，逐个错开 30ms */
    private float itemProgress(int i)
    {
        float t = (System.currentTimeMillis() - pageOpenAt - 40 - Math.min(i, 12) * 30f) / 340f;
        return easeOut(t);
    }

    /** 页头入场进度：从右侧 12px 滑入淡入 */
    private float headerProgress()
    {
        return easeOut((System.currentTimeMillis() - pageOpenAt) / 300f);
    }

    // ==================== 渲染 ====================
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta)
    {
        this.mouseX = mx; this.mouseY = my;
        BlurManager.runPending();

        // ★ 毛玻璃背景：模糊后的世界画面 + 中性暗色蒙版（不再使用原生黑色渐变背景）
        drawGlassBackdrop(g);

        // ★ 左侧边栏不在裁剪区域内（独立渲染）
        drawSidebar(g);

        // ★ 右侧正文：裁剪 + 滚动偏移（页头在裁剪区之外固定绘制 —— 修复页头随内容滚走的错位）
        g.enableScissor(SIDEBAR_W, bodyTop(), this.width, bodyBottom());
        g.pose().pushPose();
        if (!usingCoverflow()) g.pose().translate(0, -scroll, 0); // 封面墙自身不滚动

        switch (currentTab)
        {
            case HOME -> drawGridBody(g, "recommend");
            case CHARTS -> drawGridBody(g, "charts");
            case PLAYLIST -> drawPlaylistBody(g);
            case SEARCH -> drawTrackListBody(g, shown2(searchResults));
            case QUEUE -> drawTrackListBody(g, MusicPlayer.getInstance().getQueue());
            case LYRICS -> drawLyrics(g);
            case SETTINGS -> drawSettings(g);
            case ACCOUNT -> drawAccount(g);
        }

        g.pose().popPose();
        g.disableScissor();

        // ★ 固定页头（绝对坐标，不随滚动）
        drawHeader(g);

        drawControlBar(g);
        super.render(g, mx, my, delta);
    }

    private List<NeteaseSong> shown2(List<NeteaseSong> in) { return in; }

    /** 毛玻璃全屏背景：模糊图（若有）+ 暗色蒙版 */
    private void drawGlassBackdrop(GuiGraphics g)
    {
        if (BlurManager.hasBlur())
        {
            // 直接把整屏模糊图铺底（对齐世界画面，形成毛玻璃观感）
            BlurManager.drawBlurredRegion(0, 0, this.width, this.height);
        }
        g.fillGradient(0, 0, this.width, this.height, 0x88111113, 0xB4111113);
    }

    // ==================== 固定页头（借鉴 DangoMusic drawHeader：标题区 + 操作胶囊） ====================

    private void drawHeader(GuiGraphics g)
    {
        int accent = ZephyrConfig.getAccentColor();
        int x0 = SIDEBAR_W + 12;
        float hp = headerProgress();
        int hdx = (int) ((1f - hp) * 10f);
        int ca = (int) (0xF5 * hp) << 24; // 标题主色 alpha

        // ★ 每帧重置页头按钮命中区（只在对应页设置，避免切页后残留可点击）
        backBtn = null; playAllBtn = null; locateBtn = null; searchNextBtn = null; dailyBtn = null;

        // 页头底部分隔线（发丝线）
        g.fill(SIDEBAR_W, HEADER_H - 1, this.width, HEADER_H, ModernUI.HAIRLINE);

        switch (currentTab)
        {
            case HOME ->
            {
                g.drawString(this.font, Component.literal("欢迎来到 Zephyr Music"), x0 + hdx, 8, ca | 0xFFFFFF, false);
                String sub = recommendLoading && recommendPls.isEmpty() ? "加载推荐歌单..."
                        : "推荐歌单 · " + recommendPls.size() + (NeteaseSession.getInstance().isLoggedIn() ? "" : " · 登录后可听每日推荐");
                g.drawString(this.font, Component.literal(sub), x0 + hdx, 21, ModernUI.TEXT_DIM, false);
                dailyBtn = null;
                if (NeteaseSession.getInstance().isLoggedIn())
                {
                    String label = "☀ 每日推荐";
                    int bw = this.font.width(label) + 20;
                    int bx = this.width - bw - 12, by = 10;
                    boolean hv = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 20;
                    ModernUI.drawPillButton(g, bx, by, bw, 20, label, hv, true);
                    dailyBtn = new int[]{bx, by, bw, 20};
                }
            }
            case CHARTS ->
            {
                g.drawString(this.font, Component.literal("排行榜"), x0 + hdx, 8, ca | 0xFFFFFF, false);
                String sub = chartsLoading && chartPls.isEmpty() ? "加载官方榜单..."
                        : "官方榜单 · " + chartPls.size();
                g.drawString(this.font, Component.literal(sub), x0 + hdx, 21, ModernUI.TEXT_DIM, false);
            }
            case PLAYLIST ->
            {
                if (showPlaylistSongs && currentPlaylist != null) drawPlaylistDetailHeader(g, x0 + hdx, accent);
                else
                {
                    g.drawString(this.font, Component.literal("我的歌单"), x0 + hdx, 8, ca | 0xFFFFFF, false);
                    NeteaseSession.LoginState pst = NeteaseSession.getInstance().getLoginState();
                    String sub;
                    if (!playlists.isEmpty()) sub = "共 " + playlists.size() + " 个歌单";
                    else if (NeteaseSession.getInstance().isLoggedIn() || pst == NeteaseSession.LoginState.VERIFYING) sub = "加载歌单...";
                    else if (pst == NeteaseSession.LoginState.INVALID) sub = "登录态已失效，请到【账号】页重新登录";
                    else if (pst == NeteaseSession.LoginState.UNKNOWN) sub = "网络异常，无法加载歌单";
                    else sub = "登录后同步你的网易云歌单";
                    g.drawString(this.font, Component.literal(sub), x0 + hdx, 21, ModernUI.TEXT_DIM, false);
                }
            }
            case SEARCH ->
            {
                String kw = searchBox != null ? searchBox.getValue().trim() : "";
                g.drawString(this.font, Component.literal(kw.isEmpty() ? "搜索" : "搜索 “" + trunc(kw, 18) + "”"), x0 + hdx, 8, ca | 0xFFFFFF, false);
                g.drawString(this.font, Component.literal(searchStatus), x0 + hdx, 21, searchStatusColor, false);
                if (!searchResults.isEmpty())
                {
                    boolean nhv = mouseX >= this.width - 78 && mouseX <= this.width - 14 && mouseY >= 8 && mouseY <= 26;
                    ModernUI.drawPillButton(g, this.width - 78, 8, 64, 18, "＋下一首", nhv, false);
                    searchNextBtn = new int[]{this.width - 78, 8, 64, 18};
                }
                else searchNextBtn = null;
            }
            case QUEUE ->
            {
                MusicPlayer p = MusicPlayer.getInstance();
                List<NeteaseSong> q = p.getQueue();
                g.drawString(this.font, Component.literal("正在播放"), x0 + hdx, 8, ca | 0xFFFFFF, false);
                g.drawString(this.font, Component.literal(q.isEmpty() ? "队列是空的，去搜索或歌单里挑几首吧"
                        : "共 " + q.size() + " 首 · 第 " + (p.getQueueIndex() + 1) + " 首"), x0 + hdx, 21, ModernUI.TEXT_DIM, false);
            }
            case LYRICS -> g.drawString(this.font, Component.literal("歌词"), x0 + hdx, 8, ca | 0xFFFFFF, false);
            case SETTINGS -> g.drawString(this.font, Component.literal("设置"), x0 + hdx, 8, ca | 0xFFFFFF, false);
            case ACCOUNT -> { /* 账号页自带标题 */ }
        }
    }

    /** 歌单详情页头：返回 + 封面 + 标题/数量 + 全部播放 + 定位 + 列表/封面墙 */
    private void drawPlaylistDetailHeader(GuiGraphics g, int x0, int accent)
    {
        // 返回胶囊
        boolean bhv = mouseX >= x0 && mouseX <= x0 + 52 && mouseY >= 8 && mouseY <= 26;
        ModernUI.drawPillButton(g, x0, 8, 52, 18, "← 返回", bhv, false);
        backBtn = new int[]{x0, 8, 52, 18};
        int x = x0 + 62;

        // 歌单封面缩略图
        renderCover(g, currentPlaylist.coverImgUrl, x, 6, 28);
        x += 36;

        // 标题 + 数量
        String title = trunc(currentPlaylist.name, 22);
        g.drawString(this.font, Component.literal(title), x, 6, ModernUI.TEXT_PRIMARY, false);
        String sub = playlistLoading && playlistSongs.isEmpty() ? "加载歌曲..."
                : playlistSongs.size() + " 首" + (currentPlaylist.subtitle.isEmpty() ? "" : " · " + trunc(currentPlaylist.subtitle, 14));
        g.drawString(this.font, Component.literal(sub), x, 18, ModernUI.TEXT_DIM, false);
        x += this.font.width(title) + 110;

        // 全部播放胶囊（DangoMusic Play all）
        if (!playlistSongs.isEmpty())
        {
            boolean phv = mouseX >= x && mouseX <= x + 84 && mouseY >= 8 && mouseY <= 26;
            ModernUI.drawPillButton(g, x, 8, 84, 18, "▶ 全部播放", phv, true);
            playAllBtn = new int[]{x, 8, 84, 18};
        }
        else playAllBtn = null;

        // 定位当前歌曲（列表里有当前歌时显示）
        locateBtn = null;
        NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
        if (cur != null && !playlistSongs.isEmpty() && !coverView)
        {
            boolean inList = false;
            for (NeteaseSong s : playlistSongs) if (s.id == cur.id) { inList = true; break; }
            if (inList)
            {
                int lx = this.width - 218;
                boolean lhv = mouseX >= lx && mouseX <= lx + 58 && mouseY >= 8 && mouseY <= 26;
                ModernUI.drawPillButton(g, lx, 8, 58, 18, "◎ 定位", lhv, false);
                locateBtn = new int[]{lx, 8, 58, 18};
            }
        }

        // 列表 / 封面墙 分段控件（DangoMusic List | Covers）
        int segW = 92, segX = this.width - segW - 12;
        ModernUI.fillRound(g, segX, 8, segW, 18, 9, ModernUI.CARD);
        int halfW = segW / 2;
        int pillX = coverView ? segX + halfW : segX;
        ModernUI.fillRound(g, pillX, 8, halfW, 18, 9, ModernUI.withAlpha(accent, 0x60));
        g.drawString(this.font, Component.literal("列表"), segX + (halfW - this.font.width("列表")) / 2, 13,
                !coverView ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY, false);
        g.drawString(this.font, Component.literal("封面"), segX + halfW + (halfW - this.font.width("封面")) / 2, 13,
                coverView ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY, false);

        // 筛选框底座（EditBox 控件由 super.render 绘制）
        if (filterBox != null)
        {
            int fw = 110, fx = segX - fw - 8;
            ModernUI.fillRound(g, fx - 3, 7, fw + 6, 20, 9, ModernUI.CARD);
            ModernUI.strokeRound(g, fx - 3, 7, fw + 6, 20, 9,
                    filterBox.isFocused() ? ModernUI.withAlpha(accent, 0x50) : ModernUI.HAIRLINE, 1);
            g.drawString(this.font, Component.literal("⌕"), fx - 1, 11, ModernUI.TEXT_DIM, false);
        }
    }

    // ==================== 侧栏（借鉴 DangoMusic Sidebar：分组导航 + 我的歌单） ====================

    private void drawSidebar(GuiGraphics g)
    {
        int accent = ZephyrConfig.getAccentColor();
        ModernUI.glassPanel(g, 0, 0, SIDEBAR_W, this.height, 0, ModernUI.GLASS_DARK, 0.5f, true);
        g.fill(SIDEBAR_W - 1, 0, SIDEBAR_W, this.height, ModernUI.HAIRLINE);

        // 搜索框（EditBox 是 widget，自身渲染）+ 底座 + ⌕ 按钮
        ModernUI.fillRound(g, 8, 8, SIDEBAR_W - 16, 20, 10, ModernUI.CARD);
        ModernUI.strokeRound(g, 8, 8, SIDEBAR_W - 16, 20, 10,
                searchBox != null && searchBox.isFocused() ? ModernUI.withAlpha(accent, 140) : ModernUI.HAIRLINE, 1);
        g.drawString(this.font, Component.literal("⌕"), 12, 12, ModernUI.TEXT_DIM, false);
        boolean schv = hitSidebarSearchBtn(mouseX, mouseY);
        g.drawString(this.font, Component.literal("⌕"), SIDEBAR_W - 22, 12,
                schv ? accent : ModernUI.TEXT_DIM, false);

        navRowRects.clear();
        sidebarPlRects.clear();
        int y = NAV_TOP;

        // 发现（DangoMusic DISCOVER）
        y = drawSectionLabel(g, "发现", y);
        y = drawNavRow(g, Tab.HOME, "⌂", "首页", y);
        y = drawNavRow(g, Tab.CHARTS, "▤", "排行榜", y);
        y = drawNavRow(g, Tab.QUEUE, "♫", "正在播放", y);
        y = drawNavRow(g, Tab.LYRICS, "♪", "歌词", y);
        y += 6;
        // 音乐库
        y = drawSectionLabel(g, "音乐库", y);
        y = drawNavRow(g, Tab.PLAYLIST, "≡", "歌单", y);
        y = drawNavRow(g, Tab.SEARCH, "⌕", "搜索", y);
        y += 6;
        // 系统
        y = drawSectionLabel(g, "系统", y);
        y = drawNavRow(g, Tab.SETTINGS, "⚙", "设置", y);
        y = drawNavRow(g, Tab.ACCOUNT, "♥", "账号", y);
        y += 6;

        // ★ 我的歌单快捷列表（DangoMusic MY PLAYLISTS：侧栏直达歌单，滚轮滚动）
        int chY = this.height - 34;
        int listBottom = chY - 6;
        y = drawSectionLabel(g, "我的歌单", y);
        int availH = listBottom - y;
        int rowH = 22;
        int visibleRows = Math.max(0, availH / rowH);
        if (!playlists.isEmpty() && visibleRows > 0)
        {
            int maxScroll = Math.max(0, playlists.size() - visibleRows);
            sidebarScroll = Math.max(0, Math.min(sidebarScroll, maxScroll));
            for (int i = sidebarScroll; i < Math.min(playlists.size(), sidebarScroll + visibleRows); i++)
            {
                NeteasePlaylist pl = playlists.get(i);
                boolean active = currentPlaylist != null && currentPlaylist.id == pl.id && showPlaylistSongs;
                boolean hovered = mouseX >= 8 && mouseX <= SIDEBAR_W - 8 && mouseY >= y && mouseY <= y + rowH;
                if (active)
                {
                    ModernUI.fillRound(g, 8, y, SIDEBAR_W - 16, rowH - 2, 8, ModernUI.CARD_HOVER);
                    ModernUI.strokeRound(g, 8, y, SIDEBAR_W - 16, rowH - 2, 8, ModernUI.withAlpha(accent, 0x40), 1);
                }
                else if (hovered) ModernUI.fillRound(g, 8, y, SIDEBAR_W - 16, rowH - 2, 8, ModernUI.CARD);
                g.drawString(this.font, Component.literal("≡"), 14, y + 7,
                        active ? accent : ModernUI.TEXT_DIM, false);
                g.drawString(this.font, Component.literal(trunc(pl.name, 9)), 28, y + 7,
                        active ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY, false);
                sidebarPlRects.add(new Object[]{new int[]{8, y, SIDEBAR_W - 16, rowH - 2}, pl});
                y += rowH;
            }
        }
        else if (visibleRows > 0)
        {
            NeteaseSession.LoginState pst = NeteaseSession.getInstance().getLoginState();
            String msg = NeteaseSession.getInstance().isLoggedIn() ? "加载歌单..."
                    : pst == NeteaseSession.LoginState.VERIFYING ? "正在验证…"
                    : pst == NeteaseSession.LoginState.NOT_LOGGED_IN || pst == NeteaseSession.LoginState.INVALID ? "未登录"
                    : "网络异常";
            g.drawString(this.font, Component.literal(msg), 12, y + 2, ModernUI.TEXT_DIM, false);
        }

        // 底部账号 chip（DangoMusic：头像 + 昵称，点击进账号页）
        ModernUI.fillRound(g, 8, chY, SIDEBAR_W - 16, 26, 10, ModernUI.CARD);
        NeteaseUser cu = NeteaseSession.getInstance().getCurrentUser();
        if (cu != null && cu.avatarUrl != null && !cu.avatarUrl.isEmpty())
        {
            renderCover(g, cu.avatarUrl, 12, chY + 3, 20);
            String nick = trunc(cu.nickname, 7);
            g.drawString(this.font, Component.literal(nick), 38, chY + 9, ModernUI.TEXT_PRIMARY, false);
        }
        else
        {
            ModernUI.fillRound(g, 12, chY + 3, 20, 20, 10, 0xFF1C1C20);
            g.drawString(this.font, Component.literal("♪"), 12 + 7, chY + 10, ModernUI.TEXT_DIM, false);
            g.drawString(this.font, Component.literal(
                    com.zephyr.music.config.LoginStore.hasCookie() ? "验证中…" : "点击登录"),
                    38, chY + 9, ModernUI.TEXT_SECONDARY, false);
        }
    }

    /** 分组小标题（DangoMusic：10px 弱化文字） */
    private int drawSectionLabel(GuiGraphics g, String label, int y)
    {
        g.drawString(this.font, Component.literal(label), 12, y, ModernUI.TEXT_DIM, false);
        return y + 14;
    }

    /** 单个导航行：左侧图标 + 名称，选中态胶囊（返回下一行 y） */
    private int drawNavRow(GuiGraphics g, Tab tab, String icon, String label, int y)
    {
        int accent = ZephyrConfig.getAccentColor();
        if (tab == Tab.ACCOUNT)
        {
            NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
            if (NeteaseSession.getInstance().isLoggedIn() && u != null && u.nickname != null && !u.nickname.isEmpty())
                label = trunc(u.nickname, 7);
        }
        int x1 = 8, x2 = SIDEBAR_W - 8;
        boolean selected = currentTab == tab;
        boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y && mouseY <= y + NAV_ROW_H;
        if (selected)
        {
            ModernUI.fillRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.CARD_HOVER);
            ModernUI.strokeRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.withAlpha(accent, 0x30), 1);
        }
        else if (hovered)
        {
            ModernUI.fillRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.CARD);
        }
        g.drawString(this.font, Component.literal(icon), x1 + 8, y + 8,
                selected ? accent : ModernUI.TEXT_DIM, false);
        g.drawString(this.font, Component.literal(label), x1 + 24, y + 8,
                selected ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY, false);
        navRowRects.add(new Object[]{new int[]{x1, y, x2 - x1, NAV_ROW_H}, tab});
        return y + NAV_ROW_H + NAV_GAP;
    }

    // ==================== 首页 / 排行榜：歌单封面网格（借鉴 DangoMusic GridPage） ====================

    private void drawGridBody(GuiGraphics g, String which)
    {
        List<NeteasePlaylist> items = "recommend".equals(which) ? recommendPls : chartPls;
        boolean loading = "recommend".equals(which) ? recommendLoading : chartsLoading;
        int accent = ZephyrConfig.getAccentColor();
        gridHitRects.clear();

        int bodyX = SIDEBAR_W + 10, bodyW = bodyWidth() - 20;
        int bodyTop = bodyTop(), bodyBot = bodyBottom();
        if (items.isEmpty())
        {
            if (loading)
            {
                String tip = "加载中...";
                g.drawString(this.font, Component.literal(tip), bodyX + 2, bodyTop + 10, ModernUI.TEXT_DIM, false);
                ModernUI.drawThinBar(g, bodyX + 2, bodyTop + 24, 200, 3, -1);
            }
            else
            {
                String tip = "recommend".equals(which) ? "暂时没有推荐内容，稍后再试试"
                        : "暂时没有榜单数据，稍后再试试";
                g.drawCenteredString(this.font, Component.literal(tip), bodyX + bodyW / 2, bodyTop + bodyHeight() / 3, ModernUI.TEXT_DIM);
            }
            return;
        }

        // 自适应列数（DangoMusic：cell ≈160 逻辑像素，这里 ≈ 130 GUI 像素）
        int cols = Math.max(2, (bodyW + GRID_GAP) / (130 + GRID_GAP));
        int cellW = (bodyW - GRID_GAP * (cols - 1)) / cols;
        int rowH = cellW + 28;
        int totalRows = (items.size() + cols - 1) / cols;
        double contentH = totalRows * rowH;
        scrollMax = Math.max(0, contentH - bodyHeight());
        scroll = Math.max(0, Math.min(scroll, scrollMax));

        int startRow = (int) (scroll / rowH);
        int endRow = Math.min(totalRows - 1, (int) ((scroll + bodyHeight()) / rowH) + 1);
        for (int r = startRow; r <= endRow; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                int idx = r * cols + c;
                if (idx >= items.size()) break;
                NeteasePlaylist pl = items.get(idx);
                int x = bodyX + c * (cellW + GRID_GAP);
                int y = bodyTop + r * rowH;
                drawGridCell(g, pl, x, y, cellW, rowH, idx - startRow * cols, accent);
            }
        }
    }

    private void drawGridCell(GuiGraphics g, NeteasePlaylist pl, int x, int y, int cellW, int rowH, int animIdx, int accent)
    {
        float p = itemProgress(animIdx);
        if (p <= 0.01f) return;
        int dy = (int) ((1f - p) * 10f);
        y += dy;
        boolean hovered = mouseX >= x && mouseX <= x + cellW && mouseY >= y && mouseY <= y + rowH;
        if (hovered)
        {
            ModernUI.fillRound(g, x - 4, y - 4, cellW + 8, rowH, 12, ModernUI.CARD_HOVER);
        }
        // 封面（悬停时右下角 ▶ 播放钮，借鉴 DangoMusic）
        float cp = Math.max(0.35f, p);
        RenderSystem.setShaderColor(1f, 1f, 1f, cp);
        renderCover(g, pl.coverImgUrl, x, y, cellW);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        if (hovered)
        {
            ModernUI.fillRound(g, x, y, cellW, cellW, 0, 0x28000000);
            int pb = 24, px = x + cellW - pb - 6, py = y + cellW - pb - 6;
            ModernUI.fillRound(g, px, py, pb, pb, pb / 2, accent);
            g.drawCenteredString(this.font, Component.literal("▶"), px + pb / 2, py + 4, 0xFFFFFFFF);
        }
        // 两行标题（DangoMusic：封面下方 2 行歌单名）
        int ta = (int) (0xF5 * p) << 24;
        String name = pl.name == null ? "" : pl.name;
        g.drawString(this.font, Component.literal(trunc(name, cellW / 6)), x + 2, y + cellW + 4, ta | 0xFFFFFF, false);
        String sub = !pl.subtitle.isEmpty() ? pl.subtitle
                : pl.creatorName != null && !pl.creatorName.isEmpty() ? pl.creatorName
                : pl.trackCount > 0 ? pl.trackCount + " 首" : "";
        if (!sub.isEmpty())
            g.drawString(this.font, Component.literal(trunc(sub, cellW / 6)), x + 2, y + cellW + 16, ModernUI.TEXT_DIM, false);
        gridHitRects.add(new Object[]{new int[]{x, y, cellW, rowH - 24}, pl});
    }

    // ==================== 歌单正文（列表 ↔ 封面墙） ====================

    private void drawPlaylistBody(GuiGraphics g)
    {
        if (showPlaylistSongs && currentPlaylist != null)
        {
            List<NeteaseSong> shown = shownSongs();
            if (coverView) drawCoverflowBody(g, shown);
            else drawTrackListBody(g, shown);
            return;
        }
        // 歌单列表行：封面缩略图 + 名称 + 数量
        if (playlists.isEmpty())
        {
            NeteaseSession.LoginState pst = NeteaseSession.getInstance().getLoginState();
            String msg = NeteaseSession.getInstance().isLoggedIn() || pst == NeteaseSession.LoginState.VERIFYING ? "加载歌单..."
                    : pst == NeteaseSession.LoginState.INVALID ? "登录态已失效，请到【账号】页重新登录"
                    : pst == NeteaseSession.LoginState.UNKNOWN ? "网络异常，无法加载歌单" : "未登录";
            g.drawCenteredString(this.font, Component.literal(msg), SIDEBAR_W + bodyWidth() / 2, bodyTop() + bodyHeight() / 3, ModernUI.TEXT_DIM);
            return;
        }
        double rowH = 44;
        scrollMax = Math.max(0, playlists.size() * rowH - bodyHeight());
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int start = (int) (scroll / rowH);
        for (int i = start; i < Math.min(playlists.size(), start + bodyHeight() / 44 + 2); i++)
        {
            NeteasePlaylist pl = playlists.get(i);
            int y = bodyTop() + (int) (i * rowH);
            drawPlaylistRow(g, pl, y, i);
        }
    }

    private void drawPlaylistRow(GuiGraphics g, NeteasePlaylist pl, int y, int animIdx)
    {
        float p = itemProgress(animIdx);
        if (p <= 0.01f) return;
        y += (int) ((1f - p) * 8f);
        boolean hv = mouseX >= SIDEBAR_W + 6 && mouseX <= this.width - 6 && mouseY + scroll >= y && mouseY + scroll <= y + 42;
        boolean active = currentPlaylist != null && currentPlaylist.id == pl.id && showPlaylistSongs;
        int accent = ZephyrConfig.getAccentColor();
        if (hv || active)
            ModernUI.fillRound(g, SIDEBAR_W + 6, y, bodyWidth() - 12, 42, 10,
                    active ? ModernUI.withAlpha(accent, 0x28) : ModernUI.CARD_HOVER);
        RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0.35f, p));
        renderCover(g, pl.coverImgUrl, SIDEBAR_W + 12, y + 5, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        int ta = (int) (0xF5 * p) << 24;
        g.drawString(this.font, Component.literal(trunc(pl.name, 34)), SIDEBAR_W + 54, y + 6, ta | 0xFFFFFF, false);
        String sub = (pl.trackCount > 0 ? pl.trackCount + " 首" : "") + (pl.creatorName != null && !pl.creatorName.isEmpty() ? " · " + pl.creatorName : "");
        if (!sub.isEmpty())
            g.drawString(this.font, Component.literal(trunc(sub, 34)), SIDEBAR_W + 54, y + 22, ModernUI.TEXT_DIM, false);
    }

    /**
     * 通用歌曲列表（搜索 / 队列 / 歌单详情，借鉴 DangoMusic drawTrackList）：
     * 序号 + 封面缩略图 + 标题/歌手两行 + 右侧时长；当前歌曲强调色 + 定位闪烁。
     */
    private void drawTrackListBody(GuiGraphics g, List<NeteaseSong> songs)
    {
        if (songs == null || songs.isEmpty())
        {
            String kw = filterBox != null ? filterBox.getValue().trim() : "";
            String msg;
            if (currentTab == Tab.PLAYLIST && showPlaylistSongs && !kw.isEmpty() && !playlistSongs.isEmpty())
                msg = "没有匹配 “" + trunc(kw, 14) + "” 的歌曲";
            else if (currentTab == Tab.QUEUE) msg = "队列是空的";
            else if (currentTab == Tab.PLAYLIST) msg = "歌单是空的";
            else msg = "没有结果";
            g.drawCenteredString(this.font, Component.literal(msg), SIDEBAR_W + bodyWidth() / 2, bodyTop() + bodyHeight() / 3, ModernUI.TEXT_DIM);
            scrollMax = 0;
            return;
        }
        scrollMax = Math.max(0, songs.size() * (double) ROW_H - bodyHeight());
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int contentX = SIDEBAR_W + 6;
        int contentW = bodyWidth() - 12;
        NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
        long flash = System.currentTimeMillis() < flashUntil ? flashId : -1;
        int start = (int) (scroll / ROW_H);
        int end = Math.min(songs.size(), start + bodyHeight() / ROW_H + 2);
        for (int i = start; i < end; i++)
        {
            NeteaseSong song = songs.get(i);
            int y = bodyTop() + i * ROW_H;
            drawSongRow(g, song, y, contentX, contentW, i + 1, i - start,
                    cur != null && cur.id == song.id, flash == song.id);
        }
    }

    /** 歌曲行（y 为虚拟坐标，render() 已 translate(-scroll)） */
    private void drawSongRow(GuiGraphics g, NeteaseSong song, int y, int contentX, int contentW, int index, int animIdx, boolean isCurrent, boolean isFlash)
    {
        if (y < bodyTop() - ROW_H || y > bodyBottom() + 4) return;
        float p = itemProgress(animIdx);
        if (p <= 0.01f) return;
        int accent = ZephyrConfig.getAccentColor();
        double hoverY = mouseY + scroll;
        boolean hv = mouseX >= contentX && mouseX <= contentX + contentW && hoverY >= y && hoverY <= y + ROW_H - 2;
        if (isFlash)
        {
            ModernUI.fillRound(g, contentX, y, contentW, ROW_H - 2, 10, ModernUI.withAlpha(accent, 0x30));
            ModernUI.strokeRound(g, contentX, y, contentW, ROW_H - 2, 10, accent, 1);
        }
        else if (isCurrent)
            ModernUI.fillRound(g, contentX, y, contentW, ROW_H - 2, 10, ModernUI.withAlpha(accent, 0x28));
        else if (hv)
            ModernUI.fillRound(g, contentX, y, contentW, ROW_H - 2, 10, ModernUI.CARD_HOVER);
        // 序号（当前播放中 → ♫，借鉴 DangoMusic）
        if (isCurrent && MusicPlayer.getInstance().isPlaying())
            g.drawString(this.font, Component.literal("♫"), contentX + 10, y + 14, accent, false);
        else
            g.drawString(this.font, Component.literal(String.valueOf(index)), contentX + 10, y + 14, ModernUI.TEXT_DIM, false);
        // 封面缩略图
        RenderSystem.setShaderColor(1f, 1f, 1f, Math.max(0.35f, p));
        renderCover(g, song.picUrl, contentX + 28, y + 3, 32);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // 标题 / 歌手
        int ta = (int) (0xF5 * p) << 24;
        String title = trunc(song.name, 42);
        g.drawString(this.font, Component.literal(title), contentX + 68, y + 5,
                isCurrent ? accent : (ta | 0xFFFFFF), false);
        g.drawString(this.font, Component.literal(trunc(song.getDisplayArtist(), 42)), contentX + 68, y + 21, ModernUI.TEXT_DIM, false);
        // 右侧时长
        String dur = song.getDisplayDuration();
        g.drawString(this.font, Component.literal(dur), contentX + contentW - 10 - this.font.width(dur), y + 14, ModernUI.TEXT_DIM, false);
    }

    // ==================== 封面墙（借鉴 DangoMusic Coverflow） ====================

    /**
     * 中间一张大封面，两边的依次变小、变淡、往后叠。
     * 滚轮 / ←→ / 点两侧切换，点中间那张播放。
     */
    private void drawCoverflowBody(GuiGraphics g, List<NeteaseSong> songs)
    {
        if (songs == null || songs.isEmpty()) return;
        cfTarget = Math.max(0, Math.min(cfTarget, songs.size() - 1));
        // 平滑逼近
        cfPos += (cfTarget - cfPos) * CF_SPEED;
        if (Math.abs(cfTarget - cfPos) < 0.001f) cfPos = cfTarget;

        int accent = ZephyrConfig.getAccentColor();
        int cx = SIDEBAR_W + bodyWidth() / 2;
        int cyMid = (bodyTop() + bodyBottom()) / 2 - 12;
        int size = Math.min(bodyHeight() - 70, 150);
        int spacing = (int) (size * 0.62f);

        for (int off = -4; off <= 4; off++)
        {
            int idx = (int) Math.round(cfPos) + off;
            if (idx < 0 || idx >= songs.size()) continue;
            float rel = idx - cfPos; // 带小数的相对位置
            float dist = Math.abs(rel);
            if (dist > 4.5f) continue;
            float t = Math.max(0f, 1f - dist / 4.5f);
            int sz = (int) (size * (0.55f + 0.45f * t));
            int alpha = (int) (110 + 145 * t);
            int x = (int) (cx + rel * spacing) - sz / 2;
            int y = cyMid - sz / 2 + (int) ((1f - t) * 8f);
            NeteaseSong song = songs.get(idx);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha / 255f);
            renderCover(g, song.picUrl, x, y, sz);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            if (dist < 0.5f)
            {
                ModernUI.strokeRound(g, x, y, sz, sz, 8, ModernUI.withAlpha(accent, 0x80), 1);
            }
        }

        // 中间歌曲信息
        NeteaseSong cur = songs.get((int) Math.round(cfPos));
        String title = trunc(cur.name, 30);
        g.drawCenteredString(this.font, Component.literal(title), cx, cyMid + size / 2 + 10,
                ModernUI.withAlpha(accent, 0xF0));
        g.drawCenteredString(this.font, Component.literal(trunc(cur.getDisplayArtist(), 34)), cx, cyMid + size / 2 + 24, ModernUI.TEXT_SECONDARY);
        g.drawCenteredString(this.font, Component.literal(((int) Math.round(cfPos) + 1) + " / " + songs.size()), cx, cyMid + size / 2 + 38, ModernUI.TEXT_DIM);
    }

    // ==================== 歌词页 ====================
    private void drawLyrics(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int cx = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
        int cy = (bodyTop() + bodyBottom()) / 2;
        if (song == null) { g.drawCenteredString(this.font, Component.literal("暂无播放"), cx, cy, ModernUI.TEXT_DIM); return; }
        double pos = p.getPositionSec();
        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics == null || lyrics.isEmpty()) { g.drawCenteredString(this.font, Component.literal("（暂无歌词）"), cx, cy, ModernUI.TEXT_DIM); return; }
        int cur = findCur(lyrics, pos);
        if (cur < 0) { g.drawCenteredString(this.font, Component.literal("♪ ~ ~ ~"), cx, cy, ZephyrConfig.getAccentColor()); return; }
        int lh = 20, max = 13, half = max / 2;
        for (int off = -half; off <= half; off++)
        {
            int idx = cur + off;
            if (idx < 0 || idx >= lyrics.size()) continue;
            LyricLine line = lyrics.get(idx);
            boolean act = off == 0;
            int ly = cy + off * lh - lh / 2;
            int alpha = act ? 255 : (int)(140 * (1 - Math.abs(off) / (double)(half + 1)));
            if (alpha < 40) alpha = 40;
            int color = act ? ZephyrConfig.getAccentColor() : ModernUI.withAlpha(0xFFCCCCCC, alpha);
            int maxW = this.width - SIDEBAR_W - 40;
            if (act && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                renderKaraoke(g, line, cx, ly, maxW, pos);
            else
            {
                String text = line.text;
                if (this.font.width(text) <= maxW)
                    g.drawCenteredString(this.font, Component.literal(text), cx, ly, color);
                else
                {
                    List<FormattedCharSequence> lines = this.font.split(Component.literal(text), maxW);
                    int sy = ly - (lines.size() - 1) * 5;
                    for (int li = 0; li < lines.size(); li++)
                        g.drawCenteredString(this.font, lines.get(li), cx, sy + li * 10, color);
                }
            }
        }
    }

    // ==================== 设置页（数据驱动，分组渲染，借鉴 DangoMusic SettingsPage） ====================

    private static class SettingItem
    {
        final String name;
        final java.util.function.Supplier<String> value;
        final boolean slider;
        final java.util.function.Supplier<Double> ratio;
        final java.util.function.DoubleConsumer onSlide;
        final Runnable onClick;

        SettingItem(String name, java.util.function.Supplier<String> value, Runnable onClick)
        { this(name, value, null, null, null); }

        SettingItem(String name, java.util.function.Supplier<String> value,
                    java.util.function.Supplier<Double> ratio, java.util.function.DoubleConsumer onSlide,
                    Runnable unusedClick)
        {
            this.name = name; this.value = value;
            this.slider = ratio != null; this.ratio = ratio; this.onSlide = onSlide; this.onClick = null;
        }
    }

    private static class SettingSection
    {
        final String title;
        final java.util.List<SettingItem> items = new ArrayList<>();
        SettingSection(String t) { title = t; }
    }

    private final java.util.List<SettingSection> settingSections = buildSettingSections();

    private static java.util.List<SettingSection> buildSettingSections()
    {
        java.util.List<SettingSection> out = new ArrayList<>();
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
                r -> { ZephyrConfig.HUD_VOLUME.set(r); mp.setVolume((float) r); }, null));
        play.items.add(new SettingItem("听歌打卡",
                () -> ZephyrConfig.SCROBBLE_ENABLED.get() ? "ON" : "OFF",
                () -> ZephyrConfig.SCROBBLE_ENABLED.set(!ZephyrConfig.SCROBBLE_ENABLED.get())));
        out.add(play);

        SettingSection hud = new SettingSection("HUD");
        hud.items.add(new SettingItem("显示 HUD",
                () -> ZephyrConfig.HUD_ENABLED.get() ? "ON" : "OFF",
                () -> ZephyrConfig.HUD_ENABLED.set(!ZephyrConfig.HUD_ENABLED.get())));
        // ★ 灵动岛（借鉴 DangoMusic Dynamic Island）
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
                () -> { MusicHudOverlay.toggleEditMode(); }));
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

    private void drawSettings(GuiGraphics g)
    {
        int x = SIDEBAR_W + 14;
        int cw = this.width - SIDEBAR_W - 28;
        int accent = ZephyrConfig.getAccentColor();

        int rowH = 20;
        int headerH = 22;
        int cardGap = 8;
        int totalH = 0;
        for (SettingSection s : settingSections) totalH += headerH + s.items.size() * rowH + 10 + cardGap;
        scrollMax = Math.max(0, totalH - (bodyBottom() - ACC_TOP));
        scroll = Math.max(0, Math.min(scroll, scrollMax));

        // ★ 虚拟坐标（render() 已 translate(-scroll)，此处不再自己减 scroll）
        double sy = ACC_TOP;
        for (SettingSection sec : settingSections)
        {
            // 分组卡片（借鉴 DangoMusic SettingsPage：圆角卡片内含标题 + 行）
            int cardH = headerH + sec.items.size() * rowH + 10;
            int cardY = (int) sy;
            ModernUI.fillRound(g, x - 8, cardY, cw + 16, cardH, 10, ModernUI.CARD);
            g.drawString(this.font, Component.literal(sec.title), x, cardY + 6, ModernUI.TEXT_PRIMARY, false);
            sy += headerH;
            for (SettingItem item : sec.items)
            {
                int iy = (int) sy;
                double hoverY = mouseY + scroll;
                boolean hovered = mouseX >= x - 4 && mouseX <= x + cw + 4 && hoverY >= iy - 1 && hoverY <= iy + rowH - 3;
                if (hovered) ModernUI.fillRound(g, x - 4, iy - 1, cw + 8, rowH, 6, ModernUI.CARD_HOVER);
                g.drawString(this.font, Component.literal(item.name), x, iy + 2, ModernUI.TEXT_SECONDARY, false);
                String val = item.value.get();
                if (item.slider)
                {
                    // 滑块行：中间强调色细条，右侧数值（DangoMusic 样式）
                    int slX = x + 150, slW = Math.max(40, x + cw - 56 - slX);
                    double ratio = item.ratio.get();
                    ratio = Math.max(0, Math.min(1, ratio));
                    // 保存滑块命中区（绝对坐标，供 toggleSetting 使用）
                    sliderHit[0] = slX; sliderHit[1] = iy; sliderHit[2] = slW; sliderHit[3] = rowH;
                    ModernUI.drawThinBar(g, slX, iy + 6, slW, 4, ratio);
                    g.drawString(this.font, Component.literal(val), x + cw - this.font.width(val), iy + 2,
                            ModernUI.TEXT_PRIMARY, false);
                }
                else
                {
                    boolean isOn = "ON".equals(val);
                    if (isOn || "OFF".equals(val))
                    {
                        // ★ iOS 风格开关（开=强调色）
                        ModernUI.drawIosToggle(g, x + cw - 32, iy - 1, isOn, hovered);
                    }
                    else
                    {
                        // 值选项（音质/锚点/F8）：小胶囊
                        int vw = this.font.width(val) + 14;
                        ModernUI.fillRound(g, x + cw - vw, iy, vw, 14, 7, ModernUI.CARD_HOVER);
                        g.drawCenteredString(this.font, Component.literal(val), x + cw - vw / 2, iy + 3, accent);
                    }
                    sliderHit[0] = 0; sliderHit[2] = 0;
                }
                lastSettingItem[0] = System.identityHashCode(item);
                sy += rowH;
            }
            sy += 10 + cardGap;
        }
    }

    private void drawAccount(GuiGraphics g)
    {
        int contentW = this.width - SIDEBAR_W;
        int cx2 = SIDEBAR_W + contentW / 2;
        int y = ACC_TOP;
        int accent = ZephyrConfig.getAccentColor(), text = 0xFFFFFFFF, dim = ModernUI.TEXT_DIM;

        // ★ 有 cookie 就认为已登录（即使 checkLoginStatus 还没执行）
        boolean hasCookie = com.zephyr.music.config.LoginStore.hasCookie();
        boolean loggedIn = NeteaseSession.getInstance().isLoggedIn() || hasCookie;

        // 已登录：显示用户信息
        if (loggedIn)
        {
            NeteaseUser u = userDetail != null ? userDetail : NeteaseSession.getInstance().getCurrentUser();
            if (u == null)
            {
                // ★ 不再无限"加载中..."：按登录态给出明确反馈
                NeteaseSession.LoginState st = NeteaseSession.getInstance().getLoginState();
                if (st == NeteaseSession.LoginState.VERIFYING)
                {
                    g.drawCenteredString(this.font, Component.literal("正在验证登录态…"), cx2, y + 40, dim);
                    return;
                }
                if (st == NeteaseSession.LoginState.INVALID || st == NeteaseSession.LoginState.NOT_LOGGED_IN)
                {
                    g.drawCenteredString(this.font, Component.literal("登录态已失效"), cx2, y + 24, ModernUI.TEXT_DANGER);
                    g.drawCenteredString(this.font, Component.literal("保存的登录凭证已过期，请重新登录"), cx2, y + 42, dim);
                    // 按钮：重新登录
                    int bw = 120, bx = cx2 - bw / 2, by = y + 70;
                    boolean hv = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 20;
                    ModernUI.fillRound(g, bx, by, bw, 20, 8, hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
                    ModernUI.strokeRound(g, bx, by, bw, 20, 8, ModernUI.withAlpha(accent, 0x50), 1);
                    g.drawCenteredString(this.font, Component.literal("重新登录"), cx2, by + 6, accent);
                    errBtnVerify = null;
                    errBtnRelogin = new int[]{bx, by, bw, 20};
                    return;
                }
                // UNKNOWN（网络异常）或其他：提供重试入口
                g.drawCenteredString(this.font, Component.literal("无法获取用户信息"), cx2, y + 24, ModernUI.TEXT_DANGER);
                g.drawCenteredString(this.font, Component.literal("网络异常或服务暂不可用"), cx2, y + 42, dim);
                int bw = 120, bx = cx2 - bw / 2, by = y + 70;
                boolean hv1 = mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + 20;
                ModernUI.fillRound(g, bx, by, bw, 20, 8, hv1 ? ModernUI.CARD_HOVER : ModernUI.CARD);
                ModernUI.strokeRound(g, bx, by, bw, 20, 8, ModernUI.withAlpha(accent, 0x50), 1);
                g.drawCenteredString(this.font, Component.literal("重新验证"), cx2, by + 6, accent);
                errBtnVerify = new int[]{bx, by, bw, 20};
                errBtnRelogin = null;
                return;
            }
            int avSz = 56, avX = cx2 - avSz / 2;
            renderCover(g, u.avatarUrl, avX, y, avSz);
            y += avSz + 8;
            g.drawCenteredString(this.font, Component.literal(u.nickname), cx2, y, text); y += 14;
            int ix = SIDEBAR_W + 20;
            g.drawString(this.font, Component.literal("ID: " + u.userId), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("听歌: " + (u.listenSongs > 0 ? u.listenSongs + "首" : "未知")), ix, y, accent, false); y += 16;
            g.drawString(this.font, Component.literal("等级: " + (u.level > 0 ? "Lv." + u.level : "未知")), ix, y, accent, false); y += 16;
            g.drawString(this.font, Component.literal("注册: " + fmtDate(u.createTime)), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("地区: " + RegionCodeMapper.formatLocation(u.province, u.city)), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("性别: " + (u.gender == 1 ? "男" : u.gender == 2 ? "女" : "保密")), ix, y, dim, false); y += 24;
            int btnW = 80, btnX = cx2 - btnW / 2;
            boolean hv = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= y && mouseY <= y + 18;
            ModernUI.fillRound(g, btnX, y, btnW, 18, 9, ModernUI.withAlpha(0xFFFF453A, hv ? 0x60 : 0x30));
            g.drawCenteredString(this.font, Component.literal("退出登录"), cx2, y + 5, ModernUI.TEXT_DANGER);
            accountLogoutBtn = new int[]{btnX, y, btnW, 18};
            return;
        }
        accountLogoutBtn = null;

        // 未登录
        if (!loggedIn && loginMode == 0)
        {
            // 选择登录方式
            g.drawCenteredString(this.font, Component.literal("网易云登录"), cx2, y, accent); y += 28;
            int btnW = 120, btnH = 20, btnX = cx2 - btnW / 2;
            String[] modes = {"扫码登录", "手机登录", "邮箱登录"};
            for (int i = 0; i < 3; i++)
            {
                boolean hv = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= y && mouseY <= y + btnH;
                ModernUI.fillRound(g, btnX, y, btnW, btnH, 10, hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
                g.drawCenteredString(this.font, Component.literal(modes[i]), cx2, y + 6, accent);
                y += btnH + 8;
            }
            return;
        }

        // 登录状态消息
        if (loginMode == 1)
        {
            // 扫码登录：标题 + 二维码 + 状态 + 返回按钮
            g.drawCenteredString(this.font, Component.literal("扫码登录"), cx2, y, accent); y += 20;
            if (qrImage != null)
            {
                int sz = 140, qx = cx2 - sz / 2;
                // 白色背景
                g.fill(qx - 4, y - 4, qx + sz + 4, y + sz + 4, 0xFFFFFFFF);
                // ★ 修复: ps 用浮点计算再取整，避免 140/180=0
                int imgW = qrImage.getWidth();
                int imgH = qrImage.getHeight();
                double psD = (double)sz / Math.max(imgW, imgH);
                int aw = (int)(imgW * psD);
                int ah = (int)(imgH * psD);
                int sx = qx + (sz - aw) / 2;
                int sy = y + (sz - ah) / 2;
                ZephyrMusic.LOGGER.debug("[Zephyr] QR render: img={}x{}, ps={}, area={}x{}, sx={}, sy={}", imgW, imgH, psD, aw, ah, sx, sy);
                for (int py = 0; py < imgH; py++)
                {
                    int drawY = sy + (int)(py * psD);
                    int drawH = sy + (int)((py + 1) * psD) - drawY;
                    if (drawH <= 0) drawH = 1;
                    for (int px = 0; px < imgW; px++)
                    {
                        if (((qrImage.getRGB(px, py) >> 16) & 0xFF) < 128)
                        {
                            int drawX = sx + (int)(px * psD);
                            int drawW = sx + (int)((px + 1) * psD) - drawX;
                            if (drawW <= 0) drawW = 1;
                            g.fill(drawX, drawY, drawX + drawW, drawY + drawH, 0xFF000000);
                        }
                    }
                }
                // 状态文字在二维码下方
                y += sz + 12;
                g.drawCenteredString(this.font, Component.literal(loginStatus.isEmpty() ? "请使用网易云App扫码" : loginStatus), cx2, y, loginStatusColor);
                y += 16;
                // 返回按钮（手动渲染，不在 init 中创建）
                int btnW = 80, btnX = cx2 - btnW / 2;
                boolean hv = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= y && mouseY <= y + 18;
                ModernUI.fillRound(g, btnX, y, btnW, 18, 9, hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
                g.drawCenteredString(this.font, Component.literal("← 返回"), cx2, y + 5, accent);
                qrBackBtn = new int[]{btnX, y, btnW, 18};
            }
            else
            {
                // 等待生成
                int ph = 140, px2 = cx2 - ph / 2;
                g.fill(px2 - 4, y - 4, px2 + ph + 4, y + ph + 4, 0xFFFFFFFF);
                g.drawCenteredString(this.font, Component.literal("..."), cx2, y + ph / 2 - 4, ModernUI.TEXT_DIM);
                y += ph + 12;
                g.drawCenteredString(this.font, Component.literal(loginStatus.isEmpty() ? "生成二维码中..." : loginStatus), cx2, y, loginStatusColor);
                qrBackBtn = null;
            }
        }
        else
        {
            qrBackBtn = null;
            if (loginMode == 2)
            {
                // 手机登录：标题 + 输入框（在 init 中创建）+ 状态
                g.drawCenteredString(this.font, Component.literal("手机登录"), cx2, y, accent);
                if (!loginStatus.isEmpty())
                    g.drawCenteredString(this.font, Component.literal(loginStatus), cx2, y + 70, loginStatusColor);
            }
            else if (loginMode == 3)
            {
                // 邮箱登录
                g.drawCenteredString(this.font, Component.literal("邮箱登录"), cx2, y, accent);
                if (!loginStatus.isEmpty())
                    g.drawCenteredString(this.font, Component.literal(loginStatus), cx2, y + 70, loginStatusColor);
            }
        }
    }

    /** 账号页"退出登录"/扫码返回按钮命中区（绘制时更新，绝对坐标） */
    private int[] accountLogoutBtn = null;
    private int[] qrBackBtn = null;

    // ==================== 底部播放栏（借鉴 DangoMusic PlayerBar + 红心） ====================

    private void drawControlBar(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int y = this.height - CONTROL_H;
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        int accent = ZephyrConfig.getAccentColor();
        // 玻璃背景
        ModernUI.glassPanel(g, cx2, y, cw, CONTROL_H, 0, ModernUI.GLASS_DARK, 0.55f, true);
        g.fill(cx2, y, this.width, y + 1, ModernUI.HAIRLINE);

        // 左：封面 + 歌名 / 歌手
        renderCover(g, song != null ? song.picUrl : "", cx2 + 10, y + 8, 38);
        int infoX = cx2 + 56;
        String title = song != null ? trunc(song.name, 18) : "未在播放";
        String artist = song != null ? trunc(song.getDisplayArtist(), 18) : "去侧栏搜索一首歌吧";
        g.drawString(this.font, Component.literal(title), infoX, y + 9, ModernUI.TEXT_PRIMARY, false);
        g.drawString(this.font, Component.literal(artist), infoX, y + 22, ModernUI.TEXT_SECONDARY, false);

        // ♥ 红心（DangoMusic PlayerBar：登录后显示，点击收藏/取消收藏）
        likeBtn = null;
        if (song != null && (NeteaseSession.getInstance().isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie()))
        {
            int lbX = infoX + 118, lbY = y + 9;
            boolean lhv = mouseX >= lbX && mouseX <= lbX + 22 && mouseY >= lbY && mouseY <= lbY + 22;
            boolean liked = NeteaseSession.getInstance().isLiked(song.id);
            if (lhv) ModernUI.fillRound(g, lbX - 3, lbY - 2, 28, 26, 8, ModernUI.CARD_HOVER);
            g.drawString(this.font, Component.literal("♥"), lbX, lbY + 2,
                    liked ? accent : ModernUI.TEXT_DIM, false);
            likeBtn = new int[]{lbX - 3, lbY - 2, 28, 26};
        }

        // 中：走带控制自适应布局（居中于信息区与音量区之间，窄屏不再与音量重叠）
        int centerL = infoX + (likeBtn != null ? 152 : 128);
        int centerR = this.width - 172;
        int cxm = (centerL + centerR) / 2;
        int by = y + 15;
        int[] xs = {cxm - 84, cxm - 42, cxm, cxm + 42, cxm + 84};
        drawCtrlBtn(g, "⏮", xs[0], by, mouseX >= xs[0] && mouseX < xs[0] + 28 && mouseY >= by - 4 && mouseY <= by + 20, false);
        drawCtrlBtn(g, p.isPaused() ? "▶" : "⏸", xs[1], by, mouseX >= xs[1] && mouseX < xs[1] + 28 && mouseY >= by - 4 && mouseY <= by + 20, false);
        drawCtrlBtn(g, "⏭", xs[2], by, mouseX >= xs[2] && mouseX < xs[2] + 28 && mouseY >= by - 4 && mouseY <= by + 20, false);
        drawCtrlBtn(g, "⇄", xs[3], by, mouseX >= xs[3] && mouseX < xs[3] + 28 && mouseY >= by - 4 && mouseY <= by + 20, p.isShuffleMode());
        drawCtrlBtn(g, "↻", xs[4], by, mouseX >= xs[4] && mouseX < xs[4] + 28 && mouseY >= by - 4 && mouseY <= by + 20, p.isLoopMode());
        ctrlBtnXs = xs;

        // 右：音量（喇叭 + 强调色细条 + 百分比）
        float vol = p.getVolume();
        g.drawString(this.font, Component.literal("♪"), this.width - 158, by + 5, ModernUI.TEXT_SECONDARY, false);
        volBarX = this.width - 146; volBarY = by + 8; volBarW = 78;
        ModernUI.drawThinBar(g, volBarX, volBarY, volBarW, 4, vol);
        String pct = Math.round(vol * 100) + "%";
        g.drawString(this.font, Component.literal(pct), volBarX + volBarW + 6, by + 5, ModernUI.TEXT_DIM, false);

        // 下：进度条（时间 | 细条 | 时间，可拖拽 seek，居中于走带区下方）
        double pos = p.getPositionSec();
        double total = song != null && song.duration > 0 ? song.duration / 1000.0 : 0;
        double prog = total > 0 ? Math.min(1, pos / total) : 0;
        String t1 = fmtTime(pos), t2 = fmtTime(total);
        int barY = y + 44;
        progBarX = centerL + 34;
        progBarW = Math.max(40, centerR - centerL - 68);
        progBarY = barY - 3;
        g.drawString(this.font, Component.literal(t1), centerL, barY - 10, ModernUI.TEXT_SECONDARY, false);
        ModernUI.drawThinBar(g, progBarX, barY, progBarW, 4, prog);
        g.drawString(this.font, Component.literal(t2), centerR - this.font.width(t2), barY - 10, ModernUI.TEXT_DIM, false);
    }

    private void drawCtrlBtn(GuiGraphics g, String label, int x, int y, boolean hovered, boolean active)
    {
        int accent = ZephyrConfig.getAccentColor();
        int bg = active ? ModernUI.withAlpha(accent, 40) : (hovered ? ModernUI.CARD_HOVER : 0x00FFFFFF);
        if (bg != 0) ModernUI.fillRound(g, x, y, 28, 20, 10, bg);
        g.drawCenteredString(this.font, Component.literal(label), x + 14, y + 6,
                active ? accent : (hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY));
    }

    // ==================== 鼠标 ====================

    @Override
    public boolean mouseClicked(double mx, double my, int btn)
    {
        // ★ 进度条 / 音量条（优先级最高）
        if (mx >= progBarX - 4 && mx <= progBarX + progBarW + 4 && my >= progBarY - 6 && my <= progBarY + 10)
        { progDrag = true; seekMouse(mx); return true; }
        if (mx >= volBarX - 4 && mx <= volBarX + volBarW + 4 && my >= volBarY - 6 && my <= volBarY + 10)
        { volDrag = true; volMouse(mx); return true; }

        // ★ 侧栏
        if (mx < SIDEBAR_W)
        {
            if (hitSidebarSearchBtn(mx, my)) { doSearch(); return true; }
            for (Object[] row : navRowRects)
            {
                int[] r = (int[]) row[0];
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                { switchTab((Tab) row[1]); return true; }
            }
            for (Object[] row : sidebarPlRects)
            {
                int[] r = (int[]) row[0];
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                { switchTab(Tab.PLAYLIST); openPlaylist((NeteasePlaylist) row[1]); return true; }
            }
            int chY = this.height - 34;
            if (mx >= 8 && mx <= SIDEBAR_W - 8 && my >= chY && my <= chY + 26)
            { switchTab(Tab.ACCOUNT); return true; }
            return super.mouseClicked(mx, my, btn);
        }

        // ★ 固定页头（绝对坐标）
        if (my >= 4 && my < HEADER_H)
        {
            if (backBtn != null && hit(backBtn, mx, my)) { goBackPage(); return true; }
            if (playAllBtn != null && hit(playAllBtn, mx, my) && !playlistSongs.isEmpty())
            { MusicPlayer.getInstance().setQueue(playlistSongs, 0);
              MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist != null ? currentPlaylist.id : 0);
              MusicPlayer.getInstance().playSong(playlistSongs.get(0)); return true; }
            if (locateBtn != null && hit(locateBtn, mx, my)) { locateCurrent(); return true; }
            if (searchNextBtn != null && hit(searchNextBtn, mx, my)) { addSelectedToNext(); return true; }
            if (dailyBtn != null && hit(dailyBtn, mx, my)) { openDaily(); return true; }
            // 列表 / 封面墙分段（仅 PLAYLIST 详情页）
            if (currentTab == Tab.PLAYLIST && showPlaylistSongs)
            {
                int segW = 92, segX = this.width - segW - 12, halfW = segW / 2;
                if (mx >= segX && mx <= segX + segW && my >= 8 && my <= 26)
                {
                    boolean wantCover = mx >= segX + halfW;
                    if (wantCover != coverView) { coverView = wantCover; scroll = 0; }
                    return true;
                }
            }
            // 其余交给 super（EditBox 聚焦：筛选框等）
            return super.mouseClicked(mx, my, btn);
        }

        // ★ 底部播放栏（红心 / 走带控制）
        int barY0 = this.height - CONTROL_H;
        if (my >= barY0)
        {
            MusicPlayer mp0 = MusicPlayer.getInstance();
            if (likeBtn != null && hit(likeBtn, mx, my))
            {
                NeteaseSong s = mp0.getCurrentSong();
                if (s != null)
                    NeteaseSession.getInstance().toggleLike(s.id, !NeteaseSession.getInstance().isLiked(s.id));
                return true;
            }
            int by = barY0 + 15;
            if (my >= by - 4 && my <= by + 22)
            {
                for (int i = 0; i < ctrlBtnXs.length; i++)
                {
                    if (mx >= ctrlBtnXs[i] && mx <= ctrlBtnXs[i] + 28)
                    {
                        switch (i)
                        {
                            case 0 -> mp0.prev();
                            case 1 -> { if (mp0.isPaused()) mp0.resume(); else mp0.pause(); }
                            case 2 -> mp0.next();
                            case 3 -> mp0.setShuffleMode(!mp0.isShuffleMode());
                            case 4 -> mp0.setLoopMode(!mp0.isLoopMode());
                        }
                        return true;
                    }
                }
            }
            return super.mouseClicked(mx, my, btn);
        }

        // ★ 右侧正文点击
        if (mx >= SIDEBAR_W && my >= HEADER_H && my < bodyBottom())
        {
            switch (currentTab)
            {
                case HOME, CHARTS ->
                {
                    for (Object[] cell : gridHitRects)
                    {
                        int[] r = (int[]) cell[0];
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                        { switchTab(Tab.PLAYLIST); openPlaylist((NeteasePlaylist) cell[1]); return true; }
                    }
                }
                case PLAYLIST ->
                {
                    if (showPlaylistSongs)
                    {
                        if (coverView) { coverflowClick(mx, my, shownSongs()); return true; }
                        List<NeteaseSong> shown = shownSongs();
                        int idx = (int) ((my - HEADER_H + scroll) / ROW_H);
                        if (idx >= 0 && idx < shown.size())
                        { MusicPlayer.getInstance().setQueue(playlistSongs, playlistSongs.indexOf(shown.get(idx)));
                          MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist != null ? currentPlaylist.id : 0);
                          MusicPlayer.getInstance().playSong(shown.get(idx)); return true; }
                    }
                    else
                    {
                        int idx = (int) ((my - HEADER_H + scroll) / 44);
                        if (idx >= 0 && idx < playlists.size()) { openPlaylist(playlists.get(idx)); return true; }
                    }
                }
                case SEARCH ->
                {
                    if (coverView && !searchResults.isEmpty()) { coverflowClick(mx, my, searchResults); return true; }
                    int idx = (int) ((my - HEADER_H + scroll) / ROW_H);
                    if (idx >= 0 && idx < searchResults.size())
                    { MusicPlayer.getInstance().setQueue(searchResults, idx);
                      MusicPlayer.getInstance().playSong(searchResults.get(idx));
                      searchStatus = "正在播放: " + searchResults.get(idx).name;
                      searchStatusColor = ZephyrConfig.getAccentColor(); return true; }
                }
                case QUEUE ->
                {
                    List<NeteaseSong> q = MusicPlayer.getInstance().getQueue();
                    if (coverView && !q.isEmpty()) { coverflowClick(mx, my, q); return true; }
                    int idx = (int) ((my - HEADER_H + scroll) / ROW_H);
                    if (idx >= 0 && idx < q.size())
                    { MusicPlayer.getInstance().jumpTo(idx); return true; }
                }
                case SETTINGS -> toggleSetting(mx, my);
                case ACCOUNT ->
                {
                    NeteaseSession sess = NeteaseSession.getInstance();
                    boolean drawLoggedIn = sess.isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie();
                    NeteaseUser curUser = sess.getCurrentUser();
                    // ★ 与绘制逻辑保持一致：有 cookie 但没有用户信息 → 错误/验证界面（按钮命中区优先）
                    if (drawLoggedIn && userDetail == null && curUser == null
                            && sess.getLoginState() != NeteaseSession.LoginState.VERIFYING)
                    {
                        if (errBtnRelogin != null && hit(errBtnRelogin, mx, my)) { relogin(); return true; }
                        if (errBtnVerify != null && hit(errBtnVerify, mx, my))
                        {
                            // 重新验证：重置标记并重跑
                            userDetail = null;
                            loginVerifyStarted = false;
                            sess.resetLocalState();
                            ensureLoginVerified();
                            return true;
                        }
                        break;
                    }
                    if (!sess.isLoggedIn())
                    {
                        if (loginMode == 0)
                        {
                            // 选择登录方式按钮
                            int cx2 = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
                            int btnW = 120, btnX = cx2 - btnW / 2;
                            int loginY = ACC_TOP + 28;
                            for (int i = 0; i < 3; i++)
                            {
                                if (mx >= btnX && mx <= btnX + btnW && my >= loginY && my <= loginY + 20)
                                { loginMode = i + 1; if (loginMode == 1) startQrLogin(); clearWidgets(); init(); return true; }
                                loginY += 28;
                            }
                        }
                        else if (loginMode == 1 && qrBackBtn != null && hit(qrBackBtn, mx, my))
                        { loginMode = 0; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } qrImage = null; clearWidgets(); init(); return true; }
                    }
                    else if (accountLogoutBtn != null && hit(accountLogoutBtn, mx, my))
                    {
                        NeteaseSession.getInstance().logout().thenAccept(v -> Minecraft.getInstance().execute(() -> { userDetail = null; loginMode = 0; switchTab(Tab.ACCOUNT); }));
                        return true;
                    }
                }
                default -> {}
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private static boolean hit(int[] r, double mx, double my)
    { return r != null && mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]; }

    /** 封面墙点击：左半 → 上一张，右半 → 下一张，中间 → 播放 */
    private void coverflowClick(double mx, double my, List<NeteaseSong> songs)
    {
        if (songs == null || songs.isEmpty()) return;
        int cx = SIDEBAR_W + bodyWidth() / 2;
        int size = Math.min(bodyHeight() - 70, 150);
        int cyMid = (bodyTop() + bodyBottom()) / 2 - 12;
        if (mx >= cx - size / 2 && mx <= cx + size / 2 && my >= cyMid - size / 2 && my <= cyMid + size / 2)
        {
            int idx = Math.max(0, Math.min((int) Math.round(cfPos), songs.size() - 1));
            NeteaseSong song = songs.get(idx);
            MusicPlayer.getInstance().setQueue(new ArrayList<>(songs), idx);
            MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist != null ? currentPlaylist.id : 0);
            MusicPlayer.getInstance().playSong(song);
            return;
        }
        if (mx < cx) cfTarget = Math.max(0, cfTarget - 1);
        else cfTarget = Math.min(songs.size() - 1, cfTarget + 1);
    }

    /** 最近渲染的滑块命中区 {x,y,w,h}，0 宽表示无 */
    private final int[] sliderHit = new int[4];
    private final int[] lastSettingItem = new int[1];

    private void toggleSetting(double mx, double my)
    {
        // ★ 与 drawSettings 完全同构的几何（虚拟 y = 屏幕 y + scroll，绝对 x）
        int x = SIDEBAR_W + 14;
        int cw = this.width - SIDEBAR_W - 28;
        int rowH = 20, headerH = 22;
        double vy = my + scroll;
        double sy = ACC_TOP;
        for (SettingSection sec : settingSections)
        {
            sy += headerH;
            for (SettingItem item : sec.items)
            {
                int iy = (int) sy;
                if (vy >= iy - 2 && vy < iy + rowH - 2)
                {
                    if (item.slider && sliderHit[2] > 0 && mx >= sliderHit[0] && mx <= sliderHit[0] + sliderHit[2])
                    {
                        double ratio = (mx - sliderHit[0]) / sliderHit[2];
                        item.onSlide.accept(Math.max(0, Math.min(1, ratio)));
                    }
                    else if (item.onClick != null)
                    {
                        item.onClick.run();
                    }
                    return;
                }
                sy += rowH;
            }
            sy += 10 + 8;
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy)
    {
        if (progDrag) { seekMouse(mx); return true; }
        if (volDrag) { volMouse(mx); return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn)
    {
        if (progDrag) { progDrag = false; return true; }
        if (volDrag) { volDrag = false; return true; }
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta)
    {
        // 封面墙：滚轮切换封面（DangoMusic：滚轮 → coverflow）
        if (usingCoverflow() && mx >= SIDEBAR_W && my >= HEADER_H && my < bodyBottom())
        {
            List<NeteaseSong> songs = currentTab == Tab.SEARCH ? searchResults
                    : currentTab == Tab.QUEUE ? MusicPlayer.getInstance().getQueue() : shownSongs();
            if (!songs.isEmpty())
            {
                cfTarget = Math.max(0, Math.min(cfTarget + (delta > 0 ? -1 : 1), songs.size() - 1));
                return true;
            }
        }
        if (mx >= SIDEBAR_W)
        {
            scroll -= delta * 30;
            scroll = Math.max(0, Math.min(scroll, scrollMax > 0 ? scrollMax : 9999));
            return true;
        }
        // 侧栏：悬停在"我的歌单"区域时滚动快捷列表
        sidebarScroll -= (int) delta;
        if (sidebarScroll < 0) sidebarScroll = 0;
        return super.mouseScrolled(mx, my, delta);
    }

    // ==================== 键盘（借鉴 DangoMusic keyTyped 快捷键） ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        // ★ 侧栏搜索框内回车直接搜索
        if (searchBox != null && searchBox.isFocused()
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER))
        { doSearch(); return true; }
        boolean editing = (searchBox != null && searchBox.isFocused()) || (filterBox != null && filterBox.isFocused());
        // ★ 空格：播放/暂停（DangoMusic Keys.SPACE）
        if (!editing && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE)
        {
            MusicPlayer mp = MusicPlayer.getInstance();
            if (mp.getCurrentSong() != null) { if (mp.isPaused()) mp.resume(); else mp.pause(); }
            return true;
        }
        // ★ ←/→：快退/快进 5 秒；封面墙模式下切换封面
        if (!editing && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)
        {
            if (usingCoverflow())
            {
                List<NeteaseSong> songs = currentTab == Tab.SEARCH ? searchResults
                        : currentTab == Tab.QUEUE ? MusicPlayer.getInstance().getQueue() : shownSongs();
                cfTarget = Math.min(songs.size() - 1, cfTarget + 1);
            }
            else MusicPlayer.getInstance().seekTo(MusicPlayer.getInstance().getPositionSec() + 5);
            return true;
        }
        if (!editing && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT)
        {
            if (usingCoverflow()) cfTarget = Math.max(0, cfTarget - 1);
            else MusicPlayer.getInstance().seekTo(Math.max(0, MusicPlayer.getInstance().getPositionSec() - 5));
            return true;
        }
        // ★ Backspace：返回上一级（歌单详情 → 歌单列表）
        if (!editing && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE)
        { goBackPage(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 工具方法 ====================
    private void seekMouse(double mx)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong s = p.getCurrentSong();
        if (s == null || s.duration <= 0) return;
        double r = Math.max(0, Math.min(1, (mx - progBarX) / progBarW));
        p.seekTo(r * (s.duration / 1000.0));
    }
    private void volMouse(double mx)
    {
        double r = Math.max(0, Math.min(1, (mx - volBarX) / volBarW));
        MusicPlayer.getInstance().setVolume((float)r);
    }
    private void renderCover(GuiGraphics g, String url, int x, int y, int sz)
    {
        if (url == null || url.isEmpty()) return;
        ResourceLocation tid = CoverTextureManager.getInstance().getCover(url, null);
        if (tid != null)
        {
            RenderSystem.setShaderTexture(0, tid);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            g.blit(tid, x, y, 0, 0, sz, sz, sz, sz);
            RenderSystem.disableBlend();
        }
    }
    private void renderKaraoke(GuiGraphics g, LyricLine line, int cx, int y, int maxW, double pos)
    {
        int played = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get(), cur2 = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get(), unplayed = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();
        StringBuilder sb = new StringBuilder(); for (LyricWord w : line.words) sb.append(w.text);
        String text = sb.toString();
        int tw = this.font.width(text), sx = cx - tw / 2, cx2 = sx;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            String wt = w.text; int ww = this.font.width(wt); if (cx2 + ww > sx + maxW) break;
            int color = w.isFinished(pos) ? played : (w.isPlayingAt(pos) ? cur2 : unplayed);
            g.drawString(this.font, Component.literal(wt), cx2, y, color, false); cx2 += ww;
        }
    }
    private int findCur(List<LyricLine> lyrics, double pos) { int idx = -1; for (int i = 0; i < lyrics.size(); i++) { if (lyrics.get(i).time <= pos) idx = i; else break; } return idx; }
    private String fmtTime(double s) { if (s < 0) s = 0; int t = (int)s; return String.format("%d:%02d", t / 60, t % 60); }
    private String fmtDate(long ms) { if (ms <= 0) return "未知"; try { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(ms)); } catch (Exception e) { return "未知"; } }
    private String trunc(String s, int m) { if (s == null) return ""; if (s.length() <= m) return s; return s.substring(0, m - 1) + "…"; }
    @Override public boolean isPauseScreen() { return false; }
}
