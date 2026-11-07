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

package com.eecccm.yunyy.forge.client;

import com.eecccm.yunyy.forge.ServerNetwork;
import com.eecccm.yunyy.mccore.client.ClientNet;
import com.eecccm.yunyy.mccore.client.ClientNet.Handler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraftforge.network.PacketDistributor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge 客户端网络通道：实现共享核心的客户端网络抽象。
 *
 * <p>Forge 26.2 的通道在构建期一次性绑定 handler，无法像 NeoForge 那样把下行接收器
 * 留到客户端初始化再补注册。因此这里改为「先登记、后分发」：{@link #register} 把每个
 * payload 类型的处理器收进查找表，通道的下行 sink 统一投递到 {@link #deliver}，
 * 运行期按 payload 自带的 {@code type()} 精确路由。
 */
public final class ClientNetwork implements ClientNet.Impl {
   public static final ClientNetwork INSTANCE = new ClientNetwork();
   /** payload 类型 -> 客户端处理器；键为类型标识而非 Class，避免同一类多实例的歧义。 */
   private final Map<Type<?>, Handler<CustomPacketPayload>> handlers = new ConcurrentHashMap<>();

   private ClientNetwork() {
   }

   /** 把本实例挂到服务端通道的下行分发入口（仅在客户端初始化阶段调用）。 */
   public void attach() {
      ServerNetwork.installClientSink(this::deliver);
   }

   @Override
   public <T extends CustomPacketPayload> void register(Type<T> type, Handler<T> handler) {
      @SuppressWarnings("unchecked")
      Handler<CustomPacketPayload> cast = (Handler<CustomPacketPayload>) (Handler<?>) handler;
      handlers.put(type, cast);
   }

   /** 下行分发：类型未登记时静默忽略，避免单个未知 payload 影响整条通道。 */
   private void deliver(CustomPacketPayload payload) {
      Handler<CustomPacketPayload> handler = handlers.get(payload.type());
      if (handler != null) {
         handler.receive(payload, Minecraft.getInstance());
      }
   }

   @Override
   public void send(Object payload) {
      // Forge 26.2 的上行发送统一走通道对象的 send(MSG, PacketTarget)
      ServerNetwork.channel().send((CustomPacketPayload) payload, PacketDistributor.SERVER.noArg());
   }

   @Override
   public boolean supportsListeningModes() {
      return true;
   }
}
