/*
 * 云音乐（yunyy）— Minecraft 模组
 * Copyright (C) 2026 eecccm
 * 本文件由 eecccm 与 AI 协作完成，部分内容经由 AI 辅助生成。
 *
 * 本程序是自由软件：你可以根据自由软件基金会发布的 GNU 通用公共许可证
 * （版本 3，或（按你的选择）任何更高版本）的条款重新分发和/或修改它。
 *
 * 本程序分发的目的是希望它有用，但没有任何担保；甚至没有适销性或
 * 特定用途适用性的隐含担保。详见 GNU 通用公共许可证。
 *
 * 你应当已经收到一份 GNU 通用公共许可证的副本；如果没有，请见
 * <https://www.gnu.org/licenses/>。
 *
 * 附加许可（依据 GPL-3.0 第 7 条）：本模组适用“链接例外”，允许本模组与
 * Minecraft 及 Forge / Fabric / NeoForge 等运行时链接，而无需使这些运行时
 * 或任何组合作品整体受到 GPL 约束。GPL-3.0 仅约束本模组自身代码。
 */

package com.eecccm.yunyy.mccore.client;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.config.GainPreferences;
import com.eecccm.yunyy.core.log.TraceLog;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.Library.Pool;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.AudioDeviceBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.ChannelAccess.ChannelHandle;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;

public final class GainDevice extends AudioDeviceBase {
   private static final AtomicReference<GainDevice> CURRENT = new AtomicReference<>();
   private static volatile ChannelAccess cachedChannelAccess;
   private static volatile Library musicLibrary;
   private final BooleanSupplier playbackValid;
   private volatile GainDevice.PcmAudioStream stream;
   private volatile ChannelHandle handle;
   private volatile Channel channel;
   private volatile boolean closed;
   private volatile long writtenSamples;
   private long decodedSamples;
   private boolean caughtUp;
   private volatile int channels = 2;
   private volatile int sampleRate = 44100;

   public GainDevice(BooleanSupplier playbackValid) {
      this.playbackValid = playbackValid;
   }

   public static void stopAll() {
      stopCurrent();
      ChannelAccess access = cachedChannelAccess;
      if (access != null) {
         access.executeOnChannels(channels -> channels.forEach(Channel::stop));
      }
   }

   public static void tick() {
      ChannelAccess access = cachedChannelAccess;
      if (access != null) {
         access.scheduleTick();
      }
   }

   public static void stopCurrent() {
      GainDevice device = CURRENT.getAndSet(null);
      if (device != null) {
         device.closed = true;
         GainDevice.PcmAudioStream currentStream = device.stream;
         if (currentStream != null) {
            currentStream.cancel();
         }

         ChannelHandle current = device.handle;
         if (current != null && !current.isStopped()) {
            current.execute(Channel::stop);
         }
      }
   }

   public static void beforeLibraryCleanup(Library library) {
      if (library == musicLibrary) {
         ChannelAccess access = cachedChannelAccess;
         if (access != null) {
            access.clear();
         }

         cachedChannelAccess = null;
         musicLibrary = null;
      }
   }

   public static void refreshVolume() {
      GainDevice device = CURRENT.get();
      if (device != null) {
         ChannelHandle current = device.handle;
         if (current != null && !current.isStopped()) {
            current.execute(channel -> channel.setVolume(GainPreferences.gain()));
         }
      }
   }

   public static void setPaused(boolean paused) {
      GainDevice device = CURRENT.get();
      if (device != null) {
         ChannelHandle current = device.handle;
         if (current != null && !current.isStopped()) {
            current.execute(channel -> {
               if (paused) {
                  channel.pause();
               } else {
                  channel.unpause();
               }
            });
         }
      }
   }

   public static boolean preventUnpause(Channel candidate) {
      GainDevice device = CURRENT.get();
      return device != null && device.channel == candidate && PlaybackEngine.isPaused();
   }

   protected void writeImpl(short[] samples, int offset, int length) throws JavaLayerException {
      if (!this.closed && !Thread.currentThread().isInterrupted()) {
         Decoder decoder = this.getDecoder();
         this.sampleRate = decoder.getOutputFrequency();
         this.channels = decoder.getOutputChannels();
         this.validateFormat();
         this.decodedSamples += length;
         if (!this.caughtUp) {
            long targetSamples = PlaybackEngine.elapsedMillis() * this.sampleRate * this.channels / 1000L;
            if (this.decodedSamples <= targetSamples) {
               return;
            }

            this.caughtUp = true;
            TraceLog.info("已通过完整解码追上同步进度：{} ms", PlaybackEngine.elapsedMillis());
         }

         this.ensureStream();
         byte[] pcm = new byte[length * 2];
         int out = 0;

         for (int i = 0; i < length; i++) {
            int sample = samples[offset + i];
            pcm[out++] = (byte)sample;
            pcm[out++] = (byte)(sample >>> 8);
         }

         try {
            ChannelHandle currentHandle = this.handle;
            if (!this.closed && currentHandle != null && currentHandle.isStopped()) {
               throw channelReset();
            } else if (this.stream.write(pcm)) {
               this.writtenSamples += length;
               if (this.handle == null && this.stream.bufferedBytes() >= this.sampleRate * this.channels * 2) {
                  this.startOutput();
               }
            } else if (!this.closed && this.stream.closedByConsumer()) {
               throw channelReset();
            } else {
               throw new CancellationException();
            }
         } catch (InterruptedException var9) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
         }
      } else {
         throw new CancellationException();
      }
   }

   private synchronized void ensureStream() throws JavaLayerException {
      if (this.closed || Thread.currentThread().isInterrupted()) {
         throw new CancellationException();
      } else if (this.stream == null) {
         this.validateFormat();
         AudioFormat format = new AudioFormat(this.sampleRate, 16, this.channels, true, false);
         this.stream = new GainDevice.PcmAudioStream(format);
         CURRENT.set(this);
         if (!this.playbackValid.getAsBoolean()) {
            this.closed = true;
            this.stream.cancel();
            CURRENT.compareAndSet(this, null);
            throw new CancellationException();
         }
      }
   }

   private synchronized void startOutput() throws JavaLayerException {
      if (this.handle == null && !this.closed) {
         GainDevice.PcmAudioStream created = this.stream;

         ChannelHandle createdHandle;
         try {
            createdHandle = channelAccess().createHandle(Pool.STREAMING).get(5L, TimeUnit.SECONDS);
         } catch (Exception var4) {
            created.cancel();
            throw new JavaLayerException("无法从 Minecraft 获取 OpenAL 流式声道", var4);
         }

         if (createdHandle == null) {
            created.cancel();
            throw new JavaLayerException("Minecraft 没有可用的 OpenAL 流式声道");
         } else if (!this.closed && !Thread.currentThread().isInterrupted()) {
            this.handle = createdHandle;
            createdHandle.execute(channel -> {
               this.channel = channel;
               String stage = "声源位置";

               try {
                  channel.setSelfPosition(Vec3.ZERO);
                  stage = "相对声源";
                  channel.setRelative(true);
                  stage = "距离衰减";
                  channel.disableAttenuation();
                  stage = "循环和音调";
                  channel.setLooping(false);
                  channel.setPitch(1.0F);
                  stage = "音量";
                  channel.setVolume(GainPreferences.gain());
                  stage = "PCM 流";
                  channel.attachBufferStream(created);
                  if (!this.closed && this.playbackValid.getAsBoolean()) {
                     stage = "开始播放";
                     if (PlaybackEngine.isPaused()) {
                        channel.setVolume(0.0F);
                     }

                     channel.play();
                     if (PlaybackEngine.isPaused()) {
                        channel.pause();
                     }

                     channel.setVolume(GainPreferences.gain());
                  } else {
                     created.cancel();
                     channel.stop();
                  }
               } catch (LinkageError | RuntimeException var5) {
                  created.fail(var5);
                  channel.stop();
                  TraceLog.error("Minecraft OpenAL 初始化失败（阶段：{}）", stage, var5);
               }
            });
            TraceLog.info("已使用 Minecraft OpenAL 流式声道播放：{} Hz / {} 声道", this.sampleRate, this.channels);
         } else {
            created.cancel();
            createdHandle.execute(Channel::stop);
            throw new CancellationException();
         }
      }
   }

   private void validateFormat() throws JavaLayerException {
      if (this.sampleRate <= 0 || this.channels != 1 && this.channels != 2) {
         throw new JavaLayerException("OpenAL 不支持此歌曲的 PCM 格式：" + this.sampleRate + " Hz / " + this.channels + " 声道");
      }
   }

   protected void flushImpl() {
      GainDevice.PcmAudioStream currentStream = this.stream;
      if (currentStream != null && !this.closed && this.handle == null) {
         try {
            this.startOutput();
         } catch (JavaLayerException var7) {
            currentStream.fail(var7);
            TraceLog.error("无法播放剩余音频缓冲", var7);
            return;
         }
      }

      ChannelHandle currentHandle = this.handle;
      if (currentStream != null && currentHandle != null && !this.closed) {
         currentStream.finish();
         long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);

         while (!this.closed && !currentHandle.isStopped() && System.nanoTime() < deadline) {
            if (PlaybackEngine.isPaused()) {
               deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
            }

            try {
               Thread.sleep(20L);
            } catch (InterruptedException var6) {
               Thread.currentThread().interrupt();
               return;
            }
         }

         if (!currentHandle.isStopped()) {
            currentHandle.execute(Channel::stop);
         }
      }
   }

   protected void closeImpl() {
      this.closed = true;
      CURRENT.compareAndSet(this, null);
      GainDevice.PcmAudioStream currentStream = this.stream;
      if (currentStream != null) {
         currentStream.cancel();
      }

      ChannelHandle currentHandle = this.handle;
      if (currentHandle != null && !currentHandle.isStopped()) {
         currentHandle.execute(Channel::stop);
      }

      this.stream = null;
      this.handle = null;
      this.channel = null;
   }

   public int getPosition() {
      int rate = this.sampleRate * Math.max(1, this.channels);
      return rate <= 0 ? 0 : (int)Math.min(2147483647L, this.writtenSamples * 1000L / rate);
   }

   private static ChannelAccess channelAccess() throws ReflectiveOperationException {
      ChannelAccess access = cachedChannelAccess;
      if (access != null) {
         return access;
      } else {
         synchronized (GainDevice.class) {
            access = cachedChannelAccess;
            if (access != null) {
               return access;
            } else {
               SoundManager manager = Minecraft.getInstance().getSoundManager();
               SoundEngine engine = findFieldValue(manager, SoundEngine.class);
               Library library = findFieldValue(engine, Library.class);
               SoundEngineExecutor executor = findFieldValue(engine, SoundEngineExecutor.class);
               access = new ChannelAccess(library, executor);
               musicLibrary = library;
               cachedChannelAccess = access;
               return access;
            }
         }
      }
   }

   private static <T> T findFieldValue(Object owner, Class<T> type) throws ReflectiveOperationException {
      for (Field field : owner.getClass().getDeclaredFields()) {
         if (type.isAssignableFrom(field.getType())) {
            field.setAccessible(true);
            return type.cast(field.get(owner));
         }
      }

      throw new NoSuchFieldException(owner.getClass().getName() + " -> " + type.getName());
   }

   private static JavaLayerException channelReset() {
      return new JavaLayerException("Minecraft OpenAL 声道已重置", new GainDevice.AudioChannelResetException("Minecraft OpenAL 声道已重置"));
   }

   static final class AudioChannelResetException extends IOException {
      /** 版本号：该异常只用于内部声道重置信号，不参与持久化或跨进程序列化。 */
      private static final long serialVersionUID = 1L;

      private AudioChannelResetException(String message) {
         super(message);
      }
   }

   private static final class PcmAudioStream implements AudioStream {
      private final AudioFormat format;
      private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
      private final int maxQueuedBytes;
      private final int readChunkBytes;
      private int headOffset;
      private int queuedBytes;
      private boolean finished;
      private boolean cancelled;
      private volatile boolean closedByConsumer;
      private Throwable failure;
      private long skippedBytes;

      private PcmAudioStream(AudioFormat format) {
         this.format = format;
         int bytesPerSecond = Math.max(1, Math.round(format.getFrameRate()) * format.getFrameSize());
         this.maxQueuedBytes = bytesPerSecond * 3;
         this.readChunkBytes = Math.max(format.getFrameSize(), bytesPerSecond / 4);
      }

      public AudioFormat getFormat() {
         return this.format;
      }

      private synchronized boolean write(byte[] pcm) throws InterruptedException, JavaLayerException {
         while (!this.cancelled && this.queuedBytes + pcm.length > this.maxQueuedBytes) {
            this.wait();
         }

         if (this.failure != null) {
            throw new JavaLayerException("Minecraft OpenAL 无法接收 PCM 音频", this.failure);
         } else if (this.cancelled) {
            return false;
         } else {
            if (this.skippedBytes > 0L) {
               int skip = (int)Math.min(this.skippedBytes, (long)pcm.length);
               this.skippedBytes -= skip;
               if (skip == pcm.length) {
                  return true;
               }

               pcm = Arrays.copyOfRange(pcm, skip, pcm.length);
            }

            this.chunks.addLast(pcm);
            this.queuedBytes += pcm.length;
            this.notifyAll();
            return true;
         }
      }

      public synchronized ByteBuffer read(int requestedBytes) throws IOException {
         int wanted = Math.max(this.format.getFrameSize(), Math.min(requestedBytes, this.readChunkBytes));
         wanted -= wanted % this.format.getFrameSize();
         if (!this.cancelled && (!this.finished || this.queuedBytes != 0)) {
            int count = Math.min(wanted, this.queuedBytes);
            count -= count % this.format.getFrameSize();
            int outputSize = this.finished ? count : wanted;
            ByteBuffer output = BufferUtils.createByteBuffer(outputSize);
            int remaining = count;

            while (remaining > 0) {
               byte[] head = this.chunks.peekFirst();
               if (head == null) {
                  break;
               }

               int copy = Math.min(remaining, head.length - this.headOffset);
               output.put(head, this.headOffset, copy);
               this.headOffset += copy;
               this.queuedBytes -= copy;
               remaining -= copy;
               if (this.headOffset == head.length) {
                  this.chunks.removeFirst();
                  this.headOffset = 0;
               }
            }

            if (!this.finished && count < wanted) {
               this.skippedBytes += wanted - count;
            }

            while (output.position() < outputSize) {
               output.put((byte)0);
            }

            output.flip();
            this.notifyAll();
            return output;
         } else {
            return null;
         }
      }

      private synchronized int bufferedBytes() {
         return this.queuedBytes;
      }

      private synchronized void finish() {
         this.finished = true;
         this.notifyAll();
      }

      private synchronized void cancel() {
         this.cancelled = true;
         this.chunks.clear();
         this.queuedBytes = 0;
         this.headOffset = 0;
         this.notifyAll();
      }

      private synchronized void fail(Throwable throwable) {
         this.failure = throwable;
         this.cancelled = true;
         this.chunks.clear();
         this.queuedBytes = 0;
         this.headOffset = 0;
         this.notifyAll();
      }

      private boolean closedByConsumer() {
         return this.closedByConsumer;
      }

      public void close() {
         this.closedByConsumer = true;
         this.cancel();
      }
   }
}
