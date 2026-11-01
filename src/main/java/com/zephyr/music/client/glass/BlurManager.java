package com.zephyr.music.client.glass;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * 一次性背景模糊管理器（毛玻璃效果的核心）
 *
 * 设计原则（性能优先）：
 * - 不使用 PostChain，也不每帧模糊。
 * - 仅在"面板打开"的那一刻，把主 RenderTarget 的颜色附件抓取下来，
 *   降采样 + 多轮可分离高斯模糊（ping-pong），结果存成一张模糊纹理。
 * - 面板是静态的，之后每帧只是把这张已经模糊好的纹理按面板区域取样绘制，
 *   开销接近一张普通贴图 blit。
 *
 * 使用流程：
 *   1. 界面 init() / HUD 悬浮面板开启时调用 {@link #requestBlur()}
 *   2. 每帧渲染开头调用 {@link #runPending()}（真正执行抓取 + 模糊）
 *   3. 绘制面板前调用 {@link #drawBlurredRegion(...)}，把模糊图对齐取样到面板矩形
 */
public final class BlurManager
{
    /** 标记一次模糊请求 */
    private static boolean pending = false;
    /** 模糊结果（A 纹理），null 表示不可用 */
    private static TextureTarget blurTargetA;
    private static TextureTarget blurTargetB;

    /** 抓取时主帧缓冲的尺寸（用于 UV 换算） */
    private static int srcWidth, srcHeight;

    /** 模糊版本号，窗口尺寸变化时失效 */
    private static int resizeStamp = -1;

    private BlurManager() {}

    /** 请求一次模糊（下一帧 runPending 时执行） */
    public static void requestBlur()
    {
        if (ZephyrConfig.glassBlurEnabled()) pending = true;
    }

    /** 立即丢弃当前模糊结果 */
    public static void invalidate()
    {
        pending = false;
        release();
    }

    /** 释放显存 */
    public static void release()
    {
        if (blurTargetA != null) { blurTargetA.destroyBuffers(); blurTargetA = null; }
        if (blurTargetB != null) { blurTargetB.destroyBuffers(); blurTargetB = null; }
    }

    /**
     * 每帧渲染开头调用：若有挂起的模糊请求则执行一次抓取 + 模糊。
     * 必须在 RenderThread 上调用。
     */
    public static void runPending()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow() == null) return;

        // 窗口尺寸变化 → 模糊失效，需要重抓
        RenderTarget main = mc.getMainRenderTarget();
        int stamp = main.width * 100000 + main.height;
        if (stamp != resizeStamp)
        {
            resizeStamp = stamp;
            release();
        }

        if (!pending) return;
        pending = false;
        if (!ZephyrConfig.glassBlurEnabled()) return;

        try
        {
            doBlur(main);
        }
        catch (Exception e)
        {
            com.zephyr.music.ZephyrMusic.LOGGER.warn("[Zephyr] blur capture failed: {}", e.toString());
            release();
        }
    }

    /** 是否已有可用的模糊结果 */
    public static boolean hasBlur()
    {
        return blurTargetA != null;
    }

    /** 抓取主帧缓冲并执行降采样 + 可分离高斯模糊 */
    private static void doBlur(RenderTarget main)
    {
        int strength = Math.max(1, Math.min(4, ZephyrConfig.UI_BLUR_STRENGTH.get()));
        // 降采样比例：强度越高降得越狠（1/3 ~ 1/6），既是模糊也省性能
        int ds = 2 + strength;              // 3,4,5,6
        int w = Math.max(4, main.width / ds);
        int h = Math.max(4, main.height / ds);

        ensureTargets(w, h);
        if (blurTargetA == null) return;

        srcWidth = main.width;
        srcHeight = main.height;

        // ★ 保存 GUI 投影矩阵，RT pass 使用正交投影，结束后恢复
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        try
        {
            RenderSystem.setProjectionMatrix(new Matrix4f().ortho2D(0f, 1f, 1f, 0f), VertexSorting.ORTHOGRAPHIC_Z);

            // ---- 1. 主帧缓冲 -> rtA（降采样拷贝） ----
            RenderSystem.disableBlend();
            blitFullscreen(main.getColorTextureId(), blurTargetA, w, h);

            // ---- 2. 可分离高斯模糊 ping-pong × strength 轮 ----
            for (int i = 0; i < strength; i++)
            {
                blurPass(blurTargetA.getColorTextureId(), blurTargetB, w, h, 1.0f, 0.0f);
                blurPass(blurTargetB.getColorTextureId(), blurTargetA, w, h, 0.0f, 1.0f);
            }
        }
        finally
        {
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }

        // ---- 3. 恢复主帧缓冲绑定与视口 ----
        main.bindWrite(true);
        RenderSystem.viewport(0, 0, main.width, main.height);
        RenderSystem.enableBlend();
    }

    private static void ensureTargets(int w, int h)
    {
        if (blurTargetA == null || blurTargetA.width != w || blurTargetA.height != h)
        {
            if (blurTargetA != null) blurTargetA.destroyBuffers();
            if (blurTargetB != null) blurTargetB.destroyBuffers();
            blurTargetA = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            blurTargetA.setFilterMode(GlConst.GL_LINEAR);
            blurTargetB = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            blurTargetB.setFilterMode(GlConst.GL_LINEAR);
        }
    }

    /** 用指定 shader 画一个全屏四边形（当前投影下 0..1 覆盖视口）；调用方负责视口/投影 */
    private static void drawTexturedQuadUv(int textureId, float u0, float v0, float u1, float v1,
                                           ShaderInstance shader)
    {
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        RenderSystem.setShader(() -> shader);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(0, 0, 0).uv(u0, v0).endVertex();
        bb.vertex(0, 1, 0).uv(u0, v1).endVertex();
        bb.vertex(1, 1, 0).uv(u1, v1).endVertex();
        bb.vertex(1, 0, 0).uv(u1, v0).endVertex();
        BufferUploader.drawWithShader(bb.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** 把 textureId 全屏 blit 到 target（简单纹理拷贝，使用 vanilla 位置-纹理 shader） */
    private static void blitFullscreen(int textureId, TextureTarget target, int w, int h)
    {
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.viewport(0, 0, w, h);
        drawTexturedQuadUv(textureId, 0.0f, 0.0f, 1.0f, 1.0f,
                net.minecraft.client.renderer.GameRenderer.getPositionTexShader());
    }

    /** 对 srcTexture 做一次方向性高斯模糊，写入 target */
    private static void blurPass(int srcTexture, TextureTarget target, int w, int h, float dx, float dy)
    {
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.viewport(0, 0, w, h);

        ShaderInstance shader = GlassRenderer.getBlurShader();
        if (shader == null)
        {
            // shader 未注册时退化为纯拷贝
            drawTexturedQuadUv(srcTexture, 0.0f, 0.0f, 1.0f, 1.0f,
                    net.minecraft.client.renderer.GameRenderer.getPositionTexShader());
            return;
        }
        RenderSystem.setShader(() -> shader);
        shader.safeGetUniform("TexelSize").set(1.0f / w, 1.0f / h);
        shader.safeGetUniform("Direction").set(dx, dy);
        drawTexturedQuadUv(srcTexture, 0.0f, 0.0f, 1.0f, 1.0f, shader);
    }

    /**
     * 把模糊结果按面板矩形取样绘制（UV 对齐全屏，使模糊图与世界背景对位）。
     * x,y,w,h 为 GUI 坐标。
     */
    public static void drawBlurredRegion(int x, int y, int w, int h)
    {
        if (blurTargetA == null || srcWidth <= 0 || srcHeight <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        double scale = mc.getWindow().getGuiScale();

        // GUI 坐标 -> 抓取时帧缓冲像素坐标 -> 归一化 UV
        double px = x * scale, py = y * scale;
        double pw = w * scale, ph = h * scale;
        double u0 = px / srcWidth, v0 = py / srcHeight;
        double u1 = (px + pw) / srcWidth, v1 = (py + ph) / srcHeight;
        v0 = 1.0 - v0; v1 = 1.0 - v1;   // 帧缓冲纹理 Y 轴翻转

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        drawTexturedQuadPx(blurTargetA.getColorTextureId(), x, y, w, h,
                (float) u0, (float) v0, (float) u1, (float) v1);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /** GUI 像素坐标版本的四边形绘制 */
    private static void drawTexturedQuadPx(int textureId, int x, int y, int w, int h,
                                           float u0, float v0, float u1, float v1)
    {
        RenderSystem.setShaderTexture(0, textureId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        ShaderInstance shader =
                net.minecraft.client.renderer.GameRenderer.getPositionTexShader();
        RenderSystem.setShader(() -> shader);
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.vertex(x, y, 0).uv(u0, v0).endVertex();
        bb.vertex(x, y + h, 0).uv(u0, v1).endVertex();
        bb.vertex(x + w, y + h, 0).uv(u1, v1).endVertex();
        bb.vertex(x + w, y, 0).uv(u1, v0).endVertex();
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
