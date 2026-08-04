package com.zephyr.music.client.audio;

import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.config.ZephyrConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网易云听歌打卡管理器 - 移植自 Zephyr Music 参考项目
 *
 * 职责分离（两套独立的网易云后端系统）：
 *
 * 1. scrobbleToRecent() → /scrobble（非加密 eapi）
 *    - startplay → 最近播放
 *    - play → 听歌排行计数
 *    时机：新歌开始播放后 ~2.5s（让 accumulatedTime >= 1s）
 *    效果：新歌立即出现在「最近播放」列表
 *
 * 2. doScrobble() → /scrobble/v1（NCBL 加密 clientlog PLV/PLD）
 *    - 听歌足迹实际听歌时长
 *    时机：切歌/播放完毕时，对上一首歌上报实际播放秒数
 *    - isAutoNext=true（自动播放完）：上报完整时长（duration）
 *    - isAutoNext=false（手动切歌）：上报实际播放时长
 *
 * 精确跟踪每首歌的实际播放时长：
 * - 手动切歌：上报已播放时长
 * - 播放完自动切歌：上报完整时长
 * - 暂停后切歌：上报暂停前的播放时长
 * - 跳转进度：按实际播放位置计算（seek 不会增加额外时长）
 * - 刚切歌就切下一首：不上报（playTime=0）
 */
public class ScrobbleManager
{
    private static ScrobbleManager instance;

    /** 当前歌曲的打卡信息 */
    private static class ScrobbleInfo
    {
        long neteaseId;
        String name;
        String artist;
        double duration; // 秒
        long sourceid;
        /** 本首歌已累计的真实播放秒数 */
        double accumulatedTime;
        /** 上次记录时间戳（用于计算增量） */
        long lastTickMs;
        /** 是否正在播放（用于暂停时停止累计） */
        boolean ticking;
        /** 是否已调用 /scrobble 记录到最近播放 */
        boolean scrobbledToRecent;
    }

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScrobbleInfo> currentInfo = new AtomicReference<>();
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> recentScrobbleFuture;

    /** 上次上报的播放位置（用于增量计算） */
    private double lastReportedPosition = 0;

    private ScrobbleManager()
    {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ZephyrMusic-Scrobble");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized ScrobbleManager getInstance()
    {
        if (instance == null) instance = new ScrobbleManager();
        return instance;
    }

    /**
     * 初始化新歌曲的打卡信息（切歌时调用）
     * @param song 新歌曲
     * @param sourcePlaylistId 来源歌单 ID（用于打卡来源标识）
     */
    public void initSong(NeteaseSong song, long sourcePlaylistId)
    {
        // 先对上一首歌做手动切歌打卡（如果还在 ticking）
        ScrobbleInfo prev = currentInfo.get();
        if (prev != null)
        {
            doScrobble(prev, false); // false = 手动切歌
        }

        if (song == null)
        {
            currentInfo.set(null);
            stopTick();
            return;
        }

        ScrobbleInfo info = new ScrobbleInfo();
        info.neteaseId = song.id;
        info.name = song.name;
        info.artist = song.getDisplayArtist();
        info.duration = song.duration > 0 ? song.duration / 1000.0 : 0;
        info.sourceid = sourcePlaylistId > 0 ? sourcePlaylistId : song.id;
        info.accumulatedTime = 0;
        info.lastTickMs = 0;
        info.ticking = false;
        info.scrobbledToRecent = false;
        currentInfo.set(info);

        lastReportedPosition = 0;
        ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] init: id={} name={} duration={}s sourceid={}",
                info.neteaseId, info.name, info.duration, info.sourceid);
    }

    /**
     * 开始计时（播放时调用）
     */
    public void startTicking()
    {
        ScrobbleInfo info = currentInfo.get();
        if (info == null || info.ticking) return;
        info.ticking = true;
        info.lastTickMs = System.currentTimeMillis();
        scheduleRecentScrobble(info);
        startTick();
    }

    /**
     * 停止计时并累计（暂停/切歌时调用）
     */
    public void stopTicking()
    {
        ScrobbleInfo info = currentInfo.get();
        if (info == null || !info.ticking) return;
        info.ticking = false;
        if (info.lastTickMs > 0)
        {
            long delta = (System.currentTimeMillis() - info.lastTickMs);
            double deltaSec = delta / 1000.0;
            // 限制单次增量不超过 5 秒（防止 sleep/卡顿导致异常增量）
            if (deltaSec > 0 && deltaSec < 5)
            {
                info.accumulatedTime += deltaSec;
            }
            info.lastTickMs = 0;
        }
    }

    /**
     * 播放完毕自动切歌时调用
     */
    public void onSongEnded()
    {
        ScrobbleInfo info = currentInfo.get();
        if (info == null) return;
        doScrobble(info, true); // true = 自动播放完毕
        currentInfo.set(null);
        stopTick();
    }

    /**
     * 手动切歌时调用（对上一首歌打卡）
     */
    public void onManualSkip()
    {
        ScrobbleInfo info = currentInfo.get();
        if (info == null) return;
        doScrobble(info, false); // false = 手动切歌
        currentInfo.set(null);
        stopTick();
    }

    /**
     * 上报听歌时长：调用 /scrobble/v1（NCBL clientlog PLV/PLD）
     * @param info 歌曲打卡信息
     * @param isAutoNext true=自动播放完毕，false=手动切歌
     */
    private void doScrobble(ScrobbleInfo info, boolean isAutoNext)
    {
        if (!ZephyrConfig.SCROBBLE_ENABLED.get()) return;

        // 停止计时
        stopTickingInternal(info);

        long playTime = (long) Math.floor(info.accumulatedTime);
        long reportTime = isAutoNext
                ? (long) Math.floor(info.duration > 0 ? info.duration : playTime)
                : playTime;

        if (reportTime <= 0)
        {
            ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] skipped (reportTime=0) id={} name={} isAutoNext={} playTime={} dur={}",
                    info.neteaseId, info.name, isAutoNext, playTime, info.duration);
            return;
        }

        ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] doScrobble (duration) id={} name={} isAutoNext={} reportTime={} accumulated={}",
                info.neteaseId, info.name, isAutoNext, reportTime, info.accumulatedTime);

        // 调用 /scrobble/v1（NCBL 加密 clientlog）→ 听歌足迹时长
        NeteaseSession.getInstance().getApi().scrobbleV1(
                info.neteaseId,
                reportTime,
                info.sourceid,
                info.name,
                info.artist,
                (long) info.duration,
                isAutoNext
        ).thenAccept(resp -> {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] scrobbleV1 (duration) ok id={} code={} time={}",
                    info.neteaseId, code, reportTime);
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.warn("[Zephyr-Scrobble] scrobbleV1 (duration) failed: {}", e.getMessage());
            return null;
        });
    }

    /**
     * 记录到最近播放：调用 /scrobble（非加密 eapi）
     * 在新歌开始播放后尽快调用一次，使新歌立即出现在「最近播放」列表
     */
    private void scrobbleToRecent(ScrobbleInfo info)
    {
        if (!ZephyrConfig.SCROBBLE_ENABLED.get()) return;
        if (info == null || info.scrobbledToRecent) return;
        if (info.accumulatedTime < 1) return; // 累积到 >=1 秒再上报

        info.scrobbledToRecent = true;
        long sourceid = info.sourceid > 0 ? info.sourceid : info.neteaseId;
        long reportTime = Math.max(1, (long) Math.floor(info.accumulatedTime));

        ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] scrobbleToRecent (startplay) id={} name={} time={} sourceid={}",
                info.neteaseId, info.name, reportTime, sourceid);

        NeteaseSession.getInstance().getApi().scrobble(
                info.neteaseId, sourceid, (int) reportTime
        ).thenAccept(resp -> {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            ZephyrMusic.LOGGER.info("[Zephyr-Scrobble] scrobble to recent ok id={} code={} time={}",
                    info.neteaseId, code, reportTime);
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.warn("[Zephyr-Scrobble] scrobble to recent failed: {}", e.getMessage());
            // 失败了允许重试
            info.scrobbledToRecent = false;
            return null;
        });
    }

    /**
     * 安排延迟 2.5s 后的最近播放打卡
     */
    private void scheduleRecentScrobble(ScrobbleInfo info)
    {
        if (info.scrobbledToRecent) return;
        if (recentScrobbleFuture != null)
        {
            recentScrobbleFuture.cancel(false);
        }
        recentScrobbleFuture = scheduler.schedule(() -> {
            ScrobbleInfo cur = currentInfo.get();
            if (cur == null || cur.neteaseId != info.neteaseId || !cur.ticking) return;
            scrobbleToRecent(cur);
        }, 2500, TimeUnit.MILLISECONDS);
    }

    /**
     * 启动每秒 tick：用播放位置增量累计 accumulatedTime
     */
    private void startTick()
    {
        stopTick();
        tickFuture = scheduler.scheduleAtFixedRate(() -> {
            ScrobbleInfo info = currentInfo.get();
            if (info == null || !info.ticking) return;

            // 从 MusicPlayer 获取当前播放位置
            double ct = MusicPlayer.getInstance().getPositionSec();
            if (ct >= 0 && Double.isFinite(ct))
            {
                // 增量方式累计：如果位置前进了，增加差值
                // 如果位置倒退了（seek），不减少已累计时长
                if (ct > lastReportedPosition && ct - lastReportedPosition < 5)
                {
                    info.accumulatedTime += (ct - lastReportedPosition);
                }
                lastReportedPosition = ct;
                info.lastTickMs = System.currentTimeMillis();

                // 播放开始后，记录到网易云最近播放（仅一次）
                scrobbleToRecent(info);
            }
        }, 1000, 1000, TimeUnit.MILLISECONDS); // 每秒一次
    }

    private void stopTick()
    {
        if (tickFuture != null)
        {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (recentScrobbleFuture != null)
        {
            recentScrobbleFuture.cancel(false);
            recentScrobbleFuture = null;
        }
    }

    private void stopTickingInternal(ScrobbleInfo info)
    {
        if (info == null || !info.ticking) return;
        info.ticking = false;
        if (info.lastTickMs > 0)
        {
            long delta = System.currentTimeMillis() - info.lastTickMs;
            double deltaSec = delta / 1000.0;
            if (deltaSec > 0 && deltaSec < 5)
            {
                info.accumulatedTime += deltaSec;
            }
            info.lastTickMs = 0;
        }
    }

    public void shutdown()
    {
        stopTick();
        // 退出时对当前歌曲做一次打卡
        ScrobbleInfo info = currentInfo.get();
        if (info != null)
        {
            doScrobble(info, false);
        }
        scheduler.shutdownNow();
    }
}
