package com.zephyr.music.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 歌词行 - 支持普通行（lrc）和逐字行（yrc）
 */
public class LyricLine
{
    /** 行开始时间（秒） */
    public final double time;
    /** 行文本（整行） */
    public final String text;
    /** 该行包含的字（逐字歌词时非空，普通 lrc 为空列表） */
    public final List<LyricWord> words;
    /** 是否为逐字歌词 */
    public final boolean isYrc;

    public LyricLine(double time, String text)
    {
        this.time = time;
        this.text = text == null ? "" : text;
        this.words = Collections.emptyList();
        this.isYrc = false;
    }

    public LyricLine(double time, String text, List<LyricWord> words)
    {
        this.time = time;
        this.text = text == null ? "" : text;
        this.words = words == null ? Collections.emptyList() : words;
        this.isYrc = !this.words.isEmpty();
    }
}
