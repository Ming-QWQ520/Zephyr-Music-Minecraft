package com.zephyr.music.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.client.gui.screen.PlayerScreen;
import com.zephyr.music.client.hud.MusicHudOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端事件总线 - 只保留 F7 快捷键
 */
@Mod.EventBusSubscriber(modid = ZephyrMusic.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEventHandler
{
    public static KeyMapping KEY_OPEN;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        KEY_OPEN = new KeyMapping("key.zephyr.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.zephyr");
        event.register(KEY_OPEN);
        ZephyrMusic.LOGGER.info("[Zephyr] Key mappings registered (F7 only)");
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event)
    {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "zephyr_music_hud", new MusicHudOverlay());
        ZephyrMusic.LOGGER.info("[Zephyr] HUD overlay registered");
    }

    @OnlyIn(Dist.CLIENT)
    public static void register()
    {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ClientEventHandler::onClientKeyInput);
    }

    private static void onClientKeyInput(InputEvent.Key event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        if (KEY_OPEN != null && KEY_OPEN.consumeClick())
        {
            // F7: 打开播放器，有 cookie 切换到歌单 Tab，没有则切换到登录 Tab
            PlayerScreen ps = new PlayerScreen();
            if (!com.zephyr.music.config.ZephyrConfig.COOKIE.get().isEmpty())
            {
                ps.setCurrentTab(PlayerScreen.Tab.PLAYLIST);
                // ★ 异步检查登录状态，更新用户信息
                com.zephyr.music.api.NeteaseSession.getInstance().checkLoginStatus();
            }
            else
            {
                ps.setCurrentTab(PlayerScreen.Tab.ACCOUNT);
            }
            mc.setScreen(ps);
        }
    }
}
