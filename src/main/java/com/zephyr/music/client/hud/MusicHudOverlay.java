package com.zephyr.music.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.LyricWord;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.glass.BlurManager;
import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 游戏内 HUD v2 —— 毛玻璃悬浮面板
 *
 * 悬浮面板模式（默认开启）：
 * - 玻璃卡片：SDF 圆角 + RenderTarget 一次性背景模糊（面板静态，不逐帧模糊）
 * - 封面 / 歌曲信息 / 可点击进度条 / 迷你控制键（上一首/播放暂停/下一首/随机/循环）
 * - 当前歌词行（卡拉OK可选）
 * - HUD 上的按钮真实可点击：通过 InputEvent.MouseButton/Motion.Pre 拦截实现
 * - F8 进入编辑模式：拖拽移动面板，再按 F8 保存位置
 */
public class MusicHudOverlay implements IGuiOverlay
{
    private static final int PAD = 8;
    private static final int COVER = 32;
    private static final int RADIUS = 12;

    // ---- 编辑模式状态（静态：ClientEventHandler 直接调用） ----
    private static boolean editMode = false;
    private static boolean dragging = false;
    private static int dragGrabDX, dragGrabDY;
    private static int[] panelRectCache = new int[]{0, 0, 0, 0};

    /** 编辑模式开关（F8 触发） */
    public static void toggleEditMode()
    {
        editMode = !editMode;
        if (!editMode)
        {
            dragging = false;
            savePanelPosition();
        }
    }

    public static boolean isEditMode() { return editMode; }

    private static void savePanelPosition()
    {
        int[] r = panelRectCache;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        // 计算相对锚点基准的偏移（与 computePos 的 margin 语义一致）
        int[] base = anchorBase(sw, sh, r[2], r[3]);
        int offX = clamp(r[0] - base[0], -r[2], sw);
        int offY = clamp(r[1] - base[1], -r[3], sh);
        ZephyrConfig.HUD_X.set(offX);
        ZephyrConfig.HUD_Y.set(offY);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ==================================================================
    //  渲染
    // ==================================================================

    @Override
    public void render(ForgeGui forgeGui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!ZephyrConfig.HUD_ENABLED.get()) return;

        // ★ 一次性模糊：悬浮面板打开时抓取一次（面板静态，无需每帧模糊）
        boolean floatMode = ZephyrConfig.HUD_FLOAT_MODE.get();
        if ((floatMode || editMode) && ZephyrConfig.glassBlurEnabled() && !BlurManager.hasBlur())
        {
            BlurManager.requestBlur();
        }
        BlurManager.runPending();

        MusicPlayer mp = MusicPlayer.getInstance();
        NeteaseSong song = mp.getCurrentSong();
        if (song == null) return;

        int mouseX = (int) mc.mouseHandler.xpos();
        int mouseY = (int) mc.mouseHandler.ypos();

        // 悬停高亮（渲染时直接读鼠标）
        hoverId = -1;
        if (floatMode && ZephyrConfig.HUD_FLOAT_CONTROLS.get())
        {
            for (int i = 0; i < buttonRects.length; i++)
            {
                if (buttonRects[i][2] > 0 && inRect(mouseX, mouseY, buttonRects[i][0], buttonRects[i][1], buttonRects[i][2], buttonRects[i][3]))
                {
                    hoverId = i;
                    break;
                }
            }
        }

        // 编辑模式拖拽（每帧跟随鼠标）
        if (editMode && dragging)
        {
            int[] rc = panelRectCache;
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            int nx = mouseX - dragGrabDX;
            int ny = mouseY - dragGrabDY;
            ZephyrConfig.HUD_X.set(clamp(nx - anchorBase(sw, sh, rc[2], rc[3])[0], -rc[2] + 10, sw - 10));
            ZephyrConfig.HUD_Y.set(clamp(ny - anchorBase(sw, sh, rc[2], rc[3])[1], -rc[3] + 10, sh - 10));
        }

        int[] r = floatMode ? floatPanelRect(mc, song, screenWidth, screenHeight)
                            : classicPanelRect(mc, song, screenWidth, screenHeight);
        panelRectCache = r;
        int x = r[0], y = r[1], panelW = r[2], panelH = r[3];

        // === 玻璃面板 ===
        ModernUI.glassHudPanel(g, x, y, panelW, panelH);

        if (floatMode)
        {
            renderFloatPanel(g, mc, mp, song, x, y, panelW, panelH);
        }
        else
        {
            renderClassicPanel(g, mc, mp, song, x, y, panelW, panelH);
        }

        // 封面 URL 变化 → 触发异步加载
        String coverUrl = song.picUrl;
        if (coverUrl != null && !coverUrl.equals(lastCoverUrl))
        {
            lastCoverUrl = coverUrl;
            if (!coverUrl.isEmpty()) CoverTextureManager.getInstance().getCover(coverUrl, null);
        }

        // 编辑模式提示
        if (editMode)
        {
            String hint = "HUD 编辑模式：拖动面板 / F8 保存";
            g.fill(0, screenHeight - 14, screenWidth, screenHeight, 0x90000000);
            g.drawCenteredString(mc.font, Component.literal(hint), screenWidth / 2, screenHeight - 11, 0xFF4FC3F7);
        }
    }

    // ---------------- 悬浮面板 ----------------

    private void renderFloatPanel(GuiGraphics g, Minecraft mc, MusicPlayer mp, NeteaseSong song,
                                  int x, int y, int panelW, int panelH)
    {
        boolean showCover = ZephyrConfig.HUD_SHOW_COVER.get();
        int contentX = x + PAD;

        // --- 封面 ---
        if (showCover)
        {
            renderCover(g, mc, song.picUrl, contentX, y + PAD, COVER);
        }
        int textX = contentX + (showCover ? COVER + 8 : 0);
        int textW = x + panelW - PAD - textX;

        // --- 歌曲名 ---
        String stateStr = mp.isPaused() ? "⏸ " : "▶ ";
        String title = truncate(mc, song.name, textW - mc.font.width(stateStr) - 2);
        g.drawString(mc.font, Component.literal(stateStr + title), textX, y + PAD + 1,
                ModernUI.TEXT_PRIMARY, false);
        // --- 歌手 ---
        g.drawString(mc.font, Component.literal(truncate(mc, song.getDisplayArtist(), textW)),
                textX, y + PAD + 10, ModernUI.TEXT_SECONDARY, false);

        // --- 进度条（可点击 seek） ---
        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        // ★ v0.1.0：进度区改从面板顶部定位（控件隐藏时也不会与封面重叠）
        int barY = y + PAD + COVER + 4;
        int barX = x + PAD + 2;
        int barW = panelW - PAD * 2 - 4;
        seekRect[0] = barX; seekRect[1] = barY - 2; seekRect[2] = barW; seekRect[3] = 8;
        ModernUI.drawProgressBar(g, barX, barY, barW, 4, total > 0 ? pos / total : 0);
        String t1 = formatTime(pos), t2 = formatTime(total);
        g.drawString(mc.font, Component.literal(t1), barX, barY + 6, ModernUI.TEXT_ACCENT, false);
        g.drawString(mc.font, Component.literal(t2), barX + barW - mc.font.width(t2), barY + 6, ModernUI.TEXT_DIM, false);

        // --- 迷你控制键 ---
        if (ZephyrConfig.HUD_FLOAT_CONTROLS.get())
        {
            int by = barY + 17;
            int bx = x + PAD + 2;
            drawHudButton(g, 0, bx, by, "⏮", false);          bx += 19;
            drawHudButton(g, 1, bx, by, mp.isPaused() ? "▶" : "⏸", false); bx += 19;
            drawHudButton(g, 2, bx, by, "⏭", false);          bx += 19;
            drawHudButton(g, 3, bx, by, "🔀", mp.isShuffleMode()); bx += 19;
            drawHudButton(g, 4, bx, by, "🔁", mp.isLoopMode());
        }

        // --- 当前歌词 ---
        if (ZephyrConfig.HUD_SHOW_LYRICS.get())
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                if (curIdx >= 0)
                {
                    LyricLine line = lyrics.get(curIdx);
                    int ly = y + PAD + 20;
                    if (line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                    {
                        renderKaraokeLine(g, mc, line, textX, ly, textW, pos);
                    }
                    else
                    {
                        g.drawString(mc.font, Component.literal(truncate(mc, line.text, textW)),
                                textX, ly, ModernUI.TEXT_ACCENT, false);
                    }
                }
            }
        }
    }

    // ---------------- 经典模式（保留原布局，玻璃化） ----------------

    private void renderClassicPanel(GuiGraphics g, Minecraft mc, MusicPlayer mp, NeteaseSong song,
                                    int x, int y, int panelW, int panelH)
    {
        int coverSize = ZephyrConfig.HUD_SHOW_COVER.get() ? ZephyrConfig.HUD_COVER_SIZE.get() : 0;
        int lineH = mc.font.lineHeight + 2;
        int infoX = x + PAD;
        if (coverSize > 0)
        {
            renderCover(g, mc, song.picUrl, x + PAD, y + (panelH - coverSize) / 2, coverSize);
            infoX = x + PAD + coverSize + 8;
        }
        int infoW = x + panelW - PAD - infoX;

        String stateStr = mp.isPaused() ? "⏸ " : "▶ ";
        g.drawString(mc.font, Component.literal(stateStr + truncate(mc, song.name, infoW - 16)),
                infoX, y + PAD, ModernUI.TEXT_PRIMARY, false);
        g.drawString(mc.font, Component.literal(truncate(mc, "by: " + song.getDisplayArtist(), infoW)),
                infoX, y + PAD + lineH, ModernUI.TEXT_SECONDARY, false);

        double pos = mp.getPositionSec();
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        int ty = y + PAD + lineH * 2 + 2;
        if (ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() && total > 0)
        {
            String t1 = formatTime(pos), t2 = formatTime(total);
            int barX = infoX + mc.font.width(t1) + 5;
            int barW = infoW - mc.font.width(t1) - mc.font.width(t2) - 10;
            ModernUI.drawProgressBar(g, barX, ty + 2, barW, 3, pos / total);
            g.drawString(mc.font, Component.literal(t1), infoX, ty, ModernUI.TEXT_ACCENT, false);
            g.drawString(mc.font, Component.literal(t2), infoX + infoW - mc.font.width(t2), ty, ModernUI.TEXT_DIM, false);
            ty += 10;
        }

        if (ZephyrConfig.HUD_SHOW_LYRICS.get())
        {
            List<LyricLine> lyrics = mp.getCurrentLyrics();
            if (lyrics != null && !lyrics.isEmpty())
            {
                int curIdx = findCurrentIndex(lyrics, pos);
                int lines = Math.min(ZephyrConfig.HUD_LYRICS_LINES.get(), Math.max(1, (y + panelH - ty - 4) / lineH));
                int center = lines / 2;
                for (int slot = 0; slot < lines; slot++)
                {
                    int idx = curIdx - center + slot;
                    if (idx < 0 || idx >= lyrics.size()) { ty += lineH; continue; }
                    LyricLine line = lyrics.get(idx);
                    boolean act = idx == curIdx;
                    int color = act ? ModernUI.TEXT_ACCENT : ModernUI.TEXT_SECONDARY;
                    if (!act && lines > 2)
                    {
                        int alpha = (int) (180 * (1 - Math.abs(slot - center) / (double) (center + 1)));
                        color = ModernUI.withAlpha(color, Math.max(80, alpha));
                    }
                    if (act && line.isYrc && ZephyrConfig.LYRIC_KARAOKE.get())
                    {
                        renderKaraokeLine(g, mc, line, infoX, ty, infoW, pos);
                    }
                    else
                    {
                        g.drawString(mc.font, Component.literal(truncate(mc, line.text, infoW)), infoX, ty, color, false);
                    }
                    ty += lineH;
                }
            }
        }
    }

    // ---------------- HUD 迷你按钮 ----------------

    /** 鼠标悬停的按钮 id（motion 事件设置） */
    private static int hoverId = -1;
    /** 按钮命中区域缓存（render 时填充）：5 × {x,y,w,h} */
    private static final int[][] buttonRects = new int[5][4];
    /** 进度条命中区域缓存 */
    private static final int[] seekRect = new int[4];

    private void drawHudButton(GuiGraphics g, int id, int x, int y, String label, boolean active)
    {
        boolean hovered = hoverId == id;
        buttonRects[id][0] = x; buttonRects[id][1] = y; buttonRects[id][2] = 16; buttonRects[id][3] = 12;
        ModernUI.fillRound(g, x, y, 16, 12, 4, active ? 0xFF2E4A66 : (hovered ? 0xFF33405A : 0x30FFFFFF));
        g.drawCenteredString(Minecraft.getInstance().font, Component.literal(label),
                x + 8, y + 2, active ? ModernUI.TEXT_ACCENT : ModernUI.TEXT_SECONDARY);
    }

    // ---------------- 布局计算 ----------------

    private int[] floatPanelRect(Minecraft mc, NeteaseSong song, int sw, int sh)
    {
        // ★ v0.1.0 HUD 缩小：宽度默认 160（原 220）、封面 32（原 44）、内边距 8（原 10）
        int w = Math.max(ZephyrConfig.HUD_WIDTH.get(), 140);
        int controlsH = ZephyrConfig.HUD_FLOAT_CONTROLS.get() ? 17 : 0;
        // 内边距×2 + 封面行 + 间隔 4 + 进度条 4 + 2 + 时间文字 9 + 控制键区
        int h = PAD * 2 + COVER + 4 + 6 + 9 + controlsH;
        int[] base = anchorBase(sw, sh, w, h);
        int x = clamp(base[0] + ZephyrConfig.HUD_X.get(), 2, Math.max(2, sw - w - 2));
        int y = clamp(base[1] + ZephyrConfig.HUD_Y.get(), 2, Math.max(2, sh - h - 2));
        return new int[]{x, y, w, h};
    }

    private int[] classicPanelRect(Minecraft mc, NeteaseSong song, int sw, int sh)
    {
        int coverSize = ZephyrConfig.HUD_SHOW_COVER.get() ? ZephyrConfig.HUD_COVER_SIZE.get() : 0;
        int lineH = mc.font.lineHeight + 2;
        int lyricLines = ZephyrConfig.HUD_SHOW_LYRICS.get() ? ZephyrConfig.HUD_LYRICS_LINES.get() : 0;
        int barH = ZephyrConfig.HUD_SHOW_PROGRESS_BAR.get() ? 10 : 0;
        int h = PAD * 2 + lineH * 2 + 2 + barH + lyricLines * lineH;
        h = Math.max(h, PAD * 2 + coverSize);
        int w = Math.max(ZephyrConfig.HUD_WIDTH.get(), 140);
        int[] base = anchorBase(sw, sh, w, h);
        int x = clamp(base[0] + ZephyrConfig.HUD_X.get(), 2, Math.max(2, sw - w - 2));
        int y = clamp(base[1] + ZephyrConfig.HUD_Y.get(), 2, Math.max(2, sh - h - 2));
        return new int[]{x, y, w, h};
    }

    private static int[] anchorBase(int sw, int sh, int w, int h)
    {
        String anchor = ZephyrConfig.HUD_ANCHOR.get();
        int margin = 12;
        switch (anchor)
        {
            case "top_right":    return new int[]{sw - w - margin, margin};
            case "bottom_left":  return new int[]{margin, sh - h - margin};
            case "bottom_right": return new int[]{sw - w - margin, sh - h - margin};
            default:             return new int[]{margin, margin};
        }
    }

    // ==================================================================
    //  输入拦截：HUD 控件真实可点击（无界面打开时）
    // ==================================================================

    /** ClientEventHandler.register() 调用 */
    public static void registerInput()
    {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MusicHudOverlay::onMouseButton);
    }

    private static boolean hudVisible(Minecraft mc)
    {
        return mc.player != null && mc.screen == null && ZephyrConfig.HUD_ENABLED.get()
                && MusicPlayer.getInstance().getCurrentSong() != null;
    }

    private static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (!hudVisible(mc)) return;
        if (event.getAction() == GLFW.GLFW_PRESS)
        {
            int mx = (int) mc.mouseHandler.xpos();
            int my = (int) mc.mouseHandler.ypos();
            int[] r = panelRectCache;

            // 编辑模式：开始拖拽（或点击面板任意处开始）
            if (editMode)
            {
                if (inRect(mx, my, r[0], r[1], r[2], r[3]))
                {
                    dragging = true;
                    dragGrabDX = mx - r[0];
                    dragGrabDY = my - r[1];
                    event.setCanceled(true);
                }
                return;
            }

            MusicPlayer mp = MusicPlayer.getInstance();
            // 迷你控制按钮
            if (ZephyrConfig.HUD_FLOAT_MODE.get() && ZephyrConfig.HUD_FLOAT_CONTROLS.get())
            {
                for (int i = 0; i < buttonRects.length; i++)
                {
                    if (buttonRects[i][2] <= 0) continue;
                    if (inRect(mx, my, buttonRects[i][0], buttonRects[i][1], buttonRects[i][2], buttonRects[i][3]))
                    {
                        switch (i)
                        {
                            case 0 -> mp.prev();
                            case 1 -> { if (mp.isPlaying()) { if (mp.isPaused()) mp.resume(); else mp.pause(); } }
                            case 2 -> mp.next();
                            case 3 -> mp.setShuffleMode(!mp.isShuffleMode());
                            case 4 -> mp.setLoopMode(!mp.isLoopMode());
                        }
                        event.setCanceled(true);
                        return;
                    }
                }
                // 进度条 seek
                if (inRect(mx, my, seekRect[0], seekRect[1], seekRect[2], seekRect[3]))
                {
                    seekByMouse(mc, mp, mx, seekRect);
                    event.setCanceled(true);
                    return;
                }
            }
        }
        else if (event.getAction() == GLFW.GLFW_RELEASE)
        {
            dragging = false;
        }
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h)
    {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static void seekByMouse(Minecraft mc, MusicPlayer mp, int mouseX, int[] rect)
    {
        NeteaseSong song = mp.getCurrentSong();
        if (song == null || song.duration <= 0 || rect[2] <= 0) return;
        double ratio = clampF((mouseX - rect[0]) / (double) rect[2], 0, 1);
        mp.seekTo(ratio * song.duration / 1000.0);
    }

    private static double clampF(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    // ==================================================================
    //  封面 / 歌词 / 工具
    // ==================================================================

    private volatile String lastCoverUrl = "";

    private void renderCover(GuiGraphics g, Minecraft mc, String picUrl, int x, int y, int size)
    {
        if (picUrl == null || picUrl.isEmpty())
        {
            renderCoverPlaceholder(g, x, y, size, "?");
            return;
        }
        ResourceLocation texId = CoverTextureManager.getInstance().getCover(picUrl, null);
        if (texId != null)
        {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(texId, x, y, 0, 0, size, size, size, size);
            RenderSystem.disableBlend();
            // 圆角裁切遮罩（四个角上盖玻璃色小方块，视觉圆角化）
            int corner = 4;
            int mask = 0x9910141C;
            g.fill(x, y, x + corner, y + 1, mask); g.fill(x, y + 1, x + 1, y + corner, mask);
            g.fill(x + size - corner, y, x + size, y + 1, mask); g.fill(x + size - 1, y + 1, x + size, y + corner, mask);
            g.fill(x, y + size - 1, x + corner, y + size, mask); g.fill(x, y + size - corner, x + 1, y + size - 1, mask);
            g.fill(x + size - corner, y + size - 1, x + size, y + size, mask); g.fill(x + size - 1, y + size - corner, x + size, y + size - 1, mask);
        }
        else
        {
            renderCoverPlaceholder(g, x, y, size, "...");
        }
    }

    private void renderCoverPlaceholder(GuiGraphics g, int x, int y, int size, String text)
    {
        ModernUI.fillRound(g, x, y, size, size, 8, 0xFF1A222E);
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
            if (w.isFinished(pos)) color = playedColor;
            else if (w.isPlayingAt(pos)) color = currentColor;
            else color = unplayedColor;
            g.drawString(mc.font, Component.literal(wt), curX, y, color, false);
            curX += mc.font.width(wt);
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
        return String.format("%d:%02d", total / 60, total % 60);
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
