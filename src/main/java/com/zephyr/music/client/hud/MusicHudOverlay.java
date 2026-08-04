package com.zephyr.music.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.LyricWord;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * 游戏内 HUD - 封面图风格
 *
 * - 左侧显示歌曲封面（异步下载）
 * - 右侧显示歌曲信息 + 进度条 + 歌词
 * - 默认无边框无背景
 * - UI 大小可配置
 */
public class MusicHudOverlay implements IGuiOverlay
{
    // AllMusic 风格配色
    private static final int COLOR_ACCENT = 0xFF00FFFF;   // 青色强调
    private static final int COLOR_TEXT = 0xFFFFFFFF;     // 白色文字
    private static final int COLOR_DIM = 0xFFAAAAAA;     // 灰色辅助
    private static final int COLOR_LYRIC_NEXT = 0xFFCCCCCC;

    /** 用于触发 HUD 重新渲染（封面加载完成后） */
    private volatile String lastCoverUrl = "";

    @Override
    public void render(ForgeGui forgeGui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ZephyrConfig.HUD_ENABLED.get()) return;

        MusicPlayer mp = MusicPlayer.getInstance();
        NeteaseSong song = mp.getCurrentSong();
        if (song == null) return;

        // 配置
        int coverSize = ZephyrConfig.HUD_COVER_SIZE.get();
        int panelW = ZephyrConfig.HUD_PANEL_WIDTH.get();
        boolean compact = ZephyrConfig.HUD_COMPACT.get();
        int padX = compact ? 6 : 10;
        int padY = compact ? 4 : 8;
        int lineH = mc.font.lineHeight + 2;
        double bgOpacity = ZephyrConfig.HUD_BG_OPACITY.get();
        boolean showBorder = ZephyrConfig.HUD_SHOW_BORDER.get();
        boolean showCover = ZephyrConfig.HUD_SHOW_COVER.get();

        // 计算实际使用的封面尺寸（不显示时为 0）
        int actualCoverSize = showCover ? coverSize : 0;
        int coverGap = showCover ? 8 : 0;

        // 歌词行数
        int lyricLines = ZephyrConfig.HUD_SHOW_LYRICS.get() ? ZephyrConfig.HUD_LYRICS_LINES.get() : 0;

        // 计算组件高度
        int infoH = lineH * 2 + 4;           // 歌曲名 + 作者
        int barH = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 6 : 0;
        int barPad = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 4 : 0;
        int lyricH = lyricLines > 0 ? lyricLines * lineH + 4 : 0;

        int contentH = infoH + barH + barPad + lyricH;
        int panelH = padY * 2 + Math.max(contentH, actualCoverSize);

        // 实际宽度：封面 + 间距 + 信息区
        int infoW = panelW - actualCoverSize - coverGap - padX * 2;
        if (!showCover) infoW = panelW - padX * 2;

        // 锚点定位
        int[] xy = computePos(screenWidth, screenHeight, panelW, panelH);
        int x = xy[0] + ZephyrConfig.HUD_X.get() - 12;
        int y = xy[1] + ZephyrConfig.HUD_Y.get() - 12;
        x = Math.max(2, Math.min(screenWidth - panelW - 2, x));
        y = Math.max(2, Math.min(screenHeight - panelH - 2, y));

        // === 1. 背景（默认透明） ===
        if (bgOpacity > 0.001)
        {
            int bg = ZephyrConfig.getBgColor(bgOpacity);
            ModernUI.fillRound(g, x, y, panelW, panelH, 6, bg);
        }
        if (showBorder)
        {
            ModernUI.strokeRound(g, x, y, panelW, panelH, 6, 0x80FFA000, 1);
        }

        // === 2. 左侧封面图 ===
        int coverX = x + padX;
        int coverY = y + (panelH - actualCoverSize) / 2;

        if (showCover)
        {
            renderCover(g, mc, song.picUrl, coverX, coverY, actualCoverSize);
        }

        // === 3. 右侧信息区 ===
        int infoX = showCover ? coverX + actualCoverSize + coverGap : x + padX;
        int ty = y + padY;

        // 歌曲名（带状态前缀）
        String stateStr = mp.isPaused() ? "⏸ " : (mp.isPlaying() ? "▶ " : "■ ");
        String songName = truncate(mc, song.name, infoW - mc.font.width(stateStr) - 4);
        g.drawString(mc.font, Component.literal(stateStr + songName), infoX, ty, COLOR_TEXT, false);
        ty += lineH;

        // 作者（灰色）
        String artist = truncate(mc, "by: " + song.getDisplayArtist(), infoW);
        g.drawString(mc.font, Component.literal(artist), infoX, ty, COLOR_DIM, false);
        ty += lineH + 2;

        // === 4. 进度条（左时间 + 长条 + 青色滑块 + 右时间） ===
        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;

        if (ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() && total > 0)
        {
            String curTime = formatTime(pos);
            String totTime = formatTime(total);
            int curW = mc.font.width(curTime);
            int totW = mc.font.width(totTime);
            int barX = infoX + curW + 6;
            int barAvailW = infoW - curW - totW - 12;
            int barY = ty + 4;

            // 当前时间（青色）
            g.drawString(mc.font, Component.literal(curTime), infoX, ty, COLOR_ACCENT, false);
            // 进度条背景
            g.fill(barX, barY, barX + barAvailW, barY + 2, 0xFF444444);
            // 进度条填充（白色）
            int fillW = (int) (barAvailW * Math.min(1, pos / total));
            if (fillW > 0)
            {
                g.fill(barX, barY, barX + fillW, barY + 2, COLOR_TEXT);
            }
            // 青色滑块
            int sliderX = barX + fillW - 1;
            g.fill(sliderX, barY - 2, sliderX + 4, barY + 4, COLOR_ACCENT);
            // 总时间
            g.drawString(mc.font, Component.literal(totTime), infoX + infoW - totW, ty, COLOR_DIM, false);

            ty += barH + barPad;
        }
        else
        {
            ty += barPad;
        }

        // === 5. 歌词区 ===
        if (lyricLines > 0)
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    int centerSlot = lyricLines / 2;
                    int lineY = ty + 2;
                    for (int slot = 0; slot < lyricLines; slot++)
                    {
                        int idx = curIdx - centerSlot + slot;
                        if (idx < 0 || idx >= lyrics.size())
                        {
                            lineY += lineH;
                            continue;
                        }
                        LyricLine line = lyrics.get(idx);
                        boolean isActive = (idx == curIdx);
                        int color = isActive ? COLOR_ACCENT : COLOR_LYRIC_NEXT;
                        if (!isActive && lyricLines > 2)
                        {
                            int alpha = (int) (180 * (1 - Math.abs(slot - centerSlot) / (double) (centerSlot + 1)));
                            color = ModernUI.withAlpha(color, Math.max(80, alpha));
                        }

                        if (isActive && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                        {
                            renderKaraokeLine(g, mc, line, infoX, lineY, infoW, pos);
                        }
                        else
                        {
                            String text = truncate(mc, line.text, infoW);
                            g.drawString(mc.font, Component.literal(text), infoX, lineY, color, false);
                        }
                        lineY += lineH;
                    }
                }
            }
        }

        // 检测封面 URL 变化，触发重新加载
        if (showCover && !song.picUrl.equals(lastCoverUrl))
        {
            lastCoverUrl = song.picUrl;
            if (!song.picUrl.isEmpty())
            {
                CoverTextureManager.getInstance().getCover(song.picUrl, null);
            }
        }
    }

    /**
     * 渲染封面图（如果未加载完成则显示占位）
     */
    private void renderCover(GuiGraphics g, Minecraft mc, String picUrl, int x, int y, int size)
    {
        if (picUrl == null || picUrl.isEmpty())
        {
            // 无封面 URL，画占位
            renderCoverPlaceholder(g, x, y, size, "?");
            return;
        }

        // 尝试获取已加载的纹理
        ResourceLocation texId = CoverTextureManager.getInstance().getCover(picUrl, () -> {
            // 加载完成回调：Minecraft 会自动重新渲染下一帧
        });

        if (texId != null)
        {
            // 已加载，绘制封面
            RenderSystem.setShaderTexture(0, texId);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(texId, x, y, 0, 0, size, size, size, size);
            RenderSystem.disableBlend();
        }
        else
        {
            // 加载中，显示占位（旋转指示）
            renderCoverPlaceholder(g, x, y, size, "...");
        }
    }

    /**
     * 渲染封面占位（深色方块 + 文字）
     */
    private void renderCoverPlaceholder(GuiGraphics g, int x, int y, int size, String text)
    {
        // 深色背景
        g.fill(x, y, x + size, y + size, 0xFF1A1A1A);
        // 边框
        g.fill(x, y, x + size, y + 1, 0xFF444444);
        g.fill(x, y + size - 1, x + size, y + size, 0xFF444444);
        g.fill(x, y, x + 1, y + size, 0xFF444444);
        g.fill(x + size - 1, y, x + size, y + size, 0xFF444444);
        // 中心文字
        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(text);
        g.drawString(mc.font, Component.literal(text), x + (size - tw) / 2, y + size / 2 - 4, 0xFF888888, false);
    }

    private void renderKaraokeLine(GuiGraphics g, Minecraft mc, LyricLine line, int x, int y, int maxW, double pos)
    {
        int playedColor = ZephyrConfig.LYRIC_WORD_PLAYED_COLOR.get();
        int currentColor = ZephyrConfig.LYRIC_WORD_CURRENT_COLOR.get();
        int unplayedColor = ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR.get();

        int curX = x;
        for (LyricWord w : line.words)
        {
            if (w.text.isEmpty()) continue;
            String wt = w.text;
            if (mc.font.width(wt) > maxW - (curX - x))
            {
                wt = truncateByWidth(mc, wt, maxW - (curX - x));
                if (wt.isEmpty()) break;
            }
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
            g.drawString(mc.font, Component.literal(wt), curX, y, color, false);
            curX += mc.font.width(wt);
        }
    }

    private int[] computePos(int sw, int sh, int w, int h)
    {
        String anchor = ZephyrConfig.HUD_ANCHOR.get();
        int margin = 12;
        switch (anchor)
        {
            case "top_right":
                return new int[]{sw - w - margin, margin};
            case "bottom_left":
                return new int[]{margin, sh - h - margin};
            case "bottom_right":
                return new int[]{sw - w - margin, sh - h - margin};
            case "top_left":
            default:
                return new int[]{margin, margin};
        }
    }

    private static int findCurrentIndex(List<LyricLine> lyrics, double posSec)
    {
        int idx = -1;
        for (int i = 0; i < lyrics.size(); i++)
        {
            if (lyrics.get(i).time <= posSec) idx = i;
            else break;
        }
        return idx;
    }

    private static String formatTime(double sec)
    {
        if (sec < 0) sec = 0;
        int total = (int) sec;
        int m = total / 60;
        int s = total % 60;
        return String.format("%d:%02d", m, s);
    }

    private static String truncate(Minecraft mc, String s, int maxW)
    {
        if (s == null) return "";
        if (mc.font.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
        {
            sb.append(s.charAt(i));
            if (mc.font.width(sb.toString() + "…") > maxW)
            {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.append("…").toString();
    }

    private static String truncateByWidth(Minecraft mc, String s, int maxW)
    {
        if (s == null) return "";
        if (mc.font.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++)
        {
            sb.append(s.charAt(i));
            if (mc.font.width(sb.toString()) > maxW)
            {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb.toString();
    }
}
