package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseUser;
import com.zephyr.music.client.audio.CoverTextureManager;
import com.zephyr.music.client.gui.ModernUI;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 账号详情界面 - 仿 Zephyr Music 桌面版
 */
public class AccountScreen extends Screen
{
    private NeteaseUser user;
    private boolean loading = true;
    private String errorMessage = "";

    public AccountScreen() { super(Component.literal("Zephyr Music · 账号")); }

    @Override
    protected void init()
    {
        int navY = 6;
        addRenderableWidget(Button.builder(Component.literal("播放器"), b -> minecraft.setScreen(new PlayerScreen())).bounds(8, navY, 42, 16).build());
        addRenderableWidget(Button.builder(Component.literal("歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen())).bounds(54, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> minecraft.setScreen(new SearchScreen())).bounds(98, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("设置"), b -> minecraft.setScreen(new SettingsScreen())).bounds(142, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose()).bounds(this.width - 22, navY, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("退出登录"), b -> doLogout()).bounds(this.width / 2 - 60, this.height - 36, 120, 20).build());
        loadUserDetail();
    }

    private void loadUserDetail()
    {
        NeteaseUser baseUser = NeteaseSession.getInstance().getCurrentUser();
        if (baseUser == null)
        {
            NeteaseSession.getInstance().checkLoginStatus().thenAccept(ok -> {
                if (ok) fetchDetail();
                else { loading = false; errorMessage = "未登录"; minecraft.execute(() -> minecraft.setScreen(new LoginScreen())); }
            });
        }
        else { user = baseUser; fetchDetail(); }
    }

    private void fetchDetail()
    {
        if (user == null || user.userId == 0) { loading = false; errorMessage = "无法获取用户 ID"; return; }
        loading = true;
        ZephyrMusic.LOGGER.info("[Zephyr] Loading user detail for uid={}", user.userId);
        NeteaseSession.getInstance().getApi().userDetail(user.userId).thenAccept(resp -> {
            ZephyrMusic.LOGGER.info("[Zephyr] userDetail response keys: {}", resp.keySet());
            NeteaseUser detailed = NeteaseApi.parseUserDetail(resp, user);
            if (detailed != null) { user = detailed; NeteaseSession.getInstance().updateCurrentUser(user); }
            loading = false;
        }).exceptionally(e -> { ZephyrMusic.LOGGER.error("[Zephyr] userDetail failed", e); loading = false; errorMessage = "加载失败"; return null; });
    }

    private void doLogout()
    {
        NeteaseSession.getInstance().logout().thenAccept(v -> minecraft.execute(() -> minecraft.setScreen(new LoginScreen())));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        int cx = this.width / 2;
        final int COLOR_ACCENT = 0xFF00FFFF;
        final int COLOR_TEXT = 0xFFFFFFFF;
        final int COLOR_DIM = 0xFFAAAAAA;
        final int COLOR_PANEL = 0xE0452A1F;

        if (loading) { g.drawCenteredString(this.font, Component.literal("加载用户信息…"), cx, this.height / 2, COLOR_ACCENT); super.render(g, mouseX, mouseY, partialTick); return; }
        if (user == null) { g.drawCenteredString(this.font, Component.literal(errorMessage.isEmpty() ? "未登录" : errorMessage), cx, this.height / 2, 0xFFFF5555); super.render(g, mouseX, mouseY, partialTick); return; }

        int cardW = Math.min(400, this.width - 40);
        int cardX = cx - cardW / 2;
        int cardY = 40;
        int cardH = 280;
        ModernUI.fillRound(g, cardX, cardY, cardW, cardH, 10, COLOR_PANEL);
        ModernUI.strokeRound(g, cardX, cardY, cardW, cardH, 10, 0x80FFA000, 1);

        int avatarSize = 72;
        int avatarX = cardX + (cardW - avatarSize) / 2;
        int avatarY = cardY + 24;
        renderAvatar(g, user.avatarUrl, avatarX, avatarY, avatarSize);

        int textY = avatarY + avatarSize + 8;
        String nickname = user.nickname;
        if (nickname.length() > 20) nickname = nickname.substring(0, 19) + "…";
        g.drawCenteredString(this.font, Component.literal(nickname), cx, textY, COLOR_TEXT);
        textY += 12;
        if (user.vipType > 0)
        {
            String vipText = "VIP";
            int vipW = this.font.width(vipText) + 8;
            int vipX = cx + this.font.width(nickname) / 2 + 4;
            ModernUI.fillRound(g, vipX, textY - 10, vipW, 10, 3, 0xFFFFAA00);
            g.drawCenteredString(this.font, Component.literal(vipText), vipX + vipW / 2, textY - 10, COLOR_TEXT);
        }
        textY += 6;
        if (user.signature != null && !user.signature.isEmpty())
        {
            String sig = user.signature; if (sig.length() > 40) sig = sig.substring(0, 39) + "…";
            g.drawCenteredString(this.font, Component.literal("「" + sig + "」"), cx, textY, COLOR_DIM);
        }
        else g.drawCenteredString(this.font, Component.literal("暂无签名"), cx, textY, COLOR_DIM);

        textY += 14;
        g.fill(cardX + 40, textY, cardX + cardW - 40, textY + 1, 0x40FFFFFF);
        textY += 10;

        int infoX = cardX + 30;
        int infoX2 = cardX + cardW / 2 + 10;
        g.drawString(this.font, Component.literal("📅 注册时间"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(formatCreateTime(user.createTime)), infoX2, textY, COLOR_TEXT, false);
        textY += 14;
        g.drawString(this.font, Component.literal("⚧ 性别"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(formatGender(user.gender)), infoX2, textY, COLOR_TEXT, false);
        textY += 14;
        g.drawString(this.font, Component.literal("📍 所在地"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(com.zephyr.music.api.RegionCodeMapper.formatLocation(user.province, user.city)), infoX2, textY, COLOR_TEXT, false);
        textY += 14;
        g.drawString(this.font, Component.literal("🎵 听歌数量"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(user.listenSongs > 0 ? user.listenSongs + " 首" : "未知"), infoX2, textY, COLOR_ACCENT, false);
        textY += 14;
        g.drawString(this.font, Component.literal("📊 用户等级"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(user.level > 0 ? "Lv." + user.level : "未知"), infoX2, textY, COLOR_ACCENT, false);
        textY += 14;
        g.drawString(this.font, Component.literal("🆔 用户 ID"), infoX, textY, COLOR_DIM, false);
        g.drawString(this.font, Component.literal(String.valueOf(user.userId)), infoX2, textY, COLOR_TEXT, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderAvatar(GuiGraphics g, String avatarUrl, int x, int y, int size)
    {
        if (avatarUrl == null || avatarUrl.isEmpty())
        {
            ModernUI.fillRound(g, x, y, size, size, size / 2, 0xFF1A1A1A);
            String t = "♪"; int tw = this.font.width(t);
            g.drawString(this.font, Component.literal(t), x + (size - tw) / 2, y + size / 2 - 4, 0xFF888888, false);
            return;
        }
        ResourceLocation texId = CoverTextureManager.getInstance().getCover(avatarUrl, null);
        if (texId != null)
        {
            RenderSystem.setShaderTexture(0, texId);
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            g.blit(texId, x, y, 0, 0, size, size, size, size);
            RenderSystem.disableBlend();
        }
        else
        {
            ModernUI.fillRound(g, x, y, size, size, size / 2, 0xFF1A1A1A);
            String t = "..."; int tw = this.font.width(t);
            g.drawString(this.font, Component.literal(t), x + (size - tw) / 2, y + size / 2 - 4, 0xFF888888, false);
        }
    }

    private String formatCreateTime(long createTimeMs)
    {
        if (createTimeMs <= 0) return "未知";
        try { return new SimpleDateFormat("yyyy-MM-dd").format(new Date(createTimeMs)); }
        catch (Exception e) { return "未知"; }
    }

    private String formatGender(int gender)
    {
        switch (gender) { case 1: return "男"; case 2: return "女"; default: return "保密"; }
    }

    @Override public boolean isPauseScreen() { return false; }
}
