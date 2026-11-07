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

package com.eecccm.yunyy.fabric;

import com.eecccm.yunyy.mccore.net.AuthPacket;
import com.eecccm.yunyy.mccore.net.ClearQueuePacket;
import com.eecccm.yunyy.mccore.net.HudPacket;
import com.eecccm.yunyy.mccore.net.ListeningActionPacket;
import com.eecccm.yunyy.mccore.net.ListeningStatePacket;
import com.eecccm.yunyy.mccore.net.MenuOpenPacket;
import com.eecccm.yunyy.mccore.net.NextSongPacket;
import com.eecccm.yunyy.mccore.net.PauseActionPacket;
import com.eecccm.yunyy.mccore.net.PauseStatePacket;
import com.eecccm.yunyy.mccore.net.PlayPacket;
import com.eecccm.yunyy.mccore.net.PlaybackModePacket;
import com.eecccm.yunyy.mccore.net.PlaylistImportPacket;
import com.eecccm.yunyy.mccore.net.PlaylistOpenPacket;
import com.eecccm.yunyy.mccore.net.QueueActionPacket;
import com.eecccm.yunyy.mccore.net.QueuePacket;
import com.eecccm.yunyy.mccore.net.SearchOpenPacket;
import com.eecccm.yunyy.mccore.net.SelectSongPacket;
import com.eecccm.yunyy.mccore.net.StopPacket;
import com.eecccm.yunyy.mccore.net.VolumePacket;
import com.eecccm.yunyy.mccore.server.SyncChannels;
import com.eecccm.yunyy.mccore.server.ServerCore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Fabric 服务端网络通道：payload 注册与收发，实现共享核心的 Net 抽象。 */
public final class FabricServerNet implements ServerCore.Net {
   public static final FabricServerNet INSTANCE = new FabricServerNet();

   private FabricServerNet() {
   }

   public static void initialize() {
      ChannelRegistrar.registerClientbound(PlayPacket.TYPE, PlayPacket.CODEC);
      ChannelRegistrar.registerClientbound(StopPacket.TYPE, StopPacket.CODEC);
      ChannelRegistrar.registerClientbound(VolumePacket.TYPE, VolumePacket.CODEC);
      ChannelRegistrar.registerClientbound(AuthPacket.TYPE, AuthPacket.CODEC);
      ChannelRegistrar.registerClientbound(HudPacket.TYPE, HudPacket.CODEC);
      ChannelRegistrar.registerClientbound(QueuePacket.TYPE, QueuePacket.CODEC);
      ChannelRegistrar.registerClientbound(PlaylistOpenPacket.TYPE, PlaylistOpenPacket.CODEC);
      ChannelRegistrar.registerServerbound(PlaylistImportPacket.TYPE, PlaylistImportPacket.CODEC);
      ChannelRegistrar.registerClientbound(MenuOpenPacket.TYPE, MenuOpenPacket.CODEC);
      ChannelRegistrar.registerClientbound(SearchOpenPacket.TYPE, SearchOpenPacket.CODEC);
      ChannelRegistrar.registerServerbound(SelectSongPacket.TYPE, SelectSongPacket.CODEC);
      ChannelRegistrar.registerServerbound(NextSongPacket.TYPE, NextSongPacket.CODEC);
      ChannelRegistrar.registerServerbound(ClearQueuePacket.TYPE, ClearQueuePacket.CODEC);
      ChannelRegistrar.registerServerbound(QueueActionPacket.TYPE, QueueActionPacket.CODEC);
      ChannelRegistrar.registerServerbound(PlaybackModePacket.TYPE, PlaybackModePacket.CODEC);
      ChannelRegistrar.registerServerbound(ListeningActionPacket.TYPE, ListeningActionPacket.CODEC);
      ChannelRegistrar.registerClientbound(ListeningStatePacket.TYPE, ListeningStatePacket.CODEC);
      ChannelRegistrar.registerServerbound(PauseActionPacket.TYPE, PauseActionPacket.CODEC);
      ChannelRegistrar.registerClientbound(PauseStatePacket.TYPE, PauseStatePacket.CODEC);
      ServerPlayNetworking.registerGlobalReceiver(
         PlaylistImportPacket.TYPE, (payload, context) -> ServerCore.importSongs(context.server(), context.player(), payload.json())
      );
      ServerPlayNetworking.registerGlobalReceiver(
         SelectSongPacket.TYPE, (payload, context) -> ServerCore.acceptSelection(context.server(), context.player(), payload.json())
      );
      ServerPlayNetworking.registerGlobalReceiver(NextSongPacket.TYPE, (payload, context) -> ServerCore.playNext(context.server(), context.player()));
      ServerPlayNetworking.registerGlobalReceiver(ClearQueuePacket.TYPE, (payload, context) -> ServerCore.clearQueue(context.server(), context.player()));
      ServerPlayNetworking.registerGlobalReceiver(
         QueueActionPacket.TYPE, (payload, context) -> ServerCore.handleQueueAction(context.server(), context.player(), payload.action(), payload.index())
      );
      ServerPlayNetworking.registerGlobalReceiver(
         PlaybackModePacket.TYPE, (payload, context) -> ServerCore.applyPlaybackMode(context.server(), context.player(), payload.mode())
      );
      ServerPlayNetworking.registerGlobalReceiver(
         ListeningActionPacket.TYPE,
         (payload, context) -> SyncChannels.dispatch(context.server(), context.player(), payload.action(), payload.value())
      );
      ServerPlayNetworking.registerGlobalReceiver(PauseActionPacket.TYPE, (payload, context) -> ServerCore.flipPause(context.server(), context.player()));
   }

   @Override
   public void send(ServerPlayer player, CustomPacketPayload payload) {
      ServerPlayNetworking.send(player, payload);
   }
}
