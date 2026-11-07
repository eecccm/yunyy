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

package com.eecccm.yunyy.forge;

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
import com.eecccm.yunyy.mccore.server.ServerCore;
import com.eecccm.yunyy.mccore.server.SyncChannels;
import com.eecccm.yunyy.mccore.util.PacketIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadFlow;
import net.minecraftforge.network.payload.PayloadProtocol;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Forge 服务端网络通道：通道构建、payload 注册、命令与生命周期事件。
 *
 * <p>Forge 26.2 的网络层由 {@code ChannelBuilder} 一次性成型（旧的 IPayloadRegistrar /
 * RegisterPayloadHandlersEvent 已移除），因此本类承担三件事：
 * 构建唯一 play 通道、绑定全部 payload 编解码与接收器、把通道对象交给客户端复用。
 */
public final class ServerNetwork implements ServerCore.Net {
   public static final ServerNetwork INSTANCE = new ServerNetwork();
   /** 通道标识（与 modid 对齐）。 */
   private static final String CHANNEL_ID = "main";
   /** 协议版本：payload 结构变更时必须递增，否则新旧端握手直接拒绝。 */
   private static final int PROTOCOL_VERSION = 1;

   /**
    * 客户端方向的 payload 分发入口。
    *
    * <p>专用服务器上恒为 null —— 通道构建早于客户端初始化，这里用纯 {@link Consumer}
    * 承接而不引用任何客户端类，避免专服加载 {@code net.minecraft.client} 相关类型。
    */
   private static volatile Consumer<CustomPacketPayload> clientSink;
   private static volatile Channel<CustomPacketPayload> channel;

   private ServerNetwork() {
   }

   /** 由客户端入口安装下行分发器。 */
   public static void installClientSink(Consumer<CustomPacketPayload> sink) {
      clientSink = sink;
   }

   /** 惰性获取通道（首次调用时构建，之后复用同一实例）。 */
   public static Channel<CustomPacketPayload> channel() {
      Channel<CustomPacketPayload> built = channel;
      if (built == null) {
         synchronized (ServerNetwork.class) {
            built = channel;
            if (built == null) {
               built = buildChannel();
               channel = built;
            }
         }
      }
      return built;
   }

   /**
    * 构建 play 阶段通道并注册全部 payload。
    *
    * <p>Forge 26.2 的 handler 在构建期一次性绑定，无法像 NeoForge 那样分注册期/游戏期两段，
    * 因此下行统一转发到 {@link #clientSink}，上行统一走 {@link #withSender}。
    */
   private static Channel<CustomPacketPayload> buildChannel() {
      PayloadProtocol<RegistryFriendlyByteBuf, CustomPacketPayload> protocol = ChannelBuilder
         .named(PacketIds.id(CHANNEL_ID))
         .networkProtocolVersion(PROTOCOL_VERSION)
         .payloadChannel()
         .play();
      PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow = protocol.bidirectional();
      flow = flow.addMain(PlayPacket.TYPE, PlayPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(StopPacket.TYPE, StopPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(VolumePacket.TYPE, VolumePacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(AuthPacket.TYPE, AuthPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(HudPacket.TYPE, HudPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(QueuePacket.TYPE, QueuePacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(PlaylistOpenPacket.TYPE, PlaylistOpenPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(MenuOpenPacket.TYPE, MenuOpenPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(SearchOpenPacket.TYPE, SearchOpenPacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(ListeningStatePacket.TYPE, ListeningStatePacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(PauseStatePacket.TYPE, PauseStatePacket.CODEC, (payload, context) -> forwardToClient(payload));
      flow = flow.addMain(PlaylistImportPacket.TYPE, PlaylistImportPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.importSongs(server, sender, payload.json())));
      flow = flow.addMain(SelectSongPacket.TYPE, SelectSongPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.acceptSelection(server, sender, payload.json())));
      flow = flow.addMain(NextSongPacket.TYPE, NextSongPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.playNext(server, sender)));
      flow = flow.addMain(ClearQueuePacket.TYPE, ClearQueuePacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.clearQueue(server, sender)));
      flow = flow.addMain(QueueActionPacket.TYPE, QueueActionPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.handleQueueAction(server, sender, payload.action(), payload.index())));
      flow = flow.addMain(PlaybackModePacket.TYPE, PlaybackModePacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.applyPlaybackMode(server, sender, payload.mode())));
      flow = flow.addMain(ListeningActionPacket.TYPE, ListeningActionPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> SyncChannels.dispatch(server, sender, payload.action(), payload.value())));
      flow = flow.addMain(PauseActionPacket.TYPE, PauseActionPacket.CODEC,
         (payload, context) -> withSender(context, (server, sender) -> ServerCore.flipPause(server, sender)));
      return flow.build();
   }

   /** 下行转发：客户端未就绪（专用服务器）时静默丢弃。 */
   private static void forwardToClient(CustomPacketPayload payload) {
      Consumer<CustomPacketPayload> sink = clientSink;
      if (sink != null) {
         sink.accept(payload);
      }
   }

   /**
    * 上行上下文适配。
    *
    * <p>Minecraft 26.2 起 {@code ServerPlayer.server} 字段改为 private，服务器实例须经
    * {@code ServerPlayer.level()} 拿到所在维度后再调 {@code Level#getServer()}。
    */
   private static void withSender(CustomPayloadEvent.Context context,
                                  BiConsumer<MinecraftServer, ServerPlayer> action) {
      ServerPlayer sender = context.getSender();
      if (sender != null) {
         action.accept(serverOf(sender), sender);
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

   /**
    * 游戏总线事件接线。
    *
    * <p>EventBus 7 为每种事件提供独立的静态 {@code BUS}，旧的统一 {@code MinecraftForge.EVENT_BUS}
    * 已降级为迁移辅助器，因此这里逐个事件订阅。
    */
   public static void registerGameEvents() {
      RegisterCommandsEvent.BUS.addListener((RegisterCommandsEvent event) ->
         event.getDispatcher().register(ServerCore.commandTree()));
      // 26.2 的 ServerTickEvent 拆为 Pre/Post 两个具体事件，取 Post 保持每 tick 一次
      TickEvent.ServerTickEvent.Post.BUS.addListener((TickEvent.ServerTickEvent.Post event) ->
         ServerCore.onTick(event.server()));
      PlayerEvent.PlayerLoggedInEvent.BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            ServerCore.onPlayerJoin(serverOf(player), player);
         }
      });
      PlayerEvent.PlayerLoggedOutEvent.BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
         if (event.getEntity() instanceof ServerPlayer player) {
            ServerCore.onPlayerLeave(serverOf(player), player);
         }
      });
      ServerStoppedEvent.BUS.addListener((ServerStoppedEvent event) -> ServerCore.reset());
   }

   @Override
   public void send(ServerPlayer player, CustomPacketPayload payload) {
      channel().send(payload, PacketDistributor.PLAYER.with(player));
   }
}
