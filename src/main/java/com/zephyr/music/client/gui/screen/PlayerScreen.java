package com.zephyr.music.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.LyricWord;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Zephyr Music 播放器主界面 - 彻底重构
 *
 * 布局参考 Zephyr Music 桌面版：
 * - 顶部 Tab 栏：播放/歌单/搜索/队列/设置/账号
 * - 左侧面板：封面 + 歌曲信息 + 进度条 + 播放控制 + 音量条
 * - 右侧主区域：根据 Tab 显示不同内容（歌词/歌单/搜索/队列/设置/账号）
 */
public class PlayerScreen extends Screen
{
    /** 当前选中的 Tab */
    private enum Tab { PLAYER, PLAYLIST, SEARCH, QUEUE, SETTINGS, ACCOUNT }
    private Tab currentTab = Tab.PLAYER;

    // 左侧面板尺寸
    private static final int LEFT_PANEL_W = 220;
    private static final int COVER_SIZE = 120;

    // 进度条/音量条拖动
    private int progBarX, progBarY, progBarW, progBarH;
    private int volBarX, volBarY, volBarW, volBarH;
    private boolean progDragging = false;
    private boolean volDragging = false;

    // 子界面引用（延迟创建）
    private PlaylistBrowserScreen playlistScreen;
    private SearchScreen searchScreen;
    private QueueScreen queueScreen;
    private SettingsScreen settingsScreen;
    private AccountScreen accountScreen;

    public PlayerScreen() { super(Component.literal("Zephyr Music")); }

    @Override
    protected void init()
    {
        MusicPlayer p = MusicPlayer.getInstance();
        int bottomY = this.height - 30;

        // === 顶部 Tab 栏 ===
        int tabY = 4;
        int tabW = 42;
        int tabGap = 2;
        int tabStartX = 8;
        String[] tabs = {"播放", "歌单", "搜索", "队列", "设置", "账号"};
        Tab[] tabVals = Tab.values();
        for (int i = 0; i < tabs.length; i++)
        {
            final Tab tab = tabVals[i];
            boolean active = (currentTab == tab);
            String label = active ? "▶" + tabs[i] : tabs[i];
            // 账号 Tab 显示用户名
            if (tab == Tab.ACCOUNT)
            {
                NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
                if (u != null && u.nickname != null && !u.nickname.isEmpty())
                    label = active ? "▶" + truncate(u.nickname, 6) : truncate(u.nickname, 6);
                else
                    label = active ? "▶登录" : "登录";
            }
            addRenderableWidget(Button.builder(Component.literal(label), b -> switchTab(tab))
                    .bounds(tabStartX + i * (tabW + tabGap), tabY, tabW, 14).build());
        }

        // 关闭按钮
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 18, tabY, 14, 14).build());

        // === 左侧面板播放控制（始终显示） ===
        int leftX = 8;
        int ctrlY = bottomY;

        // 上一首
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev())
                .bounds(leftX, ctrlY, 30, 16).build());
        // 播放/暂停
        String playBtn = p.isPaused() ? "▶" : (p.isPlaying() ? "⏸" : "▶");
        addRenderableWidget(Button.builder(Component.literal(playBtn), b -> {
                    if (p.isPlaying()) { if (p.isPaused()) p.resume(); else p.pause(); }
                })
                .bounds(leftX + 32, ctrlY, 36, 16).build());
        // 下一首
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next())
                .bounds(leftX + 70, ctrlY, 30, 16).build());
        // 循环
        addRenderableWidget(Button.builder(Component.literal(p.isLoopMode() ? "🔁" : "➡"), b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁" : "➡"));
                })
                .bounds(leftX + 102, ctrlY, 30, 16).build());

        // 音量条位置
        volBarX = leftX + 136;
        volBarY = ctrlY + 5;
        volBarW = 70;
        volBarH = 6;
    }

    private void switchTab(Tab tab)
    {
        currentTab = tab;
        // 重建 widgets
        clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // 不绘制默认背景（透明）
        // renderBackground(g); // 注释掉，不要泥土背景

        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        int cx = this.width / 2;

        final int COLOR_ACCENT = 0xFF00FFFF;
        final int COLOR_TEXT = 0xFFFFFFFF;
        final int COLOR_DIM = 0xFFAAAAAA;
        final int COLOR_LYRIC_NEXT = 0xFFCCCCCC;

        // === 左侧面板 ===
        int leftX = 8;
        int leftY = 22;
        int panelW = LEFT_PANEL_W;
        int panelH = this.height - 60;

        // 面板背景（半透明深色）
        ModernUI.fillRound(g, leftX, leftY, panelW, panelH, 8, 0xC01a1a2e);

        // 封面
        int coverX = leftX + (panelW - COVER_SIZE) / 2;
        int coverY = leftY + 12;
        if (song != null)
        {
            renderCover(g, song.picUrl, coverX, coverY, COVER_SIZE);
        }
        else
        {
            ModernUI.fillRound(g, coverX, coverY, COVER_SIZE, COVER_SIZE, 8, 0xFF1A1A1A);
            g.drawCenteredString(this.font, Component.literal("♪"), coverX + COVER_SIZE / 2, coverY + COVER_SIZE / 2 - 4, COLOR_DIM);
        }

        // 歌曲信息
        int infoY = coverY + COVER_SIZE + 10;
        if (song != null)
        {
            String songName = truncate(song.name, 22);
            g.drawCenteredString(this.font, Component.literal(songName), leftX + panelW / 2, infoY, COLOR_TEXT);
            infoY += 12;
            g.drawCenteredString(this.font, Component.literal(truncate(song.getDisplayArtist(), 24)),
                    leftX + panelW / 2, infoY, COLOR_DIM);
        }
        else
        {
            g.drawCenteredString(this.font, Component.literal("未播放"), leftX + panelW / 2, infoY, COLOR_DIM);
            infoY += 12;
            g.drawCenteredString(this.font, Component.literal("按歌单/搜索选择"), leftX + panelW / 2, infoY, 0xFF888888);
        }

        // 进度条
        infoY += 18;
        if (song != null)
        {
            double pos = p.getPositionSec();
            double total = song.duration > 0 ? song.duration / 1000.0 : 0;
            double progress = total > 0 ? Math.min(1, pos / total) : 0;

            String curTime = formatTime(pos);
            String totTime = formatTime(total);
            int timeY = infoY;
            g.drawString(this.font, Component.literal(curTime), leftX + 10, timeY, COLOR_ACCENT, false);
            int totW = this.font.width(totTime);
            g.drawString(this.font, Component.literal(totTime), leftX + panelW - 10 - totW, timeY, COLOR_DIM, false);

            // 进度条
            int barX = leftX + 10;
            int barW = panelW - 20;
            int barY = infoY + 12;
            progBarX = barX; progBarY = barY; progBarW = barW; progBarH = 6;
            ModernUI.drawProgressBar(g, barX, barY, barW, 4, progress);
            // 滑块
            int fillW = (int)(barW * progress);
            g.fill(barX + fillW - 1, barY - 2, barX + fillW + 3, barY + 6, COLOR_ACCENT);
        }

        // 音量条
        int volY = this.height - 28;
        float vol = p.getVolume();
        String volIcon = vol <= 0 ? "🔇" : (vol < 0.5 ? "🔉" : "🔊");
        g.drawString(this.font, Component.literal(volIcon), volBarX - 14, volY - 1, COLOR_DIM, false);
        ModernUI.drawProgressBar(g, volBarX, volBarY, volBarW, volBarH, vol);
        String volText = (int)(vol * 100) + "%";
        g.drawString(this.font, Component.literal(volText), volBarX + volBarW + 3, volY - 1, COLOR_DIM, false);

        // === 右侧主区域（根据 Tab 显示不同内容） ===
        int rightX = leftX + panelW + 8;
        int rightW = this.width - rightX - 8;
        int rightY = 22;
        int rightH = this.height - 60;

        switch (currentTab)
        {
            case PLAYER:
                renderLyricsPanel(g, rightX, rightY, rightW, rightH, p, song, COLOR_ACCENT, COLOR_LYRIC_NEXT);
                break;
            case PLAYLIST:
                renderPlaylistTab(g, rightX, rightY, rightW, rightH, mouseX, mouseY, partialTick);
                break;
            case SEARCH:
                renderSearchTab(g, rightX, rightY, rightW, rightH, mouseX, mouseY, partialTick);
                break;
            case QUEUE:
                renderQueueTab(g, rightX, rightY, rightW, rightH, mouseX, mouseY, partialTick);
                break;
            case SETTINGS:
                renderSettingsTab(g, rightX, rightY, rightW, rightH, mouseX, mouseY, partialTick);
                break;
            case ACCOUNT:
                renderAccountTab(g, rightX, rightY, rightW, rightH, COLOR_ACCENT, COLOR_TEXT, COLOR_DIM);
                break;
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 歌词面板 */
    private void renderLyricsPanel(GuiGraphics g, int x, int y, int w, int h,
            MusicPlayer p, NeteaseSong song, int accentColor, int lyricNextColor)
    {
        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("暂无播放"), x + w / 2, y + h / 2 - 4, 0xFFAAAAAA);
            return;
        }
        double pos = p.getPositionSec();
        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics == null || lyrics.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal("（暂无歌词）"), x + w / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }
        int curIdx = findCurrentIndex(lyrics, pos);
        if (curIdx < 0)
        {
            g.drawCenteredString(this.font, Component.literal("♪ ~ ~ ~"), x + w / 2, y + h / 2 - 4, accentColor);
            return;
        }
        int lineH = 20;
        int centerY = y + h / 2;
        int maxLines = Math.max(5, Math.min(13, h / lineH));
        int half = maxLines / 2;
        for (int offset = -half; offset <= half; offset++)
        {
            int idx = curIdx + offset;
            if (idx < 0 || idx >= lyrics.size()) continue;
            LyricLine line = lyrics.get(idx);
            boolean isActive = (offset == 0);
            int lineY = centerY + offset * lineH - lineH / 2;
            int alpha;
            if (isActive) alpha = 255;
            else
            {
                double fadeRatio = 1.0 - Math.abs(offset) / (double)(half + 1);
                alpha = (int)(140 * fadeRatio);
                if (alpha < 40) alpha = 40;
            }
            int color = isActive ? accentColor : ModernUI.withAlpha(lyricNextColor, alpha);
            if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
            {
                renderKaraokeLine(g, line, x + w / 2, lineY, w - 40, pos);
            }
            else
            {
                String text = truncate(line.text, 55);
                int tw = this.font.width(text);
                g.drawString(this.font, Component.literal(text), x + w / 2 - tw / 2, lineY, color, false);
            }
        }
    }

    /** 歌单 Tab（简化版 - 直接跳转到独立界面） */
    private void renderPlaylistTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt)
    {
        g.drawCenteredString(this.font, Component.literal("点击查看完整歌单 →"), x + w / 2, y + h / 2 - 10, 0xFFAAAAAA);
        g.drawCenteredString(this.font, Component.literal("或在此区域浏览歌单"), x + w / 2, y + h / 2 + 6, 0xFF888888);
    }
    private void renderSearchTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt)
    {
        g.drawCenteredString(this.font, Component.literal("点击查看完整搜索 →"), x + w / 2, y + h / 2 - 10, 0xFFAAAAAA);
    }
    private void renderQueueTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt)
    {
        MusicPlayer mp = MusicPlayer.getInstance();
        List<NeteaseSong> queue = mp.getQueue();
        if (queue.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal("播放队列为空"), x + w / 2, y + h / 2, 0xFFAAAAAA);
            return;
        }
        g.drawString(this.font, Component.literal("播放队列 (" + queue.size() + ")"), x + 4, y + 2, 0xFFFFFFFF, false);
        int lineY = y + 18;
        int maxShow = Math.min(queue.size(), (h - 24) / 14);
        for (int i = 0; i < maxShow; i++)
        {
            NeteaseSong s = queue.get(i);
            boolean isCurrent = (i == mp.getQueueIndex());
            String prefix = isCurrent ? "▶ " : (i + 1) + ". ";
            String text = prefix + truncate(s.name, 40);
            int color = isCurrent ? 0xFF00FFFF : 0xFFDDDDDD;
            g.drawString(this.font, Component.literal(text), x + 4, lineY, color, false);
            lineY += 14;
        }
    }
    private void renderSettingsTab(GuiGraphics g, int x, int y, int w, int h, int mx, int my, float pt)
    {
        g.drawCenteredString(this.font, Component.literal("点击查看完整设置 →"), x + w / 2, y + h / 2, 0xFFAAAAAA);
    }
    private void renderAccountTab(GuiGraphics g, int x, int y, int w, int h, int accent, int text, int dim)
    {
        NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
        if (u == null)
        {
            g.drawCenteredString(this.font, Component.literal("未登录"), x + w / 2, y + h / 2, 0xFFFF5555);
            return;
        }
        int cy = y + 20;
        // 头像
        int avatarSize = 56;
        int avatarX = x + (w - avatarSize) / 2;
        renderCover(g, u.avatarUrl, avatarX, cy, avatarSize);
        cy += avatarSize + 8;
        // 昵称
        g.drawCenteredString(this.font, Component.literal(u.nickname), x + w / 2, cy, text);
        cy += 14;
        // 信息
        int infoX = x + 20;
        int infoX2 = x + w / 2;
        g.drawString(this.font, Component.literal("🆔 ID: " + u.userId), infoX, cy, dim, false);
        cy += 12;
        g.drawString(this.font, Component.literal("🎵 听歌: " + (u.listenSongs > 0 ? u.listenSongs : "未知")), infoX, cy, accent, false);
        cy += 12;
        g.drawString(this.font, Component.literal("📊 等级: " + (u.level > 0 ? "Lv." + u.level : "未知")), infoX, cy, accent, false);
        cy += 12;
        g.drawString(this.font, Component.literal("📅 注册: " + formatCreateTime(u.createTime)), infoX, cy, dim, false);
        cy += 12;
        g.drawString(this.font, Component.literal("📍 地区: " + com.zephyr.music.api.RegionCodeMapper.formatLocation(u.province, u.city)), infoX, cy, dim, false);
        cy += 12;
        g.drawString(this.font, Component.literal("⚧ 性别: " + formatGender(u.gender)), infoX, cy, dim, false);
    }

    // === 工具方法 ===
    private void renderCover(GuiGraphics g, String picUrl, int x, int y, int size)
    {
        if (picUrl == null || picUrl.isEmpty())
        {
            ModernUI.fillRound(g, x, y, size, size, 8, 0xFF1A1A1A);
            g.drawCenteredString(this.font, Component.literal("♪"), x + size / 2, y + size / 2 - 4, 0xFF888888);
            return;
        }
        ResourceLocation texId = CoverTextureManager.getInstance().getCover(picUrl, null);
        if (texId != null)
        {
            RenderSystem.setShaderTexture(0, texId);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(texId, x, y, 0, 0, size, size, size, size);
            RenderSystem.disableBlend();
        }
        else
        {
            ModernUI.fillRound(g, x, y, size, size, 8, 0xFF1A1A1A);
            g.drawCenteredString(this.font, Component.literal("..."), x + size / 2, y + size / 2 - 4, 0xFF888888);
        }
    }

    private void renderKaraokeLine(GuiGraphics g, LyricLine line, int cx, int y, int maxW, double pos)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();
        StringBuilder fullText = new StringBuilder();
        for (LyricWord w : line.words) fullText.append(w.text);
        String text = fullText.toString();
        if (this.font.width(text) > maxW) text = truncate(text, 55);
        int totalW = this.font.width(text);
        int startX = cx - totalW / 2;
        int curX = startX;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            String wt = w.text;
            int wWidth = this.font.width(wt);
            if (curX + wWidth > startX + maxW) break;
            int color;
            if (w.isFinished(pos)) color = playedColor;
            else if (w.isPlayingAt(pos))
            {
                double progress = w.getProgress(pos);
                int playedWidth = (int)Math.round(wWidth * progress);
                color = (playedWidth > 0) ? currentColor : unplayedColor;
            }
            else color = unplayedColor;
            g.drawString(this.font, Component.literal(wt), curX, y, color, false);
            curX += wWidth;
        }
    }

    // === 鼠标交互 ===
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        // 进度条
        if (mouseX >= progBarX - 4 && mouseX <= progBarX + progBarW + 4
                && mouseY >= progBarY - 4 && mouseY <= progBarY + progBarH + 4)
        {
            progDragging = true; seekFromMouse(mouseX); return true;
        }
        // 音量条
        if (mouseX >= volBarX - 14 && mouseX <= volBarX + volBarW + 20
                && mouseY >= volBarY - 4 && mouseY <= volBarY + volBarH + 4)
        {
            volDragging = true; updateVolumeFromMouse(mouseX); return true;
        }
        // 双击 Tab 标签可以跳转到独立界面
        if (currentTab == Tab.PLAYLIST && button == 0)
        {
            // 点击歌单 Tab 区域中心跳转
            int rightX = 8 + LEFT_PANEL_W + 8;
            int rightW = this.width - rightX - 8;
            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= 22 && mouseY <= this.height - 38)
            {
                minecraft.setScreen(new PlaylistBrowserScreen());
                return true;
            }
        }
        if (currentTab == Tab.SEARCH && button == 0)
        {
            int rightX = 8 + LEFT_PANEL_W + 8;
            int rightW = this.width - rightX - 8;
            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= 22 && mouseY <= this.height - 38)
            {
                minecraft.setScreen(new SearchScreen());
                return true;
            }
        }
        if (currentTab == Tab.SETTINGS && button == 0)
        {
            int rightX = 8 + LEFT_PANEL_W + 8;
            int rightW = this.width - rightX - 8;
            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= 22 && mouseY <= this.height - 38)
            {
                minecraft.setScreen(new SettingsScreen());
                return true;
            }
        }
        if (currentTab == Tab.ACCOUNT && button == 0)
        {
            int rightX = 8 + LEFT_PANEL_W + 8;
            int rightW = this.width - rightX - 8;
            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= 22 && mouseY <= this.height - 38)
            {
                if (NeteaseSession.getInstance().isLoggedIn())
                    minecraft.setScreen(new AccountScreen());
                else
                    minecraft.setScreen(new LoginScreen());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
    {
        if (progDragging) { seekFromMouse(mouseX); return true; }
        if (volDragging) { updateVolumeFromMouse(mouseX); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        if (progDragging) { progDragging = false; return true; }
        if (volDragging) { volDragging = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta)
    {
        if (mouseX >= volBarX - 14 && mouseX <= volBarX + volBarW + 20)
        {
            float vol = MusicPlayer.getInstance().getVolume();
            vol += (delta > 0 ? 0.05f : -0.05f);
            MusicPlayer.getInstance().setVolume(vol);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void updateVolumeFromMouse(double mouseX)
    {
        double rel = (mouseX - volBarX) / volBarW;
        rel = Math.max(0, Math.min(1, rel));
        MusicPlayer.getInstance().setVolume((float)rel);
    }

    private void seekFromMouse(double mouseX)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        if (song == null || song.duration <= 0) return;
        double rel = (mouseX - progBarX) / progBarW;
        rel = Math.max(0, Math.min(1, rel));
        p.seekTo(rel * (song.duration / 1000.0));
    }

    private int findCurrentIndex(List<LyricLine> lyrics, double posSec)
    {
        int idx = -1;
        for (int i = 0; i < lyrics.size(); i++)
        {
            if (lyrics.get(i).time <= posSec) idx = i;
            else break;
        }
        return idx;
    }

    private String formatTime(double sec)
    {
        if (sec < 0) sec = 0;
        int total = (int)sec;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    private String formatCreateTime(long ms)
    {
        if (ms <= 0) return "未知";
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(ms)); }
        catch (Exception e) { return "未知"; }
    }

    private String formatGender(int gender)
    {
        switch (gender) { case 1: return "男"; case 2: return "女"; default: return "保密"; }
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    @Override public boolean isPauseScreen() { return false; }
}
