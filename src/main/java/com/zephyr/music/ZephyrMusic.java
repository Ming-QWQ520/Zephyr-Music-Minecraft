package com.zephyr.music;

import com.mojang.logging.LogUtils;
import com.zephyr.music.client.ClientEventHandler;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Zephyr Music - 网易云音乐模组主类
 *
 * 客户端模组：登录网易云账号、播放歌单、HUD 显示歌词
 */
@Mod(ZephyrMusic.MODID)
public class ZephyrMusic
{
    public static final String MODID = "zephyrmusic";
    public static final String NAME = "Zephyr Music";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ZephyrMusic(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        modEventBus.addListener(this::commonSetup);

        context.registerConfig(ModConfig.Type.CLIENT, ZephyrConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("[Zephyr Music] Initializing - Forge 1.20.1-47.4.20");
        event.enqueueWork(() -> {
            // 提前初始化 HttpClient
            com.zephyr.music.net.NeteaseHttpClient.getInstance();
        });
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            event.enqueueWork(() -> {
                ClientEventHandler.register();
                LOGGER.info("[Zephyr Music] Client setup complete");
            });
        }
    }
}
