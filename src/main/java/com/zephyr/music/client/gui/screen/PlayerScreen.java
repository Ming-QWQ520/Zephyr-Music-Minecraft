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

    @Override
    protected void init()
    {
        MusicPlayer p = MusicPlayer.getInstance();
        int cx = this.width / 2;
        int bottomBarY = this.height - 36;

        // 底部播放控制条（更紧凑）
        addRenderableWidget(Button.builder(Component.literal("🔉"), b -> p.setVolume(p.getVolume() - 0.1f))
                .bounds(cx - 180, bottomBarY, 26, 18).build());
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev())
                .bounds(cx - 145, bottomBarY, 36, 18).build());
        String playBtn = p.isPaused() ? "▶" : (p.isPlaying() ? "⏸" : "▶");
        addRenderableWidget(Button.builder(Component.literal(playBtn), b -> {
                    if (p.isPlaying()) {
                        if (p.isPaused()) p.resume(); else p.pause();
                    }
                })
                .bounds(cx - 100, bottomBarY, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next())
                .bounds(cx + 20, bottomBarY, 36, 18).build());
        addRenderableWidget(Button.builder(Component.literal(p.isLoopMode() ? "🔁" : "➡"), b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁" : "➡"));
                })
                .bounds(cx + 65, bottomBarY, 36, 18).build());
        addRenderableWidget(Button.builder(Component.literal("🔊"), b -> p.setVolume(p.getVolume() + 0.1f))
                .bounds(cx + 110, bottomBarY, 26, 18).build());

        // 顶部紧凑导航栏
        int topY = 6;
        int navY = topY;
        addRenderableWidget(Button.builder(Component.literal("歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen()))
                .bounds(8, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> minecraft.setScreen(new SearchScreen()))
                .bounds(50, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("设置"), b -> minecraft.setScreen(new SettingsScreen()))
                .bounds(92, navY, 40, 16).build());

        String acctText = NeteaseSession.getInstance().isLoggedIn() ? "账号" : "登录";
        addRenderableWidget(Button.builder(Component.literal(acctText), b -> minecraft.setScreen(new LoginScreen()))
                .bounds(this.width - 130, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 22, navY, 14, 16).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        int cx = this.width / 2;

        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();

        // AllMusic 风格配色
        final int COLOR_ACCENT = 0xFF00FFFF;       // 青色强调
        final int COLOR_TEXT = 0xFFFFFFFF;
        final int COLOR_DIM = 0xFFAAAAAA;
        final int COLOR_LYRIC_NEXT = 0xFFCCCCCC;
        final int COLOR_PANEL = 0xE0452A1F;

        // === 顶部歌曲信息卡片（带唱片机图标） ===
        int cardW = Math.min(560, this.width - 40);
        int cardX = cx - cardW / 2;
        int cardY = 28;
        int cardH = 68;
        int recordSize = 48;

        ModernUI.fillRound(g, cardX, cardY, cardW, cardH, 8, COLOR_PANEL);
        ModernUI.strokeRound(g, cardX, cardY, cardW, cardH, 8, 0x80FFA000, 1);

        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("当前没有播放"), cx, cardY + cardH / 2 - 4, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("按 F7 浏览歌单 或 F10 搜索"), cx, cardY + cardH / 2 + 10, 0xFF888888);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // 左侧唱片机图标
        drawRecordPlayer(g, cardX + 10, cardY + (cardH - recordSize) / 2, recordSize, p.isPlaying(), partialTick);

        // 右侧信息
        int textX = cardX + 10 + recordSize + 12;
        int textY = cardY + 10;
        int textW = cardW - (textX - cardX) - 14;

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

        // 当前时间（青色）
        g.drawString(this.font, Component.literal(curTime), textX, textY, COLOR_ACCENT, false);
        // 进度条背景
        g.fill(barX, barY, barX + barAvailW, barY + 2, 0xFF444444);
        // 进度条填充（白色）
        int fillW = (int) (barAvailW * progress);
        if (fillW > 0)
        {
            g.fill(barX, barY, barX + fillW, barY + 2, COLOR_TEXT);
        }
        // 青色滑块
        int sliderX = barX + fillW - 1;
        g.fill(sliderX, barY - 2, sliderX + 4, barY + 4, COLOR_ACCENT);
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

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 绘制唱片机图标（像素风格，AllMusic 风格） */
    private void drawRecordPlayer(GuiGraphics g, int x, int y, int size, boolean isPlaying, float partialTick)
    {
        int COLOR_RECORD = 0xFF5D4037;
        int COLOR_ACCENT = 0xFF00FFFF;

        // 底座
        ModernUI.fillRound(g, x, y, size, size, 4, COLOR_RECORD);
        g.fill(x, y, x + size, y + 1, 0xFF8D6E63);
        g.fill(x, y + size - 1, x + size, y + size, 0xFF3E2723);

        // 唱片
        int discSize = size - 8;
        int discX = x + 4;
        int discY = y + 4;
        fillCircle(g, discX + discSize / 2, discY + discSize / 2, discSize / 2, 0xFF1A1A1A);

        int cx = discX + discSize / 2;
        int cy = discY + discSize / 2;
        for (int r = discSize / 2 - 2; r > 4; r -= 3)
        {
            drawCircleRing(g, cx, cy, r, 0xFF333333);
        }

        // 中央标签
        int labelR = discSize / 4;
        int labelColor = isPlaying ? COLOR_ACCENT : 0xFFFF6B35;
        fillCircle(g, cx, cy, labelR, labelColor);
        fillCircle(g, cx, cy, 2, 0xFFFFFFFF);

        // 旋转指示线
        if (isPlaying)
        {
            float angle = (System.currentTimeMillis() / 50f + partialTick * 10) % 360;
            double rad = Math.toRadians(angle);
            int lineLen = labelR - 2;
            int ex = cx + (int) (Math.cos(rad) * lineLen);
            int ey = cy + (int) (Math.sin(rad) * lineLen);
            drawLine(g, cx, cy, ex, ey, COLOR_ACCENT);
        }

        // 底部按钮
        int btnY = y + size - 6;
        fillCircle(g, x + 8, btnY, 3, 0xFF888888);
        fillCircle(g, x + 14, btnY, 3, isPlaying ? 0xFFFF5555 : 0xFF444444);
    }

    private void fillCircle(GuiGraphics g, int cx, int cy, int r, int color)
    {
        if (r <= 0) return;
        for (int dy = -r; dy <= r; dy++)
        {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private void drawCircleRing(GuiGraphics g, int cx, int cy, int r, int color)
    {
        if (r <= 0) return;
        for (int dy = -r; dy <= r; dy++)
        {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy));
            if (dy == -r || dy == r || dx == r)
            {
                g.fill(cx - dx, cy + dy, cx - dx + 1, cy + dy + 1, color);
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color)
    {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++)
        {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
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
