package com.zephyr.music.client.gui.screen;

import com.zephyr.music.client.gui.ModernUI;
import com.zephyr.music.config.ZephyrConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 设置界面 - HUD/歌词/主题自定义
 */
public class SettingsScreen extends Screen
{
    private SettingsList list;

    public SettingsScreen()
    {
        super(Component.literal("Zephyr Music · 设置"));
    }

    @Override
    protected void init()
    {
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 22, 6, 14, 16).build());

        addRenderableWidget(Button.builder(Component.literal("播放器"), b -> minecraft.setScreen(new PlayerScreen()))
                .bounds(this.width - 64, 6, 42, 16).build());

        list = new SettingsList();
        addWidget(list);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // 不绘制默认泥土背景
        // renderBackground(g);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.getTitle(), cx, 14, 0xFFFFFFFF);
        g.drawString(this.font, Component.literal("修改后即时生效，配置自动保存"), 14, 40, 0xFF888888, false);

        list.render(g, mouseX, mouseY, partialTick);

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private class SettingsList extends ObjectSelectionList<SettingsList.Entry>
    {
        
        @Override
        public int getRowWidth()
        {
            return this.width - 8;
        }

        public SettingsList()
        {
            super(SettingsScreen.this.minecraft, SettingsScreen.this.width - 40,
                    SettingsScreen.this.height - 100, 60, SettingsScreen.this.height - 40, 24);
            setLeftPos(20);

            // === HUD 设置 ===
            addEntry(new HeaderEntry("─── HUD 显示 ───"));
            addEntry(new ToggleEntry("启用 HUD",
                    ZephyrConfig.HUD_ENABLED,
                    Arrays.asList("OFF", "ON")));
            addEntry(new CycleEntry("HUD 锚点",
                    ZephyrConfig.HUD_ANCHOR,
                    Arrays.asList("top_left", "top_right", "bottom_left", "bottom_right")));
            addEntry(new SliderEntryI("HUD X 偏移", ZephyrConfig.HUD_X, 0, 3840));
            addEntry(new SliderEntryI("HUD Y 偏移", ZephyrConfig.HUD_Y, 0, 2160));
            addEntry(new SliderEntryI("面板宽度", ZephyrConfig.HUD_PANEL_WIDTH, 100, 600));
            addEntry(new SliderEntryD("背景透明度", ZephyrConfig.HUD_BG_OPACITY, 0.0, 1.0));
            addEntry(new ToggleEntry("显示边框", ZephyrConfig.HUD_SHOW_BORDER, Arrays.asList("OFF", "ON")));
            addEntry(new ToggleEntry("显示封面", ZephyrConfig.HUD_SHOW_COVER, Arrays.asList("OFF", "ON")));
            addEntry(new SliderEntryI("封面大小", ZephyrConfig.HUD_COVER_SIZE, 16, 256));
            addEntry(new ToggleEntry("紧凑模式", ZephyrConfig.HUD_COMPACT, Arrays.asList("OFF", "ON")));
            addEntry(new ToggleEntry("显示进度条", ZephyrConfig.HUD_SHOW_PROGRESS_BAR, Arrays.asList("OFF", "ON")));
            addEntry(new SliderEntryD("音量", ZephyrConfig.HUD_VOLUME, 0.0, 1.0));

            // === 歌词设置 ===
            addEntry(new HeaderEntry("─── 歌词 ───"));
            addEntry(new CycleEntry("歌词模式", ZephyrConfig.LYRIC_MODE,
                    Arrays.asList("yrc", "lrc", "off")));
            addEntry(new ToggleEntry("卡拉OK 逐字染色", ZephyrConfig.LYRIC_KARAOKE, Arrays.asList("OFF", "ON")));
            addEntry(new ToggleEntry("显示歌词", ZephyrConfig.HUD_SHOW_LYRICS, Arrays.asList("OFF", "ON")));
            addEntry(new SliderEntryI("HUD 歌词行数", ZephyrConfig.HUD_LYRICS_LINES, 1, 12));
            addEntry(new SliderEntryI("歌词字号", ZephyrConfig.LYRIC_FONT_SIZE, 6, 16));
            addEntry(new ColorEntry("当前行颜色", ZephyrConfig.LYRIC_ACTIVE_COLOR, 0xFF1DB954));
            addEntry(new ColorEntry("其他行颜色", ZephyrConfig.LYRIC_OTHER_COLOR, 0xFF888888));
            addEntry(new ColorEntry("已唱字颜色", ZephyrConfig.LYRIC_WORD_PLAYED_COLOR, 0xFF1DB954));
            addEntry(new ColorEntry("当前字颜色", ZephyrConfig.LYRIC_WORD_CURRENT_COLOR, 0xFF4ADE80));
            addEntry(new ColorEntry("未唱字颜色", ZephyrConfig.LYRIC_WORD_UNPLAYED_COLOR, 0xFF666666));

            // === 主题 ===
            addEntry(new HeaderEntry("─── 主题色 ───"));
            addEntry(new StringEntry("主题色 (HEX)", ZephyrConfig.THEME_PRIMARY, "#1DB954"));
            addEntry(new StringEntry("强调色 (HEX)", ZephyrConfig.THEME_ACCENT, "#4ADE80"));
            addEntry(new StringEntry("背景色 (HEX)", ZephyrConfig.THEME_BG, "#0A0A0A"));

            // === 通用 ===
            addEntry(new HeaderEntry("─── 通用 ───"));
            addEntry(new CycleEntry("音质", ZephyrConfig.DEFAULT_QUALITY,
                    Arrays.asList("standard", "higher", "exhigh", "lossless", "hires")));
            addEntry(new StringEntry("API 地址", ZephyrConfig.API_BASE, "https://musicapi.mingqwq.top"));
            addEntry(new ToggleEntry("听歌打卡", ZephyrConfig.SCROBBLE_ENABLED, Arrays.asList("OFF", "ON")));
        }

        abstract class Entry extends ObjectSelectionList.Entry<Entry>
        {
            abstract String label();
        }

        class HeaderEntry extends Entry
        {
            final String text;
            HeaderEntry(String t) { this.text = t; }
            @Override String label() { return text; }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawCenteredString(SettingsScreen.this.font, Component.literal(text), left + w / 2, top + 4, 0xFF1DB954);
            }
            @Override public boolean mouseClicked(double x, double y, int b) { return false; }
            @Override public Component getNarration() { return Component.literal(text); }
        }

        class ToggleEntry extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.BooleanValue cfg;
            final List<String> options;
            ToggleEntry(String n, net.minecraftforge.common.ForgeConfigSpec.BooleanValue c, List<String> opts)
            { name = n; cfg = c; options = opts; }
            @Override String label() { return name + ": " + (cfg.get() ? options.get(1) : options.get(0)); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                String val = cfg.get() ? options.get(1) : options.get(0);
                int vw = SettingsScreen.this.font.width(val);
                ModernUI.fillRound(g, left + w - vw - 16, top + 2, vw + 12, h - 4, 4, 0xFF222222);
                g.drawString(SettingsScreen.this.font, Component.literal(val), left + w - vw - 10, top + 4,
                        cfg.get() ? 0xFF4ADE80 : 0xFFAAAAAA, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                cfg.set(!cfg.get());
                return true;
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }

        class CycleEntry extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> cfg;
            final List<String> options;
            int idx = 0;
            CycleEntry(String n, net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> c, List<String> opts)
            {
                name = n; cfg = c; options = opts;
                String cur = cfg.get();
                idx = opts.indexOf(cur);
                if (idx < 0) idx = 0;
            }
            @Override String label() { return name + ": " + cfg.get(); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                String val = cfg.get();
                int vw = SettingsScreen.this.font.width(val);
                ModernUI.fillRound(g, left + w - vw - 16, top + 2, vw + 12, h - 4, 4, 0xFF222222);
                g.drawString(SettingsScreen.this.font, Component.literal(val), left + w - vw - 10, top + 4,
                        0xFF4ADE80, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                idx = (idx + 1) % options.size();
                cfg.set(options.get(idx));
                return true;
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }

        class SliderEntryI extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.IntValue cfg;
            final int min, max;
            SliderEntryI(String n, net.minecraftforge.common.ForgeConfigSpec.IntValue c, int mn, int mx)
            { name = n; cfg = c; min = mn; max = mx; }
            @Override String label() { return name + ": " + cfg.get(); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                int val = cfg.get();
                double prog = (val - min) / (double) (max - min);
                int barW = 100;
                int barX = left + w - barW - 50;
                ModernUI.drawProgressBar(g, barX, top + 6, barW, 8, prog);
                String vs = String.valueOf(val);
                g.drawString(SettingsScreen.this.font, Component.literal(vs), left + w - 40, top + 4, 0xFF4ADE80, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                int barW = 100;
                int barX = SettingsList.this.getRowWidth() - barW - 50 + 20; // approximate
                double rel = (x - barX) / barW;
                if (rel < 0 || rel > 1) return false;
                int nv = (int) (min + (max - min) * rel);
                cfg.set(nv);
                return true;
            }
            @Override
            public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
            {
                return mouseClicked(mouseX, mouseY, button);
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }

        class SliderEntryD extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.DoubleValue cfg;
            final double min, max;
            SliderEntryD(String n, net.minecraftforge.common.ForgeConfigSpec.DoubleValue c, double mn, double mx)
            { name = n; cfg = c; min = mn; max = mx; }
            @Override String label() { return name + ": " + String.format("%.2f", cfg.get()); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                double val = cfg.get();
                double prog = (val - min) / (max - min);
                int barW = 100;
                int barX = left + w - barW - 60;
                ModernUI.drawProgressBar(g, barX, top + 6, barW, 8, prog);
                String vs = String.format("%.2f", val);
                g.drawString(SettingsScreen.this.font, Component.literal(vs), left + w - 50, top + 4, 0xFF4ADE80, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                int barW = 100;
                int barX = SettingsList.this.getRowWidth() - barW - 60 + 20;
                double rel = (x - barX) / barW;
                if (rel < 0 || rel > 1) return false;
                double nv = min + (max - min) * rel;
                cfg.set(nv);
                return true;
            }
            @Override
            public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
            {
                return mouseClicked(mouseX, mouseY, button);
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }

        class ColorEntry extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.IntValue cfg;
            ColorEntry(String n, net.minecraftforge.common.ForgeConfigSpec.IntValue c, int def)
            { name = n; cfg = c; }
            @Override String label() { return name + ": " + Integer.toHexString(cfg.get()).toUpperCase(); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                // 颜色块预览
                int val = cfg.get();
                ModernUI.fillRound(g, left + 150, top + 2, 30, h - 4, 4, val);
                String hex = String.format("#%08X", val);
                g.drawString(SettingsScreen.this.font, Component.literal(hex), left + 200, top + 4, 0xFFCCCCCC, false);
                g.drawString(SettingsScreen.this.font, Component.literal("[点击切换]"), left + w - 70, top + 4, 0xFF888888, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                // 在几个预设颜色间循环
                int[] presets = {0xFF1DB954, 0xFF4ADE80, 0xFFFFFFFF, 0xFFFF5555, 0xFFFFAA00,
                        0xFF00BFFF, 0xFFFF69B4, 0xFF888888};
                int cur = cfg.get();
                int idx = -1;
                for (int i = 0; i < presets.length; i++) if (presets[i] == cur) { idx = i; break; }
                int next = presets[(idx + 1) % presets.length];
                cfg.set(next);
                return true;
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }

        class StringEntry extends Entry
        {
            final String name;
            final net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> cfg;
            final String def;
            EditBox edit;
            StringEntry(String n, net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> c, String d)
            {
                name = n; cfg = c; def = d;
            }
            @Override String label() { return name + ": " + cfg.get(); }
            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int w, int h, int mx, int my, boolean hov, float pt)
            {
                g.drawString(SettingsScreen.this.font, Component.literal(name), left + 4, top + 4, 0xFFFFFFFF, false);
                String val = cfg.get();
                if (val.length() > 30) val = val.substring(0, 30) + "…";
                g.drawString(SettingsScreen.this.font, Component.literal(val), left + 200, top + 4, 0xFF4ADE80, false);
            }
            @Override
            public boolean mouseClicked(double x, double y, int b)
            {
                // 简单实现：点击弹出输入框比较麻烦，这里循环几个预设
                if (name.contains("API"))
                {
                    String[] presets = {
                        "https://musicapi.mingqwq.top",
                        "http://localhost:3000"
                    };
                    String cur = cfg.get();
                    int idx = -1;
                    for (int i = 0; i < presets.length; i++) if (presets[i].equals(cur)) { idx = i; break; }
                    cfg.set(presets[(idx + 1) % presets.length]);
                }
                else if (name.contains("主题色") || name.contains("强调色") || name.contains("背景色"))
                {
                    String[] presets = {"#1DB954", "#4ADE80", "#0A0A0A", "#FF5555", "#00BFFF",
                                        "#9D4EDD", "#FF6B35", "#00F5D4"};
                    String cur = cfg.get();
                    int idx = -1;
                    for (int i = 0; i < presets.length; i++) if (presets[i].equals(cur)) { idx = i; break; }
                    cfg.set(presets[(idx + 1) % presets.length]);
                }
                return true;
            }
            @Override public Component getNarration() { return Component.literal(label()); }
        }
    }
}
