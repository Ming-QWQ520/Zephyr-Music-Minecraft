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
 * 现代化主播放器界面
 *
 * - 顶部导航栏
 * - 中央歌曲信息卡片（含状态）
 * - 逐字歌词面板（大字体、当前行高亮、卡拉OK 模式）
 * - 底部播放控制条（圆角按钮）
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
        int bottomBarY = this.height - 50;

        // 底部播放控制条
        int ctrlY = bottomBarY;
        // 音量-
        addRenderableWidget(Button.builder(Component.literal("🔉"), b -> p.setVolume(p.getVolume() - 0.1f))
                .bounds(cx - 170, ctrlY, 30, 20).build());
        // 上一首
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev())
                .bounds(cx - 130, ctrlY, 40, 20).build());
        // 播放/暂停
        String playBtn = p.isPaused() ? "▶" : (p.isPlaying() ? "⏸" : "▶");
        addRenderableWidget(Button.builder(Component.literal(playBtn), b -> {
                    if (p.isPlaying()) {
                        if (p.isPaused()) p.resume(); else p.pause();
                    }
                })
                .bounds(cx - 80, ctrlY, 60, 20).build());
        // 下一首
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next())
                .bounds(cx + 20, ctrlY, 40, 20).build());
        // 循环
        String loopText = p.isLoopMode() ? "🔁" : "➡";
        addRenderableWidget(Button.builder(Component.literal(loopText), b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁" : "➡"));
                })
                .bounds(cx + 70, ctrlY, 40, 20).build());
        // 音量+
        addRenderableWidget(Button.builder(Component.literal("🔊"), b -> p.setVolume(p.getVolume() + 0.1f))
                .bounds(cx + 120, ctrlY, 30, 20).build());

        // 顶部导航
        int topY = 10;
        int navY = topY;
        addRenderableWidget(Button.builder(Component.literal("≡ 我的歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen()))
                .bounds(10, navY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("🔍 搜索"), b -> minecraft.setScreen(new SearchScreen()))
                .bounds(105, navY, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⚙ 设置"), b -> minecraft.setScreen(new SettingsScreen()))
                .bounds(180, navY, 60, 20).build());

        // 右上角登录/账号
        String acctText = NeteaseSession.getInstance().isLoggedIn() ? "👤 账号" : "🔒 登录";
        addRenderableWidget(Button.builder(Component.literal(acctText), b -> minecraft.setScreen(new LoginScreen()))
                .bounds(this.width - 170, navY, 70, 20).build());

        // 返回游戏
        addRenderableWidget(Button.builder(Component.literal("✕ 关闭"), b -> onClose())
                .bounds(this.width - 80, navY, 60, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        int cx = this.width / 2;

        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();

        // === 顶部歌曲信息卡片 ===
        int cardW = Math.min(560, this.width - 40);
        int cardX = cx - cardW / 2;
        int cardY = 40;
        int cardH = 110;

        ModernUI.drawCard(g, cardX, cardY, cardW, cardH);

        // 左侧色条
        int accent = ZephyrConfig.getAccentColor();
        g.fill(cardX + 1, cardY + 8, cardX + 4, cardY + cardH - 8, accent);

        int textX = cardX + 16;
        int textY = cardY + 14;

        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("当前没有播放"), cx, cardY + 40, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("按 F7 浏览歌单 或 F10 搜索"), cx, cardY + 56, 0xFF888888);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // 歌曲名（大字）
        String songName = truncate(song.name, 40);
        g.drawString(this.font, Component.literal("♪ " + songName), textX, textY, 0xFFFFFFFF, false);

        // 艺术家 + 专辑
        textY += 14;
        String meta = song.getDisplayArtist() + " · " + (song.album == null || song.album.isEmpty() ? "未知专辑" : song.album);
        g.drawString(this.font, Component.literal(truncate(meta, 60)), textX, textY, 0xFFCCCCCC, false);

        // 用户信息（右上）
        NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
        if (u != null)
        {
            String uInfo = "👤 " + u.nickname;
            int uw = this.font.width(uInfo);
            g.drawString(this.font, Component.literal(uInfo), cardX + cardW - uw - 16, cardY + 14, accent, false);
        }

        // 状态徽章
        textY += 14;
        String status = p.isPaused() ? "⏸ 已暂停" : (p.isPlaying() ? "▶ 正在播放" : "■ 已停止");
        g.drawString(this.font, Component.literal(status), textX, textY, accent, false);

        // 队列信息
        if (!p.getQueue().isEmpty())
        {
            String qInfo = "队列 " + (p.getQueueIndex() + 1) + "/" + p.getQueue().size();
            int qw = this.font.width(qInfo);
            g.drawString(this.font, Component.literal(qInfo), cardX + cardW - qw - 16, textY, 0xFFAAAAAA, false);
        }

        // 进度条
        textY += 16;
        double pos = p.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        double progress = total > 0 ? Math.min(1, pos / total) : 0;

        int barX = textX;
        int barW = cardW - 32;
        int barY = textY;
        ModernUI.drawProgressBar(g, barX, barY, barW, 6, progress);

        // 时间
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        g.drawString(this.font, Component.literal(timeStr), barX, barY + 10, 0xFFAAAAAA, false);

        // === 歌词面板 ===
        int lyricY = cardY + cardH + 20;
        int lyricH = this.height - 50 - lyricY - 10;
        if (lyricH < 80) lyricH = 80;
        int lyricW = Math.min(600, this.width - 60);
        int lyricX = cx - lyricW / 2;

        // 歌词背景卡片
        ModernUI.drawCard(g, lyricX, lyricY, lyricW, lyricH);

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
                        cx, lyricY + lyricH / 2 - 4, 0xFF888888);
            }
            else
            {
                int maxLines = Math.max(3, lyricH / 18);
                int half = maxLines / 2;
                int startIdx = Math.max(0, curIdx - half);
                int endIdx = Math.min(lyrics.size() - 1, startIdx + maxLines - 1);
                startIdx = Math.max(0, endIdx - maxLines + 1);

                int lineY = lyricY + (lyricH - maxLines * 18) / 2;
                int activeColor = ZephyrConfig.LYRIC_ACTIVE_COLOR.get();
                int otherColor = ZephyrConfig.LYRIC_OTHER_COLOR.get();

                for (int i = startIdx; i <= endIdx; i++)
                {
                    LyricLine line = lyrics.get(i);
                    boolean isActive = (i == curIdx);

                    if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                    {
                        renderKaraokeLineCentered(g, line, cx, lineY, lyricW - 40, pos, activeColor);
                    }
                    else
                    {
                        String text = truncate(line.text, 50);
                        int color = isActive ? activeColor : otherColor;
                        int tw = this.font.width(text);
                        g.drawString(this.font, Component.literal(text), cx - tw / 2, lineY, color, false);
                    }
                    lineY += 18;
                }
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderKaraokeLineCentered(GuiGraphics g, LyricLine line, int cx, int y, int maxW, double pos, int activeColor)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();

        // 计算整行宽度
        StringBuilder fullText = new StringBuilder();
        for (LyricWord w : line.words) fullText.append(w.text);
        String text = fullText.toString();
        if (text.length() > 50) text = text.substring(0, 50) + "…";
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
