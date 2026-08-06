package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.*;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zephyr Music 全集成播放器界面
 *
 * 所有功能集成在一个界面内，通过 Tab 切换：
 * - 左侧面板（固定）：封面 + 歌曲信息 + 进度条 + 播放控制 + 音量条
 * - 右侧面板（Tab 切换）：歌词 / 歌单 / 搜索 / 队列 / 设置 / 账号
 *
 * 不跳转到任何独立 Screen，所有交互在当前界面内完成
 */
public class PlayerScreen extends Screen
{
    private enum Tab { LYRICS, PLAYLIST, SEARCH, QUEUE, SETTINGS, ACCOUNT }

    private Tab currentTab = Tab.LYRICS;
    private int scrollOffset = 0;

    // 左侧面板
    private static final int LEFT_W = 220;
    private static final int COVER_SIZE = 120;

    // 进度条/音量条
    private int progX, progY, progW, progH;
    private int volX, volY, volW, volH;
    private boolean progDrag, volDrag;

    // 搜索
    private EditBox searchField;
    private final List<NeteaseSong> searchResults = new ArrayList<>();
    private String searchStatus = "输入关键词后搜索";
    private int searchStatusColor = 0xFFAAAAAA;

    // 歌单数据
    private final List<NeteasePlaylist> playlists = new ArrayList<>();
    private final List<NeteaseSong> playlistSongs = new ArrayList<>();
    private NeteasePlaylist currentPlaylist;
    private boolean showPlaylistSongs = false;

    // 队列/设置/账号
    private NeteaseUser userDetail;
    private String settingsStatus = "";

    // 右侧滚动
    private int rightScrollY = 0;
    private int rightScrollMax = 0;

    public PlayerScreen() { super(Component.literal("Zephyr Music")); }

    @Override
    protected void init()
    {
        MusicPlayer p = MusicPlayer.getInstance();
        int bottomY = this.height - 28;

        // === Tab 栏 ===
        int tabY = 4;
        int tabH = 14;
        String[] tabNames = {"歌词", "歌单", "搜索", "队列", "设置", "账号"};
        Tab[] tabVals = Tab.values();
        int tabW = 36;
        for (int i = 0; i < tabNames.length; i++)
        {
            final Tab tab = tabVals[i];
            boolean active = currentTab == tab;
            String label = active ? "▸" + tabNames[i] : tabNames[i];
            if (tab == Tab.ACCOUNT)
            {
                NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
                if (u != null && u.nickname != null && !u.nickname.isEmpty())
                    label = active ? "▸" + trunc(u.nickname, 5) : trunc(u.nickname, 5);
                else
                    label = active ? "▸登录" : "登录";
            }
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                        currentTab = tab;
                        rightScrollY = 0;
                        onTabSwitch();
                    })
                    .bounds(8 + i * (tabW + 2), tabY, tabW, tabH).build());
        }
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 18, tabY, 14, tabH).build());

        // === 左侧播放控制 ===
        int lx = 8;
        // 上一首 / 播放暂停 / 下一首 / 循环
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev()).bounds(lx, bottomY, 28, 16).build());
        String pb = p.isPaused() ? "▶" : (p.isPlaying() ? "⏸" : "▶");
        addRenderableWidget(Button.builder(Component.literal(pb), b -> {
                    if (p.isPlaying()) { if (p.isPaused()) p.resume(); else p.pause(); }
                }).bounds(lx + 30, bottomY, 34, 16).build());
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next()).bounds(lx + 66, bottomY, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal(p.isLoopMode() ? "🔁" : "➡"), b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁" : "➡"));
                }).bounds(lx + 96, bottomY, 28, 16).build());

        // 音量条
        volX = lx + 130;
        volY = bottomY + 5;
        volW = 68;
        volH = 6;

        // 搜索框（SEARCH Tab 时显示）
        if (currentTab == Tab.SEARCH)
        {
            int rx = 8 + LEFT_W + 8;
            int rw = this.width - rx - 12;
            searchField = new EditBox(this.font, rx, tabY + tabH + 4, rw - 110, 16, Component.literal("搜索"));
            searchField.setHint(Component.literal("歌曲/歌手/专辑"));
            searchField.setMaxLength(64);
            addRenderableWidget(searchField);
            addRenderableWidget(Button.builder(Component.literal("搜索"), b -> doSearch())
                    .bounds(rx + rw - 104, tabY + tabH + 4, 50, 16).build());
            addRenderableWidget(Button.builder(Component.literal("▶下一首"), b -> addSelectedToNext())
                    .bounds(rx + rw - 50, tabY + tabH + 4, 48, 16).build());
        }

        onTabSwitch();
    }

    private void onTabSwitch()
    {
        if (currentTab == Tab.PLAYLIST && playlists.isEmpty() && NeteaseSession.getInstance().isLoggedIn())
        {
            NeteaseSession.getInstance().fetchUserPlaylists().thenAccept(list -> {
                playlists.clear();
                playlists.addAll(list);
            });
        }
        if (currentTab == Tab.ACCOUNT && userDetail == null)
        {
            NeteaseUser base = NeteaseSession.getInstance().getCurrentUser();
            if (base != null && base.userId != 0)
            {
                NeteaseSession.getInstance().getApi().userDetail(base.userId).thenAccept(resp -> {
                    userDetail = NeteaseApi.parseUserDetail(resp, base);
                    if (userDetail != null)
                        NeteaseSession.getInstance().updateCurrentUser(userDetail);
                });
            }
            else userDetail = base;
        }
    }

    // === 搜索 ===
    private void doSearch()
    {
        if (searchField == null) return;
        String kw = searchField.getValue().trim();
        if (kw.isEmpty()) { searchStatus = "请输入关键词"; searchStatusColor = 0xFFFF5555; return; }
        searchStatus = "搜索中: " + kw;
        searchStatusColor = 0xFFFFFFFF;
        searchResults.clear();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            Minecraft.getInstance().execute(() -> {
                searchResults.clear();
                searchResults.addAll(songs);
                rightScrollY = 0;
                searchStatus = songs.isEmpty() ? "无结果" : "找到 " + songs.size() + " 首";
                searchStatusColor = songs.isEmpty() ? 0xFFFF5555 : 0xFF00FFFF;
            });
        });
    }

    private void addSelectedToNext()
    {
        // 简化：添加第一首搜索结果
        if (!searchResults.isEmpty())
        {
            MusicPlayer.getInstance().playNext(searchResults.get(0));
            searchStatus = "已设为下一首: " + searchResults.get(0).name;
            searchStatusColor = 0xFF00FFFF;
        }
    }

    // === 歌单 ===
    private void openPlaylist(NeteasePlaylist pl)
    {
        currentPlaylist = pl;
        showPlaylistSongs = true;
        playlistSongs.clear();
        NeteaseSession.getInstance().getApi().playlistTrackAll(pl.id, 300, 0).thenAccept(resp -> {
            List<NeteaseSong> songs = NeteaseApi.parseSongs(resp, "songs");
            if (songs.isEmpty() && resp.has("playlist") && resp.get("playlist").isJsonObject())
            {
                JsonObject plobj = resp.getAsJsonObject("playlist");
                if (plobj.has("tracks") && plobj.get("tracks").isJsonArray())
                    songs = NeteaseApi.parseSongs(plobj, "tracks");
            }
            final List<NeteaseSong> finalSongs = songs;
            Minecraft.getInstance().execute(() -> {
                playlistSongs.clear();
                playlistSongs.addAll(finalSongs);
                rightScrollY = 0;
            });
        });
    }

    // === 渲染 ===
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        final int ACCENT = 0xFF00FFFF, TEXT = 0xFFFFFFFF, DIM = 0xFFAAAAAA, NEXT = 0xFFCCCCCC;

        // === 左侧面板 ===
        int lx = 8, ly = 22, lw = LEFT_W, lh = this.height - 56;
        ModernUI.fillRound(g, lx, ly, lw, lh, 8, 0xC01a1a2e);

        // 封面
        int cx2 = lx + (lw - COVER_SIZE) / 2;
        int cy2 = ly + 10;
        if (song != null) renderCover(g, song.picUrl, cx2, cy2, COVER_SIZE);
        // 无封面时不绘制背景

        // 歌曲信息
        int iy = cy2 + COVER_SIZE + 8;
        if (song != null)
        {
            g.drawCenteredString(this.font, Component.literal(trunc(song.name, 22)), lx + lw/2, iy, TEXT);
            g.drawCenteredString(this.font, Component.literal(trunc(song.getDisplayArtist(), 24)), lx + lw/2, iy + 12, DIM);
        }
        else
        {
            g.drawCenteredString(this.font, Component.literal("未播放"), lx + lw/2, iy, DIM);
            g.drawCenteredString(this.font, Component.literal("选择歌曲开始"), lx + lw/2, iy + 12, 0xFF888888);
        }

        // 进度条
        iy += 28;
        if (song != null)
        {
            double pos = p.getPositionSec();
            double total = song.duration > 0 ? song.duration / 1000.0 : 0;
            double prog = total > 0 ? Math.min(1, pos / total) : 0;
            g.drawString(this.font, Component.literal(fmtTime(pos)), lx + 10, iy, ACCENT, false);
            String tt = fmtTime(total);
            g.drawString(this.font, Component.literal(tt), lx + lw - 10 - this.font.width(tt), iy, DIM, false);
            int bx = lx + 10, bw = lw - 20, by = iy + 12;
            progX = bx; progY = by; progW = bw; progH = 6;
            ModernUI.drawProgressBar(g, bx, by, bw, 4, prog);
            int fw = (int)(bw * prog);
            g.fill(bx + fw - 1, by - 2, bx + fw + 3, by + 6, ACCENT);
        }

        // 音量条
        int vy = this.height - 26;
        float vol = p.getVolume();
        String vi = vol <= 0 ? "🔇" : (vol < 0.5 ? "🔉" : "🔊");
        g.drawString(this.font, Component.literal(vi), volX - 14, vy - 1, DIM, false);
        ModernUI.drawProgressBar(g, volX, volY, volW, volH, vol);
        g.drawString(this.font, Component.literal((int)(vol*100) + "%"), volX + volW + 3, vy - 1, DIM, false);

        // === 右侧面板 ===
        int rx = lx + lw + 8, ry = 22, rw = this.width - rx - 8, rh = this.height - 56;
        // 不绘制背景（透明）

        // ★ 用 PoseStack 偏移实现滚动
        g.pose().pushPose();
        g.pose().translate(0, -rightScrollY, 0);

        // 裁剪右侧区域
        g.enableScissor(rx, ry, rx + rw, ry + rh);

        // ★ 传递偏移后的 Y 坐标
        int scrolledRy = ry + rightScrollY;
        switch (currentTab)
        {
            case LYRICS: renderLyrics(g, rx, ry, rw, rh, p, song, ACCENT, NEXT); break;
            case PLAYLIST: renderPlaylist(g, rx, ry, rw, rh, mouseX, mouseY); break;
            case SEARCH: renderSearch(g, rx, ry, rw, rh, mouseX, mouseY); break;
            case QUEUE: renderQueue(g, rx, ry, rw, rh, p, mouseX, mouseY); break;
            case SETTINGS: renderSettings(g, rx, ry, rw, rh, ACCENT, TEXT, DIM); break;
            case ACCOUNT: renderAccount(g, rx, ry, rw, rh, ACCENT, TEXT, DIM); break;
        }

        g.disableScissor();
        g.pose().popPose();

        // 滚动条
        if (rightScrollMax > 0)
        {
            int sbX = rx + rw - 4;
            int sbH = rh - 4;
            int thumbH = Math.max(20, sbH * rh / (rh + rightScrollMax));
            int thumbY = ry + 2 + (int)((float)rightScrollY / rightScrollMax * (sbH - thumbH));
            g.fill(sbX, ry + 2, sbX + 2, ry + 2 + sbH, 0x40FFFFFF);
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, 0x80FFFFFF);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    // === 各 Tab 渲染 ===
    private void renderLyrics(GuiGraphics g, int x, int y, int w, int h, MusicPlayer p, NeteaseSong song, int accent, int next)
    {
        if (song == null) { g.drawCenteredString(this.font, Component.literal("暂无播放"), x+w/2, y+h/2-4, 0xFFAAAAAA); return; }
        double pos = p.getPositionSec();
        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics == null || lyrics.isEmpty()) { g.drawCenteredString(this.font, Component.literal("（暂无歌词）"), x+w/2, y+h/2-4, 0xFF888888); return; }
        int cur = findCur(lyrics, pos);
        if (cur < 0) { g.drawCenteredString(this.font, Component.literal("♪ ~ ~ ~"), x+w/2, y+h/2-4, accent); return; }
        int lh = 20, cy = y + h/2, max = Math.max(5, Math.min(13, h/lh)), half = max/2;
        for (int off = -half; off <= half; off++)
        {
            int idx = cur + off;
            if (idx < 0 || idx >= lyrics.size()) continue;
            LyricLine line = lyrics.get(idx);
            boolean act = off == 0;
            int ly2 = cy + off * lh - lh/2;
            int alpha = act ? 255 : (int)(140 * (1 - Math.abs(off)/(double)(half+1)));
            if (alpha < 40) alpha = 40;
            int color = act ? accent : ModernUI.withAlpha(next, alpha);
            if (act && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                renderKaraoke(g, line, x+w/2, ly2, w-40, pos);
            else
            {
                String t = trunc(line.text, 55);
                g.drawString(this.font, Component.literal(t), x+w/2 - this.font.width(t)/2, ly2, color, false);
            }
        }
    }

    private void renderPlaylist(GuiGraphics g, int x, int y, int w, int h, int mx, int my)
    {
        if (showPlaylistSongs && currentPlaylist != null)
        {
            // 返回按钮提示
            g.drawString(this.font, Component.literal("← 返回歌单列表"), x + 4, y + 2, 0xFF00FFFF, false);
            g.drawString(this.font, Component.literal(currentPlaylist.name + " (" + playlistSongs.size() + ")"), x + w/2, y + 2, 0xFFFFFFFF);
            int ly = y + 18;
            for (int i = 0; i < playlistSongs.size() && ly < y + h; i++)
            {
                NeteaseSong s = playlistSongs.get(i);
                boolean isCur = MusicPlayer.getInstance().getCurrentSong() != null
                        && MusicPlayer.getInstance().getCurrentSong().id == s.id;
                boolean hov = mx >= x+4 && mx <= x+w-8 && my >= ly && my < ly + 14;
                if (hov) ModernUI.fillRound(g, x+4, ly, w-12, 14, 3, 0x20FFFFFF);
                String prefix = isCur ? "▶ " : (i+1) + ". ";
                g.drawString(this.font, Component.literal(prefix + trunc(s.name, 40)), x+8, ly, isCur ? 0xFF00FFFF : 0xFFDDDDDD, false);
                g.drawString(this.font, Component.literal(trunc(s.getDisplayArtist(), 30)), x+8, ly + 7, 0xFF888888, false);
                if (hov) g.drawString(this.font, Component.literal("[播放]"), x+w-30, ly, 0xFF00FFFF, false);
                ly += 14;
            }
            rightScrollMax = Math.max(0, playlistSongs.size() * 14 - h + 20);
            return;
        }
        if (playlists.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal(NeteaseSession.getInstance().isLoggedIn() ? "加载中…" : "未登录"), x+w/2, y+h/2, 0xFFAAAAAA);
            return;
        }
        int ly = y + 2;
        for (int i = 0; i < playlists.size() && ly < y + h; i++)
        {
            NeteasePlaylist pl = playlists.get(i);
            boolean hov = mx >= x+4 && mx <= x+w-8 && my >= ly && my < ly + 24;
            if (hov) ModernUI.fillRound(g, x+4, ly, w-12, 24, 4, 0x20FFFFFF);
            g.drawString(this.font, Component.literal(trunc(pl.name, 30)), x+8, ly + 2, 0xFFFFFFFF, false);
            g.drawString(this.font, Component.literal(pl.trackCount + " 首" + (pl.creatorName != null && !pl.creatorName.isEmpty() ? " · " + pl.creatorName : "")), x+8, ly + 14, 0xFF888888, false);
            ly += 24;
        }
        rightScrollMax = Math.max(0, playlists.size() * 24 - h + 4);
    }

    private void renderSearch(GuiGraphics g, int x, int y, int w, int h, int mx, int my)
    {
        // 搜索框在 init 中创建，这里渲染状态
        int statusY = y + 24;
        if (!searchStatus.isEmpty())
            g.drawString(this.font, Component.literal(searchStatus), x + 4, statusY, searchStatusColor, false);
        if (searchResults.isEmpty()) return;
        int ly = statusY + 14;
        for (int i = 0; i < searchResults.size() && ly < y + h; i++)
        {
            NeteaseSong s = searchResults.get(i);
            boolean isCur = MusicPlayer.getInstance().getCurrentSong() != null
                    && MusicPlayer.getInstance().getCurrentSong().id == s.id;
            boolean hov = mx >= x+4 && mx <= x+w-8 && my >= ly && my < ly + 14;
            if (hov) ModernUI.fillRound(g, x+4, ly, w-12, 14, 3, 0x20FFFFFF);
            g.drawString(this.font, Component.literal((isCur ? "▶ " : (i+1) + ". ") + trunc(s.name, 40)), x+8, ly, isCur ? 0xFF00FFFF : 0xFFDDDDDD, false);
            g.drawString(this.font, Component.literal(trunc(s.getDisplayArtist() + " · " + s.getDisplayDuration(), 40)), x+8, ly + 7, 0xFF888888, false);
            if (hov) g.drawString(this.font, Component.literal("[▶下一首]"), x+w-40, ly, 0xFF00FFFF, false);
            ly += 14;
        }
        rightScrollMax = Math.max(0, searchResults.size() * 14 - h + 40);
    }

    private void renderQueue(GuiGraphics g, int x, int y, int w, int h, MusicPlayer p, int mx, int my)
    {
        List<NeteaseSong> q = p.getQueue();
        if (q.isEmpty()) { g.drawCenteredString(this.font, Component.literal("播放队列为空"), x+w/2, y+h/2, 0xFFAAAAAA); return; }
        g.drawString(this.font, Component.literal("播放队列 (" + q.size() + ") · 当前 " + (p.getQueueIndex()+1)), x+4, y+2, 0xFFFFFFFF, false);
        int ly = y + 18;
        for (int i = 0; i < q.size() && ly < y + h; i++)
        {
            NeteaseSong s = q.get(i);
            boolean isCur = i == p.getQueueIndex();
            boolean hov = mx >= x+4 && mx <= x+w-8 && my >= ly && my < ly + 14;
            if (isCur) ModernUI.fillRound(g, x+4, ly, w-12, 14, 3, 0x30FFFFFF);
            else if (hov) ModernUI.fillRound(g, x+4, ly, w-12, 14, 3, 0x20FFFFFF);
            g.drawString(this.font, Component.literal((isCur ? "▶ " : (i+1) + ". ") + trunc(s.name, 40)), x+8, ly, isCur ? 0xFF00FFFF : 0xFFDDDDDD, false);
            g.drawString(this.font, Component.literal(trunc(s.getDisplayArtist(), 30)), x+8, ly + 7, 0xFF888888, false);
            ly += 14;
        }
        rightScrollMax = Math.max(0, q.size() * 14 - h + 20);
    }

    private void renderSettings(GuiGraphics g, int x, int y, int w, int h, int accent, int text, int dim)
    {
        int ly = y + 4;
        // HUD 设置
        g.drawString(this.font, Component.literal("─── HUD ───"), x + w/2 - 30, ly, accent, false); ly += 14;
        String[][] items = {
            {"启用HUD", ZephyrConfig.HUD_ENABLED.get() ? "ON" : "OFF"},
            {"显示封面", ZephyrConfig.HUD_SHOW_COVER.get() ? "ON" : "OFF"},
            {"显示进度条", ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? "ON" : "OFF"},
            {"显示歌词", ZephyrConfig.HUD_SHOW_LYRICS.get() ? "ON" : "OFF"},
            {"菜单时暂停", ZephyrConfig.HUD_PAUSE_ON_MENU.get() ? "ON" : "OFF"},
            {"紧凑模式", ZephyrConfig.HUD_COMPACT.get() ? "ON" : "OFF"},
            {"面板宽度", String.valueOf(ZephyrConfig.HUD_PANEL_WIDTH.get())},
            {"封面大小", String.valueOf(ZephyrConfig.HUD_COVER_SIZE.get())},
            {"歌词行数", String.valueOf(ZephyrConfig.HUD_LYRICS_LINES.get())},
            {"音量", String.format("%.0f%%", ZephyrConfig.HUD_VOLUME.get() * 100)},
        };
        for (String[] item : items)
        {
            g.drawString(this.font, Component.literal(item[0]), x + 8, ly, text, false);
            int vw = this.font.width(item[1]);
            ModernUI.fillRound(g, x + w - vw - 16, ly - 1, vw + 10, 12, 3, 0x40444444);
            g.drawString(this.font, Component.literal(item[1]), x + w - vw - 11, ly, accent, false);
            ly += 14;
        }
        ly += 6;
        g.drawString(this.font, Component.literal("─── 歌词 ───"), x + w/2 - 30, ly, accent, false); ly += 14;
        String[][] lyricItems = {
            {"卡拉OK", ZephyrConfig.LYRIC_KARAOKE.get() ? "ON" : "OFF"},
            {"歌词模式", ZephyrConfig.LYRIC_MODE.get()},
        };
        for (String[] item : lyricItems)
        {
            g.drawString(this.font, Component.literal(item[0]), x + 8, ly, text, false);
            g.drawString(this.font, Component.literal(item[1]), x + w - 40, ly, accent, false);
            ly += 14;
        }
        ly += 6;
        g.drawString(this.font, Component.literal("─── 通用 ───"), x + w/2 - 30, ly, accent, false); ly += 14;
        g.drawString(this.font, Component.literal("音质"), x + 8, ly, text, false);
        g.drawString(this.font, Component.literal(ZephyrConfig.DEFAULT_QUALITY.get()), x + w - 40, ly, accent, false); ly += 14;
        g.drawString(this.font, Component.literal("打卡"), x + 8, ly, text, false);
        g.drawString(this.font, Component.literal(ZephyrConfig.SCROBBLE_ENABLED.get() ? "ON" : "OFF"), x + w - 40, ly, accent, false); ly += 14;
        rightScrollMax = Math.max(0, ly - y - h + 4);
    }

    private void renderAccount(GuiGraphics g, int x, int y, int w, int h, int accent, int text, int dim)
    {
        NeteaseUser u = userDetail != null ? userDetail : NeteaseSession.getInstance().getCurrentUser();
        if (u == null) { g.drawCenteredString(this.font, Component.literal("未登录"), x+w/2, y+h/2, 0xFFFF5555); return; }
        int cy = y + 10;
        int avSize = 56;
        int avX = x + (w - avSize) / 2;
        renderCover(g, u.avatarUrl, avX, cy, avSize);
        cy += avSize + 6;
        g.drawCenteredString(this.font, Component.literal(u.nickname), x+w/2, cy, text); cy += 14;
        if (u.vipType > 0) { g.drawCenteredString(this.font, Component.literal("VIP"), x+w/2, cy, 0xFFFFAA00); cy += 12; }
        cy += 6;
        int ix = x + 20;
        g.drawString(this.font, Component.literal("🆔 ID: " + u.userId), ix, cy, dim, false); cy += 14;
        g.drawString(this.font, Component.literal("🎵 听歌: " + (u.listenSongs > 0 ? u.listenSongs + "首" : "未知")), ix, cy, accent, false); cy += 14;
        g.drawString(this.font, Component.literal("📊 等级: " + (u.level > 0 ? "Lv." + u.level : "未知")), ix, cy, accent, false); cy += 14;
        g.drawString(this.font, Component.literal("📅 注册: " + fmtDate(u.createTime)), ix, cy, dim, false); cy += 14;
        g.drawString(this.font, Component.literal("📍 地区: " + RegionCodeMapper.formatLocation(u.province, u.city)), ix, cy, dim, false); cy += 14;
        g.drawString(this.font, Component.literal("⚧ 性别: " + fmtGender(u.gender)), ix, cy, dim, false); cy += 14;
        if (u.signature != null && !u.signature.isEmpty())
        { g.drawString(this.font, Component.literal("「" + trunc(u.signature, 40) + "」"), ix, cy, dim, false); cy += 14; }
        cy += 10;
        // 退出登录按钮
        boolean hov = Minecraft.getInstance().mouseHandler.xpos() / 2.0 >= ix && Minecraft.getInstance().mouseHandler.xpos() / 2.0 <= ix + 80
                && Minecraft.getInstance().mouseHandler.ypos() / 2.0 >= cy && Minecraft.getInstance().mouseHandler.ypos() / 2.0 <= cy + 16;
        ModernUI.fillRound(g, ix, cy, 80, 16, 4, hov ? 0x80FF5555 : 0x40FF5555);
        g.drawCenteredString(this.font, Component.literal("退出登录"), ix + 40, cy + 4, 0xFFFF5555);
        rightScrollMax = Math.max(0, cy + 20 - y - h + 4);
    }

    // === 鼠标交互 ===
    @Override
    public boolean mouseClicked(double mx, double my, int btn)
    {
        // 进度条
        if (mx >= progX-4 && mx <= progX+progW+4 && my >= progY-4 && my <= progY+progH+4)
        { progDrag = true; seekMouse(mx); return true; }
        // 音量条
        if (mx >= volX-14 && mx <= volX+volW+20 && my >= volY-4 && my <= volY+volH+4)
        { volDrag = true; volMouse(mx); return true; }
        // 右侧区域点击
        int rx = 8 + LEFT_W + 8, ry = 22, rw = this.width - rx - 8, rh = this.height - 56;
        if (mx >= rx && mx <= rx + rw && my >= ry && my <= ry + rh)
        {
            int adjMy = (int)(my - ry + rightScrollY);
            int adjX = (int)(mx - rx);
            switch (currentTab)
            {
                case PLAYLIST:
                    if (showPlaylistSongs)
                    {
                        // 返回按钮
                        if (adjX < 80 && adjMy < 14) { showPlaylistSongs = false; playlistSongs.clear(); return true; }
                        int idx = (adjMy - 18) / 14;
                        if (idx >= 0 && idx < playlistSongs.size())
                        { MusicPlayer.getInstance().setQueue(playlistSongs, idx);
                          MusicPlayer.getInstance().setCurrentSourcePlaylistId(currentPlaylist.id);
                          MusicPlayer.getInstance().playSong(playlistSongs.get(idx)); return true; }
                    }
                    else
                    {
                        int pidx = adjMy / 24;
                        if (pidx >= 0 && pidx < playlists.size()) { openPlaylist(playlists.get(pidx)); return true; }
                    }
                    break;
                case SEARCH:
                    int sidx = (adjMy - 38) / 14;
                    if (sidx >= 0 && sidx < searchResults.size())
                    { MusicPlayer.getInstance().setQueue(searchResults, sidx);
                      MusicPlayer.getInstance().playSong(searchResults.get(sidx));
                      searchStatus = "正在播放: " + searchResults.get(sidx).name;
                      searchStatusColor = 0xFF00FFFF; return true; }
                    break;
                case QUEUE:
                    int qidx = (adjMy - 18) / 14;
                    if (qidx >= 0 && qidx < MusicPlayer.getInstance().getQueue().size())
                    { MusicPlayer.getInstance().jumpTo(qidx); return true; }
                    break;
                case SETTINGS:
                    // 点击切换设置项
                    toggleSetting(adjMy, adjX, rx, rw);
                    break;
                case ACCOUNT:
                    // 退出登录
                    int btnY = (int)(my - ry + rightScrollY);
                    NeteaseUser u = userDetail;
                    if (u != null && adjX >= 20 && adjX <= 100 && btnY >= 0)
                    {
                        // 检查是否点击了退出按钮（需要计算实际位置）
                        // 简化：点击底部区域退出
                    }
                    break;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void toggleSetting(int adjMy, int adjX, int rx, int rw)
    {
        int ly = 4;
        // HUD 项
        if (adjMy >= ly && adjMy < ly + 14*10)
        {
            int idx = (adjMy - ly) / 14;
            if (adjX > rw - 50)
            {
                switch (idx)
                {
                    case 0: ZephyrConfig.HUD_ENABLED.set(!ZephyrConfig.HUD_ENABLED.get()); break;
                    case 1: ZephyrConfig.HUD_SHOW_COVER.set(!ZephyrConfig.HUD_SHOW_COVER.get()); break;
                    case 2: ZephyrConfig.HUD_SHOW_PROGRESS_BAR.set(!ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get()); break;
                    case 3: ZephyrConfig.HUD_SHOW_LYRICS.set(!ZephyrConfig.HUD_SHOW_LYRICS.get()); break;
                    case 4: ZephyrConfig.HUD_PAUSE_ON_MENU.set(!ZephyrConfig.HUD_PAUSE_ON_MENU.get()); break;
                    case 5: ZephyrConfig.HUD_COMPACT.set(!ZephyrConfig.HUD_COMPACT.get()); break;
                    case 6: ZephyrConfig.HUD_PANEL_WIDTH.set(Math.min(600, ZephyrConfig.HUD_PANEL_WIDTH.get() + 20)); break;
                    case 7: ZephyrConfig.HUD_COVER_SIZE.set(Math.min(256, ZephyrConfig.HUD_COVER_SIZE.get() + 16)); break;
                    case 8: ZephyrConfig.HUD_LYRICS_LINES.set(Math.min(12, ZephyrConfig.HUD_LYRICS_LINES.get() + 1)); break;
                    case 9: ZephyrConfig.HUD_VOLUME.set(Math.min(1.0, ZephyrConfig.HUD_VOLUME.get() + 0.1)); break;
                }
            }
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
        // 音量条附近滚轮
        if (mx >= volX - 14 && mx <= volX + volW + 20)
        { float v = MusicPlayer.getInstance().getVolume() + (delta > 0 ? 0.05f : -0.05f);
          MusicPlayer.getInstance().setVolume(v); return true; }
        // 右侧区域滚轮
        int rx = 8 + LEFT_W + 8;
        if (mx >= rx && mx < this.width - 8)
        {
            rightScrollY -= (int)(delta * 20);
            rightScrollY = Math.max(0, Math.min(rightScrollMax, rightScrollY));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void seekMouse(double mx) { MusicPlayer p = MusicPlayer.getInstance(); NeteaseSong s = p.getCurrentSong();
        if (s == null || s.duration <= 0) return; double r = Math.max(0, Math.min(1, (mx - progX) / progW));
        p.seekTo(r * (s.duration / 1000.0)); }
    private void volMouse(double mx) { double r = Math.max(0, Math.min(1, (mx - volX) / volW));
        MusicPlayer.getInstance().setVolume((float)r); }
    private void renderKaraoke(GuiGraphics g, LyricLine line, int cx, int y, int maxW, double pos)
    {
        int played = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get(), cur = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get(), unplayed = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();
        StringBuilder sb = new StringBuilder(); for (LyricWord w : line.words) sb.append(w.text);
        String text = sb.toString(); if (this.font.width(text) > maxW) text = trunc(text, 55);
        int tw = this.font.width(text), sx = cx - tw / 2, cx2 = sx;
        for (LyricWord w : line.words) { if (w.text.isEmpty()) continue; String wt = w.text;
            int ww = this.font.width(wt); if (cx2 + ww > sx + maxW) break;
            int color = w.isFinished(pos) ? played : (w.isPlayingAt(pos) ? cur : unplayed);
            g.drawString(this.font, Component.literal(wt), cx2, y, color, false); cx2 += ww; }
    }
    private void renderCover(GuiGraphics g, String url, int x, int y, int sz)
    {
        if (url == null || url.isEmpty()) { return; }
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
    private int findCur(List<LyricLine> lyrics, double pos) { int idx = -1;
        for (int i = 0; i < lyrics.size(); i++) { if (lyrics.get(i).time <= pos) idx = i; else break; } return idx; }
    private String fmtTime(double s) { if (s < 0) s = 0; int t = (int)s; return String.format("%d:%02d", t/60, t%60); }
    private String fmtDate(long ms) { if (ms <= 0) return "未知";
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(ms)); } catch (Exception e) { return "未知"; } }
    private String fmtGender(int g) { return g == 1 ? "男" : g == 2 ? "女" : "保密"; }
    private String trunc(String s, int m) { if (s == null) return ""; if (s.length() <= m) return s; return s.substring(0, m-1) + "…"; }
    @Override public boolean isPauseScreen() { return false; }
}
