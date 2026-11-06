package com.zephyr.music.client.glass;

import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 毛玻璃 UI 渲染器 —— SDF (Signed Distance Field) 圆角矩形
 *
 * 相比"逐像素方块近似圆角"的传统 fill：
 * - 圆角由 SDF 解析计算，边缘走 MSAA 式 smoothstep 抗锯齿，任意缩放都圆润平滑
 * - 一条 draw call 画完 填充+描边+顶部高光，无锯齿、无破边
 * - 支持任意半径、任意尺寸，配合 BlurManager 的模糊背景即为完整毛玻璃面板
 */
public final class GlassRenderer
{
    /** 由 ClientEventHandler 在 RegisterShadersEvent 中注册 */
    private static ShaderInstance glassShader;
    private static ShaderInstance blurShader;
    private static ShaderInstance coverShader;
    private static boolean shaderFailed = false;

    private GlassRenderer() {}

    public static ShaderInstance getGlassShader() { return glassShader; }
    public static ShaderInstance getBlurShader() { return blurShader; }
    public static ShaderInstance getCoverShader() { return coverShader; }

    public static void setGlassShader(ShaderInstance s) { glassShader = s; }
    public static void setBlurShader(ShaderInstance s) { blurShader = s; }
    public static void setCoverShader(ShaderInstance s) { coverShader = s; }

    public static boolean shadersReady()
    {
        return !shaderFailed && glassShader != null;
    }

    // ==================================================================
    //  SDF 圆角矩形（实心 + 可选描边 + 顶部高光渐变）
    // ==================================================================

    /**
     * 绘制一个 SDF 抗锯齿圆角矩形。
     *
     * @param g        GuiGraphics
     * @param x,y      左上角（GUI 坐标）
     * @param w,h      尺寸
     * @param radius   圆角半径（像素），自动钳制
     * @param fill     填充色（ARGB）
     * @param border   描边色（ARGB），alpha=0 表示不描边
     * @param sheen    顶部高光强度 0..1（毛玻璃质感的关键：上缘亮、下缘暗）
     */
    public static void drawSdfRoundRect(GuiGraphics g, int x, int y, int w, int h,
                                        float radius, int fill, int border, float sheen)
    {
        if (w <= 0 || h <= 0) return;
        if (!shadersReady())
        {
            fallbackRoundRect(g, x, y, w, h, (int) radius, fill);
            return;
        }
        radius = Math.min(radius, Math.min(w, h) / 2.0f);

        Matrix4f mat = g.pose().last().pose();
        ShaderInstance shader = glassShader;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(() -> shader);

        shader.safeGetUniform("HalfSize").set(w / 2.0f, h / 2.0f);
        shader.safeGetUniform("Radius").set(radius);
        shader.safeGetUniform("BorderWidth").set((border & 0xFF000000) != 0 ? 1.0f : 0.0f);
        setUniformColor(shader, "BorderColor", border);
        float sh = Math.max(0f, Math.min(1f, sheen));
        shader.safeGetUniform("Sheen").set(sh);

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        // UV 传"面板局部像素坐标"，shader 里减去 HalfSize 得到以中心为原点的 SDF 坐标
        // 顶部顶点带 sheen 高光（vertexColor 提亮），底部顶点稍暗
        putVertex(bb, mat, x,     y,     0, 0, brighten(fill,  sh * 0.35f));
        putVertex(bb, mat, x,     y + h, 0, h, dim(fill, sh * 0.12f));
        putVertex(bb, mat, x + w, y + h, w, h, dim(fill, sh * 0.12f));
        putVertex(bb, mat, x + w, y,     w, 0, brighten(fill,  sh * 0.35f));
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** 无边框便捷重载 */
    public static void drawSdfRoundRect(GuiGraphics g, int x, int y, int w, int h,
                                        float radius, int fill)
    {
        drawSdfRoundRect(g, x, y, w, h, radius, fill, 0, 0.0f);
    }

    // ==================================================================
    //  SDF 圆角贴图（封面圆角，对齐 DangoMusic drawCover）
    // ==================================================================

    /**
     * 绘制一张带 SDF 圆角掩码的贴图（专辑/歌单封面）。
     * shader 不可用时降级为方形贴图。
     */
    public static void drawSdfCover(GuiGraphics g, int x, int y, int w, int h,
                                    float radius, ResourceLocation tex)
    {
        if (w <= 0 || h <= 0) return;
        if (!coverReady() || tex == null)
        {
            if (tex != null)
            {
                RenderSystem.setShaderTexture(0, tex);
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                g.blit(tex, x, y, 0, 0, w, h, w, h);
                RenderSystem.disableBlend();
            }
            return;
        }
        radius = Math.min(radius, Math.min(w, h) / 2.0f);

        Matrix4f mat = g.pose().last().pose();
        ShaderInstance shader = coverShader;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShader(() -> shader);

        shader.safeGetUniform("HalfSize").set(w / 2.0f, h / 2.0f);
        shader.safeGetUniform("Radius").set(radius);
        shader.safeGetUniform("ColorModulator").set(1.0f, 1.0f, 1.0f, 1.0f);

        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        // UV = 面板局部像素坐标；片元端 panelPos/size 得到贴图 UV（行 0 = 图片顶部）
        putVertex(bb, mat, x,     y,     0, 0, 0xFFFFFFFF);
        putVertex(bb, mat, x,     y + h, 0, h, 0xFFFFFFFF);
        putVertex(bb, mat, x + w, y + h, w, h, 0xFFFFFFFF);
        putVertex(bb, mat, x + w, y,     w, 0, 0xFFFFFFFF);
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    private static boolean coverReady()
    {
        return !shaderFailed && coverShader != null;
    }

    private static void putVertex(BufferBuilder bb, Matrix4f mat, int x, int y, int u, int v, int argb)
    {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float gc = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        bb.vertex(mat, x, y, 0).color(r, gc, b, a).uv(u, v).endVertex();
    }

    private static void setUniformColor(ShaderInstance shader, String name, int argb)
    {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float gc = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        shader.safeGetUniform(name).set(r, gc, b, a);
    }

    private static int brighten(int argb, float t)
    {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((argb >>> 16) & 0xFF) + 255 * t));
        int g = Math.min(255, (int) (((argb >>> 8) & 0xFF) + 255 * t));
        int b = Math.min(255, (int) ((argb & 0xFF) + 255 * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int dim(int argb, float t)
    {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, (int) (((argb >>> 16) & 0xFF) - 255 * t));
        int g = Math.max(0, (int) (((argb >>> 8) & 0xFF) - 255 * t));
        int b = Math.max(0, (int) ((argb & 0xFF) - 255 * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ==================================================================
    //  完整毛玻璃面板：模糊背景 + SDF 圆角玻璃 + 描边
    // ==================================================================

    /**
     * 绘制毛玻璃面板（需要在每帧渲染开头已调用 BlurManager.runPending()）。
     *
     * @param blurBehind 是否在其后方绘制模糊背景图（HUD 悬浮面板需要，屏幕内嵌面板可复用整屏模糊）
     */
    public static void glassPanel(GuiGraphics g, int x, int y, int w, int h,
                                  float radius, int tint, float sheen, boolean blurBehind)
    {
        if (blurBehind && BlurManager.hasBlur())
        {
            // 稍微外扩 2px 模糊区，避免模糊图边缘从圆角内侧露出
            BlurManager.drawBlurredRegion(x - 2, y - 2, w + 4, h + 4);
        }
        drawSdfRoundRect(g, x, y, w, h, radius, tint, 0x66FFFFFF, sheen);
        // 内侧 1px 亮边（玻璃厚度感）
        drawSdfRoundRect(g, x + 1, y + 1, w - 2, h - 2, Math.max(0, radius - 1), 0, 0x18FFFFFF, 0);
    }

    // ==================================================================
    //  降级路径：shader 不可用时的方块近似圆角
    // ==================================================================

    private static void fallbackRoundRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color)
    {
        int r = Math.min(radius, Math.min(w, h) / 2);
        if (r <= 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + r, y + h - r, color);
        g.fill(x + w - r, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++)
        {
            int dy = r - i;
            int dx = (int) Math.round(Math.sqrt(Math.max(0, r * (double) r - dy * (double) dy)));
            if (dx <= 0) continue;
            g.fill(x + r - dx, y + i, x + r, y + i + 1, color);
            g.fill(x + w - r, y + i, x + w - r + dx, y + i + 1, color);
            g.fill(x + r - dx, y + h - 1 - i, x + r, y + h - i, color);
            g.fill(x + w - r, y + h - 1 - i, x + w - r + dx, y + h - i, color);
        }
    }
}
