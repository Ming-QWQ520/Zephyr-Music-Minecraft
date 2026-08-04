package com.zephyr.music.client.audio;

import com.mojang.blaze3d.platform.NativeImage;
import com.zephyr.music.ZephyrMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 专辑封面纹理管理器
 *
 * - 异步下载封面图（带内存缓存）
 * - BufferedImage → NativeImage → DynamicTexture
 * - 注册到 Minecraft TextureManager，返回 ResourceLocation
 * - 支持取消/清理
 */
public class CoverTextureManager
{
    private static CoverTextureManager instance;

    private final ExecutorService executor;
    /** picUrl → ResourceLocation 缓存（已加载完成） */
    private final Map<String, ResourceLocation> textureCache = new HashMap<>();
    /** picUrl → 加载中的 Future（防止重复下载） */
    private final Map<String, CompletableFuture<ResourceLocation>> loadingFutures = new HashMap<>();
    /** picUrl → DynamicTexture（用于清理时释放） */
    private final Map<String, DynamicTexture> dynamicTextures = new HashMap<>();

    /** 占位用的默认纹理 ID（未加载/加载中） */
    private static final ResourceLocation PLACEHOLDER = new ResourceLocation("zephyrmusic", "cover_placeholder");
    private static boolean placeholderRegistered = false;

    private CoverTextureManager()
    {
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ZephyrMusic-CoverLoader");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized CoverTextureManager getInstance()
    {
        if (instance == null) instance = new CoverTextureManager();
        return instance;
    }

    /**
     * 获取封面纹理
     * - 如果已缓存，立即返回
     * - 如果正在加载，返回 null（调用方应该用占位）
     * - 否则启动异步加载，加载完成后回调
     *
     * @param picUrl 封面 URL
     * @param onLoaded 加载完成回调（在 Minecraft 主线程）
     * @return ResourceLocation，如果未加载返回 null
     */
    public ResourceLocation getCover(String picUrl, Runnable onLoaded)
    {
        if (picUrl == null || picUrl.isEmpty())
        {
            return null;
        }

        // 已缓存
        ResourceLocation cached = textureCache.get(picUrl);
        if (cached != null)
        {
            return cached;
        }

        // 正在加载
        if (loadingFutures.containsKey(picUrl))
        {
            // 已经在加载，附加回调
            if (onLoaded != null)
            {
                loadingFutures.get(picUrl).thenAccept(loc -> {
                    if (loc != null)
                    {
                        Minecraft.getInstance().execute(onLoaded);
                    }
                });
            }
            return null;
        }

        // 启动加载
        CompletableFuture<ResourceLocation> future = CompletableFuture.supplyAsync(() -> {
            try
            {
                return downloadAndRegister(picUrl);
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr-Cover] Failed to load cover {}: {}", picUrl, e.getMessage());
                return null;
            }
        }, executor);

        loadingFutures.put(picUrl, future);

        if (onLoaded != null)
        {
            future.thenAccept(loc -> {
                if (loc != null)
                {
                    Minecraft.getInstance().execute(onLoaded);
                }
            });
        }

        return null;
    }

    /**
     * 同步下载并注册纹理（在后台线程执行）
     */
    private ResourceLocation downloadAndRegister(String picUrl)
    {
        try
        {
            // 转换 http → https
            String url = picUrl;
            if (url.startsWith("http://"))
            {
                url = "https://" + url.substring(7);
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "ZephyrMusic-Mod/1.0");
            conn.setRequestProperty("Referer", "https://music.163.com/");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != 200)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr-Cover] HTTP {} for {}", code, url);
                return null;
            }

            // 读取图片
            InputStream is = conn.getInputStream();
            BufferedImage img = ImageIO.read(is);
            is.close();
            conn.disconnect();

            if (img == null)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr-Cover] ImageIO.read returned null for {}", url);
                return null;
            }

            ZephyrMusic.LOGGER.info("[Zephyr-Cover] Loaded cover: {}x{}", img.getWidth(), img.getHeight());

            // 转换为 NativeImage
            int w = img.getWidth();
            int h = img.getHeight();
            NativeImage nativeImage = new NativeImage(w, h, false);
            for (int py = 0; py < h; py++)
            {
                for (int px = 0; px < w; px++)
                {
                    int argb = img.getRGB(px, py);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int a = (argb >> 24) & 0xFF;
                    // NativeImage 是 ABGR
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setPixelRGBA(px, py, abgr);
                }
            }

            // 注册纹理（必须在主线程）
            final NativeImage finalImage = nativeImage;
            final String finalUrl = picUrl;
            final ResourceLocation[] result = new ResourceLocation[1];

            // 用 Minecraft.execute 在主线程注册
            Minecraft.getInstance().execute(() -> {
                try
                {
                    // URL 转为合法的 resource path
                    String hash = Integer.toHexString(picUrl.hashCode());
                    ResourceLocation texId = new ResourceLocation("zephyrmusic", "cover_" + hash);
                    DynamicTexture dynTex = new DynamicTexture(finalImage);
                    Minecraft.getInstance().getTextureManager().register(texId, dynTex);
                    textureCache.put(finalUrl, texId);
                    dynamicTextures.put(finalUrl, dynTex);
                    result[0] = texId;
                    ZephyrMusic.LOGGER.info("[Zephyr-Cover] Registered texture: {}", texId);
                }
                catch (Exception e)
                {
                    ZephyrMusic.LOGGER.error("[Zephyr-Cover] Failed to register texture", e);
                }
            });

            // 等待主线程完成（简单轮询）
            for (int i = 0; i < 50 && result[0] == null; i++)
            {
                try { Thread.sleep(20); } catch (InterruptedException ignored) { break; }
            }

            loadingFutures.remove(picUrl);
            return result[0];
        }
        catch (Exception e)
        {
            loadingFutures.remove(picUrl);
            ZephyrMusic.LOGGER.error("[Zephyr-Cover] downloadAndRegister failed for {}: {}", picUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 清理指定封面（切歌时调用，释放内存）
     */
    public void releaseCover(String picUrl)
    {
        if (picUrl == null) return;
        ResourceLocation texId = textureCache.remove(picUrl);
        DynamicTexture dynTex = dynamicTextures.remove(picUrl);
        if (dynTex != null && texId != null)
        {
            Minecraft.getInstance().execute(() -> {
                try
                {
                    Minecraft.getInstance().getTextureManager().release(texId);
                    dynTex.close();
                }
                catch (Exception ignored) {}
            });
        }
    }

    /**
     * 清理所有封面（退出游戏时）
     */
    public void releaseAll()
    {
        for (String url : new HashMap<>(textureCache).keySet())
        {
            releaseCover(url);
        }
        textureCache.clear();
        dynamicTextures.clear();
        loadingFutures.clear();
    }

    public void shutdown()
    {
        releaseAll();
        executor.shutdownNow();
    }
}
