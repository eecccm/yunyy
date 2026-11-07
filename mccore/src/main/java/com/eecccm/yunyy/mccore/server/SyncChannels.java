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

package com.eecccm.yunyy.mccore.server;

import com.eecccm.yunyy.mccore.net.ListeningStatePacket;
import com.eecccm.yunyy.mccore.net.StopPacket;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 收听频道：每个玩家独立选择「全服同步」或「个人播放」。
 * 默认个人播放；切到全服同步后由服务端统一推进队列。
 */
public final class SyncChannels {
   public static final int SHARED = 0;
   public static final int SOLO = 1;
   private static final Map<UUID, Integer> CHANNELS = new ConcurrentHashMap<>();

   private SyncChannels() {
   }

   static boolean shared(ServerPlayer player) {
      return CHANNELS.getOrDefault(player.getUUID(), SOLO) == SHARED;
   }

   static boolean solo(ServerPlayer player) {
      return CHANNELS.getOrDefault(player.getUUID(), SOLO) == SOLO;
   }

   static List<ServerPlayer> sharedListeners(MinecraftServer server) {
      return server.getPlayerList().getPlayers().stream().filter(SyncChannels::shared).toList();
   }

   static void onJoin(MinecraftServer server, ServerPlayer player) {
      CHANNELS.put(player.getUUID(), SOLO);
      pushState(player);
   }

   static void onLeave(MinecraftServer server, ServerPlayer player) {
      CHANNELS.remove(player.getUUID());
   }

   public static void dispatch(MinecraftServer server, ServerPlayer player, int action, String value) {
      switch (action) {
         case 0 -> enterShared(player);
         case 1 -> enterSolo(player);
         default -> player.sendSystemMessage(Component.translatable("yunyy.msg.mode_unsupported"));
      }
   }

   static boolean rejectForPersonal(MinecraftServer server, ServerPlayer requester, ServerCore.SongEntryBrief song) {
      if (!solo(requester)) {
         return false;
      }
      requester.sendSystemMessage(Component.translatable("yunyy.msg.personal_should_client"));
      return true;
   }

   static boolean rejectPlaylistFolderForPersonal(MinecraftServer server, ServerPlayer requester, List<ServerCore.SongEntryBrief> songs) {
      if (!solo(requester)) {
         return false;
      }
      requester.sendSystemMessage(Component.translatable("yunyy.msg.personal_playlist_should_client"));
      return true;
   }

   static boolean soloOnly(MinecraftServer server, ServerPlayer requester) {
      return solo(requester);
   }

   static boolean soloOnly(MinecraftServer server, ServerPlayer requester, int action, int index) {
      return solo(requester);
   }

   static boolean soloOnly(ServerPlayer player) {
      return solo(player);
   }

   static void onTick(MinecraftServer server) {
   }

   private static void enterShared(ServerPlayer player) {
      if (shared(player)) {
         pushState(player);
         player.sendSystemMessage(Component.translatable("yunyy.msg.already_sync"));
      } else {
         CHANNELS.put(player.getUUID(), SHARED);
         ServerCore.dispatch(player, new StopPacket());
         pushState(player);
         ServerCore.resyncPlayer(player);
         player.sendSystemMessage(Component.translatable("yunyy.msg.switched_sync"));
      }
   }

   private static void enterSolo(ServerPlayer player) {
      if (solo(player)) {
         pushState(player);
         player.sendSystemMessage(Component.translatable("yunyy.msg.already_personal"));
      } else {
         CHANNELS.put(player.getUUID(), SOLO);
         ServerCore.dispatch(player, new StopPacket());
         pushState(player);
         player.sendSystemMessage(Component.translatable("yunyy.msg.switched_personal"));
      }
   }

   private static void pushState(ServerPlayer player) {
      JsonObject root = new JsonObject();
      root.addProperty("mode", CHANNELS.getOrDefault(player.getUUID(), SOLO));
      ServerCore.dispatch(player, new ListeningStatePacket(root.toString()));
   }
}
