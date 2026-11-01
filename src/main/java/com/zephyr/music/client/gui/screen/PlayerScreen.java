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
 * 全集成播放器界面 - 借鉴 InGameMusic 设计
 *
 * 左侧边栏(Tab) + 右侧内容区 + 底部控制条
 */
public class PlayerScreen extends Screen
{
    public enum Tab { LYRICS, PLAYLIST, SEARCH, QUEUE, SETTINGS, ACCOUNT }

    private static final int SIDEBAR_W = 118;
    private static final int CONTROL_H = 52;
    private static final int ROW_H = 36;
    /** 侧栏导航行几何（绘制与点击共用） */
    private static final int NAV_ROW_H = 24, NAV_GAP = 2, NAV_TOP = 42;
    /** 本帧侧栏导航行命中区（与 Tab.values() 平行），drawSidebar 时刷新 */
    private final java.util.List<int[]> navRowRects = new java.util.ArrayList<>();
    /** 搜索页“下一首”胶囊命中区 */
    private int[] searchNextBtn = null;
    /** 歌单页“全部播放”胶囊命中区 */
    private int[] playAllBtn = null;
    /** 侧栏搜索框内容（跨 init 保留） */
    private String searchTextCache = "";

    private Tab currentTab = Tab.LYRICS;
    private double mouseX, mouseY;

    // 搜索
    private EditBox searchBox;
    private final List<NeteaseSong> searchResults = new ArrayList<>();
    private String searchStatus = "";
    private int searchStatusColor = 0xFFAAAAAA;

    // 歌单
    private final List<NeteasePlaylist> playlists = new ArrayList<>();
    private final List<NeteaseSong> playlistSongs = new ArrayList<>();
    private NeteasePlaylist currentPlaylist;
    private boolean showPlaylistSongs = false;

    // 滚动
    private double scroll = 0;
    private double scrollMax = 0;

    // 进度条/音量条拖动
    private boolean progDrag = false, volDrag = false;
    private int progBarX, progBarY, progBarW;
    private int volBarX, volBarY, volBarW;

    // 账号/登录
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

        // ★ 侧栏常驻搜索框（借鉴 DangoMusic：搜索固定在侧栏顶部，不再只在搜索页出现）
        searchBox = new EditBox(this.font, 26, 11, SIDEBAR_W - 52, 14, Component.literal(""));
        searchBox.setHint(Component.literal("搜索..."));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchTextCache);
        searchBox.setResponder(s -> searchTextCache = s);
        searchBox.visible = true;
        addRenderableWidget(searchBox);

        // ★ 预计算进度条位置（避免 mouseClicked 使用旧值）
        progBarX = SIDEBAR_W + 20;
        progBarW = this.width - SIDEBAR_W - 40;
        progBarY = this.height - CONTROL_H + 6;

        // 登录表单（ACCOUNT Tab 未登录时且需要输入框的模式才显示）
        if (currentTab == Tab.ACCOUNT && !(NeteaseSession.getInstance().isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie()) && (loginMode == 2 || loginMode == 3))
        {
            int contentW = this.width - SIDEBAR_W;
            int cx2 = SIDEBAR_W + contentW / 2;
            int fx = cx2 - 100;  // ★ 自适应：基于内容区域中心
            int fw = Math.min(200, contentW - 40);
            // 手机登录字段
            countryCodeField = new EditBox(this.font, fx, 80, 40, 16, Component.literal("86"));
            countryCodeField.setValue("86"); countryCodeField.setMaxLength(5);
            phoneField = new EditBox(this.font, fx + 44, 80, 156, 16, Component.literal(""));
            phoneField.setHint(Component.literal("手机号")); phoneField.setMaxLength(20);
            captchaField = new EditBox(this.font, fx, 104, 120, 16, Component.literal(""));
            captchaField.setHint(Component.literal("验证码")); captchaField.setMaxLength(8);
            // 邮箱登录字段
            emailField = new EditBox(this.font, fx, 80, fw, 16, Component.literal(""));
            emailField.setHint(Component.literal("邮箱")); emailField.setMaxLength(64);
            emailPwdField = new EditBox(this.font, fx, 104, fw, 16, Component.literal(""));
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
                    .bounds(btnX, 128, 80, 18).build());
            addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> { loginMode = 0; clearWidgets(); init(); })
                    .bounds(btnX, 152, 80, 16).build());
        }

        // Tab 切换时加载数据
        if (currentTab == Tab.PLAYLIST && playlists.isEmpty() && NeteaseSession.getInstance().isLoggedIn())
            NeteaseSession.getInstance().fetchUserPlaylists().thenAccept(list -> { playlists.clear(); playlists.addAll(list); });
        if (currentTab == Tab.PLAYLIST && playlists.isEmpty() && !NeteaseSession.getInstance().isLoggedIn()
                && com.zephyr.music.config.LoginStore.hasCookie())
        {
            // ★ F7 直接打开歌单页时如有 cookie 先验证登录态（含过期自动续期）
            ensureLoginVerified();
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
    }

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

    private void switchTab(Tab tab) { currentTab = tab; scroll = 0; clearWidgets(); init(); }
    public void setCurrentTab(Tab tab) { this.currentTab = tab; }

    // === 登录 ===
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
        // 加载用户详情
        NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
        if (base != null && base.userId != 0)
            NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                userDetail = NeteaseApi.parseUserDetail(resp, base);
                if (userDetail != null) NeteaseSession.getInstance().updateCurrentUser(userDetail);
            });
        clearWidgets(); init();
    }

    // === 搜索 ===
    private void doSearch()
    {
        if (searchBox == null) return;
        String kw = searchBox.getValue().trim();
        if (kw.isEmpty()) { searchStatus = "请输入关键词"; searchStatusColor = 0xFFFF453A; return; }
        // ★ 从侧栏发起搜索时自动跳到搜索页
        if (currentTab != Tab.SEARCH) switchTab(Tab.SEARCH);
        searchStatus = "搜索中..."; searchStatusColor = ModernUI.TEXT_SECONDARY; searchResults.clear();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            Minecraft.getInstance().execute(() -> {
                searchResults.clear(); searchResults.addAll(songs); scroll = 0;
                searchStatus = songs.isEmpty() ? "无结果" : "找到 " + songs.size() + " 首";
                searchStatusColor = songs.isEmpty() ? ModernUI.TEXT_DANGER : ZephyrConfig.getAccentColor();
            });
        });
    }
    /** 侧栏 ⌕ 按钮命中区 */
    private boolean hitSidebarSearchBtn(double mx, double my)
    {
        return mx >= SIDEBAR_W - 26 && mx <= SIDEBAR_W - 8 && my >= 8 && my <= 28;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        // ★ 侧栏搜索框内回车直接搜索（借鉴 DangoMusic）
        if (searchBox != null && searchBox.isFocused()
                && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER))
        { doSearch(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void addSelectedToNext()
    {
        if (!searchResults.isEmpty())
        { MusicPlayer.getInstance().playNext(searchResults.get(0)); searchStatus = "已设为下一首"; searchStatusColor = ZephyrConfig.getAccentColor(); }
    }

    // === 歌单 ===
    private void openPlaylist(NeteasePlaylist pl)
    {
        currentPlaylist = pl; showPlaylistSongs = true; playlistSongs.clear();
        NeteaseSession.getInstance().getApi().playlistTrackAll(pl.id, 300, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            if (songs.isEmpty() && resp.has("playlist"))
            { JsonObject p = resp.getAsJsonObject("playlist"); if (p.has("tracks")) songs = NeteaseApi.parseSongs(p, "tracks"); }
            final List<NeteaseSong> fs = songs;
            Minecraft.getInstance().execute(() -> { playlistSongs.clear(); playlistSongs.addAll(fs); scroll = 0; });
        });
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

        // ★ 右侧内容区域裁剪 + 滚动偏移
        g.enableScissor(SIDEBAR_W, 4, this.width, this.height - CONTROL_H);
        g.pose().pushPose();
        g.pose().translate(0, -scroll, 0);

        switch (currentTab)
        {
            case LYRICS -> drawLyrics(g);
            case PLAYLIST -> drawPlaylist(g);
            case SEARCH -> drawSearch(g);
            case QUEUE -> drawQueue(g);
            case SETTINGS -> drawSettings(g);
            case ACCOUNT -> drawAccount(g);
        }

        g.pose().popPose();
        g.disableScissor();

        drawControlBar(g);
        super.render(g, mx, my, delta);
    }

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

    // === 左侧边栏（借鉴 DangoMusic：常驻搜索框 + 分组导航 + 选中胶囊 + 底部账号） ===
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
        Tab[] tabs = Tab.values();
        int y = NAV_TOP;

        y = drawSectionLabel(g, "音乐库", y);
        for (int i = 0; i < 4; i++) y = drawNavRow(g, tabs[i], y);
        y += 8;
        y = drawSectionLabel(g, "系统", y);
        for (int i = 4; i < tabs.length; i++) y = drawNavRow(g, tabs[i], y);

        // 底部账号 chip（DangoMusic：头像 + 昵称，点击进账号页）
        int chY = this.height - 34;
        ModernUI.fillRound(g, 8, chY, SIDEBAR_W - 16, 26, 10, ModernUI.CARD);
        NeteaseUser cu = NeteaseSession.getInstance().getCurrentUser();
        if (cu != null && cu.avatarUrl != null && !cu.avatarUrl.isEmpty())
        {
            renderCover(g, cu.avatarUrl, 12, chY + 3, 20);
            String nick = trunc(cu.nickname, 6);
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
        return y + 16;
    }

    /** 单个导航行：左侧图标 + 名称，选中态胶囊（返回下一行 y） */
    private int drawNavRow(GuiGraphics g, Tab tab, int y)
    {
        int accent = ZephyrConfig.getAccentColor();
        String[] icons = {"♪", "≡", "⌕", "☰", "⚙", "♥"};
        String[] labels = {"歌词", "歌单", "搜索", "队列", "设置", "账号"};
        int idx = tab.ordinal();
        String label = labels[idx];
        if (tab == Tab.ACCOUNT)
        {
            NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
            if (NeteaseSession.getInstance().isLoggedIn() && u != null && u.nickname != null && !u.nickname.isEmpty())
                label = trunc(u.nickname, 6);
        }
        int x1 = 8, x2 = SIDEBAR_W - 8;
        boolean selected = currentTab == tab;
        boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y && mouseY <= y + NAV_ROW_H;
        if (selected)
        {
            ModernUI.fillRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.CARD_HOVER);
            ModernUI.strokeRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.HAIRLINE, 1);
        }
        else if (hovered)
        {
            ModernUI.fillRound(g, x1, y, x2 - x1, NAV_ROW_H, 8, ModernUI.CARD);
        }
        g.drawString(this.font, Component.literal(icons[idx]), x1 + 8, y + 8,
                selected ? accent : ModernUI.TEXT_DIM, false);
        g.drawString(this.font, Component.literal(label), x1 + 24, y + 8,
                selected ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY, false);
        navRowRects.add(new int[]{x1, y, x2 - x1, NAV_ROW_H});
        return y + NAV_ROW_H + NAV_GAP;
    }

    // === 歌词页 ===
    private void drawLyrics(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int cx = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
        int cy = (this.height - CONTROL_H) / 2;
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

    // === 歌单页（借鉴 DangoMusic：大标题 + “全部播放”胶囊 + 卡片式歌单行） ===
    private void drawPlaylist(GuiGraphics g)
    {
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        int accent = ZephyrConfig.getAccentColor();
        if (showPlaylistSongs && currentPlaylist != null)
        {
            // 头部：返回胶囊 + 歌单标题 + 数量副标题 + 全部播放
            boolean bhv = mouseX >= cx2 + 10 && mouseX <= cx2 + 62 && mouseY >= 8 && mouseY <= 26;
            ModernUI.drawPillButton(g, cx2 + 10, 8, 52, 18, "← 返回", bhv, false);
            g.drawString(this.font, Component.literal(trunc(currentPlaylist.name, 24)), cx2 + 74, 9, ModernUI.TEXT_PRIMARY, false);
            g.drawString(this.font, Component.literal(playlistSongs.size() + " 首"), cx2 + 74, 20, ModernUI.TEXT_DIM, false);
            boolean phv = mouseX >= this.width - 108 && mouseX <= this.width - 14 && mouseY >= 8 && mouseY <= 26;
            ModernUI.drawPillButton(g, this.width - 108, 8, 94, 18, "▶ 全部播放", phv, true);
            playAllBtn = new int[]{this.width - 108, 8, 94, 18};
            // ★ 滚动已由 render() 的 translate 统一应用，行坐标不再重复减 scroll
            int listY = 36, listBot = this.height - CONTROL_H - 8;
            double maxS = Math.max(0, playlistSongs.size() * ROW_H - (listBot - listY));
            scroll = Math.max(0, Math.min(scroll, maxS));
            int start = (int)(scroll / ROW_H);
            for (int i = start; i < Math.min(playlistSongs.size(), start + (listBot - listY) / ROW_H + 1); i++)
            {
                int y = listY + i * ROW_H;
                drawSongRow(g, playlistSongs.get(i), y, cx2, cw, i + 1);
            }
            return;
        }
        // 大标题 + 副标题（DangoMusic 页头风格）
        g.drawString(this.font, Component.literal("我的歌单"), cx2 + 12, 10, ModernUI.TEXT_PRIMARY, false);
        g.drawString(this.font, Component.literal(
                playlists.isEmpty() ? "登录后同步你的网易云歌单" : "共 " + playlists.size() + " 个歌单"),
                cx2 + 12, 23, ModernUI.TEXT_DIM, false);
        if (playlists.isEmpty())
        {
            // ★ 按登录态给出明确提示，不再一律"未登录/加载中"
            NeteaseSession.LoginState pst = NeteaseSession.getInstance().getLoginState();
            String msg;
            if (NeteaseSession.getInstance().isLoggedIn()) msg = "加载歌单...";
            else if (pst == NeteaseSession.LoginState.VERIFYING) msg = "正在验证登录态…";
            else if (pst == NeteaseSession.LoginState.INVALID) msg = "登录态已失效，请到【账号】页重新登录";
            else if (pst == NeteaseSession.LoginState.UNKNOWN) msg = "网络异常，无法加载歌单";
            else msg = "未登录";
            g.drawCenteredString(this.font, Component.literal(msg), cx2 + cw / 2, this.height / 3, ModernUI.TEXT_DIM);
            playAllBtn = null;
            return;
        }
        int listY = 42, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, playlists.size() * 26 - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / 26);
        for (int i = start; i < Math.min(playlists.size(), start + (listBot - listY) / 26 + 1); i++)
        {
            NeteasePlaylist pl = playlists.get(i);
            int y = listY + i * 26;
            boolean hv = mouseX >= cx2 + 8 && mouseX <= this.width - 8 && mouseY >= y && mouseY <= y + 24;
            ModernUI.fillRound(g, cx2 + 8, y, this.width - cx2 - 16, 24, 8,
                    hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
            g.drawString(this.font, Component.literal("≡"), cx2 + 16, y + 8, accent, false);
            g.drawString(this.font, Component.literal(trunc(pl.name, 30)), cx2 + 32, y + 4, ModernUI.TEXT_PRIMARY, false);
            g.drawString(this.font, Component.literal(pl.trackCount + " 首" + (pl.creatorName != null && !pl.creatorName.isEmpty() ? " · " + pl.creatorName : "")), cx2 + 32, y + 15, ModernUI.TEXT_DIM, false);
        }
        playAllBtn = null;
    }

    // === 搜索页 ===
    private void drawSearch(GuiGraphics g)
    {
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        g.drawString(this.font, Component.literal("搜索"), cx2 + 12, 10, ModernUI.TEXT_PRIMARY, false);
        if (!searchStatus.isEmpty())
            g.drawString(this.font, Component.literal(searchStatus), cx2 + 12, 23, searchStatusColor, false);
        if (!searchResults.isEmpty())
        {
            // “下一首”胶囊（保留原 ▶下一首 功能）
            boolean nhv = mouseX >= this.width - 78 && mouseX <= this.width - 14 && mouseY >= 8 && mouseY <= 26;
            ModernUI.drawPillButton(g, this.width - 78, 8, 64, 18, "＋下一首", nhv, false);
            searchNextBtn = new int[]{this.width - 78, 8, 64, 18};
        }
        else searchNextBtn = null;
        if (searchResults.isEmpty()) return;
        int listY = 42, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, searchResults.size() * ROW_H - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / ROW_H);
        for (int i = start; i < Math.min(searchResults.size(), start + (listBot - listY) / ROW_H + 1); i++)
        {
            int y = listY + i * ROW_H;
            drawSongRow(g, searchResults.get(i), y, cx2, cw, i + 1);
        }
    }

    // === 队列页 ===
    private void drawQueue(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        List<NeteaseSong> q = p.getQueue();
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        g.drawString(this.font, Component.literal("播放队列"), cx2 + 12, 10, ModernUI.TEXT_PRIMARY, false);
        g.drawString(this.font, Component.literal(q.isEmpty() ? "队列是空的，去搜索或歌单里挑几首吧"
                : "共 " + q.size() + " 首 · 正在播放第 " + (p.getQueueIndex() + 1) + " 首"),
                cx2 + 12, 23, ModernUI.TEXT_DIM, false);
        if (q.isEmpty()) return;
        int listY = 42, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, q.size() * ROW_H - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / ROW_H);
        for (int i = start; i < Math.min(q.size(), start + (listBot - listY) / ROW_H + 1); i++)
        {
            int y = listY + i * ROW_H;
            drawSongRow(g, q.get(i), y, cx2, cw, i + 1);
        }
    }

    // === 设置页 ===
    // ==================================================================
    //  设置页（数据驱动，分组渲染）
    // ==================================================================

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
        scrollMax = Math.max(0, totalH - (this.height - CONTROL_H - 44));
        scroll = Math.max(0, Math.min(scroll, scrollMax));

        // ★ 虚拟坐标（render() 已 translate(-scroll)，此处不再自己减 scroll）
        double sy = 36;
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
        int y = 36;
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
            g.drawString(this.font, Component.literal("🆔 ID: " + u.userId), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("🎵 听歌: " + (u.listenSongs > 0 ? u.listenSongs + "首" : "未知")), ix, y, accent, false); y += 16;
            g.drawString(this.font, Component.literal("📊 等级: " + (u.level > 0 ? "Lv." + u.level : "未知")), ix, y, accent, false); y += 16;
            g.drawString(this.font, Component.literal("📅 注册: " + fmtDate(u.createTime)), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("📍 地区: " + RegionCodeMapper.formatLocation(u.province, u.city)), ix, y, dim, false); y += 16;
            g.drawString(this.font, Component.literal("⚧ 性别: " + (u.gender == 1 ? "男" : u.gender == 2 ? "女" : "保密")), ix, y, dim, false); y += 24;
            int btnW = 80, btnX = cx2 - btnW / 2;
            boolean hv = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= y && mouseY <= y + 18;
            g.fill(btnX, y, btnX + btnW, y + 18, hv ? 0x80FF6666 : 0x40FF6666);
            g.drawCenteredString(this.font, Component.literal("退出登录"), cx2, y + 5, ModernUI.TEXT_DANGER);
            return;
        }

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
                g.fill(btnX, y, btnX + btnW, y + btnH, hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
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
                g.fill(btnX, y, btnX + btnW, y + 18, hv ? ModernUI.CARD_HOVER : ModernUI.CARD);
                g.drawCenteredString(this.font, Component.literal("← 返回"), cx2, y + 5, accent);
            }
            else
            {
                // 等待生成
                int ph = 140, px2 = cx2 - ph / 2;
                g.fill(px2 - 4, y - 4, px2 + ph + 4, y + ph + 4, 0xFFFFFFFF);
                g.drawCenteredString(this.font, Component.literal("..."), cx2, y + ph / 2 - 4, ModernUI.TEXT_DIM);
                y += ph + 12;
                g.drawCenteredString(this.font, Component.literal(loginStatus.isEmpty() ? "生成二维码中..." : loginStatus), cx2, y, loginStatusColor);
            }
        }
        else if (loginMode == 2)
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

    // === 歌曲行（借鉴 DangoMusic：序号 + 封面缩略图 + 标题/歌手两行 + 右侧时长）===
    private void drawSongRow(GuiGraphics g, NeteaseSong song, int y, int contentX, int contentW, int index)
    {
        // ★ y 是虚拟坐标（render() 已 translate(-scroll)），裁剪范围放宽一个行高
        if (y < 8 - ROW_H || y > this.height - CONTROL_H + 4) return;
        double hoverY = mouseY + scroll; // ★ 与虚拟坐标同系（render() 已 translate(-scroll)）
        boolean hv = mouseX >= contentX + 4 && mouseX <= this.width - 4 && hoverY >= y && hoverY <= y + ROW_H;
        NeteaseSong curSong = MusicPlayer.getInstance().getCurrentSong();
        boolean isCurrent = curSong != null && curSong.id == song.id;
        int accent = ZephyrConfig.getAccentColor();
        if (hv || isCurrent)
        {
            ModernUI.fillRound(g, contentX + 4, y, this.width - contentX - 8, ROW_H, 10,
                    isCurrent ? ModernUI.withAlpha(accent, 0x30) : (hv ? ModernUI.CARD_HOVER : ModernUI.CARD));
        }
        // 序号（DangoMusic 歌单页左列）
        String no = String.valueOf(index);
        g.drawString(this.font, Component.literal(no), contentX + 12, y + 14, ModernUI.TEXT_DIM, false);
        // 封面缩略图
        renderCover(g, song.picUrl, contentX + 30, y + 2, 32);
        // 标题
        String title = trunc(song.name, 40);
        g.drawString(this.font, Component.literal(title), contentX + 70, y + 4,
                isCurrent ? accent : ModernUI.TEXT_PRIMARY, false);
        // 歌手
        String sub = trunc(song.getDisplayArtist(), 40);
        g.drawString(this.font, Component.literal(sub), contentX + 70, y + 18, ModernUI.TEXT_DIM, false);
        // 右侧时长
        String dur = song.getDisplayDuration();
        g.drawString(this.font, Component.literal(dur), this.width - 14 - this.font.width(dur), y + 14, ModernUI.TEXT_DIM, false);
    }

    // === 底部播放栏（借鉴 DangoMusic PlayerBar：左封面信息 / 中控制键 / 右音量，下进度条） ===
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
        renderCover(g, song != null ? song.picUrl : "", cx2 + 10, y + 8, 36);
        int infoX = cx2 + 54;
        String title = song != null ? trunc(song.name, 22) : "未在播放";
        String artist = song != null ? trunc(song.getDisplayArtist(), 22) : "去侧栏搜索一首歌吧";
        g.drawString(this.font, Component.literal(title), infoX, y + 11, ModernUI.TEXT_PRIMARY, false);
        g.drawString(this.font, Component.literal(artist), infoX, y + 24, ModernUI.TEXT_SECONDARY, false);

        // 中：上一首 / 播放暂停 / 下一首 / 随机 / 循环（圆形悬停底，DangoMusic 排布）
        int centerX = cx2 + cw / 2 + 40;
        int by = y + 15;
        int[] xs = {centerX - 84, centerX - 42, centerX, centerX + 42, centerX + 84};
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

        // 下：进度条（时间 | 细条 | 时间，可拖拽 seek）
        double pos = p.getPositionSec();
        double total = song != null && song.duration > 0 ? song.duration / 1000.0 : 0;
        double prog = total > 0 ? Math.min(1, pos / total) : 0;
        String t1 = fmtTime(pos), t2 = fmtTime(total);
        int barX = infoX + 180;
        int barW = Math.max(40, this.width - 160 - barX);
        int barY = y + 41;
        progBarX = barX; progBarY = barY - 3; progBarW = barW;
        g.drawString(this.font, Component.literal(t1), cx2 + 12, barY - 2, ModernUI.TEXT_SECONDARY, false);
        ModernUI.drawThinBar(g, barX, barY, barW, 4, prog);
        g.drawString(this.font, Component.literal(t2), this.width - 12 - this.font.width(t2), barY - 2, ModernUI.TEXT_DIM, false);
    }

    /** 控制按钮中心 x（mouseClicked 用） */
    private int[] ctrlBtnXs = new int[5];

    private void drawCtrlBtn(GuiGraphics g, String label, int x, int y, boolean hovered, boolean active)
    {
        int accent = ZephyrConfig.getAccentColor();
        int bg = active ? ModernUI.withAlpha(accent, 40) : (hovered ? ModernUI.CARD_HOVER : 0x00FFFFFF);
        if (bg != 0) ModernUI.fillRound(g, x, y, 28, 20, 10, bg);
        g.drawCenteredString(this.font, Component.literal(label), x + 14, y + 6,
                active ? accent : (hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY));
    }

    public boolean mouseClicked(double mx, double my, int btn)
    {
        // ★ 进度条检查放在最前面（优先级最高）
        if (mx >= progBarX - 4 && mx <= progBarX + progBarW + 4 && my >= progBarY - 6 && my <= progBarY + 10)
        { progDrag = true; seekMouse(mx); return true; }
        // 音量条
        if (mx >= volBarX - 4 && mx <= volBarX + volBarW + 4 && my >= volBarY - 6 && my <= volBarY + 10)
        { volDrag = true; volMouse(mx); return true; }

        // ★ 侧边栏（导航行命中区由 drawSidebar 本帧刷新，借鉴 DangoMusic 分组导航）
        if (mx < SIDEBAR_W)
        {
            if (hitSidebarSearchBtn(mx, my)) { doSearch(); return true; }
            Tab[] tabs = Tab.values();
            for (int i = 0; i < navRowRects.size() && i < tabs.length; i++)
            {
                int[] r = navRowRects.get(i);
                if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                { switchTab(tabs[i]); return true; }
            }
            // 底部账号 chip
            int chY = this.height - 34;
            if (mx >= 8 && mx <= SIDEBAR_W - 8 && my >= chY && my <= chY + 26)
            { switchTab(Tab.ACCOUNT); return true; }
            return super.mouseClicked(mx, my, btn);
        }

        // 底部控制按钮（位置由 drawControlBar 本帧写入 ctrlBtnXs）
        int by = this.height - CONTROL_H + 15;
        if (my >= by - 4 && my <= by + 22)
        {
            MusicPlayer mp0 = MusicPlayer.getInstance();
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

        // 右侧内容区点击（★ 顶部从 4 开始，覆盖歌单/搜索页头部胶囊区域）
        int contentX = SIDEBAR_W;
        if (mx >= contentX && my >= 4 && my < this.height - CONTROL_H - 4)
        {
            switch (currentTab)
            {
                case PLAYLIST:
                    if (showPlaylistSongs)
                    {
                        // ★ 返回胶囊 / 全部播放胶囊（与 drawPlaylist 同帧命中区）
                        int cx0 = SIDEBAR_W;
                        if (mx >= cx0 + 10 && mx <= cx0 + 62 && my >= 8 && my <= 26)
                        { showPlaylistSongs = false; playlistSongs.clear(); scroll = 0; return true; }
                        if (playAllBtn != null && mx >= playAllBtn[0] && mx <= playAllBtn[0] + playAllBtn[2]
                                && my >= playAllBtn[1] && my <= playAllBtn[1] + playAllBtn[3] && !playlistSongs.isEmpty())
                        { MusicPlayer.getInstance().setQueue(playlistSongs, 0);
                          MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist.id);
                          MusicPlayer.getInstance().playSong(playlistSongs.get(0)); return true; }
                        int listY = 42, rowH = ROW_H;
                        int idx = (int)((my - listY + scroll) / rowH);
                        if (idx >= 0 && idx < playlistSongs.size())
                        { MusicPlayer.getInstance().setQueue(playlistSongs, idx);
                          MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist.id);
                          MusicPlayer.getInstance().playSong(playlistSongs.get(idx)); return true; }
                    }
                    else
                    {
                        int listY = 42, rowH = 26;
                        int idx = (int)((my - listY + scroll) / rowH);
                        if (idx >= 0 && idx < playlists.size()) { openPlaylist(playlists.get(idx)); return true; }
                    }
                    break;
                case SEARCH:
                    if (searchNextBtn != null && mx >= searchNextBtn[0] && mx <= searchNextBtn[0] + searchNextBtn[2]
                            && my >= searchNextBtn[1] && my <= searchNextBtn[1] + searchNextBtn[3])
                    { addSelectedToNext(); return true; }
                    int listY2 = 42, rowH2 = ROW_H;
                    int sidx = (int)((my - listY2 + scroll) / rowH2);
                    if (sidx >= 0 && sidx < searchResults.size())
                    { MusicPlayer.getInstance().setQueue(searchResults, sidx);
                      MusicPlayer.getInstance().playSong(searchResults.get(sidx));
                      searchStatus = "正在播放: " + searchResults.get(sidx).name;
                      searchStatusColor = ZephyrConfig.getAccentColor(); return true; }
                    break;
                case QUEUE:
                    int qListY = 42, qRowH = ROW_H;
                    int qidx = (int)((my - qListY + scroll) / qRowH);
                    if (qidx >= 0 && qidx < MusicPlayer.getInstance().getQueue().size())
                    { MusicPlayer.getInstance().jumpTo(qidx); return true; }
                    break;
                case SETTINGS:
                    toggleSetting(mx, my);
                    break;
                case ACCOUNT:
                    NeteaseSession sess = NeteaseSession.getInstance();
                    boolean drawLoggedIn = sess.isLoggedIn() || com.zephyr.music.config.LoginStore.hasCookie();
                    NeteaseUser curUser = sess.getCurrentUser();
                    // ★ 与绘制逻辑保持一致：有 cookie 但没有用户信息 → 错误/验证界面（按钮命中区优先）
                    if (drawLoggedIn && userDetail == null && curUser == null
                            && sess.getLoginState() != NeteaseSession.LoginState.VERIFYING)
                    {
                        if (errBtnRelogin != null)
                        {
                            int[] r = errBtnRelogin;
                            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                            { relogin(); return true; }
                        }
                        if (errBtnVerify != null)
                        {
                            int[] r = errBtnVerify;
                            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3])
                            {
                                // 重新验证：重置标记并重跑
                                userDetail = null;
                                loginVerifyStarted = false;
                                sess.resetLocalState();
                                ensureLoginVerified();
                                return true;
                            }
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
                            int loginY = 36 + 28;
                            for (int i = 0; i < 3; i++)
                            {
                                if (mx >= btnX && mx <= btnX + btnW && my >= loginY && my <= loginY + 20)
                                { loginMode = i + 1; if (loginMode == 1) startQrLogin(); clearWidgets(); init(); return true; }
                                loginY += 28;
                            }
                        }
                        else if (loginMode == 1)
                        {
                            // 扫码模式：返回按钮在二维码下方
                            int cx2 = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
                            int backY = 36 + 20 + 140 + 12 + 16; // 标题20 + 二维码140 + 间距12 + 状态16
                            int btnW = 80, btnX = cx2 - btnW / 2;
                            if (mx >= btnX && mx <= btnX + btnW && my >= backY && my <= backY + 18)
                            { loginMode = 0; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } qrImage = null; clearWidgets(); init(); return true; }
                        }
                    }
                    else
                    {
                        // 退出登录
                        int cx2 = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
                        int infoY = 36 + 56 + 8 + 14 + 16 * 7;
                        if (mx >= cx2 - 40 && mx <= cx2 + 40 && my >= infoY && my <= infoY + 18)
                        { NeteaseSession.getInstance().logout().thenAccept(v -> Minecraft.getInstance().execute(() -> { userDetail = null; loginMode = 0; switchTab(Tab.ACCOUNT); })); return true; }
                    }
                    break;
            }
        }
        return super.mouseClicked(mx, my, btn);
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
        double sy = 36;
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
        if (mx >= SIDEBAR_W)
        {
            scroll -= delta * 30;
            scroll = Math.max(0, Math.min(scroll, scrollMax > 0 ? scrollMax : 9999));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

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

    // === 工具方法 ===
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
