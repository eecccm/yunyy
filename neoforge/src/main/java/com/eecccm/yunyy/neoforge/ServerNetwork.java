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

package com.eecccm.yunyy.neoforge;

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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** NeoForge 服务端网络通道：payload 注册、命令与生命周期事件。 */
public final class ServerNetwork implements ServerCore.Net {
   public static final ServerNetwork INSTANCE = new ServerNetwork();

   /**
    * 客户端方向的 payload 分发入口。
    *
    * <p>payload 只能注册一次（同一 type 重复注册会被 NeoForge 拒绝），因此下行接收器在
    * 服务端注册阶段就绑定到这里；客户端初始化后安装真正的处理器，专用服务器恒为 null。
    */
   private static volatile Consumer<CustomPacketPayload> clientSink;

   private ServerNetwork() {
   }

   /** 由客户端入口安装下行分发器。 */
   public static void installClientSink(Consumer<CustomPacketPayload> sink) {
      clientSink = sink;
   }

   /** 下行转发：客户端未就绪（专用服务器）时静默丢弃。 */
   private static void forwardToClient(CustomPacketPayload payload) {
      Consumer<CustomPacketPayload> sink = clientSink;
      if (sink != null) {
         sink.accept(payload);
      }
   }

   /**
    * 服务端上下文的统一适配。
    *
    * <p>NeoForge 26.2 的 {@link IPayloadContext} 只暴露 {@code player()}，不再提供 {@code getSender()}；
    * 而 Minecraft 26.2 起 {@code ServerPlayer.server} 字段改为 private，服务器实例须经
    * {@code ServerPlayer.level()} 拿到所在维度后再调 {@code Level#getServer()}。
    * 所有 playToServer 接收器共用同一套取值逻辑，避免每处重复转型。
    */
   private static void withSender(IPayloadContext context,
                                  BiConsumer<MinecraftServer, ServerPlayer> action) {
      if (context.player() instanceof ServerPlayer player) {
         action.accept(serverOf(player), player);
      }
   }

   /**
    * 从服务端玩家反查所属服务器实例。
    *
    * <p>不同加载器的补丁对 {@code ServerPlayer.level()} 的返回类型收窄程度不一致
    * MC 26.2 的 {@code Level} 已直接暴露 {@code getServer()}，两个加载器的补丁
    * 对 {@code ServerPlayer.level()} 的返回类型收窄程度不同，这里统一走基类方法取值。
    */
   private static MinecraftServer serverOf(ServerPlayer player) {
      return player.level().getServer();
   }

   /** MOD 总线：注册全部 payload 编解码与服务端接收器。 */
   public static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("yunyy");
      registrar.playToClient(PlayPacket.TYPE, PlayPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(StopPacket.TYPE, StopPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(VolumePacket.TYPE, VolumePacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(AuthPacket.TYPE, AuthPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(HudPacket.TYPE, HudPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(QueuePacket.TYPE, QueuePacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(PlaylistOpenPacket.TYPE, PlaylistOpenPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(MenuOpenPacket.TYPE, MenuOpenPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(SearchOpenPacket.TYPE, SearchOpenPacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(ListeningStatePacket.TYPE, ListeningStatePacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToClient(PauseStatePacket.TYPE, PauseStatePacket.CODEC, (payload, ctx) -> forwardToClient(payload));
      registrar.playToServer(PlaylistImportPacket.TYPE, PlaylistImportPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.importSongs(server, sender, p.json())));
      registrar.playToServer(SelectSongPacket.TYPE, SelectSongPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.acceptSelection(server, sender, p.json())));
      registrar.playToServer(NextSongPacket.TYPE, NextSongPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.playNext(server, sender)));
      registrar.playToServer(ClearQueuePacket.TYPE, ClearQueuePacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.clearQueue(server, sender)));
      registrar.playToServer(QueueActionPacket.TYPE, QueueActionPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.handleQueueAction(server, sender, p.action(), p.index())));
      registrar.playToServer(PlaybackModePacket.TYPE, PlaybackModePacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.applyPlaybackMode(server, sender, p.mode())));
      registrar.playToServer(ListeningActionPacket.TYPE, ListeningActionPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> SyncChannels.dispatch(server, sender, p.action(), p.value())));
      registrar.playToServer(PauseActionPacket.TYPE, PauseActionPacket.CODEC,
         (p, ctx) -> withSender(ctx, (server, sender) -> ServerCore.flipPause(server, sender)));
   }

   /** 游戏总线事件接线（命令、tick、连接、生命周期）。 */
   public static void registerGameEvents(IEventBus bus) {
      bus.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(ServerCore.commandTree()));
      bus.addListener((ServerTickEvent event) -> ServerCore.onTick(event.getServer()));
      bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            ServerCore.onPlayerJoin(serverOf(player), player);
         }
      });
      bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            ServerCore.onPlayerLeave(serverOf(player), player);
         }
      });
      bus.addListener((ServerStoppedEvent event) -> ServerCore.reset());
   }

   @Override
   public void send(ServerPlayer player, CustomPacketPayload payload) {
      PacketDistributor.sendToPlayer(player, payload);
   }
}
