package com.zephyr.music.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Zephyr Music 客户端配置 - 含 UI/歌词自定义选项
 */
public class ZephyrConfig
{
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> API_BASE;
    public static final ForgeConfigSpec.ConfigValue<String> COOKIE;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_QUALITY;
    public static final ForgeConfigSpec.BooleanValue SCROBBLE_ENABLED;

    // HUD 基础
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.IntValue HUD_X;
    public static final ForgeConfigSpec.IntValue HUD_Y;
    public static final ForgeConfigSpec.DoubleValue HUD_VOLUME;
    public static final ForgeConfigSpec.ConfigValue<String> HUD_ANCHOR;

    // HUD 显示选项
    public static final ForgeConfigSpec.BooleanValue HUD_COMPACT;
    public static final ForgeConfigSpec.BooleanValue HUD_SHOW_PROGRESS_BAR;
    public static final ForgeConfigSpec.BooleanValue HUD_SHOW_LYRICS;
    public static final ForgeConfigSpec.IntValue HUD_LYRICS_LINES;
    public static final ForgeConfigSpec.IntValue HUD_PANEL_WIDTH;
    public static final ForgeConfigSpec.DoubleValue HUD_BG_OPACITY;
    public static final ForgeConfigSpec.BooleanValue HUD_SHOW_COVER;
    public static final ForgeConfigSpec.IntValue HUD_COVER_SIZE;
    public static final ForgeConfigSpec.BooleanValue HUD_SHOW_BORDER;
    public static final ForgeConfigSpec.BooleanValue HUD_PAUSE_ON_MENU;

    // 歌词样式
    public static final ForgeConfigSpec.ConfigValue<String> LYRIC_MODE;
    public static final ForgeConfigSpec.IntValue LYRIC_ACTIVE_COLOR;
    public static final ForgeConfigSpec.IntValue LYRIC_OTHER_COLOR;
    public static final ForgeConfigSpec.IntValue LYRIC_WORD_PLAYED_COLOR;
    public static final ForgeConfigSpec.IntValue LYRIC_WORD_CURRENT_COLOR;
    public static final ForgeConfigSpec.IntValue LYRIC_WORD_UNPLAYED_COLOR;
    public static final ForgeConfigSpec.BooleanValue LYRIC_KARAOKE;
    public static final ForgeConfigSpec.BooleanValue LYRIC_SCROLL;
    public static final ForgeConfigSpec.IntValue LYRIC_FONT_SIZE;

    // 主题色
    public static final ForgeConfigSpec.ConfigValue<String> THEME_PRIMARY;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_ACCENT;
    public static final ForgeConfigSpec.ConfigValue<String> THEME_BG;

    static
    {
        BUILDER.push("general");
        API_BASE = BUILDER.comment("NetEase Cloud Music API base URL")
                .define("api_base", "https://musicapi.mingqwq.top");
        DEFAULT_QUALITY = BUILDER.comment("Audio quality: standard / higher / exhigh / lossless / hires")
                .define("default_quality", "exhigh");
        SCROBBLE_ENABLED = BUILDER.comment("Send play records (scrobble) to NetEase")
                .define("scrobble_enabled", true);
        BUILDER.pop();

        BUILDER.push("auth");
        COOKIE = BUILDER.comment("Stored NetEase cookie")
                .define("cookie", "");
        BUILDER.pop();

        BUILDER.push("hud");
        HUD_ENABLED = BUILDER.define("hud_enabled", true);
        HUD_X = BUILDER.defineInRange("hud_x", 12, 0, 3840);
        HUD_Y = BUILDER.defineInRange("hud_y", 12, 0, 2160);
        HUD_VOLUME = BUILDER.defineInRange("hud_volume", 0.6, 0.0, 1.0);
        HUD_ANCHOR = BUILDER.comment("Anchor: top_left / top_right / bottom_left / bottom_right")
                .define("hud_anchor", "top_left");
        HUD_COMPACT = BUILDER.comment("Compact mode (no extra padding)")
                .define("hud_compact", true);
        HUD_SHOW_PROGRESS_BAR = BUILDER.define("hud_show_progress_bar", true);
        HUD_SHOW_LYRICS = BUILDER.define("hud_show_lyrics", true);
        HUD_LYRICS_LINES = BUILDER.defineInRange("hud_lyrics_lines", 3, 1, 12);
        HUD_PANEL_WIDTH = BUILDER.defineInRange("hud_panel_width", 220, 100, 600);
        HUD_BG_OPACITY = BUILDER.defineInRange("hud_bg_opacity", 0.0, 0.0, 1.0);
        HUD_SHOW_COVER = BUILDER.comment("Show album cover thumbnail")
                .define("hud_show_cover", true);
        HUD_COVER_SIZE = BUILDER.comment("Cover image size (pixels)")
                .defineInRange("hud_cover_size", 64, 16, 256);
        HUD_SHOW_BORDER = BUILDER.comment("Show panel border")
                .define("hud_show_border", false);
        HUD_PAUSE_ON_MENU = BUILDER.comment("Pause playback when game menu is open")
                .define("hud_pause_on_menu", false);
        BUILDER.pop();

        BUILDER.push("lyric");
        LYRIC_MODE = BUILDER.comment("Lyric mode: yrc / lrc / off (yrc=word-by-word if available)")
                .define("lyric_mode", "yrc");
        LYRIC_KARAOKE = BUILDER.comment("Karaoke style: color played words progressively")
                .define("lyric_karaoke", true);
        LYRIC_SCROLL = BUILDER.comment("Smooth scroll lyric lines")
                .define("lyric_scroll", true);
        LYRIC_FONT_SIZE = BUILDER.defineInRange("lyric_font_size", 9, 6, 16);
        // AllMusic 风格：当前歌词青色，其他歌词浅灰
        LYRIC_ACTIVE_COLOR = BUILDER.comment("Active line color (ARGB int)")
                .defineInRange("lyric_active_color", 0xFF00FFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);
        LYRIC_OTHER_COLOR = BUILDER.defineInRange("lyric_other_color", 0xFFCCCCCC, Integer.MIN_VALUE, Integer.MAX_VALUE);
        LYRIC_WORD_PLAYED_COLOR = BUILDER.defineInRange("lyric_word_played_color", 0xFF00FFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);
        LYRIC_WORD_CURRENT_COLOR = BUILDER.defineInRange("lyric_word_current_color", 0xFF80FFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);
        LYRIC_WORD_UNPLAYED_COLOR = BUILDER.defineInRange("lyric_word_unplayed_color", 0xFF888888, Integer.MIN_VALUE, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("theme");
        // AllMusic 风格：青色主题 + 木质背景
        THEME_PRIMARY = BUILDER.comment("Primary theme color (hex)")
                .define("theme_primary", "#00FFFF");
        THEME_ACCENT = BUILDER.comment("Accent theme color (hex)")
                .define("theme_accent", "#00FFFF");
        THEME_BG = BUILDER.comment("Background theme color (hex)")
                .define("theme_bg", "#2A1F12");
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /** 把 #RRGGBB 转为 ARGB int (alpha=FF) */
    public static int hexToArgb(String hex)
    {
        if (hex == null || hex.isEmpty()) return 0xFF1DB954;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() == 6)
        {
            try { return 0xFF000000 | Integer.parseInt(h, 16); }
            catch (NumberFormatException e) { return 0xFF1DB954; }
        }
        if (h.length() == 8)
        {
            try { return (int) Long.parseLong(h, 16); }
            catch (NumberFormatException e) { return 0xFF1DB954; }
        }
        return 0xFF1DB954;
    }

    /** 获取主题色（primary） */
    public static int getPrimaryColor()
    {
        return hexToArgb(THEME_PRIMARY.get());
    }

    /** 获取强调色 */
    public static int getAccentColor()
    {
        return hexToArgb(THEME_ACCENT.get());
    }

    /** 获取背景色（带 alpha） */
    public static int getBgColor(double alpha)
    {
        int c = hexToArgb(THEME_BG.get());
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        int a = (int) (Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
