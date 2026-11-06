package com.zephyr.music.client.audio;

import com.zephyr.music.ZephyrMusic;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTEfx;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAL 流式音频输出引擎 v2 —— 泵线程（pump）架构
 *
 * ★ v0.1.0 修复"无法正常播放"（每首歌几秒就跳下一首）：
 *   旧实现的 write() 把 AL 调用异步投递到单线程执行器后立刻返回，
 *   解码线程从未被节流，整首歌几秒内"解码完毕"→ MusicPlayer 判定 naturalEnd
 *   并切歌；且排队的 write 任务与下一首歌的 open() 竞争同一批 AL buffer，
 *   造成 Invalid Name / 静音 / 乱序。
 *
 *   新架构：
 *   - write()（解码线程调用）：立即拷贝 PCM（JLayer 会复用 SampleBuffer 数组！），
 *     以阻塞方式放入有界队列 —— 队列满即背压，解码速度被播放速度锁住
 *   - 泵线程独占全部 AL 调用：只在有"已播放完的 buffer"可回收时才喂新数据，
 *     从物理上保证实时速率；暂停/音量/混响/开关流全部经 volatile 标志传达，
 *     响应延迟 ≤ 一个泵循环（~10ms）
 *   - drain()：流结束后等待队列 + AL 队列全部播完（由泵线程判定 drained 标志）
 *   - 进度：泵线程每轮更新位置快照（processedSamples + AL_SAMPLE_OFFSET），
 *     getPositionSec() 无锁直读，HUD/歌词同步零延迟
 *
 * - 独立 ALC device + context（与 MC 声音引擎互不干扰）
 * - EFX 洞穴混响：AL_EFFECT_REVERB 辅助效果槽，可随时开关
 */
public final class OpenAlOutput
{
    /** AL 流缓冲数：16 × 2304 样本 ≈ 0.84s @44.1kHz stereo */
    private static final int STREAM_BUFFERS = 16;
    /** write 侧待喂队列深度：24 × ~2304 样本 ≈ 1.25s（此前的总缓冲 ~2s） */
    private static final int CHUNK_QUEUE_SIZE = 24;

    // ---- OpenAL 句柄（仅泵线程访问） ----
    private long device = 0;
    private long context = 0;
    private int source = -1;
    private final int[] buffers = new int[STREAM_BUFFERS];
    private final Deque<Integer> freeBuffers = new ArrayDeque<>();
    private boolean efxAvailable = false;
    private int reverbEffect = 0;
    private int reverbSlot = 0;

    private int sampleRate = 44100;
    private int channels = 2;
    private int alFormat = AL10.AL_FORMAT_STEREO16;

    // ---- 跨线程状态 ----
    /** write() 侧 PCM 块队列（解码线程生产，泵线程消费） */
    private final ArrayBlockingQueue<short[]> chunkQueue = new ArrayBlockingQueue<>(CHUNK_QUEUE_SIZE);

    private volatile boolean pumpAlive = true;
    private volatile boolean deviceReady = false;
    /** 当前流是否可写入/播放 */
    private volatile boolean streamActive = false;
    /** 请求泵线程拆除当前流 */
    private volatile boolean stopStreamFlag = false;
    /** 流结束标志（decodeLoop 读完流后置位，泵线程据此判定 drained） */
    private volatile boolean endOfStream = false;
    /** 流内音频是否已全部播完（泵线程维护） */
    private volatile boolean drained = true;
    private volatile boolean pausedFlag = false;
    private volatile float currentVolume = 0.6f;
    private volatile boolean volumeDirty = false;
    private final AtomicBoolean reverbWanted = new AtomicBoolean(false);
    private volatile boolean reverbDirty = false;
    /** 泵线程维护的播放位置快照（秒） */
    private volatile double positionSnapshot = 0.0;
    private volatile String lastError = null;

    /** open 请求（open 线程 → 泵线程握手） */
    private static final class OpenReq
    {
        final int rate, ch;
        final CountDownLatch ack = new CountDownLatch(1);
        volatile boolean ok = false;
        OpenReq(int rate, int ch) { this.rate = rate; this.ch = ch; }
    }
    private volatile OpenReq pendingOpen = null;

    // 泵线程内计数
    private int queuedCount = 0;
    private long processedSamples = 0;
    private boolean pauseApplied = false;
    /** 每个 AL buffer 携带的样本数（buffer 句柄 → 样本数，仅泵线程访问） */
    private final java.util.HashMap<Integer, Integer> bufferSamples = new java.util.HashMap<>();

    private Thread pumpThread;
    private volatile boolean closed = false;

    private static OpenAlOutput instance;

    public static synchronized OpenAlOutput getInstance()
    {
        if (instance == null) instance = new OpenAlOutput();
        return instance;
    }

    private OpenAlOutput()
    {
        pumpThread = new Thread(this::pumpLoop, "ZephyrMusic-OpenAL");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    public static boolean isAvailable()
    {
        return !getInstance().closed && getInstance().deviceReady;
    }

    public String getLastError() { return lastError; }

    // ==================================================================
    //  对外 API
    // ==================================================================

    /**
     * 打开音频输出（解码线程确认采样率/声道后调用）。
     * 同步握手：泵线程建好 source/buffers 后返回。每次调用都会重建干净的流。
     *
     * @return 成功返回 true；失败原因见 getLastError()
     */
    public boolean open(int sampleRate, int channels)
    {
        if (closed) { lastError = "output closed"; return false; }

        // ★ v0.1.0：总是重建（不复用旧流）——避免与挂起的 stopStream 请求产生竞态
        lastError = null;
        OpenReq req = new OpenReq(sampleRate, channels);
        this.pendingOpen = req;
        try
        {
            if (!req.ack.await(8, TimeUnit.SECONDS))
            {
                lastError = "open timeout (pump thread busy)";
                pendingOpen = null;
                return false;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            lastError = "open interrupted";
            return false;
        }
        if (!req.ok && lastError == null) lastError = "open failed";
        return req.ok;
    }

    /** 兼容旧调用：异步打开（内部仍同步握手） */
    public java.util.concurrent.CompletableFuture<Void> openAsync(int sampleRate, int channels)
    {
        return java.util.concurrent.CompletableFuture.runAsync(() -> open(sampleRate, channels));
    }

    /**
     * 写入一段 PCM 短整型样本（阻塞直到进入待喂队列）。
     * ★ 立即拷贝：JLayer 的 SampleBuffer 会复用内部数组，异步读取会拿到被覆盖的数据。
     * ★ 队列满时阻塞 —— 这就是解码线程的实时节流（背压），播放多快解码才能走多快。
     */
    public void write(short[] samples, int len)
    {
        if (samples == null || len <= 0 || !streamActive) return;
        len = Math.min(len, samples.length);
        // 对齐到帧（立体声 2 样本/帧，单声道 1）
        len -= len % Math.max(1, channels);
        if (len <= 0) return;

        short[] copy = (len == samples.length) ? Arrays.copyOf(samples, len)
                                               : Arrays.copyOfRange(samples, 0, len);
        while (true)
        {
            try
            {
                if (chunkQueue.offer(copy, 50, TimeUnit.MILLISECONDS)) return;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            if (!streamActive || closed) return;   // 流被停掉，丢弃剩余数据
        }
    }

    /** 解码线程读完整个流后调用：之后 drain() 才会判定"播完" */
    public void markEndOfStream()
    {
        this.endOfStream = true;
    }

    /** 等待队列中剩余音频全部播放完（流自然结束时调用）。 */
    public void drain(long timeoutMs)
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (closed || !streamActive || drained) return;
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }

    public double getPositionSec()
    {
        if (!streamActive) return -1;
        return positionSnapshot;
    }

    public void pause() { pausedFlag = true; }

    public void resume()
    {
        pausedFlag = false;
    }

    public void setVolume(float v)
    {
        currentVolume = Math.max(0f, Math.min(1f, v));
        volumeDirty = true;
    }

    public float getVolume() { return currentVolume; }

    /** 洞穴混响开关（EFX 不可用时静默忽略） */
    public void setReverb(boolean on)
    {
        reverbWanted.set(on);
        reverbDirty = true;
    }

    public boolean isReverbEnabled() { return reverbWanted.get(); }
    public boolean isReverbAvailable() { return efxAvailable; }

    /** 停止当前流并释放 source/buffer（device/context 保留复用）。异步，泵线程 ≤10ms 内执行 */
    public void stopStream()
    {
        stopStreamFlag = true;
    }

    /** 释放整个 OpenAL 资源（模组卸载/退出时调用） */
    public void shutdown()
    {
        closed = true;
        pumpAlive = false;
        if (pumpThread != null)
        {
            try { pumpThread.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    // ==================================================================
    //  泵线程：独占所有 AL 调用
    // ==================================================================

    private void pumpLoop()
    {
        while (pumpAlive)
        {
            try
            {
                if (!deviceReady)
                {
                    if (!createDevice())
                    {
                        sleep(200);
                        continue;
                    }
                    deviceReady = true;
                }

                // 1. open 请求握手
                OpenReq req = pendingOpen;
                if (req != null)
                {
                    pendingOpen = null;
                    handleOpen(req);
                    req.ack.countDown();
                    continue;
                }

                // 2. 停流请求
                if (stopStreamFlag)
                {
                    stopStreamFlag = false;
                    teardownStream();
                    continue;
                }

                // 3. 音量
                if (volumeDirty)
                {
                    volumeDirty = false;
                    if (streamActive && source >= 0)
                        AL10.alSourcef(source, AL10.AL_GAIN, volumeToGain(currentVolume));
                }

                // 4. 无活动流：待机
                if (!streamActive || source < 0)
                {
                    sleep(10);
                    continue;
                }

                // 5. 暂停 / 恢复
                if (pausedFlag)
                {
                    if (!pauseApplied)
                    {
                        AL10.alSourcePause(source);
                        pauseApplied = true;
                    }
                    sleep(20);
                    updatePosition();
                    continue;
                }
                if (pauseApplied)
                {
                    pauseApplied = false;
                    ensurePlaying();
                }

                // 6. 回收已播完的 buffer
                reclaimProcessed();

                // 7. 播完判定（解码侧已声明流结束 且 队列全部排空）
                drained = endOfStream && chunkQueue.isEmpty() && queuedCount == 0;

                // 8. 喂数据：只有存在空闲 AL buffer 时才取一块 —— 实时节流的根源
                if (!freeBuffers.isEmpty())
                {
                    short[] chunk = chunkQueue.poll(5, TimeUnit.MILLISECONDS);
                    if (chunk != null)
                    {
                        fillAndQueue(chunk);
                        drained = false;
                    }
                }
                else
                {
                    sleep(3);   // AL 队列满 → 等待实时播放消耗
                }

                // 9. underrun 恢复 + 位置快照
                ensurePlaying();
                updatePosition();
            }
            catch (InterruptedException ie)
            {
                // 正常轮询被打断，继续
            }
            catch (Throwable t)
            {
                ZephyrMusic.LOGGER.warn("[Zephyr] OpenAL pump loop error: {}", t.toString());
                sleep(50);
            }
        }

        // 泵线程退出：释放全部资源（含 context）
        try { teardownStream(); releaseContext(); } catch (Throwable ignored) {}
    }

    /** 泵线程内：处理 open 请求 */
    private void handleOpen(OpenReq req)
    {
        try
        {
            stopStreamFlag = false;   // ★ 吸收挂起的停流请求，避免新流被旧请求拆掉
            teardownStream();   // 清掉上一首歌残留（若有）

            this.sampleRate = req.rate;
            this.channels = req.ch;
            this.alFormat = (req.ch == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

            source = AL10.alGenSources();
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR || source < 0)
            {
                source = -1;
                lastError = "alGenSources failed: 0x" + Integer.toHexString(err);
                ZephyrMusic.LOGGER.error("[Zephyr] {}", lastError);
                req.ok = false;
                return;
            }

            AL10.alSourcef(source, AL10.AL_GAIN, volumeToGain(currentVolume));
            AL10.alSourcef(source, AL10.AL_PITCH, 1.0f);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSource3f(source, AL10.AL_VELOCITY, 0f, 0f, 0f);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_ROLLOFF_FACTOR, 0);   // 立体声不做 3D 衰减

            freeBuffers.clear();
            for (int i = 0; i < STREAM_BUFFERS; i++)
            {
                buffers[i] = AL10.alGenBuffers();
                freeBuffers.add(buffers[i]);
            }
            err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR)
            {
                lastError = "alGenBuffers failed: 0x" + Integer.toHexString(err);
                ZephyrMusic.LOGGER.error("[Zephyr] {}", lastError);
                req.ok = false;
                return;
            }

            queuedCount = 0;
            processedSamples = 0;
            bufferSamples.clear();
            positionSnapshot = 0;
            endOfStream = false;
            drained = false;
            pauseApplied = false;
            pausedFlag = false;
            chunkQueue.clear();

            reverbDirty = true;
            applyReverbState();

            streamActive = true;
            req.ok = true;
            ZephyrMusic.LOGGER.info("[Zephyr] OpenAL output opened: {}Hz, {} ch, EFX reverb: {}",
                    sampleRate, channels, efxAvailable ? "available" : "n/a");
        }
        catch (Throwable t)
        {
            lastError = t.toString();
            ZephyrMusic.LOGGER.error("[Zephyr] OpenAL open failed", t);
            req.ok = false;
        }
    }

    /** 泵线程内：把一块 PCM 装入空闲 buffer 并入队播放 */
    private void fillAndQueue(short[] chunk)
    {
        Integer bufRef = freeBuffers.poll();
        if (bufRef == null || source < 0) return;
        int buf = bufRef;

        ShortBuffer sb = BufferUtils.createShortBuffer(chunk.length);
        sb.put(chunk).flip();
        AL10.alBufferData(buf, alFormat, sb, sampleRate);
        AL10.alSourceQueueBuffers(source, buf);
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] alBufferData/Queue failed: 0x{}", Integer.toHexString(err));
            freeBuffers.add(buf);
            return;
        }
        // ★ 修复歌词/进度错位：chunk.length 是交错样本数（立体声 = 帧数×2），
        //   而 AL_SAMPLE_OFFSET 的计数单位是"每声道帧"。这里必须存帧数，
        //   否则立体声歌曲的 positionSnapshot 会以 2 倍速增长（processedSamples 与
        //   AL_SAMPLE_OFFSET 单位不一致），进度条/歌词全部抢跑。
        bufferSamples.put(buf, chunk.length / Math.max(1, channels));
        queuedCount++;
    }

    /** 泵线程内：回收所有已播放处理的 buffer */
    private void reclaimProcessed()
    {
        if (source < 0) return;
        int processed = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR || processed <= 0) return;

        IntBuffer ib = BufferUtils.createIntBuffer(1);
        for (int i = 0; i < processed; i++)
        {
            ib.clear();
            AL10.alSourceUnqueueBuffers(source, ib);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) break;
            int buf = ib.get(0);
            Integer n = bufferSamples.remove(buf);
            if (n != null) processedSamples += n;   // ★ 累加已播完样本 → 播放进度
            freeBuffers.add(buf);
            queuedCount--;
        }
    }

    /** 泵线程内：队列有数据但源停止（underrun / resume）时重新起播 */
    private void ensurePlaying()
    {
        if (source < 0 || pausedFlag || queuedCount <= 0) return;
        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING)
        {
            AL10.alSourcePlay(source);
        }
    }

    /** 泵线程内：更新位置快照（已消费样本 + 源内偏移） */
    private void updatePosition()
    {
        if (source < 0 || sampleRate <= 0) return;
        int sampleOffset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) return;
        positionSnapshot = (processedSamples + sampleOffset) / (double) sampleRate;
    }

    // ==================================================================
    //  设备 / 混响 / 释放
    // ==================================================================

    /** 创建 ALC device + context（泵线程内调用一次） */
    private boolean createDevice()
    {
        if (context != 0) return true;
        try
        {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] alcOpenDevice(null) returned 0");
                lastError = "no OpenAL device";
                return false;
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = ALC10.alcCreateContext(device, (IntBuffer) null);
            if (context == 0)
            {
                ALC10.alcCloseDevice(device);
                device = 0;
                lastError = "alcCreateContext failed";
                return false;
            }
            ALC10.alcMakeContextCurrent(context);
            ALCapabilities alCaps = AL.createCapabilities(deviceCaps);

            efxAvailable = alCaps.ALC_EXT_EFX;
            if (efxAvailable)
            {
                int numSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
                if (numSends < 1) efxAvailable = false;
            }
        }
        catch (Throwable t)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] OpenAL context creation failed", t);
            releaseContext();
            lastError = t.toString();
            return false;
        }

        ZephyrMusic.LOGGER.info("[Zephyr] Own OpenAL context ready. device='{}', EFX={}",
                ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER), efxAvailable);
        return true;
    }

    /** 泵线程内：把 EFX 混响槽挂到源上 / 摘下 */
    private void applyReverbState()
    {
        if (!reverbDirty) return;
        reverbDirty = false;
        if (!efxAvailable || source < 0) return;
        if (reverbWanted.get())
        {
            if (reverbEffect == 0) createReverb();
            if (reverbEffect != 0 && reverbSlot != 0)
            {
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, reverbSlot, 0, EXTEfx.AL_FILTER_NULL);
                int err = AL10.alGetError();
                if (err != AL10.AL_NO_ERROR)
                    ZephyrMusic.LOGGER.warn("[Zephyr] reverb attach failed: 0x{}", Integer.toHexString(err));
                else
                    ZephyrMusic.LOGGER.info("[Zephyr] Cave reverb ON");
            }
        }
        else
        {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, 0);
            AL10.alGetError();
        }
    }

    /** EFX "cave" 混响参数（EFX 规范 CAVE 预设近似值；湿声增益比预设略高，保证可闻） */
    private void createReverb()
    {
        reverbEffect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, 1.0f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.45f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAINHF, 0.001f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_TIME, 2.91f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DECAY_HFRATIO, 1.30f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_GAIN, 0.7f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_REFLECTIONS_DELAY, 0.015f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_GAIN, 1.5f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_LATE_REVERB_DELAY, 0.022f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_AIR_ABSORPTION_GAINHF, 0.892f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_ROOM_ROLLOFF_FACTOR, 0.0f);
        int err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] reverb effect setup failed: 0x{}", Integer.toHexString(err));
            reverbEffect = 0;
            return;
        }
        reverbSlot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(reverbSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverbEffect);
        err = AL10.alGetError();
        if (err != AL10.AL_NO_ERROR)
        {
            ZephyrMusic.LOGGER.warn("[Zephyr] reverb slot setup failed: 0x{}", Integer.toHexString(err));
            reverbSlot = 0;
        }
    }

    /** 泵线程内：拆除当前流（source + buffers），保留 device/context/混响槽 */
    private void teardownStream()
    {
        streamActive = false;
        drained = true;
        endOfStream = false;
        if (source >= 0)
        {
            try
            {
                AL10.alSourceStop(source);
                if (efxAvailable)
                    AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, 0);
                for (int b : buffers)
                {
                    if (b != 0) AL10.alDeleteBuffers(b);
                }
                AL10.alDeleteSources(source);
                AL10.alGetError();
            }
            catch (Throwable ignored) {}
            source = -1;
        }
        freeBuffers.clear();
        java.util.Arrays.fill(buffers, 0);
        queuedCount = 0;
        processedSamples = 0;
        bufferSamples.clear();
        positionSnapshot = 0;
        chunkQueue.clear();
        pauseApplied = false;
    }

    /** 泵线程内：释放 device + context */
    private void releaseContext()
    {
        try
        {
            if (reverbSlot != 0 && efxAvailable) { EXTEfx.alDeleteAuxiliaryEffectSlots(reverbSlot); reverbSlot = 0; }
            if (reverbEffect != 0 && efxAvailable) { EXTEfx.alDeleteEffects(reverbEffect); reverbEffect = 0; }
            if (context != 0)
            {
                ALC10.alcMakeContextCurrent(0);
                ALC10.alcDestroyContext(context);
                context = 0;
            }
            if (device != 0)
            {
                ALC10.alcCloseDevice(device);
                device = 0;
            }
            deviceReady = false;
        }
        catch (Throwable ignored) {}
    }

    private static void sleep(long ms)
    {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** 音量（0..1）→ AL_GAIN（听感曲线） */
    private static float volumeToGain(float v)
    {
        if (v <= 0f) return 0f;
        return (float) Math.pow(v, 1.35);
    }
}
