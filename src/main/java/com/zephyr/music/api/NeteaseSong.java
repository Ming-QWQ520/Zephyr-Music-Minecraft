package com.zephyr.music.api;

/**
 * 网易云歌曲
 */
public class NeteaseSong
{
    public long id;
    public String name = "";
    public String artist = "";
    public String album = "";
    public long duration; // 毫秒
    public String picUrl = "";

    public String getDisplayArtist()
    {
        return artist == null || artist.isEmpty() ? "未知艺术家" : artist;
    }

    public String getDisplayDuration()
    {
        long totalSec = duration / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }

    @Override
    public String toString()
    {
        return name + " - " + artist;
    }
}
