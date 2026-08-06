package com.zephyr.music.client.gui.screen;

import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.MusicPlayer;
import com.zephyr.music.client.gui.ModernUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 播放列表界面（播放队列管理）
 * Enter: 播放 | Del: 移除 | N: 下一首 | C: 清空 | 双击播放 | 右键移除
 */
public class QueueScreen extends Screen
{
    private QueueList queueList;
    private String statusMessage = "";
    private int statusColor = 0xFFAAAAAA;

    public QueueScreen() { super(Component.literal("Zephyr Music · 播放队列")); }

    @Override
    protected void init()
    {
        int navY = 6;
        addRenderableWidget(Button.builder(Component.literal("播放器"), b -> minecraft.setScreen(new PlayerScreen())).bounds(8, navY, 42, 16).build());
        addRenderableWidget(Button.builder(Component.literal("歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen())).bounds(54, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> minecraft.setScreen(new SearchScreen())).bounds(98, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("设置"), b -> minecraft.setScreen(new SettingsScreen())).bounds(142, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("清空"), b -> clearQueue()).bounds(this.width - 116, navY, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose()).bounds(this.width - 22, navY, 14, 16).build());

        queueList = new QueueList(minecraft, this.width - 40, this.height, 32, this.height - 30, 24);
        queueList.setLeftPos(20);
        addWidget(queueList);
    }

    private void rebuildList()
    {
        removeWidget(queueList);
        queueList = new QueueList(minecraft, this.width - 40, this.height, 32, this.height - 30, 24);
        queueList.setLeftPos(20);
        addWidget(queueList);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        // 不绘制默认泥土背景
        // renderBackground(g);
        int cx = this.width / 2;
        MusicPlayer mp = MusicPlayer.getInstance();
        List<NeteaseSong> queue = mp.getQueue();
        String title = "播放队列 (" + queue.size() + " 首)";
        if (mp.getQueueIndex() >= 0 && !queue.isEmpty()) title += "  ·  当前 " + (mp.getQueueIndex() + 1);
        g.drawCenteredString(this.font, Component.literal(title), cx, 8, 0xFFFFFFFF);
        if (!statusMessage.isEmpty())
            g.drawCenteredString(this.font, Component.literal(statusMessage), cx, 22, statusColor);
        if (queue.isEmpty())
        {
            g.drawCenteredString(this.font, Component.literal("播放队列为空"), cx, this.height / 2 - 10, 0xFFAAAAAA);
            g.drawCenteredString(this.font, Component.literal("从歌单或搜索中添加歌曲"), cx, this.height / 2 + 6, 0xFF888888);
        }
        else
        {
            queueList.render(g, mouseX, mouseY, partialTick);
        }
        g.drawCenteredString(this.font, Component.literal("Enter播放 · Del移除 · N下一首 · C清空"), cx, this.height - 22, 0xFF888888);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        QueueList.Entry selected = (queueList != null) ? queueList.getSelected() : null;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
        {
            if (selected != null) { MusicPlayer.getInstance().jumpTo(selected.index); statusMessage = "正在播放: " + selected.song.name; statusColor = 0xFF00FFFF; }
            return true;
        }
        else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE)
        {
            if (selected != null) { String name = selected.song.name; int idx = selected.index; MusicPlayer.getInstance().removeFromQueue(idx); statusMessage = "已移除: " + name; statusColor = 0xFFFFAA00; rebuildList(); }
            return true;
        }
        else if (keyCode == GLFW.GLFW_KEY_N)
        {
            if (selected != null) { MusicPlayer mp = MusicPlayer.getInstance(); mp.playNext(selected.song); statusMessage = "下一首: " + selected.song.name; statusColor = 0xFF00FFFF; rebuildList(); }
            return true;
        }
        else if (keyCode == GLFW.GLFW_KEY_C) { clearQueue(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void clearQueue() { MusicPlayer.getInstance().clearQueue(); statusMessage = "队列已清空"; statusColor = 0xFFFF5555; rebuildList(); }

    @Override public boolean isPauseScreen() { return false; }

    class QueueList extends ObjectSelectionList<QueueList.Entry>
    {

        public QueueList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight)
        {
            super(mc, width, height, y0, y1, itemHeight);
            setLeftPos(20);
            List<NeteaseSong> queue = MusicPlayer.getInstance().getQueue();
            for (int i = 0; i < queue.size(); i++) addEntry(new Entry(queue.get(i), i));
        }

        class Entry extends ObjectSelectionList.Entry<Entry>
        {
            final NeteaseSong song;
            final int index;
            long lastClickTime = 0;
            Entry(NeteaseSong song, int index) { this.song = song; this.index = index; }

            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovering, float partialTick)
            {
                MusicPlayer mp = MusicPlayer.getInstance();
                boolean isCurrent = (index == mp.getQueueIndex());
                boolean isPlaying = isCurrent && mp.isPlaying() && !mp.isPaused();
                boolean isSelected = (QueueList.this.getSelected() == this);
                if (isSelected) ModernUI.fillRound(g, left, top, width, height, 4, 0x3000FFFF);
                if (isCurrent) { ModernUI.fillRound(g, left, top, width, height, 4, 0x40000000); g.fill(left, top + 2, left + 3, top + height - 2, 0xFF00FFFF); }
                else if (hovering) ModernUI.fillRound(g, left, top, width, height, 4, 0x20FFFFFF);
                int textLeft = left + 10;
                String prefix = isCurrent ? (isPlaying ? "▶" : "⏸") : String.valueOf(index + 1);
                int prefixColor = isCurrent ? 0xFF00FFFF : 0xFF888888;
                g.drawString(QueueScreen.this.font, Component.literal(prefix), textLeft, top + 4, prefixColor, false);
                String title = song.name; if (title.length() > 35) title = title.substring(0, 34) + "…";
                g.drawString(QueueScreen.this.font, Component.literal(title), textLeft + 24, top + 4, isCurrent ? 0xFFFFFFFF : 0xFFDDDDDD, false);
                String meta = song.getDisplayArtist() + " · " + song.getDisplayDuration(); if (meta.length() > 50) meta = meta.substring(0, 49) + "…";
                g.drawString(QueueScreen.this.font, Component.literal(meta), textLeft + 24, top + 15, 0xFF888888, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button)
            {
                QueueList.this.setSelected(this);
                if (button == 1) { String name = song.name; MusicPlayer.getInstance().removeFromQueue(index); statusMessage = "已移除: " + name; statusColor = 0xFFFFAA00; rebuildList(); return true; }
                long now = System.currentTimeMillis();
                if (now - lastClickTime < 400) { MusicPlayer.getInstance().jumpTo(index); statusMessage = "正在播放: " + song.name; statusColor = 0xFF00FFFF; }
                lastClickTime = now;
                return true;
            }

            @Override public Component getNarration() { return Component.literal(song.name); }
        }
    }
}
