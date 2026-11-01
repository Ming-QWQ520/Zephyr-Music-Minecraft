package com.zephyr.music.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zephyr.music.ZephyrMusic;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/**
 * ★ v0.1.0 登录态独立存储（config/zephyrmusic-login.json）
 *
 * 背景：旧版本把 NetEase cookie 存在 Forge 配置 zephyrmusic-client.toml 的
 * auth.cookie 里。Forge 在模组版本更新 / 配置结构变化时会对配置文件做
 * "not correct. Correcting" 修正，cookie 会随修正被清空 —— 用户升级后
 * 每次都要重新扫码登录。
 *
 * 现在登录态放在独立 JSON 文件中，完全不经过 Forge 配置系统：
 * - 模组版本更新不会触碰它
 * - Forge 配置修正不会触碰它
 * - 首次运行时自动从旧配置 auth.cookie 迁移（迁移后旧值保留作回滚备份）
 */
public final class LoginStore
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** cookie == null 表示尚未从磁盘加载 */
    private static String cookie = null;
    private static boolean migrated = false;

    private LoginStore() {}

    /** 获取已保存的 cookie（无则返回空串） */
    public static synchronized String getCookie()
    {
        ensureLoaded();
        return cookie == null ? "" : cookie;
    }

    public static synchronized boolean hasCookie()
    {
        return !getCookie().isEmpty();
    }

    public static synchronized void setCookie(String value)
    {
        ensureLoaded();
        cookie = value == null ? "" : value;
        save();
    }

    public static synchronized void clear()
    {
        setCookie("");
    }

    // ------------------------------------------------------------------

    private static Path file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("zephyrmusic-login.json");
    }

    private static void ensureLoaded()
    {
        if (cookie != null) return;
        cookie = "";

        Path f = file();
        if (Files.isRegularFile(f))
        {
            try
            {
                String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                Map<?, ?> map = GSON.fromJson(json, Map.class);
                if (map != null && map.get("cookie") != null)
                {
                    cookie = String.valueOf(map.get("cookie"));
                }
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr] LoginStore: failed to read {}, starting fresh ({})",
                        f.getFileName(), e.toString());
            }
            return;
        }

        // ★ 旧版本迁移：cookie 曾存在 Forge 配置 auth.cookie
        if (!migrated)
        {
            migrated = true;
            try
            {
                String legacy = ZephyrConfig.COOKIE.get();
                if (legacy != null && !legacy.isEmpty())
                {
                    cookie = legacy;
                    save();
                    ZephyrMusic.LOGGER.info("[Zephyr] LoginStore: migrated login state from legacy config (cookie length {})",
                            legacy.length());
                }
            }
            catch (Throwable t)
            {
                // 配置尚未加载完成等情况 —— 忽略，下次启动再迁移
                ZephyrMusic.LOGGER.warn("[Zephyr] LoginStore: legacy migration skipped ({})", t.toString());
            }
        }
    }

    private static void save()
    {
        try
        {
            Path f = file();
            Path parent = f.getParent();
            if (parent != null) Files.createDirectories(parent);
            String json = GSON.toJson(java.util.Collections.singletonMap("cookie", cookie == null ? "" : cookie));
            // 原子写入：先写临时文件再替换，避免写一半被读到
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try
            {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (Exception atomicFailed)
            {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] LoginStore: failed to save login state", e);
        }
    }
}
