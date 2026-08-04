package com.zephyr.music.api;

/**
 * 单行歌词
 */
public class LyricLine
{
    public final double time; // 秒
    public final String text;

    public LyricLine(double time, String text)
    {
        this.time = time;
        this.text = text == null ? "" : text;
    }
}
