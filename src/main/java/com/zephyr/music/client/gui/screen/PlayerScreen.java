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

    private static final int SIDEBAR_W = 100;
    private static final int CONTROL_H = 52;
    private static final int ROW_H = 36;

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

        // 搜索框
        searchBox = new EditBox(this.font, SIDEBAR_W + 12, 12, this.width - SIDEBAR_W - 130, 16, Component.literal(""));
        searchBox.setHint(Component.literal("搜索歌曲/歌手..."));
        searchBox.setMaxLength(64);
        // ★ 搜索框和按钮只在搜索Tab显示
        boolean showSearch = (currentTab == Tab.SEARCH);
        searchBox.visible = showSearch;
        addRenderableWidget(searchBox);

        // ★ 搜索按钮和下一首按钮只在搜索Tab显示
        if (showSearch)
        {
            addRenderableWidget(Button.builder(Component.literal("搜索"), b -> doSearch())
                    .bounds(this.width - 110, 10, 50, 18).build());
            addRenderableWidget(Button.builder(Component.literal("▶下一首"), b -> addSelectedToNext())
                    .bounds(this.width - 56, 10, 48, 18).build());
        }

        // ★ 预计算进度条位置（避免 mouseClicked 使用旧值）
        progBarX = SIDEBAR_W + 20;
        progBarW = this.width - SIDEBAR_W - 40;
        progBarY = this.height - CONTROL_H + 6;

        // 登录表单（ACCOUNT Tab 未登录时且需要输入框的模式才显示）
        if (currentTab == Tab.ACCOUNT && !(NeteaseSession.getInstance().isLoggedIn() || !com.zephyr.music.config.ZephyrConfig.COOKIE.get().isEmpty()) && (loginMode == 2 || loginMode == 3))
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
            searchBox.visible = (currentTab == Tab.SEARCH);
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
        if (currentTab == Tab.ACCOUNT && userDetail == null)
        {
            NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
            if (base != null && base.userId != 0)
                NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                    userDetail = NeteaseApi.parseUserDetail(resp, base);
                    if (userDetail != null) NeteaseSession.getInstance().updateCurrentUser(userDetail);
                });
            else userDetail = base;
        }
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
            if (phone.isEmpty()) { loginStatus = "请输入手机号"; loginStatusColor = 0xFFFF6666; return; }
            loginStatus = "登录中..."; loginStatusColor = 0xFFCCCCCC;
            NeteaseSession.getInstance().getApi().loginCellphone(phone, "", cap, ct).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = 0xFFFF6666; }
                });
            });
        }
        else if (loginMode == 3)
        {
            String email = emailField.getValue().trim();
            String pwd = emailPwdField.getValue();
            if (email.isEmpty() || pwd.isEmpty()) { loginStatus = "请输入邮箱和密码"; loginStatusColor = 0xFFFF6666; return; }
            loginStatus = "登录中..."; loginStatusColor = 0xFFCCCCCC;
            NeteaseSession.getInstance().getApi().loginEmail(email, pwd).thenAccept(resp -> {
                int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                Minecraft.getInstance().execute(() -> {
                    if (code == 200 || code == 803) { NeteaseSession.getInstance().handleLoginResponse(resp); onLoginSuccess(); }
                    else { loginStatus = "登录失败: " + code; loginStatusColor = 0xFFFF6666; }
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
            { loginStatus = "获取二维码key失败"; loginStatusColor = 0xFFFF6666; return CompletableFuture.completedFuture(new JsonObject()); }
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
            if (qrimg.isEmpty()) { loginStatus = "API未返回二维码"; loginStatusColor = 0xFFFF6666; return; }
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
                        loginStatus = "请使用网易云App扫码"; loginStatusColor = 0xFF4FC3F7;
                        startQrPolling();
                    });
                }
                else
                {
                    ZephyrMusic.LOGGER.error("[Zephyr] ImageIO.read returned null or empty image");
                    Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败(null)"; loginStatusColor = 0xFFFF6666; });
                }
            } catch (Exception e) {
                ZephyrMusic.LOGGER.error("[Zephyr] QR decode failed: {}", e.getMessage());
                Minecraft.getInstance().execute(() -> { loginStatus = "二维码解析失败: " + e.getMessage(); loginStatusColor = 0xFFFF6666; });
            }
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.error("[Zephyr] startQrLogin failed", e);
            Minecraft.getInstance().execute(() -> { loginStatus = "二维码生成失败"; loginStatusColor = 0xFFFF6666; });
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
                if (++count > 60) { loginStatus = "二维码过期"; loginStatusColor = 0xFFFF6666; qrTimer.cancel(); qrTimer = null; return; }
                NeteaseSession.getInstance().getApi().qrCheck(qrKey).thenAccept(resp -> {
                    int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
                    if (code == 800) { loginStatus = "二维码过期"; loginStatusColor = 0xFFFF6666; if (qrTimer != null) { qrTimer.cancel(); qrTimer = null; } }
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
        if (kw.isEmpty()) { searchStatus = "请输入关键词"; searchStatusColor = 0xFFFF6666; return; }
        searchStatus = "搜索中..."; searchStatusColor = 0xFFCCCCCC; searchResults.clear();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            Minecraft.getInstance().execute(() -> {
                searchResults.clear(); searchResults.addAll(songs); scroll = 0;
                searchStatus = songs.isEmpty() ? "无结果" : "找到 " + songs.size() + " 首";
                searchStatusColor = songs.isEmpty() ? 0xFFFF6666 : 0xFF4FC3F7;
            });
        });
    }
    private void addSelectedToNext()
    {
        if (!searchResults.isEmpty())
        { MusicPlayer.getInstance().playNext(searchResults.get(0)); searchStatus = "已设为下一首"; searchStatusColor = 0xFF4FC3F7; }
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

        // ★ 毛玻璃背景：模糊后的世界画面 + 暗色蒙版（不再使用原生黑色渐变背景）
        drawGlassBackdrop(g);

        // 搜索框可见性
        searchBox.visible = (currentTab == Tab.SEARCH);

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
        g.fillGradient(0, 0, this.width, this.height, 0x880E1218, 0xB40E1218);
    }

    // === 左侧边栏 ===
    private void drawSidebar(GuiGraphics g)
    {
        // 玻璃侧边栏
        ModernUI.glassPanel(g, 0, 0, SIDEBAR_W, this.height, 0, ModernUI.GLASS_DARK, 0.5f, true);
        g.fill(SIDEBAR_W - 1, 0, SIDEBAR_W, this.height, 0x40FFFFFF);

        // 品牌标识
        g.drawCenteredString(this.font, Component.literal("Zephyr"), SIDEBAR_W / 2, 9, ModernUI.TEXT_ACCENT);
        g.drawCenteredString(this.font, Component.literal("MUSIC"), SIDEBAR_W / 2, 19, 0xFF5A6B82);

        Tab[] tabs = Tab.values();
        String[] labels = {"歌词", "歌单", "搜索", "队列", "设置", "账号"};
        int y = 40;
        for (int i = 0; i < tabs.length; i++)
        {
            int x1 = 6, y1 = y, x2 = SIDEBAR_W - 6, y2 = y + 30;
            boolean hovered = mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
            boolean selected = currentTab == tabs[i];
            String label = labels[i];
            if (tabs[i] == Tab.ACCOUNT)
            {
                NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
                if (NeteaseSession.getInstance().isLoggedIn() && u != null && u.nickname != null && !u.nickname.isEmpty())
                    label = this.font.width(u.nickname) > 80 ? trunc(u.nickname, 6) : u.nickname;
                else
                    label = "登录";
            }
            if (selected)
            {
                // 选中态：强调色胶囊 + 左侧亮条
                ModernUI.fillRound(g, x1, y1, x2 - x1, y2 - y1, 8, 0xFF24384F);
                ModernUI.strokeRound(g, x1, y1, x2 - x1, y2 - y1, 8, 0x504FC3F7, 1);
                g.fill(2, y1 + 6, 4, y2 - 6, 0xFF4FC3F7);
            }
            else if (hovered)
            {
                ModernUI.fillRound(g, x1, y1, x2 - x1, y2 - y1, 8, 0x30FFFFFF);
            }
            g.drawCenteredString(this.font, Component.literal(label), (x1 + x2) / 2, y1 + (30 - 8) / 2,
                    selected ? ModernUI.TEXT_ACCENT : ModernUI.TEXT_SECONDARY);
            y += 34;
        }
    }

    // === 歌词页 ===
    private void drawLyrics(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int cx = SIDEBAR_W + (this.width - SIDEBAR_W) / 2;
        int cy = (this.height - CONTROL_H) / 2;
        if (song == null) { g.drawCenteredString(this.font, Component.literal("暂无播放"), cx, cy, 0xFF888888); return; }
        double pos = p.getPositionSec();
        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics == null || lyrics.isEmpty()) { g.drawCenteredString(this.font, Component.literal("（暂无歌词）"), cx, cy, 0xFF888888); return; }
        int cur = findCur(lyrics, pos);
        if (cur < 0) { g.drawCenteredString(this.font, Component.literal("♪ ~ ~ ~"), cx, cy, 0xFF4FC3F7); return; }
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
            int color = act ? 0xFF4FC3F7 : ModernUI.withAlpha(0xFFCCCCCC, alpha);
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

    // === 歌单页 ===
    private void drawPlaylist(GuiGraphics g)
    {
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        if (showPlaylistSongs && currentPlaylist != null)
        {
            g.drawString(this.font, Component.literal("< 返回"), cx2 + 8, 12, 0xFF4FC3F7, false);
            g.drawString(this.font, Component.literal(currentPlaylist.name + " (" + playlistSongs.size() + ")"), cx2 + 50, 12, 0xFFFFFFFF, false);
            int listY = 36, listBot = this.height - CONTROL_H - 8;
            double maxS = Math.max(0, playlistSongs.size() * ROW_H - (listBot - listY));
            scroll = Math.max(0, Math.min(scroll, maxS));
            int start = (int)(scroll / ROW_H);
            for (int i = start; i < Math.min(playlistSongs.size(), start + (listBot - listY) / ROW_H + 1); i++)
            {
                int y = listY + i * ROW_H - (int)scroll;
                drawSongRow(g, playlistSongs.get(i), y, cx2, cw);
            }
            return;
        }
        if (playlists.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal(NeteaseSession.getInstance().isLoggedIn() ? "加载中..." : "未登录"), cx2 + cw / 2, this.height / 3, 0xFF888888);
            return;
        }
        int listY = 36, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, playlists.size() * 26 - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / 26);
        for (int i = start; i < Math.min(playlists.size(), start + (listBot - listY) / 26 + 1); i++)
        {
            NeteasePlaylist pl = playlists.get(i);
            int y = listY + i * 26 - (int)scroll;
            boolean hv = mouseX >= cx2 + 4 && mouseX <= this.width - 4 && mouseY >= y && mouseY <= y + 24;
            if (hv) g.fill(cx2 + 4, y, this.width - 4, y + 24, 0xFF23262F);
            g.drawString(this.font, Component.literal(trunc(pl.name, 30)), cx2 + 12, y + 4, 0xFFCCCCCC, false);
            g.drawString(this.font, Component.literal(pl.trackCount + " 首" + (pl.creatorName != null && !pl.creatorName.isEmpty() ? " · " + pl.creatorName : "")), cx2 + 12, y + 15, 0xFF888888, false);
        }
    }

    // === 搜索页 ===
    private void drawSearch(GuiGraphics g)
    {
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        if (!searchStatus.isEmpty())
            g.drawString(this.font, Component.literal(searchStatus), cx2 + 12, 36, searchStatusColor, false);
        if (searchResults.isEmpty()) return;
        int listY = 52, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, searchResults.size() * ROW_H - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / ROW_H);
        for (int i = start; i < Math.min(searchResults.size(), start + (listBot - listY) / ROW_H + 1); i++)
        {
            int y = listY + i * ROW_H - (int)scroll;
            drawSongRow(g, searchResults.get(i), y, cx2, cw);
        }
    }

    // === 队列页 ===
    private void drawQueue(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        List<NeteaseSong> q = p.getQueue();
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        if (q.isEmpty()) { g.drawCenteredString(this.font, Component.literal("播放队列为空"), cx2 + cw / 2, this.height / 3, 0xFF888888); return; }
        g.drawString(this.font, Component.literal("播放队列 (" + q.size() + ") · 当前 " + (p.getQueueIndex() + 1)), cx2 + 12, 12, 0xFFCCCCCC, false);
        int listY = 36, listBot = this.height - CONTROL_H - 8;
        double maxS = Math.max(0, q.size() * ROW_H - (listBot - listY));
        scroll = Math.max(0, Math.min(scroll, maxS));
        int start = (int)(scroll / ROW_H);
        for (int i = start; i < Math.min(q.size(), start + (listBot - listY) / ROW_H + 1); i++)
        {
            int y = listY + i * ROW_H - (int)scroll;
            drawSongRow(g, q.get(i), y, cx2, cw);
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
                () -> ZephyrConfig.DEFAULT_QUALITY.get(),
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
                () -> String.valueOf(ZephyrConfig.HUD_PANEL_WIDTH.get()),
                () -> (ZephyrConfig.HUD_PANEL_WIDTH.get() - 100) / 500.0,
                r -> ZephyrConfig.HUD_PANEL_WIDTH.set((int) Math.round(100 + r * 500)), null));
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

        SettingSection misc = new SettingSection("通用");
        misc.items.add(new SettingItem("API 地址",
                () -> { String b = ZephyrConfig.API_BASE.get();
                        return b.length() > 26 ? b.substring(0, 25) + "…" : b; },
                () -> { }));
        out.add(misc);
        return out;
    }

    private void drawSettings(GuiGraphics g)
    {
        int x = SIDEBAR_W + 14, y = 36;
        int cw = this.width - SIDEBAR_W - 28;
        int accent = ModernUI.TEXT_ACCENT;

        int rowH = 20;
        int headerH = 22;
        int totalH = 0;
        for (SettingSection s : settingSections) totalH += headerH + s.items.size() * rowH + 6;
        scrollMax = Math.max(0, totalH - (this.height - CONTROL_H - 44));
        double s0 = Math.max(0, Math.min(scroll, scrollMax));

        g.enableScissor(SIDEBAR_W, 36, this.width, this.height - CONTROL_H);
        double sy = y - s0;
        for (SettingSection sec : settingSections)
        {
            // 分组标题（强调色小圆点 + 文本 + 细线）
            g.drawString(this.font, Component.literal("● " + sec.title), x, (int) sy, accent, false);
            g.fill(x + this.font.width(sec.title) + 16, (int) sy + 4, x + cw, (int) sy + 5, 0x28FFFFFF);
            sy += headerH;
            for (SettingItem item : sec.items)
            {
                int iy = (int) sy;
                boolean hovered = mouseX >= x && mouseX <= x + cw && mouseY >= iy && mouseY <= iy + rowH - 2;
                if (hovered) ModernUI.fillRound(g, x - 6, iy - 2, cw + 12, rowH, 6, 0x20FFFFFF);
                g.drawString(this.font, Component.literal(item.name), x, iy + 2, ModernUI.TEXT_SECONDARY, false);
                String val = item.value.get();
                if (item.slider)
                {
                    // 滑块行：左值右滑条
                    g.drawString(this.font, Component.literal(val), x + 96, iy + 2, ModernUI.TEXT_PRIMARY, false);
                    int slX = x + 140, slW = Math.max(40, x + cw - slX);
                    double ratio = item.ratio.get();
                    ratio = Math.max(0, Math.min(1, ratio));
                    // 保存滑块命中区（供点击设置）
                    sliderHit[0] = slX; sliderHit[1] = iy - 2; sliderHit[2] = slW; sliderHit[3] = rowH;
                    ModernUI.drawProgressBar(g, slX, iy + 5, slW, 4, ratio);
                    // 值即 ratio 已画，跳过右侧标签
                }
                else
                {
                    boolean isOn = "ON".equals(val);
                    int vw = this.font.width(val);
                    int pillW = Math.max(34, vw + 16);
                    ModernUI.fillRound(g, x + cw - pillW, iy, pillW, 14, 7,
                            isOn ? 0xFF24405C : 0x30FFFFFF);
                    g.drawCenteredString(this.font, Component.literal(val), x + cw - pillW / 2, iy + 3,
                            isOn ? ModernUI.TEXT_ACCENT : ModernUI.TEXT_DIM);
                    sliderHit[0] = 0; sliderHit[2] = 0;
                }
                lastSettingItem[0] = System.identityHashCode(item);
                sy += rowH;
            }
            sy += 6;
        }
        g.disableScissor();
        if (scroll > scrollMax) scroll = scrollMax;
    }

    private void drawAccount(GuiGraphics g)
    {
        int contentW = this.width - SIDEBAR_W;
        int cx2 = SIDEBAR_W + contentW / 2;
        int y = 36;
        int accent = 0xFF4FC3F7, text = 0xFFFFFFFF, dim = 0xFF888888;

        // ★ 有 cookie 就认为已登录（即使 checkLoginStatus 还没执行）
        boolean hasCookie = !com.zephyr.music.config.ZephyrConfig.COOKIE.get().isEmpty();
        boolean loggedIn = NeteaseSession.getInstance().isLoggedIn() || hasCookie;

        // 已登录：显示用户信息
        if (loggedIn)
        {
            NeteaseUser u = userDetail != null ? userDetail : NeteaseSession.getInstance().getCurrentUser();
            if (u == null) { g.drawCenteredString(this.font, Component.literal("加载中..."), cx2, y + 40, dim); return; }
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
            g.drawCenteredString(this.font, Component.literal("退出登录"), cx2, y + 5, 0xFFFF6666);
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
                g.fill(btnX, y, btnX + btnW, y + btnH, hv ? 0xFF2A3A52 : 0xFF23262F);
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
                g.fill(btnX, y, btnX + btnW, y + 18, hv ? 0xFF2A3A52 : 0xFF23262F);
                g.drawCenteredString(this.font, Component.literal("← 返回"), cx2, y + 5, accent);
            }
            else
            {
                // 等待生成
                int ph = 140, px2 = cx2 - ph / 2;
                g.fill(px2 - 4, y - 4, px2 + ph + 4, y + ph + 4, 0xFFFFFFFF);
                g.drawCenteredString(this.font, Component.literal("..."), cx2, y + ph / 2 - 4, 0xFF888888);
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

    // === 歌曲行（带封面缩略图）===

    // === 歌曲行（带封面缩略图）===
    private void drawSongRow(GuiGraphics g, NeteaseSong song, int y, int contentX, int contentW)
    {
        if (y < 8 || y > this.height - CONTROL_H - 8) return;
        boolean hv = mouseX >= contentX + 4 && mouseX <= this.width - 4 && mouseY >= y && mouseY <= y + ROW_H;
        NeteaseSong curSong = MusicPlayer.getInstance().getCurrentSong();
        boolean isCurrent = curSong != null && curSong.id == song.id;
        if (hv || isCurrent)
        {
            ModernUI.fillRound(g, contentX + 4, y, this.width - contentX - 8, ROW_H, 8,
                    isCurrent ? 0xFF22384E : 0x28FFFFFF);
        }
        // 封面缩略图
        renderCover(g, song.picUrl, contentX + 10, y + 2, 32);
        // 标题
        String title = trunc(song.name, 40);
        g.drawString(this.font, Component.literal(title), contentX + 50, y + 4,
                isCurrent ? ModernUI.TEXT_ACCENT : ModernUI.TEXT_PRIMARY, false);
        // 副标题
        String sub = trunc(song.getDisplayArtist() + " · " + song.getDisplayDuration(), 40);
        g.drawString(this.font, Component.literal(sub), contentX + 50, y + 18, ModernUI.TEXT_DIM, false);
        // 当前播放标记
        if (isCurrent)
            g.drawString(this.font, Component.literal("♪"), contentX + 50 + this.font.width(title) + 6, y + 4, ModernUI.TEXT_ACCENT, false);
    }

    // === 底部控制条 ===
    private void drawControlBar(GuiGraphics g)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int y = this.height - CONTROL_H;
        int cx2 = SIDEBAR_W, cw = this.width - SIDEBAR_W;
        // 玻璃背景
        ModernUI.glassPanel(g, cx2, y, cw, CONTROL_H, 0, ModernUI.GLASS_DARK, 0.55f, true);
        g.fill(cx2, y, this.width, y + 1, 0x40FFFFFF);

        // 进度条（SDF 圆角 + 滑块，可拖拽 seek）
        double pos = p.getPositionSec();
        double total = song != null && song.duration > 0 ? song.duration / 1000.0 : 0;
        double prog = total > 0 ? Math.min(1, pos / total) : 0;
        String t1 = fmtTime(pos), t2 = fmtTime(total);
        int t1w = this.font.width(t1), t2w = this.font.width(t2);
        int barX = cx2 + 24 + t1w + 8;
        int barW = Math.max(40, cw - 40 - t1w - t2w - 32);
        int barY = y + 9;
        progBarX = barX; progBarY = barY - 4; progBarW = barW;
        g.drawString(this.font, Component.literal(t1), cx2 + 24, barY - 1, ModernUI.TEXT_ACCENT, false);
        ModernUI.drawProgressBar(g, barX, barY, barW, 4, prog);
        g.drawString(this.font, Component.literal(t2), barX + barW + 8, barY - 1, ModernUI.TEXT_DIM, false);

        // 按钮行（玻璃按钮 + 随机/循环）
        int by = y + 24;
        boolean hovered;
        hovered = mouseX >= cx2 + 20 && mouseX <= cx2 + 50 && mouseY >= by - 2 && mouseY <= by + 20;
        drawCtrlBtn(g, "⏮", cx2 + 20, by, hovered, false);
        hovered = mouseX >= cx2 + 54 && mouseX <= cx2 + 84 && mouseY >= by - 2 && mouseY <= by + 20;
        drawCtrlBtn(g, p.isPaused() ? "▶" : "⏸", cx2 + 54, by, hovered, true);
        hovered = mouseX >= cx2 + 88 && mouseX <= cx2 + 118 && mouseY >= by - 2 && mouseY <= by + 20;
        drawCtrlBtn(g, "⏭", cx2 + 88, by, hovered, false);
        hovered = mouseX >= cx2 + 122 && mouseX <= cx2 + 152 && mouseY >= by - 2 && mouseY <= by + 20;
        drawCtrlBtn(g, "🔀", cx2 + 122, by, hovered, p.isShuffleMode());
        hovered = mouseX >= cx2 + 156 && mouseX <= cx2 + 186 && mouseY >= by - 2 && mouseY <= by + 20;
        drawCtrlBtn(g, "🔁", cx2 + 156, by, hovered, p.isLoopMode());

        // 音量滑条
        float vol = p.getVolume();
        g.drawString(this.font, Component.literal("♪"), cx2 + 196, by + 6, ModernUI.TEXT_SECONDARY, false);
        volBarX = cx2 + 210; volBarY = by + 8; volBarW = 90;
        ModernUI.drawProgressBar(g, volBarX, volBarY, volBarW, 4, vol);
        g.drawString(this.font, Component.literal(Math.round(vol * 100) + "%"),
                volBarX + volBarW + 6, by + 6, ModernUI.TEXT_DIM, false);

        // 歌曲名（右侧）
        int titleMaxW = Math.max(20, this.width - (cx2 + 348));
        String now = song != null ? trunc(song.name + " - " + song.getDisplayArtist(), titleMaxW) : "未在播放";
        g.drawString(this.font, Component.literal(now), this.width - this.font.width(now) - 10, by + 6, ModernUI.TEXT_SECONDARY, false);
    }

    private void drawCtrlBtn(GuiGraphics g, String label, int x, int y, boolean hovered, boolean active)
    {
        int bg = active ? 0xFF2E4A66 : (hovered ? 0xFF33405A : 0x24FFFFFF);
        ModernUI.fillRound(g, x, y, 30, 18, 6, bg);
        if (active) ModernUI.strokeRound(g, x, y, 30, 18, 6, 0x704FC3F7, 1);
        g.drawCenteredString(this.font, Component.literal(label), x + 15, y + 5,
                active ? ModernUI.TEXT_ACCENT : (hovered ? ModernUI.TEXT_PRIMARY : ModernUI.TEXT_SECONDARY));
    }

    public boolean mouseClicked(double mx, double my, int btn)
    {
        // ★ 进度条检查放在最前面（优先级最高）
        if (mx >= progBarX - 4 && mx <= progBarX + progBarW + 4 && my >= progBarY - 6 && my <= progBarY + 10)
        { progDrag = true; seekMouse(mx); return true; }
        // 音量条
        if (mx >= volBarX - 4 && mx <= volBarX + volBarW + 4 && my >= volBarY - 6 && my <= volBarY + 10)
        { volDrag = true; volMouse(mx); return true; }

        // 侧边栏 Tab
        if (mx < SIDEBAR_W)
        {
            int ty = 40;
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++)
            {
                if (my >= ty && my <= ty + 30) { switchTab(tabs[i]); return true; }
                ty += 34;
            }
            return super.mouseClicked(mx, my, btn);
        }

        // 底部控制按钮
        int by = this.height - CONTROL_H + 24;
        if (my >= by - 2 && my <= by + 20)
        {
            int cx2 = SIDEBAR_W;
            if (mx >= cx2 + 20 && mx <= cx2 + 50) { MusicPlayer.getInstance().prev(); return true; }
            if (mx >= cx2 + 54 && mx <= cx2 + 84) { MusicPlayer p = MusicPlayer.getInstance(); if (p.isPlaying()) { if (p.isPaused()) p.resume(); else p.pause(); } return true; }
            if (mx >= cx2 + 88 && mx <= cx2 + 118) { MusicPlayer.getInstance().next(); return true; }
            if (mx >= cx2 + 122 && mx <= cx2 + 152) { MusicPlayer p = MusicPlayer.getInstance(); p.setShuffleMode(!p.isShuffleMode()); return true; }
            if (mx >= cx2 + 156 && mx <= cx2 + 186) { MusicPlayer p = MusicPlayer.getInstance(); p.setLoopMode(!p.isLoopMode()); return true; }
        }

        // 右侧内容区点击
        int contentX = SIDEBAR_W;
        if (mx >= contentX && my >= 36 && my < this.height - CONTROL_H - 4)
        {
            switch (currentTab)
            {
                case PLAYLIST:
                    if (showPlaylistSongs)
                    {
                        if (mx < contentX + 50 && my < 36) { showPlaylistSongs = false; playlistSongs.clear(); return true; }
                        int listY = 36, rowH = ROW_H;
                        int idx = (int)((my - listY + scroll) / rowH);
                        if (idx >= 0 && idx < playlistSongs.size())
                        { MusicPlayer.getInstance().setQueue(playlistSongs, idx);
                          MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist.id);
                          MusicPlayer.getInstance().playSong(playlistSongs.get(idx)); return true; }
                    }
                    else
                    {
                        int listY = 36, rowH = 26;
                        int idx = (int)((my - listY + scroll) / rowH);
                        if (idx >= 0 && idx < playlists.size()) { openPlaylist(playlists.get(idx)); return true; }
                    }
                    break;
                case SEARCH:
                    int listY2 = 52, rowH2 = ROW_H;
                    int sidx = (int)((my - listY2 + scroll) / rowH2);
                    if (sidx >= 0 && sidx < searchResults.size())
                    { MusicPlayer.getInstance().setQueue(searchResults, sidx);
                      MusicPlayer.getInstance().playSong(searchResults.get(sidx));
                      searchStatus = "正在播放: " + searchResults.get(sidx).name;
                      searchStatusColor = 0xFF4FC3F7; return true; }
                    break;
                case QUEUE:
                    int qListY = 36, qRowH = ROW_H;
                    int qidx = (int)((my - qListY + scroll) / qRowH);
                    if (qidx >= 0 && qidx < MusicPlayer.getInstance().getQueue().size())
                    { MusicPlayer.getInstance().jumpTo(qidx); return true; }
                    break;
                case SETTINGS:
                    toggleSetting((int)(my - 36 + scroll), (int)(mx - contentX), (int)(this.width - contentX));
                    break;
                case ACCOUNT:
                    if (!NeteaseSession.getInstance().isLoggedIn())
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

    private void toggleSetting(int adjY, int adjX, int contentW)
    {
        // 与 drawSettings 相同的布局从上往下数
        int rowH = 20, headerH = 22;
        double s0 = Math.max(0, Math.min(scroll, scrollMax));
        double sy = 36 - s0;
        for (SettingSection sec : settingSections)
        {
            sy += headerH;
            for (SettingItem item : sec.items)
            {
                int iy = (int) sy;
                if (adjY >= iy - 2 && adjY < iy + rowH - 2)
                {
                    if (item.slider && sliderHit[2] > 0 && adjX >= sliderHit[0] && adjX <= sliderHit[0] + sliderHit[2])
                    {
                        double ratio = (double) (adjX - sliderHit[0]) / sliderHit[2];
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
            sy += 6;
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
