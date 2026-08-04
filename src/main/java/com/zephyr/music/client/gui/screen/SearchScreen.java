package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.MusicPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 搜索界面 - 关键词搜索歌曲
 *  - 搜索结果列表
 *  - 单击：播放当前歌曲
 *  - 双击 / 点击 [+]: 加入播放队列
 */
public class SearchScreen extends Screen
{
    private EditBox keywordField;
    private SearchList resultList;
    private final List<NeteaseSong> results = new ArrayList<>();
    private String statusMessage = "输入关键词后按 [搜索]";
    private int statusColor = 0xFFAAAAAA;

    public SearchScreen()
    {
        super(Component.literal("Zephyr Music · 搜索"));
    }

    @Override
    protected void init()
    {
        int cx = this.width / 2;
        keywordField = new EditBox(this.font, cx - 180, 30, 280, 18, Component.literal("关键词"));
        keywordField.setMaxLength(64);
        keywordField.setHint(Component.literal("歌曲/歌手/专辑名"));
        addRenderableWidget(keywordField);

        addRenderableWidget(Button.builder(Component.literal("搜 索"), b -> doSearch())
                .bounds(cx + 105, 28, 60, 20).build());

        addRenderableWidget(Button.builder(Component.literal("+ 加入队列"), b -> addToQueue())
                .bounds(cx + 170, 28, 90, 20).build());

        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(this.width - 70, 6, 60, 18).build());

        addRenderableWidget(Button.builder(Component.literal("播放器"), b -> minecraft.setScreen(new PlayerScreen()))
                .bounds(this.width - 140, 6, 60, 18).build());

        addRenderableWidget(Button.builder(Component.literal("我的歌单"), b -> minecraft.setScreen(new PlaylistBrowserScreen()))
                .bounds(this.width - 220, 6, 70, 18).build());

        resultList = new SearchList(minecraft, this.width - 40, this.height - 100, 60, this.height - 40, 22);
        resultList.setLeftPos(20);
        addWidget(resultList);

        // 默认聚焦输入框
        setInitialFocus(keywordField);
    }

    private void doSearch()
    {
        String kw = keywordField.getValue().trim();
        if (kw.isEmpty())
        {
            statusMessage = "请输入关键词";
            statusColor = 0xFFFF5555;
            return;
        }
        statusMessage = "正在搜索: " + kw;
        statusColor = 0xFFFFFFFF;
        results.clear();
        NeteaseSession.getInstance().getApi().search(kw, 50, 0)
                .thenAccept(resp -> {
                    List<NeteaseSong> songs = new ArrayList<>();
                    try
                    {
                        JsonObject result = resp.has("result") && resp.get("result").isJsonObject()
                                ? resp.getAsJsonObject("result") : resp;
                        if (result.has("songs") && result.get("songs").isJsonArray())
                        {
                            songs = NeteaseApi.parseSongs(result, "songs");
                        }
                    }
                    catch (Exception e)
                    {
                        ZephyrMusic.LOGGER.error("[Zephyr] search parse failed", e);
                    }
                    final List<NeteaseSong> finalSongs = songs;
                    Minecraft.getInstance().execute(() -> {
                        results.clear();
                        results.addAll(finalSongs);
                        // 重建列表
                        removeWidget(resultList);
                        resultList = new SearchList(minecraft, width - 40, height - 100, 60, height - 40, 22);
                        resultList.setLeftPos(20);
                        addWidget(resultList);
                        if (results.isEmpty())
                        {
                            statusMessage = "无搜索结果";
                            statusColor = 0xFFFF5555;
                        }
                        else
                        {
                            statusMessage = "找到 " + results.size() + " 首歌曲（单击播放，双击加入队列）";
                            statusColor = 0xFF1DB954;
                        }
                    });
                });
    }

    private void playSongAt(int index)
    {
        if (index < 0 || index >= results.size()) return;
        MusicPlayer.getInstance().setQueue(results, index);
        MusicPlayer.getInstance().playSong(results.get(index));
        statusMessage = "正在播放: " + results.get(index).name;
        statusColor = 0xFF1DB954;
    }

    private void addSongToQueueAt(int index)
    {
        if (index < 0 || index >= results.size()) return;
        NeteaseSong s = results.get(index);
        MusicPlayer mp = MusicPlayer.getInstance();
        List<NeteaseSong> q = new ArrayList<>(mp.getQueue());
        // 避免重复
        boolean exists = false;
        for (NeteaseSong q1 : q) { if (q1.id == s.id) { exists = true; break; } }
        if (!exists)
        {
            q.add(s);
            mp.setQueue(q, mp.getQueueIndex());
            statusMessage = "已加入队列: " + s.name;
            statusColor = 0xFF1DB954;
        }
        else
        {
            statusMessage = "已在队列中: " + s.name;
            statusColor = 0xFFFFFF55;
        }
    }

    /** 把当前选中的歌曲加入队列 */
    private void addToQueue()
    {
        if (resultList != null && resultList.getSelected() != null)
        {
            addSongToQueueAt(resultList.getSelected().index);
        }
        else
        {
            statusMessage = "请先在列表中选中一首歌";
            statusColor = 0xFFFF5555;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.getTitle(), cx, 8, 0xFFFFFFFF);
        g.drawCenteredString(this.font, Component.literal(statusMessage), cx, 52, statusColor);
        super.render(g, mouseX, mouseY, partialTick);
        // 队列大小提示
        MusicPlayer mp = MusicPlayer.getInstance();
        if (!mp.getQueue().isEmpty())
        {
            String qInfo = "队列: " + (mp.getQueueIndex() + 1) + "/" + mp.getQueue().size();
            g.drawString(this.font, Component.literal(qInfo), 20, 6, 0xFF1DB954, false);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** 搜索结果列表项 */
    class SearchList extends ObjectSelectionList<SearchList.Entry>
    {
        public SearchList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight)
        {
            super(mc, width, height, y0, y1, itemHeight);
            setLeftPos(20);
            for (int i = 0; i < results.size(); i++)
            {
                addEntry(new Entry(results.get(i), i));
            }
        }

        @Override
        public int getRowWidth()
        {
            return this.width - 12;
        }

        class Entry extends ObjectSelectionList.Entry<Entry>
        {
            final NeteaseSong song;
            final int index;
            long lastClickTime = 0;

            Entry(NeteaseSong song, int index)
            {
                this.song = song;
                this.index = index;
            }

            @Override
            public void render(GuiGraphics g, int idx, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick)
            {
                String title = (index + 1) + ". " + song.name;
                if (title.length() > 60) title = title.substring(0, 59) + "…";
                g.drawString(SearchScreen.this.font, Component.literal(title),
                        left + 4, top + 2, 0xFFFFFFFF, false);
                String meta = song.getDisplayArtist() + " · " + song.getDisplayDuration()
                        + (song.album != null && !song.album.isEmpty() ? " · " + song.album : "");
                if (meta.length() > 70) meta = meta.substring(0, 69) + "…";
                g.drawString(SearchScreen.this.font, Component.literal(meta),
                        left + 4, top + 12, 0xFFAAAAAA, false);
                if (hovering)
                {
                    g.fill(left, top, left + width, top + height, 0x40FFFFFF);
                }
                // 正在播放标记
                NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
                if (cur != null && cur.id == song.id)
                {
                    g.drawString(SearchScreen.this.font, Component.literal("▶"),
                            left - 12, top + 2, 0xFF1DB954, false);
                }
                // 在队列中标记
                for (NeteaseSong q : MusicPlayer.getInstance().getQueue())
                {
                    if (q.id == song.id)
                    {
                        g.drawString(SearchScreen.this.font, Component.literal("❉"),
                                left + width - 12, top + 2, 0xFFFFFF55, false);
                        break;
                    }
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button)
            {
                long now = System.currentTimeMillis();
                // 双击检测
                if (now - lastClickTime < 400)
                {
                    addSongToQueueAt(index);
                }
                else
                {
                    // 单击：选中后等待第二次点击，但同时也立即播放
                    playSongAt(index);
                }
                lastClickTime = now;
                SearchList.this.setSelected(this);
                return true;
            }

            @Override
            public Component getNarration()
            {
                return Component.literal(song.name);
            }
        }
    }
}
