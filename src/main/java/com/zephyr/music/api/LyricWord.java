package com.zephyr.music.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐字歌词 - yrc 格式
 *
 * yrc 行格式: [行开始ms,行总时长ms](字开始ms,字时长ms,0)字(字开始ms,字时长ms,0)字...
 * 例: [2230,5100](2230,460,0)做(2690,450,0)人(3140,470,0)民(3610,450,0)的(4060,490,0)...
 */
public class LyricWord
{
    /** 该字开始时间（秒） */
    public final double startTime;
    /** 该字持续时间（秒） */
    public final double duration;
    /** 字文本 */
    public final String text;

    public LyricWord(double startTime, double duration, String text)
    {
        this.startTime = startTime;
        this.duration = duration;
        this.text = text == null ? "" : text;
    }

    /** 该字结束时间（秒） */
    public double getEndTime()
    {
        return startTime + duration;
    }

    /** 该字是否正在播放（当前时间在 [startTime, endTime] 区间内） */
    public boolean isPlayingAt(double posSec)
    {
        return posSec >= startTime && posSec < getEndTime();
    }

    /** 该字是否已经唱完 */
    public boolean isFinished(double posSec)
    {
        return posSec >= getEndTime();
    }

    /** 该字已经唱部分的进度 0~1 */
    public double getProgress(double posSec)
    {
        if (posSec <= startTime) return 0;
        if (posSec >= getEndTime()) return 1;
        return (posSec - startTime) / duration;
    }
}
