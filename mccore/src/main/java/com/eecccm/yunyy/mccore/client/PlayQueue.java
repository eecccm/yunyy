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
import com.eecccm.yunyy.core.account.AccountSessionStore;
import com.eecccm.yunyy.mccore.net.QueuePacket;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

public final class PlayQueue {
   private static volatile List<PlayQueue.Song> songs = List.of();
   private static volatile int mode;
   private static volatile boolean hasPrevious;

   private PlayQueue() {
   }

   public static void update(QueuePacket payload) {
      try {
         JsonObject root = JsonParser.parseString(payload.json()).getAsJsonObject();
         mode = root.has("mode") ? root.get("mode").getAsInt() : 0;
         hasPrevious = root.has("hasPrevious") && root.get("hasPrevious").getAsBoolean();
         JsonArray array = root.getAsJsonArray("songs");
         List<PlayQueue.Song> parsed = new ArrayList<>();
         if (array != null) {
            for (JsonElement value : array) {
               JsonObject item = value.getAsJsonObject();
               parsed.add(
                  new PlayQueue.Song(
                     item.get("id").getAsString(),
                     item.get("title").getAsString(),
                     item.get("artist").getAsString(),
                     item.get("duration").getAsLong(),
                     item.get("requester").getAsString()
                  )
               );
            }
         }

         songs = List.copyOf(parsed);
         prepareNext();
      } catch (Exception var7) {
         clear();
      }
   }

   public static void clear() {
      songs = List.of();
      mode = 0;
      hasPrevious = false;
   }

   public static boolean hasPrevious() {
      return hasPrevious;
   }

   public static void setHasPrevious(boolean value) {
      hasPrevious = value;
   }

   public static void updateLocal(List<PlayQueue.Song> localSongs, int localMode) {
      songs = List.copyOf(localSongs);
      mode = localMode;
      prepareNext();
   }

   private static void prepareNext() {
      if (!songs.isEmpty()) {
         AccountSessionStore.warmStreamUrl(songs.getFirst().id());
      }
   }

   public static boolean hasNext() {
      return !songs.isEmpty();
   }

   public static String nextTitle() {
      return hasNext() ? songs.getFirst().title() : "";
   }

   public static String nextArtist() {
      return hasNext() ? songs.getFirst().artist() : "";
   }

   public static int queuedSongs() {
      return songs.size();
   }

   public static List<PlayQueue.Song> songs() {
      return songs;
   }

   public static int mode() {
      return mode;
   }

   public static boolean contains(String songId) {
      return PlaybackEngine.currentSong() != null && PlaybackEngine.currentSong().songId().equals(songId)
         ? true
         : songs.stream().anyMatch(song -> song.id().equals(songId));
   }

   public static String modeKey() {
      return switch (mode) {
         case 1 -> "yunyy.mode.list";
         case 2 -> "yunyy.mode.single";
         case 3 -> "yunyy.mode.shuffle";
         default -> "yunyy.mode.seq";
      };
   }

   public static String modeName() {
      return Component.translatable(modeKey()).getString();
   }

   record Song(String id, String title, String artist, long duration, String requester) {
   }
}
