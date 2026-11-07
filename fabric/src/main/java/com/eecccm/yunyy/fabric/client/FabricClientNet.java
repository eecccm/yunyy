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

package com.eecccm.yunyy.fabric.client;

import com.eecccm.yunyy.mccore.client.ClientNet;
import com.eecccm.yunyy.mccore.net.ListeningActionPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;

/** Fabric 客户端网络通道：实现共享核心的客户端网络抽象。 */
public final class FabricClientNet implements ClientNet.Impl {
   public static final FabricClientNet INSTANCE = new FabricClientNet();

   private FabricClientNet() {
   }

   @Override
   public <T extends CustomPacketPayload> void register(Type<T> type, ClientNet.Handler<T> handler) {
      ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.receive(payload, context.client()));
   }

   @Override
   public void send(Object payload) {
      CustomPacketPayload packet = (CustomPacketPayload) payload;
      if (ClientPlayNetworking.canSend(packet.type())) {
         ClientPlayNetworking.send(packet);
      }
   }

   @Override
   public boolean supportsListeningModes() {
      return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(ListeningActionPacket.TYPE);
   }
}
