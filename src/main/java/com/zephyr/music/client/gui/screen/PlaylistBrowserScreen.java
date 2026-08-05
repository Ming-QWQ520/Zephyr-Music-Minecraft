package com.zephyr.music.client.gui.screen;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteasePlaylist;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.client.audio.MusicPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌单浏览器 - 左侧显示歌单列表，点击进入显示歌曲列表
 */
public class PlaylistBrowserScreen extends Screen
{
    private enum ViewState { LIST_PLAYLISTS, LIST_SONGS, LOADING }

    private ViewState state = ViewState.LOADING;
    private List<NeteasePlaylist> playlists = new ArrayList<>();
    private List<NeteaseSong> songs = new ArrayList<>();
    private NeteasePlaylist currentPlaylist;
    private String loadingMessage = "加载中…";

    private PlaylistList playlistListWidget;
    private SongList songListWidget;
    private boolean initedOnce = false;

    public PlaylistBrowserScreen()
    {
        super(Component.literal("Zephyr Music · 我的歌单"));
    }

    @Override
    protected void init()
    {
        initedOnce = true;
        // 顶部按钮（紧凑）
        addRenderableWidget(Button.builder(Component.literal("✕"), b -> onClose())
                .bounds(this.width - 22, 6, 14, 16).build());
        addRenderableWidget(Button.builder(Component.literal("⚙"), b -> minecraft.setScreen(new SettingsScreen()))
                .bounds(this.width - 116, 6, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal("账号"), b -> minecraft.setScreen(new LoginScreen()))
                .bounds(this.width - 162, 6, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("搜索"), b -> minecraft.setScreen(new SearchScreen()))
                .bounds(this.width - 208, 6, 40, 16).build());
        addRenderableWidget(Button.builder(Component.literal("播放器"), b -> minecraft.setScreen(new PlayerScreen()))
                .bounds(this.width - 256, 6, 42, 16).build());
        addRenderableWidget(Button.builder(Component.literal("⟳"), b -> refreshPlaylists())
                .bounds(this.width - 274, 6, 14, 16).build());

        if (state == ViewState.LIST_SONGS)
        {
            addRenderableWidget(Button.builder(Component.literal("← 返回歌单"), b -> {
                state = ViewState.LIST_PLAYLISTS;
                songs.clear();
                currentPlaylist = null;
                playlistListWidget = null;
                songListWidget = null;
                clearWidgets();
                init();
            }).bounds(10, 10, 100, 20).build());
        }

        // 创建列表 widget
        if (state == ViewState.LIST_PLAYLISTS)
        {
            playlistListWidget = new PlaylistList(this.minecraft, this.width - 80,
                    this.height - 80, 40, this.height - 40, 36);
            addWidget(playlistListWidget);
        }
        else if (state == ViewState.LIST_SONGS)
        {
            songListWidget = new SongList(this.minecraft, this.width - 80,
                    this.height - 80, 40, this.height - 40, 24);
            addWidget(songListWidget);
        }

        // 启动时的加载逻辑
        if (state == ViewState.LOADING && !initedOnce)
        {
            // 触发登录态检查
            if (!NeteaseSession.getInstance().isLoggedIn())
            {
                NeteaseSession.getInstance().checkLoginStatus().thenAccept(ok -> {
                    if (ok)
                    {
                        refreshPlaylists();
                    }
                    else
                    {
                        loadingMessage = "未登录，请先点击右上角[账号/登录]";
                        state = ViewState.LIST_PLAYLISTS;
                        playlists = Collections.emptyList();
                        minecraft.execute(() -> { clearWidgets(); init(); });
                    }
                });
            }
            else
            {
                refreshPlaylists();
            }
        }
    }

    private void refreshPlaylists()
    {
        state = ViewState.LOADING;
        loadingMessage = "正在加载歌单…";
        NeteaseSession.getInstance().fetchUserPlaylists().thenAccept(list -> {
            playlists = list;
            state = ViewState.LIST_PLAYLISTS;
            minecraft.execute(() -> {
                playlistListWidget = null;
                songListWidget = null;
                clearWidgets();
                init();
            });
        });
    }

    private void openPlaylist(NeteasePlaylist p)
    {
        state = ViewState.LOADING;
        loadingMessage = "加载歌单 [" + p.name + "] 中的歌曲…";
        currentPlaylist = p;
        songs = new ArrayList<>();
        NeteaseSession.getInstance().getApi().playlistTrackAll(p.id, 300, 0)
                .thenAccept(resp -> {
                    List<NeteaseSong> list = NeteaseApi.parseSongs(resp, "songs");
                    if (list.isEmpty())
                    {
                        if (resp.has("playlist") && resp.get("playlist").isJsonObject())
                        {
                            JsonObject pl = resp.getAsJsonObject("playlist");
                            if (pl.has("tracks") && pl.get("tracks").isJsonArray())
                            {
                                list = NeteaseApi.parseSongs(pl, "tracks");
                            }
                        }
                    }
                    songs = list;
                    state = ViewState.LIST_SONGS;
                    ZephyrMusic.LOGGER.info("[Zephyr] Loaded {} songs from playlist {}", songs.size(), p.name);
                    minecraft.execute(() -> {
                        playlistListWidget = null;
                        songListWidget = null;
                        clearWidgets();
                        init();
                    });
                });
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        renderBackground(g);

        int cx = this.width / 2;

        if (state == ViewState.LOADING)
        {
            g.drawCenteredString(this.font, Component.literal(loadingMessage),
                    cx, this.height / 2, 0xFF1DB954);
        }
        else if (state == ViewState.LIST_PLAYLISTS)
        {
            g.drawCenteredString(this.font, Component.literal("我的歌单 (" + playlists.size() + ")"),
                    cx, 14, 0xFFFFFFFF);

            if (playlistListWidget != null)
            {
                playlistListWidget.render(g, mouseX, mouseY, partialTick);
            }

            if (playlists.isEmpty())
            {
                String msg = NeteaseSession.getInstance().isLoggedIn()
                        ? "没有歌单或加载失败，点击右上角⟳刷新" : "未登录，请先点击右上角 [账号/登录]";
                g.drawCenteredString(this.font, Component.literal(msg),
                        cx, this.height / 2, 0xFFAAAAAA);
            }
        }
        else if (state == ViewState.LIST_SONGS)
        {
            if (currentPlaylist != null)
            {
                g.drawCenteredString(this.font,
                        Component.literal(currentPlaylist.name + " (" + songs.size() + " 首)"),
                        cx, 14, 0xFF1DB954);
            }

            if (songListWidget != null)
            {
                songListWidget.render(g, mouseX, mouseY, partialTick);
            }

            if (songs.isEmpty())
            {
                g.drawCenteredString(this.font, Component.literal("歌单为空或加载失败"),
                        cx, this.height / 2, 0xFFAAAAAA);
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void playSongAt(int index)
    {
        if (index < 0 || index >= songs.size()) return;
        MusicPlayer mp = MusicPlayer.getInstance();
        mp.setQueue(songs, index);
        // 设置来源歌单 ID（用于打卡）
        if (currentPlaylist != null) mp.setCurrentSourcePlaylistId(currentPlaylist.id);
        mp.playSong(songs.get(index));
    }

    /** 把指定索引的歌曲追加到播放队列尾部 */
    private void appendToQueueAt(int index)
    {
        if (index < 0 || index >= songs.size()) return;
        NeteaseSong s = songs.get(index);
        MusicPlayer mp = MusicPlayer.getInstance();
        List<NeteaseSong> q = new ArrayList<>(mp.getQueue());
        boolean exists = false;
        for (NeteaseSong q1 : q) { if (q1.id == s.id) { exists = true; break; } }
        if (!exists)
        {
            q.add(s);
            mp.setQueue(q, mp.getQueueIndex());
        }
    }

    /** 歌单列表 */
    class PlaylistList extends ObjectSelectionList<PlaylistList.Entry>
    {
        public PlaylistList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight)
        {
            super(mc, width, height, y0, y1, itemHeight);
            setLeftPos(40);
            for (NeteasePlaylist p : playlists)
            {
                addEntry(new Entry(p));
            }
        }

        class Entry extends ObjectSelectionList.Entry<Entry>
        {
            final NeteasePlaylist playlist;

            Entry(NeteasePlaylist p)
            {
                this.playlist = p;
            }

            @Override
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick)
            {
                String name = playlist.name;
                if (name.length() > 30) name = name.substring(0, 29) + "…";
                g.drawString(PlaylistBrowserScreen.this.font, Component.literal(name),
                        left + 4, top + 4, 0xFFFFFFFF, false);
                String meta = (playlist.trackCount > 0 ? playlist.trackCount + " 首" : "")
                        + (playlist.creatorName != null && !playlist.creatorName.isEmpty() ? " · " + playlist.creatorName : "");
                g.drawString(PlaylistBrowserScreen.this.font, Component.literal(meta),
                        left + 4, top + 18, 0xFFAAAAAA, false);
                if (hovering)
                {
                    g.fill(left, top, left + width, top + height, 0x40FFFFFF);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button)
            {
                openPlaylist(playlist);
                return true;
            }

            @Override
            public Component getNarration()
            {
                return Component.literal(playlist.name);
            }
        }
    }

    /** 歌曲列表 */
    class SongList extends ObjectSelectionList<SongList.Entry>
    {
        public SongList(Minecraft mc, int width, int height, int y0, int y1, int itemHeight)
        {
            super(mc, width, height, y0, y1, itemHeight);
            setLeftPos(40);
            for (int i = 0; i < songs.size(); i++)
            {
                addEntry(new Entry(songs.get(i), i));
            }
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
            public void render(GuiGraphics g, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick)
            {
                String title = (this.index + 1) + ". " + song.name;
                if (title.length() > 50) title = title.substring(0, 49) + "…";
                g.drawString(PlaylistBrowserScreen.this.font, Component.literal(title),
                        left + 4, top + 3, 0xFFFFFFFF, false);
                String artist = song.getDisplayArtist() + " · " + song.getDisplayDuration();
                if (artist.length() > 60) artist = artist.substring(0, 59) + "…";
                g.drawString(PlaylistBrowserScreen.this.font, Component.literal(artist),
                        left + 4, top + 14, 0xFFAAAAAA, false);
                if (hovering)
                {
                    g.fill(left, top, left + width, top + height, 0x40FFFFFF);
                }
                // 显示正在播放标记
                NeteaseSong cur = MusicPlayer.getInstance().getCurrentSong();
                if (cur != null && cur.id == song.id)
                {
                    g.drawString(PlaylistBrowserScreen.this.font, Component.literal("▶"),
                            left - 12, top + 3, 0xFF1DB954, false);
                }
                // 在队列中标记
                for (NeteaseSong q : MusicPlayer.getInstance().getQueue())
                {
                    if (q.id == song.id)
                    {
                        g.drawString(PlaylistBrowserScreen.this.font, Component.literal("❉"),
                                left + width - 12, top + 3, 0xFFFFFF55, false);
                        break;
                    }
                }
                // 提示文字
                if (hovering)
                {
                    g.drawString(PlaylistBrowserScreen.this.font, Component.literal("单击播放 · 双击加入队列 · 右键加入队列"),
                            left + width - 220, top + 3, 0xFF888888, false);
                }
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button)
            {
                SongList.this.setSelected(this);
                if (button == 1)
                {
                    // 右键加入队列
                    appendToQueueAt(this.index);
                    return true;
                }
                long now = System.currentTimeMillis();
                if (now - lastClickTime < 400)
                {
                    // 双击
                    appendToQueueAt(this.index);
                }
                else
                {
                    // 单击播放
                    playSongAt(this.index);
                }
                lastClickTime = now;
                return true;
            }

            @Override
            public Component getNarration()
            {
                return Component.literal(song.name);
            }
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
