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
import com.eecccm.yunyy.core.config.AudioBridgeOptions;
import com.eecccm.yunyy.core.error.VendorError;
import com.eecccm.yunyy.core.error.VendorException;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.core.log.Redactor;
import com.eecccm.yunyy.core.lyrics.LyricTimeline;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.util.ErrorMessages;
import com.eecccm.yunyy.core.platform.VendorRegistry;
import com.eecccm.yunyy.core.platform.MusicVendor;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.advanced.AdvancedPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class PlaybackEngine {
   private static final int MAX_STREAM_RETRIES = 3;
   private static final int MAX_AUDIO_CHANNEL_RESTARTS = 16;
   private static final ExecutorService AUDIO_THREAD = Executors.newSingleThreadExecutor(r -> {
      Thread thread = new Thread(r, "yunyy-audio");
      thread.setDaemon(true);
      return thread;
   });
   private static final ExecutorService AUDIO_CONTROL_THREAD = Executors.newFixedThreadPool(2, r -> {
      Thread thread = new Thread(r, "yunyy-audio-control");
      thread.setDaemon(true);
      return thread;
   });
   private static final Object LOCK = new Object();
   private static volatile AdvancedPlayer activePlayer;
   private static volatile Future<?> activeTask;
   private static volatile PlayPacket currentSong;
   private static volatile boolean playbackFailed;
   private static long timelineStartAtMillis;
   private static long pausedAtMillis;
   private static boolean paused;
   private static int pauseMaintenanceTicks;

   private PlaybackEngine() {
   }

   public static void play(PlayPacket song) {
      long receivedAt = System.currentTimeMillis();
      PlayPacket localSong = new PlayPacket(
         song.songId(),
         song.title(),
         song.artist(),
         song.audioUrl(),
         song.coverUrl(),
         receivedAt + song.startAtMillis() - song.serverSentAtMillis(),
         receivedAt,
         song.durationMillis(),
         song.provider()
      );
      stop();
      synchronized (LOCK) {
         currentSong = localSong;
         playbackFailed = false;
         timelineStartAtMillis = localSong.startAtMillis();
         pausedAtMillis = 0L;
         paused = false;
         pauseMaintenanceTicks = 0;
      }

      AlbumArt.load(localSong.coverUrl(), localSong.provider(), localSong.title(), localSong.artist());
      LyricTimeline.loadFor(localSong.provider(), localSong.songId());
      activeTask = AUDIO_THREAD.submit(() -> {
         try {
            playWithReconnect(localSong);
            return;
         } catch (InterruptedException var21) {
            Thread.currentThread().interrupt();
            return;
         } catch (CancellationException var22) {
            return;
         } catch (Exception var23) {
            Throwable cause = unwrapDecoderException(var23);
            if (currentSong == localSong && !(cause instanceof CancellationException)) {
               playbackFailed = true;
               TraceLog.error("播放歌曲失败，底层原因：{}", describe(cause), cause);
               showMessage(Component.translatable("yunyy.msg.play_failed", ErrorMessages.text(cause)).getString());
               return;
            }
         } finally {
            synchronized (LOCK) {
               if (currentSong == localSong) {
                  activePlayer = null;
                  currentSong = null;
                  paused = false;
                  pausedAtMillis = 0L;
                  LOCK.notifyAll();
                  AlbumArt.clear();
                  LyricTimeline.wipe();
               }
            }
         }
      });
   }

   public static void stop() {
      AdvancedPlayer player;
      synchronized (LOCK) {
         currentSong = null;
         playbackFailed = false;
         paused = false;
         pausedAtMillis = 0L;
         timelineStartAtMillis = 0L;
         pauseMaintenanceTicks = 0;
         player = activePlayer;
         activePlayer = null;
         LOCK.notifyAll();
      }

      AlbumArt.clear();
      LyricTimeline.wipe();
      Future<?> task = activeTask;
      activeTask = null;
      if (task != null) {
         task.cancel(true);
      }

      GainDevice.stopCurrent();
      closePlayerAsync(player);
   }

   public static PlayPacket currentSong() {
      return currentSong;
   }

   public static boolean playbackFailed() {
      return playbackFailed;
   }

   public static void setPaused(boolean value) {
      synchronized (LOCK) {
         if (currentSong == null) {
            return;
         }

         if (paused != value) {
            long now = System.currentTimeMillis();
            if (value) {
               paused = true;
               pausedAtMillis = now;
            } else {
               timelineStartAtMillis = timelineStartAtMillis + Math.max(0L, now - pausedAtMillis);
               paused = false;
               pausedAtMillis = 0L;
               LOCK.notifyAll();
            }
         }

         pauseMaintenanceTicks = 0;
      }

      GainDevice.setPaused(value);
   }

   public static void tick() {
      GainDevice.tick();
      synchronized (LOCK) {
         if (currentSong == null || !paused) {
            pauseMaintenanceTicks = 0;
            return;
         }

         if (++pauseMaintenanceTicks < 4) {
            return;
         }

         pauseMaintenanceTicks = 0;
      }

      GainDevice.setPaused(true);
   }

   public static void reapplyPause() {
      synchronized (LOCK) {
         if (currentSong == null || !paused) {
            return;
         }

         pauseMaintenanceTicks = 0;
      }

      GainDevice.setPaused(true);
   }

   public static boolean isPaused() {
      synchronized (LOCK) {
         return paused;
      }
   }

   public static long elapsedMillis() {
      synchronized (LOCK) {
         if (currentSong != null && timelineStartAtMillis != 0L) {
            long positionAt = paused ? pausedAtMillis : System.currentTimeMillis();
            return Math.max(0L, positionAt - timelineStartAtMillis);
         } else {
            return 0L;
         }
      }
   }

   public static void refreshVolume() {
      GainDevice.refreshVolume();
   }

   private static long awaitPlayable(PlayPacket expected) throws InterruptedException {
      while (true) {
         long wait;
         synchronized (LOCK) {
            while (currentSong == expected && paused) {
               LOCK.wait();
            }

            if (currentSong != expected) {
               throw new CancellationException();
            }

            wait = timelineStartAtMillis - System.currentTimeMillis();
            if (wait <= 0L) {
               return timelineStartAtMillis;
            }
         }

         Thread.sleep(Math.min(wait, 50L));
      }
   }

   private static void playWithReconnect(PlayPacket localSong) throws Exception {
      int networkRetries = 0;
      int channelRestarts = 0;
      boolean firstAttempt = true;
      boolean recoveringChannel = false;

      while (true) {
         AdvancedPlayer player = null;

         try {
            String resolvedUrl = VendorRegistry.resolveStreamUrl(
                  MusicVendor.byTag(localSong.provider()), localSong.songId(), publicAudioUrl(localSong.provider(), localSong.songId()));
            InputStream stream = new BufferedInputStream(openTrusted(resolvedUrl), 65536);

            try {
               awaitPlayable(localSong);
               player = new AdvancedPlayer(stream, new GainDevice(() -> currentSong == localSong));
               synchronized (LOCK) {
                  if (currentSong != localSong) {
                     throw new CancellationException();
                  }

                  activePlayer = player;
               }

               if (firstAttempt) {
                  showMessage(Component.translatable("yunyy.msg.playing", localSong.title()).getString());
               } else {
                  showMessage(
                     Component.translatable(recoveringChannel ? "yunyy.msg.channel_recovered" : "yunyy.msg.audio_reconnected", localSong.title()).getString()
                  );
               }

               player.play();
            } catch (Throwable var28) {
               try {
                  stream.close();
               } catch (Throwable var25) {
                  var28.addSuppressed(var25);
               }

               throw var28;
            }

            stream.close();
            if (currentSong == localSong && localSong.durationMillis() > 0L && elapsedMillis() + 5000L < localSong.durationMillis()) {
               playbackFailed = true;
               showMessage(Component.translatable("yunyy.msg.audio_early_end").getString());
            }
         } catch (Exception var29) {
            Throwable cause = unwrapDecoderException(var29);
            if (currentSong == localSong && !(cause instanceof CancellationException)) {
               if (!hasPlaybackTimeRemaining(localSong)) {
                  throw var29;
               }

               if (cause instanceof GainDevice.AudioChannelResetException) {
                  if (channelRestarts >= MAX_AUDIO_CHANNEL_RESTARTS) {
                     throw var29;
                  }

                  channelRestarts++;
                  recoveringChannel = true;
                  firstAttempt = false;
                  TraceLog.info("Minecraft 声音系统已重置，正在恢复云音乐声道（{}/{}）", channelRestarts, 16);
                  showMessage(Component.translatable("yunyy.msg.audio_reset").getString());
                  Thread.sleep(150L);
                  continue;
               }

               if (isTransientNetworkFailure(cause) && networkRetries < MAX_STREAM_RETRIES) {
                  networkRetries++;
                  recoveringChannel = false;
                  firstAttempt = false;
                  TraceLog.warn("音频流中断，准备自动重连（{}/{}）：{}", new Object[]{networkRetries, 3, describe(cause)});
                  showMessage(Component.translatable("yunyy.msg.audio_reconnecting", networkRetries).getString());
                  Thread.sleep(500L * networkRetries);
                  continue;
               }

               throw var29;
            }

            throw new CancellationException();
         } finally {
            if (player != null) {
               synchronized (LOCK) {
                  if (activePlayer == player) {
                     activePlayer = null;
                  }
               }

               player.close();
            }
         }

         return;
      }
   }

   private static boolean hasPlaybackTimeRemaining(PlayPacket song) {
      return song.durationMillis() <= 0L || elapsedMillis() + 5000L < song.durationMillis();
   }

   private static boolean isTransientNetworkFailure(Throwable throwable) {
      if (throwable instanceof SocketTimeoutException || throwable instanceof SocketException || throwable instanceof EOFException) {
         return true;
      } else if (!(throwable instanceof IOException)) {
         return false;
      } else {
         String message = throwable.getMessage();
         if (message == null) {
            return true;
         } else {
            String normalized = message.toLowerCase();
            return normalized.contains("reset")
               || normalized.contains("timed out")
               || normalized.contains("closed")
               || normalized.contains("eof")
               || normalized.contains("broken pipe");
         }
      }
   }

   private static void closePlayerAsync(AdvancedPlayer player) {
      if (player != null) {
         AUDIO_CONTROL_THREAD.execute(() -> {
            try {
               player.close();
            } catch (Exception var2) {
               TraceLog.debug("后台关闭旧音频播放器失败", var2);
            }
         });
      }
   }

   private static void showMessage(String text) {
      Minecraft client = Minecraft.getInstance();
      client.execute(() -> ClientI18n.send(client.player, Component.literal(text)));
   }

   private static Throwable unwrapDecoderException(Throwable throwable) {
      Throwable current = throwable;

      for (int depth = 0; depth < 12; depth++) {
         Throwable nested = current instanceof JavaLayerException javaLayer ? javaLayer.getException() : current.getCause();
         if (nested == null || nested == current) {
            return current;
         }

         current = nested;
      }

      return current;
   }

   private static String describe(Throwable throwable) {
      return Redactor.throwableMessage(throwable);
   }

   private static InputStream openTrusted(String url) throws Exception {
      URI current = parseMusicUri(url);

      for (int redirects = 0; redirects <= 5; redirects++) {
         validateMusicUri(current);
         HttpURLConnection connection = connect(current, false, true, false);
         int status = connection.getResponseCode();
         if (status == 403 && isAudioCdnHost(current.getHost())) {
            connection.disconnect();
            connection = connect(current, true, true, false);
            status = connection.getResponseCode();
         }

         if (status == 403 && isAudioCdnHost(current.getHost())) {
            connection.disconnect();
            connection = connect(current, false, false, false);
            status = connection.getResponseCode();
         }

         if (status == 403 && "http".equalsIgnoreCase(current.getScheme()) && isAudioCdnHost(current.getHost())) {
            connection.disconnect();
            URI httpsVariant = withScheme(current, "https");
            connection = connect(httpsVariant, false, false, false);
            status = connection.getResponseCode();
            if (isAudioSuccess(status)) {
               current = httpsVariant;
            }
         }

         if (status == 403 && isAudioCdnHost(current.getHost()) && AudioBridgeOptions.on()) {
            connection.disconnect();
            connection = connect(current, false, true, false, AudioBridgeOptions.proxy());
            status = connection.getResponseCode();
            if (status == 403) {
               connection.disconnect();
               connection = connect(current, false, false, false, AudioBridgeOptions.proxy());
               status = connection.getResponseCode();
            }

            if (isAudioSuccess(status)) {
               TraceLog.info("音频 CDN 直连被拒绝，已通过客户端音频代理连接");
            }
         }

         if (status < 300 || status >= 400) {
            if (!isAudioSuccess(status)) {
               connection.disconnect();
               if (status == 403) {
                  TraceLog.warn("音频 CDN 拒绝请求：host={}, scheme={}, status={}", new Object[]{current.getHost(), current.getScheme(), status});
                  throw new VendorException(VendorError.NO_STREAM_PERMISSION, status);
               } else {
                  throw new VendorException(VendorError.STREAM_RESOLVE_FAILED, status);
               }
            } else {
               return connection.getInputStream();
            }
         }

         String location = connection.getHeaderField("Location");
         connection.disconnect();
         if (location == null) {
            throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
         }

         current = current.resolve(location);
         if (isNotFoundRedirect(current)) {
            throw new VendorException(VendorError.NO_STREAM_PERMISSION);
         }
      }

      throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
   }

   private static boolean isAudioSuccess(int status) {
      return status == 200 || status == 206;
   }

   private static HttpURLConnection connect(URI uri, boolean includeReferer, boolean includeRange, boolean includeCookie) throws Exception {
      return connect(uri, includeReferer, includeRange, includeCookie, Proxy.NO_PROXY);
   }

   private static HttpURLConnection connect(URI uri, boolean includeReferer, boolean includeRange, boolean includeCookie, Proxy proxy) throws Exception {
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection(proxy);
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(60000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty(
         "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
      );
      connection.setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.8");
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (includeRange) {
         connection.setRequestProperty("Range", "bytes=0-");
      }

      if (includeReferer) {
         connection.setRequestProperty("Referer", "https://music.163.com/");
      }

      return connection;
   }

   private static URI withScheme(URI uri, String scheme) throws Exception {
      return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
   }

   private static void validateMusicUri(URI uri) {
      String host = uri.getHost();
      boolean publicIpv4 = isPublicIpv4Literal(host);
      boolean trustedHost = isTrustedMusicHost(host);
      int port = uri.getPort();
      boolean standardPort = port == -1 || port == 80 || port == 443;
      boolean trustedPort = standardPort || publicIpv4 && port >= 1 && port <= 65535;
      boolean trustedScheme = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()) && isAudioCdnHost(host);
      if (!trustedScheme || !trustedHost || !trustedPort) {
         String scheme = uri.getScheme() == null ? "missing" : uri.getScheme();
         String safeHost = host == null ? "missing" : host;
         TraceLog.warn(
               "拒绝不受信任的音频地址：scheme={}, host={}, port={}, trustedScheme={}, trustedHost={}, trustedPort={}",
               new Object[]{scheme, safeHost, port, trustedScheme, trustedHost, trustedPort}
            );
         throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
      }
   }

   private static URI parseMusicUri(String url) {
      if (url != null && !url.isBlank() && !"null".equalsIgnoreCase(url.trim())) {
         String normalized = url.trim();
         if (normalized.startsWith("//")) {
            normalized = "https:" + normalized;
         }

         try {
            return URI.create(normalized);
         } catch (IllegalArgumentException var3) {
            throw new VendorException(VendorError.STREAM_RESOLVE_FAILED, var3);
         }
      } else {
         throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
      }
   }

   private static String publicAudioUrl(String provider, String songId) {
      String prov = provider == null ? "netease" : provider;
      if (songId == null || songId.isBlank()) {
         throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
      }
      return switch (prov) {
         case "kugou" -> "https://trackerlite.kugou.com/i/ipredirect?hash=" + songId + "&plat=0&version=8990&album_audio_id=0";
         case "qq" -> "";
         default -> {
            if (songId.matches("[1-9][0-9]{0,18}")) {
               yield "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
            } else {
               throw new VendorException(VendorError.STREAM_RESOLVE_FAILED);
            }
         }
      };
   }
   private static boolean isTrustedMusicHost(String host) {
      return host != null
         && (
            host.equalsIgnoreCase("music.163.com")
               || host.toLowerCase().endsWith(".music.163.com")
               || host.equalsIgnoreCase("music.126.net")
               || host.toLowerCase().endsWith(".music.126.net")
               || host.toLowerCase().endsWith(".kugou.com")
               || host.toLowerCase().endsWith(".kqcdn.com")
               || host.toLowerCase().endsWith(".gtimg.cn")
               || host.toLowerCase().endsWith(".qq.com")
               || host.toLowerCase().endsWith(".music.tc.qq.com")
               || isPublicIpv4Literal(host)
         );
   }

   private static boolean isAudioCdnHost(String host) {
      return host != null
         && (
            host.equalsIgnoreCase("music.126.net")
               || host.toLowerCase().endsWith(".music.126.net")
               || host.toLowerCase().endsWith(".music.163.com")
               || host.toLowerCase().endsWith(".kugou.com")
               || host.toLowerCase().endsWith(".kqcdn.com")
               || host.toLowerCase().endsWith(".gtimg.cn")
               || host.toLowerCase().endsWith(".qq.com")
               || host.toLowerCase().endsWith(".music.tc.qq.com")
               || host.equalsIgnoreCase("tracker.kugou.com")
               || host.equalsIgnoreCase("trackerlite.kugou.com")
               || isPublicIpv4Literal(host)
         );
   }

   private static boolean isPublicIpv4Literal(String host) {
      if (host != null && host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
         String[] parts = host.split("\\.");
         int[] octets = new int[4];

         try {
            for (int i = 0; i < 4; i++) {
               octets[i] = Integer.parseInt(parts[i]);
               if (octets[i] > 255) {
                  return false;
               }
            }
         } catch (NumberFormatException var6) {
            return false;
         }

         int first = octets[0];
         int second = octets[1];
         int third = octets[2];
         if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
         } else if (first == 100 && second >= 64 && second <= 127) {
            return false;
         } else if (first == 169 && second == 254) {
            return false;
         } else if (first == 172 && second >= 16 && second <= 31) {
            return false;
         } else if (first == 192 && second == 168) {
            return false;
         } else if (first != 192 || second != 0 || third != 0 && third != 2) {
            if (first != 198 || second != 18 && second != 19) {
               return first == 198 && second == 51 && third == 100 ? false : first != 203 || second != 0 || third != 113;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean isNotFoundRedirect(URI uri) {
      return "music.163.com".equalsIgnoreCase(uri.getHost()) && ("/404".equals(uri.getPath()) || "/404/".equals(uri.getPath()));
   }
}
