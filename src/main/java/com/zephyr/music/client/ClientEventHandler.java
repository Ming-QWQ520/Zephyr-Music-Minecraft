package com.zephyr.music.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.screen.LoginScreen;
import com.zephyr.music.client.gui.screen.PlayerScreen;
import com.zephyr.music.client.gui.screen.PlaylistBrowserScreen;
import com.zephyr.music.client.gui.screen.SearchScreen;
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
 * 客户端事件总线 - 注册按键绑定与 GUI Overlay
 */
@Mod.EventBusSubscriber(modid = ZephyrMusic.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEventHandler
{
    public static KeyMapping KEY_OPEN_PLAYER;
    public static KeyMapping KEY_OPEN_PLAYLIST;
    public static KeyMapping KEY_OPEN_LOGIN;
    public static KeyMapping KEY_OPEN_SEARCH;
    public static KeyMapping KEY_TOGGLE_PLAY;
    public static KeyMapping KEY_NEXT;
    public static KeyMapping KEY_PREV;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        KEY_OPEN_PLAYER = new KeyMapping("key.zephyr.open_player", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.zephyr");
        KEY_OPEN_PLAYLIST = new KeyMapping("key.zephyr.open_playlist", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.zephyr");
        KEY_OPEN_LOGIN = new KeyMapping("key.zephyr.open_login", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.zephyr");
        KEY_OPEN_SEARCH = new KeyMapping("key.zephyr.open_search", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.zephyr");
        KEY_TOGGLE_PLAY = new KeyMapping("key.zephyr.toggle_play", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.zephyr");
        KEY_NEXT = new KeyMapping("key.zephyr.next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.zephyr");
        KEY_PREV = new KeyMapping("key.zephyr.prev", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.categories.zephyr");

        event.register(KEY_OPEN_PLAYER);
        event.register(KEY_OPEN_PLAYLIST);
        event.register(KEY_OPEN_LOGIN);
        event.register(KEY_OPEN_SEARCH);
        event.register(KEY_TOGGLE_PLAY);
        event.register(KEY_NEXT);
        event.register(KEY_PREV);

        ZephyrMusic.LOGGER.info("[Zephyr] Key mappings registered");
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

        if (KEY_OPEN_PLAYER != null && KEY_OPEN_PLAYER.consumeClick())
        {
            mc.setScreen(new PlayerScreen());
        }
        else if (KEY_OPEN_PLAYLIST != null && KEY_OPEN_PLAYLIST.consumeClick())
        {
            mc.setScreen(new PlaylistBrowserScreen());
        }
        else if (KEY_OPEN_LOGIN != null && KEY_OPEN_LOGIN.consumeClick())
        {
            mc.setScreen(new LoginScreen());
        }
        else if (KEY_OPEN_SEARCH != null && KEY_OPEN_SEARCH.consumeClick())
        {
            mc.setScreen(new SearchScreen());
        }
        else if (KEY_TOGGLE_PLAY != null && KEY_TOGGLE_PLAY.consumeClick())
        {
            MusicPlayer p = MusicPlayer.getInstance();
            if (p.isPlaying())
            {
                if (p.isPaused()) p.resume(); else p.pause();
            }
        }
        else if (KEY_NEXT != null && KEY_NEXT.consumeClick())
        {
            MusicPlayer.getInstance().next();
        }
        else if (KEY_PREV != null && KEY_PREV.consumeClick())
        {
            MusicPlayer.getInstance().prev();
        }
    }
}
