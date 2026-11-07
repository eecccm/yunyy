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
import com.eecccm.yunyy.core.model.SongEntry;
import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.core.history.ListenLog;
import com.eecccm.yunyy.mccore.util.DisplayNames;

import com.eecccm.yunyy.core.history.ListenLog;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public final class SoloPlayback {
   private static final int MAX_QUEUE_SIZE = 500;
   private static final Deque<SoloPlayback.SongEntry> QUEUE = new ArrayDeque<>();
   private static final ListenLog<SoloPlayback.SongEntry> HISTORY = new ListenLog<>(SoloPlayback.SongEntry::id);
   private static SoloPlayback.SongEntry current;
   private static PlayPacket currentPacket;
   private static long pausedOffset;
   private static int mode;
   private static boolean active;
   private static boolean paused;

   private SoloPlayback() {
   }

   public static void activate() {
      if (active) {
         return; // 幂等：已激活时不重复 start，避免重播当前歌曲
      }
      active = true;
      if (current != null) {
         start(current, pausedOffset);
      } else if (!QUEUE.isEmpty()) {
         start(takeNext(), 0L);
      }

      syncQueue();
   }

   public static void deactivate() {
      deactivate(true);
   }

   public static void deactivateAfterServerStop() {
      deactivate(false);
   }

   public static void beforeExternalStop() {
      if (active && currentPacket != null && PlaybackEngine.currentSong() != null) {
         pausedOffset = PlaybackEngine.elapsedMillis();
      }
   }

   private static void deactivate(boolean stopAudio) {
      if (active) {
         active = false;
         if (currentPacket != null && PlaybackEngine.currentSong() != null) {
            pausedOffset = PlaybackEngine.elapsedMillis();
         }

         if (stopAudio) {
            PlaybackEngine.stop();
         }
      }
   }

   public static void reset() {
      active = false;
      current = null;
      currentPacket = null;
      pausedOffset = 0L;
      mode = 0;
      paused = false;
      QUEUE.clear();
      HISTORY.wipe();
   }

   public static void tick() {
      if (active && !paused && current != null && currentPacket != null) {
         boolean durationEnded = current.duration > 0L && PlaybackEngine.elapsedMillis() >= current.duration;
         // currentSong 被清空（含播放失败后）即视为本曲结束，避免失败曲目残留在 current 导致后续只入队不播放
         if (durationEnded || PlaybackEngine.currentSong() == null) {
            finished();
         }
      }
   }

   public static void add(AccountSessionStore.SongMeta song) {
      add(new SoloPlayback.SongEntry(song.id(), song.title(), song.artist(), song.duration(), song.coverUrl(), "netease", requester()));
   }

   public static void add(com.eecccm.yunyy.core.model.SongEntry song) {
      add(new SoloPlayback.SongEntry(song.songId(), song.name(), song.performer(), song.lengthMs(), song.coverUri(), song.vendor().tag, requester()));
   }

   public static void addAll(List<AccountSessionStore.SongMeta> songs) {
      int added = 0;

      for (AccountSessionStore.SongMeta song : songs) {
         if (size() < 500 && !contains("netease", song.id())) {
            SoloPlayback.SongEntry track = new SoloPlayback.SongEntry(song.id(), song.title(), song.artist(), song.duration(), song.coverUrl(), "netease", requester());
            if (current == null && added == 0) {
               start(track, 0L);
            } else {
               QUEUE.addLast(track);
            }

            added++;
         }
      }

      syncQueue();
   }

   public static void addAll(com.eecccm.yunyy.core.model.SongEntry first, List<com.eecccm.yunyy.core.model.SongEntry> rest) {
      add(first);
      int added = 0;

      for (com.eecccm.yunyy.core.model.SongEntry song : rest) {
         if (size() < 500 && !contains(song.vendor().tag, song.songId())) {
            SoloPlayback.SongEntry track = new SoloPlayback.SongEntry(song.songId(), song.name(), song.performer(), song.lengthMs(), song.coverUri(), song.vendor().tag, requester());
            QUEUE.addLast(track);
            added++;
         }
      }

      syncQueue();
   }

   private static void add(SoloPlayback.SongEntry track) {
      if (contains(track.provider, track.id)) {
         message(Component.translatable("yunyy.msg.already_queued"));
      } else if (size() >= 500) {
         message(Component.translatable("yunyy.msg.queue_full", MAX_QUEUE_SIZE));
      } else {
         if (current == null) {
            start(track, 0L);
         } else {
            QUEUE.addLast(track);
            syncQueue();
            message(Component.translatable("yunyy.msg.personal_added_song", track.title, DisplayNames.artist(track.artist)));
         }
      }
   }

   public static void next() {
      if (QUEUE.isEmpty()) {
         boolean wasPlaying = current != null;
         HISTORY.note(current);
         current = null;
         currentPacket = null;
         pausedOffset = 0L;
         paused = false;
         PlaybackEngine.stop();
         syncQueue();
         message(wasPlaying ? Component.translatable("yunyy.msg.no_next_stopped") : Component.translatable("yunyy.msg.no_playing"));
      } else {
         if (mode == 1 && current != null && QUEUE.size() < 500) {
            QUEUE.addLast(current);
         }

         start(takeNext(), 0L);
      }
   }

   public static void clear() {
      HISTORY.wipe();
      boolean hadSongs = current != null || !QUEUE.isEmpty();
      current = null;
      currentPacket = null;
      pausedOffset = 0L;
      paused = false;
      QUEUE.clear();
      PlaybackEngine.stop();
      syncQueue();
      message(hadSongs ? Component.translatable("yunyy.msg.personal_cleared") : Component.translatable("yunyy.msg.empty_already"));
   }

   public static void queueAction(int action, int index) {
      if (action == 4) {
         SoloPlayback.SongEntry previous = HISTORY.rewind(current, QUEUE, 499);
         if (previous == null) {
            message(Component.translatable("yunyy.msg.no_previous"));
         } else {
            current = null;
            start(previous, 0L);
         }
      } else if (action != 2 && action != 3) {
         if (action == 1 || action == 0) {
            if (index >= 0 && index < QUEUE.size()) {
               Iterator<SoloPlayback.SongEntry> iterator = QUEUE.iterator();
               SoloPlayback.SongEntry selected = null;

               for (int i = 0; iterator.hasNext(); i++) {
                  SoloPlayback.SongEntry track = iterator.next();
                  if (i == index) {
                     selected = track;
                     iterator.remove();
                     break;
                  }
               }

               if (selected != null) {
                  if (action == 1) {
                     if (mode == 1 && current != null && QUEUE.size() < 500) {
                        QUEUE.addLast(current);
                     }

                     start(selected, 0L);
                  } else if (action == 0) {
                     syncQueue();
                  } else {
                     QUEUE.addFirst(selected);
                  }
               }
            }
         }
      } else {
         if (ListenLog.reorder(QUEUE, index, action == 2 ? -1 : 1)) {
            syncQueue();
         }
      }
   }

   public static void setMode(int value) {
      if (value >= 0 && value <= 3) {
         mode = value;
         syncQueue();
      }
   }

   public static void togglePause() {
      if (current != null && PlaybackEngine.currentSong() != null) {
         paused = !paused;
         PlaybackEngine.setPaused(paused);
         TransportScreen.refresh();
         message(paused ? Component.translatable("yunyy.msg.personal_paused") : Component.translatable("yunyy.msg.personal_resumed"));
      } else {
         message(Component.translatable("yunyy.msg.nothing_to_pause"));
      }
   }

   private static void finished() {
      SoloPlayback.SongEntry finished = current;
      current = null;
      currentPacket = null;
      pausedOffset = 0L;
      if (mode == 2 && finished != null) {
         start(finished, 0L);
      } else {
         if (mode == 1 && finished != null && QUEUE.size() < 500) {
            QUEUE.addLast(finished);
         }

         SoloPlayback.SongEntry next = takeNext();
         if (next == null) {
            HISTORY.note(finished);
         } else {
            HISTORY.advance(finished, next);
         }

         if (next != null) {
            start(next, 0L);
         } else {
            syncQueue();
         }
      }
   }

   private static void start(SoloPlayback.SongEntry track, long offset) {
      if (track != null) {
         HISTORY.advance(current, track);
         paused = false;
         current = track;
         pausedOffset = Math.max(0L, offset);
         long startAt = pausedOffset == 0L ? System.currentTimeMillis() + 300L : System.currentTimeMillis() - pausedOffset;
         long sentAt = System.currentTimeMillis();
         currentPacket = new PlayPacket(
            track.id,
            track.title,
            track.artist,
            "https://music.163.com/song/media/outer/url?id=" + track.id + ".mp3",
            track.cover,
            startAt,
            sentAt,
            track.duration,
            track.provider
         );
         if (active) {
            PlaybackEngine.play(currentPacket);
         }

         syncQueue();
      }
   }

   private static SoloPlayback.SongEntry takeNext() {
      if (QUEUE.isEmpty()) {
         return null;
      } else if (mode == 3 && QUEUE.size() != 1) {
         int target = ThreadLocalRandom.current().nextInt(QUEUE.size());
         Iterator<SoloPlayback.SongEntry> iterator = QUEUE.iterator();

         for (int i = 0; iterator.hasNext(); i++) {
            SoloPlayback.SongEntry track = iterator.next();
            if (i == target) {
               iterator.remove();
               return track;
            }
         }

         return QUEUE.pollFirst();
      } else {
         return QUEUE.pollFirst();
      }
   }

   private static boolean contains(String provider, String id) {
      String prov = provider == null ? "netease" : provider;
      return (current != null && prov.equals(current.provider) && current.id.equals(id))
         || QUEUE.stream().anyMatch(track -> prov.equals(track.provider) && track.id.equals(id));
   }

   private static int size() {
      return QUEUE.size() + (current == null ? 0 : 1);
   }

   private static void syncQueue() {
      List<PlayQueue.Song> songs = new ArrayList<>();

      for (SoloPlayback.SongEntry track : QUEUE) {
         songs.add(new PlayQueue.Song(track.id, track.title, track.artist, track.duration, track.requester));
      }

      PlayQueue.updateLocal(songs, mode);
      PlayQueue.setHasPrevious(HISTORY.canRecall());
      TransportScreen.refresh();
   }

   private static String requester() {
      LocalPlayer player = Minecraft.getInstance().player;
      return player == null ? Component.translatable("yunyy.requester.self").getString() : player.getName().getString();
   }

   private static void message(Component component) {
      Minecraft client = Minecraft.getInstance();
      ClientI18n.send(client.player, component);
   }

   private static void message(String text) {
      message(Component.literal(text));
   }

   private record SongEntry(String id, String title, String artist, long duration, String cover, String provider, String requester) {
   }
}
