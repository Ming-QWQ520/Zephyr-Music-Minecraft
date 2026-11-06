package com.zephyr.music.api;

/**
 * 网易云歌单
 */
public class NeteasePlaylist
{
    public long id;
    public String name = "";
    public String coverImgUrl = "";
    public int trackCount;
    public long playCount;
    public String creatorName = "";
    /** 副标题（排行榜=更新频率，推荐歌单=文案），借鉴 DangoMusic 网格卡片第二行 */
    public String subtitle = "";

    @Override
    public String toString()
    {
        return name + " (" + trackCount + " tracks)";
    }
}
