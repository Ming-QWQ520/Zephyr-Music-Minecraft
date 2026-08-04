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

    @Override
    public String toString()
    {
        return name + " (" + trackCount + " tracks)";
    }
}
