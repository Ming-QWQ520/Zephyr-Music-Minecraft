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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAL 流式音频输出引擎
 *
 * ★ 替代原先的 javax.sound.sampled.SourceDataLine（JavaSound）：
 * - 使用 Minecraft 自带 LWJGL OpenAL Soft，无需额外依赖
 * - 独立的 ALC device + context（与 MC 声音引擎互不干扰）
 * - 单线程化所有 AL 调用（OpenAL context 只允许一个 current 线程）
 * - 流式播放：N 个 AL buffer 循环入队，write() 语义对齐 SourceDataLine（队列满则阻塞）
 * - EFX 洞穴混响：AL_EFFECT_REVERB 辅助效果槽，可随时开关
 *
 * write() / drain() / close() 由解码线程调用；pause()/resume()/seek 等
 * 控制可来自渲染线程 —— 全部经内部单线程执行器串行化。
 */
public final class OpenAlOutput
{
    private static final int STREAM_BUFFERS = 6;
    /** 每个 AL buffer 的短整型样本数（约 90ms @44.1kHz stereo） */
    private static final int SAMPLES_PER_BUFFER = 16384;

    // ---- OpenAL 句柄（仅 alExecutor 线程访问） ----
    private long device = 0;
    private long context = 0;
    private int source = -1;
    private final int[] buffers = new int[STREAM_BUFFERS];
    private boolean efxAvailable = false;
    private int reverbEffect = 0;
    private int reverbSlot = 0;

    private int sampleRate = 44100;
    private int channels = 2;
    private int alFormat = AL10.AL_FORMAT_STEREO16;

    /** 已入队样本总量 & 已消费样本量（用于精确播放进度） */
    private long totalQueuedSamples = 0;
    private long processedSamples = 0;
    private final Map<Integer, Long> bufferSampleCounts = new HashMap<>();

    private volatile float volume = 0.6f;
    private final AtomicBoolean reverbEnabled = new AtomicBoolean(false);
    private volatile boolean opened = false;
    private volatile boolean closed = false;
    private volatile String lastError = null;

    private final ExecutorService alExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZephyrMusic-OpenAL");
        t.setDaemon(true);
        return t;
    });

    private static OpenAlOutput instance;

    public static synchronized OpenAlOutput getInstance()
    {
        if (instance == null) instance = new OpenAlOutput();
        return instance;
    }

    private OpenAlOutput() {}

    public static boolean isAvailable()
    {
        return !getInstance().closed;
    }

    public String getLastError() { return lastError; }

    /**
     * 打开音频输出（在解码线程确认采样率/声道后调用）。
     * 幂等：同一首歌内格式不变则复用。
     */
    public CompletableFuture<Void> openAsync(int sampleRate, int channels)
    {
        return CompletableFuture.runAsync(() -> open(sampleRate, channels), alExecutor);
    }

    /** AL 线程内执行 */
    private void open(int sampleRate, int channels)
    {
        if (closed) { lastError = "output closed"; return; }
        if (opened && this.sampleRate == sampleRate && this.channels == channels) return;

        closeInternal();   // 若已有旧流则先释放

        try
        {
            ensureContext();
            if (device == 0) { lastError = "no OpenAL device"; return; }

            this.sampleRate = sampleRate;
            this.channels = channels;
            this.alFormat = (channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

            source = AL10.alGenSources();
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR)
            {
                source = -1;
                lastError = "alGenSources failed: 0x" + Integer.toHexString(err);
                ZephyrMusic.LOGGER.error("[Zephyr] {}", lastError);
                return;
            }

            AL10.alSourcef(source, AL10.AL_GAIN, volumeToGain(volume));
            AL10.alSourcef(source, AL10.AL_PITCH, 1.0f);
            AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            AL10.alSource3f(source, AL10.AL_VELOCITY, 0f, 0f, 0f);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            // 立体声不做 3D 距离衰减
            AL10.alSourcei(source, AL10.AL_ROLLOFF_FACTOR, 0);

            for (int i = 0; i < STREAM_BUFFERS; i++)
            {
                buffers[i] = AL10.alGenBuffers();
            }
            err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR)
            {
                lastError = "alGenBuffers failed: 0x" + Integer.toHexString(err);
                ZephyrMusic.LOGGER.error("[Zephyr] {}", lastError);
                return;
            }

            totalQueuedSamples = 0;
            processedSamples = 0;
            bufferSampleCounts.clear();

            applyReverbState();

            opened = true;
            ZephyrMusic.LOGGER.info("[Zephyr] OpenAL output opened: {}Hz, {} ch, EFX reverb: {}",
                    sampleRate, channels, efxAvailable ? "available" : "n/a");
        }
        catch (Exception e)
        {
            lastError = e.toString();
            ZephyrMusic.LOGGER.error("[Zephyr] OpenAL open failed", e);
        }
    }

    /** 确保 ALC device + context 在本线程创建并 current */
    private void ensureContext()
    {
        if (context != 0) return;
        try
        {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0)
            {
                ZephyrMusic.LOGGER.error("[Zephyr] alcOpenDevice(null) returned 0");
                return;
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            IntBuffer attrs = null;
            // 请求 EFX 扩展支持的上下文（OpenAL Soft 默认开启，属性留空即可）
            context = ALC10.alcCreateContext(device, attrs);
            if (context == 0)
            {
                ALC10.alcCloseDevice(device);
                device = 0;
                return;
            }
            ALC10.alcMakeContextCurrent(context);
            ALCapabilities alCaps = AL.createCapabilities(deviceCaps);

            efxAvailable = alCaps.ALC_EXT_EFX;
            if (efxAvailable)
            {
                int numSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
                if (numSends < 1) efxAvailable = false;
            }
            ZephyrMusic.LOGGER.info("[Zephyr] Own OpenAL context ready. device='{}', EFX={}",
                    ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER), efxAvailable);
        }
        catch (Exception e)
        {
            ZephyrMusic.LOGGER.error("[Zephyr] OpenAL context creation failed", e);
            releaseContext();
        }
    }

    // ==================================================================
    //  流式写入（对齐 SourceDataLine.write 的阻塞语义）
    // ==================================================================

    /**
     * 写入一段 PCM 短整型样本（阻塞直到入队完成）。
     * 与 JavaSound 一致：队列满时等待 AL 播放消费出空闲 buffer。
     */
    public void write(short[] samples, int len)
    {
        if (closed || !opened || source < 0 || len <= 0) return;
        runAl(() -> {
            int off = 0;
            while (off < len && !closed)
            {
                // 等待一个空闲 buffer（已播放处理的）
                int freeBuf = waitForFreeBuffer();
                if (freeBuf < 0) return;

                int n = Math.min(SAMPLES_PER_BUFFER, len - off);
                // 对齐到帧（立体声 2 samples/帧，单声道 1）
                int frame = channels;
                n -= n % frame;

                java.nio.ShortBuffer sb = BufferUtils.createShortBuffer(n);
                sb.put(samples, off, n).flip();
                AL10.alBufferData(freeBuf, alFormat, sb, sampleRate);
                AL10.alSourceQueueBuffers(source, freeBuf);

                bufferSampleCounts.put(freeBuf, (long) n);
                totalQueuedSamples += n;
                off += n;

                // 队列里已有数据且源未播放 → 开始播放
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING)
                {
                    AL10.alSourcePlay(source);
                }
            }
        });
    }

    /** AL 线程内：取一个已处理完的 buffer（阻塞等待，最多 ~2s） */
    private int waitForFreeBuffer()
    {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline)
        {
            if (closed) return -1;
            int processed = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);
            if (processed > 0)
            {
                IntBuffer ib = BufferUtils.createIntBuffer(1);
                AL10.alSourceUnqueueBuffers(source, ib);
                int buf = ib.get(0);
                int err = AL10.alGetError();
                if (err != AL10.AL_NO_ERROR) return -1;
                Long cnt = bufferSampleCounts.remove(buf);
                if (cnt != null) processedSamples += cnt;
                return buf;
            }
            // 若源已停止且队列为空（underrun），直接允许填充
            int queued = AL10.alGetSourcei(source, AL11.AL_BUFFERS_QUEUED);
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (queued == 0 && state != AL10.AL_PLAYING)
            {
                return nextFreeBufferSlot();
            }
            try { Thread.sleep(4); } catch (InterruptedException e) { return -1; }
        }
        return -1;
    }

    /** 初始化时按槽位轮转取空闲 buffer（队列为空时用） */
    private int nextFreeBufferSlot()
    {
        for (int b : buffers)
        {
            if (!bufferSampleCounts.containsKey(b)) return b;
        }
        return buffers[0];
    }

    // ==================================================================
    //  控制
    // ==================================================================

    public void pause()
    {
        runAl(() -> { if (opened && source >= 0) AL10.alSourcePause(source); });
    }

    public void resume()
    {
        runAl(() -> {
            if (opened && source >= 0)
            {
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING) AL10.alSourcePlay(source);
            }
        });
    }

    /** 当前流内播放位置（秒，相对本流的起点） */
    public double getPositionSec()
    {
        try
        {
            return CompletableFuture.supplyAsync(() -> {
                if (!opened || source < 0) return 0.0;
                int sampleOffset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
                long consumed = processedSamples + sampleOffset;
                return consumed / (double) Math.max(1, sampleRate);
            }, alExecutor).get(200, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            return -1;   // 调用方应回退到自己的估算
        }
    }

    /** 等待队列中剩余音频播放完（流自然结束时调用） */
    public void drain(long timeoutMs)
    {
        try
        {
            CompletableFuture.runAsync(() -> {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < deadline)
                {
                    if (closed || source < 0) return;
                    int queued = AL10.alGetSourcei(source, AL11.AL_BUFFERS_QUEUED);
                    int processed = AL10.alGetSourcei(source, AL11.AL_BUFFERS_PROCESSED);
                    if (queued == 0 || (queued == processed)) return;
                    try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                }
            }, alExecutor).get(timeoutMs + 200, TimeUnit.MILLISECONDS);
        }
        catch (Exception ignored) {}
    }

    public void setVolume(float v)
    {
        volume = Math.max(0f, Math.min(1f, v));
        runAl(() -> { if (opened && source >= 0) AL10.alSourcef(source, AL10.AL_GAIN, volumeToGain(volume)); });
    }

    public float getVolume() { return volume; }

    /** 洞穴混响开关（EFX 不可用时静默忽略） */
    public void setReverb(boolean on)
    {
        reverbEnabled.set(on);
        runAl(this::applyReverbState);
    }

    public boolean isReverbEnabled() { return reverbEnabled.get(); }
    public boolean isReverbAvailable() { return efxAvailable; }

    /** AL 线程内：把 EFX 混响槽挂到源上 / 摘下 */
    private void applyReverbState()
    {
        if (!efxAvailable || source < 0) return;
        if (reverbEnabled.get())
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

    /** EFX "cave" 混响参数（EFX 规范 CAVE 预设的近似值） */
    private void createReverb()
    {
        reverbEffect = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(reverbEffect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_REVERB);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DENSITY, 1.0f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_DIFFUSION, 1.0f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAIN, 0.316f);
        EXTEfx.alEffectf(reverbEffect, EXTEfx.AL_REVERB_GAINHF, 0.0f);
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

    // ==================================================================
    //  关闭
    // ==================================================================

    /** 停止当前流并释放源/buffer（保留 device+context） */
    public void stopStream()
    {
        runAl(this::closeInternal);
    }

    /** 释放整个 OpenAL 资源（模组卸载/退出时调用） */
    public void shutdown()
    {
        closed = true;
        try
        {
            alExecutor.submit(this::releaseAll).get(2, TimeUnit.SECONDS);
        }
        catch (Exception ignored) {}
        alExecutor.shutdownNow();
    }

    private void closeInternal()
    {
        opened = false;
        if (source >= 0)
        {
            try
            {
                AL10.alSourceStop(source);
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, EXTEfx.AL_EFFECTSLOT_NULL, 0, 0);
                for (int b : buffers)
                {
                    if (b != 0) AL10.alDeleteBuffers(b);
                }
                AL10.alDeleteSources(source);
                AL10.alGetError();
            }
            catch (Exception ignored) {}
            source = -1;
        }
        bufferSampleCounts.clear();
        totalQueuedSamples = 0;
        processedSamples = 0;
    }

    private void releaseAll()
    {
        closeInternal();
        releaseContext();
    }

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
        }
        catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------

    private void runAl(Runnable r)
    {
        if (closed) return;
        try
        {
            alExecutor.execute(r);
        }
        catch (Exception ignored) {}
    }

    /** 音量（0..1）→ AL_GAIN（听感曲线：平方根提升低段） */
    private static float volumeToGain(float v)
    {
        if (v <= 0f) return 0f;
        return (float) Math.pow(v, 1.35);
    }
}
