package com.zephyr.music.client.audio;

import com.google.gson.JsonObject;
import com.zephyr.music.ZephyrMusic;
import com.zephyr.music.api.LyricLine;
import com.zephyr.music.api.NeteaseApi;
import com.zephyr.music.api.NeteaseSong;
import com.zephyr.music.api.NeteaseSession;
import com.zephyr.music.config.ZephyrConfig;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 音频播放引擎 - 基于 JLayer 解码 + 直接使用 SourceDataLine 播放
 *
 * ★ 关键修复（v4）：
 *   不再使用 JLayer 的 FactoryRegistry/JavaSoundAudioDevice（其内部 test() 会以
 *   22050Hz mono 测试播放，在不支持该格式的 audio mixer 上抛异常导致整个解码
 *   循环立即结束，没有声音也没有报错）。
 *   改为直接获取 SourceDataLine，用 MP3 解码后的实际格式（44100Hz stereo）打开。
 *
 * - 支持从 URL 流式播放 MP3
 * - 后台线程解码 + SourceDataLine 播放
 * - 暴露播放进度（用于歌词同步）
 */
public class MusicPlayer
{
    private static MusicPlayer instance;

    private final ExecutorService playExecutor;
    private final List<PlaybackListener> listeners = new CopyOnWriteArrayList<>();

    private volatile Thread playThread;
    private volatile Bitstream currentBitstream;
    private volatile Decoder currentDecoder;
    private volatile SourceDataLine currentLine;
    private volatile HttpURLConnection currentConnection;
    private volatile AudioFormat currentFormat;

    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final AtomicReference<NeteaseSong> currentSong = new AtomicReference<>();
    private final AtomicReference<List<LyricLine>> currentLyrics = new AtomicReference<>(Collections.emptyList());

    private volatile long playStartTimeMs = 0;
    private volatile double positionOffsetSec = 0;
    private volatile long lineStartPosUs = 0;
    private volatile double lastReturnedPos = 0;

    private final List<NeteaseSong> queue = new CopyOnWriteArrayList<>();
    private volatile int queueIndex = -1;
    private volatile boolean loopMode = false;

    private MusicPlayer()
    {
        this.playExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ZephyrMusic-Dispatcher");
            t.setDaemon(true);
            return t;
        });
    }

    public static synchronized MusicPlayer getInstance()
    {
        if (instance == null) instance = new MusicPlayer();
        return instance;
    }

    public interface PlaybackListener
    {
        default void onSongChanged(NeteaseSong song) {}
        default void onPlayStateChanged(boolean playing, boolean paused) {}
        default void onLyricsLoaded(List<LyricLine> lyrics) {}
        default void onError(String message) {}
    }

    public void addListener(PlaybackListener l)
    {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(PlaybackListener l)
    {
        listeners.remove(l);
    }

    public NeteaseSong getCurrentSong()
    {
        return currentSong.get();
    }

    public List<LyricLine> getCurrentLyrics()
    {
        return currentLyrics.get();
    }

    public boolean isPlaying()
    {
        return playing.get();
    }

    public boolean isPaused()
    {
        return paused.get();
    }

    /**
     * ★ 获取当前播放位置（秒）
     * 使用 SourceDataLine.getMicrosecondPosition() 获取实际音频播放位置
     * 暂停时位置冻结，seek 时通过 positionOffsetSec 调整
     */
    public double getPositionSec()
    {
        if (paused.get())
        {
            return positionOffsetSec;
        }
        SourceDataLine line = currentLine;
        if (line != null && playing.get())
        {
            try
            {
                long curPosUs = line.getMicrosecondPosition();
                double posSec = positionOffsetSec + (curPosUs - lineStartPosUs) / 1_000_000.0;
                // 钳制到总时长
                NeteaseSong song = currentSong.get();
                if (song != null && song.duration > 0)
                {
                    double total = song.duration / 1000.0;
                    if (posSec > total) posSec = total;
                }
                // 单调递增保护
                if (posSec < lastReturnedPos) return lastReturnedPos;
                lastReturnedPos = posSec;
                return posSec;
            }
            catch (Exception ignored) {}
        }
        return positionOffsetSec;
    }

    public float getVolume()
    {
        return ZephyrConfig.HUD_VOLUME.get().floatValue();
    }

    public void setVolume(float v)
    {
        ZephyrConfig.HUD_VOLUME.set((double) Math.max(0, Math.min(1, v)));
        applyVolumeToLine();
    }

    private void applyVolumeToLine()
    {
        try
        {
            SourceDataLine line = currentLine;
            if (line != null && line.isControlSupported(FloatControl.Type.MASTER_GAIN))
            {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                float vol = ZephyrConfig.HUD_VOLUME.get().floatValue();
                if (vol <= 0)
                {
                    gain.setValue(gain.getMinimum());
                }
                else
                {
                    float dB = 20f * (float) Math.log10(Math.max(0.01, vol));
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
                }
            }
        }
        catch (Exception ignored) {}
    }

    /**
     * 设置播放队列
     */
    public void setQueue(List<NeteaseSong> songs, int startIndex)
    {
        this.queue.clear();
        this.queue.addAll(songs);
        this.queueIndex = Math.max(0, Math.min(startIndex, songs.size() - 1));
    }

    public List<NeteaseSong> getQueue()
    {
        return queue;
    }

    public int getQueueIndex()
    {
        return queueIndex;
    }

    public boolean isLoopMode()
    {
        return loopMode;
    }

    public void setLoopMode(boolean loop)
    {
        this.loopMode = loop;
    }

    /**
     * 播放指定歌曲
     */
    public void playSong(NeteaseSong song)
    {
        // ★ 切歌时先对上一首歌做打卡（手动切歌）
        ScrobbleManager.getInstance().onManualSkip();
        stopInternal(false);
        playExecutor.submit(() -> {
            try
            {
                ZephyrMusic.LOGGER.info("[Zephyr] Requested: {} - {}", song.name, song.artist);
                currentSong.set(song);
                listeners.forEach(l -> l.onSongChanged(song));

                String level = ZephyrConfig.DEFAULT_QUALITY.get();
                if (level == null || level.isEmpty()) level = "exhigh";
                JsonObject urlResp = NeteaseSession.getInstance().getApi().songUrlV1(song.id, level).join();
                String url = NeteaseApi.extractSongUrl(urlResp);
                if (url == null || url.isEmpty())
                {
                    urlResp = NeteaseSession.getInstance().getApi().songUrl(song.id).join();
                    url = NeteaseApi.extractSongUrl(urlResp);
                }
                if (url == null || url.isEmpty())
                {
                    String msg = "无法获取播放 URL（可能无版权或需要 VIP）";
                    ZephyrMusic.LOGGER.warn("[Zephyr] {}", msg);
                    listeners.forEach(l -> l.onError(msg));
                    return;
                }

                ZephyrMusic.LOGGER.info("[Zephyr] Got URL: {}",
                        url.length() > 80 ? url.substring(0, 80) + "..." : url);

                // ★ 修复：歌词异步加载，不阻塞 startPlayback
                loadLyricsAsync(song.id);
                startPlayback(url);

                // ★ 初始化打卡信息（不再立即调用 scrobble，由 ScrobbleManager 管理）
                ScrobbleManager.getInstance().initSong(song, currentSourcePlaylistId);
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] playSong failed", e);
                listeners.forEach(l -> l.onError("播放失败: " + e.getMessage()));
            }
        });
    }

    /** 当前播放来源歌单 ID（用于打卡来源标识） */
    private long currentSourcePlaylistId = 0;

    public void setCurrentSourcePlaylistId(long playlistId)
    {
        this.currentSourcePlaylistId = playlistId;
    }

    /** 异步加载歌词，不阻塞 startPlayback */
    private void loadLyricsAsync(long songId)
    {
        NeteaseSession.getInstance().getApi().lyricNew(songId).thenAccept(resp -> {
            try
            {
                List<LyricLine> lyrics = Collections.emptyList();
                if (resp.has("yrc") && resp.getAsJsonObject("yrc").has("lyric")
                        && !resp.getAsJsonObject("yrc").get("lyric").isJsonNull())
                {
                    String yrc = resp.getAsJsonObject("yrc").get("lyric").getAsString();
                    lyrics = NeteaseApi.parseYrc(yrc);
                    if (lyrics.isEmpty())
                    {
                        String lrc = resp.has("lrc") && resp.getAsJsonObject("lrc").has("lyric")
                                && !resp.getAsJsonObject("lrc").get("lyric").isJsonNull()
                                ? resp.getAsJsonObject("lrc").get("lyric").getAsString() : "";
                        lyrics = NeteaseApi.parseLrc(lrc);
                    }
                }
                else if (resp.has("lrc") && resp.getAsJsonObject("lrc").has("lyric")
                        && !resp.getAsJsonObject("lrc").get("lyric").isJsonNull())
                {
                    String lrc = resp.getAsJsonObject("lrc").get("lyric").getAsString();
                    lyrics = NeteaseApi.parseLrc(lrc);
                }
                currentLyrics.set(lyrics);
                final List<LyricLine> loadedLyrics = lyrics;
                listeners.forEach(l -> l.onLyricsLoaded(loadedLyrics));
                int yrcCount = (int) lyrics.stream().filter(l -> l.isYrc).count();
                ZephyrMusic.LOGGER.info("[Zephyr] Lyrics loaded async: {} lines ({} yrc)", lyrics.size(), yrcCount);
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr] loadLyricsAsync parse failed: {}", e.getMessage());
                currentLyrics.set(Collections.emptyList());
            }
        }).exceptionally(e -> {
            ZephyrMusic.LOGGER.warn("[Zephyr] loadLyricsAsync failed: {}", e.getMessage());
            currentLyrics.set(Collections.emptyList());
            return null;
        });
    }

    @Deprecated
    private void loadLyrics(long songId)
    {
        try
        {
            JsonObject resp = NeteaseSession.getInstance().getApi().lyricNew(songId).join();
            List<LyricLine> lyrics = Collections.emptyList();
            if (resp.has("yrc") && resp.getAsJsonObject("yrc").has("lyric")
                    && !resp.getAsJsonObject("yrc").get("lyric").isJsonNull())
            {
                String yrc = resp.getAsJsonObject("yrc").get("lyric").getAsString();
                lyrics = NeteaseApi.parseYrc(yrc);
                if (lyrics.isEmpty())
                {
                    String lrc = resp.has("lrc") && resp.getAsJsonObject("lrc").has("lyric")
                            && !resp.getAsJsonObject("lrc").get("lyric").isJsonNull()
                            ? resp.getAsJsonObject("lrc").get("lyric").getAsString() : "";
                    lyrics = NeteaseApi.parseLrc(lrc);
                }
            }
            else if (resp.has("lrc") && resp.getAsJsonObject("lrc").has("lyric")
                    && !resp.getAsJsonObject("lrc").get("lyric").isJsonNull())
            {
                String lrc = resp.getAsJsonObject("lrc").get("lyric").getAsString();
                lyrics = NeteaseApi.parseLrc(lrc);
            }
            currentLyrics.set(lyrics);
            final List<LyricLine> loadedLyrics = lyrics;
            listeners.forEach(l -> l.onLyricsLoaded(loadedLyrics));
            ZephyrMusic.LOGGER.info("[Zephyr] Lyrics: {} lines", lyrics.size());
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] loadLyrics failed: {}", e.getMessage());
            currentLyrics.set(Collections.emptyList());
        }
    }

    private void startPlayback(String urlStr) throws Exception
    {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "ZephyrMusic-Mod/1.0");
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.connect();

        int code = conn.getResponseCode();
        ZephyrMusic.LOGGER.info("[Zephyr] Audio stream HTTP {} ({} bytes)",
                code, conn.getContentLength());
        if (code != 200)
        {
            throw new RuntimeException("HTTP " + code + " fetching audio stream");
        }

        InputStream is = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
        currentConnection = conn;
        currentBitstream = new Bitstream(is);
        currentDecoder = new Decoder();
        // ★ 不创建 AudioDevice，直接用 SourceDataLine（在第一帧解码后才知道实际格式）
        currentLine = null;
        currentFormat = null;

        stopped.set(false);
        paused.set(false);
        playing.set(true);
        playStartTimeMs = System.currentTimeMillis();
        positionOffsetSec = 0;
        lineStartPosUs = 0;
        lastReturnedPos = 0;

        playThread = new Thread(this::decodeLoop, "ZephyrMusic-Decode");
        playThread.setDaemon(true);
        // ★ 修复: 设置最高优先级，窗口失焦时不卡顿
        playThread.setPriority(Thread.MAX_PRIORITY);
        playThread.start();

        // ★ 启动打卡计时（播放开始）
        ScrobbleManager.getInstance().startTicking();

        listeners.forEach(l -> l.onPlayStateChanged(true, false));
    }

    /**
     * 在第一次解码得到 SampleBuffer 后，根据其频率和声道数创建 SourceDataLine
     */
    private SourceDataLine createSourceDataLine(int sampleRate, int channels) throws LineUnavailableException
    {
        AudioFormat fmt = new AudioFormat(
                sampleRate,
                16,
                channels,
                true,    // signed
                false    // little-endian
        );
        ZephyrMusic.LOGGER.info("[Zephyr] Creating SourceDataLine: {} Hz, {} ch", sampleRate, channels);

        // 列出所有可用 mixer（用于调试）
        try
        {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            ZephyrMusic.LOGGER.info("[Zephyr] Available mixers: {}", mixers.length);
            for (Mixer.Info mi : mixers)
            {
                Mixer m = AudioSystem.getMixer(mi);
                DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, fmt);
                boolean supported = m.isLineSupported(lineInfo);
                ZephyrMusic.LOGGER.info("[Zephyr]   - {} (supported: {})", mi.getName(), supported);
                if (supported)
                {
                    // 优先用第一个支持的 mixer
                    SourceDataLine line = (SourceDataLine) m.getLine(lineInfo);
                    line.open(fmt, 4096 * 4);  // 16KB buffer
                    line.start();
                    ZephyrMusic.LOGGER.info("[Zephyr] Opened SourceDataLine on mixer: {}, buffer={}",
                            mi.getName(), line.getBufferSize());
                    return line;
                }
            }
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] Mixer enumeration failed: {}", e.getMessage());
        }

        // Fallback：用默认 AudioSystem
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        if (!AudioSystem.isLineSupported(info))
        {
            ZephyrMusic.LOGGER.error("[Zephyr] Default audio system does NOT support format: {}", fmt);
            throw new LineUnavailableException("Format not supported: " + fmt);
        }
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(fmt, 4096 * 4);
        line.start();
        ZephyrMusic.LOGGER.info("[Zephyr] Opened default SourceDataLine, buffer={}", line.getBufferSize());
        return line;
    }

    private void decodeLoop()
    {
        int frameCount = 0;
        try
        {
            while (!stopped.get())
            {
                if (paused.get())
                {
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                    continue;
                }
                try
                {
                    Header frame = currentBitstream.readFrame();
                    if (frame == null)
                    {
                        ZephyrMusic.LOGGER.info("[Zephyr] Stream ended after {} frames", frameCount);
                        // ★ 自动播放完毕，触发打卡（上报完整时长）
                        ScrobbleManager.getInstance().onSongEnded();
                        boolean wasLast = (queueIndex >= queue.size() - 1);
                        if (loopMode || !wasLast)
                        {
                            playExecutor.submit(this::next);
                        }
                        break;
                    }
                    SampleBuffer buf = (SampleBuffer) currentDecoder.decodeFrame(frame, currentBitstream);
                    currentBitstream.closeFrame();
                    short[] samples = buf.getBuffer();
                    int len = buf.getBufferLength();

                    // 第一帧：创建 SourceDataLine（基于实际的采样率/声道数）
                    if (currentLine == null && len > 0)
                    {
                        int sr = buf.getSampleFrequency();
                        int ch = buf.getChannelCount();
                        ZephyrMusic.LOGGER.info("[Zephyr] First frame: {} Hz, {} ch, len={}", sr, ch, len);
                        try
                        {
                            currentLine = createSourceDataLine(sr, ch);
                            currentFormat = currentLine.getFormat();
                            // ★ 记录 SourceDataLine 起始位置用于准确追踪播放进度
                            lineStartPosUs = currentLine.getMicrosecondPosition();
                            positionOffsetSec = 0;
                            lastReturnedPos = 0;
                            applyVolumeToLine();
                        }
                        catch (LineUnavailableException e)
                        {
                            ZephyrMusic.LOGGER.error("[Zephyr] Cannot open SourceDataLine: {}", e.getMessage());
                            listeners.forEach(l -> l.onError("无法打开音频输出: " + e.getMessage()));
                            break;
                        }
                    }

                    if (len > 0 && currentLine != null)
                    {
                        // ★ 防越界：samples 数组实际长度可能 < len（JLayer SampleBuffer 复用 buffer）
                        int actualLen = Math.min(len, samples.length);
                        // short[] -> byte[] (little-endian 16-bit PCM)
                        byte[] bytes = shortsToBytesLE(samples, actualLen);
                        // SourceDataLine.write 阻塞直到 buffer 有空间
                        currentLine.write(bytes, 0, actualLen * 2);
                    }
                    frameCount++;
                }
                catch (JavaLayerException e)
                {
                    ZephyrMusic.LOGGER.warn("[Zephyr] decode frame error: {}", e.getMessage());
                    break;
                }
                catch (Exception e)
                {
                    ZephyrMusic.LOGGER.warn("[Zephyr] decodeLoop error: {}", e.getMessage());
                    break;
                }
            }
        }
        finally
        {
            ZephyrMusic.LOGGER.info("[Zephyr] decodeLoop ending ({} frames decoded)", frameCount);
            cleanupPlaybackResources();
        }
    }

    /** short[] -> byte[] (little-endian)，JDK 没有 Arrays.toString for short[] 这种 */
    private static byte[] shortsToBytesLE(short[] samples, int len)
    {
        byte[] b = new byte[len * 2];
        for (int i = 0; i < len; i++)
        {
            short s = samples[i];
            b[i * 2] = (byte) s;
            b[i * 2 + 1] = (byte) (s >> 8);
        }
        return b;
    }

    private void cleanupPlaybackResources()
    {
        try
        {
            SourceDataLine line = currentLine;
            if (line != null)
            {
                try { line.drain(); } catch (Exception ignored) {}
                try { line.stop(); } catch (Exception ignored) {}
                try { line.close(); } catch (Exception ignored) {}
                currentLine = null;
            }
            if (currentBitstream != null)
            {
                try { currentBitstream.close(); } catch (Exception ignored) {}
                currentBitstream = null;
            }
            if (currentConnection != null)
            {
                try { currentConnection.disconnect(); } catch (Exception ignored) {}
                currentConnection = null;
            }
            currentFormat = null;
        }
        catch (Exception ignored) {}
    }

    public void pause()
    {
        if (playing.get() && !paused.get())
        {
            paused.set(true);
            // ★ 保存当前位置到 positionOffsetSec（暂停时 getPositionSec 返回此值）
            positionOffsetSec = getPositionSec();
            ScrobbleManager.getInstance().stopTicking();
            try { if (currentLine != null) currentLine.stop(); } catch (Exception ignored) {}
            listeners.forEach(l -> l.onPlayStateChanged(false, true));
        }
    }

    public void resume()
    {
        if (playing.get() && paused.get())
        {
            paused.set(false);
            // ★ 恢复时更新 lineStartPosUs，使位置从暂停处继续
            if (currentLine != null)
            {
                lineStartPosUs = currentLine.getMicrosecondPosition();
            }
            ScrobbleManager.getInstance().startTicking();
            try { if (currentLine != null) currentLine.start(); } catch (Exception ignored) {}
            listeners.forEach(l -> l.onPlayStateChanged(true, false));
        }
    }

    public void stop()
    {
        stopInternal(true);
    }

    private void stopInternal(boolean notify)
    {
        stopped.set(true);
        playing.set(false);
        paused.set(false);
        playStartTimeMs = 0;

        // ★ 停止打卡计时（但不打卡，由调用方决定是否打卡）
        ScrobbleManager.getInstance().stopTicking();

        cleanupPlaybackResources();

        if (notify)
        {
            listeners.forEach(l -> l.onPlayStateChanged(false, false));
        }
    }

    /**
     * 下一首
     */
    public void next()
    {
        if (queue.isEmpty())
        {
            stop();
            return;
        }
        int next = queueIndex + 1;
        if (next >= queue.size())
        {
            if (loopMode)
            {
                next = 0;
            }
            else
            {
                stop();
                return;
            }
        }
        queueIndex = next;
        playSong(queue.get(next));
    }

    /**
     * 上一首
     */
    public void prev()
    {
        if (queue.isEmpty()) return;
        int prev = queueIndex - 1;
        if (prev < 0)
        {
            if (loopMode)
            {
                prev = queue.size() - 1;
            }
            else
            {
                prev = 0;
            }
        }
        queueIndex = prev;
        playSong(queue.get(prev));
    }

    /** 跳转到队列中指定索引的歌曲 */
    public void jumpTo(int index)
    {
        if (queue.isEmpty() || index < 0 || index >= queue.size()) return;
        queueIndex = index;
        playSong(queue.get(index));
    }

    /** 从队列中移除指定索引的歌曲 */
    public void removeFromQueue(int index)
    {
        if (queue.isEmpty() || index < 0 || index >= queue.size()) return;
        boolean isCurrent = (index == queueIndex);
        java.util.List<NeteaseSong> newQueue = new java.util.ArrayList<>(queue);
        newQueue.remove(index);
        queue.clear();
        queue.addAll(newQueue);
        if (queue.isEmpty()) { queueIndex = -1; stop(); return; }
        if (isCurrent) { int newIdx = Math.min(index, queue.size() - 1); queueIndex = newIdx; playSong(queue.get(newIdx)); }
        else if (index < queueIndex) { queueIndex--; }
    }

    /** 清空队列 */
    public void clearQueue() { queue.clear(); queueIndex = -1; stop(); }

    /** 添加到当前播放歌曲的下一首 */
    public void playNext(NeteaseSong song)
    {
        if (song == null) return;
        for (NeteaseSong q : queue) { if (q.id == song.id) return; }
        if (queue.isEmpty() || queueIndex < 0) { queue.add(song); queueIndex = 0; playSong(song); return; }
        int insertPos = queueIndex + 1;
        if (insertPos > queue.size()) insertPos = queue.size();
        queue.add(insertPos, song);
    }

    /** 追加到队列尾部 */
    public void appendToQueue(NeteaseSong song)
    {
        if (song == null) return;
        for (NeteaseSong q : queue) { if (q.id == song.id) return; }
        queue.add(song);
        if (queueIndex < 0 || !playing.get()) { queueIndex = queue.size() - 1; playSong(song); }
    }

    /** ★ Seek 到指定位置（秒）- 重新打开流并跳过前面的帧 */
    public void seekTo(double targetSec)
    {
        NeteaseSong song = currentSong.get();
        if (song == null) { ZephyrMusic.LOGGER.warn("[Zephyr] seekTo: no song"); return; }
        double total = song.duration > 0 ? song.duration / 1000.0 : 0;
        if (total > 0 && targetSec > total) targetSec = total;
        if (targetSec < 0) targetSec = 0;

        ZephyrMusic.LOGGER.info("[Zephyr] Seek to {}s", String.format("%.1f", targetSec));

        // ★ 真正 seek: 停止当前播放，异步重新打开流并跳过帧
        final double seekTarget = targetSec;
        final NeteaseSong seekSong = song;
        final String level = ZephyrConfig.DEFAULT_QUALITY.get();
        final int seekQueueIndex = queueIndex;

        // 停止解码线程
        stopped.set(true);
        cleanupPlaybackResources();

        playExecutor.submit(() -> {
            try
            {
                // 重新获取 URL
                JsonObject urlResp = NeteaseSession.getInstance().getApi().songUrlV1(seekSong.id, level == null ? "exhigh" : level).join();
                String url = NeteaseApi.extractSongUrl(urlResp);
                if (url == null || url.isEmpty())
                {
                    urlResp = NeteaseSession.getInstance().getApi().songUrl(seekSong.id).join();
                    url = NeteaseApi.extractSongUrl(urlResp);
                }
                if (url == null || url.isEmpty())
                {
                    ZephyrMusic.LOGGER.warn("[Zephyr] Seek: cannot get URL");
                    return;
                }

                // 重新打开流
                java.net.URL audioUrl = URI.create(url).toURL();
                HttpURLConnection conn = (HttpURLConnection) audioUrl.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "ZephyrMusic-Mod/1.0");
                conn.setRequestProperty("Referer", "https://music.163.com/");
                conn.connect();
                if (conn.getResponseCode() != 200) return;

                InputStream is = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                currentConnection = conn;
                currentBitstream = new Bitstream(is);
                currentDecoder = new Decoder();
                currentLine = null;
                currentFormat = null;

                // 计算需要跳过的帧数（近似：MP3 44100Hz 每帧 1152 samples = 26.12ms）
                // 也可以用 JLayer Header 的 frame count 计算
                int skipFrames = (int)(seekTarget * 38.28); // 38.28 frames/sec at 44100Hz

                stopped.set(false);
                paused.set(false);
                playing.set(true);
                positionOffsetSec = seekTarget;
                lineStartPosUs = 0;
                lastReturnedPos = seekTarget;
                playStartTimeMs = System.currentTimeMillis();

                ZephyrMusic.LOGGER.info("[Zephyr] Seek: skipping {} frames to {}s", skipFrames, String.format("%.1f", seekTarget));

                // 跳过帧（不解码到音频设备，只读 Bitstream）
                int skipped = 0;
                for (int i = 0; i < skipFrames && !stopped.get(); i++)
                {
                    try
                    {
                        Header frame = currentBitstream.readFrame();
                        if (frame == null) break;
                        currentBitstream.closeFrame();
                        skipped++;
                    }
                    catch (Exception e) { break; }
                }
                ZephyrMusic.LOGGER.info("[Zephyr] Seek: skipped {} frames", skipped);

                // 开始正常播放
                playThread = new Thread(this::decodeLoop, "ZephyrMusic-Decode");
                playThread.setDaemon(true);
                playThread.setPriority(Thread.MAX_PRIORITY);
                playThread.start();
            }
            catch (Exception e)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] Seek failed", e);
            }
        });
    }

    public void shutdown()
    {
        stopInternal(false);
        playExecutor.shutdownNow();
    }
}
