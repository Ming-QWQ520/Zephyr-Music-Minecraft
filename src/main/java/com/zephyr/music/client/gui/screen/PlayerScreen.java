package com.zephyr.music.client.gui.screen;

import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.LyricWord;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 现代化主播放器界面（v3 - 更紧凑、歌词居中）
 *
 * - 顶部紧凑导航栏
 * - 中央歌曲信息卡片（小）
 * - 大型歌词面板（当前歌词始终居中显示）
 * - 底部播放控制条
 */
public class PlayerScreen extends Screen
{
    public PlayerScreen()
    {
        super(Component.literal("Zephyr Music"));
    }

    /** 音量条区域（用于鼠标拖动检测） */
    private int volBarX, volBarY, volBarW, volBarH;
    private boolean volDragging = false;
    /** ★ 进度条区域（用于鼠标拖动检测） */
    private int progBarX, progBarY, progBarW, progBarH;
    private boolean progDragging = false;

    @Override
    protected void init()
    {
        MusicPlayer p = MusicPlayer.getInstance();
        int cx = this.width / 2;
        int bottomBarY = this.height - 36;

        // 底部播放控制条
        // 上一首
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev())
                .bounds(cx - 140, bottomBarY, 36, 18).build());
        // 播放/暂停
        String playBtn = p.isPaused() ? "▶" : (p.isPlaying() ? "⏸" : "▶");
        addRenderableWidget(Button.builder(Component.literal(playBtn), b -> {
                    if (p.isPlaying()) {
                        if (p.isPaused()) p.resume(); else p.pause();
                    }
                })
                .bounds(cx - 95, bottomBarY, 50, 18).build());
        // 下一首
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next())
                .bounds(cx + 25, bottomBarY, 36, 18).build());
        // 循环
        addRenderableWidget(Button.builder(Component.literal(p.isLoopMode() ? "🔁" : "➡"), b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁" : "➡"));
                })
                .bounds(cx + 70, bottomBarY, 36, 18).build());

        // ★ 音量条（可拖动）位置
        volBarX = cx + 120;
        volBarY = bottomBarY + 6;
        volBarW = 80;
        volBarH = 6;

        // 顶部紧凑导航栏
        int topY = 6;
        int navY = topY;
        addRenderableWidget(Button.builder(Component.literal("歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen()))
                .bounds(8, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> minecraft.setScreen(new SearchScreen()))
                .bounds(50, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("设置"), b -> minecraft.setScreen(new SettingsScreen()))
                .bounds(92, navY, 40, 16).build());

        // ★ 登录后显示完整用户名，按钮宽度自适应
        NeteaseUser curUser = NeteaseSession.getInstance().getCurrentUser();
        String acctText = (curUser != null && curUser.nickname != null && !curUser.nickname.isEmpty())
                ? curUser.nickname : "登录";
        int acctBtnW = Math.max(40, this.font.width(acctText) + 12);
        addRenderableWidget(Button.builder(Component.literal(acctText), b -> {
                    if (NeteaseSession.getInstance().isLoggedIn())
                        minecraft.setScreen(new AccountScreen());
                    else
                        minecraft.setScreen(new LoginScreen());
                })
                .bounds(this.width - acctBtnW - 22, navY, acctBtnW, 16).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 22, navY, 14, 16).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // 不绘制默认泥土背景
        // renderBackground(g);
        int cx = this.width / 2;

        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();

        // AllMusic 风格配色
        final int COLOR_ACCENT = 0xFF00FFFF;       // 青色强调
        final int COLOR_TEXT = 0xFFFFFFFF;
        final int COLOR_DIM = 0xFFAAAAAA;
        final int COLOR_LYRIC_NEXT = 0xFFCCCCCC;

        // === 顶部歌曲信息卡片（带封面图） ===
        int cardW = Math.min(560, this.width - 40);
        int cardX = cx - cardW / 2;
        int cardY = 28;
        int cardH = 80;
        int coverSize = 64;

        // 默认无背景无边框（按配置）
        double bgOpacity = ZephyrConfig.HUD_BG_OPACITY.get();
        if (bgOpacity > 0.001)
        {
            ModernUI.fillRound(g, cardX, cardY, cardW, cardH, 8, ZephyrConfig.getBgColor(bgOpacity));
        }
        if (ZephyrConfig.HUD_SHOW_BORDER.get())
        {
            ModernUI.strokeRound(g, cardX, cardY, cardW, cardH, 8, 0x80FFA000, 1);
        }

        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("当前没有播放"), cx, cardY + cardH / 2 - 4, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("按 F7 浏览歌单 或 F10 搜索"), cx, cardY + cardH / 2 + 10, 0xFF888888);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // 左侧封面图
        if (ZephyrConfig.HUD_SHOW_COVER.get())
        {
            renderCover(g, this.font, song.picUrl, cardX + 8, cardY + (cardH - coverSize) / 2, coverSize);
        }

        // 右侧信息
        int coverOffset = ZephyrConfig.HUD_SHOW_COVER.get() ? coverSize + 16 : 14;
        int textX = cardX + coverOffset;
        int textY = cardY + 10;
        int textW = cardW - coverOffset - 14;

        // 歌曲名（带状态前缀，白色）
        String statePrefix = p.isPaused() ? "⏸ " : (p.isPlaying() ? "▶ " : "■ ");
        String songName = truncate(song.name, 38);
        g.drawString(this.font, Component.literal(statePrefix + songName), textX, textY, COLOR_TEXT, false);

        // 用户信息（右上角，青色）
        NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
        if (u != null)
        {
            String uInfo = "👤 " + truncate(u.nickname, 10);
            int uw = this.font.width(uInfo);
            g.drawString(this.font, Component.literal(uInfo), cardX + cardW - uw - 14, textY, COLOR_ACCENT, false);
        }

        // 艺术家 + 专辑（灰色小字）
        textY += 12;
        String meta = song.getDisplayArtist() + " · " + (song.album == null || song.album.isEmpty() ? "未知" : truncate(song.album, 18));
        g.drawString(this.font, Component.literal(truncate(meta, 50)), textX, textY, COLOR_DIM, false);

        // 队列信息（右上）
        if (!p.getQueue().isEmpty())
        {
            String qInfo = "队列 " + (p.getQueueIndex() + 1) + "/" + p.getQueue().size();
            int qw = this.font.width(qInfo);
            g.drawString(this.font, Component.literal(qInfo), cardX + cardW - qw - 14, textY, 0xFF888888, false);
        }

        // 进度条（AllMusic 风格：左时间 + 长条 + 右时间 + 青色滑块）
        textY += 16;
        double pos = p.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        double progress = total > 0 ? Math.min(1, pos / total) : 0;

        String curTime = formatTime(pos);
        String totTime = formatTime(total);
        int curW = this.font.width(curTime);
        int totW = this.font.width(totTime);
        int barX = textX + curW + 6;
        int barAvailW = textW - curW - totW - 12;
        int barY = textY + 4;

        // ★ 保存进度条位置用于鼠标拖动
        progBarX = barX;
        progBarY = barY;
        progBarW = barAvailW;
        progBarH = 6;

        // 当前时间（青色）
        g.drawString(this.font, Component.literal(curTime), textX, textY, COLOR_ACCENT, false);
        // ★ 进度条用 ModernUI.drawProgressBar（圆角，可点击）
        ModernUI.drawProgressBar(g, barX, barY, barAvailW, 4, progress);
        // 青色滑块
        int fillW = (int) (barAvailW * progress);
        int sliderX = barX + fillW - 1;
        g.fill(sliderX, barY - 2, sliderX + 4, barY + 6, COLOR_ACCENT);
        // 总时间（灰色）
        g.drawString(this.font, Component.literal(totTime), textX + textW - totW, textY, COLOR_DIM, false);

        // === 大型歌词面板（当前歌词居中，AllMusic 双行对照风格） ===
        int lyricY = cardY + cardH + 16;
        int lyricH = this.height - 36 - lyricY - 8;
        if (lyricH < 100) lyricH = 100;
        int lyricW = Math.min(700, this.width - 60);

        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics == null || lyrics.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal("（暂无歌词或加载中）"),
                    cx, lyricY + lyricH / 2 - 4, 0xFF888888);
        }
        else
        {
            int curIdx = findCurrentIndex(lyrics, pos);
            if (curIdx < 0)
            {
                g.drawCenteredString(this.font, Component.literal("♪ ~ ~ ~"),
                        cx, lyricY + lyricH / 2 - 4, COLOR_ACCENT);
            }
            else
            {
                int lineH = 22;
                int centerY = lyricY + lyricH / 2;
                int maxLines = Math.max(5, Math.min(11, lyricH / lineH));
                int half = maxLines / 2;

                for (int offset = -half; offset <= half; offset++)
                {
                    int idx = curIdx + offset;
                    if (idx < 0 || idx >= lyrics.size()) continue;

                    LyricLine line = lyrics.get(idx);
                    boolean isActive = (offset == 0);
                    int lineY = centerY + offset * lineH - lineH / 2;

                    int alpha;
                    if (isActive)
                    {
                        alpha = 255;
                    }
                    else
                    {
                        double fadeRatio = 1.0 - Math.abs(offset) / (double) (half + 1);
                        alpha = (int) (140 * fadeRatio);
                        if (alpha < 40) alpha = 40;
                    }

                    int color = isActive ? COLOR_ACCENT : ModernUI.withAlpha(COLOR_LYRIC_NEXT, alpha);

                    if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                    {
                        renderKaraokeLineCentered(g, line, cx, lineY, lyricW - 80, pos);
                    }
                    else
                    {
                        String text = truncate(line.text, 55);
                        int tw = this.font.width(text);
                        g.drawString(this.font, Component.literal(text), cx - tw / 2, lineY, color, false);
                    }
                }
            }
        }

        // ★ 绘制音量条（在 super.render 之前，这样按钮会覆盖在上面）
        float vol = MusicPlayer.getInstance().getVolume();
        // 音量图标
        String volIcon = vol <= 0 ? "🔇" : (vol < 0.5 ? "🔉" : "🔊");
        g.drawString(this.font, Component.literal(volIcon), volBarX - 16, volBarY - 4, 0xFFCCCCCC, false);
        // 音量条背景
        ModernUI.drawProgressBar(g, volBarX, volBarY, volBarW, volBarH, vol);
        // 音量百分比
        String volText = (int)(vol * 100) + "%";
        g.drawString(this.font, Component.literal(volText), volBarX + volBarW + 4, volBarY - 4, 0xFFAAAAAA, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** ★ 鼠标点击音量条 / 进度条 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        // 检查进度条
        if (mouseX >= progBarX - 4 && mouseX <= progBarX + progBarW + 4
                && mouseY >= progBarY - 4 && mouseY <= progBarY + progBarH + 4)
        {
            progDragging = true;
            seekFromMouse(mouseX);
            return true;
        }
        // 检查音量条
        if (mouseX >= volBarX - 16 && mouseX <= volBarX + volBarW + 20
                && mouseY >= volBarY - 4 && mouseY <= volBarY + volBarH + 4)
        {
            volDragging = true;
            updateVolumeFromMouse(mouseX);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** ★ 鼠标拖动 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
    {
        if (progDragging) { seekFromMouse(mouseX); return true; }
        if (volDragging) { updateVolumeFromMouse(mouseX); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** ★ 鼠标释放 */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        if (progDragging) { progDragging = false; return true; }
        if (volDragging) { volDragging = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** ★ 鼠标滚轮调节音量 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta)
    {
        if (mouseX >= volBarX - 20 && mouseX <= volBarX + volBarW + 20)
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
        MusicPlayer.getInstance().setVolume((float) rel);
    }

    /** ★ 从鼠标位置 seek 播放进度 */
    private void seekFromMouse(double mouseX)
    {
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();
        if (song == null || song.duration <= 0) return;
        double rel = (mouseX - progBarX) / progBarW;
        rel = Math.max(0, Math.min(1, rel));
        double targetSec = rel * (song.duration / 1000.0);
        p.seekTo(targetSec);
    }

    /** 渲染封面图（PlayerScreen 用大图） */
    private void renderCover(GuiGraphics g, net.minecraft.client.gui.Font font, String picUrl, int x, int y, int size)
    {
        if (picUrl == null || picUrl.isEmpty())
        {
            // 占位
            g.fill(x, y, x + size, y + size, 0xFF1A1A1A);
            String t = "♪";
            int tw = font.width(t);
            g.drawString(font, Component.literal(t), x + (size - tw) / 2, y + size / 2 - 4, 0xFF888888, false);
            return;
        }

        com.zephyr.music.client.audio.CoverTextureManager tm = com.zephyr.music.client.audio.CoverTextureManager.getInstance();
        net.minecraft.resources.ResourceLocation texId = tm.getCover(picUrl, null);
        if (texId != null)
        {
            com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, texId);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(texId, x, y, 0, 0, size, size, size, size);
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
        else
        {
            // 加载中
            g.fill(x, y, x + size, y + size, 0xFF1A1A1A);
            String t = "...";
            int tw = font.width(t);
            g.drawString(font, Component.literal(t), x + (size - tw) / 2, y + size / 2 - 4, 0xFF888888, false);
        }
    }

    private void renderKaraokeLineCentered(GuiGraphics g, LyricLine line, int cx, int y, int maxW, double pos)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();

        StringBuilder fullText = new StringBuilder();
        for (LyricWord w : line.words) fullText.append(w.text);
        String text = fullText.toString();
        if (this.font.width(text) > maxW)
        {
            text = truncate(text, 55);
        }
        int totalW = this.font.width(text);
        int startX = cx - totalW / 2;

        int curX = startX;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            String wt = w.text;
            if (curX + this.font.width(wt) > startX + maxW) break;
            int color;
            if (w.isFinished(pos))
            {
                color = playedColor;
            }
            else if (w.isPlayingAt(pos))
            {
                color = currentColor;
            }
            else
            {
                color = unplayedColor;
            }
            g.drawString(this.font, Component.literal(wt), curX, y, color, false);
            curX += this.font.width(wt);
        }
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
        int total = (int) sec;
        int m = total / 60;
        int s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
