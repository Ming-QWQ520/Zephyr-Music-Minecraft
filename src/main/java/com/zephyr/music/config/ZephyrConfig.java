package com.zephyr.music.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Zephyr Music 客户端配置
 */
public class ZephyrConfig
{
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> API_BASE;
    public static final ForgeConfigSpec.ConfigValue<String> COOKIE;
    public static final ForgeConfigSpec.IntValue HUD_X;
    public static final ForgeConfigSpec.IntValue HUD_Y;
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HUD_SHOW_LYRICS;
    public static final ForgeConfigSpec.IntValue HUD_LYRICS_LINES;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_QUALITY;
    public static final ForgeConfigSpec.DoubleValue HUD_VOLUME;
    public static final ForgeConfigSpec.BooleanValue SCROBBLE_ENABLED;

    static
    {
        BUILDER.push("general");
        API_BASE = BUILDER.comment("NetEase Cloud Music API base URL")
                .define("api_base", "https://musicapi.mingqwq.top");
        DEFAULT_QUALITY = BUILDER.comment("Default audio quality: standard / higher / exhigh / lossless / hires")
                .define("default_quality", "exhigh");
        SCROBBLE_ENABLED = BUILDER.comment("Whether to send play records (scrobble) to NetEase")
                .define("scrobble_enabled", true);
        BUILDER.pop();

        BUILDER.push("auth");
        COOKIE = BUILDER.comment("Stored NetEase cookie for authenticated requests")
                .define("cookie", "");
        BUILDER.pop();

        BUILDER.push("hud");
        HUD_ENABLED = BUILDER.comment("Whether the in-game HUD is enabled")
                .define("hud_enabled", true);
        HUD_X = BUILDER.comment("HUD X position (top-left)")
                .defineInRange("hud_x", 8, 0, 1920);
        HUD_Y = BUILDER.comment("HUD Y position")
                .defineInRange("hud_y", 8, 0, 1080);
        HUD_SHOW_LYRICS = BUILDER.comment("Show lyrics in HUD")
                .define("hud_show_lyrics", true);
        HUD_LYRICS_LINES = BUILDER.comment("Number of lyric lines to display in HUD")
                .defineInRange("hud_lyrics_lines", 4, 1, 10);
        HUD_VOLUME = BUILDER.comment("Music volume (0.0 - 1.0)")
                .defineInRange("hud_volume", 0.6, 0.0, 1.0);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
