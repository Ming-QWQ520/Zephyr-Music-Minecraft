package com.zephyr.music.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.client.gui.screen.PlayerScreen;
import com.zephyr.music.client.glass.GlassRenderer;
import com.zephyr.music.client.hud.MusicHudOverlay;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

/**
 * 客户端事件总线 - F7 打开播放器 / F8 HUD 编辑模式 + 自定义 shader 注册
 */
@Mod.EventBusSubscriber(modid = ZephyrMusic.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEventHandler
{
    public static KeyMapping KEY_OPEN;
    public static KeyMapping KEY_HUD_EDIT;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        KEY_OPEN = new KeyMapping("key.zephyr.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.zephyr");
        KEY_HUD_EDIT = new KeyMapping("key.zephyr.hud_edit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.zephyr");
        event.register(KEY_OPEN);
        event.register(KEY_HUD_EDIT);
        ZephyrMusic.LOGGER.info("[Zephyr] Key mappings registered (F7 open, F8 HUD edit)");
    }

    /** 注册自定义 core shader（SDF 圆角矩形 + 高斯模糊） */
    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException
    {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), "zephyrmusic/glass", DefaultVertexFormat.POSITION_COLOR_TEX),
                GlassRenderer::setGlassShader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), "zephyrmusic/blur", DefaultVertexFormat.POSITION_TEX),
                GlassRenderer::setBlurShader);
        ZephyrMusic.LOGGER.info("[Zephyr] Glass shaders registered");
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
        MusicHudOverlay.registerInput();
    }

    private static void onClientKeyInput(InputEvent.Key event)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (event.getAction() == GLFW.GLFW_PRESS)
        {
            // F8: HUD 编辑模式（悬浮面板跟随鼠标，再按一次保存位置）
            if (KEY_HUD_EDIT != null && KEY_HUD_EDIT.consumeClick())
            {
                MusicHudOverlay.toggleEditMode();
            }

            if (mc.screen != null) return;

            // F7: 打开播放器，有 cookie 切换到歌单 Tab，没有则切换到登录 Tab
            if (KEY_OPEN != null && KEY_OPEN.consumeClick())
            {
                PlayerScreen ps = new PlayerScreen();
                if (com.zephyr.music.config.LoginStore.hasCookie())
                {
                    ps.setCurrentTab(PlayerScreen.Tab.PLAYLIST);
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
}
