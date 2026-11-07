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

import com.eecccm.yunyy.core.config.ServerPolicy;
import com.eecccm.yunyy.core.history.ListenLog;
import com.eecccm.yunyy.core.history.VipAuditLog;
import com.eecccm.yunyy.core.log.TraceLog;
import com.eecccm.yunyy.mccore.client.AccessControl;
import com.eecccm.yunyy.mccore.util.DisplayNames;
import com.eecccm.yunyy.mccore.net.AuthPacket;
import com.eecccm.yunyy.mccore.net.HudPacket;
import com.eecccm.yunyy.mccore.net.MenuOpenPacket;
import com.eecccm.yunyy.mccore.net.PauseStatePacket;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.net.PlaylistOpenPacket;
import com.eecccm.yunyy.mccore.net.QueuePacket;
import com.eecccm.yunyy.mccore.net.SearchOpenPacket;
import com.eecccm.yunyy.mccore.net.StopPacket;
import com.eecccm.yunyy.mccore.net.VolumePacket;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 多加载器共享服务端核心：点歌队列状态机、收听频道、命令业务逻辑。
 * 不依赖任何加载器 API；命令注册、网络通道、生命周期由各加载器入口接入。
 */
public final class ServerCore {
   public static final String MOD_ID = "yunyy";
   private static final AtomicReference<PlayPacket> ACTIVE_SONG = new AtomicReference<>();
   private static final AtomicReference<QueueItem> ACTIVE_ITEM = new AtomicReference<>();
   private static final Deque<QueueItem> QUEUE = new ArrayDeque<>();
   private static final ListenLog<QueueItem> PAST = new ListenLog<>(item -> item.track().songId());
   private static final Map<UUID, Long> IMPORT_GATE = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> SELECT_GATE = new ConcurrentHashMap<>();
   private static volatile int MODE;
   private static volatile boolean PAUSED;
   private static volatile long PAUSE_STAMP;

   /** 服务端网络发送抽象：由各加载器注入实现。 */
   public interface Net {
      void send(ServerPlayer player, CustomPacketPayload payload);
   }

   public static Net net;

   private ServerCore() {
   }

   public static void reset() {
      ACTIVE_SONG.set(null);
      ACTIVE_ITEM.set(null);
      QUEUE.clear();
      PAST.wipe();
      MODE = 0;
      PAUSED = false;
      PAUSE_STAMP = 0L;
      IMPORT_GATE.clear();
      SELECT_GATE.clear();
   }

   /** 每服务端 tick 调用（由各加载器 tick 事件接入）。 */
   public static void onTick(MinecraftServer server) {
      advanceQueue(server);
      SyncChannels.onTick(server);
   }

   /** 玩家加入（由各加载器连接事件接入）。 */
   public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
      SyncChannels.onJoin(server, player);
      resyncPlayer(player);
   }

   /** 玩家断开（由各加载器断开事件接入）。 */
   public static void onPlayerLeave(MinecraftServer server, ServerPlayer player) {
      SyncChannels.onLeave(server, player);
   }

   /** 构建 /music 命令树（由各加载器命令注册事件接入）。 */
   public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
      return Commands.literal("music")
         .executes(context -> openMenu((context.getSource()).getPlayerOrException()))
         // 平台前缀：wyy=网易云音乐，kg=酷狗音乐，qq=QQ音乐。涉及具体平台的指令必须带前缀。
         .then(platformBranch("wyy"))
         .then(platformBranch("kg"))
         .then(platformBranch("qq"))
         .then(Commands.literal("next").executes(context -> playNext((context.getSource()).getServer(), (context.getSource()).getPlayerOrException())))
         .then(Commands.literal("pause").executes(context -> flipPause((context.getSource()).getServer(), (context.getSource()).getPlayerOrException())))
         .then(Commands.literal("now").executes(context -> reportNow((context.getSource()).getPlayerOrException())))
         .then(
            Commands.literal("volume")
               .executes(context -> adjustVolume((context.getSource()).getPlayerOrException(), 0, 0))
               .then(
                  Commands.argument("volume", IntegerArgumentType.integer(0, 100))
                     .executes(
                        context -> adjustVolume(
                           (context.getSource()).getPlayerOrException(),
                           1,
                           IntegerArgumentType.getInteger(context, "volume")
                        )
                     )
               )
         )
         .then(Commands.literal("mute").executes(context -> adjustVolume((context.getSource()).getPlayerOrException(), 2, 0)))
         .then(Commands.literal("hud").executes(context -> openHud((context.getSource()).getPlayerOrException())));
   }

   /** 按平台构建 /music wyy|kg 子命令分支（play/login/account/logout/playlist）。 */
   private static LiteralArgumentBuilder<CommandSourceStack> platformBranch(String provider) {
      return Commands.literal(provider)
         .then(
            Commands.literal("play")
               .then(
                  Commands.argument("query", StringArgumentType.greedyString())
                     .executes(
                        context -> beginSearch(
                           (context.getSource()).getPlayerOrException(),
                           provider,
                           StringArgumentType.getString(context, "query")
                        )
                     )
               )
         )
         .then(Commands.literal("login").executes(context -> openAuth((context.getSource()).getPlayerOrException(), 0, provider)))
         .then(Commands.literal("account").executes(context -> openAuth((context.getSource()).getPlayerOrException(), 1, provider)))
         .then(Commands.literal("logout").executes(context -> openAuth((context.getSource()).getPlayerOrException(), 2, provider)))
         .then(Commands.literal("playlist").executes(context -> openPlaylistFolder((context.getSource()).getPlayerOrException(), provider)));
   }

   public static void dispatch(ServerPlayer player, CustomPacketPayload payload) {
      if (net != null) {
         net.send(player, payload);
      }
   }

   private static int beginSearch(ServerPlayer requester, String provider, String input) {
      String query = input == null ? "" : input.trim();
      if (query.isEmpty()) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.search_empty"));
         return 0;
      }
      if (query.length() > 80) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.search_too_long"));
         return 0;
      }
      dispatch(requester, new SearchOpenPacket(query, provider));
      return 1;
   }

   public static int acceptSelection(MinecraftServer server, ServerPlayer requester, String submittedJson) {
      long now = System.currentTimeMillis();
      if (now < SELECT_GATE.getOrDefault(requester.getUUID(), 0L)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.request_too_fast"));
         return 0;
      }
      SELECT_GATE.put(requester.getUUID(), now + ServerPolicy.searchCooldown);

      SongEntryBrief song;
      try {
         if (submittedJson == null || submittedJson.isBlank() || submittedJson.length() > 4096) {
            throw new IllegalArgumentException("invalid length");
         }
         song = decodeSongEntry(JsonParser.parseString(submittedJson).getAsJsonObject());
      } catch (RuntimeException e) {
         song = null;
      }

      if (song == null) {
         TraceLog.warn("玩家 {} 提交了无效的点歌数据", requester.getName().getString());
         requester.sendSystemMessage(Component.translatable("yunyy.msg.invalid_song"));
         return 0;
      }
      if (SyncChannels.rejectForPersonal(server, requester, song)) {
         return 1;
      }
      enqueue(server, requester, song);
      return 1;
   }

   private static void enqueue(MinecraftServer server, ServerPlayer requester, SongEntryBrief song) {
      advanceQueue(server);
      if (alreadyQueued(song.provider(), song.songId())) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.already_queued"));
         return;
      }
      QueueItem item = new QueueItem(song, requester.getUUID(), requester.getName().getString());
      PlayPacket current = ACTIVE_SONG.get();
      if (current != null && (PAUSED || !expired(current))) {
         if (QUEUE.size() >= ServerPolicy.queueCap) {
            requester.sendSystemMessage(Component.translatable("yunyy.msg.queue_full", ServerPolicy.queueCap));
            return;
         }
         if (countOwned(requester.getUUID()) >= ServerPolicy.maxPerPlayer) {
            requester.sendSystemMessage(Component.translatable("yunyy.msg.queue_player_full", ServerPolicy.maxPerPlayer));
            return;
         }
         QUEUE.addLast(item);
         VipAuditLog.record(song.provider(), requester.getName().getString(), song.songId(),
               VipAuditLog.SOURCE_QUEUE, VipAuditLog.RESULT_ALLOWED, "queued");
         syncQueueAll(server);
         for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
            player.sendSystemMessage(
               Component.translatable("yunyy.msg.added_to_queue", requester.getName().getString(), song.name(), DisplayNames.artistComponent(song.performer()), QUEUE.size())
            );
         }
      } else {
         launchSong(server, item, Component.translatable("yunyy.msg.request_play", requester.getName().getString()));
      }
   }

   /** 统计指定玩家当前在公共队列与当前播放中占用的曲目数（单人配额用）。 */
   private static int countOwned(UUID uuid) {
      if (uuid == null) {
         return 0;
      }
      int count = 0;
      QueueItem active = ACTIVE_ITEM.get();
      if (active != null && uuid.equals(active.requesterUuid())) {
         count++;
      }
      for (QueueItem item : QUEUE) {
         if (uuid.equals(item.requesterUuid())) {
            count++;
         }
      }
      return count;
   }

   public static int playNext(MinecraftServer server, ServerPlayer requester) {
      if (SyncChannels.soloOnly(server, requester)) {
         return 1;
      }
      if (ServerPolicy.controlNeedsAdmin && !AccessControl.isAdmin(requester)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_control", ServerPolicy.adminRank));
         return 0;
      }
      if (!QUEUE.isEmpty()) {
         if (MODE == 1 && ACTIVE_ITEM.get() != null && QUEUE.size() < ServerPolicy.queueCap) {
            QUEUE.addLast(ACTIVE_ITEM.get());
         }
         QueueItem next = pollNext();
         launchSong(server, next, Component.translatable("yunyy.msg.switched_next"));
         return 1;
      }
      boolean wasPlaying = ACTIVE_SONG.getAndSet(null) != null;
      PAST.note(ACTIVE_ITEM.getAndSet(null));
      PAUSED = false;
      PAUSE_STAMP = 0L;
      QueuePacket queue = buildQueuePacket();
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         dispatch(player, new StopPacket());
         dispatch(player, queue);
      }
      requester.sendSystemMessage(Component.translatable(wasPlaying ? "yunyy.msg.no_next_stopped" : "yunyy.msg.no_playing"));
      return wasPlaying ? 1 : 0;
   }

   public static int clearQueue(MinecraftServer server, ServerPlayer requester) {
      if (SyncChannels.soloOnly(server, requester)) {
         return 1;
      }
      if (!AccessControl.isAdmin(requester)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_clear", ServerPolicy.adminRank));
         return 0;
      }
      boolean hadSongs = ACTIVE_SONG.getAndSet(null) != null || !QUEUE.isEmpty();
      ACTIVE_ITEM.set(null);
      PAUSED = false;
      PAUSE_STAMP = 0L;
      QUEUE.clear();
      PAST.wipe();
      if (!hadSongs) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.empty_already"));
         return 0;
      }
      QueuePacket queue = buildQueuePacket();
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         dispatch(player, new StopPacket());
         dispatch(player, queue);
         player.sendSystemMessage(Component.translatable("yunyy.msg.cleared", requester.getName().getString()));
      }
      return 1;
   }

   public static int handleQueueAction(MinecraftServer server, ServerPlayer requester, int action, int index) {
      if (SyncChannels.soloOnly(server, requester, action, index)) {
         return 1;
      }
      boolean admin = AccessControl.isAdmin(requester);
      // 上一首、立即播放、上移、下移属于全局控制：受 controlNeedsAdmin 约束，仅管理员可执行。
      boolean controlAction = action == 1 || action == 2 || action == 3 || action == 4;
      if (controlAction && ServerPolicy.controlNeedsAdmin && !admin) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_control", ServerPolicy.adminRank));
         return 0;
      }
      if (action == 4) {
         QueueItem previous = PAST.rewind(ACTIVE_ITEM.get(), QUEUE, ServerPolicy.queueCap);
         if (previous == null) {
            requester.sendSystemMessage(Component.translatable("yunyy.msg.no_previous"));
            return 0;
         }
         ACTIVE_ITEM.set(null);
         launchSong(server, previous, Component.translatable("yunyy.msg.switched_previous"));
         return 1;
      }
      if (action != 2 && action != 3) {
         if (action != 1 && action != 0) {
            return 0;
         }
         if (index < 0 || index >= QUEUE.size()) {
            requester.sendSystemMessage(Component.translatable("yunyy.msg.not_in_queue"));
            return 0;
         }
         // 先定位目标曲目（移除前完成归属判定）：移除自己点的曲目允许，移除他人曲目需管理员。
         Iterator<QueueItem> iterator = QUEUE.iterator();
         QueueItem selected = null;
         for (int i = 0; iterator.hasNext(); i++) {
            QueueItem item = iterator.next();
            if (i == index) {
               selected = item;
               break;
            }
         }
         if (selected == null) {
            return 0;
         }
         if (action == 0 && !admin && !requester.getUUID().equals(selected.requesterUuid())) {
            requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_control", ServerPolicy.adminRank));
            return 0;
         }
         iterator.remove();
         if (action == 1) {
            if (MODE == 1 && ACTIVE_ITEM.get() != null && QUEUE.size() < ServerPolicy.queueCap) {
               QUEUE.addLast(ACTIVE_ITEM.get());
            }
            launchSong(server, selected, Component.translatable("yunyy.msg.playing_now", requester.getName().getString()));
         } else {
            syncQueueAll(server);
            for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
               player.sendSystemMessage(Component.translatable("yunyy.msg.removed_from_queue", requester.getName().getString(), selected.track().name()));
            }
         }
         return 1;
      }
      if (!ListenLog.reorder(QUEUE, index, action == 2 ? -1 : 1)) {
         return 0;
      }
      syncQueueAll(server);
      return 1;
   }

   public static int applyPlaybackMode(MinecraftServer server, ServerPlayer requester, int mode) {
      if (SyncChannels.soloOnly(server, requester)) {
         return 1;
      }
      if (ServerPolicy.modeSwitchNeedsAdmin && !AccessControl.isAdmin(requester)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_mode", ServerPolicy.adminRank));
         return 0;
      }
      if (mode < 0 || mode > 3) {
         return 0;
      }
      MODE = mode;
      syncQueueAll(server);
      String nameKey = switch (mode) {
         case 1 -> "yunyy.mode.list";
         case 2 -> "yunyy.mode.single";
         case 3 -> "yunyy.mode.shuffle";
         default -> "yunyy.mode.seq";
      };
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         player.sendSystemMessage(Component.translatable("yunyy.msg.mode_set", requester.getName().getString(), Component.translatable(nameKey)));
      }
      return 1;
   }

   public static int flipPause(MinecraftServer server, ServerPlayer requester) {
      if (SyncChannels.soloOnly(server, requester)) {
         return 1;
      }
      if (ServerPolicy.controlNeedsAdmin && !AccessControl.isAdmin(requester)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_control", ServerPolicy.adminRank));
         return 0;
      }
      PlayPacket current = ACTIVE_SONG.get();
      if (current == null || (!PAUSED && expired(current))) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.nothing_to_pause"));
         return 0;
      }
      long now = System.currentTimeMillis();
      if (!PAUSED) {
         PAUSED = true;
         PAUSE_STAMP = now;
      } else {
         ACTIVE_SONG.updateAndGet(song -> song == null ? null : song.shiftedBy(now - PAUSE_STAMP));
         PAUSED = false;
         PAUSE_STAMP = 0L;
      }
      PauseStatePacket state = new PauseStatePacket(PAUSED);
      String actionKey = PAUSED ? "yunyy.msg.paused" : "yunyy.msg.resumed";
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         dispatch(player, state);
         player.sendSystemMessage(Component.translatable(actionKey, requester.getName().getString()));
      }
      return 1;
   }

   private static int reportNow(ServerPlayer player) {
      if (SyncChannels.soloOnly(player)) {
         return 1;
      }
      PlayPacket current = ACTIVE_SONG.get();
      if (current != null && !PAUSED && expired(current)) {
         ACTIVE_SONG.compareAndSet(current, null);
         current = null;
      }
      if (current == null) {
         player.sendSystemMessage(Component.translatable("yunyy.msg.now_none"));
      } else {
         MutableComponent message = Component.translatable("yunyy.msg.now_playing", current.title());
         if (PAUSED) {
            message = message.append(Component.translatable("yunyy.msg.now_paused"));
         }
         if (!QUEUE.isEmpty()) {
            if (MODE == 3) {
               message = message.append(Component.translatable("yunyy.msg.next_random"));
            } else {
               message = message.append(Component.translatable("yunyy.msg.next_song", QUEUE.peekFirst().track().name()));
            }
         }
         player.sendSystemMessage(message);
      }
      return 1;
   }

   private static void advanceQueue(MinecraftServer server) {
      if (PAUSED) {
         return;
      }
      PlayPacket current = ACTIVE_SONG.get();
      if (current == null || !expired(current)) {
         return;
      }
      QueueItem finished = ACTIVE_ITEM.get();
      if (MODE == 2 && finished != null) {
         launchSong(server, finished, Component.translatable("yunyy.msg.loop_single"));
         return;
      }
      if (MODE == 1 && finished != null && QUEUE.size() < ServerPolicy.queueCap) {
         QUEUE.addLast(finished);
      }
      QueueItem next = pollNext();
      if (next == null) {
         PAST.note(finished);
         ACTIVE_SONG.compareAndSet(current, null);
         ACTIVE_ITEM.set(null);
         syncQueueAll(server);
      } else {
         launchSong(server, next, Component.translatable("yunyy.msg.auto_next"));
      }
   }

   private static void launchSong(MinecraftServer server, QueueItem item, Component prefix) {
      PAST.advance(ACTIVE_ITEM.get(), item);
      SongEntryBrief song = item.track();
      String url = fallbackStreamUrl(song.provider(), song.songId());
      long now = System.currentTimeMillis();
      PlayPacket payload = new PlayPacket(song.songId(), song.name(), song.performer(), url, song.coverUri(), now + 2500L, now, song.lengthMs(), song.provider());
      ACTIVE_SONG.set(payload);
      ACTIVE_ITEM.set(item);
      PAUSED = false;
      PAUSE_STAMP = 0L;
      VipAuditLog.record(song.provider(), item.requesterName(), song.songId(),
            VipAuditLog.SOURCE_PLAY, VipAuditLog.RESULT_ALLOWED, "play");
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         dispatch(player, payload.sentAt(System.currentTimeMillis()));
         player.sendSystemMessage(prefix.copy()
            .append(Component.literal(song.name()))
            .append(Component.literal(" - "))
            .append(DisplayNames.artistComponent(song.performer())));
      }
      syncQueueAll(server);
   }

   /** 按服务商生成公开兜底音源。 */
   private static String fallbackStreamUrl(String provider, String songId) {
      return switch (provider == null ? "netease" : provider) {
         case "kugou" -> "https://www.kugou.com/yy/html/index.html#hash=" + songId + "&album_id=0";
         case "qq" -> "https://y.qq.com/n/ryqq/songDetail/" + songId;
         default -> "https://music.163.com/song/media/outer/url?id=" + songId + ".mp3";
      };
   }

   private static QueueItem pollNext() {
      if (QUEUE.isEmpty()) {
         return null;
      }
      if (MODE == 3 && QUEUE.size() != 1) {
         int target = ThreadLocalRandom.current().nextInt(QUEUE.size());
         Iterator<QueueItem> iterator = QUEUE.iterator();
         for (int i = 0; iterator.hasNext(); i++) {
            QueueItem item = iterator.next();
            if (i == target) {
               iterator.remove();
               return item;
            }
         }
         return QUEUE.pollFirst();
      }
      return QUEUE.pollFirst();
   }

   private static QueuePacket buildQueuePacket() {
      JsonObject root = new JsonObject();
      root.addProperty("mode", MODE);
      root.addProperty("hasPrevious", PAST.canRecall());
      JsonArray songs = new JsonArray();
      for (QueueItem item : QUEUE) {
         JsonObject song = new JsonObject();
         song.addProperty("id", item.track().songId());
         song.addProperty("title", item.track().name());
         song.addProperty("artist", item.track().performer());
         song.addProperty("duration", item.track().lengthMs());
         song.addProperty("provider", item.track().provider());
         song.addProperty("requester", item.requesterName());
         songs.add(song);
      }
      root.add("songs", songs);
      return new QueuePacket(root.toString());
   }

   private static boolean alreadyQueued(String provider, String songId) {
      String prov = provider == null ? "netease" : provider;
      QueueItem current = ACTIVE_ITEM.get();
      if (current != null && current.track().songId().equals(songId) && current.track().provider().equals(prov)) {
         return true;
      }
      for (QueueItem item : QUEUE) {
         if (item.track().songId().equals(songId) && item.track().provider().equals(prov)) {
            return true;
         }
      }
      return false;
   }

   private static void syncQueueAll(MinecraftServer server) {
      QueuePacket payload = buildQueuePacket();
      for (ServerPlayer player : SyncChannels.sharedListeners(server)) {
         dispatch(player, payload);
      }
   }

   public static void resyncPlayer(ServerPlayer player) {
      PlayPacket current = ACTIVE_SONG.get();
      if (current != null && (PAUSED || !expired(current))) {
         long now = System.currentTimeMillis();
         PlayPacket synchronizedSong = PAUSED ? current.shiftedBy(now - PAUSE_STAMP) : current;
         dispatch(player, synchronizedSong.sentAt(now));
      }
      dispatch(player, buildQueuePacket());
      if (current != null && PAUSED) {
         dispatch(player, new PauseStatePacket(true));
      }
   }

   private static int adjustVolume(ServerPlayer player, int action, int value) {
      dispatch(player, new VolumePacket(action, value));
      return 1;
   }

   private static int openHud(ServerPlayer player) {
      if (ServerPolicy.tuningNeedsAdmin && !AccessControl.isAdmin(player)) {
         player.sendSystemMessage(Component.translatable("yunyy.msg.permission_denied_tuning", ServerPolicy.adminRank));
         return 0;
      }
      dispatch(player, new HudPacket());
      return 1;
   }

   private static int openMenu(ServerPlayer player) {
      dispatch(player, new MenuOpenPacket());
      return 1;
   }

   private static int openPlaylistFolder(ServerPlayer player, String provider) {
      dispatch(player, new PlaylistOpenPacket(provider));
      return 1;
   }

   public static void importSongs(MinecraftServer server, ServerPlayer requester, String submittedJson) {
      long now = System.currentTimeMillis();
      if (now < IMPORT_GATE.getOrDefault(requester.getUUID(), 0L)) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.playlist_too_fast"));
         return;
      }
      IMPORT_GATE.put(requester.getUUID(), now + ServerPolicy.importCooldown);

      List<SongEntryBrief> submitted;
      try {
         submitted = decodeSongEntrys(submittedJson);
      } catch (RuntimeException e) {
         TraceLog.warn("玩家 {} 提交了无效的歌单数据", requester.getName().getString());
         requester.sendSystemMessage(Component.translatable("yunyy.msg.playlist_invalid"));
         return;
      }
      if (submitted.isEmpty()) {
         requester.sendSystemMessage(Component.translatable("yunyy.msg.playlist_empty"));
         return;
      }
      if (SyncChannels.rejectPlaylistFolderForPersonal(server, requester, submitted)) {
         return;
      }
      advanceQueue(server);
      int added = 0;
      boolean firstLaunched = false;
      for (SongEntryBrief song : submitted) {
         if (ACTIVE_SONG.get() != null && QUEUE.size() >= ServerPolicy.queueCap) {
            break;
         }
         if (!alreadyQueued(song.provider(), song.songId())) {
            QueueItem item = new QueueItem(song, requester.getUUID(), requester.getName().getString());
            if (ACTIVE_SONG.get() == null && !firstLaunched) {
               launchSong(server, item, Component.translatable("yunyy.msg.playlist_start"));
               firstLaunched = true;
            } else {
               if (countOwned(requester.getUUID()) >= ServerPolicy.maxPerPlayer) {
                  requester.sendSystemMessage(Component.translatable("yunyy.msg.queue_player_full", ServerPolicy.maxPerPlayer));
                  break;
               }
               QUEUE.addLast(item);
            }
            added++;
         }
      }
      int skipped = Math.max(0, submitted.size() - added);
      syncQueueAll(server);
      for (ServerPlayer online : SyncChannels.sharedListeners(server)) {
         online.sendSystemMessage(
            Component.translatable(skipped > 0 ? "yunyy.msg.playlist_skipped" : "yunyy.msg.playlist_imported", requester.getName().getString(), added, skipped)
         );
      }
   }

   private static List<SongEntryBrief> decodeSongEntrys(String json) {
      if (json == null || json.isBlank() || json.length() > 786432) {
         return List.of();
      }
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonArray array = root.getAsJsonArray("tracks");
      if (array == null) {
         return List.of();
      }
      Map<String, SongEntryBrief> unique = new LinkedHashMap<>();
      for (JsonElement value : array) {
         if (unique.size() >= ServerPolicy.queueCap) {
            break;
         }
         if (value.isJsonObject()) {
            SongEntryBrief song = decodeSongEntry(value.getAsJsonObject());
            if (song != null) {
               unique.putIfAbsent(song.songId(), song);
            }
         }
      }
      return List.copyOf(unique.values());
   }

   private static SongEntryBrief decodeSongEntry(JsonObject item) {
      if (item == null || !textField(item, "id") || !textField(item, "title")) {
         return null;
      }
      String id = item.get("id").getAsString();
      String provider = textField(item, "provider") ? item.get("provider").getAsString() : "netease";
      // 平台白名单：网易云 / 酷狗 / QQ 音乐，未知平台直接拒绝（不落入 fallback 拼接路径）
      if (!"netease".equals(provider) && !"kugou".equals(provider) && !"qq".equals(provider)) {
         return null;
      }
      if (!isPlausibleId(provider, id)) {
         return null;
      }
      String title = cleanText(item.get("title").getAsString(), 128, "");
      String artist = cleanText(textField(item, "artist") ? item.get("artist").getAsString() : "", 128, "");
      if (title.isBlank()) {
         return null;
      }
      long duration = numberField(item, "duration") ? item.get("duration").getAsLong() : 0L;
      if (duration < 1000L || duration > 7200000L) {
         duration = 0L;
      }
      String cover = textField(item, "cover") ? safeCoverUrl(item.get("cover").getAsString()) : "";
      return new SongEntryBrief(id, title, artist, duration, cover, provider);
   }

   /** 按服务商校验歌曲 ID。 */
   private static boolean isPlausibleId(String provider, String id) {
      if (id == null || id.isBlank()) {
         return false;
      }
      return switch (provider == null ? "netease" : provider) {
         // 网易云：纯数字；酷狗：32 位 hex hash（大小写均可）；QQ 音乐：字母数字 songmid
         case "kugou" -> id.toLowerCase().matches("[a-f0-9]{32}");
         case "qq" -> id.matches("[A-Za-z0-9]{6,40}");
         default -> id.matches("[1-9][0-9]{0,18}");
      };
   }

   private static String cleanText(String value, int maxLength, String fallback) {
      if (value == null) {
         return fallback;
      }
      String sanitized = value.replaceAll("[\\p{Cntrl}§]", " ").replaceAll("\\s+", " ").trim();
      if (sanitized.isBlank()) {
         return fallback;
      }
      return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
   }

   private static String safeCoverUrl(String value) {
      try {
         if (value == null || value.length() > 512) {
            return "";
         }
         URI uri = URI.create(value);
         String host = uri.getHost();
         boolean trusted = host != null
            && (
               host.equalsIgnoreCase("music.126.net")
                  || host.toLowerCase().endsWith(".music.126.net")
                  || host.equalsIgnoreCase("music.163.com")
                  || host.toLowerCase().endsWith(".music.163.com")
                  || host.equalsIgnoreCase("y.gtimg.cn")
                  || host.toLowerCase().endsWith(".y.gtimg.cn")
                  || host.equalsIgnoreCase("imge.kugou.com")
                  || host.toLowerCase().endsWith(".kugou.com")
                  || host.toLowerCase().endsWith(".kqcdn.com")
                  || host.toLowerCase().endsWith(".qq.com")
                  || host.toLowerCase().endsWith(".gtimg.cn")
            );
         if (!trusted || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            return "";
         }
         return "https".equalsIgnoreCase(uri.getScheme())
            ? uri.toASCIIString()
            : new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment()).toASCIIString();
      } catch (Exception e) {
         return "";
      }
   }

   private static int openAuth(ServerPlayer player, int action, String provider) {
      dispatch(player, new AuthPacket(action, provider));
      return 1;
   }

   public static boolean expired(PlayPacket song) {
      return song.durationMillis() > 0L && System.currentTimeMillis() >= song.startAtMillis() + song.durationMillis();
   }

   private static boolean textField(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive() && !object.get(key).getAsString().isBlank();
   }

   private static boolean numberField(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive() && object.get(key).getAsJsonPrimitive().isNumber();
   }

   /** 当前播放歌曲的 标题/歌手（用于歌词翻译兜底匹配），无当前歌时返回 null。 */
   public static String[] currentSongEntryMeta() {
      QueueItem item = ACTIVE_ITEM.get();
      if (item == null || item.track() == null) {
         return null;
      }
      return new String[] { item.track().name(), item.track().performer() };
   }

   public record QueueItem(SongEntryBrief track, UUID requesterUuid, String requesterName) {
   }

   public record SongEntryBrief(String songId, String name, String performer, long lengthMs, String coverUri, String provider) {
   }
}
