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

        // === 顶部歌曲信息卡片（紧凑） ===
        int cardW = Math.min(520, this.width - 40);
        int cardX = cx - cardW / 2;
        int cardY = 28;
        int cardH = 56;

        ModernUI.drawCard(g, cardX, cardY, cardW, cardH, 0.5, true);
        int accent = ZephyrConfig.getAccentColor();
        g.fill(cardX + 1, cardY + 6, cardX + 4, cardY + cardH - 6, accent);

        int textX = cardX + 14;
        int textY = cardY + 8;

        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("当前没有播放"), cx, cardY + 22, 0xFFAAAAAA);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // 歌曲名 + 状态
        String songName = truncate(song.name, 35);
        String statePrefix = p.isPaused() ? "⏸ " : (p.isPlaying() ? "▶ " : "■ ");
        g.drawString(this.font, Component.literal(statePrefix + songName), textX, textY, 0xFFFFFFFF, false);

        // 艺术家（右上角）
        NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
        if (u != null)
        {
            String uInfo = "👤 " + truncate(u.nickname, 10);
            int uw = this.font.width(uInfo);
            g.drawString(this.font, Component.literal(uInfo), cardX + cardW - uw - 14, textY, accent, false);
        }

        // 艺术家 + 专辑（小字）
        textY += 12;
        String meta = song.getDisplayArtist() + " · " + (song.album == null || song.album.isEmpty() ? "未知" : truncate(song.album, 18));
        g.drawString(this.font, Component.literal(truncate(meta, 50)), textX, textY, 0xFFCCCCCC, false);

        // 队列信息（右上）
        if (!p.getQueue().isEmpty())
        {
            String qInfo = "队列 " + (p.getQueueIndex() + 1) + "/" + p.getQueue().size();
            int qw = this.font.width(qInfo);
            g.drawString(this.font, Component.literal(qInfo), cardX + cardW - qw - 14, textY, 0xFF888888, false);
        }

        // 进度条（底部）
        textY += 14;
        double pos = p.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        double progress = total > 0 ? Math.min(1, pos / total) : 0;
        ModernUI.drawProgressBar(g, textX, textY, cardW - 28, 4, progress);
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        g.drawString(this.font, Component.literal(timeStr), textX, textY + 6, 0xFF888888, false);

        // === 大型歌词面板（当前歌词居中） ===
        int lyricY = cardY + cardH + 16;
        int lyricH = this.height - 36 - lyricY - 8;
        if (lyricH < 100) lyricH = 100;
        int lyricW = Math.min(700, this.width - 60);
        int lyricX = cx - lyricW / 2;

        // 不画背景卡片（半透明显示，让歌词更醒目）
        // ModernUI.drawCard(g, lyricX, lyricY, lyricW, lyricH, 0.35, false);

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
                // 居中布局：当前行始终在面板正中
                int lineH = 20;  // 大字体行高
                int centerY = lyricY + lyricH / 2;
                int maxLines = Math.max(5, Math.min(11, lyricH / lineH));
                int half = maxLines / 2;

                int activeColor = ZephyrConfig.LYRIC_ACTIVE_COLOR.get();
                int otherColor = ZephyrConfig.LYRIC_OTHER_COLOR.get();

                for (int offset = -half; offset <= half; offset++)
                {
                    int idx = curIdx + offset;
                    if (idx < 0 || idx >= lyrics.size()) continue;

                    LyricLine line = lyrics.get(idx);
                    boolean isActive = (offset == 0);
                    int lineY = centerY + offset * lineH - lineH / 2;

                    // 离中心越远越淡
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

                    int color = isActive ? activeColor : ModernUI.withAlpha(otherColor, alpha);

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
