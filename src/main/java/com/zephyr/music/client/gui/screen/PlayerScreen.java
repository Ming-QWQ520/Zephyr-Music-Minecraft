package com.zephyr.music.client.gui.screen;

import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.client.audio.MusicPlayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 主播放器界面 - 显示歌曲信息、播放控件、当前歌词
 */
public class PlayerScreen extends Screen
{
    public PlayerScreen()
    {
        super(Component.literal("Zephyr Music · 正在播放"));
    }

    @Override
    protected void init()
    {
        MusicPlayer p = MusicPlayer.getInstance();
        int cx = this.width / 2;

        // 控制按钮
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> p.prev())
                .bounds(cx - 90, this.height - 60, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(p.isPaused() ? "▶" : "⏸"),
                b -> {
                    if (p.isPlaying()) {
                        if (p.isPaused()) p.resume(); else p.pause();
                    }
                })
                .bounds(cx - 40, this.height - 60, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> p.next())
                .bounds(cx + 30, this.height - 60, 30, 20).build());

        // 循环模式
        addRenderableWidget(Button.builder(Component.literal(p.isLoopMode() ? "🔁 单曲" : "🔁"),
                b -> {
                    p.setLoopMode(!p.isLoopMode());
                    b.setMessage(Component.literal(p.isLoopMode() ? "🔁 单曲" : "🔁"));
                })
                .bounds(cx + 70, this.height - 60, 60, 20).build());

        // 音量按钮
        addRenderableWidget(Button.builder(Component.literal("🔉"),
                b -> p.setVolume(p.getVolume() - 0.1f))
                .bounds(cx - 130, this.height - 60, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("🔊"),
                b -> p.setVolume(p.getVolume() + 0.1f))
                .bounds(cx + 140, this.height - 60, 30, 20).build());

        // 上方按钮 - 歌单 / 登录
        addRenderableWidget(Button.builder(Component.literal("我的歌单"), b -> {
            minecraft.setScreen(new PlaylistBrowserScreen());
        }).bounds(10, 10, 80, 20).build());

        addRenderableWidget(Button.builder(Component.literal(NeteaseSession.getInstance().isLoggedIn() ? "账号" : "登录"),
                b -> minecraft.setScreen(new LoginScreen()))
                .bounds(this.width - 90, 10, 80, 20).build());

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(this.width - 90, this.height - 30, 80, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        MusicPlayer p = MusicPlayer.getInstance();
        NeteaseSong song = p.getCurrentSong();

        // 标题
        g.drawCenteredString(this.font, this.getTitle(), cx, 14, 0xFFFFFFFF);

        // 用户信息
        NeteaseUser u = NeteaseSession.getInstance().getCurrentUser();
        if (u != null)
        {
            String info = "👤 " + u.nickname;
            g.drawString(this.font, Component.literal(info), 100, 14, 0xFF1DB954, false);
        }

        if (song == null)
        {
            g.drawCenteredString(this.font, Component.literal("当前没有播放"),
                    cx, this.height / 2 - 30, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("请按 F7 打开歌单选择歌曲"),
                    cx, this.height / 2 - 10, 0xFFAAAAAA);
            return;
        }

        // 歌曲信息卡片
        int cardY = 60;
        int cardW = 360;
        int cardX = cx - cardW / 2;
        int cardH = 100;
        g.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0xB0222222);
        // 边框
        g.fill(cardX, cardY, cardX + cardW, cardY + 1, 0xFF1DB954);
        g.fill(cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, 0xFF1DB954);
        g.fill(cardX, cardY, cardX + 1, cardY + cardH, 0xFF1DB954);
        g.fill(cardX + cardW - 1, cardY, cardX + cardW, cardY + cardH, 0xFF1DB954);

        g.drawCenteredString(this.font, Component.literal("♪ " + song.name),
                cx, cardY + 14, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(song.getDisplayArtist()),
                cx, cardY + 30, 0xFFCCCCCC);
        g.drawCenteredString(this.font, Component.literal("专辑: " + (song.album.isEmpty() ? "未知" : song.album)),
                cx, cardY + 46, 0xFFAAAAAA);

        // 进度条
        double pos = p.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        double progress = total > 0 ? Math.min(1, pos / total) : 0;
        int barX = cardX + 20;
        int barW = cardW - 40;
        int barY = cardY + 70;
        g.fill(barX, barY, barX + barW, barY + 6, 0xFF333333);
        g.fill(barX, barY, barX + (int) (barW * progress), barY + 6, 0xFF1DB954);
        // 进度文本
        String timeStr = formatTime(pos) + " / " + formatTime(total);
        g.drawCenteredString(this.font, Component.literal(timeStr),
                cx, barY + 12, 0xFFCCCCCC);

        // 状态
        String status = p.isPaused() ? "⏸ 已暂停" : (p.isPlaying() ? "▶ 正在播放" : "■ 已停止");
        g.drawCenteredString(this.font, Component.literal(status),
                cx, cardY + 86, 0xFF1DB954);

        // 歌词面板
        List<LyricLine> lyrics = p.getCurrentLyrics();
        if (lyrics != null && !lyrics.isEmpty())
        {
            int curIdx = findCurrentIndex(lyrics, pos);
            if (curIdx >= 0)
            {
                int lyricStartY = cardY + cardH + 20;
                int maxLines = 8;
                int half = maxLines / 2;
                int startIdx = Math.max(0, curIdx - half);
                int endIdx = Math.min(lyrics.size() - 1, startIdx + maxLines - 1);
                startIdx = Math.max(0, endIdx - maxLines + 1);

                int lineY = lyricStartY;
                int maxW = 0;
                for (int i = startIdx; i <= endIdx; i++)
                {
                    int w = this.font.width(lyrics.get(i).text);
                    if (w > maxW) maxW = w;
                }
                int lyricCardX = cx - Math.min(maxW + 40, this.width - 40) / 2;
                int lyricCardW = Math.min(maxW + 40, this.width - 40);
                g.fill(lyricCardX, lyricStartY - 6, lyricCardX + lyricCardW, lyricStartY + maxLines * 12 + 6, 0x80000000);

                for (int i = startIdx; i <= endIdx; i++)
                {
                    LyricLine line = lyrics.get(i);
                    String text = line.text;
                    if (text.length() > 50) text = text.substring(0, 49) + "…";
                    int color = (i == curIdx) ? 0xFF1DB954 : 0xFF888888;
                    // 居中绘制
                    int tw = this.font.width(text);
                    g.drawString(this.font, Component.literal(text),
                            cx - tw / 2, lineY, color, false);
                    lineY += 12;
                }
            }
            else
            {
                g.drawCenteredString(this.font, Component.literal("（暂无对应时间的歌词）"),
                        cx, cardY + cardH + 30, 0xFFAAAAAA);
            }
        }
        else if (p.isPlaying() && song != null)
        {
            g.drawCenteredString(this.font, Component.literal("（无歌词或正在加载）"),
                    cx, cardY + cardH + 30, 0xFFAAAAAA);
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

    @Override
    public boolean isPauseScreen() { return false; }
}
